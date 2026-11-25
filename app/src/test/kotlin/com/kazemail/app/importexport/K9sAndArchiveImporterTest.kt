package com.kazemail.app.importexport

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.kazemail.app.auth.OAuth2SessionResult
import com.kazemail.app.data.db.FtsQueryBuilder
import com.kazemail.app.data.db.KazeMailDatabase
import com.kazemail.app.data.db.KazeMailDatabase_Impl
import com.kazemail.app.security.AccountCredentialStore
import com.kazemail.app.security.EncryptedBlob
import com.kazemail.app.security.SecretCipher
import com.kazemail.app.security.StoredSecurityMode
import com.kazemail.app.sync.CoreAttachmentMeta
import com.kazemail.app.sync.CoreImapConfig
import com.kazemail.app.sync.CoreParsedEmail
import com.kazemail.app.sync.ImapSessionHandle
import com.kazemail.app.sync.MailCoreEngine
import com.kazemail.app.sync.NonDestructiveSyncEngine
import com.kazemail.app.sync.OAuth2TokenRefresher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class K9sAndArchiveImporterTest {

    @Test
    fun parsesZippedModernK9sWithMultipleAccountsAndIdentities() {
        val xml = """
            <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
            <k9settings version="87" format="1">
                <accounts>
                    <account uuid="acc-gmail">
                        <name>Work Gmail</name>
                        <incoming-server type="IMAP">
                            <host>imap.gmail.com</host>
                            <port>993</port>
                            <connection-security>SSL_TLS_REQUIRED</connection-security>
                            <authentication-type>XOAUTH2</authentication-type>
                            <username>taro@gmail.com</username>
                        </incoming-server>
                        <outgoing-server type="SMTP">
                            <host>smtp.gmail.com</host>
                            <port>465</port>
                            <connection-security>SSL_TLS_REQUIRED</connection-security>
                            <authentication-type>XOAUTH2</authentication-type>
                            <username>taro@gmail.com</username>
                        </outgoing-server>
                        <identities>
                            <identity>
                                <name>山田 太郎</name>
                                <email>taro@gmail.com</email>
                            </identity>
                        </identities>
                    </account>
                    <account uuid="acc-corp">
                        <name>Corp Mail</name>
                        <incoming-server type="IMAP">
                            <host>imap.corp.jp</host>
                            <port>143</port>
                            <connection-security>STARTTLS_REQUIRED</connection-security>
                            <authentication-type>PLAIN</authentication-type>
                            <username>taro-login</username>
                        </incoming-server>
                        <outgoing-server type="SMTP">
                            <host>smtp.corp.jp</host>
                            <port>587</port>
                            <connection-security>STARTTLS_REQUIRED</connection-security>
                            <authentication-type>PLAIN</authentication-type>
                            <username>taro-login</username>
                        </outgoing-server>
                        <identities>
                            <identity>
                                <name>太郎 (会社)</name>
                                <email>taro@corp.jp</email>
                            </identity>
                        </identities>
                    </account>
                    <account uuid="acc-pop">
                        <name>Old POP3</name>
                        <incoming-server type="POP3">
                            <host>pop.old.jp</host>
                            <port>995</port>
                            <connection-security>SSL_TLS_REQUIRED</connection-security>
                            <authentication-type>PLAIN</authentication-type>
                            <username>old@old.jp</username>
                        </incoming-server>
                    </account>
                </accounts>
            </k9settings>
        """.trimIndent()

        val zipBytes = ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(ZipEntry("settings.xml"))
                zos.write(xml.toByteArray(StandardCharsets.UTF_8))
                zos.closeEntry()
            }
            baos.toByteArray()
        }

        val parsed = K9sSettingsParser.parseStream(ByteArrayInputStream(zipBytes))
        assertEquals("87", parsed.settingsVersion)
        assertEquals("1", parsed.formatVersion)
        assertEquals(3, parsed.accounts.size)

        val gmail = parsed.accounts[0]
        assertEquals("taro@gmail.com", gmail.email)
        assertEquals("山田 太郎", gmail.displayName)
        assertEquals("imap.gmail.com", gmail.imapHost)
        assertEquals(993, gmail.imapPort)
        assertEquals(StoredSecurityMode.IMPLICIT_TLS, gmail.imapSecurity)
        assertTrue(gmail.usesOAuth2)
        assertTrue(gmail.isImapSupported)
        assertEquals("gmail", gmail.suggestedOAuthPresetId)

        val corp = parsed.accounts[1]
        assertEquals("taro@corp.jp", corp.email)
        assertEquals("taro-login", corp.username)
        assertEquals(StoredSecurityMode.START_TLS, corp.imapSecurity)
        assertEquals(587, corp.smtpPort)
        assertFalse(corp.usesOAuth2)
        assertTrue(corp.isImapSupported)

        val pop = parsed.accounts[2]
        assertEquals("POP3", pop.incomingType)
        assertFalse(pop.isImapSupported)
    }

    @Test
    fun parsesLegacyPlainXmlAndRejectsXxeDoctype() {
        val legacyXml = """
            <?xml version='1.0' encoding='UTF-8' ?>
            <k9settings version="12" format="1">
                <accounts>
                    <account uuid="legacy-1">
                        <name>Legacy Account</name>
                        <settings>
                            <value key="storeUri">imap+ssl+://PLAIN:legacy%40example.jp:p%40ss@imap.legacy.jp:993</value>
                            <value key="transportUri">smtp+tls+://PLAIN:legacy%40example.jp@smtp.legacy.jp:587</value>
                        </settings>
                        <identities>
                            <identity>
                                <name>レガシー利用者</name>
                                <email>legacy@example.jp</email>
                            </identity>
                        </identities>
                    </account>
                </accounts>
            </k9settings>
        """.trimIndent()

        val res = K9sSettingsParser.parseStream(
            ByteArrayInputStream(legacyXml.toByteArray(StandardCharsets.UTF_8)),
        )
        assertEquals(1, res.accounts.size)
        val acc = res.accounts.single()
        assertEquals("legacy@example.jp", acc.email)
        assertEquals("レガシー利用者", acc.displayName)
        assertEquals("imap.legacy.jp", acc.imapHost)
        assertEquals(993, acc.imapPort)
        assertEquals(StoredSecurityMode.IMPLICIT_TLS, acc.imapSecurity)
        assertEquals("p@ss", acc.incomingPassword)
        assertEquals("smtp.legacy.jp", acc.smtpHost)
        assertEquals(587, acc.smtpPort)
        assertEquals(StoredSecurityMode.START_TLS, acc.smtpSecurity)

        val maliciousXxe = """
            <?xml version='1.0' encoding='UTF-8'?>
            <!DOCTYPE foo [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
            <k9settings version="1"><accounts/></k9settings>
        """.trimIndent()

        assertThrows(SecurityException::class.java) {
            K9sSettingsParser.parseStream(
                ByteArrayInputStream(maliciousXxe.toByteArray(StandardCharsets.UTF_8)),
            )
        }
    }

    @Test
    fun importsEmlAndStreamsMboxAndDeduplicatesSafely() = runTest {
        val db = KazeMailDatabase.buildWithDriver(
            Room.inMemoryDatabaseBuilder<KazeMailDatabase>(
                factory = { KazeMailDatabase_Impl() },
            ),
            BundledSQLiteDriver(),
        )
        try {
            val fakeCore = object : MailCoreEngine {
                override suspend fun connect(config: CoreImapConfig): ImapSessionHandle = error("must not connect IMAP")
                override suspend fun decompressHtmlZstd(compressed: ByteArray, maxOutputBytes: Long) = ""
                override suspend fun parseEmlBytes(
                    uid: Long,
                    rawBytes: ByteArray,
                    isRead: Boolean,
                    isFlagged: Boolean,
                    internalDateMs: Long?,
                ): CoreParsedEmail {
                    val rawStr = rawBytes.toString(StandardCharsets.UTF_8)
                    val headers = rawStr.substringBefore("\r\n\r\n")
                    val body = rawStr.substringAfter("\r\n\r\n", "")
                    val subj = headers.lines().firstOrNull { it.startsWith("Subject:") }
                        ?.substringAfter(':')?.trim() ?: "(no subject)"
                    val mid = headers.lines().firstOrNull { it.startsWith("Message-ID:") }
                        ?.substringAfter(':')?.trim()?.trim('<', '>')
                    val from = headers.lines().firstOrNull { it.startsWith("From:") }
                        ?.substringAfter(':')?.trim() ?: "unknown@example.jp"
                    return CoreParsedEmail(
                        uid = uid,
                        messageIdHeader = mid,
                        inReplyTo = null,
                        references = emptyList(),
                        threadKey = mid?.let { "mid:$it" } ?: "uid:$uid",
                        senderName = "インポート送信者",
                        senderAddress = from,
                        recipientsToJson = "[]",
                        subject = subj,
                        snippet = body.take(80),
                        receivedAtMs = 1_760_000_000_000L + uid,
                        isRead = isRead,
                        isFlagged = isFlagged,
                        hasAttachments = false,
                        sizeBytes = rawBytes.size.toLong(),
                        plainText = body,
                        htmlZstd = null,
                        rawHeaders = headers,
                        isTruncated = false,
                        attachments = emptyList<CoreAttachmentMeta>(),
                    )
                }
            }

            val importer = LocalArchiveImporter(db, fakeCore)

            val emlRaw = (
                "From: tanaka@example.jp\r\n" +
                    "Subject: 契約書アーカイブ\r\n" +
                    "Message-ID: <eml-1@example.jp>\r\n\r\n" +
                    "単体EMLインポート本文"
                ).toByteArray(StandardCharsets.UTF_8)

            val emlRes = importer.importEmlStream(null, ByteArrayInputStream(emlRaw))
            assertEquals(1, emlRes.importedCount)
            assertEquals(0, emlRes.skippedDuplicates)

            // Importing MBOX containing same Message-ID once + 2 new messages (including >From unescape)
            val mboxRaw = """
                From tanaka@example.jp Mon Nov 20 10:00:00 2026
                From: tanaka@example.jp
                Subject: 契約書アーカイブ
                Message-ID: <eml-1@example.jp>

                単体EML重複分

                From sato@example.jp Mon Nov 20 11:00:00 2026
                From: sato@example.jp
                Subject: 見積書インポート
                Message-ID: <mbox-2@example.jp>

                1通目のMBOX本文
                >From escaped quote line

                From suzuki@example.jp Mon Nov 20 12:00:00 2026
                From: suzuki@example.jp
                Subject: 議事録バックアップ
                Message-ID: <mbox-3@example.jp>

                2通目のMBOX本文
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)

            val mboxRes = importer.importMboxStream(null, ByteArrayInputStream(mboxRaw))
            assertEquals(2, mboxRes.importedCount)
            assertEquals(1, mboxRes.skippedDuplicates)
            assertEquals(0, mboxRes.errorCount)
            assertEquals(3, db.messageMetaDao().countByFolder(mboxRes.folderId))

            val hits = db.messageFtsDao().search(
                FtsQueryBuilder.buildSearchQuery("議事録", accountId = mboxRes.accountId),
            )
            assertEquals(1, hits.size)
            assertEquals("議事録バックアップ", hits.single().subject)

            // Verify >From unescaping worked cleanly
            val m2 = db.messageMetaDao().findByMessageId(mboxRes.accountId, "mbox-2@example.jp")!!
            val body2 = db.messageBodyDao().getByMetaId(m2.id)!!
            assertTrue(body2.plainText!!.contains("From escaped quote line"))

            // Verify NonDestructiveSyncEngine skips LOCAL archive account cleanly without calling IMAP connect
            val cipher = object : SecretCipher {
                override fun encrypt(plaintext: ByteArray) = EncryptedBlob(
                    Base64.getEncoder().encodeToString(plaintext),
                    "AAAA",
                )
                override fun decrypt(blob: EncryptedBlob): ByteArray =
                    Base64.getDecoder().decode(blob.ciphertextB64)
            }
            val syncEngine = NonDestructiveSyncEngine(
                db = db,
                credentialStore = AccountCredentialStore(db.accountSecretDao(), cipher),
                mailCore = fakeCore,
                tokenRefresher = OAuth2TokenRefresher { _, email ->
                    OAuth2SessionResult(email, "tok", "{}")
                },
            )
            val syncDelta = syncEngine.runIncrementalSync(mboxRes.accountId)
            assertEquals(0, syncDelta.newlySyncedCount)
        } finally {
            db.close()
        }
    }
}
