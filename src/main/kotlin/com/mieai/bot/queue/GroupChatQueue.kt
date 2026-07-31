package com.mieai.bot.queue

import com.mieai.bot.history.ChatTaskRecord
import com.mieai.bot.history.HistoryDatabase
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class GroupChatQueue(
    private val database: HistoryDatabase,
    private val processor: (ChatTaskRecord) -> Unit,
    private val warning: (String, Throwable?) -> Unit = { _, _ -> },
) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val workers = HashMap<String, Worker>()

    fun enqueue(groupId: String, eventId: UUID, maximumWaiting: Int): Boolean {
        while (running.get()) {
            val selection = synchronized(workers) {
                if (!running.get()) return false
                workers[groupId]?.takeIf { it.active }?.let { WorkerSelection(it, false) }
                    ?: Worker(groupId).let {
                        workers[groupId] = it
                        WorkerSelection(it, true)
                    }
            }
            val worker = selection.worker
            if (selection.start) {
                try {
                    executor.submit { runWorker(worker) }
                } catch (_: RuntimeException) {
                    synchronized(worker.monitor) { worker.active = false }
                    synchronized(workers) { workers.remove(groupId, worker) }
                    return false
                }
            }
            val accepted = synchronized(worker.monitor) {
                when {
                    !worker.active -> null
                    worker.queue.size >= maximumWaiting.coerceAtLeast(0) -> false
                    else -> {
                        worker.queue.add(eventId)
                        true
                    }
                }
            }
            if (accepted != null) return accepted
        }
        return false
    }

    fun recover(tasks: List<ChatTaskRecord>) {
        tasks.groupBy(ChatTaskRecord::groupId).forEach { (groupId, groupTasks) ->
            val worker = synchronized(workers) {
                workers[groupId] ?: Worker(groupId).also {
                    workers[groupId] = it
                    executor.submit { runWorker(it) }
                }
            }
            groupTasks.sortedBy(ChatTaskRecord::createdAt).forEach { worker.queue.add(it.eventId) }
        }
    }

    private fun runWorker(worker: Worker) {
        try {
            while (running.get() && worker.active) {
                val eventId = worker.queue.poll(5, TimeUnit.MINUTES)
                if (eventId == null) {
                    synchronized(worker.monitor) {
                        if (worker.queue.isEmpty()) {
                            worker.active = false
                            synchronized(workers) { workers.remove(worker.groupId, worker) }
                            return
                        }
                    }
                    continue
                }
                val task = database.claimChatTask(eventId) ?: continue
                try {
                    processor(task)
                } catch (failure: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                } catch (failure: Throwable) {
                    warning("Chat task ${task.eventId} failed", failure)
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            synchronized(worker.monitor) { worker.active = false }
            synchronized(workers) { workers.remove(worker.groupId, worker) }
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        val currentWorkers = synchronized(workers) {
            val snapshot = workers.values.toList()
            workers.clear()
            snapshot
        }
        currentWorkers.forEach { worker -> synchronized(worker.monitor) { worker.active = false } }
        executor.shutdownNow()
        executor.awaitTermination(10, TimeUnit.SECONDS)
    }

    private class Worker(val groupId: String) {
        val monitor = Any()
        val queue = LinkedBlockingQueue<UUID>()
        @Volatile
        var active = true
    }

    private data class WorkerSelection(val worker: Worker, val start: Boolean)
}
