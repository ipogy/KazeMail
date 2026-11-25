package com.kazemail.app.sync

import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import com.kazemail.app.auth.OAuth2Manager
import com.kazemail.app.auth.OAuth2SessionResult
import com.kazemail.app.data.AttachmentMetadataHelper
import com.kazemail.app.data.db.AccountEntity
import com.kazemail.app.data.db.AttachmentEntity
import com.kazemail.app.data.db.FolderEntity
import com.kazemail.app.data.db.FolderSyncStateEntity
import com.kazemail.app.data.db.FtsQueryBuilder
import com.kazemail.app.data.db.KazeMailDatabase
import com.kazemail.app.data.db.MessageBodyEntity
import com.kazemail.app.data.db.MessageFtsEntity
import com.kazemail.app.data.db.MessageLocalStateEntity
import com.kazemail.app.data.db.MessageMetaEntity
import com.kazemail.app.data.db.MessageSyncFlagsEntity
import com.kazemail.app.filter.FilterEvaluationTarget
import com.kazemail.app.filter.LocalFilterEngine
import com.kazemail.app.security.AccountCredentialStore
import com.kazemail.app.security.StoredAccountCredentials
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class SyncProgress(
    val accountId: Long,
    val folderPath: String,
    val syncedInFolder: Int,
    val serverTotalInFolder: Int,
    val phaseLabel: String,
)

data class Phase1Result(
    val accountId: Long,
    val inboxFolderId: Long,
    val newlySyncedCount: Int,
    val totalInboxCount: Int,
    val backfillCompleted: Boolean,
)

data class IncrementalSyncResult(
    val accountId: Long,
    val newlySyncedCount: Int,
    val flagUpdatedCount: Int,
    val uidValidityChangedFolders: Int,
    val newInboxUnreadMessages: List<MessageMetaEntity> = emptyList(),
)

fun interface OAuth2TokenRefresher {
    suspend fun ensureFreshToken(authStateJson: String, fallbackEmail: String): OAuth2SessionResult
}

class NonDestructiveSyncEngine(
    private val db: KazeMailDatabase,
    private val credentialStore: AccountCredentialStore,
    private val mailCore: MailCoreEngine,
    private val tokenRefresher: OAuth2TokenRefresher,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    constructor(
        db: KazeMailDatabase,
        credentialStore: AccountCredentialStore,
        mailCore: MailCoreEngine,
        oauth2Manager: OAuth2Manager,
    ) : this(
        db = db,
        credentialStore = credentialStore,
        mailCore = mailCore,
        tokenRefresher = OAuth2TokenRefresher { stateJson, email ->
            oauth2Manager.ensureFreshAccessToken(stateJson, email)
        },
    )

    suspend fun testConnectionAndDiscoverFolders(config: CoreImapConfig): List<CoreFolderInfo> =
        mailCore.connect(config).use { session ->
            session.listFolders()
        }

    suspend fun runPhase1InboxSync(accountId: Long): Phase1Result {
        val account = requireAccount(accountId)
        if (account.authType.equals(AUTH_TYPE_LOCAL, ignoreCase = true)) {
            return Phase1Result(accountId, 0L, 0, 0, true)
        }
        return withImapSession(account) { session ->
            healStoredEncodedHeaders()
            discoverAndSyncFolderRows(accountId, session)
            val inbox = ensureFolderRow(accountId, INBOX_PATH, INBOX_PATH, session)
            val snapshot = session.examineFolder(inbox.fullPath)
            val activeInbox = reconcileUidValidityPreservingLocal(inbox, snapshot)

            val latestUids = session.fetchLatestUids(activeInbox.fullPath, PHASE1_LIMIT)
            val newlySynced = fetchAndPersistMissingUids(
                accountId = accountId,
                folder = activeInbox,
                session = session,
                candidateUids = latestUids,
            )

            val maxUidSynced = maxOf(activeInbox.lastSyncedUid, latestUids.maxOrNull() ?: 0L)
            val minPhase1Uid = latestUids.minOrNull() ?: 0L
            val allInRangeDone = snapshot.exists <= PHASE1_LIMIT || minPhase1Uid <= 1L
            val nextBackfillMax = if (allInRangeDone) 0L else (minPhase1Uid - 1L).coerceAtLeast(1L)

            updateFolderCountsAndState(
                folderId = activeInbox.folderId,
                snapshot = snapshot,
                highestUid = maxUidSynced,
                phase1Done = true,
                backfillDone = allInRangeDone,
                backfillNextMaxUid = nextBackfillMax,
                lastError = null,
            )
            db.accountDao().updateLastSyncAt(accountId, clockMs())

            Phase1Result(
                accountId = accountId,
                inboxFolderId = activeInbox.folderId,
                newlySyncedCount = newlySynced,
                totalInboxCount = snapshot.exists,
                backfillCompleted = allInRangeDone,
            )
        }
    }

    suspend fun runPhase2Backfill(
        accountId: Long,
        shouldContinue: () -> Boolean = { true },
        onProgress: suspend (SyncProgress) -> Unit = {},
    ): Int {
        val account = requireAccount(accountId)
        if (account.authType.equals(AUTH_TYPE_LOCAL, ignoreCase = true)) return 0
        var totalSynced = 0

        withImapSession(account) { session ->
            healStoredEncodedHeaders()
            discoverAndSyncFolderRows(accountId, session)
            val folders = db.folderDao().getByAccount(accountId)
                .filter { !isLocalOnlyFolder(it.fullPath) }

            for (initialFolder in folders) {
                if (!shouldContinue()) break

                val snapshot = runCatching { session.examineFolder(initialFolder.fullPath) }
                    .getOrElse { continue }
                val folder = reconcileUidValidityPreservingLocal(initialFolder, snapshot)
                val existingState = db.folderSyncStateDao().getByFolderId(folder.folderId)
                if (existingState?.backfillCompleted == true) {
                    continue
                }

                if (snapshot.exists == 0) {
                    updateFolderCountsAndState(
                        folderId = folder.folderId,
                        snapshot = snapshot,
                        highestUid = folder.lastSyncedUid,
                        phase1Done = true,
                        backfillDone = true,
                        backfillNextMaxUid = 0L,
                        lastError = null,
                    )
                    continue
                }

                val allUidsDesc = session.fetchAllUidsDescending(folder.fullPath)
                val checkpoint = existingState?.backfillNextMaxUid ?: 0L
                val remainingUids = if (checkpoint > 0L) {
                    allUidsDesc.filter { it <= checkpoint }
                } else {
                    allUidsDesc
                }

                var highestUid = maxOf(folder.lastSyncedUid, allUidsDesc.firstOrNull() ?: 0L)
                var completed = true

                for (chunk in remainingUids.chunked(BACKFILL_CHUNK_SIZE)) {
                    if (!shouldContinue()) {
                        completed = false
                        break
                    }

                    val inserted = fetchAndPersistMissingUids(
                        accountId = accountId,
                        folder = folder,
                        session = session,
                        candidateUids = chunk,
                    )
                    totalSynced += inserted

                    val chunkMin = chunk.minOrNull() ?: 1L
                    val nextCursor = (chunkMin - 1L).coerceAtLeast(0L)
                    val isDoneNow = nextCursor == 0L || chunkMin <= (allUidsDesc.lastOrNull() ?: 1L)
                    updateFolderCountsAndState(
                        folderId = folder.folderId,
                        snapshot = snapshot,
                        highestUid = highestUid,
                        phase1Done = true,
                        backfillDone = isDoneNow,
                        backfillNextMaxUid = if (isDoneNow) 0L else nextCursor,
                        lastError = null,
                    )

                    val localCount = db.messageMetaDao().countByFolder(folder.folderId)
                    onProgress(
                        SyncProgress(
                            accountId = accountId,
                            folderPath = folder.fullPath,
                            syncedInFolder = localCount,
                            serverTotalInFolder = snapshot.exists,
                            phaseLabel = "Phase 2 Backfill",
                        ),
                    )
                }

                if (completed) {
                    updateFolderCountsAndState(
                        folderId = folder.folderId,
                        snapshot = snapshot,
                        highestUid = highestUid,
                        phase1Done = true,
                        backfillDone = true,
                        backfillNextMaxUid = 0L,
                        lastError = null,
                    )
                }
            }
            db.accountDao().updateLastSyncAt(accountId, clockMs())
        }

        return totalSynced
    }

    private val incrementalMutexByAccount = ConcurrentHashMap<Long, Mutex>()

    private fun incrementalMutexFor(accountId: Long): Mutex =
        incrementalMutexByAccount.computeIfAbsent(accountId) { Mutex() }

    suspend fun runIncrementalSync(accountId: Long): IncrementalSyncResult =
        incrementalMutexFor(accountId).withLock {
            runIncrementalSyncInternal(accountId)
        }

    private suspend fun runIncrementalSyncInternal(accountId: Long): IncrementalSyncResult {
        val account = requireAccount(accountId)
        if (account.authType.equals(AUTH_TYPE_LOCAL, ignoreCase = true)) {
            return IncrementalSyncResult(accountId, 0, 0, 0)
        }
        var newlySyncedTotal = 0
        var flagUpdatedTotal = 0
        var uidValidityChangedCount = 0
        val newInboxUnread = mutableListOf<MessageMetaEntity>()

        withImapSession(account) { session ->
            healStoredEncodedHeaders()
            discoverAndSyncFolderRows(accountId, session)
            val folders = db.folderDao().getByAccount(accountId)
                .filter { !isLocalOnlyFolder(it.fullPath) }

            for (initialFolder in folders) {
                val delta = runCatching {
                    session.fetchIncrementalDelta(
                        fullPath = initialFolder.fullPath,
                        knownLastUid = initialFolder.lastSyncedUid,
                        knownHighestModSeq = initialFolder.highestModSeq,
                    )
                }.getOrElse { continue }

                val uidValidityChanged = initialFolder.uidValidity != 0L &&
                    delta.snapshot.uidValidity != 0L &&
                    initialFolder.uidValidity != delta.snapshot.uidValidity
                if (uidValidityChanged) {
                    uidValidityChangedCount += 1
                }

                val activeFolder = reconcileUidValidityPreservingLocal(initialFolder, delta.snapshot)
                val existingState = db.folderSyncStateDao().getByFolderId(activeFolder.folderId)

                // Apply CONDSTORE flag changes without touching or deleting message records
                if (!uidValidityChanged && delta.flagChanges.isNotEmpty()) {
                    db.useWriterConnection { conn ->
                        conn.immediateTransaction {
                            for (change in delta.flagChanges) {
                                db.messageMetaDao().updateFlagsByUid(
                                    folderId = activeFolder.folderId,
                                    uid = change.uid,
                                    isRead = change.isRead,
                                    isFlagged = change.isFlagged,
                                )
                                flagUpdatedTotal += 1
                            }
                        }
                    }
                }

                val targetUids = if (uidValidityChanged) {
                    session.fetchAllUidsDescending(activeFolder.fullPath)
                } else {
                    delta.newUids
                }

                if (targetUids.isNotEmpty()) {
                    for (chunk in targetUids.chunked(BACKFILL_CHUNK_SIZE)) {
                        val batch = fetchAndPersistMissingUidsWithMeta(
                            accountId = accountId,
                            folder = activeFolder,
                            session = session,
                            candidateUids = chunk,
                        )
                        newlySyncedTotal += batch.insertedCount
                        if (
                            !uidValidityChanged &&
                            existingState?.phase1Completed == true &&
                            activeFolder.fullPath.equals(INBOX_PATH, ignoreCase = true)
                        ) {
                            newInboxUnread += batch.notifyUnreadMessages
                        }
                    }
                }

                val highestUid = maxOf(activeFolder.lastSyncedUid, targetUids.maxOrNull() ?: 0L)
                updateFolderCountsAndState(
                    folderId = activeFolder.folderId,
                    snapshot = delta.snapshot,
                    highestUid = highestUid,
                    phase1Done = existingState?.phase1Completed ?: true,
                    backfillDone = existingState?.backfillCompleted ?: !uidValidityChanged,
                    backfillNextMaxUid = existingState?.backfillNextMaxUid ?: 0L,
                    lastError = null,
                )
            }

            db.accountDao().updateLastSyncAt(accountId, clockMs())
        }

        return IncrementalSyncResult(
            accountId = accountId,
            newlySyncedCount = newlySyncedTotal,
            flagUpdatedCount = flagUpdatedTotal,
            uidValidityChangedFolders = uidValidityChangedCount,
            newInboxUnreadMessages = newInboxUnread,
        )
    }

    suspend fun searchLocal(
        rawQuery: String,
        accountId: Long? = null,
        limit: Int = 200,
    ): List<MessageMetaEntity> {
        val query = FtsQueryBuilder.buildSearchQuery(rawQuery, accountId, limit)
        return db.messageFtsDao().search(query)
    }

    suspend fun downloadAttachmentOnDemand(
        accountId: Long,
        metaId: Long,
        attachmentId: Long,
        attachmentsRootDir: File,
    ): File {
        val account = requireAccount(accountId)
        val meta = db.messageMetaDao().getById(metaId)
            ?: error("Message meta $metaId not found")
        val folder = db.folderDao().getById(meta.folderId)
            ?: error("Folder ${meta.folderId} not found")
        val attachment = db.attachmentDao().getByMetaId(metaId)
            .firstOrNull { it.attachmentId == attachmentId }
            ?: error("Attachment $attachmentId not found")

        attachment.localFilePath?.let { existingPath ->
            val existing = File(existingPath)
            if (!AttachmentMetadataHelper.isCachedAttachmentInvalid(existing, attachment.filename, attachment.mimeType)) {
                val sample = AttachmentMetadataHelper.readHeaderSample(existing)
                val safeName = AttachmentMetadataHelper.buildSafeAttachmentFileName(
                    rawFilename = attachment.filename,
                    rawMimeType = attachment.mimeType,
                    payloadPrefix = sample,
                )
                val preferredFile = File(existing.parentFile ?: File(attachmentsRootDir, metaId.toString()), "${attachment.attachmentId}_$safeName")
                if (existing.absolutePath == preferredFile.absolutePath) {
                    return existing
                }
                if (existing.renameTo(preferredFile)) {
                    db.attachmentDao().updateLocalPath(attachmentId, preferredFile.absolutePath)
                    return preferredFile
                }
                return existing
            }
            runCatching { existing.delete() }
        }

        val payload = withImapSession(account) { session ->
            session.fetchMessagePart(
                fullPath = folder.fullPath,
                uid = meta.uid,
                partId = attachment.partId,
                maxBytes = null,
            )
        }

        if (payload.isEmpty() && attachment.sizeBytes > 0L) {
            error("サーバーから空の添付データ（0バイト）を受信しました")
        }

        val msgDir = File(attachmentsRootDir, metaId.toString()).apply { mkdirs() }
        val safeFilename = AttachmentMetadataHelper.buildSafeAttachmentFileName(
            rawFilename = attachment.filename,
            rawMimeType = attachment.mimeType,
            payloadPrefix = payload.copyOfRange(0, minOf(payload.size, 32)),
        )
        val destFile = File(msgDir, "${attachment.attachmentId}_$safeFilename")
        destFile.writeBytes(payload)
        db.attachmentDao().updateLocalPath(attachmentId, destFile.absolutePath)
        return destFile
    }

    private suspend fun discoverAndSyncFolderRows(accountId: Long, session: ImapSessionHandle) {
        val remoteFolders = session.listFolders().filter { it.isSelectable }
        for (rf in remoteFolders) {
            val cleanName = ImapFolderNameFormatter.formatDisplayName(rf.fullPath, rf.folderName)
                .takeIf { !rf.fullPath.equals("INBOX", ignoreCase = true) } ?: "INBOX"
            val existing = db.folderDao().getByPath(accountId, rf.fullPath)
            if (existing == null) {
                val id = db.folderDao().insert(
                    FolderEntity(
                        accountId = accountId,
                        folderName = cleanName,
                        fullPath = rf.fullPath,
                        uidValidity = 0L,
                    ),
                )
                db.folderSyncStateDao().upsert(
                    FolderSyncStateEntity(folderId = id, updatedAt = clockMs()),
                )
            } else if (existing.folderName != cleanName) {
                db.folderDao().updateFolderName(existing.folderId, cleanName)
            }
        }
    }

    private suspend fun ensureFolderRow(
        accountId: Long,
        fullPath: String,
        folderName: String,
        session: ImapSessionHandle,
    ): FolderEntity {
        db.folderDao().getByPath(accountId, fullPath)?.let { return it }
        val snapshot = session.examineFolder(fullPath)
        val id = db.folderDao().insert(
            FolderEntity(
                accountId = accountId,
                folderName = folderName,
                fullPath = fullPath,
                uidValidity = snapshot.uidValidity,
                highestModSeq = snapshot.highestModSeq,
                totalCount = snapshot.exists,
            ),
        )
        db.folderSyncStateDao().upsert(
            FolderSyncStateEntity(
                folderId = id,
                supportsCondstore = snapshot.supportsCondstore,
                supportsQresync = snapshot.supportsQresync,
                supportsIdle = snapshot.supportsIdle,
                updatedAt = clockMs(),
            ),
        )
        return db.folderDao().getById(id)!!
    }

    /**
     * Requirement 4.1.2: If UIDVALIDITY changed on the server, NEVER delete local messages.
     * Preserves existing messages in an archive snapshot folder row before creating a fresh active folder row.
     */
    private suspend fun reconcileUidValidityPreservingLocal(
        folder: FolderEntity,
        snapshot: CoreFolderSnapshot,
    ): FolderEntity {
        if (snapshot.uidValidity == 0L || folder.uidValidity == snapshot.uidValidity) {
            if (folder.uidValidity == 0L && snapshot.uidValidity != 0L) {
                db.folderDao().updateFolderState(
                    folderId = folder.folderId,
                    folderName = folder.folderName,
                    fullPath = folder.fullPath,
                    uidValidity = snapshot.uidValidity,
                    highestModSeq = maxOf(folder.highestModSeq, snapshot.highestModSeq),
                    lastSyncedUid = folder.lastSyncedUid,
                    totalCount = snapshot.exists,
                    unreadCount = folder.unreadCount,
                )
                return db.folderDao().getById(folder.folderId)!!
            }
            return folder
        }

        val existingCount = db.messageMetaDao().countByFolder(folder.folderId)
        if (existingCount == 0) {
            db.folderDao().updateFolderState(
                folderId = folder.folderId,
                folderName = folder.folderName,
                fullPath = folder.fullPath,
                uidValidity = snapshot.uidValidity,
                highestModSeq = snapshot.highestModSeq,
                lastSyncedUid = 0L,
                totalCount = snapshot.exists,
                unreadCount = 0,
            )
            return db.folderDao().getById(folder.folderId)!!
        }

        val ts = clockMs()
        val archivedPath = "$ARCHIVE_PREFIX${folder.fullPath}#uidv_${folder.uidValidity}_$ts"
        val archivedName = "${folder.folderName} (Archive ${folder.uidValidity})"

        return db.useWriterConnection { conn ->
            conn.immediateTransaction {
                db.folderDao().renameToArchiveSnapshot(
                    folderId = folder.folderId,
                    archivedPath = archivedPath,
                    archivedName = archivedName,
                )
                val newFolderId = db.folderDao().insert(
                    FolderEntity(
                        accountId = folder.accountId,
                        folderName = folder.folderName,
                        fullPath = folder.fullPath,
                        uidValidity = snapshot.uidValidity,
                        highestModSeq = snapshot.highestModSeq,
                        lastSyncedUid = 0L,
                        totalCount = snapshot.exists,
                        unreadCount = 0,
                    ),
                )
                db.folderSyncStateDao().upsert(
                    FolderSyncStateEntity(
                        folderId = newFolderId,
                        supportsCondstore = snapshot.supportsCondstore,
                        supportsQresync = snapshot.supportsQresync,
                        supportsIdle = snapshot.supportsIdle,
                        updatedAt = ts,
                    ),
                )
                db.folderDao().getById(newFolderId)!!
            }
        }
    }

    private data class SyncedBatchResult(
        val insertedCount: Int,
        val notifyUnreadMessages: List<MessageMetaEntity>,
    )

    private suspend fun fetchAndPersistMissingUids(
        accountId: Long,
        folder: FolderEntity,
        session: ImapSessionHandle,
        candidateUids: List<Long>,
    ): Int = fetchAndPersistMissingUidsWithMeta(accountId, folder, session, candidateUids).insertedCount

    private suspend fun fetchAndPersistMissingUidsWithMeta(
        accountId: Long,
        folder: FolderEntity,
        session: ImapSessionHandle,
        candidateUids: List<Long>,
    ): SyncedBatchResult {
        if (candidateUids.isEmpty()) return SyncedBatchResult(0, emptyList())

        val existing = mutableSetOf<Long>()
        for (uidChunk in candidateUids.chunked(400)) {
            existing += db.messageMetaDao().findExistingUids(folder.folderId, uidChunk)
        }
        val missingUids = candidateUids.filter { it !in existing }
        if (missingUids.isEmpty()) return SyncedBatchResult(0, emptyList())

        val parsed = session.fetchMessagesByUids(folder.fullPath, missingUids)
        if (parsed.isEmpty()) return SyncedBatchResult(0, emptyList())

        val isInbox = folder.fullPath.equals(INBOX_PATH, ignoreCase = true)
        val enabledRules = if (isInbox) {
            db.localFilterRuleDao().getEnabledByAccount(accountId)
        } else {
            emptyList()
        }
        val now = clockMs()

        var insertedCount = 0
        val notifyUnread = mutableListOf<MessageMetaEntity>()
        db.useWriterConnection { conn ->
            conn.immediateTransaction {
                for (rec in parsed) {
                    val cleanSenderName = Rfc2047HeaderDecoder.decodeIfNeeded(rec.senderName)
                    val cleanSubject = Rfc2047HeaderDecoder.decodeIfNeeded(rec.subject)
                    val baseEntity = MessageMetaEntity(
                        accountId = accountId,
                        folderId = folder.folderId,
                        uid = rec.uid,
                        messageIdHeader = rec.messageIdHeader,
                        inReplyTo = rec.inReplyTo,
                        threadId = rec.threadKey,
                        senderName = cleanSenderName,
                        senderAddress = rec.senderAddress,
                        recipientTo = rec.recipientsToJson,
                        subject = cleanSubject,
                        snippet = rec.snippet,
                        receivedAt = rec.receivedAtMs,
                        isRead = rec.isRead,
                        isFlagged = rec.isFlagged,
                        hasAttachments = rec.hasAttachments,
                        sizeBytes = rec.sizeBytes,
                    )
                    val outcome = if (enabledRules.isNotEmpty()) {
                        LocalFilterEngine.evaluate(
                            rules = enabledRules,
                            target = FilterEvaluationTarget(
                                meta = baseEntity,
                                plainText = rec.plainText,
                                rawHeaders = rec.rawHeaders,
                            ),
                        )
                    } else {
                        null
                    }
                    val finalEntity = if (outcome != null) {
                        baseEntity.copy(
                            isRead = baseEntity.isRead || outcome.markRead,
                            isFlagged = baseEntity.isFlagged || outcome.markFlagged,
                        )
                    } else {
                        baseEntity
                    }

                    val metaId = db.messageMetaDao().insertIgnore(finalEntity)
                    if (metaId <= 0L) continue
                    val persistedMeta = finalEntity.copy(id = metaId)
                    insertedCount += 1

                    if (
                        !persistedMeta.isRead &&
                        outcome?.suppressNotification != true &&
                        outcome?.archiveLocally != true
                    ) {
                        notifyUnread += persistedMeta
                    }

                    if (outcome != null && (outcome.archiveLocally || outcome.targetLocalFolder != null)) {
                        db.messageLocalStateDao().upsert(
                            MessageLocalStateEntity(
                                metaId = metaId,
                                isArchived = outcome.archiveLocally,
                                archivedAt = if (outcome.archiveLocally) now else null,
                                localCategory = outcome.targetLocalFolder,
                                hiddenFromFolder = outcome.hideFromFolder,
                            ),
                        )
                    }

                    db.messageBodyDao().upsert(
                        MessageBodyEntity(
                            metaId = metaId,
                            plainText = rec.plainText,
                            htmlZstd = rec.htmlZstd,
                            rawHeaders = rec.rawHeaders,
                        ),
                    )

                    if (rec.isTruncated) {
                        db.messageSyncFlagsDao().upsert(
                            MessageSyncFlagsEntity(metaId = metaId, isBodyTruncated = true),
                        )
                    }

                    if (rec.attachments.isNotEmpty()) {
                        db.attachmentDao().insertAll(
                            rec.attachments.map { att ->
                                AttachmentEntity(
                                    metaId = metaId,
                                    partId = att.partId,
                                    filename = att.filename,
                                    mimeType = att.mimeType,
                                    sizeBytes = att.sizeBytes,
                                    localFilePath = null,
                                )
                            },
                        )
                    }

                    db.messageFtsDao().insert(
                        MessageFtsEntity(
                            metaId = metaId,
                            senderName = cleanSenderName,
                            senderAddress = rec.senderAddress,
                            subject = cleanSubject,
                            plainText = rec.plainText,
                        ),
                    )
                }
            }
        }

        return SyncedBatchResult(
            insertedCount = insertedCount,
            notifyUnreadMessages = notifyUnread,
        )
    }

    /**
     * Repairs previously-synced DB rows whose Subject or From display name retained raw
     * `=?ISO-2022-JP?B?...?=` / RFC 2047 tokens, updating both `messages_meta` and `messages_fts`.
     */
    suspend fun healStoredEncodedHeaders(): Int {
        var healedCount = 0
        while (true) {
            val candidates = db.messageMetaDao().findMessagesWithEncodedHeaders(limit = 300)
            if (candidates.isEmpty()) break
            var updatedInBatch = 0
            db.useWriterConnection { conn ->
                conn.immediateTransaction {
                    for (meta in candidates) {
                        val decodedSubject = Rfc2047HeaderDecoder.decodeIfNeeded(meta.subject)
                        val decodedSender = Rfc2047HeaderDecoder.decodeIfNeeded(meta.senderName)
                        if (decodedSubject == meta.subject && decodedSender == meta.senderName) {
                            continue
                        }
                        db.messageMetaDao().updateDecodedHeaders(meta.id, decodedSubject, decodedSender)
                        val body = db.messageBodyDao().getByMetaId(meta.id)
                        db.messageFtsDao().deleteByMetaId(meta.id)
                        db.messageFtsDao().insert(
                            MessageFtsEntity(
                                metaId = meta.id,
                                senderName = decodedSender,
                                senderAddress = meta.senderAddress,
                                subject = decodedSubject,
                                plainText = body?.plainText,
                            ),
                        )
                        updatedInBatch += 1
                    }
                }
            }
            healedCount += updatedInBatch
            if (updatedInBatch == 0 || candidates.size < 300) break
        }
        return healedCount
    }

    private suspend fun updateFolderCountsAndState(
        folderId: Long,
        snapshot: CoreFolderSnapshot,
        highestUid: Long,
        phase1Done: Boolean,
        backfillDone: Boolean,
        backfillNextMaxUid: Long,
        lastError: String?,
    ) {
        val localCount = db.messageMetaDao().countByFolder(folderId)
        val unreadCount = db.messageMetaDao().countUnreadByFolder(folderId)
        db.folderDao().updateSyncProgress(
            folderId = folderId,
            highestModSeq = snapshot.highestModSeq,
            lastSyncedUid = highestUid,
            totalCount = maxOf(snapshot.exists, localCount),
            unreadCount = unreadCount,
        )
        db.folderSyncStateDao().upsert(
            FolderSyncStateEntity(
                folderId = folderId,
                phase1Completed = phase1Done,
                backfillCompleted = backfillDone,
                backfillNextMaxUid = backfillNextMaxUid,
                supportsCondstore = snapshot.supportsCondstore,
                supportsQresync = snapshot.supportsQresync,
                supportsIdle = snapshot.supportsIdle,
                lastError = lastError,
                updatedAt = clockMs(),
            ),
        )
    }

    private suspend fun <T> withImapSession(
        account: AccountEntity,
        block: suspend (ImapSessionHandle) -> T,
    ): T {
        val creds = credentialStore.loadCredentials(account.accountId)
            ?: error("No stored credentials for account ${account.emailAddress}")

        val auth = when (creds) {
            is StoredAccountCredentials.Password -> CoreImapAuth.Password(
                username = creds.username,
                password = creds.password,
            )
            is StoredAccountCredentials.OAuth2 -> {
                val refreshed = tokenRefresher.ensureFreshToken(creds.authStateJson, creds.email)
                if (refreshed.updatedAuthStateJson != creds.authStateJson) {
                    credentialStore.saveCredentials(
                        account.accountId,
                        creds.copy(authStateJson = refreshed.updatedAuthStateJson),
                    )
                }
                CoreImapAuth.OAuth2(
                    email = creds.email,
                    accessToken = refreshed.accessToken,
                )
            }
        }

        val config = CoreImapConfig(
            host = account.imapHost,
            port = account.imapPort,
            security = creds.securityMode,
            auth = auth,
        )
        return mailCore.connect(config).use { session ->
            block(session)
        }
    }

    private suspend fun requireAccount(accountId: Long): AccountEntity =
        db.accountDao().getById(accountId) ?: error("Account $accountId not found")

    private fun isLocalOnlyFolder(fullPath: String): Boolean =
        fullPath.startsWith(ARCHIVE_PREFIX) || fullPath.startsWith(LOCAL_PREFIX)

    companion object {
        const val PHASE1_LIMIT = 100
        const val BACKFILL_CHUNK_SIZE = 500
        const val INBOX_PATH = "INBOX"
        const val ARCHIVE_PREFIX = "__archive__/"
        const val LOCAL_PREFIX = "__local_"
        const val AUTH_TYPE_LOCAL = "LOCAL"
    }
}
