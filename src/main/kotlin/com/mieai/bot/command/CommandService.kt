package com.mieai.bot.command

import com.mieai.bot.config.CommandAliasesConfig
import com.mieai.bot.config.MieAiConfigStore
import com.mieai.bot.history.HistoryDatabase
import com.mieai.qqbot.plugin.api.GroupMemberRole
import java.util.Locale
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
        val config = configStore.snapshot()
        val parsed = parse(content, config.commands) ?: return CommandOutcome.NotCommand
        if (parsed.name.isEmpty() || parsed.name == "help") {
            return CommandOutcome.Handled(helpReply(config.commands))
        }
        if (memberRole != GroupMemberRole.ADMIN && memberRole != GroupMemberRole.OWNER) {
            return CommandOutcome.Handled("仅群管理员或群主可以使用此指令。")
        }
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
            else -> return CommandOutcome.Handled(helpReply(config.commands))
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

    private fun helpReply(aliases: CommandAliasesConfig): String = buildString {
        appendLine("可用指令（查看帮助不限成员，修改群设置仅限群管理员和群主）：")
        appendHelp("/mieai", aliases.mainAlias, "", "显示这份指令帮助。", mainAlias = true)
        appendHelp("/mieai help", aliases.helpAlias, "", "显示这份完整指令帮助，不修改任何群设置。")
        appendHelp(
            "/mieai prob <1-100>",
            aliases.probAlias,
            " <1-100>",
            "设置当前群 AI 聊天概率，数值越高越容易通过概率触发。",
        )
        appendHelp(
            "/mieai prompt <提示词>",
            aliases.promptAlias,
            " <提示词>",
            "设置当前群独立系统提示词，影响 AI 回复风格和规则。",
        )
        appendHelp(
            "/mieai keyword <关键词>",
            aliases.keywordAlias,
            " <关键词>",
            "设置当前群独立触发关键词，并在当前群覆盖默认关键词。",
        )
        appendHelp(
            "/mieai chat",
            aliases.chatAlias,
            "",
            "切换当前群 AI 聊天的启用和禁用状态，禁用后所有触发方式都会停止。",
        )
    }.trimEnd()

    private fun StringBuilder.appendHelp(
        command: String,
        alias: String,
        aliasArguments: String,
        description: String,
        mainAlias: Boolean = false,
    ) {
        appendLine("$command：$description")
        if (alias.isEmpty()) return
        if (mainAlias) {
            appendLine("  主指令别名：/$alias（可替代 /mieai 前缀，例如 /$alias prob 50）。")
        } else {
            appendLine("  指令别名：/$alias$aliasArguments")
        }
    }

    private fun parse(content: String, aliases: CommandAliasesConfig): ParsedCommand? {
        val trimmedStart = content.trimStart()
        if (!trimmedStart.startsWith('/')) return null
        val boundary = trimmedStart.indexOfFirst(Char::isWhitespace)
        val invocation = if (boundary < 0) trimmedStart else trimmedStart.substring(0, boundary)
        val invokedName = invocation.drop(1)
        if (invokedName.isEmpty()) return null
        val remainder = if (boundary < 0) "" else trimmedStart.substring(boundary).trim()

        if (invokedName.equals(MAIN_COMMAND, ignoreCase = true) ||
            invokedName.matchesAlias(aliases.mainAlias)
        ) {
            return parseSubcommand(remainder)
        }

        val directName = when {
            invokedName.matchesAlias(aliases.helpAlias) -> "help"
            invokedName.matchesAlias(aliases.probAlias) -> "prob"
            invokedName.matchesAlias(aliases.promptAlias) -> "prompt"
            invokedName.matchesAlias(aliases.keywordAlias) -> "keyword"
            invokedName.matchesAlias(aliases.chatAlias) -> "chat"
            else -> return null
        }
        return ParsedCommand(directName, remainder)
    }

    private fun parseSubcommand(remainder: String): ParsedCommand {
        if (remainder.isEmpty()) return ParsedCommand("", "")
        val boundary = remainder.indexOfFirst(Char::isWhitespace)
        val name = (if (boundary < 0) remainder else remainder.substring(0, boundary)).lowercase(Locale.ROOT)
        val argument = if (boundary < 0) "" else remainder.substring(boundary).trim()
        return ParsedCommand(name, argument)
    }

    private fun String.matchesAlias(alias: String): Boolean =
        alias.isNotEmpty() && equals(alias, ignoreCase = true)

    private data class ParsedCommand(val name: String, val argument: String)
    private data class DesiredCommand(val kind: String, val value: String)

    private companion object {
        const val MAIN_COMMAND = "mieai"
        fun codePointLength(value: String): Int = value.codePointCount(0, value.length)
    }
}

sealed interface CommandOutcome {
    data object NotCommand : CommandOutcome
    data class Handled(val reply: String, val disabledNow: Boolean? = null) : CommandOutcome
}
