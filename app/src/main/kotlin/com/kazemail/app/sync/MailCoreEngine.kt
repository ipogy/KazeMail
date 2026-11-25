package com.kazemail.app.sync

import com.kazemail.app.security.StoredSecurityMode

sealed interface CoreImapAuth {
    data class Password(val username: String, val password: String) : CoreImapAuth
    data class OAuth2(val email: String, val accessToken: String) : CoreImapAuth
}

data class CoreImapConfig(
    val host: String,
    val port: Int,
    val security: StoredSecurityMode,
    val auth: CoreImapAuth,
    val timeoutSecs: Int = 30,
)

data class CoreFolderInfo(
    val fullPath: String,
    val folderName: String,
    val delimiter: String?,
    val attributes: List<String>,
    val isSelectable: Boolean,
)

data class CoreFolderSnapshot(
    val fullPath: String,
    val uidValidity: Long,
    val highestModSeq: Long,
    val exists: Int,
    val uidNext: Long?,
    val supportsCondstore: Boolean,
    val supportsQresync: Boolean,
    val supportsIdle: Boolean,
)

data class CoreAttachmentMeta(
    val partId: String,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
)

data class CoreParsedEmail(
    val uid: Long,
    val messageIdHeader: String?,
    val inReplyTo: String?,
    val references: List<String>,
    val threadKey: String,
    val senderName: String?,
    val senderAddress: String,
    val recipientsToJson: String,
    val subject: String?,
    val snippet: String,
    val receivedAtMs: Long,
    val isRead: Boolean,
    val isFlagged: Boolean,
    val hasAttachments: Boolean,
    val sizeBytes: Long,
    val plainText: String,
    val htmlZstd: ByteArray?,
    val rawHeaders: String,
    val isTruncated: Boolean,
    val attachments: List<CoreAttachmentMeta>,
)

data class CoreUidFlagChange(
    val uid: Long,
    val isRead: Boolean,
    val isFlagged: Boolean,
    val modSeq: Long?,
)

data class CoreIncrementalDelta(
    val newUids: List<Long>,
    val flagChanges: List<CoreUidFlagChange>,
    val vanishedUids: List<Long>,
    val snapshot: CoreFolderSnapshot,
)

data class CoreSmtpConfig(
    val host: String,
    val port: Int,
    val security: StoredSecurityMode,
    val auth: CoreImapAuth,
    val timeoutSecs: Int = 30,
)

data class CoreOutgoingAttachment(
    val filename: String,
    val mimeType: String,
    val filePath: String,
)

data class CoreOutgoingMessage(
    val fromName: String?,
    val fromAddress: String,
    val toAddresses: List<String>,
    val ccAddresses: List<String>,
    val bccAddresses: List<String>,
    val subject: String,
    val plainText: String,
    val htmlBody: String? = null,
    val inReplyTo: String? = null,
    val references: List<String> = emptyList(),
    val messageId: String? = null,
    val attachments: List<CoreOutgoingAttachment> = emptyList(),
)

data class CoreSmtpSendResult(
    val messageId: String,
    val serverResponse: String,
)

interface ImapSessionHandle : AutoCloseable {
    suspend fun listFolders(): List<CoreFolderInfo>
    suspend fun examineFolder(fullPath: String): CoreFolderSnapshot
    suspend fun fetchLatestUids(fullPath: String, limit: Int): List<Long>
    suspend fun fetchAllUidsDescending(fullPath: String): List<Long>
    suspend fun fetchMessagesByUids(fullPath: String, uids: List<Long>): List<CoreParsedEmail>
    suspend fun fetchIncrementalDelta(
        fullPath: String,
        knownLastUid: Long,
        knownHighestModSeq: Long,
    ): CoreIncrementalDelta

    suspend fun fetchMessagePart(
        fullPath: String,
        uid: Long,
        partId: String,
        maxBytes: Long? = null,
    ): ByteArray

    suspend fun waitForIdleEvent(fullPath: String, timeoutSecs: Int): Boolean
    suspend fun logout()
}

interface MailCoreEngine {
    suspend fun connect(config: CoreImapConfig): ImapSessionHandle
    suspend fun decompressHtmlZstd(compressed: ByteArray, maxOutputBytes: Long = 2L * 1024 * 1024): String
    suspend fun parseEmlBytes(
        uid: Long,
        rawBytes: ByteArray,
        isRead: Boolean = true,
        isFlagged: Boolean = false,
        internalDateMs: Long? = null,
    ): CoreParsedEmail = throw UnsupportedOperationException("parseEmlBytes is not configured on this MailCoreEngine")

    suspend fun sendSmtpMessage(config: CoreSmtpConfig, message: CoreOutgoingMessage): CoreSmtpSendResult =
        throw UnsupportedOperationException("SMTP send is not configured on this MailCoreEngine")
}
