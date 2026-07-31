package com.mieai.bot.delivery

import com.mieai.bot.history.HistoryDatabase
import com.mieai.bot.history.StoredMessage
import com.mieai.qqbot.plugin.api.MessageReference
import com.mieai.qqbot.plugin.api.MessageSendOptions
import com.mieai.qqbot.plugin.api.MessageSender
import com.mieai.qqbot.plugin.api.MessageTarget
import com.mieai.qqbot.plugin.api.MessageTargetType
import com.mieai.qqbot.plugin.api.TextMessage
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException

class OutboundService(
    private val sender: MessageSender,
    private val database: HistoryDatabase,
    private val onEnqueued: (Long, UUID) -> Unit = { _, _ -> },
) {
    fun sendQuoted(trigger: StoredMessage, sourceEventId: UUID, kind: String, content: String): UUID {
        val referenceId = requireNotNull(trigger.platformMessageId) { "Trigger message has no platform message id" }
        val cleaned = sanitizeForQq(content)
        val eventKey = "out:$kind:$sourceEventId"
        val deduplicationKey = "mieai:$kind:$sourceEventId"
        // Reserve the bot history row before touching the host Outbox. If the process
        // stops after enqueue succeeds, the content and reference chain are still durable.
        val messageRowId = database.reserveOutbound(
            eventKey = eventKey,
            groupId = trigger.groupId,
            text = cleaned,
            referencedPlatformMessageId = referenceId,
            replyEventId = trigger.replyEventId,
            createdAt = Instant.now(),
        )
        val message = TextMessage(
            target = MessageTarget(MessageTargetType.GROUP, trigger.groupId),
            content = cleaned,
            replyMessageId = referenceId,
            replyEventId = trigger.replyEventId,
            deduplicationKey = deduplicationKey,
            sourceEventId = sourceEventId,
        )
        val future = sender.enqueue(
            message,
            MessageSendOptions(MessageReference(referenceId, ignoreGetMessageError = false)),
        ).toCompletableFuture()
        val receipt = try {
            future.get()
        } catch (failure: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw CancellationException("Message enqueue interrupted")
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        }
        database.attachOutboundJob(
            messageRowId,
            receipt.jobId,
            receipt.queuedAt.takeIf { it != Instant.EPOCH } ?: Instant.now(),
        )
        onEnqueued(messageRowId, receipt.jobId)
        return receipt.jobId
    }

    companion object {
        fun sanitizeForQq(value: String): String {
            val clean = buildString(value.length) {
                value.codePoints().forEach { codePoint ->
                    if (!Character.isISOControl(codePoint) || codePoint in setOf('\n'.code, '\r'.code, '\t'.code)) {
                        appendCodePoint(codePoint)
                    }
                }
            }.trim()
            require(clean.isNotEmpty()) { "Reply is empty after sanitization" }
            if (clean.codePointCount(0, clean.length) <= 4000) return clean
            val end = clean.offsetByCodePoints(0, 4000)
            return clean.substring(0, end)
        }
    }
}
