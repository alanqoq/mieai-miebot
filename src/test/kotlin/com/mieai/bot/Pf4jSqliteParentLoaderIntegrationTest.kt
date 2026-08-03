package com.mieai.bot

import com.mieai.qqbot.domain.bot.BotEnvironment
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.plugin.api.ConfigSnapshot
import com.mieai.qqbot.plugin.api.GroupMemberRole
import com.mieai.qqbot.plugin.api.InboundMessage
import com.mieai.qqbot.plugin.api.MessageTarget
import com.mieai.qqbot.plugin.api.MessageTargetType
import com.mieai.qqbot.plugin.api.PluginContext
import com.mieai.qqbot.plugin.api.PluginEvent
import com.mieai.qqbot.plugin.api.PluginHttpResponse
import com.mieai.qqbot.plugin.api.PluginRuntimeContext
import com.mieai.qqbot.plugin.spi.BotPlugin
import com.mieai.qqbot.plugin.spi.BotPluginFactory
import com.mieai.qqbot.plugin.testkit.FakeEventService
import com.mieai.qqbot.plugin.testkit.FakeMediaService
import com.mieai.qqbot.plugin.testkit.FakeMessageSender
import com.mieai.qqbot.plugin.testkit.FakePluginHttpClient
import com.mieai.qqbot.plugin.testkit.FakePluginLogger
import com.mieai.qqbot.plugin.testkit.FakePluginStorage
import com.mieai.qqbot.plugin.testkit.ManualPluginScheduler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.pf4j.ClassLoadingStrategy
import org.pf4j.DefaultPluginDescriptor
import org.pf4j.DefaultPluginManager
import org.pf4j.PluginClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.ServiceLoader
import java.util.UUID
import java.util.jar.JarFile

class Pf4jSqliteParentLoaderIntegrationTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun pluginArchiveDoesNotBundleHostOwnedSqlite() {
        JarFile(pluginJar().toFile(), false).use { archive ->
            val sqliteEntries = archive.entries().asSequence()
                .map { it.name }
                .filter { it.startsWith("org/sqlite/") }
                .toList()

            assertTrue(sqliteEntries.isEmpty(), "Plugin JAR contains host-owned SQLite entries: $sqliteEntries")
        }
    }

    @Test
    fun loadsParentSqliteAndRoundTripsBindingHistory() {
        val parentLoader = javaClass.classLoader
        val parentDriver = Class.forName("org.sqlite.JDBC", true, parentLoader)
        DriverManager.getConnection(sqliteUrl(temporaryDirectory.resolve("host.db"))).use { connection ->
            connection.createStatement().use { it.execute("SELECT 1") }
        }

        val pluginRoot = Files.createDirectories(temporaryDirectory.resolve("plugins"))
        val manager = DefaultPluginManager(pluginRoot)
        val descriptor = DefaultPluginDescriptor(
            "mieai-bot",
            "MieAI Bot",
            "com.mieai.qqbot.plugin.host.Pf4jPluginBridge",
            "0.0.4",
            "3.2.0",
            "MieAI",
            null,
        )
        PluginClassLoader(manager, descriptor, parentLoader, ClassLoadingStrategy.PDA).use { pluginLoader ->
            pluginLoader.addFile(pluginJar().toFile())

            assertSame(parentDriver, Class.forName("org.sqlite.JDBC", true, pluginLoader))
            val factories = ServiceLoader.load(BotPluginFactory::class.java, pluginLoader)
                .filter { it.pluginId == "mieai-bot" }
                .toList()
            assertEquals(1, factories.size)
            val factory = factories.single()
            assertSame(pluginLoader, factory.javaClass.classLoader)

            val bindingDirectory = Files.createDirectories(
                temporaryDirectory.resolve("plugin-data").resolve(BOT_ID.toString()).resolve("mieai-bot"),
            )
            val configuration = checkNotNull(pluginLoader.getResourceAsStream("qqbot-plugin-default.yml"))
                .bufferedReader(StandardCharsets.UTF_8)
                .use { it.readText() }
            Files.writeString(bindingDirectory.resolve("config.yml"), configuration, StandardCharsets.UTF_8)
            val fixture = fixture(bindingDirectory, configuration)
            try {
                startAndStopAfterWriting(factory, fixture.context, fixture.events)

                val history = bindingDirectory.resolve("history.db")
                assertTrue(Files.isRegularFile(history))
                assertEquals(listOf("/mieai prob 42"), storedMemberTexts(history))

                factory.create(fixture.context).usePlugin { it.start() }
                assertEquals(listOf("/mieai prob 42"), storedMemberTexts(history))
            } finally {
                fixture.close()
            }
        }
    }

    private fun startAndStopAfterWriting(
        factory: BotPluginFactory,
        context: PluginRuntimeContext,
        events: FakeEventService,
    ) {
        factory.create(context).usePlugin { plugin ->
            plugin.start()
            val eventId = UUID.randomUUID()
            events.emit(
                PluginEvent(
                    eventId,
                    context.base.botId,
                    context.base.environment,
                    "GROUP_MESSAGE_CREATE",
                    "platform-event-${eventId.toString().take(8)}",
                    """{"d":{"attachments":[]}}""",
                    CLOCK.instant(),
                    InboundMessage(
                        MessageTarget(MessageTargetType.GROUP, "group-openid"),
                        "member-message-id",
                        "reply-event-id",
                        "member-openid",
                        "/mieai prob 42",
                        null,
                        GroupMemberRole.OWNER,
                    ),
                ),
            ).toCompletableFuture().join()
        }
    }

    private fun fixture(bindingDirectory: Path, configuration: String): Fixture {
        val messages = FakeMessageSender(CLOCK)
        val logger = FakePluginLogger()
        val storage = FakePluginStorage()
        val events = FakeEventService()
        val scheduler = ManualPluginScheduler()
        val http = FakePluginHttpClient {
            PluginHttpResponse(200, emptyMap(), "{}".toByteArray(StandardCharsets.UTF_8))
        }
        val base = PluginContext(
            BOT_ID,
            BotEnvironment.SANDBOX,
            "mieai-bot",
            bindingDirectory,
            configuration,
            messages,
            logger,
            storage,
        )
        return Fixture(
            PluginRuntimeContext(
                base,
                ConfigSnapshot(configuration, 0L, CLOCK.instant(), "config.yml"),
                events,
                scheduler,
                http,
                FakeMediaService(CLOCK),
            ),
            events,
            scheduler,
        )
    }

    private fun storedMemberTexts(database: Path): List<String> =
        DriverManager.getConnection(sqliteUrl(database)).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT text_content FROM messages WHERE direction='MEMBER' ORDER BY id",
                ).use { rows ->
                    buildList { while (rows.next()) add(rows.getString(1)) }
                }
            }
        }

    private fun pluginJar(): Path = Path.of(checkNotNull(System.getProperty("mieai.plugin.jar")))
        .toAbsolutePath()
        .normalize()

    private fun sqliteUrl(path: Path): String = "jdbc:sqlite:${path.toAbsolutePath().normalize()}"

    private inline fun BotPlugin.usePlugin(block: (BotPlugin) -> Unit) {
        try {
            block(this)
        } finally {
            stop()
        }
    }

    private data class Fixture(
        val context: PluginRuntimeContext,
        val events: FakeEventService,
        val scheduler: ManualPluginScheduler,
    ) : AutoCloseable {
        override fun close() {
            events.close()
            scheduler.close()
        }
    }

    private companion object {
        val BOT_ID: BotId = BotId.of(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"))
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC)
    }
}
