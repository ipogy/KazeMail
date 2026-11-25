package com.kazemail.app.data.db

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Fts5
import androidx.room3.FtsOptions
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "accounts",
    indices = [
        Index(value = ["email_address"], unique = true),
    ],
)
data class AccountEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "account_id")
    val accountId: Long = 0,
    @ColumnInfo(name = "email_address")
    val emailAddress: String,
    @ColumnInfo(name = "display_name")
    val displayName: String? = null,
    @ColumnInfo(name = "imap_host")
    val imapHost: String,
    @ColumnInfo(name = "imap_port")
    val imapPort: Int = 993,
    @ColumnInfo(name = "smtp_host")
    val smtpHost: String,
    @ColumnInfo(name = "smtp_port")
    val smtpPort: Int = 465,
    @ColumnInfo(name = "auth_type")
    val authType: String, // "PASSWORD" or "OAUTH2"
    @ColumnInfo(name = "last_sync_at")
    val lastSyncAt: Long? = null,
    @ColumnInfo(name = "signature_enabled", defaultValue = "0")
    val signatureEnabled: Boolean = false,
    @ColumnInfo(name = "signature_text", defaultValue = "''")
    val signatureText: String = "",
    @ColumnInfo(name = "signature_configured", defaultValue = "0")
    val signatureConfigured: Boolean = false,
)

@Entity(
    tableName = "folders",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["account_id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["account_id", "full_path"], unique = true),
    ],
)
data class FolderEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "folder_id")
    val folderId: Long = 0,
    @ColumnInfo(name = "account_id")
    val accountId: Long,
    @ColumnInfo(name = "folder_name")
    val folderName: String,
    @ColumnInfo(name = "full_path")
    val fullPath: String,
    @ColumnInfo(name = "uid_validity")
    val uidValidity: Long,
    @ColumnInfo(name = "highest_mod_seq")
    val highestModSeq: Long = 0,
    @ColumnInfo(name = "last_synced_uid")
    val lastSyncedUid: Long = 0,
    @ColumnInfo(name = "total_count")
    val totalCount: Int = 0,
    @ColumnInfo(name = "unread_count")
    val unreadCount: Int = 0,
)

@Entity(
    tableName = "messages_meta",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["folder_id"],
            childColumns = ["folder_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["folder_id", "uid"], unique = true),
        Index(
            name = "idx_meta_folder_date",
            value = ["folder_id", "received_at"],
            orders = [Index.Order.ASC, Index.Order.DESC],
        ),
        Index(
            name = "idx_meta_thread",
            value = ["thread_id", "received_at"],
            orders = [Index.Order.ASC, Index.Order.ASC],
        ),
        Index(
            name = "idx_meta_account",
            value = ["account_id"],
        ),
        Index(
            name = "idx_meta_folder_thread_date",
            value = ["folder_id", "thread_id", "received_at"],
            orders = [Index.Order.ASC, Index.Order.ASC, Index.Order.DESC],
        ),
        Index(
            name = "idx_meta_account_thread_date",
            value = ["account_id", "thread_id", "received_at"],
            orders = [Index.Order.ASC, Index.Order.ASC, Index.Order.ASC],
        ),
        Index(
            name = "idx_meta_folder_read",
            value = ["folder_id", "is_read"],
        ),
    ],
)
data class MessageMetaEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "account_id")
    val accountId: Long,
    @ColumnInfo(name = "folder_id")
    val folderId: Long,
    @ColumnInfo(name = "uid")
    val uid: Long,
    @ColumnInfo(name = "message_id_header")
    val messageIdHeader: String? = null,
    @ColumnInfo(name = "in_reply_to")
    val inReplyTo: String? = null,
    @ColumnInfo(name = "thread_id")
    val threadId: String? = null,
    @ColumnInfo(name = "sender_name")
    val senderName: String? = null,
    @ColumnInfo(name = "sender_address")
    val senderAddress: String,
    @ColumnInfo(name = "recipient_to")
    val recipientTo: String? = null,
    @ColumnInfo(name = "subject")
    val subject: String? = null,
    @ColumnInfo(name = "snippet")
    val snippet: String? = null,
    @ColumnInfo(name = "received_at")
    val receivedAt: Long,
    @ColumnInfo(name = "is_read")
    val isRead: Boolean = false,
    @ColumnInfo(name = "is_flagged")
    val isFlagged: Boolean = false,
    @ColumnInfo(name = "has_attachments")
    val hasAttachments: Boolean = false,
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long = 0,
)

@Entity(
    tableName = "messages_body",
    foreignKeys = [
        ForeignKey(
            entity = MessageMetaEntity::class,
            parentColumns = ["id"],
            childColumns = ["meta_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class MessageBodyEntity(
    @PrimaryKey
    @ColumnInfo(name = "meta_id")
    val metaId: Long,
    @ColumnInfo(name = "plain_text")
    val plainText: String? = null,
    @ColumnInfo(name = "html_zstd")
    val htmlZstd: ByteArray? = null,
    @ColumnInfo(name = "raw_headers")
    val rawHeaders: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessageBodyEntity) return false
        return metaId == other.metaId &&
            plainText == other.plainText &&
            htmlZstd.contentEquals(other.htmlZstd) &&
            rawHeaders == other.rawHeaders
    }

    override fun hashCode(): Int {
        var result = metaId.hashCode()
        result = 31 * result + (plainText?.hashCode() ?: 0)
        result = 31 * result + (htmlZstd?.contentHashCode() ?: 0)
        result = 31 * result + (rawHeaders?.hashCode() ?: 0)
        return result
    }
}

@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = MessageMetaEntity::class,
            parentColumns = ["id"],
            childColumns = ["meta_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["meta_id"]),
    ],
)
data class AttachmentEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "attachment_id")
    val attachmentId: Long = 0,
    @ColumnInfo(name = "meta_id")
    val metaId: Long,
    @ColumnInfo(name = "part_id")
    val partId: String,
    @ColumnInfo(name = "filename")
    val filename: String,
    @ColumnInfo(name = "mime_type")
    val mimeType: String,
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,
    @ColumnInfo(name = "local_file_path")
    val localFilePath: String? = null,
)

@Fts5(
    tokenizer = FtsOptions.TOKENIZER_TRIGRAM,
    notIndexed = ["meta_id"],
)
@Entity(tableName = "messages_fts")
data class MessageFtsEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowId: Long = 0,
    @ColumnInfo(name = "meta_id")
    val metaId: Long,
    @ColumnInfo(name = "sender_name")
    val senderName: String?,
    @ColumnInfo(name = "sender_address")
    val senderAddress: String,
    @ColumnInfo(name = "subject")
    val subject: String?,
    @ColumnInfo(name = "plain_text")
    val plainText: String?,
)

@Entity(
    tableName = "folder_sync_state",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["folder_id"],
            childColumns = ["folder_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class FolderSyncStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "folder_id")
    val folderId: Long,
    @ColumnInfo(name = "phase1_completed")
    val phase1Completed: Boolean = false,
    @ColumnInfo(name = "backfill_completed")
    val backfillCompleted: Boolean = false,
    @ColumnInfo(name = "backfill_next_max_uid")
    val backfillNextMaxUid: Long = 0,
    @ColumnInfo(name = "supports_condstore")
    val supportsCondstore: Boolean = false,
    @ColumnInfo(name = "supports_qresync")
    val supportsQresync: Boolean = false,
    @ColumnInfo(name = "supports_idle")
    val supportsIdle: Boolean = false,
    @ColumnInfo(name = "last_error")
    val lastError: String? = null,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = 0,
)

@Entity(
    tableName = "account_secrets",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["account_id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class AccountSecretEntity(
    @PrimaryKey
    @ColumnInfo(name = "account_id")
    val accountId: Long,
    @ColumnInfo(name = "encrypted_payload_b64")
    val encryptedPayloadB64: String,
    @ColumnInfo(name = "iv_b64")
    val ivB64: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "message_sync_flags",
    foreignKeys = [
        ForeignKey(
            entity = MessageMetaEntity::class,
            parentColumns = ["id"],
            childColumns = ["meta_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class MessageSyncFlagsEntity(
    @PrimaryKey
    @ColumnInfo(name = "meta_id")
    val metaId: Long,
    @ColumnInfo(name = "is_body_truncated")
    val isBodyTruncated: Boolean = false,
)

@Entity(
    tableName = "message_local_state",
    foreignKeys = [
        ForeignKey(
            entity = MessageMetaEntity::class,
            parentColumns = ["id"],
            childColumns = ["meta_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["is_archived", "archived_at"]),
        Index(value = ["local_category"]),
    ],
)
data class MessageLocalStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "meta_id")
    val metaId: Long,
    @ColumnInfo(name = "is_archived")
    val isArchived: Boolean = false,
    @ColumnInfo(name = "archived_at")
    val archivedAt: Long? = null,
    @ColumnInfo(name = "local_category")
    val localCategory: String? = null,
    @ColumnInfo(name = "hidden_from_folder", defaultValue = "0")
    val hiddenFromFolder: Boolean = false,
)

@Entity(
    tableName = "local_filter_rules",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["account_id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["account_id", "priority"]),
    ],
)
data class LocalFilterRuleEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "rule_id")
    val ruleId: Long = 0,
    @ColumnInfo(name = "account_id")
    val accountId: Long,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "is_enabled")
    val isEnabled: Boolean = true,
    @ColumnInfo(name = "priority")
    val priority: Int = 0,
    @ColumnInfo(name = "match_mode")
    val matchMode: String = MATCH_MODE_ALL,
    @ColumnInfo(name = "conditions_json")
    val conditionsJson: String = "[]",
    @ColumnInfo(name = "target_local_folder")
    val targetLocalFolder: String? = null,
    @ColumnInfo(name = "hide_from_folder")
    val hideFromFolder: Boolean = true,
    @ColumnInfo(name = "archive_locally")
    val archiveLocally: Boolean = false,
    @ColumnInfo(name = "mark_read")
    val markRead: Boolean = false,
    @ColumnInfo(name = "mark_flagged")
    val markFlagged: Boolean = false,
    @ColumnInfo(name = "suppress_notification")
    val suppressNotification: Boolean = false,
    @ColumnInfo(name = "stop_processing")
    val stopProcessing: Boolean = true,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = 0,
) {
    companion object {
        const val MATCH_MODE_ALL = "ALL"
        const val MATCH_MODE_ANY = "ANY"
    }
}

@kotlinx.serialization.Serializable
data class DraftAttachmentRef(
    val filePath: String,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
)

@Entity(
    tableName = "drafts",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["account_id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["account_id", "updated_at"]),
    ],
)
data class DraftEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "draft_id")
    val draftId: Long = 0,
    @ColumnInfo(name = "account_id")
    val accountId: Long,
    @ColumnInfo(name = "reply_to_meta_id")
    val replyToMetaId: Long? = null,
    @ColumnInfo(name = "mode")
    val mode: String = "NEW", // "NEW", "REPLY", "REPLY_ALL", "FORWARD"
    @ColumnInfo(name = "to_addresses")
    val toAddressesJson: String = "[]",
    @ColumnInfo(name = "cc_addresses")
    val ccAddressesJson: String = "[]",
    @ColumnInfo(name = "bcc_addresses")
    val bccAddressesJson: String = "[]",
    @ColumnInfo(name = "subject")
    val subject: String = "",
    @ColumnInfo(name = "body_text")
    val bodyText: String = "",
    @ColumnInfo(name = "is_html", defaultValue = "0")
    val isHtml: Boolean = false,
    @ColumnInfo(name = "body_html")
    val bodyHtml: String? = null,
    @ColumnInfo(name = "in_reply_to")
    val inReplyTo: String? = null,
    @ColumnInfo(name = "references_json")
    val referencesJson: String? = null,
    @ColumnInfo(name = "attachments_json")
    val attachmentsJson: String = "[]",
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "outbox",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["account_id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["account_id", "status", "updated_at"]),
    ],
)
data class OutboxMessageEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "outbox_id")
    val outboxId: Long = 0,
    @ColumnInfo(name = "account_id")
    val accountId: Long,
    @ColumnInfo(name = "draft_id")
    val draftId: Long? = null,
    @ColumnInfo(name = "from_email")
    val fromEmail: String,
    @ColumnInfo(name = "from_name")
    val fromName: String? = null,
    @ColumnInfo(name = "to_addresses")
    val toAddressesJson: String,
    @ColumnInfo(name = "cc_addresses")
    val ccAddressesJson: String = "[]",
    @ColumnInfo(name = "bcc_addresses")
    val bccAddressesJson: String = "[]",
    @ColumnInfo(name = "subject")
    val subject: String,
    @ColumnInfo(name = "body_text")
    val bodyText: String,
    @ColumnInfo(name = "body_html")
    val bodyHtml: String? = null,
    @ColumnInfo(name = "in_reply_to")
    val inReplyTo: String? = null,
    @ColumnInfo(name = "references_json")
    val referencesJson: String? = null,
    @ColumnInfo(name = "message_id_header")
    val messageIdHeader: String? = null,
    @ColumnInfo(name = "attachments_json")
    val attachmentsJson: String = "[]",
    @ColumnInfo(name = "status")
    val status: String = STATUS_QUEUED, // "QUEUED", "SENDING", "SENT", "FAILED"
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int = 0,
    @ColumnInfo(name = "last_error")
    val lastError: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "sent_at")
    val sentAt: Long? = null,
) {
    companion object {
        const val STATUS_QUEUED = "QUEUED"
        const val STATUS_SENDING = "SENDING"
        const val STATUS_SENT = "SENT"
        const val STATUS_FAILED = "FAILED"
    }
}

@Entity(
    tableName = "ai_provider_profiles",
    indices = [
        Index(value = ["is_default", "updated_at"]),
    ],
)
data class AiProviderProfileEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "profile_id")
    val profileId: Long = 0,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "provider_type")
    val providerType: String,
    @ColumnInfo(name = "base_url")
    val baseUrl: String,
    @ColumnInfo(name = "model_id")
    val modelId: String,
    @ColumnInfo(name = "encrypted_api_key_b64")
    val encryptedApiKeyB64: String? = null,
    @ColumnInfo(name = "api_key_iv_b64")
    val apiKeyIvB64: String? = null,
    @ColumnInfo(name = "api_key_hint")
    val apiKeyHint: String? = null,
    @ColumnInfo(name = "is_default")
    val isDefault: Boolean = false,
    @ColumnInfo(name = "is_enabled")
    val isEnabled: Boolean = true,
    @ColumnInfo(name = "last_test_status")
    val lastTestStatus: String = "UNTESTED",
    @ColumnInfo(name = "last_test_message")
    val lastTestMessage: String? = null,
    @ColumnInfo(name = "last_tested_at")
    val lastTestedAt: Long? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "ai_writing_presets",
    indices = [
        Index(value = ["sort_order", "preset_id"]),
    ],
)
data class AiWritingPresetEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "preset_id")
    val presetId: Long = 0,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "goal")
    val goal: String,
    @ColumnInfo(name = "tone")
    val tone: String,
    @ColumnInfo(name = "instruction_prompt")
    val instructionPrompt: String,
    @ColumnInfo(name = "is_built_in")
    val isBuiltIn: Boolean = false,
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
