package com.mieai.bot.queue

import com.mieai.bot.history.ChatTaskStatus
import com.mieai.bot.history.HistoryDatabase
import com.mieai.bot.history.NewInboundMessage
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GroupChatQueueTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun differentGroupsRunInParallelWhileWaitingCapacityExcludesTheRunningTask() {
        HistoryDatabase(temporaryDirectory.resolve("history.db")).use { database ->
            val bothRunning = CountDownLatch(2)
            val release = CountDownLatch(1)
            val completed = CountDownLatch(3)
            GroupChatQueue(database, { task ->
                if (task.eventId != waitingEvent) {
                    bothRunning.countDown()
                    assertTrue(bothRunning.await(3, TimeUnit.SECONDS))
                    release.await(3, TimeUnit.SECONDS)
                }
                database.finishChatTask(task.eventId, ChatTaskStatus.COMPLETED)
                completed.countDown()
            }).use { queue ->
                val first = task(database, "group-a", "a1")
                val otherGroup = task(database, "group-b", "b1")
                assertTrue(queue.enqueue("group-a", first, 1))
                assertTrue(queue.enqueue("group-b", otherGroup, 1))
                assertTrue(bothRunning.await(3, TimeUnit.SECONDS))

                waitingEvent = task(database, "group-a", "a2")
                val rejected = task(database, "group-a", "a3")
                assertTrue(queue.enqueue("group-a", waitingEvent, 1))
                assertFalse(queue.enqueue("group-a", rejected, 1))
                database.deletePendingChatTask(rejected)

                release.countDown()
                assertTrue(completed.await(5, TimeUnit.SECONDS))
            }
        }
    }

    private fun task(database: HistoryDatabase, groupId: String, messageId: String): UUID {
        val eventId = UUID.randomUUID()
        val rowId = database.insertInbound(
            NewInboundMessage(
                eventId,
                groupId,
                "member",
                messageId,
                null,
                "text",
                null,
                Instant.now(),
            ),
            emptyList(),
        )
        assertTrue(database.createChatTask(eventId, rowId, groupId))
        return eventId
    }

    companion object {
        @Volatile
        private var waitingEvent: UUID = UUID(0, 0)
    }
}
