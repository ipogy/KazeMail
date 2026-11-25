package com.kazemail.app.importexport

import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import com.kazemail.app.data.db.AccountEntity
import com.kazemail.app.data.db.AttachmentEntity
import com.kazemail.app.data.db.FolderEntity
import com.kazemail.app.data.db.FolderSyncStateEntity
import com.kazemail.app.data.db.KazeMailDatabase
import com.kazemail.app.data.db.MessageBodyEntity
import com.kazemail.app.data.db.MessageFtsEntity
import com.kazemail.app.data.db.MessageMetaEntity
import com.kazemail.app.data.db.MessageSyncFlagsEntity
import com.kazemail.app.sync.CoreParsedEmail
import com.kazemail.app.sync.MailCoreEngine
import com.kazemail.app.sync.NonDestructiveSyncEngine
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

data class LocalArchiveImportResult(
    val accountId: Long,
    val folderId: Long,
    val importedCount: Int,
    val skippedDuplicates: Int,
    val errorCount: Int,
)

class LocalArchiveImporter(
    private val db: KazeMailDatabase,
    private val mailCore: MailCoreEngine,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun importEmlStream(
        targetAccountId: Long?,
        inputStream: InputStream,
        isReadByDefault: Boolean = true,
    ): LocalArchiveImportResult {
        val rawBytes = readSingleMessageBounded(inputStream)
        val (account, folder) = ensureImportTarget(targetAccountId)
        var nextUid = (db.messageMetaDao().findMaxUid(folder.folderId) ?: 0L) + 1L

        val parsed = mailCore.parseEmlBytes(
            uid = nextUid,
            rawBytes = rawBytes,
            isRead = isReadByDefault,
            isFlagged = false,
            internalDateMs = null,
        )

        var imported = 0
        var skipped = 0
        db.useWriterConnection { conn ->
            conn.immediateTransaction {
                if (isDuplicateInFolder(folder.folderId, parsed.messageIdHeader)) {
                    skipped += 1
                } else {
                    persistParsedRecord(account.accountId, folder.folderId, nextUid, parsed)
                    imported += 1
                }
                refreshFolderCounts(folder)
            }
        }

        return LocalArchiveImportResult(
            accountId = account.accountId,
            folderId = folder.folderId,
            importedCount = imported,
            skippedDuplicates = skipped,
            errorCount = 0,
        )
    }

    /**
     * Streams a standard mbox / mboxo / mboxrd file in chunks without loading the whole file into memory.
     * Uses ISO-8859-1 1:1 byte preservation so legacy Japanese encodings (ISO-2022-JP / Shift_JIS / EUC-JP)
     * remain intact until parsed by Rust's `mail-parser` + recovery decoder.
     */
    suspend fun importMboxStream(
        targetAccountId: Long?,
        inputStream: InputStream,
        isReadByDefault: Boolean = true,
        onProgress: suspend (Int) -> Unit = {},
    ): LocalArchiveImportResult {
        val (account, folder) = ensureImportTarget(targetAccountId)
        var nextUid = (db.messageMetaDao().findMaxUid(folder.folderId) ?: 0L) + 1L

        var importedCount = 0
        var skippedCount = 0
        var errorCount = 0

        val pendingBatch = ArrayList<Pair<Long, CoreParsedEmail>>(BATCH_SIZE)

        suspend fun flushBatch() {
            if (pendingBatch.isEmpty()) return
            db.useWriterConnection { conn ->
                conn.immediateTransaction {
                    for ((uid, parsed) in pendingBatch) {
                        if (isDuplicateInFolder(folder.folderId, parsed.messageIdHeader)) {
                            skippedCount += 1
                        } else {
                            persistParsedRecord(account.accountId, folder.folderId, uid, parsed)
                            importedCount += 1
                        }
                    }
                    refreshFolderCounts(folder)
                }
            }
            pendingBatch.clear()
            onProgress(importedCount)
        }

        val currentMsg = StringBuilder(8192)
        var inMessage = false

        BufferedReader(InputStreamReader(inputStream, StandardCharsets.ISO_8859_1), 32 * 1024).use { reader ->
            while (true) {
                val rawLine = reader.readLine() ?: break
                val lineNoCr = rawLine.trimEnd('\r')

                if (looksLikeMboxFromSeparator(lineNoCr)) {
                    if (inMessage && currentMsg.isNotEmpty()) {
                        val rawBytes = currentMsg.toString().toByteArray(StandardCharsets.ISO_8859_1)
                        val uid = nextUid++
                        runCatching {
                            mailCore.parseEmlBytes(
                                uid = uid,
                                rawBytes = rawBytes,
                                isRead = isReadByDefault,
                                isFlagged = false,
                                internalDateMs = null,
                            )
                        }.onSuccess { parsed ->
                            pendingBatch += uid to parsed
                        }.onFailure {
                            errorCount += 1
                        }
                        if (pendingBatch.size >= BATCH_SIZE) {
                            flushBatch()
                        }
                        currentMsg.setLength(0)
                    }
                    inMessage = true
                    continue
                }

                if (!inMessage) {
                    if (lineNoCr.isEmpty()) continue
                    inMessage = true
                }

                if (currentMsg.length + lineNoCr.length + 2 <= MAX_SINGLE_MESSAGE_BYTES) {
                    val unescaped = if (lineNoCr.startsWith(">From ")) lineNoCr.substring(1) else lineNoCr
                    currentMsg.append(unescaped).append("\r\n")
                }
            }
        }

        if (inMessage && currentMsg.isNotEmpty()) {
            val rawBytes = currentMsg.toString().toByteArray(StandardCharsets.ISO_8859_1)
            val uid = nextUid++
            runCatching {
                mailCore.parseEmlBytes(
                    uid = uid,
                    rawBytes = rawBytes,
                    isRead = isReadByDefault,
                    isFlagged = false,
                    internalDateMs = null,
                )
            }.onSuccess { parsed ->
                pendingBatch += uid to parsed
            }.onFailure {
                errorCount += 1
            }
        }
        flushBatch()

        return LocalArchiveImportResult(
            accountId = account.accountId,
            folderId = folder.folderId,
            importedCount = importedCount,
            skippedDuplicates = skippedCount,
            errorCount = errorCount,
        )
    }

    private suspend fun ensureImportTarget(targetAccountId: Long?): Pair<AccountEntity, FolderEntity> {
        val account = targetAccountId?.let { db.accountDao().getById(it) }
            ?: db.accountDao().getByEmail(LOCAL_ARCHIVE_EMAIL)
            ?: run {
                val id = db.accountDao().insert(
                    AccountEntity(
                        emailAddress = LOCAL_ARCHIVE_EMAIL,
                        displayName = "ローカルインポート",
                        imapHost = "localhost",
                        imapPort = 993,
                        smtpHost = "localhost",
                        smtpPort = 465,
                        authType = NonDestructiveSyncEngine.AUTH_TYPE_LOCAL,
                    ),
                )
                db.accountDao().getById(id)!!
            }

        val folder = db.folderDao().getByPath(account.accountId, LOCAL_IMPORT_FOLDER_PATH)
            ?: run {
                val folderId = db.folderDao().insert(
                    FolderEntity(
                        accountId = account.accountId,
                        folderName = "ローカルインポート",
                        fullPath = LOCAL_IMPORT_FOLDER_PATH,
                        uidValidity = 1L,
                    ),
                )
                db.folderSyncStateDao().upsert(
                    FolderSyncStateEntity(
                        folderId = folderId,
                        phase1Completed = true,
                        backfillCompleted = true,
                        updatedAt = clockMs(),
                    ),
                )
                db.folderDao().getById(folderId)!!
            }

        return account to folder
    }

    private suspend fun isDuplicateInFolder(folderId: Long, messageIdHeader: String?): Boolean {
        if (messageIdHeader.isNullOrBlank()) return false
        return db.messageMetaDao().findIdByMessageIdInFolder(folderId, messageIdHeader) != null
    }

    private suspend fun persistParsedRecord(
        accountId: Long,
        folderId: Long,
        uid: Long,
        rec: CoreParsedEmail,
    ) {
        val metaId = db.messageMetaDao().insertIgnore(
            MessageMetaEntity(
                accountId = accountId,
                folderId = folderId,
                uid = uid,
                messageIdHeader = rec.messageIdHeader,
                inReplyTo = rec.inReplyTo,
                threadId = rec.threadKey,
                senderName = rec.senderName,
                senderAddress = rec.senderAddress,
                recipientTo = rec.recipientsToJson,
                subject = rec.subject,
                snippet = rec.snippet,
                receivedAt = rec.receivedAtMs,
                isRead = rec.isRead,
                isFlagged = rec.isFlagged,
                hasAttachments = rec.hasAttachments,
                sizeBytes = rec.sizeBytes,
            ),
        )
        if (metaId <= 0L) return

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
                senderName = rec.senderName,
                senderAddress = rec.senderAddress,
                subject = rec.subject,
                plainText = rec.plainText,
            ),
        )
    }

    private suspend fun refreshFolderCounts(folder: FolderEntity) {
        val total = db.messageMetaDao().countByFolder(folder.folderId)
        val unread = db.messageMetaDao().countUnreadByFolder(folder.folderId)
        val maxUid = db.messageMetaDao().findMaxUid(folder.folderId) ?: folder.lastSyncedUid
        db.folderDao().updateSyncProgress(
            folderId = folder.folderId,
            highestModSeq = folder.highestModSeq,
            lastSyncedUid = maxUid,
            totalCount = total,
            unreadCount = unread,
        )
    }

    private fun readSingleMessageBounded(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buf)
            if (read <= 0) break
            total += read
            if (total > MAX_SINGLE_MESSAGE_BYTES) {
                throw IllegalArgumentException("1通あたりのサイズが上限 (${MAX_SINGLE_MESSAGE_BYTES / (1024 * 1024)}MB) を超えています")
            }
            out.write(buf, 0, read)
        }
        return out.toByteArray()
    }

    private fun looksLikeMboxFromSeparator(line: String): Boolean {
        if (!line.startsWith("From ")) return false
        val parts = line.split(' ', limit = 3)
        return parts.size >= 3 &&
            parts[1].isNotBlank() &&
            !parts[1].contains(':') &&
            parts[2].any { it.isDigit() }
    }

    companion object {
        const val LOCAL_IMPORT_FOLDER_PATH = "__local_import__"
        const val LOCAL_ARCHIVE_EMAIL = "local-archive@kazemail.local"
        private const val BATCH_SIZE = 50
        private const val MAX_SINGLE_MESSAGE_BYTES = 25 * 1024 * 1024
    }
}
