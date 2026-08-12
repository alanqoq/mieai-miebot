package com.mieai.bot

import com.mieai.bot.config.MieAiConfigCodec
import com.mieai.qqbot.plugin.api.GroupMemberRole
import com.mieai.qqbot.plugin.api.InboundMessage
import com.mieai.qqbot.plugin.api.MessageTarget
import com.mieai.qqbot.plugin.api.MessageTargetType
import com.mieai.qqbot.plugin.api.PluginEvent
import com.mieai.qqbot.plugin.spi.BotPluginFactory
import com.mieai.qqbot.plugin.testkit.PluginTestContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.sql.DriverManager
import java.util.UUID
import java.util.ServiceLoader

class PluginIntegrationTest {
    @Test
    fun serviceLoaderPublishesExactlyTheMieAiFactory() {
        val factories = ServiceLoader.load(BotPluginFactory::class.java)
            .filter { it.pluginId == "mieai-bot" }
            .toList()

        assertEquals(1, factories.size)
        assertEquals(MieAiBotPluginFactory::class.java, factories.single().javaClass)
    }

    @Test
    fun registersOneHandlerAndQuotesAnAdministratorCommandReply() {
        val yaml = requireNotNull(javaClass.getResource("/qqbot-plugin-default.yml")).readText()
        PluginTestContext("mieai-bot", yaml, "config.yml").use { fixture ->
            val plugin = MieAiBotPluginFactory().create(fixture.context)
            plugin.start()
            try {
                assertEquals(setOf("mieai-group-chat"), fixture.events.handlerIds())
                val eventId = UUID.randomUUID()
                val event = PluginEvent(
                    eventId,
                    fixture.context.base.botId,
                    fixture.context.base.environment,
                    "GROUP_MESSAGE_CREATE",
                    "platform-event-${eventId.toString().take(8)}",
                    """{"d":{"attachments":[]}}""",
                    Instant.now(),
                    InboundMessage(
                        MessageTarget(MessageTargetType.GROUP, "group-openid"),
                        "member-message-id",
                        "reply-event-id",
                        "member-openid",
                        "/mieai prob 42",
                        null,
                        GroupMemberRole.OWNER,
                    ),
                )
                fixture.events.emit(event).toCompletableFuture().join()
                await { fixture.messages.textMessages().isNotEmpty() }

                val sent = fixture.messages.textMessages().single()
                assertTrue(sent.content.contains("42%"))
                assertEquals("mieai:command:$eventId", sent.deduplicationKey)
                assertEquals("member-message-id", fixture.messages.textSendOptions().single().messageReference?.messageId)
            } finally {
                plugin.stop()
            }
        }
    }

    @Test
    fun genericGroupMessageWithCurrentBotMentionStartsChat() {
        val defaults = packagedDefaultConfig()
        val config = defaults.copy(
            chat = defaults.chat.copy(defaultProbability = 0),
        )
        val yaml = MieAiConfigCodec.render(config)
        PluginTestContext("mieai-bot-at-${UUID.randomUUID()}", yaml, "config.yml").use { fixture ->
            val plugin = MieAiBotPluginFactory().create(fixture.context)
            plugin.start()
            try {
                val eventId = UUID.randomUUID()
                val event = PluginEvent(
                    eventId,
                    fixture.context.base.botId,
                    fixture.context.base.environment,
                    "GROUP_MESSAGE_CREATE",
                    "platform-event-${eventId.toString().take(8)}",
                    """{"d":{"attachments":[],"mentions":[{"is_you":true}]}}""",
                    Instant.now(),
                    InboundMessage(
                        MessageTarget(MessageTargetType.GROUP, "group-openid"),
                        "member-message-id",
                        "reply-event-id",
                        "member-openid",
                        "ordinary prompt",
                        null,
                        GroupMemberRole.MEMBER,
                    ),
                )

                fixture.events.emit(event).toCompletableFuture().join()

                await { chatTaskCount(fixture) == 1 }
            } finally {
                plugin.stop()
            }
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            if (System.nanoTime() >= deadline) error("Timed out waiting for asynchronous plugin work")
            Thread.sleep(20)
        }
    }

    private fun chatTaskCount(fixture: PluginTestContext): Int =
        DriverManager.getConnection("jdbc:sqlite:${fixture.context.base.dataDirectory.resolve("history.db")}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM chat_tasks").use { rows ->
                    check(rows.next())
                    rows.getInt(1)
                }
            }
        }
}
