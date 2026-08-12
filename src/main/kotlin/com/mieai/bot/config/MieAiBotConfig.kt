package com.mieai.bot.config

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import java.net.URI
import java.util.Collections
import java.util.SortedMap
import java.util.TreeMap
import java.util.TreeSet

enum class AiProtocol(@get:JsonValue val configValue: String) {
    OPENAI_OLD("openai-old"),
    OPENAI_NEW("openai-new");

    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun fromConfigValue(value: String): AiProtocol = entries.firstOrNull { it.configValue == value }
            ?: throw ConfigValidationException("api.protocol", "只能填写 openai-old 或 openai-new")
    }
}

data class CommandAliasesConfig(
    val mainAlias: String = "",
    val helpAlias: String = "",
    val probAlias: String = "",
    val promptAlias: String = "",
    val keywordAlias: String = "",
    val chatAlias: String = "",
) {
    init {
        val configured = entries().filter { (_, alias) -> alias.isNotEmpty() }
        configured.forEach { (field, alias) ->
            requireCommandAlias(field, alias)
            requireConfig(!alias.equals("mieai", ignoreCase = true), field, "不能与内置主指令 mieai 冲突")
        }

        configured.forEachIndexed { index, (field, alias) ->
            val previous = configured.take(index).firstOrNull { (_, other) ->
                alias.equals(other, ignoreCase = true)
            }
            requireConfig(previous == null, field, "与 ${previous?.first} 重复；所有指令别名必须唯一且不区分大小写")
        }
    }

    internal fun entries(): List<Pair<String, String>> = listOf(
        "commands.mainAlias" to mainAlias,
        "commands.helpAlias" to helpAlias,
        "commands.probAlias" to probAlias,
        "commands.promptAlias" to promptAlias,
        "commands.keywordAlias" to keywordAlias,
        "commands.chatAlias" to chatAlias,
    )
}

data class ApiConfig(
    val baseUrl: String,
    val apiKey: String,
    val protocol: AiProtocol,
    val primaryModel: String,
    val requestTimeoutSeconds: Int,
    val fallbackModel: String,
    val fallbackDurationMinutes: Int,
) {
    init {
        validateBaseUrl(baseUrl)
        requireTrimmed("api.apiKey", apiKey, allowBlank = true, maxCodePoints = 8192)
        requireConfig(apiKey.codePoints().noneMatch(Character::isISOControl), "api.apiKey", "不能包含控制字符")
        requireToken("api.primaryModel", primaryModel, 255)
        requireConfig(requestTimeoutSeconds in 1..3_600, "api.requestTimeoutSeconds", "必须在 1 到 3600 之间")
        if (fallbackModel.isNotEmpty()) requireToken("api.fallbackModel", fallbackModel, 255)
        requireConfig(
            fallbackDurationMinutes in 1..525_600,
            "api.fallbackDurationMinutes",
            "必须在 1 到 525600 之间",
        )
    }
}

data class ChatConfig(
    val defaultProbability: Int,
    val groupProbabilities: Map<String, Int>,
    val defaultSystemPrompt: String,
    val groupSystemPrompts: Map<String, String>,
    val promptMaxLength: Int,
    val promptTooLongReply: String,
    val defaultKeyword: String,
    val groupKeywords: Map<String, String>,
    val keywordMaxLength: Int,
    val keywordTooLongReply: String,
    val disabledGroups: Set<String>,
    val imageUnderstandingEnabled: Boolean,
    val maxContextMessages: Int,
) {
    init {
        requireConfig(defaultProbability in 0..100, "chat.defaultProbability", "必须在 0 到 100 之间")
        requireConfig(promptMaxLength in 1..100_000, "chat.promptMaxLength", "必须在 1 到 100000 之间")
        requireConfig(keywordMaxLength in 1..10_000, "chat.keywordMaxLength", "必须在 1 到 10000 之间")
        requireConfig(maxContextMessages in 0..10_000, "chat.maxContextMessages", "必须在 0 到 10000 之间")

        requirePrompt("chat.defaultSystemPrompt", defaultSystemPrompt, 100_000)
        requireReply("chat.promptTooLongReply", promptTooLongReply)
        requireKeyword("chat.defaultKeyword", defaultKeyword, keywordMaxLength)
        requireReply("chat.keywordTooLongReply", keywordTooLongReply)

        groupProbabilities.forEach { (groupId, probability) ->
            requireGroupId("chat.groupProbabilities", groupId)
            requireConfig(probability in 1..100, "chat.groupProbabilities[$groupId]", "必须在 1 到 100 之间")
        }
        groupSystemPrompts.forEach { (groupId, prompt) ->
            requireGroupId("chat.groupSystemPrompts", groupId)
            requirePrompt("chat.groupSystemPrompts[$groupId]", prompt, promptMaxLength)
        }
        groupKeywords.forEach { (groupId, keyword) ->
            requireGroupId("chat.groupKeywords", groupId)
            requireKeyword("chat.groupKeywords[$groupId]", keyword, keywordMaxLength)
        }
        disabledGroups.forEach { requireGroupId("chat.disabledGroups", it) }
    }

    fun probabilityFor(groupId: String): Int = groupProbabilities[groupId] ?: defaultProbability

    fun systemPromptFor(groupId: String): String = groupSystemPrompts[groupId] ?: defaultSystemPrompt

    fun keywordFor(groupId: String): String = groupKeywords[groupId] ?: defaultKeyword

    fun isChatEnabled(groupId: String): Boolean = groupId !in disabledGroups
}

data class StorageConfig(
    val maxBase64ImageBytes: Long,
    val maxMessagesTotal: Int,
    val defaultMaxMessagesPerGroup: Int,
    val groupMaxMessages: Map<String, Int>,
    val maxMessageAgeDays: Int,
    val cleanupDeleteBatchSize: Int,
    val cleanupTime: String,
) {
    init {
        requireConfig(maxBase64ImageBytes >= 0L, "storage.maxBase64ImageBytes", "不能小于 0")
        requireConfig(maxMessagesTotal >= 0, "storage.maxMessagesTotal", "不能小于 0")
        requireConfig(defaultMaxMessagesPerGroup >= 0, "storage.defaultMaxMessagesPerGroup", "不能小于 0")
        requireConfig(maxMessageAgeDays >= 0, "storage.maxMessageAgeDays", "不能小于 0")
        requireConfig(cleanupDeleteBatchSize > 0, "storage.cleanupDeleteBatchSize", "必须大于 0")
        requireConfig(CLEANUP_TIME.matches(cleanupTime), "storage.cleanupTime", "必须使用 HH:mm 形式的有效 24 小时时间")
        groupMaxMessages.forEach { (groupId, maximum) ->
            requireGroupId("storage.groupMaxMessages", groupId)
            requireConfig(maximum >= 0, "storage.groupMaxMessages[$groupId]", "不能小于 0")
        }
    }

    private companion object {
        val CLEANUP_TIME = Regex("(?:[01]\\d|2[0-3]):[0-5]\\d")
    }
}

data class QueueConfig(
    val maxPendingPerGroup: Int,
) {
    init {
        requireConfig(maxPendingPerGroup > 0, "queue.maxPendingPerGroup", "必须大于 0")
    }
}

data class MieAiBotConfig(
    val api: ApiConfig,
    val chat: ChatConfig,
    val storage: StorageConfig,
    val queue: QueueConfig,
    val commands: CommandAliasesConfig = CommandAliasesConfig(),
) {
    fun immutableCopy(): MieAiBotConfig = copy(
        chat = chat.copy(
            groupProbabilities = immutableSortedMap(chat.groupProbabilities),
            groupSystemPrompts = immutableSortedMap(chat.groupSystemPrompts),
            groupKeywords = immutableSortedMap(chat.groupKeywords),
            disabledGroups = immutableSortedSet(chat.disabledGroups),
        ),
        storage = storage.copy(groupMaxMessages = immutableSortedMap(storage.groupMaxMessages)),
    )
}

class ConfigValidationException(
    val field: String,
    detail: String,
) : IllegalArgumentException("$field: $detail")

private fun validateBaseUrl(value: String) {
    requireTrimmed("api.baseUrl", value, allowBlank = false, maxCodePoints = 2_048)
    val uri = try {
        URI(value)
    } catch (_: IllegalArgumentException) {
        throw ConfigValidationException("api.baseUrl", "必须是有效的 HTTP(S) 域名")
    }
    requireConfig(uri.scheme == "http" || uri.scheme == "https", "api.baseUrl", "只允许小写 http 或 https")
    requireConfig(!uri.host.isNullOrBlank(), "api.baseUrl", "必须包含有效域名或 IP 地址")
    requireConfig(uri.port == -1 || uri.port in 1..65_535, "api.baseUrl", "端口必须在 1 到 65535 之间")
    requireConfig(uri.rawAuthority?.endsWith(':') != true, "api.baseUrl", "端口不能为空")
    requireConfig(uri.userInfo == null, "api.baseUrl", "不能包含账号信息")
    requireConfig(uri.query == null && uri.fragment == null, "api.baseUrl", "不能包含查询参数或片段")
    requireConfig(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/", "api.baseUrl", "只能填写域名，不能填写接口路径")
}

private fun requireGroupId(field: String, value: String) {
    requireToken("$field key", value, 255)
}

private fun requireCommandAlias(field: String, value: String) {
    requireTrimmed(field, value, allowBlank = true, maxCodePoints = 64)
    requireConfig('/' !in value, field, "填写时不能包含斜杠 /")
    requireConfig(value.codePoints().noneMatch(Character::isWhitespace), field, "不能包含空白字符")
    requireConfig(value.codePoints().noneMatch(Character::isISOControl), field, "不能包含控制字符")
}

private fun requireToken(field: String, value: String, maxCodePoints: Int) {
    requireTrimmed(field, value, allowBlank = false, maxCodePoints = maxCodePoints)
    requireConfig(value.codePoints().noneMatch(Character::isWhitespace), field, "不能包含空白字符")
    requireConfig(value.codePoints().noneMatch(Character::isISOControl), field, "不能包含控制字符")
}

private fun requireKeyword(field: String, value: String, maxCodePoints: Int) {
    requireTrimmed(field, value, allowBlank = false, maxCodePoints = maxCodePoints)
    requireConfig(value.codePoints().noneMatch(Character::isISOControl), field, "不能包含控制字符")
}

private fun requirePrompt(field: String, value: String, maxCodePoints: Int) {
    requireTrimmed(field, value, allowBlank = false, maxCodePoints = maxCodePoints)
    requireConfig(value.codePoints().noneMatch(::unsupportedTextControl), field, "包含不支持的控制字符")
}

private fun requireReply(field: String, value: String) {
    requirePrompt(field, value, 4_000)
}

private fun requireTrimmed(field: String, value: String, allowBlank: Boolean, maxCodePoints: Int) {
    requireConfig(!hasUnpairedSurrogate(value), field, "包含无效 Unicode 字符")
    requireConfig(value == value.trim(), field, "首尾不能包含空白字符")
    if (!allowBlank) requireConfig(value.isNotBlank(), field, "不能为空")
    requireConfig(codePointLength(value) <= maxCodePoints, field, "最多允许 $maxCodePoints 个 Unicode 字符")
}

private fun unsupportedTextControl(codePoint: Int): Boolean =
    Character.isISOControl(codePoint) && codePoint != '\n'.code && codePoint != '\r'.code && codePoint != '\t'.code

private fun hasUnpairedSurrogate(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val current = value[index]
        when {
            Character.isHighSurrogate(current) -> {
                if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) return true
                index += 2
            }
            Character.isLowSurrogate(current) -> return true
            else -> index++
        }
    }
    return false
}

internal fun codePointLength(value: String): Int = value.codePointCount(0, value.length)

private fun requireConfig(condition: Boolean, field: String, detail: String) {
    if (!condition) throw ConfigValidationException(field, detail)
}

private fun <V> immutableSortedMap(source: Map<String, V>): Map<String, V> {
    val sorted: SortedMap<String, V> = TreeMap(source)
    return Collections.unmodifiableSortedMap(sorted)
}

private fun immutableSortedSet(source: Set<String>): Set<String> =
    Collections.unmodifiableSortedSet(TreeSet(source))
