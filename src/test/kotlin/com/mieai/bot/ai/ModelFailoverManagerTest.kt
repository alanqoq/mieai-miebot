package com.mieai.bot.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModelFailoverManagerTest {
    @Test
    fun switchesImmediatelyAndKeepsFallbackUntilWindowExpires() {
        var now = 1_000L
        val warnings = mutableListOf<String>()
        val manager = ModelFailoverManager({ now }) { message, _ -> warnings += message }
        val firstCalls = mutableListOf<String>()

        val first = manager.execute("primary", "fallback", 1) { model ->
            firstCalls += model
            if (model == "primary") throw ChatApiException("failed")
            "fallback answer"
        }
        assertEquals("fallback answer", first)
        assertEquals(listOf("primary", "fallback"), firstCalls)
        assertTrue(warnings.isNotEmpty())

        val secondCalls = mutableListOf<String>()
        manager.execute("primary", "fallback", 1) { model -> secondCalls += model; "ok" }
        assertEquals(listOf("fallback"), secondCalls)

        now += 60_001
        val thirdCalls = mutableListOf<String>()
        manager.execute("primary", "fallback", 1) { model -> thirdCalls += model; "ok" }
        assertEquals(listOf("primary"), thirdCalls)
    }
}
