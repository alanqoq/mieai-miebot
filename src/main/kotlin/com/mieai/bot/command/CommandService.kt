package com.mieai.bot.command

import com.mieai.bot.config.MieAiConfigStore
import com.mieai.bot.history.HistoryDatabase
import com.mieai.qqbot.plugin.api.GroupMemberRole
import java.util.UUID

class CommandService(
    private val configStore: MieAiConfigStore,
    private val database: HistoryDatabase,
) {
    private val commandLock = Any()

    fun handle(
        eventId: UUID,
        groupId: String,
        memberRole: GroupMemberRole?,
        content: String,
    ): CommandOutcome = synchronized(commandLock) {
        handleLocked(eventId, groupId, memberRole, content)
    }

    private fun handleLocked(
        eventId: UUID,
        groupId: String,
        memberRole: GroupMemberRole?,
        content: String,
    ): CommandOutcome {
        val parsed = parse(content) ?: return CommandOutcome.NotCommand
        if (memberRole != GroupMemberRole.ADMIN && memberRole != GroupMemberRole.OWNER) {
            return CommandOutcome.Handled("仅群管理员或群主可以使用此指令。")
        }
        val config = configStore.snapshot()
        val desired = when (parsed.name) {
            "prob" -> {
                val probability = parsed.argument.toIntOrNull()
                if (probability == null || probability !in 1..100) {
                    return CommandOutcome.Handled("用法：/mieai prob <1-100>")
                }
                DesiredCommand("prob", probability.toString())
            }
            "prompt" -> {
                val prompt = parsed.argument.trim()
                if (prompt.isEmpty()) return CommandOutcome.Handled("用法：/mieai prompt <提示词>")
                if (codePointLength(prompt) > config.chat.promptMaxLength) {
                    return CommandOutcome.Handled(config.chat.promptTooLongReply)
                }
                DesiredCommand("prompt", prompt)
            }
            "keyword" -> {
                val keyword = parsed.argument.trim()
                if (keyword.isEmpty()) return CommandOutcome.Handled("用法：/mieai keyword <关键词>")
                if (codePointLength(keyword) > config.chat.keywordMaxLength) {
                    return CommandOutcome.Handled(config.chat.keywordTooLongReply)
                }
                DesiredCommand("keyword", keyword)
            }
            "chat" -> {
                if (parsed.argument.isNotEmpty()) return CommandOutcome.Handled("用法：/mieai chat")
                DesiredCommand("chat", (!config.chat.disabledGroups.contains(groupId)).toString())
            }
            else -> return CommandOutcome.Handled(
                "可用指令：/mieai prob、/mieai prompt、/mieai keyword、/mieai chat",
            )
        }

        val plan = database.commandPlan(eventId, desired.kind, desired.value)
        val disabled = applyPlan(groupId, plan.kind, plan.value)
        database.completeCommandPlan(eventId)
        if (disabled == true) database.cancelPendingTasks(groupId)
        return CommandOutcome.Handled(successReply(plan.kind, plan.value), disabled)
    }

    private fun applyPlan(groupId: String, kind: String, value: String): Boolean? {
        var disabled: Boolean? = null
        configStore.update { config ->
            when (kind) {
                "prob" -> config.copy(
                    chat = config.chat.copy(groupProbabilities = config.chat.groupProbabilities + (groupId to value.toInt())),
                )
                "prompt" -> config.copy(
                    chat = config.chat.copy(groupSystemPrompts = config.chat.groupSystemPrompts + (groupId to value)),
                )
                "keyword" -> config.copy(
                    chat = config.chat.copy(groupKeywords = config.chat.groupKeywords + (groupId to value)),
                )
                "chat" -> {
                    disabled = value.toBooleanStrict()
                    val groups = if (disabled == true) {
                        config.chat.disabledGroups + groupId
                    } else {
                        config.chat.disabledGroups - groupId
                    }
                    config.copy(chat = config.chat.copy(disabledGroups = groups))
                }
                else -> error("Unknown persisted command kind: $kind")
            }
        }
        return disabled
    }

    private fun successReply(kind: String, value: String): String = when (kind) {
        "prob" -> "当前群聊天概率已设置为 $value%。"
        "prompt" -> "当前群系统提示词已更新。"
        "keyword" -> "当前群触发关键词已更新。"
        "chat" -> if (value.toBoolean()) "当前群 AI 聊天已禁用。" else "当前群 AI 聊天已启用。"
        else -> "设置已更新。"
    }

    private fun parse(content: String): ParsedCommand? {
        val trimmedStart = content.trimStart()
        if (!trimmedStart.startsWith(PREFIX, ignoreCase = true)) return null
        if (trimmedStart.length > PREFIX.length && !trimmedStart[PREFIX.length].isWhitespace()) return null
        val remainder = trimmedStart.substring(PREFIX.length).trim()
        if (remainder.isEmpty()) return ParsedCommand("", "")
        val boundary = remainder.indexOfFirst(Char::isWhitespace)
        val name = (if (boundary < 0) remainder else remainder.substring(0, boundary)).lowercase()
        val argument = if (boundary < 0) "" else remainder.substring(boundary).trim()
        return ParsedCommand(name, argument)
    }

    private data class ParsedCommand(val name: String, val argument: String)
    private data class DesiredCommand(val kind: String, val value: String)

    private companion object {
        const val PREFIX = "/mieai"
        fun codePointLength(value: String): Int = value.codePointCount(0, value.length)
    }
}

sealed interface CommandOutcome {
    data object NotCommand : CommandOutcome
    data class Handled(val reply: String, val disabledNow: Boolean? = null) : CommandOutcome
}
