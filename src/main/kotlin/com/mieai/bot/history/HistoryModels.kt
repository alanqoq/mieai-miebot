package com.mieai.bot.history

import java.time.Instant
import java.util.UUID

enum class MessageDirection {
    MEMBER,
    BOT,
}

data class ImageAttachment(
    val sequence: Int,
    val url: String,
    val declaredMimeType: String?,
)

data class StoredImage(
    val sequence: Int,
    val mimeType: String,
    val base64: String,
) {
    val encodedBytes: Int = base64.toByteArray(Charsets.US_ASCII).size
}

data class NewInboundMessage(
    val eventId: UUID,
    val groupId: String,
    val authorId: String?,
    val platformMessageId: String?,
    val replyEventId: String?,
    val text: String?,
    val referencedPlatformMessageId: String?,
    val receivedAt: Instant,
)

data class StoredMessage(
    val id: Long,
    val groupId: String,
    val authorId: String?,
    val direction: MessageDirection,
    val text: String?,
    val platformMessageId: String?,
    val replyEventId: String?,
    val referencedPlatformMessageId: String?,
    val hadReference: Boolean,
    val createdAt: Instant,
    val placeholder: Boolean,
    val images: List<StoredImage>,
) {
    fun hasModelContent(): Boolean = !text.isNullOrBlank() || images.isNotEmpty()
}

data class PendingImage(
    val messageRowId: Long,
    val sequence: Int,
    val url: String,
    val declaredMimeType: String?,
    val availableAt: Instant,
)

enum class ChatTaskStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class ChatTaskRecord(
    val eventId: UUID,
    val messageRowId: Long,
    val groupId: String,
    val status: ChatTaskStatus,
    val createdAt: Instant,
)

data class OpenDelivery(
    val messageRowId: Long,
    val jobId: UUID,
)

data class CommandPlan(
    val eventId: UUID,
    val kind: String,
    val value: String,
)

data class CleanupPolicy(
    val maxAgeDays: Int,
    val maxMessagesTotal: Int,
    val defaultMaxMessagesPerGroup: Int,
    val groupMaxMessages: Map<String, Int>,
    val deleteBatchSize: Int,
)

data class CleanupResult(
    val deletedMessages: Int,
    val remainingMessages: Long,
)
