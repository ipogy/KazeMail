package com.kazemail.app.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import com.kazemail.app.data.db.AttachmentEntity
import com.kazemail.app.data.db.KazeMailDatabase
import com.kazemail.app.data.db.MessageBodyEntity
import com.kazemail.app.data.db.MessageLocalStateEntity
import com.kazemail.app.data.db.MessageMetaEntity
import com.kazemail.app.data.db.ThreadSummaryTuple
import com.kazemail.app.filter.FilterEvaluationTarget
import com.kazemail.app.filter.LocalFilterEngine
import com.kazemail.app.sync.MailCoreEngine
import com.kazemail.app.sync.NonDestructiveSyncEngine
import kotlinx.coroutines.flow.Flow
import java.io.File

enum class InboxQuickFilter(val label: String) {
    ALL("すべて"),
    UNREAD("未読"),
    STARRED("スター"),
    WITH_ATTACHMENTS("添付あり"),
}

data class LoadedMessageDetail(
    val meta: MessageMetaEntity,
    val body: MessageBodyEntity?,
    val attachments: List<AttachmentEntity>,
    val isBodyTruncated: Boolean,
    val isLocallyArchived: Boolean,
)

class MessageRepository(
    private val db: KazeMailDatabase,
    private val mailCore: MailCoreEngine,
    private val syncEngine: NonDestructiveSyncEngine,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    fun pagedFlatMessages(
        folderId: Long,
        filter: InboxQuickFilter = InboxQuickFilter.ALL,
    ): Flow<PagingData<MessageMetaEntity>> =
        Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                initialLoadSize = PAGE_SIZE,
                prefetchDistance = PREFETCH_DISTANCE,
                maxSize = MAX_PAGING_WINDOW_SIZE,
                enablePlaceholders = false,
            ),
            pagingSourceFactory = {
                db.messageMetaDao().pagingSourceFlatFiltered(
                    folderId = folderId,
                    onlyUnread = if (filter == InboxQuickFilter.UNREAD) 1 else 0,
                    onlyFlagged = if (filter == InboxQuickFilter.STARRED) 1 else 0,
                    onlyAttachments = if (filter == InboxQuickFilter.WITH_ATTACHMENTS) 1 else 0,
                )
            },
        ).flow

    fun pagedThreads(
        folderId: Long,
        filter: InboxQuickFilter = InboxQuickFilter.ALL,
    ): Flow<PagingData<ThreadSummaryTuple>> =
        Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                initialLoadSize = PAGE_SIZE,
                prefetchDistance = PREFETCH_DISTANCE,
                maxSize = MAX_PAGING_WINDOW_SIZE,
                enablePlaceholders = false,
            ),
            pagingSourceFactory = {
                db.messageMetaDao().pagingSourceThreadsFiltered(
                    folderId = folderId,
                    onlyUnread = if (filter == InboxQuickFilter.UNREAD) 1 else 0,
                    onlyFlagged = if (filter == InboxQuickFilter.STARRED) 1 else 0,
                    onlyAttachments = if (filter == InboxQuickFilter.WITH_ATTACHMENTS) 1 else 0,
                )
            },
        ).flow

    fun pagedLocallyArchived(accountId: Long): Flow<PagingData<MessageMetaEntity>> =
        Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                initialLoadSize = PAGE_SIZE,
                prefetchDistance = PREFETCH_DISTANCE,
                maxSize = MAX_PAGING_WINDOW_SIZE,
                enablePlaceholders = false,
            ),
            pagingSourceFactory = { db.messageMetaDao().pagingSourceLocallyArchived(accountId) },
        ).flow

    fun observeFlatPage(
        folderId: Long,
        filter: InboxQuickFilter,
        pageIndex: Int,
        pageSize: Int = PAGE_SIZE,
    ): Flow<List<MessageMetaEntity>> =
        db.messageMetaDao().observeFlatPage(
            folderId = folderId,
            onlyUnread = if (filter == InboxQuickFilter.UNREAD) 1 else 0,
            onlyFlagged = if (filter == InboxQuickFilter.STARRED) 1 else 0,
            onlyAttachments = if (filter == InboxQuickFilter.WITH_ATTACHMENTS) 1 else 0,
            limit = pageSize,
            offset = pageIndex.coerceAtLeast(0) * pageSize,
        )

    fun observeFlatCount(
        folderId: Long,
        filter: InboxQuickFilter,
    ): Flow<Int> =
        db.messageMetaDao().observeFlatCount(
            folderId = folderId,
            onlyUnread = if (filter == InboxQuickFilter.UNREAD) 1 else 0,
            onlyFlagged = if (filter == InboxQuickFilter.STARRED) 1 else 0,
            onlyAttachments = if (filter == InboxQuickFilter.WITH_ATTACHMENTS) 1 else 0,
        )

    fun observeThreadPage(
        folderId: Long,
        filter: InboxQuickFilter,
        pageIndex: Int,
        pageSize: Int = PAGE_SIZE,
    ): Flow<List<ThreadSummaryTuple>> =
        db.messageMetaDao().observeThreadPage(
            folderId = folderId,
            onlyUnread = if (filter == InboxQuickFilter.UNREAD) 1 else 0,
            onlyFlagged = if (filter == InboxQuickFilter.STARRED) 1 else 0,
            onlyAttachments = if (filter == InboxQuickFilter.WITH_ATTACHMENTS) 1 else 0,
            limit = pageSize,
            offset = pageIndex.coerceAtLeast(0) * pageSize,
        )

    fun observeThreadCount(
        folderId: Long,
        filter: InboxQuickFilter,
    ): Flow<Int> =
        db.messageMetaDao().observeThreadCount(
            folderId = folderId,
            onlyUnread = if (filter == InboxQuickFilter.UNREAD) 1 else 0,
            onlyFlagged = if (filter == InboxQuickFilter.STARRED) 1 else 0,
            onlyAttachments = if (filter == InboxQuickFilter.WITH_ATTACHMENTS) 1 else 0,
        )

    fun observeArchivedPage(
        accountId: Long,
        pageIndex: Int,
        pageSize: Int = PAGE_SIZE,
    ): Flow<List<MessageMetaEntity>> =
        db.messageMetaDao().observeArchivedPage(
            accountId = accountId,
            limit = pageSize,
            offset = pageIndex.coerceAtLeast(0) * pageSize,
        )

    fun observeLocalCategoryFlatPage(
        accountId: Long,
        categoryName: String,
        filter: InboxQuickFilter,
        pageIndex: Int,
        pageSize: Int = PAGE_SIZE,
    ): Flow<List<MessageMetaEntity>> =
        db.messageMetaDao().observeLocalCategoryFlatPage(
            accountId = accountId,
            categoryName = categoryName,
            onlyUnread = if (filter == InboxQuickFilter.UNREAD) 1 else 0,
            onlyFlagged = if (filter == InboxQuickFilter.STARRED) 1 else 0,
            onlyAttachments = if (filter == InboxQuickFilter.WITH_ATTACHMENTS) 1 else 0,
            limit = pageSize,
            offset = pageIndex.coerceAtLeast(0) * pageSize,
        )

    fun observeLocalCategoryFlatCount(
        accountId: Long,
        categoryName: String,
        filter: InboxQuickFilter,
    ): Flow<Int> =
        db.messageMetaDao().observeLocalCategoryFlatCount(
            accountId = accountId,
            categoryName = categoryName,
            onlyUnread = if (filter == InboxQuickFilter.UNREAD) 1 else 0,
            onlyFlagged = if (filter == InboxQuickFilter.STARRED) 1 else 0,
            onlyAttachments = if (filter == InboxQuickFilter.WITH_ATTACHMENTS) 1 else 0,
        )

    fun observeLocalCategoryThreadPage(
        accountId: Long,
        categoryName: String,
        filter: InboxQuickFilter,
        pageIndex: Int,
        pageSize: Int = PAGE_SIZE,
    ): Flow<List<ThreadSummaryTuple>> =
        db.messageMetaDao().observeLocalCategoryThreadPage(
            accountId = accountId,
            categoryName = categoryName,
            onlyUnread = if (filter == InboxQuickFilter.UNREAD) 1 else 0,
            onlyFlagged = if (filter == InboxQuickFilter.STARRED) 1 else 0,
            onlyAttachments = if (filter == InboxQuickFilter.WITH_ATTACHMENTS) 1 else 0,
            limit = pageSize,
            offset = pageIndex.coerceAtLeast(0) * pageSize,
        )

    fun observeLocalCategoryThreadCount(
        accountId: Long,
        categoryName: String,
        filter: InboxQuickFilter,
    ): Flow<Int> =
        db.messageMetaDao().observeLocalCategoryThreadCount(
            accountId = accountId,
            categoryName = categoryName,
            onlyUnread = if (filter == InboxQuickFilter.UNREAD) 1 else 0,
            onlyFlagged = if (filter == InboxQuickFilter.STARRED) 1 else 0,
            onlyAttachments = if (filter == InboxQuickFilter.WITH_ATTACHMENTS) 1 else 0,
        )

    /**
     * Loads single message or entire thread by primary/thread index (<50ms target).
     * Never decompresses HTML eagerly unless requested by UI.
     */
    suspend fun loadConversation(
        accountId: Long,
        metaId: Long,
        threadId: String?,
        loadFullThread: Boolean,
    ): List<LoadedMessageDetail> {
        val metas = if (loadFullThread && !threadId.isNullOrBlank()) {
            db.messageMetaDao().getThreadMessages(accountId, threadId)
                .ifEmpty { listOfNotNull(db.messageMetaDao().getById(metaId)) }
        } else {
            listOfNotNull(db.messageMetaDao().getById(metaId))
        }

        return metas.map { meta ->
            val body = db.messageBodyDao().getByMetaId(meta.id)
            val attachments = db.attachmentDao().getByMetaId(meta.id)
            val syncFlags = db.messageSyncFlagsDao().getByMetaId(meta.id)
            val localState = db.messageLocalStateDao().getByMetaId(meta.id)
            LoadedMessageDetail(
                meta = meta,
                body = body,
                attachments = attachments,
                isBodyTruncated = syncFlags?.isBodyTruncated == true,
                isLocallyArchived = localState?.isArchived == true,
            )
        }
    }

    suspend fun decompressHtmlInMemory(htmlZstd: ByteArray): String =
        mailCore.decompressHtmlZstd(htmlZstd, maxOutputBytes = 4L * 1024 * 1024)

    suspend fun setReadState(metaId: Long, isRead: Boolean) {
        db.messageMetaDao().updateReadState(metaId, isRead)
    }

    suspend fun markConversationRead(metas: List<MessageMetaEntity>) {
        val unreadIds = metas.filter { !it.isRead }.map { it.id }
        if (unreadIds.isEmpty()) return
        db.useWriterConnection { conn ->
            conn.immediateTransaction {
                for (id in unreadIds) {
                    db.messageMetaDao().updateReadState(id, true)
                }
            }
        }
    }

    suspend fun toggleFlagged(metaId: Long, currentlyFlagged: Boolean) {
        db.messageMetaDao().updateFlaggedState(metaId, !currentlyFlagged)
    }

    /**
     * Non-destructive local archive (Iron Rule #1 + Requirement 4.1.3).
     * Preserves all records and server UIDs; returns archived meta IDs for 5-second Undo.
     */
    suspend fun archiveMessagesLocally(metaIds: List<Long>) {
        if (metaIds.isEmpty()) return
        val now = clockMs()
        val existingByMetaId = mutableMapOf<Long, MessageLocalStateEntity>()
        metaIds.chunked(400).forEach { chunk ->
            db.messageLocalStateDao().getStatesByMetaIds(chunk).forEach { st ->
                existingByMetaId[st.metaId] = st
            }
        }
        db.messageLocalStateDao().upsertAll(
            metaIds.map { id ->
                val cur = existingByMetaId[id]
                MessageLocalStateEntity(
                    metaId = id,
                    isArchived = true,
                    archivedAt = now,
                    localCategory = cur?.localCategory,
                    hiddenFromFolder = cur?.hiddenFromFolder ?: false,
                )
            },
        )
    }

    suspend fun archiveThreadInFolderLocally(accountId: Long, folderId: Long, threadId: String?): List<Long> {
        val ids = if (!threadId.isNullOrBlank()) {
            db.messageMetaDao().getThreadMessages(accountId, threadId)
                .filter { it.folderId == folderId }
                .map { it.id }
        } else {
            emptyList()
        }
        archiveMessagesLocally(ids)
        return ids
    }

    suspend fun unarchiveMessagesLocally(metaIds: List<Long>) {
        if (metaIds.isEmpty()) return
        val existingByMetaId = mutableMapOf<Long, MessageLocalStateEntity>()
        metaIds.chunked(400).forEach { chunk ->
            db.messageLocalStateDao().getStatesByMetaIds(chunk).forEach { st ->
                existingByMetaId[st.metaId] = st
            }
        }
        db.messageLocalStateDao().upsertAll(
            metaIds.map { id ->
                val cur = existingByMetaId[id]
                MessageLocalStateEntity(
                    metaId = id,
                    isArchived = false,
                    archivedAt = null,
                    localCategory = cur?.localCategory,
                    hiddenFromFolder = cur?.hiddenFromFolder ?: false,
                )
            },
        )
    }

    /**
     * Re-evaluates all enabled client-side sorting rules for `accountId` across non-archived INBOX
     * messages. Strictly local SQLite updates only (never touches remote IMAP servers).
     */
    suspend fun applyAccountFilterRulesNow(accountId: Long): Int {
        val rules = db.localFilterRuleDao().getEnabledByAccount(accountId)
        val now = clockMs()
        var matchedTotal = 0

        db.useWriterConnection { conn ->
            conn.immediateTransaction {
                db.messageLocalStateDao().clearNonArchivedLocalCategories(accountId)
                if (rules.isNotEmpty()) {
                    var beforeId = Long.MAX_VALUE
                    val batchSize = 200
                    while (true) {
                        val page = db.messageMetaDao().getAccountInboxMessagesBeforeId(
                            accountId = accountId,
                            beforeId = beforeId,
                            limit = batchSize,
                        )
                        if (page.isEmpty()) break
                        val updates = mutableListOf<MessageLocalStateEntity>()
                        for (meta in page) {
                            val body = db.messageBodyDao().getByMetaId(meta.id)
                            val outcome = LocalFilterEngine.evaluate(
                                rules = rules,
                                target = FilterEvaluationTarget(
                                    meta = meta,
                                    plainText = body?.plainText,
                                    rawHeaders = body?.rawHeaders,
                                ),
                            )
                            if (outcome.matchedRuleIds.isEmpty()) continue
                            matchedTotal += 1
                            if (outcome.markRead && !meta.isRead) {
                                db.messageMetaDao().updateReadState(meta.id, true)
                            }
                            if (outcome.markFlagged && !meta.isFlagged) {
                                db.messageMetaDao().updateFlaggedState(meta.id, true)
                            }
                            if (outcome.archiveLocally || outcome.targetLocalFolder != null) {
                                updates += MessageLocalStateEntity(
                                    metaId = meta.id,
                                    isArchived = outcome.archiveLocally,
                                    archivedAt = if (outcome.archiveLocally) now else null,
                                    localCategory = outcome.targetLocalFolder,
                                    hiddenFromFolder = outcome.hideFromFolder,
                                )
                            }
                        }
                        if (updates.isNotEmpty()) {
                            db.messageLocalStateDao().upsertAll(updates)
                        }
                        beforeId = page.last().id
                        if (page.size < batchSize) break
                    }
                }
                val inbox = db.folderDao().getByPath(accountId, NonDestructiveSyncEngine.INBOX_PATH)
                if (inbox != null) {
                    val unread = db.messageMetaDao().countUnreadByFolder(inbox.folderId)
                    db.folderDao().updateFolderState(
                        folderId = inbox.folderId,
                        folderName = inbox.folderName,
                        fullPath = inbox.fullPath,
                        uidValidity = inbox.uidValidity,
                        highestModSeq = inbox.highestModSeq,
                        lastSyncedUid = inbox.lastSyncedUid,
                        totalCount = inbox.totalCount,
                        unreadCount = unread,
                    )
                }
            }
        }
        return matchedTotal
    }

    suspend fun healStoredEncodedHeaders(): Int = syncEngine.healStoredEncodedHeaders()

    suspend fun searchLocal(
        query: String,
        accountId: Long? = null,
        limit: Int = 200,
    ): List<MessageMetaEntity> = syncEngine.searchLocal(query, accountId, limit)

    suspend fun downloadAttachment(
        accountId: Long,
        metaId: Long,
        attachmentId: Long,
        attachmentsRootDir: File,
    ): File = syncEngine.downloadAttachmentOnDemand(accountId, metaId, attachmentId, attachmentsRootDir)

    suspend fun setBulkReadState(metaIds: List<Long>, isRead: Boolean) {
        if (metaIds.isEmpty()) return
        db.useWriterConnection { conn ->
            conn.immediateTransaction {
                metaIds.chunked(500).forEach { chunk ->
                    db.messageMetaDao().updateReadStateBulk(chunk, isRead)
                }
            }
        }
    }

    /**
     * Deletes an account strictly from local device storage (FTS5 + Room cascade).
     * Never sends DELETE/EXPUNGE or any destructive command to the remote mail server.
     */
    suspend fun deleteLocalAccount(accountId: Long) {
        db.useWriterConnection { conn ->
            conn.immediateTransaction {
                db.messageMetaDao().deleteFtsForAccount(accountId)
                db.accountDao().deleteById(accountId)
            }
        }
    }

    fun calculateAttachmentCacheBytes(attachmentsRootDir: File): Long {
        if (!attachmentsRootDir.exists()) return 0L
        return attachmentsRootDir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }

    suspend fun clearAttachmentCache(attachmentsRootDir: File): Long {
        val cleared = calculateAttachmentCacheBytes(attachmentsRootDir)
        if (attachmentsRootDir.exists()) {
            attachmentsRootDir.deleteRecursively()
        }
        attachmentsRootDir.mkdirs()
        db.attachmentDao().clearAllLocalPaths()
        return cleared
    }

    companion object {
        const val PAGE_SIZE = 100
        private const val PREFETCH_DISTANCE = 25
        private const val MAX_PAGING_WINDOW_SIZE = 500
    }
}
