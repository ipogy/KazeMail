package com.kazemail.app.data.db

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class KazeMailDatabaseFts5Test {

    private fun openTempDiskDb(file: File): KazeMailDatabase {
        val builder = Room.databaseBuilder<KazeMailDatabase>(
            name = file.absolutePath,
            factory = { KazeMailDatabase_Impl() },
        )
        return KazeMailDatabase.buildWithDriver(builder, BundledSQLiteDriver())
    }

    @Test
    fun diskReopenWithPartialIndexAndFts5TrigramWorksCleanly() = runTest {
        val tempDir = createTempDirectory("kazemail-db-test").toFile()
        val dbFile = File(tempDir, "test_reopen.db")

        try {
            val db1 = openTempDiskDb(dbFile)
            val accountId = db1.accountDao().insert(
                AccountEntity(
                    emailAddress = "taro@example.jp",
                    displayName = "太郎",
                    imapHost = "imap.example.jp",
                    smtpHost = "smtp.example.jp",
                    authType = "PASSWORD",
                ),
            )
            val folderId = db1.folderDao().insert(
                FolderEntity(
                    accountId = accountId,
                    folderName = "INBOX",
                    fullPath = "INBOX",
                    uidValidity = 1001L,
                ),
            )
            val metaId = db1.messageMetaDao().insertIgnore(
                MessageMetaEntity(
                    accountId = accountId,
                    folderId = folderId,
                    uid = 55L,
                    messageIdHeader = "msg-55@example.jp",
                    threadId = "mid:msg-55@example.jp",
                    senderName = "田中 一郎",
                    senderAddress = "tanaka@corp.jp",
                    recipientTo = """[{"name":"太郎","address":"taro@example.jp"}]""",
                    subject = "【重要】11月度 請求書のご確認",
                    snippet = "添付の請求書PDFをご確認のほどよろしくお願いいたします。",
                    receivedAt = 1_750_000_000_000L,
                    isRead = false,
                    isFlagged = true,
                    hasAttachments = true,
                    sizeBytes = 42_000L,
                ),
            )
            db1.messageBodyDao().upsert(
                MessageBodyEntity(
                    metaId = metaId,
                    plainText = "お疲れ様です。田中です。11月度の請求書を送付いたします。会議室Aにて打ち合わせ予定です。",
                    htmlZstd = byteArrayOf(1, 2, 3),
                    rawHeaders = "From: tanaka@corp.jp\r\n",
                ),
            )
            db1.attachmentDao().insertAll(
                listOf(
                    AttachmentEntity(
                        metaId = metaId,
                        partId = "2",
                        filename = "invoice_202611.pdf",
                        mimeType = "application/pdf",
                        sizeBytes = 38_000L,
                        localFilePath = null,
                    ),
                ),
            )
            db1.messageFtsDao().insert(
                MessageFtsEntity(
                    metaId = metaId,
                    senderName = "田中 一郎",
                    senderAddress = "tanaka@corp.jp",
                    subject = "【重要】11月度 請求書のご確認",
                    plainText = "お疲れ様です。田中です。11月度の請求書を送付いたします。会議室Aにて打ち合わせ予定です。",
                ),
            )
            db1.close()

            // Reopen database from disk to verify Room schema validation + partial index compatibility
            val db2 = openTempDiskDb(dbFile)
            val trigramHits = db2.messageFtsDao().search(
                FtsQueryBuilder.buildSearchQuery("会議室 from:tanaka has:attachment is:unread"),
            )
            assertEquals(1, trigramHits.size)
            assertEquals(55L, trigramHits[0].uid)

            val shortTermHits = db2.messageFtsDao().search(
                FtsQueryBuilder.buildSearchQuery("請求"),
            )
            assertEquals(1, shortTermHits.size)

            val dateExcludedHits = db2.messageFtsDao().search(
                FtsQueryBuilder.buildSearchQuery("会議室 before:2024-01-01"),
            )
            assertTrue(dateExcludedHits.isEmpty())

            val attachments = db2.attachmentDao().getByMetaId(metaId)
            assertEquals(1, attachments.size)
            assertEquals(null, attachments[0].localFilePath)
            db2.close()
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
