package com.mieai.bot.config

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
    fun `packaged default config parses to code defaults`() {
        val content = requireNotNull(javaClass.getResource("/qqbot-plugin-default.yml")).readText()

        assertEquals(MieAiBotConfig.defaults(), MieAiConfigCodec.parse(content))
    }

    @Test
    fun `render emits comments and round trips all dynamic group values`() {
        val defaults = MieAiBotConfig.defaults()
        val configured = defaults.copy(
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
        assertTrue(rendered.contains("# 每群单独关键词。"))
        assertTrue(rendered.endsWith("\n"))
        assertEquals(configured.immutableCopy(), MieAiConfigCodec.parse(rendered))
    }

    @Test
    fun `parser rejects unknown and duplicate fields`() {
        val valid = MieAiConfigCodec.render(MieAiBotConfig.defaults())
        assertFailsWith<ConfigParseException> {
            MieAiConfigCodec.parse(valid.replace("api:\n", "api:\n  unknown: true\n"))
        }
        assertFailsWith<ConfigParseException> {
            MieAiConfigCodec.parse(valid + "queue:\n  maxPendingPerGroup: 10\n")
        }
    }

    @Test
    fun `validation enforces domain protocol group maps and Unicode code points`() {
        val defaults = MieAiBotConfig.defaults()
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
    fun `store writes a complete config before replacing its atomic snapshot`(@TempDir directory: Path) {
        val path = directory.resolve("config.yml")
        val defaults = MieAiBotConfig.defaults()
        Files.writeString(path, MieAiConfigCodec.render(defaults))
        val store = MieAiConfigStore.load(path)

        val updated = store.update { current ->
            current.copy(chat = current.chat.copy(groupProbabilities = mapOf("group-1" to 35)))
        }

        assertEquals(35, updated.chat.probabilityFor("group-1"))
        assertEquals(updated, store.snapshot())
        assertEquals(updated, MieAiConfigCodec.parse(Files.readString(path)))
        assertFalse(Files.list(directory).use { files -> files.anyMatch { it.fileName.toString().endsWith(".tmp") } })
    }
}
