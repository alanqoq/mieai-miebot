package com.mieai.bot

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.mieai.bot.ai.ChatCompletionRequest
import com.mieai.bot.ai.ChatRole
import com.mieai.bot.ai.ChatTurn
import com.mieai.bot.ai.ModelFailoverManager
import com.mieai.bot.ai.OpenAiChatProvider
import com.mieai.bot.command.CommandOutcome
import com.mieai.bot.command.CommandService
import com.mieai.bot.config.MieAiConfigStore
import com.mieai.bot.delivery.DeliveryTracker
import com.mieai.bot.delivery.OutboundService
import com.mieai.bot.event.AttachmentParser
import com.mieai.bot.event.BotMentionParser
import com.mieai.bot.event.TriggerDecider
import com.mieai.bot.history.ChatTaskRecord
import com.mieai.bot.history.ChatTaskStatus
import com.mieai.bot.history.CleanupPolicy
import com.mieai.bot.history.HistoryDatabase
import com.mieai.bot.history.MessageDirection
import com.mieai.bot.history.NewInboundMessage
import com.mieai.bot.history.StoredMessage
import com.mieai.bot.media.ImageDownloader
import com.mieai.bot.media.ImagePersistenceService
import com.mieai.bot.queue.GroupChatQueue
import com.mieai.qqbot.plugin.api.EventSubscription
import com.mieai.qqbot.plugin.api.MessageTargetType
import com.mieai.qqbot.plugin.api.PluginEvent
import com.mieai.qqbot.plugin.api.PluginRuntimeContext
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

class MieAiEngine(
    private val context: PluginRuntimeContext,
    private val randomPercent: () -> Int = { Random.nextInt(1, 101) },
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val json = jacksonObjectMapper()
    private val configStore = MieAiConfigStore.open(context.configurationFile, context.configuration.content)
    private val database = HistoryDatabase(context.base.dataDirectory.resolve("history.db"))
    private val background: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(2) { task ->
        Thread(task, "mieai-scheduler").apply { isDaemon = true }
    }
    private val downloader = ImageDownloader()
    private val imagePersistence = ImagePersistenceService(
        database,
        downloader,
        background,
        maxBase64Bytes = { configStore.snapshot().storage.maxBase64ImageBytes },
        warning = ::warn,
    )
    private val provider = OpenAiChatProvider(context.httpClient, json)
    private val failover = ModelFailoverManager(warning = ::warn)
    private val commandService = CommandService(configStore, database)
    private val attachments = AttachmentParser(json)
    private val mentions = BotMentionParser(json)
    private val queue = GroupChatQueue(database, ::processChatTask, ::warn)
    private val deliveryTracker = DeliveryTracker(context.base.messageSender, database, scheduler, ::warn)
    private val outbound = OutboundService(context.base.messageSender, database, deliveryTracker::track)
    @Volatile
    private var subscription: EventSubscription? = null

    fun start() {
        check(!closed.get()) { "MieAI engine is closed" }
        imagePersistence.recover()
        queue.recover(database.recoverChatTasks())
        deliveryTracker.start()
        scheduleNextCleanup()
        subscription = context.events.subscribe(
            "mieai-group-chat",
            setOf(GROUP_MESSAGE_CREATE, GROUP_AT_MESSAGE_CREATE),
            ::handle,
        )
    }

    private fun handle(event: PluginEvent): CompletionStage<Void> {
        if (closed.get()) return CompletableFuture.completedFuture(null)
        return try {
            accept(event)
            CompletableFuture.completedFuture(null)
        } catch (failure: Throwable) {
            context.base.logger.error("MieAI failed to accept event ${event.id}", failure)
            CompletableFuture.failedFuture(failure)
        }
    }

    private fun accept(event: PluginEvent) {
        val inbound = event.message ?: return
        if (inbound.replyTarget.type != MessageTargetType.GROUP) return
        val groupId = inbound.replyTarget.id
        val text = inbound.content?.trim()?.ifBlank { null }
        val imageAttachments = attachments.parse(event.rawPayload)
        val isBotMentioned = mentions.isBotMentioned(event.rawPayload)
        if (text == null && imageAttachments.isEmpty() && inbound.referencedMessageId == null) return
        val rowId = database.insertInbound(
            NewInboundMessage(
                event.id,
                groupId,
                inbound.authorId,
                inbound.messageId,
                inbound.eventId,
                text,
                inbound.referencedMessageId,
                event.receivedAt,
            ),
            imageAttachments,
        )
        if (imageAttachments.isNotEmpty()) imagePersistence.schedule(rowId)

        val command = commandService.handle(
            event.id,
            groupId,
            inbound.memberRole,
            text.orEmpty(),
        )
        if (command is CommandOutcome.Handled) {
            sendReplyAsync(rowId, event.id, "command", command.reply)
            return
        }

        val config = configStore.snapshot()
        if (!TriggerDecider.shouldTrigger(
                event.eventType,
                groupId,
                text,
                imageAttachments.isNotEmpty(),
                config.chat,
                isBotMentioned,
                randomPercent,
            )
        ) return

        if (inbound.messageId.isNullOrBlank()) {
            context.base.logger.warn("MieAI ignored triggered event ${event.id}: QQ message id is unavailable")
            return
        }
        if (!database.createChatTask(event.id, rowId, groupId)) return
        if (!queue.enqueue(groupId, event.id, config.queue.maxPendingPerGroup)) {
            database.deletePendingChatTask(event.id)
            sendReplyAsync(rowId, event.id, "queue-full", "当前群的 AI 回复队列已满，请稍后再试。")
        }
    }

    private fun processChatTask(task: ChatTaskRecord) {
        try {
            var config = configStore.snapshot()
            if (!config.chat.isChatEnabled(task.groupId)) {
                database.finishChatTask(task.eventId, ChatTaskStatus.CANCELLED)
                return
            }
            imagePersistence.processNow(task.messageRowId)
            val trigger = database.loadMessage(task.messageRowId) ?: return
            if (!trigger.hasModelContent()) {
                database.finishChatTask(task.eventId, ChatTaskStatus.COMPLETED)
                return
            }
            var contextMessages = database.loadContext(task.messageRowId, config.chat.maxContextMessages)
            // A previous message may still have a delayed image download in progress. Complete
            // those downloads before rebuilding the context so image-capable models see the same
            // history that is persisted for later requests.
            contextMessages.forEach { imagePersistence.processNow(it.id) }
            contextMessages = database.loadContext(task.messageRowId, config.chat.maxContextMessages)
            val turns = (contextMessages + trigger).map(::toChatTurn)
            val answer = failover.execute(
                config.api.primaryModel,
                config.api.fallbackModel,
                config.api.fallbackDurationMinutes.toLong(),
            ) { selectedModel ->
                config = configStore.snapshot()
                provider.complete(
                    ChatCompletionRequest(
                        baseUrl = config.api.baseUrl,
                        apiKey = config.api.apiKey,
                        protocol = config.api.protocol.configValue,
                        model = selectedModel,
                        timeoutSeconds = config.api.requestTimeoutSeconds.toLong(),
                        systemPrompt = config.chat.systemPromptFor(task.groupId),
                        turns = turns,
                        imageUnderstandingEnabled = config.chat.imageUnderstandingEnabled,
                    ),
                )
            }
            if (!configStore.snapshot().chat.isChatEnabled(task.groupId)) {
                database.finishChatTask(task.eventId, ChatTaskStatus.CANCELLED)
                return
            }
            outbound.sendQuoted(trigger, task.eventId, "ai", answer)
            database.finishChatTask(task.eventId, ChatTaskStatus.COMPLETED)
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: CancellationException) {
            // RUNNING is deliberately left durable; the next start resets it to PENDING.
        } catch (failure: Throwable) {
            database.finishChatTask(task.eventId, ChatTaskStatus.FAILED, failure.javaClass.simpleName)
            warn("AI chat task ${task.eventId} failed", failure)
        }
    }

    private fun toChatTurn(message: StoredMessage): ChatTurn = ChatTurn(
        role = if (message.direction == MessageDirection.BOT) ChatRole.ASSISTANT else ChatRole.USER,
        text = message.text,
        authorId = message.authorId,
        images = message.images,
    )

    private fun sendReplyAsync(rowId: Long, eventId: UUID, kind: String, reply: String) {
        background.execute {
            runCatching {
                val trigger = database.loadMessage(rowId) ?: return@runCatching
                if (trigger.platformMessageId.isNullOrBlank()) return@runCatching
                outbound.sendQuoted(trigger, eventId, kind, reply)
            }.onFailure { warn("Unable to send $kind reply for event $eventId", it) }
        }
    }

    private fun scheduleNextCleanup() {
        if (closed.get()) return
        val cleanupAt = LocalTime.parse(configStore.snapshot().storage.cleanupTime)
        val now = ZonedDateTime.now()
        var next = now.withHour(cleanupAt.hour).withMinute(cleanupAt.minute).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val delayMillis = Duration.between(now, next).toMillis().coerceAtLeast(1)
        scheduler.schedule(
            {
                try {
                    val storage = configStore.snapshot().storage
                    database.cleanup(
                        CleanupPolicy(
                            storage.maxMessageAgeDays,
                            storage.maxMessagesTotal,
                            storage.defaultMaxMessagesPerGroup,
                            storage.groupMaxMessages,
                            storage.cleanupDeleteBatchSize,
                        ),
                        Instant.now(),
                    )
                } catch (failure: Throwable) {
                    warn("Scheduled SQLite cleanup failed", failure)
                } finally {
                    runCatching(::scheduleNextCleanup)
                }
            },
            delayMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun warn(message: String, failure: Throwable?) {
        if (failure is LinkageError) {
            val detail = generateSequence<Throwable>(failure) { it.cause }
                .take(4)
                .joinToString(" <- ") {
                    "${it.javaClass.name}: ${it.message.orEmpty().replace(Regex("[\\r\\n]+"), " ").take(500)}"
                }
            context.base.logger.warn("$message ($detail)")
            return
        }
        val suffix = failure?.let { " (${it.javaClass.simpleName})" }.orEmpty()
        context.base.logger.warn(message + suffix)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { subscription?.close() }
        subscription = null
        runCatching(queue::close).onFailure { warn("Unable to stop chat queues", it) }
        scheduler.shutdownNow()
        background.shutdownNow()
        runCatching { scheduler.awaitTermination(10, TimeUnit.SECONDS) }
        runCatching { background.awaitTermination(10, TimeUnit.SECONDS) }
        runCatching(downloader::close)
        runCatching(database::close).onFailure { warn("Unable to close SQLite history", it) }
    }

    private companion object {
        const val GROUP_MESSAGE_CREATE = "GROUP_MESSAGE_CREATE"
        const val GROUP_AT_MESSAGE_CREATE = "GROUP_AT_MESSAGE_CREATE"
    }
}
