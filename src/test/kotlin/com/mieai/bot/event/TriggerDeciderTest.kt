package com.mieai.bot.event

import com.mieai.bot.config.MieAiBotConfig
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TriggerDeciderTest {
    @Test
    fun atAndKeywordShortCircuitBeforeProbability() {
        var randomCalls = 0
        val random = { randomCalls++; 100 }
        val chat = MieAiBotConfig.defaults().chat.copy(defaultProbability = 0)

        assertTrue(TriggerDecider.shouldTrigger("GROUP_AT_MESSAGE_CREATE", "group", null, false, chat, random))
        assertTrue(
            TriggerDecider.shouldTrigger(
                "GROUP_MESSAGE_CREATE",
                "group",
                "ordinary prompt",
                false,
                chat,
                true,
                random,
            ),
        )
        assertTrue(TriggerDecider.shouldTrigger("GROUP_MESSAGE_CREATE", "group", "hello MIEAI", false, chat, random))
        assertTrue(randomCalls == 0)
    }

    @Test
    fun groupKeywordCompletelyReplacesDefaultKeyword() {
        val defaults = MieAiBotConfig.defaults().chat
        val chat = defaults.copy(defaultProbability = 0, groupKeywords = mapOf("group" to "only-here"))

        assertFalse(TriggerDecider.shouldTrigger("GROUP_MESSAGE_CREATE", "group", "mieai", false, chat) { 1 })
        assertTrue(TriggerDecider.shouldTrigger("GROUP_MESSAGE_CREATE", "group", "ONLY-HERE", false, chat) { 100 })
    }

    @Test
    fun probabilitySupportsPureImagesAndDisabledGroupsStopEveryTrigger() {
        val defaults = MieAiBotConfig.defaults().chat
        val always = defaults.copy(defaultProbability = 100)
        assertTrue(TriggerDecider.shouldTrigger("GROUP_MESSAGE_CREATE", "group", null, true, always) { 100 })

        val disabled = always.copy(disabledGroups = setOf("group"))
        assertFalse(TriggerDecider.shouldTrigger("GROUP_AT_MESSAGE_CREATE", "group", "mieai", true, disabled) { 1 })
    }
}
