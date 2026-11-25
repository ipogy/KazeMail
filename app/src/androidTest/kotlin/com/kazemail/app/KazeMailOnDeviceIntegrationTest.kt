package com.kazemail.app

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room3.Room
import androidx.room3.useReaderConnection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kazemail.app.data.db.AccountEntity
import com.kazemail.app.data.db.FtsQueryBuilder
import com.kazemail.app.data.db.KazeMailDatabase
import com.kazemail.app.data.db.OutboxMessageEntity
import com.kazemail.app.security.AccountCredentialStore
import com.kazemail.app.security.AndroidKeystoreSecretCipher
import com.kazemail.app.security.StoredAccountCredentials
import com.kazemail.app.security.StoredSecurityMode
import com.kazemail.app.send.OutboxManager
import com.kazemail.app.sync.NonDestructiveSyncEngine
import com.kazemail.app.sync.OAuth2TokenRefresher
import com.kazemail.app.sync.RustMailCoreEngine
import com.kazemail.app.ui.viewer.SandboxedHtmlViewer
import com.kazemail.core.uniffi.compressHtmlZstd
import com.kazemail.core.uniffi.coreVersion
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.zetetic.database.sqlcipher.driver.SQLCipherDriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class KazeMailOnDeviceIntegrationTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun sqlCipherKeystoreNativeRustAndFts5EndToEnd() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        System.loadLibrary("sqlcipher")

        // Verify Rust native core loads on device ABI via UniFFI
        assertEquals("0.1.0", coreVersion())
        val htmlSample = "<html><body><p>KazeMail 暗号化＋Zstd検証</p></body></html>"
        val zstdBytes = compressHtmlZstd(htmlSample)
        val rustEngine = RustMailCoreEngine()
        assertEquals(htmlSample, rustEngine.decompressHtmlZstd(zstdBytes))

        // Verify Keystore AES-256-GCM wrap/unwrap + real SQLCipher disk encryption + FTS5 trigram
        val cipher = AndroidKeystoreSecretCipher("kazemail_test_master_key_v1")
        val rawKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val wrapped = cipher.encrypt(rawKey)
        assertTrue(rawKey.contentEquals(cipher.decrypt(wrapped)))

        val dbFile = File(context.cacheDir, "kazemail_sqlcipher_test_${System.nanoTime()}.db")
        try {
            val db1 = KazeMailDatabase.buildWithDriver(
                Room.databaseBuilder<KazeMailDatabase>(
                    context = context,
                    name = dbFile.absolutePath,
                ),
                SQLCipherDriver(rawKey, null, null),
            )
            val accId = db1.accountDao().insert(
                AccountEntity(
                    emailAddress = "cipher@example.jp",
                    imapHost = "imap.example.jp",
                    smtpHost = "smtp.example.jp",
                    authType = "PASSWORD",
                ),
            )
            val folderId = db1.folderDao().insert(
                com.kazemail.app.data.db.FolderEntity(
                    accountId = accId,
                    folderName = "INBOX",
                    fullPath = "INBOX",
                    uidValidity = 10L,
                ),
            )
            val secretSubject = "ConfidentialSqlCipherSubject_請求書テスト"
            val metaId = db1.messageMetaDao().insertIgnore(
                com.kazemail.app.data.db.MessageMetaEntity(
                    accountId = accId,
                    folderId = folderId,
                    uid = 1L,
                    senderName = "暗号化送信者",
                    senderAddress = "sender@example.jp",
                    subject = secretSubject,
                    snippet = "秘密の本文スニペット",
                    receivedAt = 1_760_000_000_000L,
                ),
            )
            db1.messageFtsDao().insert(
                com.kazemail.app.data.db.MessageFtsEntity(
                    metaId = metaId,
                    senderName = "暗号化送信者",
                    senderAddress = "sender@example.jp",
                    subject = secretSubject,
                    plainText = "SQLCipherで保護された日本語請求書ドキュメントです。",
                ),
            )
            db1.close()

            // Verify DB file on disk is actually encrypted (no plaintext subject leakage)
            val onDiskText = dbFile.readBytes().toString(StandardCharsets.ISO_8859_1)
            assertFalse("Plaintext leaked into SQLCipher file!", onDiskText.contains(secretSubject))

            // Reopen with same Keystore-unwrapped passphrase and execute FTS5 trigram search
            val db2 = KazeMailDatabase.buildWithDriver(
                Room.databaseBuilder<KazeMailDatabase>(
                    context = context,
                    name = dbFile.absolutePath,
                ),
                SQLCipherDriver(cipher.decrypt(wrapped), null, null),
            )
            val hits = db2.messageFtsDao().search(FtsQueryBuilder.buildSearchQuery("請求書"))
            assertEquals(1, hits.size)
            assertEquals(secretSubject, hits.single().subject)
            db2.close()
        } finally {
            dbFile.delete()
        }
    }

    @Test
    fun realRustNativeImapSyncAndSmtpOutboxThroughLoopbackServers() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        System.loadLibrary("sqlcipher")

        val imapCommands = CopyOnWriteArrayList<String>()
        val smtpCommands = CopyOnWriteArrayList<String>()
        val smtpDataLines = CopyOnWriteArrayList<String>()

        val imapSocket = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        val smtpSocket = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))

        val imapThread = thread(start = true) {
            runCatching {
                imapSocket.use { server ->
                    server.accept().use { client ->
                        val writer = OutputStreamWriter(client.getOutputStream(), StandardCharsets.US_ASCII)
                        val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII))
                        writer.write("* OK [CAPABILITY IMAP4rev1 AUTH=PLAIN CONDSTORE IDLE] AndroidMock ready\r\n")
                        writer.flush()

                        while (true) {
                            val line = reader.readLine() ?: break
                            imapCommands += line
                            val parts = line.split(" ", limit = 2)
                            val tag = parts.getOrNull(0) ?: ""
                            val rest = parts.getOrNull(1) ?: ""
                            val upper = rest.uppercase()

                            when {
                                upper.startsWith("LOGIN ") -> {
                                    writer.write("$tag OK LOGIN completed\r\n")
                                }
                                upper == "CAPABILITY" -> {
                                    writer.write("* CAPABILITY IMAP4rev1 AUTH=PLAIN CONDSTORE IDLE\r\n")
                                    writer.write("$tag OK CAPABILITY completed\r\n")
                                }
                                upper.startsWith("ENABLE ") -> {
                                    writer.write("* ENABLED CONDSTORE\r\n")
                                    writer.write("$tag OK ENABLE completed\r\n")
                                }
                                upper.startsWith("LIST ") -> {
                                    writer.write("* LIST (\\HasNoChildren) \"/\" \"INBOX\"\r\n")
                                    writer.write("$tag OK LIST completed\r\n")
                                }
                                upper.startsWith("EXAMINE ") -> {
                                    writer.write("* FLAGS (\\Answered \\Flagged \\Deleted \\Seen \\Draft)\r\n")
                                    writer.write("* 1 EXISTS\r\n")
                                    writer.write("* OK [UIDVALIDITY 888] Ok\r\n")
                                    writer.write("* OK [UIDNEXT 502] Ok\r\n")
                                    writer.write("* OK [HIGHESTMODSEQ 42] Ok\r\n")
                                    writer.write("$tag OK [READ-ONLY] EXAMINE completed\r\n")
                                }
                                upper.startsWith("FETCH 1:1 (UID)") -> {
                                    writer.write("* 1 FETCH (UID 501)\r\n")
                                    writer.write("$tag OK FETCH completed\r\n")
                                }
                                upper.startsWith("UID FETCH 501") && upper.contains("BODYSTRUCTURE") -> {
                                    writer.write(
                                        "* 1 FETCH (UID 501 FLAGS () INTERNALDATE \"20-Nov-2026 10:00:00 +0900\" RFC822.SIZE 1024 BODYSTRUCTURE ((\"TEXT\" \"PLAIN\" (\"CHARSET\" \"UTF-8\") NIL NIL \"7BIT\" 30 1 NIL NIL NIL)))\r\n",
                                    )
                                    writer.write("$tag OK UID FETCH completed\r\n")
                                }
                                upper.startsWith("UID FETCH 501") && upper.contains("BODY.PEEK") -> {
                                    val hdr = "From: Partner <partner@example.jp>\r\nTo: Me <me@example.jp>\r\nSubject: OnDevice Test\r\nMessage-ID: <ondev-501@example.jp>\r\n\r\n"
                                    val body = "Android 実機Rust同期テスト本文\r\n"
                                    writer.write("* 1 FETCH (UID 501 BODY[HEADER] {${hdr.toByteArray().size}}\r\n$hdr BODY[1] {${body.toByteArray(StandardCharsets.UTF_8).size}}\r\n$body)\r\n")
                                    writer.write("$tag OK UID FETCH completed\r\n")
                                }
                                upper == "LOGOUT" -> {
                                    writer.write("* BYE\r\n$tag OK LOGOUT\r\n")
                                    writer.flush()
                                    break
                                }
                                else -> {
                                    writer.write("$tag BAD Unexpected: $rest\r\n")
                                }
                            }
                            writer.flush()
                        }
                    }
                }
            }
        }

        val smtpThread = thread(start = true) {
            runCatching {
                smtpSocket.use { server ->
                    server.accept().use { client ->
                        val writer = OutputStreamWriter(client.getOutputStream(), StandardCharsets.US_ASCII)
                        val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
                        writer.write("220 smtp.androidmock.local ESMTP\r\n")
                        writer.flush()

                        var inData = false
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (inData) {
                                if (line == ".") {
                                    inData = false
                                    writer.write("250 2.0.0 Ok: queued on Android mock\r\n")
                                    writer.flush()
                                } else {
                                    smtpDataLines += line
                                }
                                continue
                            }
                            smtpCommands += line
                            val upper = line.uppercase()
                            when {
                                upper.startsWith("EHLO ") -> {
                                    writer.write("250-smtp.androidmock.local\r\n250 AUTH PLAIN LOGIN\r\n")
                                }
                                upper.startsWith("AUTH PLAIN ") -> {
                                    writer.write("235 2.7.0 Auth ok\r\n")
                                }
                                upper.startsWith("MAIL FROM:") || upper.startsWith("RCPT TO:") -> {
                                    writer.write("250 2.1.5 Ok\r\n")
                                }
                                upper == "DATA" -> {
                                    inData = true
                                    writer.write("354 End data\r\n")
                                }
                                upper == "QUIT" -> {
                                    writer.write("221 Bye\r\n")
                                    writer.flush()
                                    break
                                }
                                else -> writer.write("502 Unknown\r\n")
                            }
                            writer.flush()
                        }
                    }
                }
            }
        }

        val dbFile = File(context.cacheDir, "kazemail_loopback_${System.nanoTime()}.db")
        try {
            val cipher = AndroidKeystoreSecretCipher("kazemail_test_master_key_v1")
            val dbKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val db = KazeMailDatabase.buildWithDriver(
                Room.databaseBuilder<KazeMailDatabase>(
                    context = context,
                    name = dbFile.absolutePath,
                ),
                SQLCipherDriver(dbKey, null, null),
            )
            val credStore = AccountCredentialStore(db.accountSecretDao(), cipher)
            val rustCore = RustMailCoreEngine()
            val syncEngine = NonDestructiveSyncEngine(
                db = db,
                credentialStore = credStore,
                mailCore = rustCore,
                tokenRefresher = OAuth2TokenRefresher { _, _ -> error("unused") },
            )
            val outboxManager = OutboxManager(
                db = db,
                credentialStore = credStore,
                mailCore = rustCore,
                tokenRefresher = OAuth2TokenRefresher { _, _ -> error("unused") },
            )

            val accountId = db.accountDao().insert(
                AccountEntity(
                    emailAddress = "me@example.jp",
                    displayName = "実機テストユーザー",
                    imapHost = "127.0.0.1",
                    imapPort = imapSocket.localPort,
                    smtpHost = "127.0.0.1",
                    smtpPort = smtpSocket.localPort,
                    authType = "PASSWORD",
                ),
            )
            credStore.saveCredentials(
                accountId,
                StoredAccountCredentials.Password(
                    username = "me@example.jp",
                    password = "pw",
                    securityMode = StoredSecurityMode.PLAINTEXT,
                ),
            )

            val p1 = syncEngine.runPhase1InboxSync(accountId)
            assertEquals(1, p1.newlySyncedCount)
            val syncedMsg = db.messageMetaDao().getByFolderAndUid(p1.inboxFolderId, 501L)
            assertNotNull(syncedMsg)
            assertEquals("OnDevice Test", syncedMsg!!.subject)
            assertTrue(
                "Non-destructive check: SELECT/EXPUNGE must never be issued",
                imapCommands.none {
                    val up = it.uppercase()
                    up.contains("SELECT ") || up.contains("EXPUNGE")
                },
            )

            val outboxId = outboxManager.enqueueSend(
                accountId = accountId,
                draftId = null,
                to = listOf("partner@example.jp"),
                cc = emptyList(),
                bcc = listOf("bcc-hidden@example.jp"),
                subject = "Re: OnDevice Test",
                bodyText = "Android実機からのSMTP送信テスト",
                inReplyTo = syncedMsg.messageIdHeader,
                references = emptyList(),
                attachments = emptyList(),
            )
            val summary = outboxManager.processPendingOutbox()
            assertEquals(1, summary.sentCount)
            assertEquals(OutboxMessageEntity.STATUS_SENT, db.outboxDao().getById(outboxId)!!.status)
            assertTrue(smtpCommands.any { it == "RCPT TO:<bcc-hidden@example.jp>" })
            val joinedData = smtpDataLines.joinToString("\n")
            assertFalse(joinedData.contains("bcc-hidden@example.jp"))

            db.close()
            imapThread.join(5_000)
            smtpThread.join(5_000)
        } finally {
            dbFile.delete()
            runCatching { imapSocket.close() }
            runCatching { smtpSocket.close() }
        }
    }

    @Test
    fun sandboxedHtmlViewerBlocksExternalTrackingPixelByDefault() {
        val blockedCount = AtomicInteger(0)
        val maliciousHtml = """
            <html>
              <body>
                <p>Safe text content</p>
                <img src="https://tracking.example.invalid/pixel.png" />
                <script>document.write('BAD_JS');</script>
              </body>
            </html>
        """.trimIndent()

        composeRule.setContent {
            SandboxedHtmlViewer(
                html = maliciousHtml,
                allowExternalImages = false,
                isDarkSurface = false,
                onBlockedExternalResource = { blockedCount.incrementAndGet() },
                onExternalLinkClick = {},
            )
        }

        composeRule.waitUntil(timeoutMillis = 8_000) {
            blockedCount.get() > 0
        }
        assertTrue(blockedCount.get() >= 1)
    }

    @Test
    fun sqlCipherMigrationV5ToV7PreservesExistingDataAndReplacesUnmanagedIndex() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        System.loadLibrary("sqlcipher")

        val rawKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val dbFile = File(context.cacheDir, "kazemail_v5_v7_mig_${System.nanoTime()}.db")
        try {
            val seedConn = SQLCipherDriver(rawKey, null, null).open(dbFile.absolutePath)
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
                "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
                "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'e8f8d0129e100a434e384f5de74724c5')",
                "INSERT INTO accounts (account_id, email_address, display_name, imap_host, imap_port, smtp_host, smtp_port, auth_type, last_sync_at) VALUES (1, 'v5cipher@example.jp', '既存ユーザー', 'imap.example.jp', 993, 'smtp.example.jp', 465, 'OAUTH2', 1760000000000)",
                "INSERT INTO folders (folder_id, account_id, folder_name, full_path, uid_validity, highest_mod_seq, last_synced_uid, total_count, unread_count) VALUES (1, 1, 'INBOX', 'INBOX', 1, 0, 1, 1, 1)",
                "INSERT INTO messages_meta (id, account_id, folder_id, uid, thread_id, sender_name, sender_address, subject, snippet, received_at, is_read, is_flagged, has_attachments, size_bytes) VALUES (50, 1, 1, 1, 't5', '既存送信者', 'v5sender@example.jp', 'V5 SQLCipher Mail', '本文スニペット', 25000, 0, 0, 0, 120)",
                "INSERT INTO messages_fts (meta_id, sender_name, sender_address, subject, plain_text) VALUES (50, '既存送信者', 'v5sender@example.jp', 'V5 SQLCipher Mail', 'SQLCipher移行検証テスト本文')",
                "INSERT INTO message_local_state (meta_id, is_archived, archived_at, local_category, hidden_from_folder) VALUES (50, 0, NULL, NULL, 0)",
                "PRAGMA user_version = 5",
            ).forEach { sql ->
                seedConn.prepare(sql).use { it.step() }
            }
            seedConn.close()

            val migratedDb = KazeMailDatabase.buildWithDriver(
                Room.databaseBuilder<KazeMailDatabase>(
                    context = context,
                    name = dbFile.absolutePath,
                ),
                SQLCipherDriver(rawKey, null, null),
            )
            val accounts = migratedDb.accountDao().observeAll().first()
            assertEquals(1, accounts.size)
            assertEquals("v5cipher@example.jp", accounts.single().emailAddress)
            assertEquals("V5 SQLCipher Mail", migratedDb.messageMetaDao().getById(50L)?.subject)
            assertEquals(1, migratedDb.messageFtsDao().search(FtsQueryBuilder.buildSearchQuery("SQLCipher移行")).size)

            val profileId = migratedDb.aiProviderProfileDao().upsert(
                com.kazemail.app.data.db.AiProviderProfileEntity(
                    displayName = "Cipher Migrated Gemini",
                    providerType = "GEMINI",
                    baseUrl = "https://generativelanguage.googleapis.com/v1beta",
                    modelId = "gemini-2.5-flash",
                    isDefault = true,
                    createdAt = 100L,
                    updatedAt = 100L,
                ),
            )
            assertTrue(profileId > 0L)
            assertEquals(1, migratedDb.aiProviderProfileDao().getAll().size)

            val aiService = com.kazemail.app.ai.AiPreparationService(
                db = migratedDb,
                appPreferences = com.kazemail.app.data.AppPreferences(context),
                secretVault = com.kazemail.app.ai.AiSecretVault(
                    com.kazemail.app.security.AndroidKeystoreSecretCipher("kazemail_test_master_key_v1"),
                ),
                aiClient = object : com.kazemail.app.ai.AiClient {
                    override suspend fun verifyConnection(
                        config: com.kazemail.app.ai.AiProviderConnectionConfig,
                    ) = error("unused")

                    override suspend fun complete(
                        config: com.kazemail.app.ai.AiProviderConnectionConfig,
                        request: com.kazemail.app.ai.AiCompletionRequest,
                    ) = error("unused")
                },
                messageRepository = com.kazemail.app.data.MessageRepository(
                    db = migratedDb,
                    mailCore = RustMailCoreEngine(),
                    syncEngine = NonDestructiveSyncEngine(
                        db = migratedDb,
                        credentialStore = AccountCredentialStore(
                            migratedDb.accountSecretDao(),
                            com.kazemail.app.security.AndroidKeystoreSecretCipher("kazemail_test_master_key_v1"),
                        ),
                        mailCore = RustMailCoreEngine(),
                        tokenRefresher = OAuth2TokenRefresher { _, _ -> error("unused") },
                    ),
                ),
            )
            aiService.ensureDefaultPresetsSeeded()
            assertTrue(migratedDb.aiWritingPresetDao().count() >= 4)
            assertEquals(1, migratedDb.messageMetaDao().observeTotalMessageCount().first())
            assertEquals(1, migratedDb.messageMetaDao().observeMessageCountsByAccount().first().single().count)
            assertEquals(1, migratedDb.folderDao().observeByAccount(1L).first().size)
            assertTrue(migratedDb.localFilterRuleDao().observeByAccount(1L).first().isEmpty())

            val indexNames = migratedDb.useReaderConnection { conn ->
                conn.usePrepared("PRAGMA index_list(`messages_meta`)") { stmt ->
                    buildList {
                        while (stmt.step()) {
                            add(stmt.getText(1))
                        }
                    }
                }
            }
            assertFalse(indexNames.contains("idx_meta_unread"))
            assertTrue(indexNames.contains("idx_meta_folder_read"))

            migratedDb.close()
        } finally {
            dbFile.delete()
        }
    }

    @Test
    fun sqlCipherMigrationV6ToV7ReplacesUnmanagedUnreadIndexAndPreservesAiData() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        System.loadLibrary("sqlcipher")

        val rawKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val dbFile = File(context.cacheDir, "kazemail_v6_v7_mig_${System.nanoTime()}.db")
        try {
            val seedConn = SQLCipherDriver(rawKey, null, null).open(dbFile.absolutePath)
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
                "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
                "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'b3c7e76f6fa1140976f6b3cbe49a7e7e')",
                "INSERT INTO ai_provider_profiles (profile_id, display_name, provider_type, base_url, model_id, is_default, is_enabled, last_test_status, created_at, updated_at) VALUES (1, 'V6 Cipher Gemini', 'GEMINI', 'https://generativelanguage.googleapis.com/v1beta', 'gemini-2.5-flash', 1, 1, 'UNTESTED', 100, 100)",
                "PRAGMA user_version = 6",
            ).forEach { sql ->
                seedConn.prepare(sql).use { it.step() }
            }
            seedConn.close()

            val migratedDb = KazeMailDatabase.buildWithDriver(
                Room.databaseBuilder<KazeMailDatabase>(
                    context = context,
                    name = dbFile.absolutePath,
                ),
                SQLCipherDriver(rawKey, null, null),
            )
            assertEquals("V6 Cipher Gemini", migratedDb.aiProviderProfileDao().getAll().single().displayName)
            migratedDb.close()
        } finally {
            dbFile.delete()
        }
    }

    @Test
    fun sqlCipherMigrationV1ThroughV7PreservesLegacyData() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        System.loadLibrary("sqlcipher")

        val rawKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val dbFile = File(context.cacheDir, "kazemail_v1_v7_mig_${System.nanoTime()}.db")
        try {
            val seedConn = SQLCipherDriver(rawKey, null, null).open(dbFile.absolutePath)
            listOf(
                "CREATE TABLE IF NOT EXISTS `accounts` (`account_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `email_address` TEXT NOT NULL, `display_name` TEXT, `imap_host` TEXT NOT NULL, `imap_port` INTEGER NOT NULL, `smtp_host` TEXT NOT NULL, `smtp_port` INTEGER NOT NULL, `auth_type` TEXT NOT NULL, `last_sync_at` INTEGER)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_accounts_email_address` ON `accounts` (`email_address`)",
                "CREATE TABLE IF NOT EXISTS `folders` (`folder_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `account_id` INTEGER NOT NULL, `folder_name` TEXT NOT NULL, `full_path` TEXT NOT NULL, `uid_validity` INTEGER NOT NULL, `highest_mod_seq` INTEGER NOT NULL, `last_synced_uid` INTEGER NOT NULL, `total_count` INTEGER NOT NULL, `unread_count` INTEGER NOT NULL, FOREIGN KEY(`account_id`) REFERENCES `accounts`(`account_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_folders_account_id_full_path` ON `folders` (`account_id`, `full_path`)",
                "CREATE TABLE IF NOT EXISTS `messages_meta` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `account_id` INTEGER NOT NULL, `folder_id` INTEGER NOT NULL, `uid` INTEGER NOT NULL, `message_id_header` TEXT, `in_reply_to` TEXT, `thread_id` TEXT, `sender_name` TEXT, `sender_address` TEXT NOT NULL, `recipient_to` TEXT, `subject` TEXT, `snippet` TEXT, `received_at` INTEGER NOT NULL, `is_read` INTEGER NOT NULL, `is_flagged` INTEGER NOT NULL, `has_attachments` INTEGER NOT NULL, `size_bytes` INTEGER NOT NULL, FOREIGN KEY(`folder_id`) REFERENCES `folders`(`folder_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_messages_meta_folder_id_uid` ON `messages_meta` (`folder_id`, `uid`)",
                "CREATE INDEX IF NOT EXISTS `idx_meta_folder_date` ON `messages_meta` (`folder_id` ASC, `received_at` DESC)",
                "CREATE INDEX IF NOT EXISTS `idx_meta_thread` ON `messages_meta` (`thread_id` ASC, `received_at` ASC)",
                "CREATE INDEX IF NOT EXISTS idx_meta_unread ON messages_meta(folder_id, is_read) WHERE is_read = 0",
                "CREATE TABLE IF NOT EXISTS `messages_body` (`meta_id` INTEGER NOT NULL, `plain_text` TEXT, `html_zstd` BLOB, `raw_headers` TEXT, PRIMARY KEY(`meta_id`), FOREIGN KEY(`meta_id`) REFERENCES `messages_meta`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE TABLE IF NOT EXISTS `attachments` (`attachment_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `meta_id` INTEGER NOT NULL, `part_id` TEXT NOT NULL, `filename` TEXT NOT NULL, `mime_type` TEXT NOT NULL, `size_bytes` INTEGER NOT NULL, `local_file_path` TEXT, FOREIGN KEY(`meta_id`) REFERENCES `messages_meta`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE INDEX IF NOT EXISTS `index_attachments_meta_id` ON `attachments` (`meta_id`)",
                "CREATE VIRTUAL TABLE IF NOT EXISTS `messages_fts` USING FTS5(`meta_id` UNINDEXED, `sender_name`, `sender_address`, `subject`, `plain_text`, tokenize=`trigram`)",
                "CREATE TABLE IF NOT EXISTS `folder_sync_state` (`folder_id` INTEGER NOT NULL, `phase1_completed` INTEGER NOT NULL, `backfill_completed` INTEGER NOT NULL, `backfill_next_max_uid` INTEGER NOT NULL, `supports_condstore` INTEGER NOT NULL, `supports_qresync` INTEGER NOT NULL, `supports_idle` INTEGER NOT NULL, `last_error` TEXT, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`folder_id`), FOREIGN KEY(`folder_id`) REFERENCES `folders`(`folder_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE TABLE IF NOT EXISTS `account_secrets` (`account_id` INTEGER NOT NULL, `encrypted_payload_b64` TEXT NOT NULL, `iv_b64` TEXT NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`account_id`), FOREIGN KEY(`account_id`) REFERENCES `accounts`(`account_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE TABLE IF NOT EXISTS `message_sync_flags` (`meta_id` INTEGER NOT NULL, `is_body_truncated` INTEGER NOT NULL, PRIMARY KEY(`meta_id`), FOREIGN KEY(`meta_id`) REFERENCES `messages_meta`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "INSERT INTO accounts (account_id, email_address, imap_host, imap_port, smtp_host, smtp_port, auth_type) VALUES (1, 'v1cipher@example.jp', 'imap.example.jp', 993, 'smtp.example.jp', 465, 'PASSWORD')",
                "INSERT INTO folders (folder_id, account_id, folder_name, full_path, uid_validity, highest_mod_seq, last_synced_uid, total_count, unread_count) VALUES (1, 1, 'INBOX', 'INBOX', 1, 0, 1, 1, 1)",
                "INSERT INTO messages_meta (id, account_id, folder_id, uid, sender_address, subject, received_at, is_read, is_flagged, has_attachments, size_bytes) VALUES (10, 1, 1, 1, 'old@example.jp', 'V1 Cipher Mail', 5000, 0, 0, 0, 100)",
                "PRAGMA user_version = 1",
            ).forEach { sql ->
                seedConn.prepare(sql).use { it.step() }
            }
            seedConn.close()

            val migratedDb = KazeMailDatabase.buildWithDriver(
                Room.databaseBuilder<KazeMailDatabase>(
                    context = context,
                    name = dbFile.absolutePath,
                ),
                SQLCipherDriver(rawKey, null, null),
            )
            assertEquals("V1 Cipher Mail", migratedDb.messageMetaDao().getById(10L)?.subject)
            val v1Acc = migratedDb.accountDao().getById(1L)!!
            assertFalse(v1Acc.signatureConfigured)
            migratedDb.close()
        } finally {
            dbFile.delete()
        }
    }

    @Test
    fun sqlCipherMigrationV7ToV8PreservesDataAndAddsSignatureHtmlColumns() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        System.loadLibrary("sqlcipher")

        val rawKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val dbFile = File(context.cacheDir, "kazemail_v7_v8_mig_${System.nanoTime()}.db")
        try {
            val seedConn = SQLCipherDriver(rawKey, null, null).open(dbFile.absolutePath)
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
                "INSERT INTO accounts (account_id, email_address, imap_host, imap_port, smtp_host, smtp_port, auth_type) VALUES (1, 'v7cipher@example.jp', 'imap.example.jp', 993, 'smtp.example.jp', 465, 'PASSWORD')",
                "PRAGMA user_version = 7",
            ).forEach { sql ->
                seedConn.prepare(sql).use { it.step() }
            }
            seedConn.close()

            val migratedDb = KazeMailDatabase.buildWithDriver(
                Room.databaseBuilder<KazeMailDatabase>(
                    context = context,
                    name = dbFile.absolutePath,
                ),
                SQLCipherDriver(rawKey, null, null),
            )
            migratedDb.accountDao().updateSignature(1L, true, "暗号化済みアカウント署名")
            val updated = migratedDb.accountDao().getById(1L)!!
            assertTrue(updated.signatureEnabled)
            assertTrue(updated.signatureConfigured)
            assertEquals("暗号化済みアカウント署名", updated.signatureText)
            migratedDb.close()
        } finally {
            dbFile.delete()
        }
    }
}
