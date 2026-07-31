package com.mieai.bot.delivery

import com.mieai.bot.history.HistoryDatabase
import com.mieai.bot.history.OpenDelivery
import com.mieai.qqbot.plugin.api.MessageSender
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class DeliveryTracker(
    private val sender: MessageSender,
    private val database: HistoryDatabase,
    private val scheduler: ScheduledExecutorService,
    private val warning: (String, Throwable?) -> Unit = { _, _ -> },
) {
    fun start() {
        scheduler.scheduleWithFixedDelay(::pollSafely, 1, 2, TimeUnit.SECONDS)
    }

    /** Start a short-lived fast poll immediately after an Outbox job is accepted. */
    fun track(messageRowId: Long, jobId: java.util.UUID) {
        scheduleFastPoll(OpenDelivery(messageRowId, jobId), 20)
    }

    private fun pollSafely() {
        runCatching { poll() }.onFailure { warning("Unable to poll message delivery receipts", it) }
    }

    private fun poll() {
        database.openDeliveries().forEach { open ->
            pollOne(open)
        }
    }

    private fun scheduleFastPoll(open: OpenDelivery, remaining: Int) {
        if (remaining <= 0) return
        runCatching {
            scheduler.schedule(
                {
                    val settled = pollOne(open)
                    if (!settled) scheduleFastPoll(open, remaining - 1)
                },
                100,
                TimeUnit.MILLISECONDS,
            )
        }.onFailure { warning("Unable to schedule delivery ${open.jobId} poll", it) }
    }

    /** Returns true when the host returned a terminal receipt or a platform message ID. */
    private fun pollOne(open: OpenDelivery): Boolean {
        val receipt = runCatching { sender.findDelivery(open.jobId).toCompletableFuture().get(5, TimeUnit.SECONDS) }
            .onFailure { warning("Unable to query delivery ${open.jobId}", it) }
            .getOrNull() ?: return false
        database.updateDelivery(open.messageRowId, receipt.state.name, receipt.platformMessageId)
        return receipt.completedAt != null || receipt.platformMessageId != null
    }
}
