package com.mieai.bot.config

import com.mieai.bot.packagedDefaultConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MieAiConfigCodecTest {
    @Test
    fun `render emits comments and round trips all dynamic group values`() {
        val defaults = packagedDefaultConfig()
        val configured = defaults.copy(
            commands = defaults.commands.copy(
                mainAlias = "ai",
                helpAlias = "查询mieai指令",
                probAlias = "设置概率",
                promptAlias = "设置提示词",
                keywordAlias = "设置关键词",
                chatAlias = "切换聊天",
            ),
            chat = defaults.chat.copy(
                groupProbabilities = mapOf("group-b" to 15),
                groupSystemPrompts = mapOf("group-b" to "第一行\n第二行"),
                groupKeywords = mapOf("group-b" to "小助手"),
                disabledGroups = setOf("group-c"),
            ),
            storage = defaults.storage.copy(groupMaxMessages = mapOf("group-b" to 50_000)),
        )

        val rendered = MieAiConfigCodec.render(configured)

        assertTrue(rendered.contains("# 服务域名。"))
        assertTrue(rendered.contains("# /mieai help 的独立别名"))
        assertTrue(rendered.contains("# 每群单独关键词。"))
        assertTrue(rendered.endsWith("\n"))
        assertEquals(configured.immutableCopy(), MieAiConfigCodec.parse(rendered))
    }

    @Test
    fun `legacy config without commands section uses empty aliases`() {
        val defaults = packagedDefaultConfig()
        val rendered = MieAiConfigCodec.render(defaults)
        val commandsStart = rendered.indexOf("# 指令别名设置。")
        val chatStart = rendered.indexOf("# 群聊触发、提示词、关键词和上文设置。")
        val legacy = rendered.removeRange(commandsStart, chatStart)

        val parsed = MieAiConfigCodec.parse(legacy)

        assertEquals(CommandAliasesConfig(), parsed.commands)
        assertEquals(defaults, parsed)
    }

    @Test
    fun `parser rejects unknown and duplicate fields`() {
        val valid = MieAiConfigCodec.render(packagedDefaultConfig())
        assertFailsWith<ConfigParseException> {
            MieAiConfigCodec.parse(valid.replace("api:\n", "api:\n  unknown: true\n"))
        }
        assertFailsWith<ConfigParseException> {
            MieAiConfigCodec.parse(valid + "queue:\n  maxPendingPerGroup: 10\n")
        }
    }

    @Test
    fun `validation enforces domain protocol group maps and Unicode code points`() {
        val defaults = packagedDefaultConfig()
        assertFailsWith<ConfigValidationException> {
            defaults.copy(api = defaults.api.copy(baseUrl = "https://api.openai.com/v1"))
        }
        assertFailsWith<ConfigValidationException> {
            defaults.copy(chat = defaults.chat.copy(groupProbabilities = mapOf("bad group" to 10)))
        }

        val oneEmoji = "\uD83D\uDE00"
        val valid = defaults.copy(chat = defaults.chat.copy(keywordMaxLength = 1, defaultKeyword = oneEmoji))
        assertEquals(1, codePointLength(valid.chat.defaultKeyword))
        assertFailsWith<ConfigValidationException> {
            defaults.copy(chat = defaults.chat.copy(keywordMaxLength = 1, defaultKeyword = oneEmoji + "a"))
        }

        val invalidProtocol = MieAiConfigCodec.render(defaults).replace("\"openai-old\"", "\"OPENAI_OLD\"")
        assertFailsWith<ConfigValidationException> { MieAiConfigCodec.parse(invalidProtocol) }
    }

    @Test
    fun `command aliases reject reserved duplicate and malformed values`() {
        assertFailsWith<ConfigValidationException> { CommandAliasesConfig(mainAlias = "MIEAI") }
        assertFailsWith<ConfigValidationException> {
            CommandAliasesConfig(helpAlias = "Same", probAlias = "same")
        }
        assertFailsWith<ConfigValidationException> { CommandAliasesConfig(helpAlias = "/help") }
        assertFailsWith<ConfigValidationException> { CommandAliasesConfig(probAlias = "set prob") }
        assertFailsWith<ConfigValidationException> { CommandAliasesConfig(promptAlias = " prompt") }
        assertFailsWith<ConfigValidationException> { CommandAliasesConfig(keywordAlias = "bad\u0001alias") }
        assertFailsWith<ConfigValidationException> { CommandAliasesConfig(chatAlias = "a".repeat(65)) }

        assertEquals(64, codePointLength(CommandAliasesConfig(chatAlias = "聊".repeat(64)).chatAlias))
    }

    @Test
    fun `store writes a complete config before replacing its atomic snapshot`(@TempDir directory: Path) {
        val path = directory.resolve("config.yml")
        val defaults = packagedDefaultConfig()
        Files.writeString(path, MieAiConfigCodec.render(defaults))
        val store = MieAiConfigStore.open(path, Files.readString(path))

        val updated = store.update { current ->
            current.copy(chat = current.chat.copy(groupProbabilities = mapOf("group-1" to 35)))
        }

        assertEquals(35, updated.chat.probabilityFor("group-1"))
        assertEquals(updated, store.snapshot())
        assertEquals(updated, MieAiConfigCodec.parse(Files.readString(path)))
        assertFalse(Files.list(directory).use { files -> files.anyMatch { it.fileName.toString().endsWith(".tmp") } })
    }
}
