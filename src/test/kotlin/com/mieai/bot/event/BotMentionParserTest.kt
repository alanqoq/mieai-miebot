package com.mieai.bot.event

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BotMentionParserTest {
    private val parser = BotMentionParser(jacksonObjectMapper())

    @Test
    fun recognizesOnlyAnExplicitCurrentBotMention() {
        assertTrue(
            parser.isBotMentioned(
                """{"d":{"mentions":[{"is_you":false},{"is_you":true}]}}""",
            ),
        )
        assertFalse(parser.isBotMentioned("""{"d":{"mentions":[{"is_you":false}]}}"""))
        assertFalse(parser.isBotMentioned("""{"d":{"mentions":[{"is_you":"true"}]}}"""))
        assertFalse(parser.isBotMentioned("not-json"))
    }
}
