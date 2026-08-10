package com.mieai.bot.config

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue

object MieAiConfigCodec {
    private val mapper = YAMLMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
        .build()

    @JvmStatic
    fun parse(content: String): MieAiBotConfig {
        if (content.isBlank()) throw ConfigParseException("配置文件不能为空")
        return try {
            mapper.readValue<MieAiBotConfig>(content).immutableCopy()
        } catch (error: ConfigValidationException) {
            throw error
        } catch (error: JsonProcessingException) {
            val validation = error.findCause<ConfigValidationException>()
            if (validation != null) throw validation
            val location = error.location
            val suffix = if (location == null || location.lineNr <= 0) {
                ""
            } else {
                "（第 ${location.lineNr} 行，第 ${location.columnNr} 列）"
            }
            throw ConfigParseException("配置文件格式、字段名称或字段类型无效$suffix")
        } catch (_: RuntimeException) {
            throw ConfigParseException("配置文件格式、字段名称或字段类型无效")
        }
    }

    @JvmStatic
    fun render(config: MieAiBotConfig): String {
        val value = config.immutableCopy()
        return buildString {
            appendLine("# AI 接口设置。链接只填写协议、域名和可选端口，插件会自动补全接口路径。")
            appendLine("api:")
            appendLine("  # 服务域名。不要填写 /v1、接口路径、查询参数或账号信息；自建服务可使用端口。")
            appendLine("  baseUrl: ${yamlString(value.api.baseUrl)}")
            appendLine("  # API 密钥。请在绑定配置中填写真实密钥，不要把真实密钥提交到版本库。")
            appendLine("  apiKey: ${yamlString(value.api.apiKey)}")
            appendLine("  # 模型协议。openai-old 使用 /v1/chat/completions；openai-new 使用 /v1/responses。")
            appendLine("  protocol: ${yamlString(value.api.protocol.configValue)}")
            appendLine("  # 默认主模型名称。名称不能为空，也不能包含空白字符。")
            appendLine("  primaryModel: ${yamlString(value.api.primaryModel)}")
            appendLine("  # 单次 AI 请求超时秒数，范围 1-3600；超时会触发备用模型逻辑。")
            appendLine("  requestTimeoutSeconds: ${value.api.requestTimeoutSeconds}")
            appendLine("  # 备用模型名称。留空或与主模型相同表示不启用备用模型。")
            appendLine("  fallbackModel: ${yamlString(value.api.fallbackModel)}")
            appendLine("  # 切换到备用模型后持续使用的分钟数，范围 1-525600。")
            appendLine("  fallbackDurationMinutes: ${value.api.fallbackDurationMinutes}")
            appendLine()

            appendLine("# 指令别名设置。所有别名最多 64 个 Unicode 字符，彼此不能重复，填写时不要包含开头的 /；留空表示不启用该别名。")
            appendLine("commands:")
            appendLine("  # /mieai 主指令的别名，用于显示帮助，也可替代 /mieai 前缀。示例填 ai 后可使用 /ai、/ai help 或 /ai prob 50。")
            appendLine("  mainAlias: ${yamlString(value.commands.mainAlias)}")
            appendLine("  # /mieai help 的独立别名，不带参数，用于查看完整指令说明。示例填 查询mieai指令 后可使用 /查询mieai指令。")
            appendLine("  helpAlias: ${yamlString(value.commands.helpAlias)}")
            appendLine("  # /mieai prob <1-100> 的独立别名，用于设置当前群聊天概率。使用别名时仍要在后面填写 1-100。")
            appendLine("  probAlias: ${yamlString(value.commands.probAlias)}")
            appendLine("  # /mieai prompt <提示词> 的独立别名，用于设置当前群系统提示词。使用别名时仍要在后面填写提示词。")
            appendLine("  promptAlias: ${yamlString(value.commands.promptAlias)}")
            appendLine("  # /mieai keyword <关键词> 的独立别名，用于设置当前群触发关键词。使用别名时仍要在后面填写关键词。")
            appendLine("  keywordAlias: ${yamlString(value.commands.keywordAlias)}")
            appendLine("  # /mieai chat 的独立别名，不带参数，用于切换当前群 AI 聊天的启用或禁用状态。")
            appendLine("  chatAlias: ${yamlString(value.commands.chatAlias)}")
            appendLine()

            appendLine("# 群聊触发、提示词、关键词和上文设置。所有群键都填写 QQ 提供的 group_openid 字符串。")
            appendLine("chat:")
            appendLine("  # 全局随机聊天概率，单位为百分比，范围 0-100；未单独设置的群使用该值。")
            appendLine("  defaultProbability: ${value.chat.defaultProbability}")
            appendLine("  # 每群随机聊天概率，值范围 1-100。示例：{\"group_openid\": 15}。")
            appendMap("  ", "groupProbabilities", value.chat.groupProbabilities) { it.toString() }
            appendLine("  # 全局系统提示词。群没有单独提示词时使用该内容。")
            appendLine("  defaultSystemPrompt: ${yamlString(value.chat.defaultSystemPrompt)}")
            appendLine("  # 每群系统提示词。示例：{\"group_openid\": \"你是这个群的技术助手。\"}。")
            appendMap("  ", "groupSystemPrompts", value.chat.groupSystemPrompts, ::yamlString)
            appendLine("  # 群管理员通过 /mieai prompt 设置提示词时允许的最大 Unicode 字符数。")
            appendLine("  promptMaxLength: ${value.chat.promptMaxLength}")
            appendLine("  # 群提示词超过上限时引用回复的内容；必须非空且不超过 4000 个 Unicode 字符。")
            appendLine("  promptTooLongReply: ${yamlString(value.chat.promptTooLongReply)}")
            appendLine("  # 默认关键词，采用忽略大小写的子串匹配；群设置单独关键词后本字段在该群失效。")
            appendLine("  defaultKeyword: ${yamlString(value.chat.defaultKeyword)}")
            appendLine("  # 每群单独关键词。示例：{\"group_openid\": \"小助手\"}。")
            appendMap("  ", "groupKeywords", value.chat.groupKeywords, ::yamlString)
            appendLine("  # 群管理员通过 /mieai keyword 设置关键词时允许的最大 Unicode 字符数。")
            appendLine("  keywordMaxLength: ${value.chat.keywordMaxLength}")
            appendLine("  # 群关键词超过上限时引用回复的内容；必须非空且不超过 4000 个 Unicode 字符。")
            appendLine("  keywordTooLongReply: ${yamlString(value.chat.keywordTooLongReply)}")
            appendLine("  # 禁用聊天的群 group_openid 列表。禁用后概率、@和关键词触发全部关闭，管理命令仍可用。")
            appendList("  ", "disabledGroups", value.chat.disabledGroups)
            appendLine("  # 是否允许受支持模型读取 SQLite 上下文中的 Base64 图片；图片入库不受该开关影响。")
            appendLine("  imageUnderstandingEnabled: ${value.chat.imageUnderstandingEnabled}")
            appendLine("  # 每次请求最多联系的上文消息数，不包含当前触发消息；0 表示不使用上文。")
            appendLine("  maxContextMessages: ${value.chat.maxContextMessages}")
            appendLine()

            appendLine("# SQLite 历史和图片保留设置。数据库位于当前插件绑定的私有数据目录。")
            appendLine("storage:")
            appendLine("  # 单张图片转换为 Base64 后允许保存的最大字节数；0 表示不保存图片正文。")
            appendLine("  maxBase64ImageBytes: ${value.storage.maxBase64ImageBytes}")
            appendLine("  # 全部群合计最多保存的消息数；0 表示不限制总量。")
            appendLine("  maxMessagesTotal: ${value.storage.maxMessagesTotal}")
            appendLine("  # 每群默认最多保存的消息数；0 表示不限制单群数量。")
            appendLine("  defaultMaxMessagesPerGroup: ${value.storage.defaultMaxMessagesPerGroup}")
            appendLine("  # 每群单独消息上限，覆盖默认单群值；0 表示该群不限制。示例：{\"group_openid\": 50000}。")
            appendMap("  ", "groupMaxMessages", value.storage.groupMaxMessages) { it.toString() }
            appendLine("  # 一条消息最多保存的天数；0 表示不按时间删除。")
            appendLine("  maxMessageAgeDays: ${value.storage.maxMessageAgeDays}")
            appendLine("  # 超限时每轮删除的最旧消息数量；删除后仍超限会继续执行。")
            appendLine("  cleanupDeleteBatchSize: ${value.storage.cleanupDeleteBatchSize}")
            appendLine("  # 每天按服务器本地时区执行清理的时间，严格使用 24 小时制 HH:mm。")
            appendLine("  cleanupTime: ${yamlString(value.storage.cleanupTime)}")
            appendLine()

            appendLine("# 群内 AI 任务队列设置。不同群并行，同一个群严格按接收顺序串行。")
            appendLine("queue:")
            appendLine("  # 每群最多等待处理的触发任务数量，不包含正在执行的任务。")
            appendLine("  maxPendingPerGroup: ${value.queue.maxPendingPerGroup}")
        }
    }

    private fun <V> StringBuilder.appendMap(
        indent: String,
        name: String,
        values: Map<String, V>,
        renderValue: (V) -> String,
    ) {
        if (values.isEmpty()) {
            appendLine("$indent$name: {}")
            return
        }
        appendLine("$indent$name:")
        values.toSortedMap().forEach { (key, value) ->
            appendLine("$indent  ${yamlString(key)}: ${renderValue(value)}")
        }
    }

    private fun StringBuilder.appendList(indent: String, name: String, values: Set<String>) {
        if (values.isEmpty()) {
            appendLine("$indent$name: []")
            return
        }
        appendLine("$indent$name:")
        values.toSortedSet().forEach { appendLine("$indent  - ${yamlString(it)}") }
    }

    private inline fun <reified T : Throwable> Throwable.findCause(): T? {
        var current: Throwable? = this
        while (current != null) {
            if (current is T) return current
            current = current.cause
        }
        return null
    }

    private fun yamlString(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (Character.isISOControl(character)) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }
}

class ConfigParseException(message: String) : IllegalArgumentException(message)
