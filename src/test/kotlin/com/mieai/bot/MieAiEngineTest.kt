package com.mieai.bot

import com.mieai.qqbot.plugin.testkit.PluginTestContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MieAiEngineTest {
    @Test
    fun linkageErrorWarningIncludesTheCauseChain() {
        val deepest = IllegalStateException("driver cause\r\ndetail")
        val nativeFailure = UnsatisfiedLinkError("native load\nfailed").apply { initCause(deepest) }
        val failure = LinkageError("SQLite link\r\nfailed").apply { initCause(nativeFailure) }

        withEngine { engine, fixture ->
            invokeWarn(engine, "SQLite startup failed", failure)

            assertEquals(
                "SQLite startup failed (java.lang.LinkageError: SQLite link failed <- " +
                    "java.lang.UnsatisfiedLinkError: native load failed <- " +
                    "java.lang.IllegalStateException: driver cause detail)",
                fixture.logger.entries().single().message,
            )
        }
    }

    @Test
    fun ordinaryWarningKeepsTheOriginalCompactFormat() {
        withEngine { engine, fixture ->
            invokeWarn(engine, "Request failed", IllegalStateException("response body"))

            assertEquals("Request failed (IllegalStateException)", fixture.logger.entries().single().message)
        }
    }

    private fun withEngine(block: (MieAiEngine, PluginTestContext) -> Unit) {
        val yaml = requireNotNull(javaClass.getResource("/qqbot-plugin-default.yml")).readText()
        PluginTestContext("mieai-bot", yaml, "config.yml").use { fixture ->
            MieAiEngine(fixture.context).use { engine -> block(engine, fixture) }
        }
    }

    private fun invokeWarn(engine: MieAiEngine, message: String, failure: Throwable?) {
        MieAiEngine::class.java.getDeclaredMethod("warn", String::class.java, Throwable::class.java)
            .apply { isAccessible = true }
            .invoke(engine, message, failure)
    }
}
