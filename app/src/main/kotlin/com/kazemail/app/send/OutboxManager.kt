package com.kazemail.app.send

import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import com.kazemail.app.auth.OAuth2Manager
import com.kazemail.app.data.db.DraftAttachmentRef
import com.kazemail.app.data.db.DraftEntity
import com.kazemail.app.data.db.KazeMailDatabase
import com.kazemail.app.data.db.OutboxMessageEntity
import com.kazemail.app.security.AccountCredentialStore
import com.kazemail.app.security.StoredAccountCredentials
import com.kazemail.app.security.StoredSecurityMode
import com.kazemail.app.sync.CoreImapAuth
import com.kazemail.app.sync.CoreOutgoingAttachment
import com.kazemail.app.sync.CoreOutgoingMessage
import com.kazemail.app.sync.CoreSmtpConfig
import com.kazemail.app.sync.MailCoreEngine
import com.kazemail.app.sync.OAuth2TokenRefresher
import com.kazemail.app.ui.compose.ComposeHtmlHelper
import com.kazemail.app.ui.viewer.HtmlEmailDetector
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class OutboxProcessSummary(
    val sentCount: Int,
    val failedCount: Int,
    val retryPendingCount: Int,
    val lastError: String? = null,
) {
    val shouldScheduleRetry: Boolean
        get() = retryPendingCount > 0
}

class OutboxManager(
    private val db: KazeMailDatabase,
    private val credentialStore: AccountCredentialStore,
    private val mailCore: MailCoreEngine,
    private val tokenRefresher: OAuth2TokenRefresher,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }

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

    suspend fun saveDraft(
        draftId: Long?,
        accountId: Long,
        replyToMetaId: Long?,
        mode: String,
        to: List<String>,
        cc: List<String>,
        bcc: List<String>,
        subject: String,
        bodyText: String,
        inReplyTo: String?,
        references: List<String>,
        attachments: List<DraftAttachmentRef>,
        isHtml: Boolean = false,
        bodyHtml: String? = null,
    ): Long {
        val now = clockMs()
        val existing = if (draftId != null && draftId > 0L) db.draftDao().getById(draftId) else null
        val normalizedBodyHtml = if (isHtml) (bodyHtml ?: "") else null
        val normalizedBodyText = if (isHtml && bodyText.isBlank() && !normalizedBodyHtml.isNullOrBlank()) {
            HtmlEmailDetector.stripHtmlFallbackText(normalizedBodyHtml)
        } else {
            bodyText
        }
        val entity = DraftEntity(
            draftId = existing?.draftId ?: 0L,
            accountId = accountId,
            replyToMetaId = replyToMetaId ?: existing?.replyToMetaId,
            mode = mode,
            toAddressesJson = json.encodeToString(to),
            ccAddressesJson = json.encodeToString(cc),
            bccAddressesJson = json.encodeToString(bcc),
            subject = subject,
            bodyText = normalizedBodyText,
            isHtml = isHtml,
            bodyHtml = normalizedBodyHtml,
            inReplyTo = inReplyTo,
            referencesJson = if (references.isEmpty()) null else json.encodeToString(references),
            attachmentsJson = json.encodeToString(attachments),
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        return db.draftDao().upsert(entity)
    }

    suspend fun deleteDraft(draftId: Long) {
        if (draftId > 0L) {
            db.draftDao().deleteById(draftId)
        }
    }

    /**
     * Atomically stores the composed email in the local encrypted Outbox table and removes any backing Draft.
     * Actual SMTP transmission runs asynchronously via WorkManager with NetworkType.CONNECTED.
     */
    suspend fun enqueueSend(
        accountId: Long,
        draftId: Long?,
        to: List<String>,
        cc: List<String>,
        bcc: List<String>,
        subject: String,
        bodyText: String,
        inReplyTo: String?,
        references: List<String>,
        attachments: List<DraftAttachmentRef>,
        bodyHtml: String? = null,
    ): Long {
        val account = db.accountDao().getById(accountId)
            ?: error("Account $accountId not found")
        val now = clockMs()
        val normalizedBodyHtml = bodyHtml?.takeIf { it.isNotBlank() }
        val normalizedBodyText = if (bodyText.isBlank() && normalizedBodyHtml != null) {
            HtmlEmailDetector.stripHtmlFallbackText(normalizedBodyHtml)
        } else {
            bodyText
        }

        var createdId = 0L
        db.useWriterConnection { conn ->
            conn.immediateTransaction {
                createdId = db.outboxDao().insert(
                    OutboxMessageEntity(
                        accountId = accountId,
                        draftId = draftId?.takeIf { it > 0L },
                        fromEmail = account.emailAddress,
                        fromName = account.displayName,
                        toAddressesJson = json.encodeToString(to),
                        ccAddressesJson = json.encodeToString(cc),
                        bccAddressesJson = json.encodeToString(bcc),
                        subject = subject,
                        bodyText = normalizedBodyText,
                        bodyHtml = normalizedBodyHtml,
                        inReplyTo = inReplyTo,
                        referencesJson = if (references.isEmpty()) null else json.encodeToString(references),
                        messageIdHeader = null,
                        attachmentsJson = json.encodeToString(attachments),
                        status = OutboxMessageEntity.STATUS_QUEUED,
                        attemptCount = 0,
                        lastError = null,
                        createdAt = now,
                        updatedAt = now,
                        sentAt = null,
                    ),
                )
                if (draftId != null && draftId > 0L) {
                    db.draftDao().deleteById(draftId)
                }
            }
        }
        return createdId
    }

    suspend fun moveOutboxItemToDraft(outboxId: Long): Long? {
        val item = db.outboxDao().getById(outboxId) ?: return null
        if (item.status == OutboxMessageEntity.STATUS_SENDING || item.status == OutboxMessageEntity.STATUS_SENT) {
            return null
        }
        val now = clockMs()
        var draftId = 0L
        db.useWriterConnection { conn ->
            conn.immediateTransaction {
                draftId = db.draftDao().upsert(
                    DraftEntity(
                        accountId = item.accountId,
                        mode = if (item.inReplyTo != null) "REPLY" else "NEW",
                        toAddressesJson = item.toAddressesJson,
                        ccAddressesJson = item.ccAddressesJson,
                        bccAddressesJson = item.bccAddressesJson,
                        subject = item.subject,
                        bodyText = item.bodyText,
                        isHtml = !item.bodyHtml.isNullOrBlank(),
                        bodyHtml = item.bodyHtml,
                        inReplyTo = item.inReplyTo,
                        referencesJson = item.referencesJson,
                        attachmentsJson = item.attachmentsJson,
                        createdAt = item.createdAt,
                        updatedAt = now,
                    ),
                )
                db.outboxDao().deleteById(outboxId)
            }
        }
        return draftId
    }

    suspend fun retryOutboxItem(outboxId: Long) {
        db.outboxDao().resetToQueued(outboxId, clockMs())
    }

    suspend fun processPendingOutbox(maxRetryAttempts: Int = DEFAULT_MAX_RETRY_ATTEMPTS): OutboxProcessSummary {
        val queued = db.outboxDao().getAllQueued()
        var sentCount = 0
        var failedCount = 0
        var retryPendingCount = 0
        var lastError: String? = null

        for (item in queued) {
            val claimed = db.outboxDao().markSending(item.outboxId, clockMs())
            if (claimed == 0) continue

            val sendOutcome = runCatching {
                val account = db.accountDao().getById(item.accountId)
                    ?: error("Account ${item.accountId} not found")
                val creds = credentialStore.loadCredentials(item.accountId)
                    ?: error("No stored credentials for ${account.emailAddress}")

                val smtpAuth = resolveSmtpAuth(item.accountId, creds)
                val smtpSecurity = resolveSmtpSecurity(account.smtpPort, creds.securityMode)
                val toList = decodeStringList(item.toAddressesJson)
                val ccList = decodeStringList(item.ccAddressesJson)
                val bccList = decodeStringList(item.bccAddressesJson)
                val refs = decodeStringList(item.referencesJson)
                val atts = decodeAttachmentList(item.attachmentsJson).map {
                    CoreOutgoingAttachment(
                        filename = it.filename,
                        mimeType = it.mimeType,
                        filePath = it.filePath,
                    )
                }

                mailCore.sendSmtpMessage(
                    config = CoreSmtpConfig(
                        host = account.smtpHost,
                        port = account.smtpPort,
                        security = smtpSecurity,
                        auth = smtpAuth,
                    ),
                    message = CoreOutgoingMessage(
                        fromName = item.fromName,
                        fromAddress = item.fromEmail,
                        toAddresses = toList,
                        ccAddresses = ccList,
                        bccAddresses = bccList,
                        subject = item.subject,
                        plainText = item.bodyText.ifBlank {
                            item.bodyHtml?.let(ComposeHtmlHelper::htmlToPlainText).orEmpty()
                        },
                        htmlBody = item.bodyHtml
                            ?.takeIf { it.isNotBlank() }
                            ?.let(ComposeHtmlHelper::buildFullOutgoingHtmlDocument),
                        inReplyTo = item.inReplyTo,
                        references = refs,
                        messageId = item.messageIdHeader,
                        attachments = atts,
                    ),
                )
            }

            sendOutcome.onSuccess { res ->
                db.outboxDao().markSent(item.outboxId, res.messageId, clockMs())
                sentCount += 1
            }.onFailure { err ->
                val detail = err.message ?: err.javaClass.simpleName
                lastError = detail
                val nextAttempts = item.attemptCount + 1
                val permanent = isPermanentFailure(err) || nextAttempts >= maxRetryAttempts
                val nextStatus = if (permanent) {
                    failedCount += 1
                    OutboxMessageEntity.STATUS_FAILED
                } else {
                    retryPendingCount += 1
                    OutboxMessageEntity.STATUS_QUEUED
                }
                db.outboxDao().markAttemptResult(
                    outboxId = item.outboxId,
                    status = nextStatus,
                    errorMessage = detail,
                    nowMs = clockMs(),
                )
            }
        }

        return OutboxProcessSummary(
            sentCount = sentCount,
            failedCount = failedCount,
            retryPendingCount = retryPendingCount,
            lastError = lastError,
        )
    }

    fun decodeStringList(rawJson: String?): List<String> {
        if (rawJson.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<String>>(rawJson) }.getOrDefault(emptyList())
    }

    fun decodeAttachmentList(rawJson: String?): List<DraftAttachmentRef> {
        if (rawJson.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<DraftAttachmentRef>>(rawJson) }
            .getOrDefault(emptyList())
    }

    private suspend fun resolveSmtpAuth(
        accountId: Long,
        creds: StoredAccountCredentials,
    ): CoreImapAuth = when (creds) {
        is StoredAccountCredentials.Password -> CoreImapAuth.Password(
            username = creds.username,
            password = creds.password,
        )
        is StoredAccountCredentials.OAuth2 -> {
            val refreshed = tokenRefresher.ensureFreshToken(creds.authStateJson, creds.email)
            if (refreshed.updatedAuthStateJson != creds.authStateJson) {
                credentialStore.saveCredentials(
                    accountId,
                    creds.copy(authStateJson = refreshed.updatedAuthStateJson),
                )
            }
            CoreImapAuth.OAuth2(
                email = creds.email,
                accessToken = refreshed.accessToken,
            )
        }
    }

    private fun resolveSmtpSecurity(
        smtpPort: Int,
        storedSecurity: StoredSecurityMode,
    ): StoredSecurityMode =
        when {
            storedSecurity == StoredSecurityMode.PLAINTEXT -> StoredSecurityMode.PLAINTEXT
            smtpPort == 587 || smtpPort == 25 -> StoredSecurityMode.START_TLS
            smtpPort == 465 -> StoredSecurityMode.IMPLICIT_TLS
            else -> storedSecurity
        }

    private fun isPermanentFailure(err: Throwable): Boolean {
        val msg = (err.message ?: "").lowercase()
        val className = err.javaClass.simpleName.lowercase()
        return className.contains("auth") ||
            className.contains("invalidinput") ||
            msg.contains("authfailed") ||
            msg.contains("authentication") ||
            msg.contains("535 ") ||
            msg.contains("534 ") ||
            msg.contains("530 ") ||
            msg.contains("invalid email") ||
            msg.contains("at least one recipient")
    }

    companion object {
        const val DEFAULT_MAX_RETRY_ATTEMPTS = 5
    }
}
