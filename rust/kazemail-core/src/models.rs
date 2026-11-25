use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ImapSecurity {
    ImplicitTls,
    StartTls,
    Plaintext,
}

#[derive(Debug, Clone, uniffi::Enum)]
pub enum ImapAuth {
    Password {
        username: String,
        password: String,
    },
    OAuth2 {
        email: String,
        access_token: String,
    },
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ImapConnectConfig {
    pub host: String,
    pub port: u16,
    pub security: ImapSecurity,
    pub auth: ImapAuth,
    pub timeout_secs: u32,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FolderInfo {
    pub full_path: String,
    pub folder_name: String,
    pub delimiter: Option<String>,
    pub attributes: Vec<String>,
    pub is_selectable: bool,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FolderSyncSnapshot {
    pub full_path: String,
    pub uid_validity: u32,
    pub highest_mod_seq: u64,
    pub exists: u32,
    pub uid_next: Option<u32>,
    pub supports_condstore: bool,
    pub supports_qresync: bool,
    pub supports_idle: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, uniffi::Record)]
pub struct ParsedRecipient {
    pub name: Option<String>,
    pub address: String,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ParsedAttachmentMeta {
    pub part_id: String,
    pub filename: String,
    pub mime_type: String,
    pub size_bytes: u64,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ParsedEmailRecord {
    pub uid: u32,
    pub message_id_header: Option<String>,
    pub in_reply_to: Option<String>,
    pub references: Vec<String>,
    pub thread_key: String,
    pub sender_name: Option<String>,
    pub sender_address: String,
    pub recipients_to_json: String,
    pub subject: Option<String>,
    pub snippet: String,
    pub received_at_ms: i64,
    pub is_read: bool,
    pub is_flagged: bool,
    pub has_attachments: bool,
    pub size_bytes: u64,
    pub plain_text: String,
    pub html_zstd: Option<Vec<u8>>,
    pub raw_headers: String,
    pub is_truncated: bool,
    pub attachments: Vec<ParsedAttachmentMeta>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct UidFlagChange {
    pub uid: u32,
    pub is_read: bool,
    pub is_flagged: bool,
    pub mod_seq: Option<u64>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct IncrementalDelta {
    pub new_uids: Vec<u32>,
    pub flag_changes: Vec<UidFlagChange>,
    pub vanished_uids: Vec<u32>,
    pub snapshot: FolderSyncSnapshot,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct DecodedTextResult {
    pub text: String,
    pub used_fallback: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum SmtpSecurity {
    ImplicitTls,
    StartTls,
    Plaintext,
}

#[derive(Debug, Clone, uniffi::Enum)]
pub enum SmtpAuth {
    Password {
        username: String,
        password: String,
    },
    OAuth2 {
        email: String,
        access_token: String,
    },
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SmtpConnectConfig {
    pub host: String,
    pub port: u16,
    pub security: SmtpSecurity,
    pub auth: SmtpAuth,
    pub timeout_secs: u32,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct OutgoingAttachmentSpec {
    pub filename: String,
    pub mime_type: String,
    pub file_path: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct OutgoingEmailMessage {
    pub from_name: Option<String>,
    pub from_address: String,
    pub to_addresses: Vec<String>,
    pub cc_addresses: Vec<String>,
    pub bcc_addresses: Vec<String>,
    pub subject: String,
    pub plain_text: String,
    pub html_body: Option<String>,
    pub in_reply_to: Option<String>,
    pub references: Vec<String>,
    pub message_id: Option<String>,
    pub attachments: Vec<OutgoingAttachmentSpec>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SmtpSendResult {
    pub message_id: String,
    pub server_response: String,
}
