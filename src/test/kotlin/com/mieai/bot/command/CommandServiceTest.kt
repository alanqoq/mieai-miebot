package com.mieai.bot.command

import com.mieai.bot.config.CommandAliasesConfig
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
    fun helpExplainsEveryMieAiCommandForAnyMember() {
        val aliases = CommandAliasesConfig(
            mainAlias = "ai",
            helpAlias = "查询mieai指令",
            probAlias = "设置概率",
            promptAlias = "设置提示词",
            keywordAlias = "设置关键词",
            chatAlias = "切换聊天",
        )
        fixture(MieAiBotConfig.defaults().copy(commands = aliases)).use { fixture ->
            val help = fixture.service.handle(
                UUID.randomUUID(),
                "group-openid",
                GroupMemberRole.MEMBER,
                "/mieai",
            ) as CommandOutcome.Handled
            val explicitHelp = fixture.service.handle(
                UUID.randomUUID(),
                "group-openid",
                GroupMemberRole.MEMBER,
                "/mieai help",
            ) as CommandOutcome.Handled
            val mainAliasHelp = fixture.service.handle(
                UUID.randomUUID(),
                "group-openid",
                GroupMemberRole.MEMBER,
                "/AI",
            ) as CommandOutcome.Handled
            val directHelpAlias = fixture.service.handle(
                UUID.randomUUID(),
                "group-openid",
                GroupMemberRole.MEMBER,
                "/查询mieai指令",
            ) as CommandOutcome.Handled

            assertEquals(help, explicitHelp)
            assertEquals(help, mainAliasHelp)
            assertEquals(help, directHelpAlias)
            assertTrue(help.reply.contains("/mieai prob <1-100>：设置当前群 AI 聊天概率"))
            assertTrue(help.reply.contains("/mieai prompt <提示词>：设置当前群独立系统提示词"))
            assertTrue(help.reply.contains("/mieai keyword <关键词>：设置当前群独立触发关键词"))
            assertTrue(help.reply.contains("/mieai chat：切换当前群 AI 聊天的启用和禁用状态"))
            assertTrue(help.reply.contains("/mieai help：显示这份完整指令帮助"))
            assertTrue(help.reply.contains("主指令别名：/ai"))
            assertTrue(help.reply.contains("指令别名：/查询mieai指令"))
            assertTrue(help.reply.contains("指令别名：/设置概率 <1-100>"))
            assertTrue(help.reply.contains("指令别名：/设置提示词 <提示词>"))
            assertTrue(help.reply.contains("指令别名：/设置关键词 <关键词>"))
            assertTrue(help.reply.contains("指令别名：/切换聊天"))
        }
    }

    @Test
    fun directAliasesExecuteEveryConfiguredCommand() {
        val aliases = CommandAliasesConfig(
            helpAlias = "帮助",
            probAlias = "设置概率",
            promptAlias = "设置提示词",
            keywordAlias = "设置关键词",
            chatAlias = "切换聊天",
        )
        fixture(MieAiBotConfig.defaults().copy(commands = aliases)).use { fixture ->
            val help = fixture.service.handle(
                UUID.randomUUID(),
                "group-openid",
                GroupMemberRole.MEMBER,
                "/帮助",
            ) as CommandOutcome.Handled
            assertTrue(help.reply.contains("可用指令"))

            fixture.service.handle(UUID.randomUUID(), "group-openid", GroupMemberRole.ADMIN, "/设置概率 73")
            fixture.service.handle(UUID.randomUUID(), "group-openid", GroupMemberRole.OWNER, "/设置提示词 请简洁回答")
            fixture.service.handle(UUID.randomUUID(), "group-openid", GroupMemberRole.ADMIN, "/设置关键词 小助手")
            fixture.service.handle(UUID.randomUUID(), "group-openid", GroupMemberRole.OWNER, "/切换聊天")

            val config = fixture.store.snapshot()
            assertEquals(73, config.chat.probabilityFor("group-openid"))
            assertEquals("请简洁回答", config.chat.systemPromptFor("group-openid"))
            assertEquals("小助手", config.chat.keywordFor("group-openid"))
            assertFalse(config.chat.isChatEnabled("group-openid"))
        }
    }

    @Test
    fun mainAliasCanReplaceMieAiPrefixForEverySubcommand() {
        val aliases = CommandAliasesConfig(mainAlias = "AI")
        fixture(MieAiBotConfig.defaults().copy(commands = aliases)).use { fixture ->
            val help = fixture.service.handle(
                UUID.randomUUID(),
                "group-openid",
                GroupMemberRole.MEMBER,
                "/ai help",
            ) as CommandOutcome.Handled
            assertTrue(help.reply.contains("可用指令"))

            fixture.service.handle(UUID.randomUUID(), "group-openid", GroupMemberRole.ADMIN, "/ai prob 64")
            fixture.service.handle(UUID.randomUUID(), "group-openid", GroupMemberRole.OWNER, "/AI prompt 保持客观")
            fixture.service.handle(UUID.randomUUID(), "group-openid", GroupMemberRole.ADMIN, "/ai keyword bot")
            fixture.service.handle(UUID.randomUUID(), "group-openid", GroupMemberRole.OWNER, "/ai chat")

            val config = fixture.store.snapshot()
            assertEquals(64, config.chat.probabilityFor("group-openid"))
            assertEquals("保持客观", config.chat.systemPromptFor("group-openid"))
            assertEquals("bot", config.chat.keywordFor("group-openid"))
            assertFalse(config.chat.isChatEnabled("group-openid"))
        }
    }

    @Test
    fun emptyAliasesDoNotClaimTopLevelCommands() {
        fixture().use { fixture ->
            val outcome = fixture.service.handle(
                UUID.randomUUID(),
                "group-openid",
                GroupMemberRole.OWNER,
                "/设置概率 80",
            )

            assertEquals(CommandOutcome.NotCommand, outcome)
            assertEquals(5, fixture.store.snapshot().chat.probabilityFor("group-openid"))
        }
    }

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
        val config = MieAiBotConfig.defaults().copy(
            commands = CommandAliasesConfig(probAlias = "设置概率"),
        )
        fixture(config).use { fixture ->
            val outcome = fixture.service.handle(
                UUID.randomUUID(),
                "group-openid",
                GroupMemberRole.MEMBER,
                "/设置概率 80",
            ) as CommandOutcome.Handled

            assertEquals("仅群管理员或群主可以使用此指令。", outcome.reply)
            assertEquals(5, fixture.store.snapshot().chat.probabilityFor("group-openid"))
        }
    }

    private fun fixture(config: MieAiBotConfig = MieAiBotConfig.defaults()): Fixture {
        val configFile = temporaryDirectory.resolve("config-${UUID.randomUUID()}.yml")
        val content = MieAiConfigCodec.render(config)
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
