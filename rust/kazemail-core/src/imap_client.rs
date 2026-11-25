use std::io::{self, Read, Write};
use std::net::{TcpStream, ToSocketAddrs};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use imap::extensions::idle::{self, SetReadTimeout};
use imap::types::{Flag, Mailbox};
use imap::{Authenticator, Client, Session};
use imap_proto::types::{Capability, MessageSection, NameAttribute, SectionPath};
use rustls_connector::{RustlsConnectorConfig, TlsStream};

use crate::bodystructure::{
    decode_part_payload, decode_text_part_bytes, inspect_bodystructure,
    transfer_encoding_for_part_id, transfer_encoding_from_mime_header, BodyStructurePlan,
    PartTransferEncoding, TextPartSpec,
};
use crate::error::KazeCoreError;
use crate::models::{
    FolderInfo, FolderSyncSnapshot, ImapAuth, ImapConnectConfig, ImapSecurity, IncrementalDelta,
    ParsedEmailRecord, UidFlagChange,
};
use crate::parser::{
    parse_header_and_parts, parse_raw_email, GIANT_EMAIL_THRESHOLD_BYTES, MAX_STORED_BODY_BYTES,
};

const FETCH_BATCH_SIZE: usize = 100;
const PARTIAL_BODY_LIMIT_BYTES: usize = MAX_STORED_BODY_BYTES;

#[derive(Debug)]
enum ImapTransport {
    Plain(TcpStream),
    Tls(Box<TlsStream<TcpStream>>),
}

impl Read for ImapTransport {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        match self {
            ImapTransport::Plain(s) => s.read(buf),
            ImapTransport::Tls(s) => s.read(buf),
        }
    }
}

impl Write for ImapTransport {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        match self {
            ImapTransport::Plain(s) => s.write(buf),
            ImapTransport::Tls(s) => s.write(buf),
        }
    }

    fn flush(&mut self) -> io::Result<()> {
        match self {
            ImapTransport::Plain(s) => s.flush(),
            ImapTransport::Tls(s) => s.flush(),
        }
    }
}

impl SetReadTimeout for ImapTransport {
    fn set_read_timeout(&mut self, timeout: Option<Duration>) -> imap::Result<()> {
        match self {
            ImapTransport::Plain(s) => TcpStream::set_read_timeout(s, timeout).map_err(imap::Error::Io),
            ImapTransport::Tls(s) => TcpStream::set_read_timeout(s.get_ref(), timeout).map_err(imap::Error::Io),
        }
    }
}

struct XOAuth2Authenticator {
    email: String,
    access_token: String,
}

impl Authenticator for XOAuth2Authenticator {
    type Response = String;

    fn process(&self, _data: &[u8]) -> Self::Response {
        format!("user={}\x01auth=Bearer {}\x01\x01", self.email, self.access_token)
    }
}

#[derive(Debug, Clone, Default)]
struct SessionCapabilities {
    enable: bool,
    condstore: bool,
    qresync: bool,
    idle: bool,
    enable_sent: bool,
}

struct SessionState {
    session: Option<Session<ImapTransport>>,
    caps: SessionCapabilities,
    examined_folder: Option<String>,
}

/// Non-destructive IMAP client wrapper.
/// Enforces Iron Rule #1: strictly uses EXAMINE + BODY.PEEK and seals off EXPUNGE/CLOSE/DELETE.
#[derive(uniffi::Object)]
pub struct SafeImapClient {
    state: Mutex<SessionState>,
}

/// Protocol-level non-destructive command firewall.
pub fn validate_non_destructive_command(cmd: &str) -> Result<(), KazeCoreError> {
    let upper = cmd.trim().to_ascii_uppercase();
    let forbidden = [
        "EXPUNGE",
        "UID EXPUNGE",
        "CLOSE",
        "DELETE ",
        "\\DELETED",
        "MOVE ",
        "UID MOVE",
    ];
    for pattern in forbidden {
        if upper.contains(pattern) {
            return Err(KazeCoreError::NonDestructiveViolation {
                operation: cmd.to_string(),
            });
        }
    }
    if upper.starts_with("SELECT ") || upper == "SELECT" {
        return Err(KazeCoreError::NonDestructiveViolation {
            operation: "SELECT is forbidden; EXAMINE must be used".to_string(),
        });
    }
    Ok(())
}

#[uniffi::export]
pub fn connect_and_auth(config: ImapConnectConfig) -> Result<Arc<SafeImapClient>, KazeCoreError> {
    if config.host.trim().is_empty() {
        return Err(KazeCoreError::InvalidInput {
            detail: "IMAP host cannot be empty".to_string(),
        });
    }
    if config.port == 0 {
        return Err(KazeCoreError::InvalidInput {
            detail: "IMAP port must be greater than 0".to_string(),
        });
    }

    let timeout = Duration::from_secs(config.timeout_secs.max(10) as u64);
    let tcp = connect_tcp_with_timeout(&config.host, config.port, timeout)?;

    let client = match config.security {
        ImapSecurity::ImplicitTls => {
            let tls = upgrade_tcp_to_tls(&config.host, tcp)?;
            let mut c = Client::new(ImapTransport::Tls(Box::new(tls)));
            c.read_greeting()?;
            c
        }
        ImapSecurity::StartTls => {
            let upgraded_tcp = negotiate_starttls(tcp)?;
            let tls = upgrade_tcp_to_tls(&config.host, upgraded_tcp)?;
            let mut tls_client = Client::new(ImapTransport::Tls(Box::new(tls)));
            tls_client.greeting_read = true;
            tls_client
        }
        ImapSecurity::Plaintext => {
            let mut c = Client::new(ImapTransport::Plain(tcp));
            c.read_greeting()?;
            c
        }
    };

    let mut session = match config.auth {
        ImapAuth::Password { username, password } => client
            .login(username, password)
            .map_err(|(err, _)| KazeCoreError::AuthFailed {
                detail: err.to_string(),
            })?,
        ImapAuth::OAuth2 {
            email,
            access_token,
        } => {
            let auth = XOAuth2Authenticator {
                email,
                access_token,
            };
            client
                .authenticate("XOAUTH2", &auth)
                .map_err(|(err, _)| KazeCoreError::AuthFailed {
                    detail: err.to_string(),
                })?
        }
    };

    let caps = detect_capabilities(&mut session);

    Ok(Arc::new(SafeImapClient {
        state: Mutex::new(SessionState {
            session: Some(session),
            caps,
            examined_folder: None,
        }),
    }))
}

fn negotiate_starttls(mut tcp: TcpStream) -> Result<TcpStream, KazeCoreError> {
    use std::io::BufRead;
    let mut reader = io::BufReader::new(tcp.try_clone()?);
    let mut greeting = String::new();
    reader.read_line(&mut greeting)?;
    if !greeting.starts_with("* ") {
        return Err(KazeCoreError::ProtocolError {
            detail: format!("Unexpected IMAP greeting before STARTTLS: {greeting}"),
        });
    }

    tcp.write_all(b"k0 STARTTLS\r\n")?;
    tcp.flush()?;

    loop {
        let mut line = String::new();
        let read = reader.read_line(&mut line)?;
        if read == 0 {
            return Err(KazeCoreError::ConnectionFailed {
                detail: "Connection closed during STARTTLS negotiation".to_string(),
            });
        }
        if line.starts_with("k0 OK") {
            drop(reader);
            return Ok(tcp);
        }
        if line.starts_with("k0 NO") || line.starts_with("k0 BAD") {
            return Err(KazeCoreError::TlsError {
                detail: format!("STARTTLS rejected by server: {}", line.trim()),
            });
        }
    }
}

pub(crate) fn connect_tcp_with_timeout(
    host: &str,
    port: u16,
    timeout: Duration,
) -> Result<TcpStream, KazeCoreError> {
    let addrs: Vec<_> = (host, port)
        .to_socket_addrs()
        .map_err(|e| KazeCoreError::ConnectionFailed {
            detail: format!("DNS resolution failed for {host}:{port}: {e}"),
        })?
        .collect();
    if addrs.is_empty() {
        return Err(KazeCoreError::ConnectionFailed {
            detail: format!("No addresses resolved for {host}:{port}"),
        });
    }

    let mut last_err = None;
    for addr in addrs {
        match TcpStream::connect_timeout(&addr, timeout) {
            Ok(stream) => {
                stream.set_read_timeout(Some(timeout))?;
                stream.set_write_timeout(Some(timeout))?;
                return Ok(stream);
            }
            Err(e) => last_err = Some(e),
        }
    }

    Err(KazeCoreError::ConnectionFailed {
        detail: format!(
            "Failed to connect to {host}:{port}: {}",
            last_err
                .map(|e| e.to_string())
                .unwrap_or_else(|| "unknown error".to_string())
        ),
    })
}

pub(crate) fn upgrade_tcp_to_tls(
    host: &str,
    tcp: TcpStream,
) -> Result<TlsStream<TcpStream>, KazeCoreError> {
    let connector = RustlsConnectorConfig::new_with_webpki_roots_certs()
        .connector_with_no_client_auth();
    connector
        .connect(host, tcp)
        .map_err(|e| KazeCoreError::TlsError {
            detail: format!("TLS connect failed for {host}: {e}"),
        })
}

fn detect_capabilities(session: &mut Session<ImapTransport>) -> SessionCapabilities {
    let mut result = SessionCapabilities::default();
    if let Ok(caps) = session.capabilities() {
        for cap in caps.iter() {
            if let Capability::Atom(atom) = cap {
                let upper = atom.to_ascii_uppercase();
                match upper.as_str() {
                    "ENABLE" => result.enable = true,
                    "CONDSTORE" => result.condstore = true,
                    "QRESYNC" => result.qresync = true,
                    "IDLE" => result.idle = true,
                    _ => {}
                }
            }
        }
    }
    result
}

#[uniffi::export]
impl SafeImapClient {
    pub fn list_folders(&self) -> Result<Vec<FolderInfo>, KazeCoreError> {
        let mut guard = self.lock_state()?;
        let session = guard.session.as_mut().ok_or_else(|| KazeCoreError::ConnectionFailed {
            detail: "IMAP session already logged out".to_string(),
        })?;

        let names = match session.list(None, Some("*")) {
            Ok(names) => names,
            Err(
                imap::Error::No(_) | imap::Error::Bad(_) | imap::Error::Parse(_),
            ) => session.list(None, Some("%"))?,
            Err(err) => return Err(err.into()),
        };
        let mut out = Vec::with_capacity(names.len());
        for name in names.iter() {
            let full_path = name.name().to_string();
            let delimiter = name.delimiter().map(str::to_string);
            let folder_name = format_imap_folder_display_name(&full_path, delimiter.as_deref());
            let mut is_selectable = true;
            let mut attrs = Vec::with_capacity(name.attributes().len());
            for attr in name.attributes() {
                match attr {
                    NameAttribute::NoSelect => {
                        is_selectable = false;
                        attrs.push("\\Noselect".to_string());
                    }
                    NameAttribute::Extension(ext) => {
                        if ext.eq_ignore_ascii_case("NonExistent")
                            || ext.eq_ignore_ascii_case("\\NonExistent")
                        {
                            is_selectable = false;
                        }
                        attrs.push(ext.to_string());
                    }
                    other => attrs.push(format!("{other:?}")),
                }
            }

            out.push(FolderInfo {
                full_path,
                folder_name,
                delimiter,
                attributes: attrs,
                is_selectable,
            });
        }
        Ok(out)
    }

    pub fn examine_folder(&self, full_path: String) -> Result<FolderSyncSnapshot, KazeCoreError> {
        let mut guard = self.lock_state()?;
        let SessionState {
            session,
            caps,
            examined_folder,
        } = &mut *guard;
        let session = session.as_mut().ok_or_else(|| KazeCoreError::ConnectionFailed {
            detail: "IMAP session already logged out".to_string(),
        })?;
        ensure_condstore_enabled(session, caps);
        let mailbox = session.examine(&full_path)?;
        *examined_folder = Some(full_path.clone());
        Ok(snapshot_from_mailbox(&full_path, &mailbox, caps))
    }

    /// Phase 1 helper: fetches the latest `limit` UIDs in the folder without scanning all UIDs.
    pub fn fetch_latest_uids(
        &self,
        full_path: String,
        limit: u32,
    ) -> Result<Vec<u32>, KazeCoreError> {
        if limit == 0 {
            return Ok(Vec::new());
        }
        let mut guard = self.lock_state()?;
        let mailbox = examine_if_needed(&mut guard, &full_path)?;
        if mailbox.exists == 0 {
            return Ok(Vec::new());
        }
        let start_seq = mailbox.exists.saturating_sub(limit.saturating_sub(1)).max(1);
        let seq_set = format!("{start_seq}:{}", mailbox.exists);
        let session = guard.session.as_mut().unwrap();
        let fetches = session.fetch(&seq_set, "(UID)")?;
        let mut uids: Vec<u32> = fetches.iter().filter_map(|f| f.uid).collect();
        uids.sort_unstable_by(|a, b| b.cmp(a));
        uids.dedup();
        Ok(uids)
    }

    /// Returns all UIDs sorted descending (newest first) for Phase 2 chunked backfill.
    pub fn fetch_all_uids_descending(&self, full_path: String) -> Result<Vec<u32>, KazeCoreError> {
        let mut guard = self.lock_state()?;
        let mailbox = examine_if_needed(&mut guard, &full_path)?;
        if mailbox.exists == 0 {
            return Ok(Vec::new());
        }
        let session = guard.session.as_mut().unwrap();
        let uid_set = session.uid_search("ALL")?;
        let mut uids: Vec<u32> = uid_set.into_iter().collect();
        uids.sort_unstable_by(|a, b| b.cmp(a));
        Ok(uids)
    }

    /// Fetches messages for the given UIDs cleanly:
    /// - Uses EXAMINE + BODY.PEEK (never marks \Seen)
    /// - Inspects BODYSTRUCTURE first so attachments are NOT downloaded during sync
    /// - Protects against >10MB giant emails with partial fetch (<0.524288>)
    pub fn fetch_messages_by_uids(
        &self,
        full_path: String,
        uids: Vec<u32>,
    ) -> Result<Vec<ParsedEmailRecord>, KazeCoreError> {
        if uids.is_empty() {
            return Ok(Vec::new());
        }

        let mut guard = self.lock_state()?;
        let _ = examine_if_needed(&mut guard, &full_path)?;
        let session = guard.session.as_mut().unwrap();

        let mut records = Vec::with_capacity(uids.len());
        for chunk in uids.chunks(FETCH_BATCH_SIZE) {
            let uid_seq = format_uid_set(chunk);
            let meta_fetches = match session
                .uid_fetch(&uid_seq, "(UID FLAGS INTERNALDATE RFC822.SIZE BODYSTRUCTURE)")
            {
                Ok(fetches) => fetches,
                Err(imap::Error::Parse(_) | imap::Error::Bad(_)) => {
                    session.uid_fetch(&uid_seq, "(UID FLAGS INTERNALDATE RFC822.SIZE)")?
                }
                Err(err) => return Err(err.into()),
            };

            let mut full_raw_uids = Vec::new();
            let mut selective_items: Vec<(
                u32,
                bool,
                bool,
                Option<i64>,
                u64,
                BodyStructurePlan,
            )> = Vec::new();
            let mut meta_map: std::collections::HashMap<u32, (bool, bool, Option<i64>, u64)> =
                std::collections::HashMap::new();

            for f in meta_fetches.iter() {
                let Some(uid) = f.uid else {
                    continue;
                };
                let is_read = f.flags().iter().any(|fl| *fl == Flag::Seen);
                let is_flagged = f.flags().iter().any(|fl| *fl == Flag::Flagged);
                let internal_ms = f.internal_date().map(|dt| dt.timestamp_millis());
                let size_bytes = f.size.unwrap_or(0) as u64;
                meta_map.insert(uid, (is_read, is_flagged, internal_ms, size_bytes));

                if let Some(bs) = f.bodystructure() {
                    let plan = inspect_bodystructure(bs);
                    let is_giant = size_bytes > GIANT_EMAIL_THRESHOLD_BYTES;
                    let has_attachments = !plan.attachments.is_empty();
                    if !is_giant && !has_attachments {
                        full_raw_uids.push(uid);
                    } else {
                        selective_items.push((
                            uid,
                            is_read,
                            is_flagged,
                            internal_ms,
                            size_bytes,
                            plan,
                        ));
                    }
                } else if size_bytes <= GIANT_EMAIL_THRESHOLD_BYTES {
                    full_raw_uids.push(uid);
                }
            }

            if !full_raw_uids.is_empty() {
                let seq = format_uid_set(&full_raw_uids);
                let body_fetches =
                    session.uid_fetch(&seq, "(UID FLAGS INTERNALDATE RFC822.SIZE BODY.PEEK[])")?;
                for f in body_fetches.iter() {
                    let Some(uid) = f.uid else {
                        continue;
                    };
                    let Some(raw) = f.body() else {
                        continue;
                    };
                    let (is_read, is_flagged, internal_ms, size_bytes) = meta_map
                        .get(&uid)
                        .copied()
                        .unwrap_or((false, false, None, raw.len() as u64));
                    if let Ok(rec) = parse_raw_email(
                        uid,
                        raw,
                        is_read,
                        is_flagged,
                        internal_ms,
                        Some(size_bytes),
                    ) {
                        records.push(rec);
                    }
                }
            }

            for (uid, is_read, is_flagged, internal_ms, size_bytes, plan) in selective_items {
                if let Ok(rec) = fetch_selective_message(
                    session,
                    uid,
                    is_read,
                    is_flagged,
                    internal_ms,
                    size_bytes,
                    &plan,
                ) {
                    records.push(rec);
                }
            }
        }

        Ok(records)
    }

    /// Incremental sync using CONDSTORE (CHANGEDSINCE) when supported, plus new UIDs above `known_last_uid`.
    pub fn fetch_incremental_delta(
        &self,
        full_path: String,
        known_last_uid: u32,
        known_highest_mod_seq: u64,
    ) -> Result<IncrementalDelta, KazeCoreError> {
        let mut guard = self.lock_state()?;
        let SessionState {
            session,
            caps,
            examined_folder,
        } = &mut *guard;
        let session = session.as_mut().ok_or_else(|| KazeCoreError::ConnectionFailed {
            detail: "IMAP session already logged out".to_string(),
        })?;
        ensure_condstore_enabled(session, caps);

        let mailbox = session.examine(&full_path)?;
        *examined_folder = Some(full_path.clone());
        let snapshot = snapshot_from_mailbox(&full_path, &mailbox, caps);

        let mut new_uids = Vec::new();
        if mailbox.exists > 0 {
            let should_search_new = match mailbox.uid_next {
                Some(next) => next > known_last_uid.saturating_add(1),
                None => true,
            };
            if should_search_new {
                let search_query = if known_last_uid == 0 {
                    "ALL".to_string()
                } else {
                    format!("UID {}:*", known_last_uid.saturating_add(1))
                };
                let found = session.uid_search(&search_query)?;
                new_uids = found
                    .into_iter()
                    .filter(|&uid| uid > known_last_uid)
                    .collect();
                new_uids.sort_unstable_by(|a, b| b.cmp(a));
            }
        }

        let mut flag_changes = Vec::new();
        if caps.condstore && known_highest_mod_seq > 0 && known_last_uid > 0 && mailbox.exists > 0 {
            // Only query flag diffs for already-synced UIDs when server modseq advanced
            if snapshot.highest_mod_seq > known_highest_mod_seq || snapshot.highest_mod_seq == 0 {
                let query = format!(
                    "(UID FLAGS MODSEQ) (CHANGEDSINCE {known_highest_mod_seq})"
                );
                validate_non_destructive_command(&query)?;
                if let Ok(fetches) = session.uid_fetch(format!("1:{known_last_uid}"), &query) {
                    for f in fetches.iter() {
                        if let Some(uid) = f.uid {
                            flag_changes.push(UidFlagChange {
                                uid,
                                is_read: f.flags().iter().any(|fl| *fl == Flag::Seen),
                                is_flagged: f.flags().iter().any(|fl| *fl == Flag::Flagged),
                                mod_seq: f.mod_seq(),
                            });
                        }
                    }
                }
            }
        }

        // Capture any QRESYNC VANISHED UIDs strictly as informational (non-destructive: never delete locally)
        let mut vanished_uids = Vec::new();
        for resp in session.take_all_unsolicited() {
            if let imap::types::UnsolicitedResponse::Vanished { uids, .. } = resp {
                for range in uids {
                    vanished_uids.extend(range);
                }
            }
        }

        Ok(IncrementalDelta {
            new_uids,
            flag_changes,
            vanished_uids,
            snapshot,
        })
    }

    /// On-demand attachment / part download (Requirement 4.2.3).
    pub fn fetch_message_part(
        &self,
        full_path: String,
        uid: u32,
        part_id: String,
        max_bytes: Option<u64>,
    ) -> Result<Vec<u8>, KazeCoreError> {
        let part_nums = parse_part_numbers(&part_id)?;
        let mut guard = self.lock_state()?;
        let _ = examine_if_needed(&mut guard, &full_path)?;
        let session = guard.session.as_mut().unwrap();

        let range_suffix = match max_bytes {
            Some(limit) if limit > 0 => format!("<0.{limit}>"),
            _ => String::new(),
        };
        let mime_query = format!(
            "(UID BODY.PEEK[{part_id}.MIME] BODY.PEEK[{part_id}]{range_suffix})"
        );
        validate_non_destructive_command(&mime_query)?;

        let mut mime_header_bytes: Option<Vec<u8>> = None;
        let mut raw_payload_bytes: Option<Vec<u8>> = None;

        if let Ok(fetches) = session.uid_fetch(uid.to_string(), &mime_query) {
            for f in fetches.iter() {
                if f.uid.is_some_and(|u| u != uid) {
                    continue;
                }
                if mime_header_bytes.is_none() {
                    mime_header_bytes = f
                        .section(&SectionPath::Part(
                            part_nums.clone(),
                            Some(MessageSection::Mime),
                        ))
                        .map(|b| b.to_vec());
                }
                if raw_payload_bytes.is_none() {
                    raw_payload_bytes = f
                        .section(&SectionPath::Part(part_nums.clone(), None))
                        .map(|b| b.to_vec());
                }
            }
        }

        // Some servers (or single-part messages with part_id "1") reject `<part>.MIME`.
        // Fall back to fetching BODY.PEEK[<part>] directly (or BODY.PEEK[HEADER] + BODY.PEEK[TEXT]).
        if raw_payload_bytes.is_none() {
            let direct_query = if part_nums == [1] {
                format!("(UID BODY.PEEK[HEADER] BODY.PEEK[{part_id}]{range_suffix})")
            } else {
                format!("(UID BODY.PEEK[{part_id}]{range_suffix})")
            };
            validate_non_destructive_command(&direct_query)?;
            let fetches = session.uid_fetch(uid.to_string(), &direct_query)?;
            for f in fetches.iter() {
                if f.uid.is_some_and(|u| u != uid) {
                    continue;
                }
                if mime_header_bytes.is_none() && part_nums == [1] {
                    mime_header_bytes = f.header().map(|b| b.to_vec());
                }
                if raw_payload_bytes.is_none() {
                    raw_payload_bytes = f
                        .section(&SectionPath::Part(part_nums.clone(), None))
                        .or_else(|| if part_nums == [1] { f.text() } else { None })
                        .map(|b| b.to_vec());
                }
            }
        }

        let raw_payload = raw_payload_bytes.ok_or_else(|| KazeCoreError::ProtocolError {
            detail: format!("Part payload {part_id} missing for UID {uid}"),
        })?;

        let encoding = if let Some(ref hdr) = mime_header_bytes {
            transfer_encoding_from_mime_header(hdr)
        } else {
            let bs_query = "(UID BODYSTRUCTURE)";
            validate_non_destructive_command(bs_query)?;
            let mut from_bs = None;
            if let Ok(bs_fetches) = session.uid_fetch(uid.to_string(), bs_query) {
                for f in bs_fetches.iter() {
                    if f.uid.is_some_and(|u| u != uid) {
                        continue;
                    }
                    if let Some(bs) = f.bodystructure() {
                        from_bs = transfer_encoding_for_part_id(bs, &part_id);
                        if from_bs.is_some() {
                            break;
                        }
                    }
                }
            }
            from_bs.unwrap_or(PartTransferEncoding::None)
        };

        Ok(decode_part_payload(&raw_payload, encoding))
    }

    /// Waits for mailbox updates via IMAP IDLE with bounded timeout (battery efficiency).
    pub fn wait_for_idle_event(
        &self,
        full_path: String,
        timeout_secs: u32,
    ) -> Result<bool, KazeCoreError> {
        let mut guard = self.lock_state()?;
        if !guard.caps.idle {
            return Ok(false);
        }
        let _ = examine_if_needed(&mut guard, &full_path)?;
        let session = guard.session.as_mut().unwrap();
        let mut handle = session.idle();
        handle.keepalive(false);
        handle.timeout(Duration::from_secs(timeout_secs.clamp(5, 29 * 60) as u64));
        match handle.wait_while(idle::stop_on_any)? {
            idle::WaitOutcome::MailboxChanged => Ok(true),
            idle::WaitOutcome::TimedOut => Ok(false),
        }
    }

    pub fn logout(&self) -> Result<(), KazeCoreError> {
        let mut guard = self.lock_state()?;
        if let Some(mut session) = guard.session.take() {
            // Never call CLOSE; LOGOUT exits cleanly without implicit EXPUNGE.
            let _ = session.logout();
        }
        guard.examined_folder = None;
        Ok(())
    }
}

impl SafeImapClient {
    fn lock_state(&self) -> Result<std::sync::MutexGuard<'_, SessionState>, KazeCoreError> {
        self.state.lock().map_err(|_| KazeCoreError::ProtocolError {
            detail: "IMAP client mutex poisoned".to_string(),
        })
    }
}

fn ensure_condstore_enabled(session: &mut Session<ImapTransport>, caps: &mut SessionCapabilities) {
    if caps.enable_sent {
        return;
    }
    caps.enable_sent = true;
    if !caps.enable {
        return;
    }
    if caps.qresync {
        let _ = session.run_command_and_read_response("ENABLE QRESYNC CONDSTORE");
    } else if caps.condstore {
        let _ = session.run_command_and_read_response("ENABLE CONDSTORE");
    }
}

fn examine_if_needed(
    guard: &mut SessionState,
    full_path: &str,
) -> Result<Mailbox, KazeCoreError> {
    let session = guard.session.as_mut().ok_or_else(|| KazeCoreError::ConnectionFailed {
        detail: "IMAP session already logged out".to_string(),
    })?;
    let mailbox = session.examine(full_path)?;
    guard.examined_folder = Some(full_path.to_string());
    Ok(mailbox)
}

fn snapshot_from_mailbox(
    full_path: &str,
    mailbox: &Mailbox,
    caps: &SessionCapabilities,
) -> FolderSyncSnapshot {
    FolderSyncSnapshot {
        full_path: full_path.to_string(),
        uid_validity: mailbox.uid_validity.unwrap_or(0),
        highest_mod_seq: mailbox.highest_mod_seq.unwrap_or(0),
        exists: mailbox.exists,
        uid_next: mailbox.uid_next,
        supports_condstore: caps.condstore,
        supports_qresync: caps.qresync,
        supports_idle: caps.idle,
    }
}

fn fetch_selective_message(
    session: &mut Session<ImapTransport>,
    uid: u32,
    is_read: bool,
    is_flagged: bool,
    internal_ms: Option<i64>,
    size_bytes: u64,
    plan: &BodyStructurePlan,
) -> Result<ParsedEmailRecord, KazeCoreError> {
    let mut query_items = vec!["UID".to_string(), "BODY.PEEK[HEADER]".to_string()];
    let mut pre_truncated = size_bytes > GIANT_EMAIL_THRESHOLD_BYTES;

    let mut fetch_part_tokens = |specs: &[TextPartSpec]| {
        for spec in specs.iter().take(4) {
            if spec.octets as usize > PARTIAL_BODY_LIMIT_BYTES {
                pre_truncated = true;
                query_items.push(format!(
                    "BODY.PEEK[{}]<0.{PARTIAL_BODY_LIMIT_BYTES}>",
                    spec.part_id
                ));
            } else {
                query_items.push(format!("BODY.PEEK[{}]", spec.part_id));
            }
        }
    };

    fetch_part_tokens(&plan.text_parts);
    fetch_part_tokens(&plan.html_parts);

    let query = format!("({})", query_items.join(" "));
    validate_non_destructive_command(&query)?;
    let fetches = session.uid_fetch(uid.to_string(), &query)?;
    let fetch = fetches.iter().next().ok_or_else(|| KazeCoreError::ProtocolError {
        detail: format!("Selective fetch returned no data for UID {uid}"),
    })?;

    let header_bytes = fetch.header().unwrap_or_default();

    let mut plain_bodies = Vec::new();
    for spec in plan.text_parts.iter().take(4) {
        if let Ok(nums) = parse_part_numbers(&spec.part_id) {
            if let Some(raw) = fetch.section(&SectionPath::Part(nums, None)) {
                plain_bodies.push(decode_text_part_bytes(
                    raw,
                    spec.transfer_encoding,
                    spec.charset.as_deref(),
                ));
            }
        }
    }

    let mut html_bodies = Vec::new();
    for spec in plan.html_parts.iter().take(4) {
        if let Ok(nums) = parse_part_numbers(&spec.part_id) {
            if let Some(raw) = fetch.section(&SectionPath::Part(nums, None)) {
                html_bodies.push(decode_text_part_bytes(
                    raw,
                    spec.transfer_encoding,
                    spec.charset.as_deref(),
                ));
            }
        }
    }

    parse_header_and_parts(
        uid,
        header_bytes,
        plain_bodies,
        html_bodies,
        plan.attachments.clone(),
        is_read,
        is_flagged,
        internal_ms,
        size_bytes,
        pre_truncated,
    )
}

fn parse_part_numbers(part_id: &str) -> Result<Vec<u32>, KazeCoreError> {
    let nums: Result<Vec<u32>, _> = part_id
        .split('.')
        .filter(|s| !s.is_empty())
        .map(|s| s.parse::<u32>())
        .collect();
    let parsed = nums.map_err(|_| KazeCoreError::InvalidInput {
        detail: format!("Invalid IMAP part_id: {part_id}"),
    })?;
    if parsed.is_empty() {
        return Err(KazeCoreError::InvalidInput {
            detail: "Empty IMAP part_id".to_string(),
        });
    }
    Ok(parsed)
}

/// Formats sorted or unsorted UIDs into compact IMAP sequence ranges (e.g. `1:3,7,10:12`).
pub fn format_uid_set(uids: &[u32]) -> String {
    if uids.is_empty() {
        return String::new();
    }
    let mut sorted = uids.to_vec();
    sorted.sort_unstable();
    sorted.dedup();

    let mut parts = Vec::new();
    let mut start = sorted[0];
    let mut prev = sorted[0];
    for &u in &sorted[1..] {
        if u == prev + 1 {
            prev = u;
        } else {
            if start == prev {
                parts.push(start.to_string());
            } else {
                parts.push(format!("{start}:{prev}"));
            }
            start = u;
            prev = u;
        }
    }
    if start == prev {
        parts.push(start.to_string());
    } else {
        parts.push(format!("{start}:{prev}"));
    }
    parts.join(",")
}

fn unescape_imap_quoted(raw: &str) -> String {
    let mut out = String::with_capacity(raw.len());
    let mut chars = raw.chars();
    while let Some(ch) = chars.next() {
        if ch == '\\' {
            if let Some(next) = chars.next() {
                out.push(next);
            } else {
                out.push('\\');
            }
        } else {
            out.push(ch);
        }
    }
    out
}

/// Decodes RFC 3501 Section 5.1.3 Modified UTF-7 mailbox strings into UTF-8.
pub(crate) fn decode_imap_mutf7(input: &str) -> String {
    fn mutf7_val(b: u8) -> Option<u32> {
        match b {
            b'A'..=b'Z' => Some((b - b'A') as u32),
            b'a'..=b'z' => Some((b - b'a' + 26) as u32),
            b'0'..=b'9' => Some((b - b'0' + 52) as u32),
            b'+' => Some(62),
            b',' => Some(63),
            _ => None,
        }
    }

    fn decode_mutf7_block(b64: &[u8]) -> Option<String> {
        if b64.is_empty() {
            return Some(String::new());
        }
        let mut buf: u32 = 0;
        let mut bits: u32 = 0;
        let mut raw_bytes = Vec::with_capacity((b64.len() * 3) / 4 + 1);
        for &ch in b64 {
            let val = mutf7_val(ch)?;
            buf = (buf << 6) | val;
            bits += 6;
            if bits >= 8 {
                bits -= 8;
                raw_bytes.push(((buf >> bits) & 0xFF) as u8);
            }
        }
        if raw_bytes.is_empty() || raw_bytes.len() % 2 != 0 {
            return None;
        }
        let units: Vec<u16> = raw_bytes
            .chunks_exact(2)
            .map(|c| u16::from_be_bytes([c[0], c[1]]))
            .collect();
        String::from_utf16(&units).ok()
    }

    let bytes = input.as_bytes();
    let mut out = String::with_capacity(input.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'&' {
            if let Some(rel_end) = bytes[i + 1..].iter().position(|&b| b == b'-') {
                let end = i + 1 + rel_end;
                if end == i + 1 {
                    out.push('&');
                    i = end + 1;
                    continue;
                }
                if let Some(decoded) = decode_mutf7_block(&bytes[i + 1..end]) {
                    out.push_str(&decoded);
                    i = end + 1;
                    continue;
                }
            }
            out.push('&');
            i += 1;
        } else if let Some(ch) = input[i..].chars().next() {
            out.push(ch);
            i += ch.len_utf8();
        } else {
            break;
        }
    }
    out
}

/// Formats an IMAP mailbox path for UI display while keeping raw `full_path` untouched for wire commands.
pub(crate) fn format_imap_folder_display_name(full_path: &str, delimiter: Option<&str>) -> String {
    let raw_segments: Vec<&str> = match delimiter {
        Some(delim) if !delim.is_empty() => full_path.split(delim).collect(),
        _ => vec![full_path],
    };

    let mut decoded_segments: Vec<String> = raw_segments
        .into_iter()
        .map(unescape_imap_quoted)
        .map(|s| decode_imap_mutf7(&s))
        .filter(|s| !s.is_empty())
        .collect();

    if decoded_segments.len() >= 2 {
        let first = &decoded_segments[0];
        if first.eq_ignore_ascii_case("[Gmail]")
            || first.eq_ignore_ascii_case("[Google Mail]")
            || first.eq_ignore_ascii_case("INBOX")
        {
            decoded_segments.remove(0);
        }
    }

    if decoded_segments.is_empty() {
        decode_imap_mutf7(&unescape_imap_quoted(full_path))
    } else {
        decoded_segments.join(" / ")
    }
}
