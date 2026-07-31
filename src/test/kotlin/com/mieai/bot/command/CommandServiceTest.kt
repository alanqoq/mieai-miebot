package com.mieai.bot.command

import com.mieai.bot.config.MieAiBotConfig
import com.mieai.bot.config.MieAiConfigCodec
import com.mieai.bot.config.MieAiConfigStore
import com.mieai.bot.history.HistoryDatabase
import com.mieai.qqbot.plugin.api.GroupMemberRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class CommandServiceTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun duplicateChatToggleEventKeepsItsFirstDesiredState() {
        fixture().use { fixture ->
            val eventId = UUID.randomUUID()
            fixture.service.handle(eventId, "group-openid", GroupMemberRole.OWNER, "/mieai chat")
            assertFalse(fixture.store.snapshot().chat.isChatEnabled("group-openid"))

            fixture.service.handle(eventId, "group-openid", GroupMemberRole.OWNER, "/MIEAI CHAT")
            assertFalse(fixture.store.snapshot().chat.isChatEnabled("group-openid"))

            fixture.service.handle(UUID.randomUUID(), "group-openid", GroupMemberRole.ADMIN, "/mieai chat")
            assertTrue(fixture.store.snapshot().chat.isChatEnabled("group-openid"))
        }
    }

    @Test
    fun promptAndKeywordLimitsDoNotWriteInvalidConfiguration() {
        fixture().use { fixture ->
            val before = Files.readString(fixture.configFile)
            val tooLong = "x".repeat(fixture.store.snapshot().chat.promptMaxLength + 1)
            val outcome = fixture.service.handle(
                UUID.randomUUID(),
                "group-openid",
                GroupMemberRole.ADMIN,
                "/mieai prompt $tooLong",
            ) as CommandOutcome.Handled
            assertEquals(fixture.store.snapshot().chat.promptTooLongReply, outcome.reply)
            assertEquals(before, Files.readString(fixture.configFile))

            fixture.service.handle(
                UUID.randomUUID(),
                "group-openid",
                GroupMemberRole.OWNER,
                "/mieai Keyword only-here",
            )
            assertEquals("only-here", fixture.store.snapshot().chat.keywordFor("group-openid"))
        }
    }

    @Test
    fun membersCannotMutateGroupConfiguration() {
        fixture().use { fixture ->
            fixture.service.handle(UUID.randomUUID(), "group-openid", GroupMemberRole.MEMBER, "/mieai prob 80")
            assertEquals(5, fixture.store.snapshot().chat.probabilityFor("group-openid"))
        }
    }

    private fun fixture(): Fixture {
        val configFile = temporaryDirectory.resolve("config-${UUID.randomUUID()}.yml")
        val content = MieAiConfigCodec.render(MieAiBotConfig.defaults())
        Files.writeString(configFile, content)
        val store = MieAiConfigStore.open(configFile, content)
        val database = HistoryDatabase(temporaryDirectory.resolve("history-${UUID.randomUUID()}.db"))
        return Fixture(configFile, store, database, CommandService(store, database))
    }

    private data class Fixture(
        val configFile: Path,
        val store: MieAiConfigStore,
        val database: HistoryDatabase,
        val service: CommandService,
    ) : AutoCloseable {
        override fun close() = database.close()
    }
}
