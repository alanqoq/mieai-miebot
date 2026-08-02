package com.mieai.bot.history

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class HistoryDatabaseTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun followsOnlyTheRecursiveReferenceChainInChronologicalOrder() {
        HistoryDatabase(temporaryDirectory.resolve("history.db")).use { database ->
            val a = insert(database, "group", "a", "first", null, 1)
            val b = insert(database, "group", "b", "second", "a", 2)
            val c = insert(database, "group", "c", "third", "b", 3)

            assertTrue(a > 0 && b > 0)
            assertEquals(listOf("first", "second"), database.loadContext(c, 10).map { it.text })
            assertEquals(listOf("second"), database.loadContext(c, 1).map { it.text })
        }
    }

    @Test
    fun withoutReferenceUsesOnlyEarlierMessagesFromTheSameGroup() {
        HistoryDatabase(temporaryDirectory.resolve("history.db")).use { database ->
            insert(database, "other", "x", "other group", null, 1)
            insert(database, "group", "a", "one", null, 2)
            insert(database, "group", "b", "two", null, 3)
            val trigger = insert(database, "group", "c", "trigger", null, 4)

            assertEquals(listOf("one", "two"), database.loadContext(trigger, 2).map { it.text })
        }
    }

    @Test
    fun cleanupProtectsAQueuedTriggerAndItsReferenceAncestors() {
        HistoryDatabase(temporaryDirectory.resolve("history.db")).use { database ->
            insert(database, "group", "a", "ancestor", null, 1)
            val triggerEvent = UUID.randomUUID()
            val trigger = database.insertInbound(
                NewInboundMessage(
                    triggerEvent,
                    "group",
                    "member",
                    "b",
                    null,
                    "trigger",
                    "a",
                    Instant.ofEpochMilli(2),
                ),
                emptyList(),
            )
            assertTrue(database.createChatTask(triggerEvent, trigger, "group", Instant.ofEpochMilli(3)))

            val result = database.cleanup(
                CleanupPolicy(1, 1, 1, emptyMap(), 1),
                Instant.parse("2030-01-01T00:00:00Z"),
            )
            assertEquals(0, result.deletedMessages)
            assertEquals(2, result.remainingMessages)
        }
    }

    @Test
    fun failedOrOversizedPureImageIsDeletedUnlessItCarriesAReference() {
        val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
        HistoryDatabase(temporaryDirectory.resolve("history.db"), clock).use { database ->
            val plainImage = database.insertInbound(
                NewInboundMessage(
                    UUID.randomUUID(), "group", "member", "image-1", null, null, null, Instant.EPOCH,
                ),
                listOf(ImageAttachment(0, "https://example.test/image", "image/png")),
            )
            assertEquals(Instant.EPOCH.plusSeconds(3), database.pendingImages(plainImage).single().availableAt)
            database.finishImages(plainImage, emptyList())
            assertEquals(null, database.loadMessage(plainImage))

            val quotedImage = database.insertInbound(
                NewInboundMessage(
                    UUID.randomUUID(), "group", "member", "image-2", null, null, "parent", Instant.EPOCH,
                ),
                listOf(ImageAttachment(0, "https://example.test/image", "image/png")),
            )
            database.finishImages(quotedImage, emptyList())
            assertTrue(database.loadMessage(quotedImage)?.placeholder == true)
        }
    }

    @Test
    fun runningChatTaskReturnsToPendingAfterRestart() {
        val path = temporaryDirectory.resolve("history.db")
        val eventId = UUID.randomUUID()
        HistoryDatabase(path).use { database ->
            val rowId = database.insertInbound(
                NewInboundMessage(
                    eventId, "group", "member", "message", null, "trigger", null, Instant.now(),
                ),
                emptyList(),
            )
            assertTrue(database.createChatTask(eventId, rowId, "group"))
            assertEquals(ChatTaskStatus.RUNNING, database.claimChatTask(eventId)?.status)
        }

        HistoryDatabase(path).use { database ->
            val recovered = database.recoverChatTasks().single()
            assertEquals(eventId, recovered.eventId)
            assertEquals(ChatTaskStatus.PENDING, recovered.status)
        }
    }

    private fun insert(
        database: HistoryDatabase,
        groupId: String,
        messageId: String,
        text: String,
        reference: String?,
        timestamp: Long,
    ): Long = database.insertInbound(
        NewInboundMessage(
            UUID.randomUUID(),
            groupId,
            "member-$messageId",
            messageId,
            null,
            text,
            reference,
            Instant.ofEpochMilli(timestamp),
        ),
        emptyList(),
    )
}
