package com.kazemail.app.send

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.kazemail.app.auth.OAuth2SessionResult
import com.kazemail.app.data.db.AccountEntity
import com.kazemail.app.data.db.AttachmentEntity
import com.kazemail.app.data.db.DraftAttachmentRef
import com.kazemail.app.data.db.FolderEntity
import com.kazemail.app.data.db.KazeMailDatabase
import com.kazemail.app.data.db.KazeMailDatabase_Impl
import com.kazemail.app.data.db.MessageBodyEntity
import com.kazemail.app.data.db.MessageMetaEntity
import com.kazemail.app.data.db.OutboxMessageEntity
import com.kazemail.app.export.LocalArchiveExporter
import com.kazemail.app.security.AccountCredentialStore
import com.kazemail.app.security.EncryptedBlob
import com.kazemail.app.security.SecretCipher
import com.kazemail.app.security.StoredAccountCredentials
import com.kazemail.app.security.StoredSecurityMode
import com.kazemail.app.sync.CoreImapAuth
import com.kazemail.app.sync.CoreImapConfig
import com.kazemail.app.sync.CoreOutgoingMessage
import com.kazemail.app.sync.CoreSmtpConfig
import com.kazemail.app.sync.CoreSmtpSendResult
import com.kazemail.app.sync.ImapSessionHandle
import com.kazemail.app.sync.MailCoreEngine
import com.kazemail.app.sync.OAuth2TokenRefresher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.io.path.createTempDirectory

class OutboxAndExportTest {

    private lateinit var db: KazeMailDatabase
    private lateinit var credentialStore: AccountCredentialStore
    private lateinit var fakeMailCore: FakeSmtpMailCoreEngine
    private lateinit var outboxManager: OutboxManager
    private var nowClock = 1_760_000_000_000L

    @Before
    fun setUp() {
        val builder = Room.inMemoryDatabaseBuilder<KazeMailDatabase>(
            factory = { KazeMailDatabase_Impl() },
        )
        db = KazeMailDatabase.buildWithDriver(builder, BundledSQLiteDriver())
        val cipher = object : SecretCipher {
            override fun encrypt(plaintext: ByteArray) = EncryptedBlob(
                Base64.getEncoder().encodeToString(plaintext),
                "AAAA",
            )
            override fun decrypt(blob: EncryptedBlob): ByteArray =
                Base64.getDecoder().decode(blob.ciphertextB64)
        }
        credentialStore = AccountCredentialStore(db.accountSecretDao(), cipher)
        fakeMailCore = FakeSmtpMailCoreEngine()
        outboxManager = OutboxManager(
            db = db,
            credentialStore = credentialStore,
            mailCore = fakeMailCore,
            tokenRefresher = OAuth2TokenRefresher { _, email ->
                OAuth2SessionResult(
                    emailHint = email,
                    accessToken = "refreshed_smtp_token",
                    updatedAuthStateJson = """{"token":"refreshed_smtp_token"}""",
                )
            },
            clockMs = { nowClock++ },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun offlineQueueRetriesTransientNetworkErrorsThenSendsWithStartTlsAndOAuth2() = runTest {
        val accountId = db.accountDao().insert(
            AccountEntity(
                emailAddress = "sender@example.jp",
                displayName = "送信テスト",
                imapHost = "imap.example.jp",
                imapPort = 993,
                smtpHost = "smtp.example.jp",
                smtpPort = 587,
                authType = "OAUTH2",
            ),
        )
        credentialStore.saveCredentials(
            accountId,
            StoredAccountCredentials.OAuth2(
                email = "sender@example.jp",
                providerId = "gmail",
                authStateJson = """{"token":"old"}""",
                securityMode = StoredSecurityMode.IMPLICIT_TLS,
            ),
        )

        val draftId = outboxManager.saveDraft(
            draftId = null,
            accountId = accountId,
            replyToMetaId = null,
            mode = "NEW",
            to = listOf("alice@example.jp"),
            cc = listOf("bob@example.jp"),
            bcc = listOf("secret@example.jp"),
            subject = "オフラインキュー送信テスト",
            bodyText = "ネットワーク復帰後に送信されます。",
            inReplyTo = null,
            references = emptyList(),
            attachments = emptyList(),
        )

        val outboxId = outboxManager.enqueueSend(
            accountId = accountId,
            draftId = draftId,
            to = listOf("alice@example.jp"),
            cc = listOf("bob@example.jp"),
            bcc = listOf("secret@example.jp"),
            subject = "オフラインキュー送信テスト",
            bodyText = "ネットワーク復帰後に送信されます。",
            inReplyTo = null,
            references = emptyList(),
            attachments = emptyList(),
        )
        // Atomic queue transition deletes the draft row
        assertNull(db.draftDao().getById(draftId))
        assertEquals(OutboxMessageEntity.STATUS_QUEUED, db.outboxDao().getById(outboxId)!!.status)

        // First attempt hits transient network timeout -> stays QUEUED with retry flag
        fakeMailCore.nextException = IOException("Connection timed out")
        val retrySummary = outboxManager.processPendingOutbox()
        assertEquals(0, retrySummary.sentCount)
        assertEquals(1, retrySummary.retryPendingCount)
        assertTrue(retrySummary.shouldScheduleRetry)
        val afterRetry = db.outboxDao().getById(outboxId)!!
        assertEquals(OutboxMessageEntity.STATUS_QUEUED, afterRetry.status)
        assertEquals(1, afterRetry.attemptCount)

        // Network returns -> succeeds with port 587 inferred STARTTLS and refreshed OAuth2 token
        fakeMailCore.nextException = null
        val sentSummary = outboxManager.processPendingOutbox()
        assertEquals(1, sentSummary.sentCount)
        assertFalse(sentSummary.shouldScheduleRetry)

        val sentItem = db.outboxDao().getById(outboxId)!!
        assertEquals(OutboxMessageEntity.STATUS_SENT, sentItem.status)
        assertNotNull(sentItem.sentAt)
        assertEquals("<sent-1@example.jp>", sentItem.messageIdHeader)

        val (cfg, msg) = fakeMailCore.sentCalls.single()
        assertEquals(StoredSecurityMode.START_TLS, cfg.security)
        val auth = cfg.auth as CoreImapAuth.OAuth2
        assertEquals("refreshed_smtp_token", auth.accessToken)
        assertEquals(listOf("secret@example.jp"), msg.bccAddresses)
    }

    @Test
    fun permanentAuthFailureMarksFailedAndCanBeMovedBackToDraft() = runTest {
        val accountId = db.accountDao().insert(
            AccountEntity(
                emailAddress = "fail@example.jp",
                imapHost = "imap.example.jp",
                smtpHost = "smtp.example.jp",
                smtpPort = 465,
                authType = "PASSWORD",
            ),
        )
        credentialStore.saveCredentials(
            accountId,
            StoredAccountCredentials.Password("fail@example.jp", "badpass"),
        )

        val outboxId = outboxManager.enqueueSend(
            accountId = accountId,
            draftId = null,
            to = listOf("dest@example.jp"),
            cc = emptyList(),
            bcc = emptyList(),
            subject = "認証エラー",
            bodyText = "大切な下書き本文",
            inReplyTo = null,
            references = emptyList(),
            attachments = emptyList(),
        )

        fakeMailCore.nextException = IllegalStateException("SMTP 535 Authentication credentials invalid")
        val summary = outboxManager.processPendingOutbox()
        assertEquals(1, summary.failedCount)
        assertFalse(summary.shouldScheduleRetry)
        val failed = db.outboxDao().getById(outboxId)!!
        assertEquals(OutboxMessageEntity.STATUS_FAILED, failed.status)
        assertTrue(failed.lastError!!.contains("535"))

        val restoredDraftId = outboxManager.moveOutboxItemToDraft(outboxId)
        assertNotNull(restoredDraftId)
        assertNull(db.outboxDao().getById(outboxId))
        val draft = db.draftDao().getById(restoredDraftId!!)!!
        assertEquals("大切な下書き本文", draft.bodyText)
    }

    @Test
    fun exporterBuildsEmlWithMultipartHtmlAndStreamsChunkedMbox() = runTest {
        val tempDir = createTempDirectory("kazemail-export-test").toFile()
        try {
            val accId = db.accountDao().insert(
                AccountEntity(
                    emailAddress = "me@example.jp",
                    imapHost = "imap.example.jp",
                    smtpHost = "smtp.example.jp",
                    authType = "PASSWORD",
                ),
            )
            val folderId = db.folderDao().insert(
                FolderEntity(
                    accountId = accId,
                    folderName = "INBOX",
                    fullPath = "INBOX",
                    uidValidity = 1L,
                ),
            )

            val localAtt = java.io.File(tempDir, "notes.txt").apply {
                writeText("Attachment content")
            }

            // Insert 105 messages to exercise chunked pagination (>100 batch size) and "From " mboxo escaping
            for (i in 1..105) {
                val metaId = db.messageMetaDao().insertIgnore(
                    MessageMetaEntity(
                        accountId = accId,
                        folderId = folderId,
                        uid = i.toLong(),
                        messageIdHeader = "msg-$i@example.jp",
                        senderName = "佐藤 $i",
                        senderAddress = "sato$i@example.jp",
                        recipientTo = """[{"name":"私","address":"me@example.jp"}]""",
                        subject = "日本語エクスポート #$i",
                        snippet = "Snippet $i",
                        receivedAt = 1_750_000_000_000L + i * 1000L,
                    ),
                )
                db.messageBodyDao().upsert(
                    MessageBodyEntity(
                        metaId = metaId,
                        plainText = "本文 #$i",
                        htmlZstd = if (i == 1) "<p>HTML本文 #1</p>".encodeToByteArray() else null,
                    ),
                )
                if (i == 1) {
                    db.attachmentDao().insertAll(
                        listOf(
                            AttachmentEntity(
                                metaId = metaId,
                                partId = "2",
                                filename = "メモ.txt",
                                mimeType = "text/plain",
                                sizeBytes = localAtt.length(),
                                localFilePath = localAtt.absolutePath,
                            ),
                        ),
                    )
                }
            }

            val m1 = db.messageMetaDao().getByFolderAndUid(folderId, 1L)!!
            val emlText = LocalArchiveExporter.buildEmlBytes(
                meta = m1,
                body = db.messageBodyDao().getByMetaId(m1.id),
                attachments = db.attachmentDao().getByMetaId(m1.id),
                decompressHtml = { it.toString(StandardCharsets.UTF_8) },
            ).toString(StandardCharsets.UTF_8)

            assertTrue(emlText.contains("Subject: =?UTF-8?B?"))
            assertTrue(emlText.contains("multipart/mixed"))
            assertTrue(emlText.contains("multipart/alternative"))
            assertTrue(emlText.contains("Content-Disposition: attachment;"))

            assertEquals("line1\n>From someone\nline3", LocalArchiveExporter.escapeMboxFromLines("line1\nFrom someone\nline3"))

            val out = ByteArrayOutputStream()
            val res = LocalArchiveExporter.exportFolderToMbox(
                db = db,
                folderId = folderId,
                outputStream = out,
                decompressHtml = { it.toString(StandardCharsets.UTF_8) },
            )
            assertEquals(105, res.exportedCount)
            assertTrue(res.totalBytes > 0L)
            val mboxText = out.toString(StandardCharsets.UTF_8)
            assertTrue(mboxText.contains("From sato1@example.jp "))
            assertTrue(mboxText.contains("From sato105@example.jp "))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun htmlComposeSaveDraftEnqueueProcessAndRestorePreservesHtmlBody() = runTest {
        val accountId = db.accountDao().insert(
            AccountEntity(
                emailAddress = "htmlsender@example.jp",
                imapHost = "imap.example.jp",
                smtpHost = "smtp.example.jp",
                smtpPort = 465,
                authType = "PASSWORD",
            ),
        )
        credentialStore.saveCredentials(
            accountId,
            StoredAccountCredentials.Password("htmlsender@example.jp", "secret"),
        )

        val fragment = "<p>お世話になっております。</p>\n<p><b>重要なお知らせ</b>です。</p>"
        val draftId = outboxManager.saveDraft(
            draftId = null,
            accountId = accountId,
            replyToMetaId = null,
            mode = "NEW",
            to = listOf("dest@example.jp"),
            cc = emptyList(),
            bcc = emptyList(),
            subject = "HTML メール送信テスト",
            bodyText = "",
            inReplyTo = null,
            references = emptyList(),
            attachments = emptyList(),
            isHtml = true,
            bodyHtml = fragment,
        )
        val savedDraft = db.draftDao().getById(draftId)!!
        assertTrue(savedDraft.isHtml)
        assertEquals(fragment, savedDraft.bodyHtml)
        assertTrue(savedDraft.bodyText.contains("重要なお知らせ"))

        val outboxId = outboxManager.enqueueSend(
            accountId = accountId,
            draftId = draftId,
            to = listOf("dest@example.jp"),
            cc = emptyList(),
            bcc = emptyList(),
            subject = "HTML メール送信テスト",
            bodyText = "",
            inReplyTo = null,
            references = emptyList(),
            attachments = emptyList(),
            bodyHtml = fragment,
        )
        assertNull(db.draftDao().getById(draftId))
        val queued = db.outboxDao().getById(outboxId)!!
        assertEquals(fragment, queued.bodyHtml)
        assertTrue(queued.bodyText.contains("お世話になっております。"))

        // Fail permanently -> move back to draft -> verify HTML flag & source restored
        fakeMailCore.nextException = IllegalStateException("SMTP 535 Authentication failed")
        outboxManager.processPendingOutbox()
        val restoredDraftId = outboxManager.moveOutboxItemToDraft(outboxId)!!
        val restoredDraft = db.draftDao().getById(restoredDraftId)!!
        assertTrue(restoredDraft.isHtml)
        assertEquals(fragment, restoredDraft.bodyHtml)

        // Re-enqueue and send successfully -> CoreOutgoingMessage carries wrapped HTML + plain fallback
        val retryOutboxId = outboxManager.enqueueSend(
            accountId = accountId,
            draftId = restoredDraftId,
            to = listOf("dest@example.jp"),
            cc = emptyList(),
            bcc = emptyList(),
            subject = restoredDraft.subject,
            bodyText = restoredDraft.bodyText,
            inReplyTo = null,
            references = emptyList(),
            attachments = emptyList(),
            bodyHtml = restoredDraft.bodyHtml,
        )
        fakeMailCore.nextException = null
        val summary = outboxManager.processPendingOutbox()
        assertEquals(1, summary.sentCount)

        val (_, sentMessage) = fakeMailCore.sentCalls.single()
        assertTrue(sentMessage.plainText.contains("重要なお知らせ"))
        val sentHtml = requireNotNull(sentMessage.htmlBody)
        assertTrue(sentHtml.contains("<!DOCTYPE html>"))
        assertTrue(sentHtml.contains("<b>重要なお知らせ</b>"))
    }
}

private class FakeSmtpMailCoreEngine : MailCoreEngine {
    val sentCalls = mutableListOf<Pair<CoreSmtpConfig, CoreOutgoingMessage>>()
    var nextException: Throwable? = null
    private var seq = 1

    override suspend fun connect(config: CoreImapConfig): ImapSessionHandle = error("not used")
    override suspend fun decompressHtmlZstd(compressed: ByteArray, maxOutputBytes: Long): String =
        compressed.decodeToString()

    override suspend fun sendSmtpMessage(
        config: CoreSmtpConfig,
        message: CoreOutgoingMessage,
    ): CoreSmtpSendResult {
        nextException?.let { throw it }
        sentCalls += config to message
        return CoreSmtpSendResult(
            messageId = "<sent-${seq++}@example.jp>",
            serverResponse = "250 2.0.0 Ok",
        )
    }
}
