package com.kazemail.app.sync

import com.kazemail.app.security.StoredSecurityMode
import com.kazemail.core.uniffi.FolderInfo
import com.kazemail.core.uniffi.FolderSyncSnapshot
import com.kazemail.core.uniffi.ImapAuth
import com.kazemail.core.uniffi.ImapConnectConfig
import com.kazemail.core.uniffi.ImapSecurity
import com.kazemail.core.uniffi.OutgoingAttachmentSpec
import com.kazemail.core.uniffi.OutgoingEmailMessage
import com.kazemail.core.uniffi.ParsedEmailRecord
import com.kazemail.core.uniffi.SafeImapClient
import com.kazemail.core.uniffi.SmtpAuth
import com.kazemail.core.uniffi.SmtpConnectConfig
import com.kazemail.core.uniffi.SmtpSecurity
import com.kazemail.core.uniffi.connectAndAuth
import com.kazemail.core.uniffi.decompressHtmlZstd as rustDecompressHtmlZstd
import com.kazemail.core.uniffi.parseEmlBytes as rustParseEmlBytes
import com.kazemail.core.uniffi.sendSmtpMessage as rustSendSmtpMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RustMailCoreEngine : MailCoreEngine {
    override suspend fun connect(config: CoreImapConfig): ImapSessionHandle =
        withContext(Dispatchers.IO) {
            val uniffiConfig = ImapConnectConfig(
                host = config.host,
                port = config.port.toUShort(),
                security = when (config.security) {
                    StoredSecurityMode.IMPLICIT_TLS -> ImapSecurity.IMPLICIT_TLS
                    StoredSecurityMode.START_TLS -> ImapSecurity.START_TLS
                    StoredSecurityMode.PLAINTEXT -> ImapSecurity.PLAINTEXT
                },
                auth = when (val auth = config.auth) {
                    is CoreImapAuth.Password -> ImapAuth.Password(
                        username = auth.username,
                        password = auth.password,
                    )
                    is CoreImapAuth.OAuth2 -> ImapAuth.OAuth2(
                        email = auth.email,
                        accessToken = auth.accessToken,
                    )
                },
                timeoutSecs = config.timeoutSecs.toUInt(),
            )
            RustImapSessionHandle(connectAndAuth(uniffiConfig))
        }

    override suspend fun decompressHtmlZstd(compressed: ByteArray, maxOutputBytes: Long): String =
        withContext(Dispatchers.IO) {
            rustDecompressHtmlZstd(compressed, maxOutputBytes.toULong())
        }

    override suspend fun parseEmlBytes(
        uid: Long,
        rawBytes: ByteArray,
        isRead: Boolean,
        isFlagged: Boolean,
        internalDateMs: Long?,
    ): CoreParsedEmail = withContext(Dispatchers.IO) {
        rustParseEmlBytes(
            uid = uid.toUInt(),
            rawBytes = rawBytes,
            isRead = isRead,
            isFlagged = isFlagged,
            internalDateMs = internalDateMs,
        ).toCore()
    }

    override suspend fun sendSmtpMessage(
        config: CoreSmtpConfig,
        message: CoreOutgoingMessage,
    ): CoreSmtpSendResult = withContext(Dispatchers.IO) {
        val uniffiConfig = SmtpConnectConfig(
            host = config.host,
            port = config.port.toUShort(),
            security = when (config.security) {
                StoredSecurityMode.IMPLICIT_TLS -> SmtpSecurity.IMPLICIT_TLS
                StoredSecurityMode.START_TLS -> SmtpSecurity.START_TLS
                StoredSecurityMode.PLAINTEXT -> SmtpSecurity.PLAINTEXT
            },
            auth = when (val auth = config.auth) {
                is CoreImapAuth.Password -> SmtpAuth.Password(
                    username = auth.username,
                    password = auth.password,
                )
                is CoreImapAuth.OAuth2 -> SmtpAuth.OAuth2(
                    email = auth.email,
                    accessToken = auth.accessToken,
                )
            },
            timeoutSecs = config.timeoutSecs.toUInt(),
        )
        val uniffiMessage = OutgoingEmailMessage(
            fromName = message.fromName,
            fromAddress = message.fromAddress,
            toAddresses = message.toAddresses,
            ccAddresses = message.ccAddresses,
            bccAddresses = message.bccAddresses,
            subject = message.subject,
            plainText = message.plainText,
            htmlBody = message.htmlBody,
            inReplyTo = message.inReplyTo,
            references = message.references,
            messageId = message.messageId,
            attachments = message.attachments.map { att ->
                OutgoingAttachmentSpec(
                    filename = att.filename,
                    mimeType = att.mimeType,
                    filePath = att.filePath,
                )
            },
        )
        val result = rustSendSmtpMessage(uniffiConfig, uniffiMessage)
        CoreSmtpSendResult(
            messageId = result.messageId,
            serverResponse = result.serverResponse,
        )
    }
}

private class RustImapSessionHandle(
    private val client: SafeImapClient,
) : ImapSessionHandle {

    override suspend fun listFolders(): List<CoreFolderInfo> = withContext(Dispatchers.IO) {
        client.listFolders().map { it.toCore() }
    }

    override suspend fun examineFolder(fullPath: String): CoreFolderSnapshot =
        withContext(Dispatchers.IO) {
            client.examineFolder(fullPath).toCore()
        }

    override suspend fun fetchLatestUids(fullPath: String, limit: Int): List<Long> =
        withContext(Dispatchers.IO) {
            client.fetchLatestUids(fullPath, limit.toUInt()).map { it.toLong() }
        }

    override suspend fun fetchAllUidsDescending(fullPath: String): List<Long> =
        withContext(Dispatchers.IO) {
            client.fetchAllUidsDescending(fullPath).map { it.toLong() }
        }

    override suspend fun fetchMessagesByUids(
        fullPath: String,
        uids: List<Long>,
    ): List<CoreParsedEmail> = withContext(Dispatchers.IO) {
        client.fetchMessagesByUids(fullPath, uids.map { it.toUInt() }).map { it.toCore() }
    }

    override suspend fun fetchIncrementalDelta(
        fullPath: String,
        knownLastUid: Long,
        knownHighestModSeq: Long,
    ): CoreIncrementalDelta = withContext(Dispatchers.IO) {
        val delta = client.fetchIncrementalDelta(
            fullPath = fullPath,
            knownLastUid = knownLastUid.toUInt(),
            knownHighestModSeq = knownHighestModSeq.toULong(),
        )
        CoreIncrementalDelta(
            newUids = delta.newUids.map { it.toLong() },
            flagChanges = delta.flagChanges.map {
                CoreUidFlagChange(
                    uid = it.uid.toLong(),
                    isRead = it.isRead,
                    isFlagged = it.isFlagged,
                    modSeq = it.modSeq?.toLong(),
                )
            },
            vanishedUids = delta.vanishedUids.map { it.toLong() },
            snapshot = delta.snapshot.toCore(),
        )
    }

    override suspend fun fetchMessagePart(
        fullPath: String,
        uid: Long,
        partId: String,
        maxBytes: Long?,
    ): ByteArray = withContext(Dispatchers.IO) {
        client.fetchMessagePart(fullPath, uid.toUInt(), partId, maxBytes?.toULong())
    }

    override suspend fun waitForIdleEvent(fullPath: String, timeoutSecs: Int): Boolean =
        withContext(Dispatchers.IO) {
            client.waitForIdleEvent(fullPath, timeoutSecs.toUInt())
        }

    override suspend fun logout() = withContext(Dispatchers.IO) {
        client.logout()
    }

    override fun close() {
        runCatching { client.logout() }
        client.close()
    }
}

private fun FolderInfo.toCore() = CoreFolderInfo(
    fullPath = fullPath,
    folderName = folderName,
    delimiter = delimiter,
    attributes = attributes,
    isSelectable = isSelectable,
)

private fun FolderSyncSnapshot.toCore() = CoreFolderSnapshot(
    fullPath = fullPath,
    uidValidity = uidValidity.toLong(),
    highestModSeq = highestModSeq.toLong(),
    exists = exists.toInt(),
    uidNext = uidNext?.toLong(),
    supportsCondstore = supportsCondstore,
    supportsQresync = supportsQresync,
    supportsIdle = supportsIdle,
)

private fun ParsedEmailRecord.toCore() = CoreParsedEmail(
    uid = uid.toLong(),
    messageIdHeader = messageIdHeader,
    inReplyTo = inReplyTo,
    references = references,
    threadKey = threadKey,
    senderName = senderName,
    senderAddress = senderAddress,
    recipientsToJson = recipientsToJson,
    subject = subject,
    snippet = snippet,
    receivedAtMs = receivedAtMs,
    isRead = isRead,
    isFlagged = isFlagged,
    hasAttachments = hasAttachments,
    sizeBytes = sizeBytes.toLong(),
    plainText = plainText,
    htmlZstd = htmlZstd,
    rawHeaders = rawHeaders,
    isTruncated = isTruncated,
    attachments = attachments.map {
        CoreAttachmentMeta(
            partId = it.partId,
            filename = it.filename,
            mimeType = it.mimeType,
            sizeBytes = it.sizeBytes.toLong(),
        )
    },
)
