package com.mieai.bot.history

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Statement
import java.sql.Types
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class HistoryDatabase(
    databaseFile: Path,
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    private val lock = ReentrantLock()
    private val connection: Connection

    init {
        Files.createDirectories(requireNotNull(databaseFile.toAbsolutePath().normalize().parent))
        Class.forName("org.sqlite.JDBC")
        connection = DriverManager.getConnection("jdbc:sqlite:${databaseFile.toAbsolutePath().normalize()}")
        initialize()
    }

    private fun initialize() = lock.withLock {
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA foreign_keys = ON")
            statement.execute("PRAGMA journal_mode = WAL")
            statement.execute("PRAGMA synchronous = NORMAL")
            statement.execute("PRAGMA busy_timeout = 5000")
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    event_key TEXT NOT NULL UNIQUE,
                    group_id TEXT NOT NULL,
                    author_id TEXT,
                    direction TEXT NOT NULL,
                    text_content TEXT,
                    platform_message_id TEXT,
                    reply_event_id TEXT,
                    referenced_platform_message_id TEXT,
                    had_reference INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL,
                    placeholder INTEGER NOT NULL DEFAULT 0,
                    outbox_job_id TEXT,
                    delivery_state TEXT
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS message_images (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    message_row_id INTEGER NOT NULL,
                    sequence_index INTEGER NOT NULL,
                    mime_type TEXT NOT NULL,
                    base64_content TEXT NOT NULL,
                    encoded_bytes INTEGER NOT NULL,
                    FOREIGN KEY (message_row_id) REFERENCES messages(id) ON DELETE CASCADE,
                    UNIQUE(message_row_id, sequence_index)
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS pending_images (
                    message_row_id INTEGER NOT NULL,
                    sequence_index INTEGER NOT NULL,
                    image_url TEXT NOT NULL,
                    declared_mime_type TEXT,
                    available_at INTEGER NOT NULL,
                    FOREIGN KEY (message_row_id) REFERENCES messages(id) ON DELETE CASCADE,
                    PRIMARY KEY(message_row_id, sequence_index)
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS chat_tasks (
                    event_id TEXT PRIMARY KEY,
                    message_row_id INTEGER NOT NULL UNIQUE,
                    group_id TEXT NOT NULL,
                    status TEXT NOT NULL,
                    last_error TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    FOREIGN KEY (message_row_id) REFERENCES messages(id) ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS command_plans (
                    event_id TEXT PRIMARY KEY,
                    command_kind TEXT NOT NULL,
                    command_value TEXT NOT NULL,
                    completed INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute("CREATE INDEX IF NOT EXISTS idx_messages_group_time ON messages(group_id, created_at, id)")
            statement.execute("CREATE INDEX IF NOT EXISTS idx_messages_platform ON messages(group_id, platform_message_id)")
            statement.execute("CREATE INDEX IF NOT EXISTS idx_messages_created ON messages(created_at, id)")
            statement.execute("CREATE INDEX IF NOT EXISTS idx_tasks_status ON chat_tasks(status, created_at)")
            statement.execute("CREATE INDEX IF NOT EXISTS idx_pending_images_time ON pending_images(available_at)")
        }
    }

    fun insertInbound(message: NewInboundMessage, attachments: List<ImageAttachment>): Long = lock.withLock {
        transaction {
            val eventKey = "in:${message.eventId}"
            val existing = findMessageIdByEventKey(eventKey)
            val rowId = existing ?: insertMessage(
                eventKey = eventKey,
                groupId = message.groupId,
                authorId = message.authorId,
                direction = MessageDirection.MEMBER,
                text = message.text?.trim()?.ifBlank { null },
                platformMessageId = message.platformMessageId,
                replyEventId = message.replyEventId,
                referencedPlatformMessageId = message.referencedPlatformMessageId,
                hadReference = message.referencedPlatformMessageId != null,
                createdAt = message.receivedAt,
                placeholder = message.text.isNullOrBlank() && attachments.isEmpty(),
                outboxJobId = null,
                deliveryState = null,
            )
            if (existing == null && attachments.isNotEmpty()) {
                val availableAt = clock.instant().plusSeconds(3).toEpochMilli()
                connection.prepareStatement(
                    """
                    INSERT OR IGNORE INTO pending_images(
                        message_row_id, sequence_index, image_url, declared_mime_type, available_at
                    ) VALUES (?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    attachments.sortedBy(ImageAttachment::sequence).forEach { attachment ->
                        statement.setLong(1, rowId)
                        statement.setInt(2, attachment.sequence)
                        statement.setString(3, attachment.url)
                        statement.setNullableString(4, attachment.declaredMimeType)
                        statement.setLong(5, availableAt)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
                connection.prepareStatement("UPDATE messages SET placeholder=0 WHERE id=?").use {
                    it.setLong(1, rowId)
                    it.executeUpdate()
                }
            }
            rowId
        }
    }

    fun reserveOutbound(
        eventKey: String,
        groupId: String,
        text: String,
        referencedPlatformMessageId: String,
        replyEventId: String?,
        createdAt: Instant,
    ): Long = lock.withLock {
        transaction {
            val existing = findMessageIdByEventKey(eventKey)
            if (existing != null) return@transaction existing
            insertMessage(
                eventKey = eventKey,
                groupId = groupId,
                authorId = null,
                direction = MessageDirection.BOT,
                text = text,
                platformMessageId = null,
                replyEventId = replyEventId,
                referencedPlatformMessageId = referencedPlatformMessageId,
                hadReference = true,
                createdAt = createdAt,
                placeholder = false,
                outboxJobId = null,
                deliveryState = "RESERVED",
            )
        }
    }

    fun attachOutboundJob(messageRowId: Long, jobId: UUID, queuedAt: Instant) = lock.withLock {
        connection.prepareStatement(
            """
            UPDATE messages
            SET outbox_job_id=?, delivery_state='PENDING', created_at=?
            WHERE id=? AND direction='BOT'
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, jobId.toString())
            statement.setLong(2, queuedAt.toEpochMilli())
            statement.setLong(3, messageRowId)
            check(statement.executeUpdate() == 1) { "Reserved outbound message no longer exists" }
        }
    }

    fun pendingImages(messageRowId: Long): List<PendingImage> = lock.withLock {
        connection.prepareStatement(
            "SELECT * FROM pending_images WHERE message_row_id=? ORDER BY sequence_index",
        ).use { statement ->
            statement.setLong(1, messageRowId)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            PendingImage(
                                messageRowId,
                                rows.getInt("sequence_index"),
                                rows.getString("image_url"),
                                rows.getString("declared_mime_type"),
                                Instant.ofEpochMilli(rows.getLong("available_at")),
                            ),
                        )
                    }
                }
            }
        }
    }

    fun allMessagesWithPendingImages(): List<Long> = lock.withLock {
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT DISTINCT message_row_id FROM pending_images ORDER BY message_row_id").use { rows ->
                buildList { while (rows.next()) add(rows.getLong(1)) }
            }
        }
    }

    fun finishImages(messageRowId: Long, images: List<StoredImage>) = lock.withLock {
        transaction {
            connection.prepareStatement("DELETE FROM message_images WHERE message_row_id=?").use {
                it.setLong(1, messageRowId)
                it.executeUpdate()
            }
            connection.prepareStatement(
                """
                INSERT INTO message_images(message_row_id, sequence_index, mime_type, base64_content, encoded_bytes)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                images.sortedBy(StoredImage::sequence).forEach { image ->
                    statement.setLong(1, messageRowId)
                    statement.setInt(2, image.sequence)
                    statement.setString(3, image.mimeType)
                    statement.setString(4, image.base64)
                    statement.setInt(5, image.encodedBytes)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
            connection.prepareStatement("DELETE FROM pending_images WHERE message_row_id=?").use {
                it.setLong(1, messageRowId)
                it.executeUpdate()
            }
            if (images.isEmpty()) {
                val deleted = connection.prepareStatement(
                    """
                    DELETE FROM messages
                    WHERE id=? AND had_reference=0
                      AND (text_content IS NULL OR trim(text_content)='')
                    """.trimIndent(),
                ).use {
                    it.setLong(1, messageRowId)
                    it.executeUpdate()
                }
                if (deleted > 0) return@transaction
            }
            connection.prepareStatement(
                """
                UPDATE messages
                SET placeholder = CASE
                    WHEN (text_content IS NULL OR trim(text_content)='') AND ?=0 THEN 1
                    ELSE 0
                END
                WHERE id=?
                """.trimIndent(),
            ).use {
                it.setInt(1, images.size)
                it.setLong(2, messageRowId)
                it.executeUpdate()
            }
        }
    }

    fun loadMessage(rowId: Long): StoredMessage? = lock.withLock { loadMessages(listOf(rowId))[rowId] }

    fun loadContext(triggerRowId: Long, maximum: Int): List<StoredMessage> = lock.withLock {
        if (maximum <= 0) return@withLock emptyList()
        val trigger = loadMessages(listOf(triggerRowId))[triggerRowId] ?: return@withLock emptyList()
        if (trigger.hadReference) return@withLock loadReferenceContext(trigger, maximum)

        val ids = ArrayList<Long>()
        connection.prepareStatement(
            """
            SELECT id FROM messages
            WHERE group_id=?
              AND (created_at < ? OR (created_at=? AND id < ?))
              AND ((text_content IS NOT NULL AND trim(text_content)!='') OR EXISTS(
                    SELECT 1 FROM message_images image WHERE image.message_row_id=messages.id
              ) OR EXISTS(
                    SELECT 1 FROM pending_images pending WHERE pending.message_row_id=messages.id
              ))
            ORDER BY created_at DESC, id DESC
            LIMIT ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, trigger.groupId)
            statement.setLong(2, trigger.createdAt.toEpochMilli())
            statement.setLong(3, trigger.createdAt.toEpochMilli())
            statement.setLong(4, trigger.id)
            statement.setInt(5, maximum)
            statement.executeQuery().use { rows -> while (rows.next()) ids += rows.getLong(1) }
        }
        val records = loadMessages(ids)
        ids.asReversed().mapNotNull(records::get).filter { message ->
            message.hasModelContent() || hasPendingImages(message.id)
        }
    }

    private fun loadReferenceContext(trigger: StoredMessage, maximum: Int): List<StoredMessage> {
        val chain = ArrayList<StoredMessage>()
        val visitedRows = HashSet<Long>()
        val visitedPlatformIds = HashSet<String>()
        var groupId = trigger.groupId
        var next = trigger.referencedPlatformMessageId
        var contextCount = 0
        while (next != null && contextCount < maximum && visitedPlatformIds.add(next)) {
            val parentId = findMessageIdByPlatformId(groupId, next) ?: break
            if (!visitedRows.add(parentId)) break
            val parent = loadMessages(listOf(parentId))[parentId] ?: break
            if (parent.hasModelContent() || hasPendingImages(parent.id)) {
                chain += parent
                contextCount++
            }
            groupId = parent.groupId
            next = if (parent.hadReference) parent.referencedPlatformMessageId else null
        }
        return chain.asReversed()
    }

    private fun hasPendingImages(messageRowId: Long): Boolean = connection.prepareStatement(
        "SELECT 1 FROM pending_images WHERE message_row_id=? LIMIT 1",
    ).use { statement ->
        statement.setLong(1, messageRowId)
        statement.executeQuery().use { rows -> rows.next() }
    }

    fun createChatTask(eventId: UUID, messageRowId: Long, groupId: String, now: Instant = Instant.now()): Boolean =
        lock.withLock {
            connection.prepareStatement(
                """
                INSERT OR IGNORE INTO chat_tasks(
                    event_id, message_row_id, group_id, status, created_at, updated_at
                ) VALUES (?, ?, ?, 'PENDING', ?, ?)
                """.trimIndent(),
            ).use {
                it.setString(1, eventId.toString())
                it.setLong(2, messageRowId)
                it.setString(3, groupId)
                it.setLong(4, now.toEpochMilli())
                it.setLong(5, now.toEpochMilli())
                it.executeUpdate() == 1
            }
        }

    fun deletePendingChatTask(eventId: UUID) = lock.withLock {
        connection.prepareStatement("DELETE FROM chat_tasks WHERE event_id=? AND status='PENDING'").use {
            it.setString(1, eventId.toString())
            it.executeUpdate()
        }
    }

    fun recoverChatTasks(): List<ChatTaskRecord> = lock.withLock {
        transaction {
            connection.createStatement().use {
                it.executeUpdate("UPDATE chat_tasks SET status='PENDING' WHERE status='RUNNING'")
            }
            selectChatTasks("status='PENDING'")
        }
    }

    fun claimChatTask(eventId: UUID): ChatTaskRecord? = lock.withLock {
        val updated = connection.prepareStatement(
            "UPDATE chat_tasks SET status='RUNNING', updated_at=? WHERE event_id=? AND status='PENDING'",
        ).use {
            it.setLong(1, Instant.now().toEpochMilli())
            it.setString(2, eventId.toString())
            it.executeUpdate()
        }
        if (updated == 0) null else selectChatTasks("event_id=?", eventId.toString()).firstOrNull()
    }

    fun finishChatTask(eventId: UUID, status: ChatTaskStatus, error: String? = null) = lock.withLock {
        require(status in setOf(ChatTaskStatus.COMPLETED, ChatTaskStatus.FAILED, ChatTaskStatus.CANCELLED))
        connection.prepareStatement(
            "UPDATE chat_tasks SET status=?, last_error=?, updated_at=? WHERE event_id=?",
        ).use {
            it.setString(1, status.name)
            it.setNullableString(2, error?.take(500))
            it.setLong(3, Instant.now().toEpochMilli())
            it.setString(4, eventId.toString())
            it.executeUpdate()
        }
    }

    fun cancelPendingTasks(groupId: String) = lock.withLock {
        connection.prepareStatement(
            "UPDATE chat_tasks SET status='CANCELLED', updated_at=? WHERE group_id=? AND status='PENDING'",
        ).use {
            it.setLong(1, Instant.now().toEpochMilli())
            it.setString(2, groupId)
            it.executeUpdate()
        }
    }

    fun commandPlan(eventId: UUID, kind: String, value: String, now: Instant = Instant.now()): CommandPlan =
        lock.withLock {
            connection.prepareStatement(
                """
                INSERT OR IGNORE INTO command_plans(
                    event_id, command_kind, command_value, completed, created_at, updated_at
                ) VALUES (?, ?, ?, 0, ?, ?)
                """.trimIndent(),
            ).use {
                it.setString(1, eventId.toString())
                it.setString(2, kind)
                it.setString(3, value)
                it.setLong(4, now.toEpochMilli())
                it.setLong(5, now.toEpochMilli())
                it.executeUpdate()
            }
            connection.prepareStatement(
                "SELECT command_kind, command_value FROM command_plans WHERE event_id=?",
            ).use {
                it.setString(1, eventId.toString())
                it.executeQuery().use { rows ->
                    check(rows.next()) { "Command plan was not persisted" }
                    CommandPlan(
                        eventId,
                        rows.getString("command_kind"),
                        rows.getString("command_value"),
                    )
                }
            }
        }

    fun openDeliveries(): List<OpenDelivery> = lock.withLock {
        connection.prepareStatement(
            """
            SELECT id, outbox_job_id FROM messages
            WHERE direction='BOT' AND outbox_job_id IS NOT NULL
              AND (delivery_state IS NULL OR delivery_state IN ('PENDING','IN_PROGRESS','RETRY_WAIT'))
            ORDER BY created_at, id
            """.trimIndent(),
        ).use { statement ->
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        runCatching { UUID.fromString(rows.getString("outbox_job_id")) }
                            .getOrNull()
                            ?.let { add(OpenDelivery(rows.getLong("id"), it)) }
                    }
                }
            }
        }
    }

    fun updateDelivery(messageRowId: Long, state: String, platformMessageId: String?) = lock.withLock {
        connection.prepareStatement(
            "UPDATE messages SET delivery_state=?, platform_message_id=COALESCE(?, platform_message_id) WHERE id=?",
        ).use {
            it.setString(1, state)
            it.setNullableString(2, platformMessageId)
            it.setLong(3, messageRowId)
            it.executeUpdate()
        }
    }

    fun cleanup(policy: CleanupPolicy, now: Instant = Instant.now()): CleanupResult = lock.withLock {
        val protectedIds = protectedMessageIds()
        var deleted = 0
        val batch = policy.deleteBatchSize.coerceAtLeast(1)

        if (policy.maxAgeDays > 0) {
            val cutoff = now.minus(policy.maxAgeDays.toLong(), ChronoUnit.DAYS).toEpochMilli()
            while (true) {
                val count = deleteOldest("created_at < ?", listOf(cutoff), batch, protectedIds)
                deleted += count
                if (count < batch) break
            }
        }

        groupIds().forEach { groupId ->
            val limit = policy.groupMaxMessages[groupId] ?: policy.defaultMaxMessagesPerGroup
            if (limit > 0) {
                while (countMessages("group_id=?", listOf(groupId)) > limit) {
                    val count = deleteOldest("group_id=?", listOf(groupId), batch, protectedIds)
                    deleted += count
                    if (count == 0) break
                }
            }
        }

        if (policy.maxMessagesTotal > 0) {
            while (countMessages("1=1", emptyList()) > policy.maxMessagesTotal) {
                val count = deleteOldest("1=1", emptyList(), batch, protectedIds)
                deleted += count
                if (count == 0) break
            }
        }
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                DELETE FROM command_plans
                WHERE NOT EXISTS (
                    SELECT 1 FROM messages
                    WHERE messages.event_key = 'in:' || command_plans.event_id
                )
                """.trimIndent(),
            )
        }
        CleanupResult(deleted, countMessages("1=1", emptyList()))
    }

    private fun protectedMessageIds(): Set<Long> {
        val protected = HashSet<Long>()
        val queue = ArrayDeque<Long>()
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT message_row_id FROM chat_tasks WHERE status IN ('PENDING','RUNNING')",
            ).use { rows -> while (rows.next()) queue.add(rows.getLong(1)) }
            statement.executeQuery(
                """
                SELECT id FROM messages
                WHERE direction='BOT' AND (
                    (outbox_job_id IS NULL AND delivery_state='RESERVED') OR
                    (outbox_job_id IS NOT NULL AND (
                        delivery_state IS NULL OR delivery_state IN ('PENDING','IN_PROGRESS','RETRY_WAIT')
                    ))
                )
                """.trimIndent(),
            ).use { rows -> while (rows.next()) queue.add(rows.getLong(1)) }
        }
        while (queue.isNotEmpty()) {
            val rowId = queue.removeFirst()
            if (!protected.add(rowId)) continue
            val message = loadMessages(listOf(rowId))[rowId] ?: continue
            val parentPlatformId = message.referencedPlatformMessageId ?: continue
            findMessageIdByPlatformId(message.groupId, parentPlatformId)?.let(queue::addLast)
        }
        return protected
    }

    private fun deleteOldest(where: String, args: List<Any>, limit: Int, protectedIds: Set<Long>): Int {
        val candidates = ArrayList<Long>()
        connection.prepareStatement(
            "SELECT id FROM messages WHERE $where ORDER BY created_at, id LIMIT ?",
        ).use { statement ->
            statement.bindAll(args)
            statement.setInt(args.size + 1, limit + protectedIds.size)
            statement.executeQuery().use { rows ->
                while (rows.next() && candidates.size < limit) {
                    rows.getLong(1).takeIf { it !in protectedIds }?.let(candidates::add)
                }
            }
        }
        if (candidates.isEmpty()) return 0
        val placeholders = candidates.joinToString(",") { "?" }
        return connection.prepareStatement("DELETE FROM messages WHERE id IN ($placeholders)").use { statement ->
            candidates.forEachIndexed { index, id -> statement.setLong(index + 1, id) }
            statement.executeUpdate()
        }
    }

    private fun groupIds(): List<String> = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT DISTINCT group_id FROM messages").use { rows ->
            buildList { while (rows.next()) add(rows.getString(1)) }
        }
    }

    private fun countMessages(where: String, args: List<Any>): Long =
        connection.prepareStatement("SELECT COUNT(*) FROM messages WHERE $where").use { statement ->
            statement.bindAll(args)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else 0L }
        }

    private fun selectChatTasks(where: String, vararg args: Any): List<ChatTaskRecord> =
        connection.prepareStatement(
            "SELECT event_id, message_row_id, group_id, status, created_at FROM chat_tasks WHERE $where ORDER BY created_at",
        ).use { statement ->
            statement.bindAll(args.toList())
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            ChatTaskRecord(
                                UUID.fromString(rows.getString("event_id")),
                                rows.getLong("message_row_id"),
                                rows.getString("group_id"),
                                ChatTaskStatus.valueOf(rows.getString("status")),
                                Instant.ofEpochMilli(rows.getLong("created_at")),
                            ),
                        )
                    }
                }
            }
        }

    private fun findMessageIdByEventKey(eventKey: String): Long? = connection.prepareStatement(
        "SELECT id FROM messages WHERE event_key=?",
    ).use {
        it.setString(1, eventKey)
        it.executeQuery().use { rows -> rows.singleLongOrNull() }
    }

    private fun findMessageIdByPlatformId(groupId: String, platformMessageId: String): Long? =
        connection.prepareStatement(
            """
            SELECT id FROM messages
            WHERE group_id=? AND platform_message_id=?
            ORDER BY created_at DESC, id DESC LIMIT 1
            """.trimIndent(),
        ).use {
            it.setString(1, groupId)
            it.setString(2, platformMessageId)
            it.executeQuery().use { rows -> rows.singleLongOrNull() }
        }

    private fun insertMessage(
        eventKey: String,
        groupId: String,
        authorId: String?,
        direction: MessageDirection,
        text: String?,
        platformMessageId: String?,
        replyEventId: String?,
        referencedPlatformMessageId: String?,
        hadReference: Boolean,
        createdAt: Instant,
        placeholder: Boolean,
        outboxJobId: UUID?,
        deliveryState: String?,
    ): Long = connection.prepareStatement(
        """
        INSERT INTO messages(
            event_key, group_id, author_id, direction, text_content, platform_message_id,
            reply_event_id, referenced_platform_message_id, had_reference, created_at,
            placeholder, outbox_job_id, delivery_state
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
        Statement.RETURN_GENERATED_KEYS,
    ).use { statement ->
        statement.setString(1, eventKey)
        statement.setString(2, groupId)
        statement.setNullableString(3, authorId)
        statement.setString(4, direction.name)
        statement.setNullableString(5, text)
        statement.setNullableString(6, platformMessageId)
        statement.setNullableString(7, replyEventId)
        statement.setNullableString(8, referencedPlatformMessageId)
        statement.setInt(9, if (hadReference) 1 else 0)
        statement.setLong(10, createdAt.toEpochMilli())
        statement.setInt(11, if (placeholder) 1 else 0)
        statement.setNullableString(12, outboxJobId?.toString())
        statement.setNullableString(13, deliveryState)
        statement.executeUpdate()
        statement.generatedKeys.use { keys ->
            if (!keys.next()) error("SQLite did not return an inserted message id")
            keys.getLong(1)
        }
    }

    private fun loadMessages(ids: List<Long>): Map<Long, StoredMessage> {
        if (ids.isEmpty()) return emptyMap()
        val placeholders = ids.joinToString(",") { "?" }
        val messages = LinkedHashMap<Long, StoredMessage>()
        connection.prepareStatement("SELECT * FROM messages WHERE id IN ($placeholders)").use { statement ->
            ids.forEachIndexed { index, id -> statement.setLong(index + 1, id) }
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    val id = rows.getLong("id")
                    messages[id] = StoredMessage(
                        id,
                        rows.getString("group_id"),
                        rows.getString("author_id"),
                        MessageDirection.valueOf(rows.getString("direction")),
                        rows.getString("text_content"),
                        rows.getString("platform_message_id"),
                        rows.getString("reply_event_id"),
                        rows.getString("referenced_platform_message_id"),
                        rows.getInt("had_reference") != 0,
                        Instant.ofEpochMilli(rows.getLong("created_at")),
                        rows.getInt("placeholder") != 0,
                        emptyList(),
                    )
                }
            }
        }
        val images = HashMap<Long, MutableList<StoredImage>>()
        connection.prepareStatement(
            "SELECT * FROM message_images WHERE message_row_id IN ($placeholders) ORDER BY sequence_index",
        ).use { statement ->
            ids.forEachIndexed { index, id -> statement.setLong(index + 1, id) }
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    images.getOrPut(rows.getLong("message_row_id")) { ArrayList() }.add(
                        StoredImage(
                            rows.getInt("sequence_index"),
                            rows.getString("mime_type"),
                            rows.getString("base64_content"),
                        ),
                    )
                }
            }
        }
        return messages.mapValues { (id, value) -> value.copy(images = images[id].orEmpty()) }
    }

    private fun <T> transaction(block: () -> T): T {
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            val result = block()
            connection.commit()
            return result
        } catch (failure: Throwable) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = previousAutoCommit
        }
    }

    override fun close() = lock.withLock { connection.close() }
}

private fun java.sql.PreparedStatement.setNullableString(index: Int, value: String?) {
    if (value == null) setNull(index, Types.VARCHAR) else setString(index, value)
}

private fun java.sql.PreparedStatement.bindAll(values: List<Any>) {
    values.forEachIndexed { index, value ->
        when (value) {
            is String -> setString(index + 1, value)
            is Int -> setInt(index + 1, value)
            is Long -> setLong(index + 1, value)
            else -> setObject(index + 1, value)
        }
    }
}

private fun ResultSet.singleLongOrNull(): Long? = if (next()) getLong(1) else null
