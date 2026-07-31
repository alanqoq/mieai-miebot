package com.mieai.bot.media

import com.mieai.bot.history.HistoryDatabase
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService

class ImagePersistenceService(
    private val database: HistoryDatabase,
    private val downloader: ImageDownloader,
    private val executor: ExecutorService,
    private val maxBase64Bytes: () -> Long,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val warning: (String, Throwable?) -> Unit = { _, _ -> },
) {
    private val rowLocks = ConcurrentHashMap<Long, Any>()

    fun schedule(messageRowId: Long) {
        executor.execute {
            runCatching { processNow(messageRowId) }
                .onFailure { warning("Image persistence failed for message row $messageRowId", it) }
        }
    }

    fun recover() {
        database.allMessagesWithPendingImages().forEach(::schedule)
    }

    fun processNow(messageRowId: Long) {
        val monitor = rowLocks.computeIfAbsent(messageRowId) { Any() }
        try {
            synchronized(monitor) {
                val pending = database.pendingImages(messageRowId)
                if (pending.isEmpty()) return
                val availableAt = pending.maxOf { it.availableAt }
                val remaining = availableAt.toEpochMilli() - clock.millis()
                if (remaining > 0) Thread.sleep(remaining)
                val images = pending.mapNotNull { attachment ->
                    runCatching { downloader.download(attachment, maxBase64Bytes()) }
                        .onFailure { warning("Image download failed", it) }
                        .getOrNull()
                }
                database.finishImages(messageRowId, images)
            }
        } finally {
            rowLocks.remove(messageRowId, monitor)
        }
    }
}
