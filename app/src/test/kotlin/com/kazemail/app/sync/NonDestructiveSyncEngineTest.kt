package com.kazemail.app.sync

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.kazemail.app.auth.OAuth2SessionResult
import com.kazemail.app.data.db.AccountEntity
import com.kazemail.app.data.db.KazeMailDatabase
import com.kazemail.app.data.db.KazeMailDatabase_Impl
import com.kazemail.app.security.AccountCredentialStore
import com.kazemail.app.security.EncryptedBlob
import com.kazemail.app.security.SecretCipher
import com.kazemail.app.security.StoredAccountCredentials
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64
import kotlin.io.path.createTempDirectory

class NonDestructiveSyncEngineTest {

    private lateinit var db: KazeMailDatabase
    private lateinit var credentialStore: AccountCredentialStore
    private lateinit var fakeCore: FakeMailCoreEngine
    private lateinit var syncEngine: NonDestructiveSyncEngine
    private var refreshedAuthStateCalled = false

    @Before
    fun setUp() {
        val builder = Room.inMemoryDatabaseBuilder<KazeMailDatabase>(
            factory = { KazeMailDatabase_Impl() },
        )
        db = KazeMailDatabase.buildWithDriver(builder, BundledSQLiteDriver())
        val cipher = XorTestCipher()
        credentialStore = AccountCredentialStore(db.accountSecretDao(), cipher)
        fakeCore = FakeMailCoreEngine()
        syncEngine = NonDestructiveSyncEngine(
            db = db,
            credentialStore = credentialStore,
            mailCore = fakeCore,
            tokenRefresher = OAuth2TokenRefresher { _, email ->
                refreshedAuthStateCalled = true
                OAuth2SessionResult(
                    emailHint = email,
                    accessToken = "fresh_access_token_xyz",
                    updatedAuthStateJson = """{"refresh":"ok","token":"fresh_access_token_xyz"}""",
                )
            },
            clockMs = { 1_760_000_000_000L },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun phase1AndResumablePhase2AndCondstoreAndUidValidityPreservation() = runTest {
        val accountId = db.accountDao().insert(
            AccountEntity(
                emailAddress = "user@example.jp",
                displayName = "Kaze User",
                imapHost = "imap.example.jp",
                imapPort = 993,
                smtpHost = "smtp.example.jp",
                smtpPort = 465,
                authType = "OAUTH2",
            ),
        )
        credentialStore.saveCredentials(
            accountId,
            StoredAccountCredentials.OAuth2(
                email = "user@example.jp",
                providerId = "gmail",
                authStateJson = """{"refresh":"initial"}""",
            ),
        )

        // Seed 1,200 messages in server INBOX (UIDs 1..1200)
        fakeCore.seedInboxMessages(1..1200, uidValidity = 100L, highestModSeq = 5000L)

        // Phase 1: Immediate top 100 messages (UIDs 1101..1200)
        val p1 = syncEngine.runPhase1InboxSync(accountId)
        assertTrue(refreshedAuthStateCalled)
        assertEquals(100, p1.newlySyncedCount)
        assertFalse(p1.backfillCompleted)
        assertEquals(100, db.messageMetaDao().countByFolder(p1.inboxFolderId))

        // Verify refreshed OAuth2 state was persisted encrypted in account_secrets
        val updatedCreds = credentialStore.loadCredentials(accountId) as StoredAccountCredentials.OAuth2
        assertTrue(updatedCreds.authStateJson.contains("fresh_access_token_xyz"))

        // Phase 2 (Chunk 1 only -> interrupted after 500 messages: UIDs 601..1100)
        var chunksProcessed = 0
        val syncedPart1 = syncEngine.runPhase2Backfill(
            accountId = accountId,
            shouldContinue = { chunksProcessed < 1 },
            onProgress = { chunksProcessed += 1 },
        )
        assertEquals(500, syncedPart1)
        assertEquals(600, db.messageMetaDao().countByFolder(p1.inboxFolderId))
        val checkpointState = db.folderSyncStateDao().getByFolderId(p1.inboxFolderId)
        assertNotNull(checkpointState)
        assertFalse(checkpointState!!.backfillCompleted)
        assertEquals(600L, checkpointState.backfillNextMaxUid)

        // Resume Phase 2 -> finishes remaining 600 messages (UIDs 1..600)
        val syncedPart2 = syncEngine.runPhase2Backfill(accountId = accountId)
        assertEquals(600, syncedPart2)
        assertEquals(1200, db.messageMetaDao().countByFolder(p1.inboxFolderId))
        assertTrue(db.folderSyncStateDao().getByFolderId(p1.inboxFolderId)!!.backfillCompleted)

        // Incremental sync via CONDSTORE: flag change on UID 1200 + 2 new UIDs + 1 server vanished UID
        fakeCore.setFlagUpdate(uid = 1200L, isRead = true, isFlagged = true, modSeq = 5001L)
        fakeCore.seedInboxMessages(1201..1202, uidValidity = 100L, highestModSeq = 5002L)
        fakeCore.reportedVanishedUids = listOf(10L)

        val deltaRes = syncEngine.runIncrementalSync(accountId)
        assertEquals(2, deltaRes.newlySyncedCount)
        assertEquals(1, deltaRes.flagUpdatedCount)
        assertEquals(0, deltaRes.uidValidityChangedFolders)
        assertEquals(2, deltaRes.newInboxUnreadMessages.size)
        // Iron rule: vanished UID 10 is NEVER deleted locally!
        assertNotNull(db.messageMetaDao().getByFolderAndUid(p1.inboxFolderId, 10L))
        val updatedMsg1200 = db.messageMetaDao().getByFolderAndUid(p1.inboxFolderId, 1200L)!!
        assertTrue(updatedMsg1200.isRead)
        assertTrue(updatedMsg1200.isFlagged)
        assertEquals(1202, db.messageMetaDao().countByFolder(p1.inboxFolderId))

        // UIDVALIDITY changed on server (100 -> 200): local messages must NOT be deleted!
        fakeCore.resetInboxWithNewUidValidity(newUidRange = 1..3, newUidValidity = 200L, highestModSeq = 10L)
        val uidvDelta = syncEngine.runIncrementalSync(accountId)
        assertEquals(1, uidvDelta.uidValidityChangedFolders)
        assertTrue(uidvDelta.newInboxUnreadMessages.isEmpty())
        val allFolders = db.folderDao().getByAccount(accountId)
        val archiveFolder = allFolders.firstOrNull { it.fullPath.startsWith("__archive__/INBOX#uidv_100_") }
        val activeInbox = allFolders.first { it.fullPath == "INBOX" }
        assertNotNull(archiveFolder)
        assertEquals(1202, db.messageMetaDao().countByFolder(archiveFolder!!.folderId))
        assertEquals(3, db.messageMetaDao().countByFolder(activeInbox.folderId))
        assertEquals(200L, activeInbox.uidValidity)

        // Local FTS5 trigram search still finds archived + active Japanese mail
        val hits = syncEngine.searchLocal("テスト件名", accountId = accountId)
        assertTrue(hits.isNotEmpty())
    }

    @Test
    fun onDemandAttachmentDownloadSavesFileAndUpdatesLocalPath() = runTest {
        val accountId = db.accountDao().insert(
            AccountEntity(
                emailAddress = "att@example.jp",
                imapHost = "imap.example.jp",
                smtpHost = "smtp.example.jp",
                authType = "PASSWORD",
            ),
        )
        credentialStore.saveCredentials(
            accountId,
            StoredAccountCredentials.Password("att@example.jp", "secret"),
        )
        fakeCore.seedInboxMessages(1..1, uidValidity = 1L, highestModSeq = 1L, withAttachment = true)
        val p1 = syncEngine.runPhase1InboxSync(accountId)
        val meta = db.messageMetaDao().getByFolderAndUid(p1.inboxFolderId, 1L)!!
        val att = db.attachmentDao().getByMetaId(meta.id).single()
        assertEquals(null, att.localFilePath)

        val tempDir = createTempDirectory("kazemail-att").toFile()
        try {
            val saved = syncEngine.downloadAttachmentOnDemand(accountId, meta.id, att.attachmentId, tempDir)
            assertTrue(saved.exists())
            assertEquals("PDF-BYTES", saved.readText())
            val reloaded = db.attachmentDao().getByMetaId(meta.id).single()
            assertEquals(saved.absolutePath, reloaded.localFilePath)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun folderNameFormatterDecodesModifiedUtf7AndHealsStoredFolderNames() = runTest {
        assertEquals("下書き", ImapFolderNameFormatter.decodeModifiedUtf7("&Tgtm+DBN-"))
        assertEquals(
            "すべてのメール",
            ImapFolderNameFormatter.formatDisplayName("[Gmail]/&MFkweTBmMG4w4TD8MOs-", "&MFkweTBmMG4w4TD8MOs-"),
        )
        assertEquals(
            "受信トレイ",
            ImapFolderNameFormatter.formatDisplayName("INBOX", "INBOX"),
        )
        assertEquals(
            "R&D",
            ImapFolderNameFormatter.formatDisplayName("R&-D", "R&-D"),
        )

        val accountId = db.accountDao().insert(
            AccountEntity(
                emailAddress = "mutf7@example.jp",
                imapHost = "imap.example.jp",
                smtpHost = "smtp.example.jp",
                authType = "PASSWORD",
            ),
        )
        credentialStore.saveCredentials(
            accountId,
            StoredAccountCredentials.Password("mutf7@example.jp", "secret"),
        )
        // Legacy DB row still containing raw encoded mUTF-7 name before upgrade
        db.folderDao().insert(
            com.kazemail.app.data.db.FolderEntity(
                accountId = accountId,
                folderName = "&MFkweTBmMG4w4TD8MOs-",
                fullPath = "[Gmail]/&MFkweTBmMG4w4TD8MOs-",
                uidValidity = 1L,
            ),
        )

        fakeCore.extraFolders = listOf(
            CoreFolderInfo("[Gmail]/&MFkweTBmMG4w4TD8MOs-", "&MFkweTBmMG4w4TD8MOs-", "/", emptyList(), true),
        )
        fakeCore.seedInboxMessages(1..1, uidValidity = 1L, highestModSeq = 1L)
        syncEngine.runPhase1InboxSync(accountId)

        val healed = db.folderDao().getByPath(accountId, "[Gmail]/&MFkweTBmMG4w4TD8MOs-")
        assertEquals("すべてのメール", healed?.folderName)
    }

    @Test
    fun incrementalSyncBatchIsolatesAccountFailuresWithoutSkippingOtherAccounts() = runTest {
        val processed = mutableListOf<Long>()
        val summary = runIncrementalSyncBatch(
            accountIds = listOf(1L, 2L, 3L),
            isStopped = { false },
        ) { accId ->
            if (accId == 1L) {
                error("transient university VPN failure")
            }
            processed += accId
        }
        assertEquals(2, summary.succeededCount)
        assertEquals(1, summary.failedCount)
        assertEquals(listOf(2L, 3L), processed)
    }
}

private class XorTestCipher : SecretCipher {
    override fun encrypt(plaintext: ByteArray): EncryptedBlob {
        val xored = plaintext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        return EncryptedBlob(
            ciphertextB64 = Base64.getEncoder().encodeToString(xored),
            ivB64 = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4)),
        )
    }

    override fun decrypt(blob: EncryptedBlob): ByteArray {
        val raw = Base64.getDecoder().decode(blob.ciphertextB64)
        return raw.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }
}

private class FakeMailCoreEngine : MailCoreEngine {
    private val messages = linkedMapOf<Long, CoreParsedEmail>()
    private val pendingFlagChanges = mutableListOf<CoreUidFlagChange>()
    var uidValidity: Long = 100L
    var highestModSeq: Long = 1L
    var reportedVanishedUids: List<Long> = emptyList()
    var extraFolders: List<CoreFolderInfo> = emptyList()

    fun seedInboxMessages(
        range: IntRange,
        uidValidity: Long,
        highestModSeq: Long,
        withAttachment: Boolean = false,
    ) {
        this.uidValidity = uidValidity
        this.highestModSeq = highestModSeq
        for (uid in range) {
            val u = uid.toLong()
            messages[u] = CoreParsedEmail(
                uid = u,
                messageIdHeader = "id-$uidValidity-$u@example.jp",
                inReplyTo = null,
                references = emptyList(),
                threadKey = "mid:id-$uidValidity-$u@example.jp",
                senderName = "送信者$u",
                senderAddress = "sender$u@example.jp",
                recipientsToJson = "[]",
                subject = "テスト件名 #$u",
                snippet = "これはUID $u の本文スニペットです。",
                receivedAtMs = 1_750_000_000_000L + u * 1000L,
                isRead = false,
                isFlagged = false,
                hasAttachments = withAttachment,
                sizeBytes = 1024L,
                plainText = "これはUID $u のテスト本文です。",
                htmlZstd = null,
                rawHeaders = "Subject: テスト\r\n",
                isTruncated = false,
                attachments = if (withAttachment) {
                    listOf(CoreAttachmentMeta("2", "doc.pdf", "application/pdf", 9L))
                } else {
                    emptyList()
                },
            )
        }
    }

    fun setFlagUpdate(uid: Long, isRead: Boolean, isFlagged: Boolean, modSeq: Long) {
        highestModSeq = maxOf(highestModSeq, modSeq)
        messages[uid]?.let { messages[uid] = it.copy(isRead = isRead, isFlagged = isFlagged) }
        pendingFlagChanges += CoreUidFlagChange(uid, isRead, isFlagged, modSeq)
    }

    fun resetInboxWithNewUidValidity(newUidRange: IntRange, newUidValidity: Long, highestModSeq: Long) {
        messages.clear()
        pendingFlagChanges.clear()
        reportedVanishedUids = emptyList()
        seedInboxMessages(newUidRange, newUidValidity, highestModSeq)
    }

    override suspend fun connect(config: CoreImapConfig): ImapSessionHandle =
        object : ImapSessionHandle {
            override suspend fun listFolders(): List<CoreFolderInfo> =
                listOf(CoreFolderInfo("INBOX", "INBOX", "/", emptyList(), true)) + extraFolders

            override suspend fun examineFolder(fullPath: String): CoreFolderSnapshot =
                CoreFolderSnapshot(
                    fullPath = fullPath,
                    uidValidity = uidValidity,
                    highestModSeq = highestModSeq,
                    exists = messages.size,
                    uidNext = (messages.keys.maxOrNull() ?: 0L) + 1L,
                    supportsCondstore = true,
                    supportsQresync = true,
                    supportsIdle = true,
                )

            override suspend fun fetchLatestUids(fullPath: String, limit: Int): List<Long> =
                messages.keys.sortedDescending().take(limit)

            override suspend fun fetchAllUidsDescending(fullPath: String): List<Long> =
                messages.keys.sortedDescending()

            override suspend fun fetchMessagesByUids(fullPath: String, uids: List<Long>): List<CoreParsedEmail> =
                uids.mapNotNull { messages[it] }

            override suspend fun fetchIncrementalDelta(
                fullPath: String,
                knownLastUid: Long,
                knownHighestModSeq: Long,
            ): CoreIncrementalDelta {
                val newUids = messages.keys.filter { it > knownLastUid }.sortedDescending()
                val changes = pendingFlagChanges.toList()
                pendingFlagChanges.clear()
                return CoreIncrementalDelta(
                    newUids = newUids,
                    flagChanges = changes,
                    vanishedUids = reportedVanishedUids,
                    snapshot = examineFolder(fullPath),
                )
            }

            override suspend fun fetchMessagePart(
                fullPath: String,
                uid: Long,
                partId: String,
                maxBytes: Long?,
            ): ByteArray = "PDF-BYTES".encodeToByteArray()

            override suspend fun waitForIdleEvent(fullPath: String, timeoutSecs: Int): Boolean = false
            override suspend fun logout() {}
            override fun close() {}
        }

    override suspend fun decompressHtmlZstd(compressed: ByteArray, maxOutputBytes: Long): String =
        compressed.decodeToString()
}
