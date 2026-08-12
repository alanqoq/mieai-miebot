package com.mieai.bot.event

import com.mieai.bot.packagedDefaultConfig
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TriggerDeciderTest {
    @Test
    fun atAndKeywordShortCircuitBeforeProbability() {
        var randomCalls = 0
        val random = { randomCalls++; 100 }
        val chat = packagedDefaultConfig().chat.copy(defaultProbability = 0)

        assertTrue(TriggerDecider.shouldTrigger("GROUP_AT_MESSAGE_CREATE", "group", null, false, chat, false, random))
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
        assertTrue(TriggerDecider.shouldTrigger("GROUP_MESSAGE_CREATE", "group", "hello MIEAI", false, chat, false, random))
        assertTrue(randomCalls == 0)
    }

    @Test
    fun groupKeywordCompletelyReplacesDefaultKeyword() {
        val defaults = packagedDefaultConfig().chat
        val chat = defaults.copy(defaultProbability = 0, groupKeywords = mapOf("group" to "only-here"))

        assertFalse(TriggerDecider.shouldTrigger("GROUP_MESSAGE_CREATE", "group", "mieai", false, chat, false) { 1 })
        assertTrue(TriggerDecider.shouldTrigger("GROUP_MESSAGE_CREATE", "group", "ONLY-HERE", false, chat, false) { 100 })
    }

    @Test
    fun probabilitySupportsPureImagesAndDisabledGroupsStopEveryTrigger() {
        val defaults = packagedDefaultConfig().chat
        val always = defaults.copy(defaultProbability = 100)
        assertTrue(TriggerDecider.shouldTrigger("GROUP_MESSAGE_CREATE", "group", null, true, always, false) { 100 })

        val disabled = always.copy(disabledGroups = setOf("group"))
        assertFalse(TriggerDecider.shouldTrigger("GROUP_AT_MESSAGE_CREATE", "group", "mieai", true, disabled, false) { 1 })
    }
}
