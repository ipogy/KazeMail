package com.kazemail.app.data

import androidx.paging.PagingSource
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.kazemail.app.data.db.AccountEntity
import com.kazemail.app.data.db.AiProviderProfileEntity
import com.kazemail.app.data.db.AiWritingPresetEntity
import com.kazemail.app.data.db.FolderEntity
import com.kazemail.app.data.db.KazeMailDatabase
import com.kazemail.app.data.db.KazeMailDatabase_Impl
import com.kazemail.app.data.db.LocalFilterRuleEntity
import com.kazemail.app.data.db.MessageBodyEntity
import com.kazemail.app.data.db.MessageFtsEntity
import com.kazemail.app.data.db.MessageMetaEntity
import com.kazemail.app.filter.FilterField
import com.kazemail.app.filter.FilterOperator
import com.kazemail.app.filter.LocalFilterCondition
import com.kazemail.app.filter.LocalFilterEngine
import com.kazemail.app.security.AccountCredentialStore
import com.kazemail.app.security.EncryptedBlob
import com.kazemail.app.security.SecretCipher
import com.kazemail.app.sync.CoreImapConfig
import com.kazemail.app.sync.ImapSessionHandle
import com.kazemail.app.sync.MailCoreEngine
import com.kazemail.app.sync.NonDestructiveSyncEngine
import com.kazemail.app.sync.OAuth2TokenRefresher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.charset.Charset
import java.util.Base64
import kotlin.io.path.createTempDirectory

class MessageRepositoryAndMigrationTest {

    private lateinit var db: KazeMailDatabase
    private lateinit var repo: MessageRepository

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
        val mailCore = object : MailCoreEngine {
            override suspend fun connect(config: CoreImapConfig): ImapSessionHandle =
                error("not used")
            override suspend fun decompressHtmlZstd(compressed: ByteArray, maxOutputBytes: Long) =
                compressed.decodeToString()
        }
        val syncEngine = NonDestructiveSyncEngine(
            db = db,
            credentialStore = AccountCredentialStore(db.accountSecretDao(), cipher),
            mailCore = mailCore,
            tokenRefresher = OAuth2TokenRefresher { _, _ -> error("not used") },
        )
        repo = MessageRepository(db, mailCore, syncEngine, clockMs = { 2_000_000_000L })
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun threadPagingAndNonDestructiveArchiveWithUndoWorkCleanly() = runTest {
        val accountId = db.accountDao().insert(
            AccountEntity(
                emailAddress = "me@kazemail.app",
                imapHost = "imap.kazemail.app",
                smtpHost = "smtp.kazemail.app",
                authType = "PASSWORD",
            ),
        )
        val inboxId = db.folderDao().insert(
            FolderEntity(
                accountId = accountId,
                folderName = "INBOX",
                fullPath = "INBOX",
                uidValidity = 1L,
            ),
        )

        val m1 = db.messageMetaDao().insertIgnore(
            MessageMetaEntity(
                accountId = accountId,
                folderId = inboxId,
                uid = 1L,
                threadId = "mid:t1",
                senderName = "佐藤",
                senderAddress = "sato@example.jp",
                subject = "設計レビュー",
                snippet = "初版を共有します",
                receivedAt = 1000L,
                isRead = true,
            ),
        )
        val m2 = db.messageMetaDao().insertIgnore(
            MessageMetaEntity(
                accountId = accountId,
                folderId = inboxId,
                uid = 2L,
                threadId = "mid:t1",
                senderName = "鈴木",
                senderAddress = "suzuki@example.jp",
                subject = "Re: 設計レビュー",
                snippet = "LGTMです！",
                receivedAt = 2000L,
                isRead = false,
                hasAttachments = true,
            ),
        )
        db.messageBodyDao().upsert(
            MessageBodyEntity(
                metaId = m2,
                plainText = "LGTMです！\n> 初版を共有します",
                htmlZstd = "<p>LGTM!</p>".encodeToByteArray(),
            ),
        )
        val m3 = db.messageMetaDao().insertIgnore(
            MessageMetaEntity(
                accountId = accountId,
                folderId = inboxId,
                uid = 3L,
                threadId = "mid:t2",
                senderName = "高橋",
                senderAddress = "takahashi@example.jp",
                subject = "単独メール",
                snippet = "確認お願いします",
                receivedAt = 1500L,
                isRead = true,
            ),
        )

        // Thread paging returns 2 threads ordered by MAX(received_at) DESC: t1 (2000), t2 (1500)
        val threadPage = db.messageMetaDao().pagingSourceThreadsByFolder(inboxId).load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 20, placeholdersEnabled = false),
        ) as PagingSource.LoadResult.Page
        assertEquals(2, threadPage.data.size)
        assertEquals("mid:t1", threadPage.data[0].threadKey)
        assertEquals(2, threadPage.data[0].messageCount)
        assertEquals(1, threadPage.data[0].unreadCount)
        assertEquals("鈴木", threadPage.data[0].senderName)
        assertTrue(threadPage.data[0].hasAttachments)

        // Load full conversation for thread t1 + verify on-demand HTML decompression
        val conversation = repo.loadConversation(accountId, m2, "mid:t1", loadFullThread = true)
        assertEquals(2, conversation.size)
        assertEquals(m1, conversation[0].meta.id)
        assertEquals(m2, conversation[1].meta.id)
        val html = repo.decompressHtmlInMemory(conversation[1].body!!.htmlZstd!!)
        assertEquals("<p>LGTM!</p>", html)

        // Archive thread t1 locally: hidden from INBOX thread & flat paging, visible in local archive, NEVER deleted from DB
        val archivedIds = repo.archiveThreadInFolderLocally(accountId, inboxId, "mid:t1")
        assertEquals(listOf(m1, m2), archivedIds)
        assertEquals(3, db.messageMetaDao().countByFolder(inboxId))

        val afterArchiveThreadPage = db.messageMetaDao().pagingSourceThreadsByFolder(inboxId).load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 20, placeholdersEnabled = false),
        ) as PagingSource.LoadResult.Page
        assertEquals(1, afterArchiveThreadPage.data.size)
        assertEquals(m3, afterArchiveThreadPage.data[0].latestMetaId)

        val archivedPage = db.messageMetaDao().pagingSourceLocallyArchived(accountId).load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 20, placeholdersEnabled = false),
        ) as PagingSource.LoadResult.Page
        assertEquals(2, archivedPage.data.size)

        // Undo restores thread t1 back to active INBOX
        repo.unarchiveMessagesLocally(archivedIds)
        val afterUndoPage = db.messageMetaDao().pagingSourceThreadsByFolder(inboxId).load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 20, placeholdersEnabled = false),
        ) as PagingSource.LoadResult.Page
        assertEquals(2, afterUndoPage.data.size)
    }

    @Test
    fun migration1To2PreservesV1MessagesAndEnablesLocalArchiveState() = runTest {
        val tempDir = createTempDirectory("kazemail-mig-test").toFile()
        val dbFile = File(tempDir, "mig_v1_v2.db")
        try {
            val driver = BundledSQLiteDriver()
            val conn = driver.open(dbFile.absolutePath)
            // Create v1 baseline tables matching version 1 schema
            listOf(
                "CREATE TABLE IF NOT EXISTS `accounts` (`account_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `email_address` TEXT NOT NULL, `display_name` TEXT, `imap_host` TEXT NOT NULL, `imap_port` INTEGER NOT NULL, `smtp_host` TEXT NOT NULL, `smtp_port` INTEGER NOT NULL, `auth_type` TEXT NOT NULL, `last_sync_at` INTEGER)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_accounts_email_address` ON `accounts` (`email_address`)",
                "CREATE TABLE IF NOT EXISTS `folders` (`folder_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `account_id` INTEGER NOT NULL, `folder_name` TEXT NOT NULL, `full_path` TEXT NOT NULL, `uid_validity` INTEGER NOT NULL, `highest_mod_seq` INTEGER NOT NULL, `last_synced_uid` INTEGER NOT NULL, `total_count` INTEGER NOT NULL, `unread_count` INTEGER NOT NULL, FOREIGN KEY(`account_id`) REFERENCES `accounts`(`account_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_folders_account_id_full_path` ON `folders` (`account_id`, `full_path`)",
                "CREATE TABLE IF NOT EXISTS `messages_meta` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `account_id` INTEGER NOT NULL, `folder_id` INTEGER NOT NULL, `uid` INTEGER NOT NULL, `message_id_header` TEXT, `in_reply_to` TEXT, `thread_id` TEXT, `sender_name` TEXT, `sender_address` TEXT NOT NULL, `recipient_to` TEXT, `subject` TEXT, `snippet` TEXT, `received_at` INTEGER NOT NULL, `is_read` INTEGER NOT NULL, `is_flagged` INTEGER NOT NULL, `has_attachments` INTEGER NOT NULL, `size_bytes` INTEGER NOT NULL, FOREIGN KEY(`folder_id`) REFERENCES `folders`(`folder_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_messages_meta_folder_id_uid` ON `messages_meta` (`folder_id`, `uid`)",
                "CREATE INDEX IF NOT EXISTS `idx_meta_folder_date` ON `messages_meta` (`folder_id` ASC, `received_at` DESC)",
                "CREATE INDEX IF NOT EXISTS `idx_meta_thread` ON `messages_meta` (`thread_id` ASC, `received_at` ASC)",
                "CREATE TABLE IF NOT EXISTS `messages_body` (`meta_id` INTEGER NOT NULL, `plain_text` TEXT, `html_zstd` BLOB, `raw_headers` TEXT, PRIMARY KEY(`meta_id`), FOREIGN KEY(`meta_id`) REFERENCES `messages_meta`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE TABLE IF NOT EXISTS `attachments` (`attachment_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `meta_id` INTEGER NOT NULL, `part_id` TEXT NOT NULL, `filename` TEXT NOT NULL, `mime_type` TEXT NOT NULL, `size_bytes` INTEGER NOT NULL, `local_file_path` TEXT, FOREIGN KEY(`meta_id`) REFERENCES `messages_meta`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE INDEX IF NOT EXISTS `index_attachments_meta_id` ON `attachments` (`meta_id`)",
                "CREATE VIRTUAL TABLE IF NOT EXISTS `messages_fts` USING FTS5(`meta_id` UNINDEXED, `sender_name`, `sender_address`, `subject`, `plain_text`, tokenize=`trigram`)",
                "CREATE TABLE IF NOT EXISTS `folder_sync_state` (`folder_id` INTEGER NOT NULL, `phase1_completed` INTEGER NOT NULL, `backfill_completed` INTEGER NOT NULL, `backfill_next_max_uid` INTEGER NOT NULL, `supports_condstore` INTEGER NOT NULL, `supports_qresync` INTEGER NOT NULL, `supports_idle` INTEGER NOT NULL, `last_error` TEXT, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`folder_id`), FOREIGN KEY(`folder_id`) REFERENCES `folders`(`folder_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE TABLE IF NOT EXISTS `account_secrets` (`account_id` INTEGER NOT NULL, `encrypted_payload_b64` TEXT NOT NULL, `iv_b64` TEXT NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`account_id`), FOREIGN KEY(`account_id`) REFERENCES `accounts`(`account_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE TABLE IF NOT EXISTS `message_sync_flags` (`meta_id` INTEGER NOT NULL, `is_body_truncated` INTEGER NOT NULL, PRIMARY KEY(`meta_id`), FOREIGN KEY(`meta_id`) REFERENCES `messages_meta`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
                "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '48b0a39c923ab23cf80bec9d38918b73')",
                "INSERT INTO accounts (account_id, email_address, imap_host, imap_port, smtp_host, smtp_port, auth_type) VALUES (1, 'v1@example.jp', 'imap.example.jp', 993, 'smtp.example.jp', 465, 'PASSWORD')",
                "INSERT INTO folders (folder_id, account_id, folder_name, full_path, uid_validity, highest_mod_seq, last_synced_uid, total_count, unread_count) VALUES (1, 1, 'INBOX', 'INBOX', 1, 0, 1, 1, 0)",
                "INSERT INTO messages_meta (id, account_id, folder_id, uid, sender_address, subject, received_at, is_read, is_flagged, has_attachments, size_bytes) VALUES (10, 1, 1, 1, 'old@example.jp', 'V1 Legacy Mail', 5000, 1, 0, 0, 100)",
                "PRAGMA user_version = 1",
            ).forEach { sql ->
                conn.prepare(sql).use { it.step() }
            }
            conn.close()

            val migratedDb = KazeMailDatabase.buildWithDriver(
                Room.databaseBuilder<KazeMailDatabase>(
                    name = dbFile.absolutePath,
                    factory = { KazeMailDatabase_Impl() },
                ),
                BundledSQLiteDriver(),
            )
            val legacyMsg = migratedDb.messageMetaDao().getById(10L)
            assertNotNull(legacyMsg)
            assertEquals("V1 Legacy Mail", legacyMsg!!.subject)
            migratedDb.close()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun migration2To3PreservesV2DataAndAddsDraftsAndOutbox() = runTest {
        val tempDir = createTempDirectory("kazemail-mig23-test").toFile()
        val dbFile = File(tempDir, "mig_v2_v3.db")
        try {
            val driver = BundledSQLiteDriver()
            val conn = driver.open(dbFile.absolutePath)
            listOf(
                "CREATE TABLE IF NOT EXISTS `accounts` (`account_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `email_address` TEXT NOT NULL, `display_name` TEXT, `imap_host` TEXT NOT NULL, `imap_port` INTEGER NOT NULL, `smtp_host` TEXT NOT NULL, `smtp_port` INTEGER NOT NULL, `auth_type` TEXT NOT NULL, `last_sync_at` INTEGER)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_accounts_email_address` ON `accounts` (`email_address`)",
                "CREATE TABLE IF NOT EXISTS `folders` (`folder_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `account_id` INTEGER NOT NULL, `folder_name` TEXT NOT NULL, `full_path` TEXT NOT NULL, `uid_validity` INTEGER NOT NULL, `highest_mod_seq` INTEGER NOT NULL, `last_synced_uid` INTEGER NOT NULL, `total_count` INTEGER NOT NULL, `unread_count` INTEGER NOT NULL, FOREIGN KEY(`account_id`) REFERENCES `accounts`(`account_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_folders_account_id_full_path` ON `folders` (`account_id`, `full_path`)",
                "CREATE TABLE IF NOT EXISTS `messages_meta` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `account_id` INTEGER NOT NULL, `folder_id` INTEGER NOT NULL, `uid` INTEGER NOT NULL, `message_id_header` TEXT, `in_reply_to` TEXT, `thread_id` TEXT, `sender_name` TEXT, `sender_address` TEXT NOT NULL, `recipient_to` TEXT, `subject` TEXT, `snippet` TEXT, `received_at` INTEGER NOT NULL, `is_read` INTEGER NOT NULL, `is_flagged` INTEGER NOT NULL, `has_attachments` INTEGER NOT NULL, `size_bytes` INTEGER NOT NULL, FOREIGN KEY(`folder_id`) REFERENCES `folders`(`folder_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_messages_meta_folder_id_uid` ON `messages_meta` (`folder_id`, `uid`)",
                "CREATE INDEX IF NOT EXISTS `idx_meta_folder_date` ON `messages_meta` (`folder_id` ASC, `received_at` DESC)",
                "CREATE INDEX IF NOT EXISTS `idx_meta_thread` ON `messages_meta` (`thread_id` ASC, `received_at` ASC)",
                "CREATE TABLE IF NOT EXISTS `messages_body` (`meta_id` INTEGER NOT NULL, `plain_text` TEXT, `html_zstd` BLOB, `raw_headers` TEXT, PRIMARY KEY(`meta_id`), FOREIGN KEY(`meta_id`) REFERENCES `messages_meta`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE TABLE IF NOT EXISTS `attachments` (`attachment_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `meta_id` INTEGER NOT NULL, `part_id` TEXT NOT NULL, `filename` TEXT NOT NULL, `mime_type` TEXT NOT NULL, `size_bytes` INTEGER NOT NULL, `local_file_path` TEXT, FOREIGN KEY(`meta_id`) REFERENCES `messages_meta`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE INDEX IF NOT EXISTS `index_attachments_meta_id` ON `attachments` (`meta_id`)",
                "CREATE VIRTUAL TABLE IF NOT EXISTS `messages_fts` USING FTS5(`meta_id` UNINDEXED, `sender_name`, `sender_address`, `subject`, `plain_text`, tokenize=`trigram`)",
                "CREATE TABLE IF NOT EXISTS `folder_sync_state` (`folder_id` INTEGER NOT NULL, `phase1_completed` INTEGER NOT NULL, `backfill_completed` INTEGER NOT NULL, `backfill_next_max_uid` INTEGER NOT NULL, `supports_condstore` INTEGER NOT NULL, `supports_qresync` INTEGER NOT NULL, `supports_idle` INTEGER NOT NULL, `last_error` TEXT, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`folder_id`), FOREIGN KEY(`folder_id`) REFERENCES `folders`(`folder_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE TABLE IF NOT EXISTS `account_secrets` (`account_id` INTEGER NOT NULL, `encrypted_payload_b64` TEXT NOT NULL, `iv_b64` TEXT NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`account_id`), FOREIGN KEY(`account_id`) REFERENCES `accounts`(`account_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE TABLE IF NOT EXISTS `message_sync_flags` (`meta_id` INTEGER NOT NULL, `is_body_truncated` INTEGER NOT NULL, PRIMARY KEY(`meta_id`), FOREIGN KEY(`meta_id`) REFERENCES `messages_meta`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE TABLE IF NOT EXISTS `message_local_state` (`meta_id` INTEGER NOT NULL, `is_archived` INTEGER NOT NULL, `archived_at` INTEGER, PRIMARY KEY(`meta_id`), FOREIGN KEY(`meta_id`) REFERENCES `messages_meta`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE INDEX IF NOT EXISTS `index_message_local_state_is_archived_archived_at` ON `message_local_state` (`is_archived`, `archived_at`)",
                "INSERT INTO accounts (account_id, email_address, imap_host, imap_port, smtp_host, smtp_port, auth_type) VALUES (1, 'v2@example.jp', 'imap.example.jp', 993, 'smtp.example.jp', 587, 'PASSWORD')",
                "INSERT INTO folders (folder_id, account_id, folder_name, full_path, uid_validity, highest_mod_seq, last_synced_uid, total_count, unread_count) VALUES (1, 1, 'INBOX', 'INBOX', 1, 0, 1, 1, 0)",
                "INSERT INTO messages_meta (id, account_id, folder_id, uid, sender_address, subject, received_at, is_read, is_flagged, has_attachments, size_bytes) VALUES (20, 1, 1, 1, 'old2@example.jp', 'V2 Preserved', 9000, 1, 0, 0, 100)",
                "INSERT INTO message_local_state (meta_id, is_archived, archived_at) VALUES (20, 1, 9500)",
                "PRAGMA user_version = 2",
            ).forEach { sql ->
                conn.prepare(sql).use { it.step() }
            }
            conn.close()

            val migratedDb = KazeMailDatabase.buildWithDriver(
                Room.databaseBuilder<KazeMailDatabase>(
                    name = dbFile.absolutePath,
                    factory = { KazeMailDatabase_Impl() },
                ),
                BundledSQLiteDriver(),
            )
            val preserved = migratedDb.messageMetaDao().getById(20L)
            assertNotNull(preserved)
            assertEquals("V2 Preserved", preserved!!.subject)
            assertTrue(migratedDb.messageLocalStateDao().getByMetaId(20L)?.isArchived == true)

            val draftId = migratedDb.draftDao().upsert(
                com.kazemail.app.data.db.DraftEntity(
                    accountId = 1L,
                    subject = "Migrated Draft",
                    bodyText = "Body",
                    createdAt = 1000L,
                    updatedAt = 1000L,
                ),
            )
            assertTrue(draftId > 0L)
            val outboxId = migratedDb.outboxDao().insert(
                com.kazemail.app.data.db.OutboxMessageEntity(
                    accountId = 1L,
                    fromEmail = "v2@example.jp",
                    toAddressesJson = """["to@example.jp"]""",
                    subject = "Migrated Outbox",
                    bodyText = "Hello",
                    createdAt = 1000L,
                    updatedAt = 1000L,
                ),
            )
            assertTrue(outboxId > 0L)
            migratedDb.close()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun quickFiltersBulkReadAttachmentCacheAndLocalAccountDeleteWithFtsCleanup() = runTest {
        val accId = db.accountDao().insert(
            AccountEntity(
                emailAddress = "filter@kazemail.app",
                imapHost = "imap.kazemail.app",
                smtpHost = "smtp.kazemail.app",
                authType = "PASSWORD",
            ),
        )
        val inboxId = db.folderDao().insert(
            FolderEntity(
                accountId = accId,
                folderName = "INBOX",
                fullPath = "INBOX",
                uidValidity = 1L,
            ),
        )
        val u1 = db.messageMetaDao().insertIgnore(
            MessageMetaEntity(
                accountId = accId,
                folderId = inboxId,
                uid = 10L,
                threadId = "t-unread",
                senderAddress = "a@example.jp",
                subject = "未読スターなし",
                snippet = "test body one",
                receivedAt = 100L,
                isRead = false,
                isFlagged = false,
                hasAttachments = false,
            ),
        )
        val u2 = db.messageMetaDao().insertIgnore(
            MessageMetaEntity(
                accountId = accId,
                folderId = inboxId,
                uid = 11L,
                threadId = "t-star-att",
                senderAddress = "b@example.jp",
                subject = "既読スター添付",
                snippet = "invoice attached",
                receivedAt = 200L,
                isRead = true,
                isFlagged = true,
                hasAttachments = true,
            ),
        )
        db.messageFtsDao().insert(
            com.kazemail.app.data.db.MessageFtsEntity(
                metaId = u1,
                senderName = null,
                senderAddress = "a@example.jp",
                subject = "未読スターなし",
                plainText = "unique_token_alpha",
            ),
        )
        db.messageFtsDao().insert(
            com.kazemail.app.data.db.MessageFtsEntity(
                metaId = u2,
                senderName = null,
                senderAddress = "b@example.jp",
                subject = "既読スター添付",
                plainText = "unique_token_beta",
            ),
        )

        val cacheDir = createTempDirectory("kazemail-att-cache").toFile()
        try {
            val cachedFile = File(cacheDir, "sample.pdf").apply { writeText("%PDF-1.4") }
            db.attachmentDao().insertAll(
                listOf(
                    com.kazemail.app.data.db.AttachmentEntity(
                        metaId = u2,
                        partId = "2",
                        filename = "sample.pdf",
                        mimeType = "application/pdf",
                        sizeBytes = cachedFile.length(),
                        localFilePath = cachedFile.absolutePath,
                    ),
                ),
            )
            assertTrue(repo.calculateAttachmentCacheBytes(cacheDir) > 0L)

            val unreadThreads = db.messageMetaDao().pagingSourceThreadsFiltered(
                folderId = inboxId,
                onlyUnread = 1,
                onlyFlagged = 0,
                onlyAttachments = 0,
            ).load(PagingSource.LoadParams.Refresh(null, 20, false)) as PagingSource.LoadResult.Page
            assertEquals(1, unreadThreads.data.size)
            assertEquals(u1, unreadThreads.data.single().latestMetaId)

            val starredFlat = db.messageMetaDao().pagingSourceFlatFiltered(
                folderId = inboxId,
                onlyUnread = 0,
                onlyFlagged = 1,
                onlyAttachments = 0,
            ).load(PagingSource.LoadParams.Refresh(null, 20, false)) as PagingSource.LoadResult.Page
            assertEquals(1, starredFlat.data.size)
            assertEquals(u2, starredFlat.data.single().id)

            val attFlat = db.messageMetaDao().pagingSourceFlatFiltered(
                folderId = inboxId,
                onlyUnread = 0,
                onlyFlagged = 0,
                onlyAttachments = 1,
            ).load(PagingSource.LoadParams.Refresh(null, 20, false)) as PagingSource.LoadResult.Page
            assertEquals(1, attFlat.data.size)
            assertEquals(u2, attFlat.data.single().id)

            repo.setBulkReadState(listOf(u1), isRead = true)
            assertTrue(db.messageMetaDao().getById(u1)!!.isRead)

            val cleared = repo.clearAttachmentCache(cacheDir)
            assertTrue(cleared > 0L)
            assertEquals(0L, repo.calculateAttachmentCacheBytes(cacheDir))
            assertEquals(null, db.attachmentDao().getByMetaId(u2).single().localFilePath)

            // Deleting local account clears FTS + cascades account rows
            repo.deleteLocalAccount(accId)
            assertEquals(null, db.accountDao().getById(accId))
            assertTrue(repo.searchLocal("unique_token_alpha", accountId = null).isEmpty())
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun hundredItemPaginationAndCountQueriesPaginateCleanly() = runTest {
        val accId = db.accountDao().insert(
            AccountEntity(
                emailAddress = "pager@example.com",
                displayName = "Pager",
                imapHost = "imap.example.com",
                smtpHost = "smtp.example.com",
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

        val totalInserted = 105
        val insertedIds = mutableListOf<Long>()
        for (idx in 1..totalInserted) {
            val id = db.messageMetaDao().insertIgnore(
                MessageMetaEntity(
                    accountId = accId,
                    folderId = folderId,
                    uid = idx.toLong(),
                    threadId = "thr-$idx",
                    senderAddress = "sender$idx@example.com",
                    subject = "Page item $idx",
                    snippet = "Preview $idx",
                    receivedAt = idx * 1_000L,
                    isRead = idx % 2 == 0,
                    isFlagged = idx % 5 == 0,
                    hasAttachments = false,
                ),
            )
            insertedIds += id
        }

        assertEquals(105, repo.observeFlatCount(folderId, InboxQuickFilter.ALL).first())
        assertEquals(105, repo.observeThreadCount(folderId, InboxQuickFilter.ALL).first())

        val flatPage0 = repo.observeFlatPage(folderId, InboxQuickFilter.ALL, pageIndex = 0, pageSize = 100).first()
        val flatPage1 = repo.observeFlatPage(folderId, InboxQuickFilter.ALL, pageIndex = 1, pageSize = 100).first()
        assertEquals(100, flatPage0.size)
        assertEquals(5, flatPage1.size)
        // Ordered newest first (receivedAt DESC): uid 105 down to uid 6 on page 0, uid 5 down to uid 1 on page 1
        assertEquals(105L, flatPage0.first().uid)
        assertEquals(6L, flatPage0.last().uid)
        assertEquals(5L, flatPage1.first().uid)
        assertEquals(1L, flatPage1.last().uid)

        val threadPage0 = repo.observeThreadPage(folderId, InboxQuickFilter.ALL, pageIndex = 0, pageSize = 100).first()
        val threadPage1 = repo.observeThreadPage(folderId, InboxQuickFilter.ALL, pageIndex = 1, pageSize = 100).first()
        assertEquals(100, threadPage0.size)
        assertEquals(5, threadPage1.size)
        assertEquals("thr-105", threadPage0.first().threadKey)
        assertEquals("thr-1", threadPage1.last().threadKey)

        // Archive all 105 locally and verify observeArchivedPage slices 100 + 5
        repo.archiveMessagesLocally(insertedIds)
        assertEquals(0, repo.observeFlatCount(folderId, InboxQuickFilter.ALL).first())
        assertEquals(105, db.messageLocalStateDao().observeLocallyArchivedCount(accId).first())

        val archPage0 = repo.observeArchivedPage(accId, pageIndex = 0, pageSize = 100).first()
        val archPage1 = repo.observeArchivedPage(accId, pageIndex = 1, pageSize = 100).first()
        assertEquals(100, archPage0.size)
        assertEquals(5, archPage1.size)
        assertEquals(105L, archPage0.first().uid)
        assertEquals(1L, archPage1.last().uid)
    }

    @Test
    fun migration3To4AddsLocalFilterRulesAndCategoriesCleanly() = runTest {
        val tempDir = createTempDirectory("kazemail-mig34-test").toFile()
        val dbFile = File(tempDir, "mig_v3_v4.db")
        try {
            val driver = BundledSQLiteDriver()
            val conn = driver.open(dbFile.absolutePath)
            listOf(
                "CREATE TABLE IF NOT EXISTS `accounts` (`account_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `email_address` TEXT NOT NULL, `display_name` TEXT, `imap_host` TEXT NOT NULL, `imap_port` INTEGER NOT NULL, `smtp_host` TEXT NOT NULL, `smtp_port` INTEGER NOT NULL, `auth_type` TEXT NOT NULL, `last_sync_at` INTEGER)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_accounts_email_address` ON `accounts` (`email_address`)",
                "CREATE TABLE IF NOT EXISTS `folders` (`folder_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `account_id` INTEGER NOT NULL, `folder_name` TEXT NOT NULL, `full_path` TEXT NOT NULL, `uid_validity` INTEGER NOT NULL, `highest_mod_seq` INTEGER NOT NULL, `last_synced_uid` INTEGER NOT NULL, `total_count` INTEGER NOT NULL, `unread_count` INTEGER NOT NULL, FOREIGN KEY(`account_id`) REFERENCES `accounts`(`account_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_folders_account_id_full_path` ON `folders` (`account_id`, `full_path`)",
                "CREATE TABLE IF NOT EXISTS `messages_meta` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `account_id` INTEGER NOT NULL, `folder_id` INTEGER NOT NULL, `uid` INTEGER NOT NULL, `message_id_header` TEXT, `in_reply_to` TEXT, `thread_id` TEXT, `sender_name` TEXT, `sender_address` TEXT NOT NULL, `recipient_to` TEXT, `subject` TEXT, `snippet` TEXT, `received_at` INTEGER NOT NULL, `is_read` INTEGER NOT NULL, `is_flagged` INTEGER NOT NULL, `has_attachments` INTEGER NOT NULL, `size_bytes` INTEGER NOT NULL, FOREIGN KEY(`folder_id`) REFERENCES `folders`(`folder_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_messages_meta_folder_id_uid` ON `messages_meta` (`folder_id`, `uid`)",
                "CREATE INDEX IF NOT EXISTS `idx_meta_folder_date` ON `messages_meta` (`folder_id` ASC, `received_at` DESC)",
                "CREATE INDEX IF NOT EXISTS `idx_meta_thread` ON `messages_meta` (`thread_id` ASC, `received_at` ASC)",
                "CREATE TABLE IF NOT EXISTS `messages_body` (`meta_id` INTEGER NOT NULL, `plain_text` TEXT, `html_zstd` BLOB, `raw_headers` TEXT, PRIMARY KEY(`meta_id`), FOREIGN KEY(`meta_id`) REFERENCES `messages_meta`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE TABLE IF NOT EXISTS `attachments` (`attachment_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `meta_id` INTEGER NOT NULL, `part_id` TEXT NOT NULL, `filename` TEXT NOT NULL, `mime_type` TEXT NOT NULL, `size_bytes` INTEGER NOT NULL, `local_file_path` TEXT, FOREIGN KEY(`meta_id`) REFERENCES `messages_meta`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE INDEX IF NOT EXISTS `index_attachments_meta_id` ON `attachments` (`meta_id`)",
                "CREATE VIRTUAL TABLE IF NOT EXISTS `messages_fts` USING FTS5(`meta_id` UNINDEXED, `sender_name`, `sender_address`, `subject`, `plain_text`, tokenize=`trigram`)",
                "CREATE TABLE IF NOT EXISTS `folder_sync_state` (`folder_id` INTEGER NOT NULL, `phase1_completed` INTEGER NOT NULL, `backfill_completed` INTEGER NOT NULL, `backfill_next_max_uid` INTEGER NOT NULL, `supports_condstore` INTEGER NOT NULL, `supports_qresync` INTEGER NOT NULL, `supports_idle` INTEGER NOT NULL, `last_error` TEXT, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`folder_id`), FOREIGN KEY(`folder_id`) REFERENCES `folders`(`folder_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE TABLE IF NOT EXISTS `account_secrets` (`account_id` INTEGER NOT NULL, `encrypted_payload_b64` TEXT NOT NULL, `iv_b64` TEXT NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`account_id`), FOREIGN KEY(`account_id`) REFERENCES `accounts`(`account_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE TABLE IF NOT EXISTS `message_sync_flags` (`meta_id` INTEGER NOT NULL, `is_body_truncated` INTEGER NOT NULL, PRIMARY KEY(`meta_id`), FOREIGN KEY(`meta_id`) REFERENCES `messages_meta`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE TABLE IF NOT EXISTS `message_local_state` (`meta_id` INTEGER NOT NULL, `is_archived` INTEGER NOT NULL, `archived_at` INTEGER, PRIMARY KEY(`meta_id`), FOREIGN KEY(`meta_id`) REFERENCES `messages_meta`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE INDEX IF NOT EXISTS `index_message_local_state_is_archived_archived_at` ON `message_local_state` (`is_archived`, `archived_at`)",
                "CREATE TABLE IF NOT EXISTS `drafts` (`draft_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `account_id` INTEGER NOT NULL, `reply_to_meta_id` INTEGER, `mode` TEXT NOT NULL, `to_addresses` TEXT NOT NULL, `cc_addresses` TEXT NOT NULL, `bcc_addresses` TEXT NOT NULL, `subject` TEXT NOT NULL, `body_text` TEXT NOT NULL, `in_reply_to` TEXT, `references_json` TEXT, `attachments_json` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, FOREIGN KEY(`account_id`) REFERENCES `accounts`(`account_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE INDEX IF NOT EXISTS `index_drafts_account_id_updated_at` ON `drafts` (`account_id`, `updated_at`)",
                "CREATE TABLE IF NOT EXISTS `outbox` (`outbox_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `account_id` INTEGER NOT NULL, `draft_id` INTEGER, `from_email` TEXT NOT NULL, `from_name` TEXT, `to_addresses` TEXT NOT NULL, `cc_addresses` TEXT NOT NULL, `bcc_addresses` TEXT NOT NULL, `subject` TEXT NOT NULL, `body_text` TEXT NOT NULL, `in_reply_to` TEXT, `references_json` TEXT, `message_id_header` TEXT, `attachments_json` TEXT NOT NULL, `status` TEXT NOT NULL, `attempt_count` INTEGER NOT NULL, `last_error` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `sent_at` INTEGER, FOREIGN KEY(`account_id`) REFERENCES `accounts`(`account_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE INDEX IF NOT EXISTS `index_outbox_account_id_status_updated_at` ON `outbox` (`account_id`, `status`, `updated_at`)",
                "INSERT INTO accounts (account_id, email_address, imap_host, imap_port, smtp_host, smtp_port, auth_type) VALUES (1, 'v3@example.jp', 'imap.example.jp', 993, 'smtp.example.jp', 465, 'OAUTH2')",
                "INSERT INTO folders (folder_id, account_id, folder_name, full_path, uid_validity, highest_mod_seq, last_synced_uid, total_count, unread_count) VALUES (1, 1, 'INBOX', 'INBOX', 1, 0, 1, 1, 1)",
                "INSERT INTO messages_meta (id, account_id, folder_id, uid, sender_address, subject, received_at, is_read, is_flagged, has_attachments, size_bytes) VALUES (30, 1, 1, 1, 'v3sender@example.jp', 'V3 Existing Mail', 12000, 0, 0, 0, 150)",
                "PRAGMA user_version = 3",
            ).forEach { sql ->
                conn.prepare(sql).use { it.step() }
            }
            conn.close()

            val migratedDb = KazeMailDatabase.buildWithDriver(
                Room.databaseBuilder<KazeMailDatabase>(
                    name = dbFile.absolutePath,
                    factory = { KazeMailDatabase_Impl() },
                ),
                BundledSQLiteDriver(),
            )
            val msg = migratedDb.messageMetaDao().getById(30L)
            assertNotNull(msg)
            assertEquals("V3 Existing Mail", msg!!.subject)

            val ruleId = migratedDb.localFilterRuleDao().upsert(
                LocalFilterRuleEntity(
                    accountId = 1L,
                    name = "Mig Rule",
                    conditionsJson = LocalFilterEngine.encodeConditions(
                        listOf(LocalFilterCondition(FilterField.SUBJECT, FilterOperator.CONTAINS, "V3")),
                    ),
                    targetLocalFolder = "引き継ぎ済み",
                    hideFromFolder = false,
                ),
            )
            assertTrue(ruleId > 0L)
            assertEquals(1, migratedDb.localFilterRuleDao().getAllByAccount(1L).size)
            migratedDb.close()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun clientSideFilterRulesSortHideArchiveMarkReadAndPreserveAcrossUndo() = runTest {
        val accId = db.accountDao().insert(
            AccountEntity(
                emailAddress = "rules@kazemail.app",
                imapHost = "imap.kazemail.app",
                smtpHost = "smtp.kazemail.app",
                authType = "PASSWORD",
            ),
        )
        val inboxId = db.folderDao().insert(
            FolderEntity(
                accountId = accId,
                folderName = "INBOX",
                fullPath = "INBOX",
                uidValidity = 1L,
            ),
        )

        val newsId = db.messageMetaDao().insertIgnore(
            MessageMetaEntity(
                accountId = accId,
                folderId = inboxId,
                uid = 1L,
                threadId = "thr-news",
                senderName = "Shop",
                senderAddress = "newsletter@shop.jp",
                subject = "週次セールのお知らせ",
                receivedAt = 1000L,
                isRead = false,
                isFlagged = false,
            ),
        )
        db.messageBodyDao().upsert(
            MessageBodyEntity(
                metaId = newsId,
                plainText = "今週の特価情報",
                rawHeaders = "List-Unsubscribe: <mailto:unsub@shop.jp>\r\nPrecedence: bulk",
            ),
        )

        val invoiceId = db.messageMetaDao().insertIgnore(
            MessageMetaEntity(
                accountId = accId,
                folderId = inboxId,
                uid = 2L,
                threadId = "thr-inv",
                senderName = "経理",
                senderAddress = "billing@vendor.jp",
                subject = "11月分 ご請求書",
                receivedAt = 2000L,
                isRead = false,
                isFlagged = false,
                hasAttachments = true,
            ),
        )
        val promoId = db.messageMetaDao().insertIgnore(
            MessageMetaEntity(
                accountId = accId,
                folderId = inboxId,
                uid = 3L,
                threadId = "thr-promo",
                senderAddress = "ad@spammy.jp",
                subject = "PR キャンペーン",
                receivedAt = 3000L,
                isRead = false,
            ),
        )
        val normalId = db.messageMetaDao().insertIgnore(
            MessageMetaEntity(
                accountId = accId,
                folderId = inboxId,
                uid = 4L,
                threadId = "thr-norm",
                senderAddress = "friend@example.jp",
                subject = "今週末の予定",
                receivedAt = 4000L,
                isRead = false,
            ),
        )

        db.localFilterRuleDao().upsert(
            LocalFilterRuleEntity(
                accountId = accId,
                name = "メルマガ仕分け",
                priority = 1,
                matchMode = LocalFilterRuleEntity.MATCH_MODE_ANY,
                conditionsJson = LocalFilterEngine.encodeConditions(
                    listOf(LocalFilterCondition(FilterField.RAW_HEADERS, FilterOperator.CONTAINS, "List-Unsubscribe:")),
                ),
                targetLocalFolder = "メルマガ",
                hideFromFolder = true,
                markRead = true,
                suppressNotification = true,
            ),
        )
        db.localFilterRuleDao().upsert(
            LocalFilterRuleEntity(
                accountId = accId,
                name = "請求書タグ",
                priority = 2,
                matchMode = LocalFilterRuleEntity.MATCH_MODE_ALL,
                conditionsJson = LocalFilterEngine.encodeConditions(
                    listOf(
                        LocalFilterCondition(FilterField.SUBJECT, FilterOperator.CONTAINS, "請求書"),
                        LocalFilterCondition(FilterField.HAS_ATTACHMENTS, FilterOperator.IS_TRUE),
                    ),
                ),
                targetLocalFolder = "請求・注文",
                hideFromFolder = false,
                markFlagged = true,
            ),
        )
        db.localFilterRuleDao().upsert(
            LocalFilterRuleEntity(
                accountId = accId,
                name = "広告ローカルアーカイブ",
                priority = 3,
                matchMode = LocalFilterRuleEntity.MATCH_MODE_ALL,
                conditionsJson = LocalFilterEngine.encodeConditions(
                    listOf(LocalFilterCondition(FilterField.SUBJECT, FilterOperator.STARTS_WITH, "PR ")),
                ),
                archiveLocally = true,
                markRead = true,
            ),
        )

        val matchedCount = repo.applyAccountFilterRulesNow(accId)
        assertEquals(3, matchedCount)

        // INBOX retains non-hidden invoice + normal message, hides newsletter & archived promo
        val visibleInbox = repo.observeFlatPage(inboxId, InboxQuickFilter.ALL, 0, 100).first()
        assertEquals(listOf(normalId, invoiceId), visibleInbox.map { it.id })

        // Read/star updates persisted strictly locally
        assertTrue(db.messageMetaDao().getById(newsId)!!.isRead)
        assertTrue(db.messageMetaDao().getById(invoiceId)!!.isFlagged)
        assertFalse(db.messageMetaDao().getById(invoiceId)!!.isRead)
        assertTrue(db.messageMetaDao().getById(promoId)!!.isRead)

        // Virtual category summaries & paginated queries reflect local folders
        val categories = db.messageLocalStateDao().observeLocalCategorySummaries(accId).first()
        assertEquals(listOf("メルマガ", "請求・注文"), categories.map { it.categoryName })
        assertEquals(1, repo.observeLocalCategoryFlatCount(accId, "メルマガ", InboxQuickFilter.ALL).first())
        assertEquals(1, repo.observeLocalCategoryThreadCount(accId, "請求・注文", InboxQuickFilter.ALL).first())
        val newsCatItems = repo.observeLocalCategoryFlatPage(accId, "メルマガ", InboxQuickFilter.ALL, 0, 100).first()
        assertEquals(listOf(newsId), newsCatItems.map { it.id })

        // Local Archive -> Undo preserves localCategory assignment
        repo.archiveMessagesLocally(listOf(invoiceId))
        assertTrue(db.messageLocalStateDao().getByMetaId(invoiceId)!!.isArchived)
        repo.unarchiveMessagesLocally(listOf(invoiceId))
        val restoredState = db.messageLocalStateDao().getByMetaId(invoiceId)!!
        assertFalse(restoredState.isArchived)
        assertEquals("請求・注文", restoredState.localCategory)
    }

    @Test
    fun healStoredEncodedHeadersDecodesLegacyRawIso2022JpRows() = runTest {
        val accId = db.accountDao().insert(
            AccountEntity(
                emailAddress = "heal@kazemail.app",
                imapHost = "imap.kazemail.app",
                smtpHost = "smtp.kazemail.app",
                authType = "PASSWORD",
            ),
        )
        val inboxId = db.folderDao().insert(
            FolderEntity(
                accountId = accId,
                folderName = "INBOX",
                fullPath = "INBOX",
                uidValidity = 1L,
            ),
        )
        val iso2022Jp = Charset.forName("ISO-2022-JP")
        val subjB64 = Base64.getEncoder().encodeToString("こんにちは会議".toByteArray(iso2022Jp))
        val nameB64 = Base64.getEncoder().encodeToString("田中太郎".toByteArray(iso2022Jp))

        val id = db.messageMetaDao().insertIgnore(
            MessageMetaEntity(
                accountId = accId,
                folderId = inboxId,
                uid = 1L,
                senderName = "=?ISO-2022-JP?B?$nameB64?=",
                senderAddress = "tanaka@example.jp",
                subject = "Re:=?ISO-2022-JP?B?$subjB64?=(確認)",
                receivedAt = 10_000L,
            ),
        )
        db.messageFtsDao().insert(
            MessageFtsEntity(
                metaId = id,
                senderName = "=?ISO-2022-JP?B?$nameB64?=",
                senderAddress = "tanaka@example.jp",
                subject = "Re:=?ISO-2022-JP?B?$subjB64?=(確認)",
                plainText = "本文",
            ),
        )

        val healed = repo.healStoredEncodedHeaders()
        assertEquals(1, healed)

        val updated = db.messageMetaDao().getById(id)!!
        assertEquals("Re:こんにちは会議(確認)", updated.subject)
        assertEquals("田中太郎", updated.senderName)

        // FTS5 index was updated with decoded UTF-8 Japanese
        val ftsMatches = repo.searchLocal("こんにちは", accId)
        assertEquals(listOf(id), ftsMatches.map { it.id })
    }

    @Test
    fun migration4To5AddsCoveringIndexesAndPreservesAccountCounts() = runTest {
        val tempDir = createTempDirectory("kazemail-mig45-test").toFile()
        val dbFile = File(tempDir, "mig_v4_v5.db")
        try {
            val driver = BundledSQLiteDriver()
            val conn = driver.open(dbFile.absolutePath)
            listOf(
                "CREATE TABLE IF NOT EXISTS `accounts` (`account_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `email_address` TEXT NOT NULL, `display_name` TEXT, `imap_host` TEXT NOT NULL, `imap_port` INTEGER NOT NULL, `smtp_host` TEXT NOT NULL, `smtp_port` INTEGER NOT NULL, `auth_type` TEXT NOT NULL, `last_sync_at` INTEGER)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_accounts_email_address` ON `accounts` (`email_address`)",
                "CREATE TABLE IF NOT EXISTS `folders` (`folder_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `account_id` INTEGER NOT NULL, `folder_name` TEXT NOT NULL, `full_path` TEXT NOT NULL, `uid_validity` INTEGER NOT NULL, `highest_mod_seq` INTEGER NOT NULL, `last_synced_uid` INTEGER NOT NULL, `total_count` INTEGER NOT NULL, `unread_count` INTEGER NOT NULL, FOREIGN KEY(`account_id`) REFERENCES `accounts`(`account_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_folders_account_id_full_path` ON `folders` (`account_id`, `full_path`)",
                "CREATE TABLE IF NOT EXISTS `messages_meta` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `account_id` INTEGER NOT NULL, `folder_id` INTEGER NOT NULL, `uid` INTEGER NOT NULL, `message_id_header` TEXT, `in_reply_to` TEXT, `thread_id` TEXT, `sender_name` TEXT, `sender_address` TEXT NOT NULL, `recipient_to` TEXT, `subject` TEXT, `snippet` TEXT, `received_at` INTEGER NOT NULL, `is_read` INTEGER NOT NULL, `is_flagged` INTEGER NOT NULL, `has_attachments` INTEGER NOT NULL, `size_bytes` INTEGER NOT NULL, FOREIGN KEY(`folder_id`) REFERENCES `folders`(`folder_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_messages_meta_folder_id_uid` ON `messages_meta` (`folder_id`, `uid`)",
                "CREATE INDEX IF NOT EXISTS `idx_meta_folder_date` ON `messages_meta` (`folder_id` ASC, `received_at` DESC)",
                "CREATE INDEX IF NOT EXISTS `idx_meta_thread` ON `messages_meta` (`thread_id` ASC, `received_at` ASC)",
                "CREATE TABLE IF NOT EXISTS `messages_body` (`meta_id` INTEGER NOT NULL, `plain_text` TEXT, `html_zstd` BLOB, `raw_headers` TEXT, PRIMARY KEY(`meta_id`), FOREIGN KEY(`meta_id`) REFERENCES `messages_meta`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE TABLE IF NOT EXISTS `attachments` (`attachment_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `meta_id` INTEGER NOT NULL, `part_id` TEXT NOT NULL, `filename` TEXT NOT NULL, `mime_type` TEXT NOT NULL, `size_bytes` INTEGER NOT NULL, `local_file_path` TEXT, FOREIGN KEY(`meta_id`) REFERENCES `messages_meta`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE INDEX IF NOT EXISTS `index_attachments_meta_id` ON `attachments` (`meta_id`)",
                "CREATE VIRTUAL TABLE IF NOT EXISTS `messages_fts` USING FTS5(`meta_id` UNINDEXED, `sender_name`, `sender_address`, `subject`, `plain_text`, tokenize=`trigram`)",
                "CREATE TABLE IF NOT EXISTS `folder_sync_state` (`folder_id` INTEGER NOT NULL, `phase1_completed` INTEGER NOT NULL, `backfill_completed` INTEGER NOT NULL, `backfill_next_max_uid` INTEGER NOT NULL, `supports_condstore` INTEGER NOT NULL, `supports_qresync` INTEGER NOT NULL, `supports_idle` INTEGER NOT NULL, `last_error` TEXT, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`folder_id`), FOREIGN KEY(`folder_id`) REFERENCES `folders`(`folder_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE TABLE IF NOT EXISTS `account_secrets` (`account_id` INTEGER NOT NULL, `encrypted_payload_b64` TEXT NOT NULL, `iv_b64` TEXT NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`account_id`), FOREIGN KEY(`account_id`) REFERENCES `accounts`(`account_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE TABLE IF NOT EXISTS `message_sync_flags` (`meta_id` INTEGER NOT NULL, `is_body_truncated` INTEGER NOT NULL, PRIMARY KEY(`meta_id`), FOREIGN KEY(`meta_id`) REFERENCES `messages_meta`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE TABLE IF NOT EXISTS `message_local_state` (`meta_id` INTEGER NOT NULL, `is_archived` INTEGER NOT NULL, `archived_at` INTEGER, `local_category` TEXT, `hidden_from_folder` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`meta_id`), FOREIGN KEY(`meta_id`) REFERENCES `messages_meta`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE INDEX IF NOT EXISTS `index_message_local_state_is_archived_archived_at` ON `message_local_state` (`is_archived`, `archived_at`)",
                "CREATE INDEX IF NOT EXISTS `index_message_local_state_local_category` ON `message_local_state` (`local_category`)",
                "CREATE TABLE IF NOT EXISTS `drafts` (`draft_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `account_id` INTEGER NOT NULL, `reply_to_meta_id` INTEGER, `mode` TEXT NOT NULL, `to_addresses` TEXT NOT NULL, `cc_addresses` TEXT NOT NULL, `bcc_addresses` TEXT NOT NULL, `subject` TEXT NOT NULL, `body_text` TEXT NOT NULL, `in_reply_to` TEXT, `references_json` TEXT, `attachments_json` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, FOREIGN KEY(`account_id`) REFERENCES `accounts`(`account_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE INDEX IF NOT EXISTS `index_drafts_account_id_updated_at` ON `drafts` (`account_id`, `updated_at`)",
                "CREATE TABLE IF NOT EXISTS `outbox` (`outbox_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `account_id` INTEGER NOT NULL, `draft_id` INTEGER, `from_email` TEXT NOT NULL, `from_name` TEXT, `to_addresses` TEXT NOT NULL, `cc_addresses` TEXT NOT NULL, `bcc_addresses` TEXT NOT NULL, `subject` TEXT NOT NULL, `body_text` TEXT NOT NULL, `in_reply_to` TEXT, `references_json` TEXT, `message_id_header` TEXT, `attachments_json` TEXT NOT NULL, `status` TEXT NOT NULL, `attempt_count` INTEGER NOT NULL, `last_error` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `sent_at` INTEGER, FOREIGN KEY(`account_id`) REFERENCES `accounts`(`account_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE INDEX IF NOT EXISTS `index_outbox_account_id_status_updated_at` ON `outbox` (`account_id`, `status`, `updated_at`)",
                "CREATE TABLE IF NOT EXISTS `local_filter_rules` (`rule_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `account_id` INTEGER NOT NULL, `name` TEXT NOT NULL, `is_enabled` INTEGER NOT NULL, `priority` INTEGER NOT NULL, `match_mode` TEXT NOT NULL, `conditions_json` TEXT NOT NULL, `target_local_folder` TEXT, `hide_from_folder` INTEGER NOT NULL, `archive_locally` INTEGER NOT NULL, `mark_read` INTEGER NOT NULL, `mark_flagged` INTEGER NOT NULL, `suppress_notification` INTEGER NOT NULL, `stop_processing` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, FOREIGN KEY(`account_id`) REFERENCES `accounts`(`account_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE INDEX IF NOT EXISTS `index_local_filter_rules_account_id_priority` ON `local_filter_rules` (`account_id`, `priority`)",
                "INSERT INTO accounts (account_id, email_address, imap_host, imap_port, smtp_host, smtp_port, auth_type) VALUES (1, 'v4@example.jp', 'imap.example.jp', 993, 'smtp.example.jp', 465, 'OAUTH2')",
                "INSERT INTO folders (folder_id, account_id, folder_name, full_path, uid_validity, highest_mod_seq, last_synced_uid, total_count, unread_count) VALUES (1, 1, 'INBOX', 'INBOX', 1, 0, 2, 2, 1)",
                "INSERT INTO messages_meta (id, account_id, folder_id, uid, thread_id, sender_address, subject, received_at, is_read, is_flagged, has_attachments, size_bytes) VALUES (40, 1, 1, 1, 't4', 'v4sender@example.jp', 'V4 Mail 1', 14000, 0, 0, 0, 100)",
                "INSERT INTO messages_meta (id, account_id, folder_id, uid, thread_id, sender_address, subject, received_at, is_read, is_flagged, has_attachments, size_bytes) VALUES (41, 1, 1, 2, 't4', 'v4sender@example.jp', 'V4 Mail 2', 15000, 1, 0, 0, 110)",
                "PRAGMA user_version = 4",
            ).forEach { sql ->
                conn.prepare(sql).use { it.step() }
            }
            conn.close()

            val migratedDb = KazeMailDatabase.buildWithDriver(
                Room.databaseBuilder<KazeMailDatabase>(
                    name = dbFile.absolutePath,
                    factory = { KazeMailDatabase_Impl() },
                ),
                BundledSQLiteDriver(),
            )
            val counts = migratedDb.messageMetaDao().observeMessageCountsByAccount().first()
            assertEquals(1, counts.size)
            assertEquals(1L, counts[0].accountId)
            assertEquals(2, counts[0].count)

            val threadMsgs = migratedDb.messageMetaDao().getThreadMessages(1L, "t4")
            assertEquals(listOf(40L, 41L), threadMsgs.map { it.id })
            migratedDb.close()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun migration5To6PreservesV5DataAndAddsAiTables() = runTest {
        val tempDir = createTempDirectory("kazemail-mig56-test").toFile()
        val dbFile = File(tempDir, "mig_v5_v6.db")
        try {
            val driver = BundledSQLiteDriver()
            val conn = driver.open(dbFile.absolutePath)
            listOf(
                "CREATE TABLE IF NOT EXISTS `accounts` (`account_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `email_address` TEXT NOT NULL, `display_name` TEXT, `imap_host` TEXT NOT NULL, `imap_port` INTEGER NOT NULL, `smtp_host` TEXT NOT NULL, `smtp_port` INTEGER NOT NULL, `auth_type` TEXT NOT NULL, `last_sync_at` INTEGER)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_accounts_email_address` ON `accounts` (`email_address`)",
                "CREATE TABLE IF NOT EXISTS `folders` (`folder_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `account_id` INTEGER NOT NULL, `folder_name` TEXT NOT NULL, `full_path` TEXT NOT NULL, `uid_validity` INTEGER NOT NULL, `highest_mod_seq` INTEGER NOT NULL, `last_synced_uid` INTEGER NOT NULL, `total_count` INTEGER NOT NULL, `unread_count` INTEGER NOT NULL, FOREIGN KEY(`account_id`) REFERENCES `accounts`(`account_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_folders_account_id_full_path` ON `folders` (`account_id`, `full_path`)",
                "CREATE TABLE IF NOT EXISTS `messages_meta` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `account_id` INTEGER NOT NULL, `folder_id` INTEGER NOT NULL, `uid` INTEGER NOT NULL, `message_id_header` TEXT, `in_reply_to` TEXT, `thread_id` TEXT, `sender_name` TEXT, `sender_address` TEXT NOT NULL, `recipient_to` TEXT, `subject` TEXT, `snippet` TEXT, `received_at` INTEGER NOT NULL, `is_read` INTEGER NOT NULL, `is_flagged` INTEGER NOT NULL, `has_attachments` INTEGER NOT NULL, `size_bytes` INTEGER NOT NULL, FOREIGN KEY(`folder_id`) REFERENCES `folders`(`folder_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_messages_meta_folder_id_uid` ON `messages_meta` (`folder_id`, `uid`)",
                "CREATE INDEX IF NOT EXISTS `idx_meta_folder_date` ON `messages_meta` (`folder_id` ASC, `received_at` DESC)",
                "CREATE INDEX IF NOT EXISTS `idx_meta_thread` ON `messages_meta` (`thread_id` ASC, `received_at` ASC)",
                "CREATE INDEX IF NOT EXISTS `idx_meta_account` ON `messages_meta` (`account_id`)",
                "CREATE INDEX IF NOT EXISTS `idx_meta_folder_thread_date` ON `messages_meta` (`folder_id` ASC, `thread_id` ASC, `received_at` DESC)",
                "CREATE INDEX IF NOT EXISTS `idx_meta_account_thread_date` ON `messages_meta` (`account_id` ASC, `thread_id` ASC, `received_at` ASC)",
                "CREATE INDEX IF NOT EXISTS idx_meta_unread ON messages_meta(folder_id, is_read) WHERE is_read = 0",
                "CREATE TABLE IF NOT EXISTS `messages_body` (`meta_id` INTEGER NOT NULL, `plain_text` TEXT, `html_zstd` BLOB, `raw_headers` TEXT, PRIMARY KEY(`meta_id`), FOREIGN KEY(`meta_id`) REFERENCES `messages_meta`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE TABLE IF NOT EXISTS `attachments` (`attachment_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `meta_id` INTEGER NOT NULL, `part_id` TEXT NOT NULL, `filename` TEXT NOT NULL, `mime_type` TEXT NOT NULL, `size_bytes` INTEGER NOT NULL, `local_file_path` TEXT, FOREIGN KEY(`meta_id`) REFERENCES `messages_meta`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE INDEX IF NOT EXISTS `index_attachments_meta_id` ON `attachments` (`meta_id`)",
                "CREATE VIRTUAL TABLE IF NOT EXISTS `messages_fts` USING FTS5(`meta_id` UNINDEXED, `sender_name`, `sender_address`, `subject`, `plain_text`, tokenize=`trigram`)",
                "CREATE TABLE IF NOT EXISTS `folder_sync_state` (`folder_id` INTEGER NOT NULL, `phase1_completed` INTEGER NOT NULL, `backfill_completed` INTEGER NOT NULL, `backfill_next_max_uid` INTEGER NOT NULL, `supports_condstore` INTEGER NOT NULL, `supports_qresync` INTEGER NOT NULL, `supports_idle` INTEGER NOT NULL, `last_error` TEXT, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`folder_id`), FOREIGN KEY(`folder_id`) REFERENCES `folders`(`folder_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE TABLE IF NOT EXISTS `account_secrets` (`account_id` INTEGER NOT NULL, `encrypted_payload_b64` TEXT NOT NULL, `iv_b64` TEXT NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`account_id`), FOREIGN KEY(`account_id`) REFERENCES `accounts`(`account_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE TABLE IF NOT EXISTS `message_sync_flags` (`meta_id` INTEGER NOT NULL, `is_body_truncated` INTEGER NOT NULL, PRIMARY KEY(`meta_id`), FOREIGN KEY(`meta_id`) REFERENCES `messages_meta`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE TABLE IF NOT EXISTS `message_local_state` (`meta_id` INTEGER NOT NULL, `is_archived` INTEGER NOT NULL, `archived_at` INTEGER, `local_category` TEXT, `hidden_from_folder` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`meta_id`), FOREIGN KEY(`meta_id`) REFERENCES `messages_meta`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE INDEX IF NOT EXISTS `index_message_local_state_is_archived_archived_at` ON `message_local_state` (`is_archived`, `archived_at`)",
                "CREATE INDEX IF NOT EXISTS `index_message_local_state_local_category` ON `message_local_state` (`local_category`)",
                "CREATE TABLE IF NOT EXISTS `drafts` (`draft_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `account_id` INTEGER NOT NULL, `reply_to_meta_id` INTEGER, `mode` TEXT NOT NULL, `to_addresses` TEXT NOT NULL, `cc_addresses` TEXT NOT NULL, `bcc_addresses` TEXT NOT NULL, `subject` TEXT NOT NULL, `body_text` TEXT NOT NULL, `in_reply_to` TEXT, `references_json` TEXT, `attachments_json` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, FOREIGN KEY(`account_id`) REFERENCES `accounts`(`account_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE INDEX IF NOT EXISTS `index_drafts_account_id_updated_at` ON `drafts` (`account_id`, `updated_at`)",
                "CREATE TABLE IF NOT EXISTS `outbox` (`outbox_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `account_id` INTEGER NOT NULL, `draft_id` INTEGER, `from_email` TEXT NOT NULL, `from_name` TEXT, `to_addresses` TEXT NOT NULL, `cc_addresses` TEXT NOT NULL, `bcc_addresses` TEXT NOT NULL, `subject` TEXT NOT NULL, `body_text` TEXT NOT NULL, `in_reply_to` TEXT, `references_json` TEXT, `message_id_header` TEXT, `attachments_json` TEXT NOT NULL, `status` TEXT NOT NULL, `attempt_count` INTEGER NOT NULL, `last_error` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `sent_at` INTEGER, FOREIGN KEY(`account_id`) REFERENCES `accounts`(`account_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE INDEX IF NOT EXISTS `index_outbox_account_id_status_updated_at` ON `outbox` (`account_id`, `status`, `updated_at`)",
                "CREATE TABLE IF NOT EXISTS `local_filter_rules` (`rule_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `account_id` INTEGER NOT NULL, `name` TEXT NOT NULL, `is_enabled` INTEGER NOT NULL, `priority` INTEGER NOT NULL, `match_mode` TEXT NOT NULL, `conditions_json` TEXT NOT NULL, `target_local_folder` TEXT, `hide_from_folder` INTEGER NOT NULL, `archive_locally` INTEGER NOT NULL, `mark_read` INTEGER NOT NULL, `mark_flagged` INTEGER NOT NULL, `suppress_notification` INTEGER NOT NULL, `stop_processing` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, FOREIGN KEY(`account_id`) REFERENCES `accounts`(`account_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE INDEX IF NOT EXISTS `index_local_filter_rules_account_id_priority` ON `local_filter_rules` (`account_id`, `priority`)",
                "INSERT INTO accounts (account_id, email_address, imap_host, imap_port, smtp_host, smtp_port, auth_type) VALUES (1, 'v5@example.jp', 'imap.example.jp', 993, 'smtp.example.jp', 465, 'OAUTH2')",
                "INSERT INTO folders (folder_id, account_id, folder_name, full_path, uid_validity, highest_mod_seq, last_synced_uid, total_count, unread_count) VALUES (1, 1, 'INBOX', 'INBOX', 1, 0, 1, 1, 0)",
                "INSERT INTO messages_meta (id, account_id, folder_id, uid, thread_id, sender_address, subject, received_at, is_read, is_flagged, has_attachments, size_bytes) VALUES (50, 1, 1, 1, 't5', 'v5sender@example.jp', 'V5 Mail', 25000, 1, 0, 0, 120)",
                "PRAGMA user_version = 5",
            ).forEach { sql ->
                conn.prepare(sql).use { it.step() }
            }
            conn.close()

            val migratedDb = KazeMailDatabase.buildWithDriver(
                Room.databaseBuilder<KazeMailDatabase>(
                    name = dbFile.absolutePath,
                    factory = { KazeMailDatabase_Impl() },
                ),
                BundledSQLiteDriver(),
            )
            assertEquals("V5 Mail", migratedDb.messageMetaDao().getById(50L)?.subject)
            val profileId = migratedDb.aiProviderProfileDao().upsert(
                AiProviderProfileEntity(
                    displayName = "Test Gemini",
                    providerType = "GEMINI",
                    baseUrl = "https://generativelanguage.googleapis.com/v1beta",
                    modelId = "gemini-2.5-flash",
                    isDefault = true,
                    createdAt = 100L,
                    updatedAt = 100L,
                ),
            )
            val presetId = migratedDb.aiWritingPresetDao().upsert(
                AiWritingPresetEntity(
                    title = "承諾テンプレート",
                    goal = "REPLY",
                    tone = "FORMAL_BUSINESS",
                    instructionPrompt = "丁寧に承諾する",
                    createdAt = 100L,
                    updatedAt = 100L,
                ),
            )
            assertTrue(profileId > 0L)
            assertTrue(presetId > 0L)
            assertEquals(1, migratedDb.aiProviderProfileDao().getAll().size)
            assertEquals(1, migratedDb.aiWritingPresetDao().getAll().size)
            migratedDb.close()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun migration6To7ReplacesUnmanagedUnreadIndexAndPreservesAiData() = runTest {
        val tempDir = createTempDirectory("kazemail-mig67-test").toFile()
        val dbFile = File(tempDir, "mig_v6_v7.db")
        try {
            val driver = BundledSQLiteDriver()
            val conn = driver.open(dbFile.absolutePath)
            listOf(
                "CREATE TABLE IF NOT EXISTS `accounts` (`account_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `email_address` TEXT NOT NULL, `display_name` TEXT, `imap_host` TEXT NOT NULL, `imap_port` INTEGER NOT NULL, `smtp_host` TEXT NOT NULL, `smtp_port` INTEGER NOT NULL, `auth_type` TEXT NOT NULL, `last_sync_at` INTEGER)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_accounts_email_address` ON `accounts` (`email_address`)",
                "CREATE TABLE IF NOT EXISTS `folders` (`folder_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `account_id` INTEGER NOT NULL, `folder_name` TEXT NOT NULL, `full_path` TEXT NOT NULL, `uid_validity` INTEGER NOT NULL, `highest_mod_seq` INTEGER NOT NULL, `last_synced_uid` INTEGER NOT NULL, `total_count` INTEGER NOT NULL, `unread_count` INTEGER NOT NULL, FOREIGN KEY(`account_id`) REFERENCES `accounts`(`account_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_folders_account_id_full_path` ON `folders` (`account_id`, `full_path`)",
                "CREATE TABLE IF NOT EXISTS `messages_meta` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `account_id` INTEGER NOT NULL, `folder_id` INTEGER NOT NULL, `uid` INTEGER NOT NULL, `message_id_header` TEXT, `in_reply_to` TEXT, `thread_id` TEXT, `sender_name` TEXT, `sender_address` TEXT NOT NULL, `recipient_to` TEXT, `subject` TEXT, `snippet` TEXT, `received_at` INTEGER NOT NULL, `is_read` INTEGER NOT NULL, `is_flagged` INTEGER NOT NULL, `has_attachments` INTEGER NOT NULL, `size_bytes` INTEGER NOT NULL, FOREIGN KEY(`folder_id`) REFERENCES `folders`(`folder_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_messages_meta_folder_id_uid` ON `messages_meta` (`folder_id`, `uid`)",
                "CREATE INDEX IF NOT EXISTS `idx_meta_folder_date` ON `messages_meta` (`folder_id` ASC, `received_at` DESC)",
                "CREATE INDEX IF NOT EXISTS `idx_meta_thread` ON `messages_meta` (`thread_id` ASC, `received_at` ASC)",
                "CREATE INDEX IF NOT EXISTS `idx_meta_account` ON `messages_meta` (`account_id`)",
                "CREATE INDEX IF NOT EXISTS `idx_meta_folder_thread_date` ON `messages_meta` (`folder_id` ASC, `thread_id` ASC, `received_at` DESC)",
                "CREATE INDEX IF NOT EXISTS `idx_meta_account_thread_date` ON `messages_meta` (`account_id` ASC, `thread_id` ASC, `received_at` ASC)",
                "CREATE INDEX IF NOT EXISTS idx_meta_unread ON messages_meta(folder_id, is_read) WHERE is_read = 0",
                "CREATE TABLE IF NOT EXISTS `messages_body` (`meta_id` INTEGER NOT NULL, `plain_text` TEXT, `html_zstd` BLOB, `raw_headers` TEXT, PRIMARY KEY(`meta_id`), FOREIGN KEY(`meta_id`) REFERENCES `messages_meta`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE TABLE IF NOT EXISTS `attachments` (`attachment_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `meta_id` INTEGER NOT NULL, `part_id` TEXT NOT NULL, `filename` TEXT NOT NULL, `mime_type` TEXT NOT NULL, `size_bytes` INTEGER NOT NULL, `local_file_path` TEXT, FOREIGN KEY(`meta_id`) REFERENCES `messages_meta`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE INDEX IF NOT EXISTS `index_attachments_meta_id` ON `attachments` (`meta_id`)",
                "CREATE VIRTUAL TABLE IF NOT EXISTS `messages_fts` USING FTS5(`meta_id` UNINDEXED, `sender_name`, `sender_address`, `subject`, `plain_text`, tokenize=`trigram`)",
                "CREATE TABLE IF NOT EXISTS `folder_sync_state` (`folder_id` INTEGER NOT NULL, `phase1_completed` INTEGER NOT NULL, `backfill_completed` INTEGER NOT NULL, `backfill_next_max_uid` INTEGER NOT NULL, `supports_condstore` INTEGER NOT NULL, `supports_qresync` INTEGER NOT NULL, `supports_idle` INTEGER NOT NULL, `last_error` TEXT, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`folder_id`), FOREIGN KEY(`folder_id`) REFERENCES `folders`(`folder_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE TABLE IF NOT EXISTS `account_secrets` (`account_id` INTEGER NOT NULL, `encrypted_payload_b64` TEXT NOT NULL, `iv_b64` TEXT NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`account_id`), FOREIGN KEY(`account_id`) REFERENCES `accounts`(`account_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE TABLE IF NOT EXISTS `message_sync_flags` (`meta_id` INTEGER NOT NULL, `is_body_truncated` INTEGER NOT NULL, PRIMARY KEY(`meta_id`), FOREIGN KEY(`meta_id`) REFERENCES `messages_meta`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE TABLE IF NOT EXISTS `message_local_state` (`meta_id` INTEGER NOT NULL, `is_archived` INTEGER NOT NULL, `archived_at` INTEGER, `local_category` TEXT, `hidden_from_folder` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`meta_id`), FOREIGN KEY(`meta_id`) REFERENCES `messages_meta`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE INDEX IF NOT EXISTS `index_message_local_state_is_archived_archived_at` ON `message_local_state` (`is_archived`, `archived_at`)",
                "CREATE INDEX IF NOT EXISTS `index_message_local_state_local_category` ON `message_local_state` (`local_category`)",
                "CREATE TABLE IF NOT EXISTS `drafts` (`draft_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `account_id` INTEGER NOT NULL, `reply_to_meta_id` INTEGER, `mode` TEXT NOT NULL, `to_addresses` TEXT NOT NULL, `cc_addresses` TEXT NOT NULL, `bcc_addresses` TEXT NOT NULL, `subject` TEXT NOT NULL, `body_text` TEXT NOT NULL, `in_reply_to` TEXT, `references_json` TEXT, `attachments_json` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, FOREIGN KEY(`account_id`) REFERENCES `accounts`(`account_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE INDEX IF NOT EXISTS `index_drafts_account_id_updated_at` ON `drafts` (`account_id`, `updated_at`)",
                "CREATE TABLE IF NOT EXISTS `outbox` (`outbox_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `account_id` INTEGER NOT NULL, `draft_id` INTEGER, `from_email` TEXT NOT NULL, `from_name` TEXT, `to_addresses` TEXT NOT NULL, `cc_addresses` TEXT NOT NULL, `bcc_addresses` TEXT NOT NULL, `subject` TEXT NOT NULL, `body_text` TEXT NOT NULL, `in_reply_to` TEXT, `references_json` TEXT, `message_id_header` TEXT, `attachments_json` TEXT NOT NULL, `status` TEXT NOT NULL, `attempt_count` INTEGER NOT NULL, `last_error` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `sent_at` INTEGER, FOREIGN KEY(`account_id`) REFERENCES `accounts`(`account_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE INDEX IF NOT EXISTS `index_outbox_account_id_status_updated_at` ON `outbox` (`account_id`, `status`, `updated_at`)",
                "CREATE TABLE IF NOT EXISTS `local_filter_rules` (`rule_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `account_id` INTEGER NOT NULL, `name` TEXT NOT NULL, `is_enabled` INTEGER NOT NULL, `priority` INTEGER NOT NULL, `match_mode` TEXT NOT NULL, `conditions_json` TEXT NOT NULL, `target_local_folder` TEXT, `hide_from_folder` INTEGER NOT NULL, `archive_locally` INTEGER NOT NULL, `mark_read` INTEGER NOT NULL, `mark_flagged` INTEGER NOT NULL, `suppress_notification` INTEGER NOT NULL, `stop_processing` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, FOREIGN KEY(`account_id`) REFERENCES `accounts`(`account_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE INDEX IF NOT EXISTS `index_local_filter_rules_account_id_priority` ON `local_filter_rules` (`account_id`, `priority`)",
                "CREATE TABLE IF NOT EXISTS `ai_provider_profiles` (`profile_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `display_name` TEXT NOT NULL, `provider_type` TEXT NOT NULL, `base_url` TEXT NOT NULL, `model_id` TEXT NOT NULL, `encrypted_api_key_b64` TEXT, `api_key_iv_b64` TEXT, `api_key_hint` TEXT, `is_default` INTEGER NOT NULL, `is_enabled` INTEGER NOT NULL, `last_test_status` TEXT NOT NULL, `last_test_message` TEXT, `last_tested_at` INTEGER, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL)",
                "CREATE INDEX IF NOT EXISTS `index_ai_provider_profiles_is_default_updated_at` ON `ai_provider_profiles` (`is_default`, `updated_at`)",
                "CREATE TABLE IF NOT EXISTS `ai_writing_presets` (`preset_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `goal` TEXT NOT NULL, `tone` TEXT NOT NULL, `instruction_prompt` TEXT NOT NULL, `is_built_in` INTEGER NOT NULL, `sort_order` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL)",
                "CREATE INDEX IF NOT EXISTS `index_ai_writing_presets_sort_order_preset_id` ON `ai_writing_presets` (`sort_order`, `preset_id`)",
                "INSERT INTO ai_provider_profiles (profile_id, display_name, provider_type, base_url, model_id, is_default, is_enabled, last_test_status, created_at, updated_at) VALUES (1, 'V6 Gemini', 'GEMINI', 'https://generativelanguage.googleapis.com/v1beta', 'gemini-2.5-flash', 1, 1, 'UNTESTED', 100, 100)",
                "PRAGMA user_version = 6",
            ).forEach { sql ->
                conn.prepare(sql).use { it.step() }
            }
            conn.close()

            val migratedDb = KazeMailDatabase.buildWithDriver(
                Room.databaseBuilder<KazeMailDatabase>(
                    name = dbFile.absolutePath,
                    factory = { KazeMailDatabase_Impl() },
                ),
                BundledSQLiteDriver(),
            )
            assertEquals("V6 Gemini", migratedDb.aiProviderProfileDao().getAll().single().displayName)
            migratedDb.close()

            val checkConn = driver.open(dbFile.absolutePath)
            val indexNames = buildList {
                checkConn.prepare("PRAGMA index_list(`messages_meta`)").use { stmt ->
                    while (stmt.step()) {
                        add(stmt.getText(1))
                    }
                }
            }
            checkConn.close()
            assertFalse(indexNames.contains("idx_meta_unread"))
            assertTrue(indexNames.contains("idx_meta_folder_read"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun migration7To8AddsAccountSignaturesAndHtmlDraftOutboxColumns() = runTest {
        val tempDir = createTempDirectory("kazemail-mig78-test").toFile()
        val dbFile = File(tempDir, "mig_v7_v8.db")
        try {
            val driver = BundledSQLiteDriver()
            val conn = driver.open(dbFile.absolutePath)
            listOf(
                "CREATE TABLE IF NOT EXISTS `accounts` (`account_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `email_address` TEXT NOT NULL, `display_name` TEXT, `imap_host` TEXT NOT NULL, `imap_port` INTEGER NOT NULL, `smtp_host` TEXT NOT NULL, `smtp_port` INTEGER NOT NULL, `auth_type` TEXT NOT NULL, `last_sync_at` INTEGER)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_accounts_email_address` ON `accounts` (`email_address`)",
                "CREATE TABLE IF NOT EXISTS `folders` (`folder_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `account_id` INTEGER NOT NULL, `folder_name` TEXT NOT NULL, `full_path` TEXT NOT NULL, `uid_validity` INTEGER NOT NULL, `highest_mod_seq` INTEGER NOT NULL, `last_synced_uid` INTEGER NOT NULL, `total_count` INTEGER NOT NULL, `unread_count` INTEGER NOT NULL, FOREIGN KEY(`account_id`) REFERENCES `accounts`(`account_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_folders_account_id_full_path` ON `folders` (`account_id`, `full_path`)",
                "CREATE TABLE IF NOT EXISTS `messages_meta` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `account_id` INTEGER NOT NULL, `folder_id` INTEGER NOT NULL, `uid` INTEGER NOT NULL, `message_id_header` TEXT, `in_reply_to` TEXT, `thread_id` TEXT, `sender_name` TEXT, `sender_address` TEXT NOT NULL, `recipient_to` TEXT, `subject` TEXT, `snippet` TEXT, `received_at` INTEGER NOT NULL, `is_read` INTEGER NOT NULL, `is_flagged` INTEGER NOT NULL, `has_attachments` INTEGER NOT NULL, `size_bytes` INTEGER NOT NULL, FOREIGN KEY(`folder_id`) REFERENCES `folders`(`folder_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_messages_meta_folder_id_uid` ON `messages_meta` (`folder_id`, `uid`)",
                "CREATE INDEX IF NOT EXISTS `idx_meta_folder_date` ON `messages_meta` (`folder_id` ASC, `received_at` DESC)",
                "CREATE INDEX IF NOT EXISTS `idx_meta_thread` ON `messages_meta` (`thread_id` ASC, `received_at` ASC)",
                "CREATE INDEX IF NOT EXISTS `idx_meta_account` ON `messages_meta` (`account_id`)",
                "CREATE INDEX IF NOT EXISTS `idx_meta_folder_thread_date` ON `messages_meta` (`folder_id` ASC, `thread_id` ASC, `received_at` DESC)",
                "CREATE INDEX IF NOT EXISTS `idx_meta_account_thread_date` ON `messages_meta` (`account_id` ASC, `thread_id` ASC, `received_at` ASC)",
                "CREATE INDEX IF NOT EXISTS `idx_meta_folder_read` ON `messages_meta` (`folder_id`, `is_read`)",
                "CREATE TABLE IF NOT EXISTS `messages_body` (`meta_id` INTEGER NOT NULL, `plain_text` TEXT, `html_zstd` BLOB, `raw_headers` TEXT, PRIMARY KEY(`meta_id`), FOREIGN KEY(`meta_id`) REFERENCES `messages_meta`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE TABLE IF NOT EXISTS `attachments` (`attachment_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `meta_id` INTEGER NOT NULL, `part_id` TEXT NOT NULL, `filename` TEXT NOT NULL, `mime_type` TEXT NOT NULL, `size_bytes` INTEGER NOT NULL, `local_file_path` TEXT, FOREIGN KEY(`meta_id`) REFERENCES `messages_meta`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE INDEX IF NOT EXISTS `index_attachments_meta_id` ON `attachments` (`meta_id`)",
                "CREATE VIRTUAL TABLE IF NOT EXISTS `messages_fts` USING FTS5(`meta_id` UNINDEXED, `sender_name`, `sender_address`, `subject`, `plain_text`, tokenize=`trigram`)",
                "CREATE TABLE IF NOT EXISTS `folder_sync_state` (`folder_id` INTEGER NOT NULL, `phase1_completed` INTEGER NOT NULL, `backfill_completed` INTEGER NOT NULL, `backfill_next_max_uid` INTEGER NOT NULL, `supports_condstore` INTEGER NOT NULL, `supports_qresync` INTEGER NOT NULL, `supports_idle` INTEGER NOT NULL, `last_error` TEXT, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`folder_id`), FOREIGN KEY(`folder_id`) REFERENCES `folders`(`folder_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE TABLE IF NOT EXISTS `account_secrets` (`account_id` INTEGER NOT NULL, `encrypted_payload_b64` TEXT NOT NULL, `iv_b64` TEXT NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`account_id`), FOREIGN KEY(`account_id`) REFERENCES `accounts`(`account_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE TABLE IF NOT EXISTS `message_sync_flags` (`meta_id` INTEGER NOT NULL, `is_body_truncated` INTEGER NOT NULL, PRIMARY KEY(`meta_id`), FOREIGN KEY(`meta_id`) REFERENCES `messages_meta`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE TABLE IF NOT EXISTS `message_local_state` (`meta_id` INTEGER NOT NULL, `is_archived` INTEGER NOT NULL, `archived_at` INTEGER, `local_category` TEXT, `hidden_from_folder` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`meta_id`), FOREIGN KEY(`meta_id`) REFERENCES `messages_meta`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE INDEX IF NOT EXISTS `index_message_local_state_is_archived_archived_at` ON `message_local_state` (`is_archived`, `archived_at`)",
                "CREATE INDEX IF NOT EXISTS `index_message_local_state_local_category` ON `message_local_state` (`local_category`)",
                "CREATE TABLE IF NOT EXISTS `drafts` (`draft_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `account_id` INTEGER NOT NULL, `reply_to_meta_id` INTEGER, `mode` TEXT NOT NULL, `to_addresses` TEXT NOT NULL, `cc_addresses` TEXT NOT NULL, `bcc_addresses` TEXT NOT NULL, `subject` TEXT NOT NULL, `body_text` TEXT NOT NULL, `in_reply_to` TEXT, `references_json` TEXT, `attachments_json` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, FOREIGN KEY(`account_id`) REFERENCES `accounts`(`account_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE INDEX IF NOT EXISTS `index_drafts_account_id_updated_at` ON `drafts` (`account_id`, `updated_at`)",
                "CREATE TABLE IF NOT EXISTS `outbox` (`outbox_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `account_id` INTEGER NOT NULL, `draft_id` INTEGER, `from_email` TEXT NOT NULL, `from_name` TEXT, `to_addresses` TEXT NOT NULL, `cc_addresses` TEXT NOT NULL, `bcc_addresses` TEXT NOT NULL, `subject` TEXT NOT NULL, `body_text` TEXT NOT NULL, `in_reply_to` TEXT, `references_json` TEXT, `message_id_header` TEXT, `attachments_json` TEXT NOT NULL, `status` TEXT NOT NULL, `attempt_count` INTEGER NOT NULL, `last_error` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `sent_at` INTEGER, FOREIGN KEY(`account_id`) REFERENCES `accounts`(`account_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE INDEX IF NOT EXISTS `index_outbox_account_id_status_updated_at` ON `outbox` (`account_id`, `status`, `updated_at`)",
                "CREATE TABLE IF NOT EXISTS `local_filter_rules` (`rule_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `account_id` INTEGER NOT NULL, `name` TEXT NOT NULL, `is_enabled` INTEGER NOT NULL, `priority` INTEGER NOT NULL, `match_mode` TEXT NOT NULL, `conditions_json` TEXT NOT NULL, `target_local_folder` TEXT, `hide_from_folder` INTEGER NOT NULL, `archive_locally` INTEGER NOT NULL, `mark_read` INTEGER NOT NULL, `mark_flagged` INTEGER NOT NULL, `suppress_notification` INTEGER NOT NULL, `stop_processing` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, FOREIGN KEY(`account_id`) REFERENCES `accounts`(`account_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE INDEX IF NOT EXISTS `index_local_filter_rules_account_id_priority` ON `local_filter_rules` (`account_id`, `priority`)",
                "CREATE TABLE IF NOT EXISTS `ai_provider_profiles` (`profile_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `display_name` TEXT NOT NULL, `provider_type` TEXT NOT NULL, `base_url` TEXT NOT NULL, `model_id` TEXT NOT NULL, `encrypted_api_key_b64` TEXT, `api_key_iv_b64` TEXT, `api_key_hint` TEXT, `is_default` INTEGER NOT NULL, `is_enabled` INTEGER NOT NULL, `last_test_status` TEXT NOT NULL, `last_test_message` TEXT, `last_tested_at` INTEGER, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL)",
                "CREATE INDEX IF NOT EXISTS `index_ai_provider_profiles_is_default_updated_at` ON `ai_provider_profiles` (`is_default`, `updated_at`)",
                "CREATE TABLE IF NOT EXISTS `ai_writing_presets` (`preset_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `goal` TEXT NOT NULL, `tone` TEXT NOT NULL, `instruction_prompt` TEXT NOT NULL, `is_built_in` INTEGER NOT NULL, `sort_order` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL)",
                "CREATE INDEX IF NOT EXISTS `index_ai_writing_presets_sort_order_preset_id` ON `ai_writing_presets` (`sort_order`, `preset_id`)",
                "INSERT INTO accounts (account_id, email_address, imap_host, imap_port, smtp_host, smtp_port, auth_type) VALUES (1, 'v7@example.jp', 'imap.example.jp', 993, 'smtp.example.jp', 465, 'PASSWORD')",
                "INSERT INTO drafts (draft_id, account_id, mode, to_addresses, cc_addresses, bcc_addresses, subject, body_text, attachments_json, created_at, updated_at) VALUES (5, 1, 'NEW', '[\"to@example.jp\"]', '[]', '[]', 'V7 Draft', 'Old Plain Draft', '[]', 100, 100)",
                "INSERT INTO outbox (outbox_id, account_id, from_email, to_addresses, cc_addresses, bcc_addresses, subject, body_text, attachments_json, status, attempt_count, created_at, updated_at) VALUES (9, 1, 'v7@example.jp', '[\"to@example.jp\"]', '[]', '[]', 'V7 Outbox', 'Queued Text', '[]', 'QUEUED', 0, 100, 100)",
                "PRAGMA user_version = 7",
            ).forEach { sql ->
                conn.prepare(sql).use { it.step() }
            }
            conn.close()

            val migratedDb = KazeMailDatabase.buildWithDriver(
                Room.databaseBuilder<KazeMailDatabase>(
                    name = dbFile.absolutePath,
                    factory = { KazeMailDatabase_Impl() },
                ),
                BundledSQLiteDriver(),
            )
            val existingAcc = migratedDb.accountDao().getById(1L)!!
            assertFalse(existingAcc.signatureEnabled)
            assertFalse(existingAcc.signatureConfigured)
            assertEquals("", existingAcc.signatureText)

            migratedDb.accountDao().updateSignature(1L, true, "<b>V8署名</b>")
            val updatedAcc = migratedDb.accountDao().getById(1L)!!
            assertTrue(updatedAcc.signatureEnabled)
            assertTrue(updatedAcc.signatureConfigured)
            assertEquals("<b>V8署名</b>", updatedAcc.signatureText)

            val existingDraft = migratedDb.draftDao().getById(5L)!!
            assertFalse(existingDraft.isHtml)
            assertNull(existingDraft.bodyHtml)

            val existingOutbox = migratedDb.outboxDao().getById(9L)!!
            assertNull(existingOutbox.bodyHtml)
            migratedDb.close()
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
