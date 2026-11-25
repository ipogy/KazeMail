uniffi::setup_scaffolding!();

pub mod bodystructure;
pub mod compression;
pub mod encoding;
pub mod error;
pub mod imap_client;
pub mod models;
pub mod parser;
pub mod smtp_client;

use encoding::decode_bytes_with_japanese_fallback;
use error::KazeCoreError;
use models::{
    DecodedTextResult, OutgoingEmailMessage, ParsedEmailRecord, SmtpConnectConfig, SmtpSendResult,
};

#[uniffi::export]
pub fn core_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

#[uniffi::export]
pub fn compress_html_zstd(html: String) -> Result<Vec<u8>, KazeCoreError> {
    compression::compress_zstd(html.as_bytes())
}

#[uniffi::export]
pub fn decompress_html_zstd(
    compressed: Vec<u8>,
    max_output_bytes: u64,
) -> Result<String, KazeCoreError> {
    let bytes = compression::decompress_zstd(&compressed, max_output_bytes as usize)?;
    Ok(String::from_utf8_lossy(&bytes).into_owned())
}

#[uniffi::export]
pub fn parse_eml_bytes(
    uid: u32,
    raw_bytes: Vec<u8>,
    is_read: bool,
    is_flagged: bool,
    internal_date_ms: Option<i64>,
) -> Result<ParsedEmailRecord, KazeCoreError> {
    parser::parse_raw_email(uid, &raw_bytes, is_read, is_flagged, internal_date_ms, None)
}

#[uniffi::export]
pub fn decode_legacy_bytes(
    raw_bytes: Vec<u8>,
    declared_charset: Option<String>,
) -> DecodedTextResult {
    let (text, used_fallback) =
        decode_bytes_with_japanese_fallback(&raw_bytes, declared_charset.as_deref());
    DecodedTextResult { text, used_fallback }
}

#[uniffi::export]
pub fn send_smtp_message(
    config: SmtpConnectConfig,
    message: OutgoingEmailMessage,
) -> Result<SmtpSendResult, KazeCoreError> {
    smtp_client::send_smtp_message(config, message)
}

#[uniffi::export]
pub fn build_rfc5322_bytes(message: OutgoingEmailMessage) -> Result<Vec<u8>, KazeCoreError> {
    let msg_id = smtp_client::ensure_message_id(
        message.message_id.as_deref(),
        &message.from_address,
    );
    smtp_client::build_rfc5322_message(&message, &msg_id)
}

#[cfg(test)]
mod tests {
    use super::*;
    use encoding_rs::{ISO_2022_JP, SHIFT_JIS};
    use imap_client::{connect_and_auth, format_uid_set, validate_non_destructive_command};
    use models::{ImapAuth, ImapConnectConfig, ImapSecurity};
    use std::io::{BufRead, BufReader, Write};
    use std::net::TcpListener;
    use std::thread;

    #[test]
    fn zstd_roundtrip_reduces_html_size() {
        let html = "<html><body><div>".repeat(120)
            + "こんにちは KazeMail テストメール本文です。"
            + &"</div></body></html>".repeat(120);
        let compressed = compress_html_zstd(html.clone()).expect("compress");
        assert!(compressed.len() < html.len() / 3);
        let restored = decompress_html_zstd(compressed, 1024 * 1024).expect("decompress");
        assert_eq!(restored, html);
    }

    #[test]
    fn legacy_japanese_encodings_recover_without_panic() {
        let sample = "お疲れ様です。明日10時に会議室Aでお待ちしております。㈱テスト";
        let (sjis_bytes, _, _) = SHIFT_JIS.encode(sample);
        let decoded_sjis = decode_legacy_bytes(sjis_bytes.to_vec(), None);
        assert!(decoded_sjis.text.contains("お疲れ様です"));
        assert!(decoded_sjis.text.contains("㈱テスト"));

        let (jis_bytes, _, _) = ISO_2022_JP.encode("日本語JISテストメール");
        // Corrupt the trailing ESC(B reset sequence to simulate broken JIS mail
        let broken_jis = jis_bytes[..jis_bytes.len().saturating_sub(3)].to_vec();
        let decoded_jis =
            decode_legacy_bytes(broken_jis, Some("iso-2022-jp".to_string()));
        assert!(decoded_jis.text.contains("日本語"));
    }

    #[test]
    fn giant_body_is_truncated_at_500kb_and_snippet_at_80_chars() {
        let long_text = "あ".repeat(250_000); // 750KB UTF-8
        let raw = format!(
            "From: Alice <alice@example.com>\r\n\
             To: Bob <bob@example.com>\r\n\
             Subject: Giant Mail\r\n\
             Message-ID: <giant-1@example.com>\r\n\
             Content-Type: text/plain; charset=utf-8\r\n\r\n\
             {long_text}"
        );
        let parsed = parse_eml_bytes(42, raw.into_bytes(), false, true, Some(1_700_000_000_000))
            .expect("parse");
        assert!(parsed.is_truncated);
        assert!(parsed.plain_text.len() <= parser::MAX_STORED_BODY_BYTES);
        assert_eq!(parsed.snippet.chars().count(), 80);
        assert_eq!(parsed.thread_key, "mid:giant-1@example.com");
    }

    #[test]
    fn non_destructive_guardrail_blocks_expunge_and_select() {
        assert!(validate_non_destructive_command("EXPUNGE").is_err());
        assert!(validate_non_destructive_command("UID EXPUNGE 1:5").is_err());
        assert!(validate_non_destructive_command("CLOSE").is_err());
        assert!(validate_non_destructive_command("SELECT INBOX").is_err());
        assert!(validate_non_destructive_command("UID STORE 1 +FLAGS (\\Deleted)").is_err());
        assert!(validate_non_destructive_command("EXAMINE INBOX").is_ok());
        assert!(validate_non_destructive_command("UID FETCH 1:10 (BODY.PEEK[])").is_ok());
    }

    #[test]
    fn uid_set_compaction_formats_ranges() {
        assert_eq!(format_uid_set(&[10, 1, 2, 3, 7, 11, 12]), "1:3,7,10:12");
    }

    #[test]
    fn mock_imap_server_enforces_examine_xoauth2_condstore_and_bodystructure_skip() {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind loopback");
        let port = listener.local_addr().unwrap().port();

        let server_thread = thread::spawn(move || {
            let (mut stream, _) = listener.accept().expect("accept");
            stream
                .write_all(b"* OK [CAPABILITY IMAP4rev1 AUTH=XOAUTH2 CONDSTORE IDLE] KazeMock ready\r\n")
                .unwrap();

            let mut reader = BufReader::new(stream.try_clone().unwrap());
            let mut commands = Vec::new();
            loop {
                let mut line = String::new();
                if reader.read_line(&mut line).unwrap() == 0 {
                    break;
                }
                let trimmed = line.trim_end_matches(&['\r', '\n'][..]).to_string();
                commands.push(trimmed.clone());
                let mut parts = trimmed.splitn(2, ' ');
                let tag = parts.next().unwrap_or("");
                let rest = parts.next().unwrap_or("");
                let upper = rest.to_ascii_uppercase();

                if upper.starts_with("AUTHENTICATE XOAUTH2") {
                    stream.write_all(b"+ \r\n").unwrap();
                    let mut auth_line = String::new();
                    reader.read_line(&mut auth_line).unwrap();
                    commands.push(auth_line.trim().to_string());
                    stream.write_all(format!("{tag} OK AUTHENTICATE completed\r\n").as_bytes()).unwrap();
                } else if upper == "CAPABILITY" {
                    stream.write_all(b"* CAPABILITY IMAP4rev1 AUTH=XOAUTH2 CONDSTORE IDLE\r\n").unwrap();
                    stream.write_all(format!("{tag} OK CAPABILITY completed\r\n").as_bytes()).unwrap();
                } else if upper.starts_with("ENABLE ") {
                    stream.write_all(b"* ENABLED CONDSTORE\r\n").unwrap();
                    stream.write_all(format!("{tag} OK ENABLE completed\r\n").as_bytes()).unwrap();
                } else if upper.starts_with("LIST ") {
                    stream.write_all(b"* LIST (\\HasNoChildren) \"/\" \"INBOX\"\r\n").unwrap();
                    stream.write_all(b"* LIST (\\HasNoChildren \\Archive) \"/\" \"Archive\"\r\n").unwrap();
                    stream.write_all(format!("{tag} OK LIST completed\r\n").as_bytes()).unwrap();
                } else if upper.starts_with("EXAMINE ") {
                    stream.write_all(b"* FLAGS (\\Answered \\Flagged \\Deleted \\Seen \\Draft)\r\n").unwrap();
                    stream.write_all(b"* 2 EXISTS\r\n").unwrap();
                    stream.write_all(b"* OK [UIDVALIDITY 777] Ok\r\n").unwrap();
                    stream.write_all(b"* OK [UIDNEXT 103] Ok\r\n").unwrap();
                    stream.write_all(b"* OK [HIGHESTMODSEQ 55] Ok\r\n").unwrap();
                    stream.write_all(format!("{tag} OK [READ-ONLY] EXAMINE completed\r\n").as_bytes()).unwrap();
                } else if upper.starts_with("FETCH 1:2 (UID)") {
                    stream.write_all(b"* 1 FETCH (UID 101)\r\n").unwrap();
                    stream.write_all(b"* 2 FETCH (UID 102)\r\n").unwrap();
                    stream.write_all(format!("{tag} OK FETCH completed\r\n").as_bytes()).unwrap();
                } else if upper.starts_with("UID FETCH 101:102 (UID FLAGS INTERNALDATE RFC822.SIZE BODYSTRUCTURE)") {
                    stream.write_all(
                        b"* 1 FETCH (UID 101 FLAGS (\\Seen) INTERNALDATE \"20-Nov-2026 06:00:00 +0900\" RFC822.SIZE 5000000 BODYSTRUCTURE ((\"TEXT\" \"PLAIN\" (\"CHARSET\" \"UTF-8\") NIL NIL \"7BIT\" 22 1 NIL NIL NIL)(\"APPLICATION\" \"PDF\" (\"NAME\" \"invoice.pdf\") NIL NIL \"BASE64\" 16 NIL (\"ATTACHMENT\" (\"FILENAME\" \"invoice.pdf\")) NIL) \"MIXED\" (\"BOUNDARY\" \"bnd\") NIL NIL))\r\n",
                    ).unwrap();
                    stream.write_all(format!("{tag} OK UID FETCH completed\r\n").as_bytes()).unwrap();
                } else if upper.starts_with("UID FETCH 101 (UID BODY.PEEK[HEADER] BODY.PEEK[1])") {
                    let hdr = b"From: Tanaka <tanaka@example.jp>\r\nTo: Me <me@example.jp>\r\nSubject: Invoice attached\r\nMessage-ID: <m101@example.jp>\r\n\r\n";
                    let part1 = b"Hello from KazeMail!\r\n";
                    let resp = format!(
                        "* 1 FETCH (UID 101 BODY[HEADER] {{{}}}\r\n{} BODY[1] {{{}}}\r\n{})\r\n",
                        hdr.len(),
                        std::str::from_utf8(hdr).unwrap(),
                        part1.len(),
                        std::str::from_utf8(part1).unwrap(),
                    );
                    stream.write_all(resp.as_bytes()).unwrap();
                    stream.write_all(format!("{tag} OK UID FETCH completed\r\n").as_bytes()).unwrap();
                } else if upper.starts_with("UID FETCH 101 (UID BODY.PEEK[2.MIME] BODY.PEEK[2])") {
                    let mime_hdr = b"Content-Type: application/pdf; name=\"invoice.pdf\"\r\nContent-Transfer-Encoding: base64\r\n\r\n";
                    let b64_payload = b"JVBERi0xLjQK"; // "%PDF-1.4\n"
                    let resp = format!(
                        "* 1 FETCH (UID 101 BODY[2.MIME] {{{}}}\r\n{} BODY[2] {{{}}}\r\n{})\r\n",
                        mime_hdr.len(),
                        std::str::from_utf8(mime_hdr).unwrap(),
                        b64_payload.len(),
                        std::str::from_utf8(b64_payload).unwrap(),
                    );
                    stream.write_all(resp.as_bytes()).unwrap();
                    stream.write_all(format!("{tag} OK UID FETCH completed\r\n").as_bytes()).unwrap();
                } else if upper.starts_with("UID SEARCH UID 103:*") {
                    // Simulate server returning highest UID 102 when querying 103:*
                    stream.write_all(b"* SEARCH 102\r\n").unwrap();
                    stream.write_all(format!("{tag} OK UID SEARCH completed\r\n").as_bytes()).unwrap();
                } else if upper.contains("CHANGEDSINCE 50") {
                    stream.write_all(b"* 1 FETCH (UID 101 FLAGS (\\Seen \\Flagged) MODSEQ (55))\r\n").unwrap();
                    stream.write_all(format!("{tag} OK UID FETCH completed\r\n").as_bytes()).unwrap();
                } else if upper == "LOGOUT" {
                    stream.write_all(b"* BYE Logging out\r\n").unwrap();
                    stream.write_all(format!("{tag} OK LOGOUT completed\r\n").as_bytes()).unwrap();
                    break;
                } else {
                    stream.write_all(format!("{tag} BAD Unexpected command: {rest}\r\n").as_bytes()).unwrap();
                }
            }
            commands
        });

        let client = connect_and_auth(ImapConnectConfig {
            host: "127.0.0.1".to_string(),
            port,
            security: ImapSecurity::Plaintext,
            auth: ImapAuth::OAuth2 {
                email: "me@example.jp".to_string(),
                access_token: "ya29.test_token".to_string(),
            },
            timeout_secs: 10,
        })
        .expect("connect");

        let folders = client.list_folders().expect("list_folders");
        assert_eq!(folders.len(), 2);
        assert_eq!(folders[0].full_path, "INBOX");

        let uids = client.fetch_latest_uids("INBOX".to_string(), 100).expect("latest");
        assert_eq!(uids, vec![102, 101]);

        let msgs = client
            .fetch_messages_by_uids("INBOX".to_string(), uids)
            .expect("fetch_messages");
        assert_eq!(msgs.len(), 1);
        assert_eq!(msgs[0].uid, 101);
        assert_eq!(msgs[0].plain_text.trim(), "Hello from KazeMail!");
        assert!(msgs[0].has_attachments);
        assert_eq!(msgs[0].attachments.len(), 1);
        assert_eq!(msgs[0].attachments[0].part_id, "2");
        assert_eq!(msgs[0].attachments[0].filename, "invoice.pdf");

        let part_bytes = client
            .fetch_message_part("INBOX".to_string(), 101, "2".to_string(), None)
            .expect("fetch attachment on demand");
        assert_eq!(part_bytes, b"%PDF-1.4\n");

        let delta = client
            .fetch_incremental_delta("INBOX".to_string(), 102, 50)
            .expect("delta");
        // UID 102 returned by UID SEARCH 103:* must be filtered out because 102 <= known_last_uid!
        assert!(delta.new_uids.is_empty());
        assert_eq!(delta.flag_changes.len(), 1);
        assert!(delta.flag_changes[0].is_flagged);
        assert_eq!(delta.snapshot.uid_validity, 777);
        assert_eq!(delta.snapshot.highest_mod_seq, 55);

        client.logout().expect("logout");

        let recorded_commands = server_thread.join().expect("server thread");
        for cmd in &recorded_commands {
            let up = cmd.to_ascii_uppercase();
            assert!(!up.contains("SELECT "), "SELECT must never be sent: {cmd}");
            assert!(!up.contains("EXPUNGE"), "EXPUNGE must never be sent: {cmd}");
            assert!(!up.contains("CLOSE"), "CLOSE must never be sent: {cmd}");
        }
        let sync_cmds: Vec<_> = recorded_commands
            .iter()
            .take_while(|c| !c.to_ascii_uppercase().contains("BODY.PEEK[2.MIME]"))
            .collect();
        assert!(
            sync_cmds
                .iter()
                .all(|c| !c.to_ascii_uppercase().contains("BODY.PEEK[2]")),
            "Attachment payload must not be fetched during sync!"
        );
    }

    #[test]
    fn mock_smtp_xoauth2_sends_utf8_attachments_and_omits_bcc_header() {
        use models::{OutgoingAttachmentSpec, SmtpAuth, SmtpConnectConfig, SmtpSecurity};

        let tmp = tempfile::NamedTempFile::new().expect("temp file");
        std::fs::write(tmp.path(), b"%PDF-1.7-KazeMail").expect("write att");

        let listener = TcpListener::bind("127.0.0.1:0").expect("bind loopback");
        let port = listener.local_addr().unwrap().port();

        let server_thread = thread::spawn(move || {
            let (mut stream, _) = listener.accept().expect("accept");
            stream
                .write_all(b"220 smtp.kazemock.local ESMTP ready\r\n")
                .unwrap();

            let mut reader = BufReader::new(stream.try_clone().unwrap());
            let mut commands = Vec::new();
            let mut in_data = false;
            let mut data_buf = Vec::new();

            loop {
                let mut line = String::new();
                if reader.read_line(&mut line).unwrap() == 0 {
                    break;
                }
                if in_data {
                    if line == ".\r\n" {
                        in_data = false;
                        stream
                            .write_all(b"250 2.0.0 Ok: queued as KM12345\r\n")
                            .unwrap();
                    } else {
                        let unstuffed = if let Some(rest) = line.strip_prefix("..") {
                            format!(".{rest}")
                        } else {
                            line
                        };
                        data_buf.extend_from_slice(unstuffed.as_bytes());
                    }
                    continue;
                }

                let trimmed = line.trim_end_matches(&['\r', '\n'][..]).to_string();
                commands.push(trimmed.clone());
                let upper = trimmed.to_ascii_uppercase();

                if upper.starts_with("EHLO ") {
                    stream
                        .write_all(b"250-smtp.kazemock.local\r\n250 AUTH XOAUTH2 PLAIN\r\n")
                        .unwrap();
                } else if upper.starts_with("AUTH XOAUTH2 ") {
                    stream.write_all(b"235 2.7.0 Authentication successful\r\n").unwrap();
                } else if upper.starts_with("MAIL FROM:")
                    || upper.starts_with("RCPT TO:")
                {
                    stream.write_all(b"250 2.1.5 Ok\r\n").unwrap();
                } else if upper == "DATA" {
                    in_data = true;
                    stream
                        .write_all(b"354 End data with <CR><LF>.<CR><LF>\r\n")
                        .unwrap();
                } else if upper == "QUIT" {
                    stream.write_all(b"221 2.0.0 Bye\r\n").unwrap();
                    break;
                } else {
                    stream.write_all(b"502 Command not implemented\r\n").unwrap();
                }
            }
            (commands, data_buf)
        });

        let result = send_smtp_message(
            SmtpConnectConfig {
                host: "127.0.0.1".to_string(),
                port,
                security: SmtpSecurity::Plaintext,
                auth: SmtpAuth::OAuth2 {
                    email: "me@example.jp".to_string(),
                    access_token: "ya29.secret_token".to_string(),
                },
                timeout_secs: 10,
            },
            OutgoingEmailMessage {
                from_name: Some("鈴木 太郎".to_string()),
                from_address: "me@example.jp".to_string(),
                to_addresses: vec!["tanaka@example.jp".to_string()],
                cc_addresses: vec!["sato@example.jp".to_string()],
                bcc_addresses: vec!["secret-bcc@example.jp".to_string()],
                subject: "Re: 会議資料のご送付".to_string(),
                plain_text: "お世話になっております。\n.先頭ドット行テスト\nよろしくお願いいたします。".to_string(),
                html_body: None,
                in_reply_to: Some("<orig-1@example.jp>".to_string()),
                references: vec!["<root-0@example.jp>".to_string(), "<orig-1@example.jp>".to_string()],
                message_id: None,
                attachments: vec![OutgoingAttachmentSpec {
                    filename: "見積書.pdf".to_string(),
                    mime_type: "application/pdf".to_string(),
                    file_path: tmp.path().to_string_lossy().into_owned(),
                }],
            },
        )
        .expect("smtp send");

        assert!(result.message_id.starts_with("<km.") && result.message_id.ends_with("@example.jp>"));
        assert!(result.server_response.contains("KM12345"));

        let (commands, data_bytes) = server_thread.join().expect("server thread");
        assert!(commands.iter().any(|c| c == "MAIL FROM:<me@example.jp>"));
        assert!(commands.iter().any(|c| c == "RCPT TO:<tanaka@example.jp>"));
        assert!(commands.iter().any(|c| c == "RCPT TO:<sato@example.jp>"));
        assert!(commands.iter().any(|c| c == "RCPT TO:<secret-bcc@example.jp>"));

        let raw_text = String::from_utf8_lossy(&data_bytes);
        assert!(!raw_text.contains("secret-bcc@example.jp"), "Bcc must never leak into DATA headers");

        let parsed = parse_eml_bytes(1, data_bytes, true, false, None).expect("re-parse sent EML");
        assert_eq!(parsed.subject.as_deref(), Some("Re: 会議資料のご送付"));
        assert_eq!(parsed.in_reply_to.as_deref(), Some("orig-1@example.jp"));
        assert_eq!(
            parsed.references,
            vec!["root-0@example.jp".to_string(), "orig-1@example.jp".to_string()]
        );
        assert!(parsed.plain_text.contains(".先頭ドット行テスト"));
        assert!(parsed.has_attachments);
        assert_eq!(parsed.attachments.len(), 1);
        assert_eq!(parsed.attachments[0].filename, "見積書.pdf");
    }

    #[test]
    fn mock_smtp_auth_login_fallback_and_bad_auth_error() {
        use base64::engine::general_purpose::STANDARD as BASE64;
        use base64::Engine as _;
        use models::{SmtpAuth, SmtpConnectConfig, SmtpSecurity};

        let listener = TcpListener::bind("127.0.0.1:0").expect("bind loopback");
        let port = listener.local_addr().unwrap().port();

        let server_thread = thread::spawn(move || {
            for attempt in 0..2 {
                let (mut stream, _) = listener.accept().expect("accept");
                stream.write_all(b"220 smtp.login.local ESMTP\r\n").unwrap();
                let mut reader = BufReader::new(stream.try_clone().unwrap());

                let mut step = 0;
                loop {
                    let mut line = String::new();
                    if reader.read_line(&mut line).unwrap() == 0 {
                        break;
                    }
                    let trimmed = line.trim_end_matches(&['\r', '\n'][..]);
                    let upper = trimmed.to_ascii_uppercase();
                    if upper.starts_with("EHLO ") {
                        stream.write_all(b"250-smtp.login.local\r\n250 AUTH LOGIN\r\n").unwrap();
                    } else if upper == "AUTH LOGIN" {
                        step = 1;
                        stream.write_all(b"334 VXNlcm5hbWU6\r\n").unwrap();
                    } else if step == 1 {
                        let user = String::from_utf8(BASE64.decode(trimmed).unwrap()).unwrap();
                        assert_eq!(user, "alice@example.jp");
                        step = 2;
                        stream.write_all(b"334 UGFzc3dvcmQ6\r\n").unwrap();
                    } else if step == 2 {
                        let pass = String::from_utf8(BASE64.decode(trimmed).unwrap()).unwrap();
                        step = 0;
                        if attempt == 0 && pass == "secret-pass" {
                            stream.write_all(b"235 Authentication succeeded\r\n").unwrap();
                        } else {
                            stream.write_all(b"535 5.7.8 Authentication credentials invalid\r\n").unwrap();
                            break;
                        }
                    } else if upper.starts_with("MAIL FROM:") || upper.starts_with("RCPT TO:") {
                        stream.write_all(b"250 Ok\r\n").unwrap();
                    } else if upper == "DATA" {
                        stream.write_all(b"354 Go ahead\r\n").unwrap();
                        loop {
                            let mut d = String::new();
                            reader.read_line(&mut d).unwrap();
                            if d == ".\r\n" {
                                break;
                            }
                        }
                        stream.write_all(b"250 Queued\r\n").unwrap();
                    } else if upper == "QUIT" {
                        stream.write_all(b"221 Bye\r\n").unwrap();
                        break;
                    }
                }
            }
        });

        let msg = OutgoingEmailMessage {
            from_name: None,
            from_address: "alice@example.jp".to_string(),
            to_addresses: vec!["bob@example.jp".to_string()],
            cc_addresses: vec![],
            bcc_addresses: vec![],
            subject: "Plain ASCII".to_string(),
            plain_text: "Hello!".to_string(),
            html_body: None,
            in_reply_to: None,
            references: vec![],
            message_id: None,
            attachments: vec![],
        };

        let ok = send_smtp_message(
            SmtpConnectConfig {
                host: "127.0.0.1".to_string(),
                port,
                security: SmtpSecurity::Plaintext,
                auth: SmtpAuth::Password {
                    username: "alice@example.jp".to_string(),
                    password: "secret-pass".to_string(),
                },
                timeout_secs: 10,
            },
            msg.clone(),
        );
        assert!(ok.is_ok());

        let bad = send_smtp_message(
            SmtpConnectConfig {
                host: "127.0.0.1".to_string(),
                port,
                security: SmtpSecurity::Plaintext,
                auth: SmtpAuth::Password {
                    username: "alice@example.jp".to_string(),
                    password: "wrong".to_string(),
                },
                timeout_secs: 10,
            },
            msg,
        );
        assert!(matches!(bad, Err(KazeCoreError::AuthFailed { .. })));

        server_thread.join().expect("server thread");
    }

    #[test]
    fn imap_handles_utf8_folders_no_enable_cap_and_bodystructure_fallback() {
        use models::{ImapAuth, ImapConnectConfig, ImapSecurity};

        let listener = TcpListener::bind("127.0.0.1:0").expect("bind loopback");
        let port = listener.local_addr().unwrap().port();

        let server_thread = thread::spawn(move || {
            let (mut stream, _) = listener.accept().expect("accept");
            // UTF-8 in greeting + CONDSTORE without ENABLE capability
            stream
                .write_all("* OK [CAPABILITY IMAP4rev1 CONDSTORE] ようこそ KazeMail\r\n".as_bytes())
                .unwrap();

            let mut reader = BufReader::new(stream.try_clone().unwrap());
            let mut commands = Vec::new();
            let raw_email = concat!(
                "From: \"=?UTF-8?B?55Sw5Lit?=\" <tanaka@example.jp>\r\n",
                "Subject: =?UTF-8?B?44OG44K544OI?=\r\n",
                "MIME-Version: 1.0\r\n",
                "Content-Type: text/plain; charset=UTF-8\r\n",
                "\r\n",
                "本文テスト\r\n"
            );

            loop {
                let mut line = String::new();
                if reader.read_line(&mut line).unwrap() == 0 {
                    break;
                }
                let trimmed = line.trim_end_matches(&['\r', '\n'][..]).to_string();
                commands.push(trimmed.clone());
                let mut parts = trimmed.splitn(2, ' ');
                let tag = parts.next().unwrap_or("");
                let cmd = parts.next().unwrap_or("").to_ascii_uppercase();

                if cmd.starts_with("CAPABILITY") {
                    stream
                        .write_all(b"* CAPABILITY IMAP4rev1 CONDSTORE\r\n")
                        .unwrap();
                    stream
                        .write_all(format!("{tag} OK CAPABILITY completed\r\n").as_bytes())
                        .unwrap();
                } else if cmd.starts_with("LOGIN ") {
                    stream
                        .write_all(format!("{tag} OK Logged in\r\n").as_bytes())
                        .unwrap();
                } else if cmd.starts_with("LIST ") {
                    stream
                        .write_all("* LIST (\\HasNoChildren) \"/\" \"INBOX\"\r\n".as_bytes())
                        .unwrap();
                    // Non-ASCII UTF-8 mailbox name (RFC 6855) + Latin-1 non-UTF8 OK text
                    stream
                        .write_all("* LIST (\\HasNoChildren \\Sent) \"/\" \"送信済み\"\r\n".as_bytes())
                        .unwrap();
                    let mut done = format!("{tag} OK LIST done ").into_bytes();
                    done.extend_from_slice(b"\xa9\r\n");
                    stream.write_all(&done).unwrap();
                } else if cmd.starts_with("EXAMINE ") {
                    stream
                        .write_all(b"* 1 EXISTS\r\n* OK [UIDVALIDITY 777] Ok\r\n")
                        .unwrap();
                    stream
                        .write_all(format!("{tag} OK [READ-ONLY] EXAMINE completed\r\n").as_bytes())
                        .unwrap();
                } else if cmd.starts_with("FETCH ") && cmd.contains("(UID)") {
                    stream.write_all(b"* 1 FETCH (UID 101)\r\n").unwrap();
                    stream
                        .write_all(format!("{tag} OK FETCH completed\r\n").as_bytes())
                        .unwrap();
                } else if cmd.starts_with("UID SEARCH ") {
                    stream.write_all(b"* SEARCH 101\r\n").unwrap();
                    stream
                        .write_all(format!("{tag} OK SEARCH completed\r\n").as_bytes())
                        .unwrap();
                } else if cmd.starts_with("UID FETCH ") && cmd.contains("BODYSTRUCTURE") {
                    // Simulate malformed BODYSTRUCTURE containing invalid escape / syntax
                    stream
                        .write_all(
                            b"* 1 FETCH (UID 101 FLAGS (\\Seen) INTERNALDATE NIL RFC822.SIZE 128 BODYSTRUCTURE (\"TEXT\" \"PLAIN\" (\"NAME\" \"bad\\xfile\") NIL NIL \"7BIT\" 128 2))\r\n",
                        )
                        .unwrap();
                    stream
                        .write_all(format!("{tag} OK FETCH completed\r\n").as_bytes())
                        .unwrap();
                } else if cmd.starts_with("UID FETCH ") && cmd.contains("BODY.PEEK[]") {
                    stream
                        .write_all(
                            format!(
                                "* 1 FETCH (UID 101 BODY[] {{{}}}\r\n{})\r\n",
                                raw_email.len(),
                                raw_email
                            )
                            .as_bytes(),
                        )
                        .unwrap();
                    stream
                        .write_all(format!("{tag} OK FETCH completed\r\n").as_bytes())
                        .unwrap();
                } else if cmd.starts_with("UID FETCH ") && cmd.contains("RFC822.SIZE") {
                    stream
                        .write_all(
                            b"* 1 FETCH (UID 101 FLAGS (\\Seen) INTERNALDATE NIL RFC822.SIZE 128)\r\n",
                        )
                        .unwrap();
                    stream
                        .write_all(format!("{tag} OK FETCH completed\r\n").as_bytes())
                        .unwrap();
                } else if cmd == "LOGOUT" {
                    stream.write_all(b"* BYE Logging out\r\n").unwrap();
                    stream
                        .write_all(format!("{tag} OK Logout completed\r\n").as_bytes())
                        .unwrap();
                    break;
                } else {
                    stream
                        .write_all(format!("{tag} BAD Unknown command: {trimmed}\r\n").as_bytes())
                        .unwrap();
                }
            }
            commands
        });

        let client = connect_and_auth(ImapConnectConfig {
            host: "127.0.0.1".to_string(),
            port,
            security: ImapSecurity::Plaintext,
            auth: ImapAuth::Password {
                username: "u@example.jp".to_string(),
                password: "pw".to_string(),
            },
            timeout_secs: 10,
        })
        .expect("connect_and_auth should succeed");

        let folders = client.list_folders().expect("UTF-8 LIST must succeed");
        assert!(folders.iter().any(|f| f.full_path == "送信済み"));

        let snap = client
            .examine_folder("INBOX".to_string())
            .expect("EXAMINE");
        assert_eq!(snap.uid_validity, 777);

        let uids = client
            .fetch_latest_uids("INBOX".to_string(), 50)
            .expect("SEARCH");
        assert_eq!(uids, vec![101]);

        let msgs = client
            .fetch_messages_by_uids("INBOX".to_string(), uids)
            .expect("BODYSTRUCTURE parse error must gracefully fall back");
        assert_eq!(msgs.len(), 1);
        assert!(msgs[0].is_read);
        assert_eq!(msgs[0].subject.as_deref(), Some("テスト"));

        client.logout().expect("logout");

        let recorded = server_thread.join().expect("server thread");
        assert!(
            recorded.iter().all(|c| !c.to_ascii_uppercase().contains("ENABLE ")),
            "ENABLE must not be sent when server lacks ENABLE capability: {recorded:?}"
        );
    }

    #[test]
    fn imap_preserves_real_bye_explanation_instead_of_generic_error() {
        use models::{ImapAuth, ImapConnectConfig, ImapSecurity};

        let listener = TcpListener::bind("127.0.0.1:0").expect("bind loopback");
        let port = listener.local_addr().unwrap().port();

        let server_thread = thread::spawn(move || {
            let (mut stream, _) = listener.accept().expect("accept");
            stream
                .write_all(b"* BYE Too many simultaneous connections from your IP\r\n")
                .unwrap();
        });

        let err: KazeCoreError = match connect_and_auth(ImapConnectConfig {
            host: "127.0.0.1".to_string(),
            port,
            security: ImapSecurity::Plaintext,
            auth: ImapAuth::Password {
                username: "u@example.jp".to_string(),
                password: "pw".to_string(),
            },
            timeout_secs: 5,
        }) {
            Ok(_) => panic!("expected BYE error"),
            Err(err) => err,
        };
        let msg = err.to_string();
        assert!(
            msg.contains("Too many simultaneous connections"),
            "unexpected error detail: {msg}"
        );
        server_thread.join().expect("server thread");
    }

    #[test]
    fn imap_decodes_mutf7_folder_names_and_hierarchy_cleanly() {
        use imap_client::{decode_imap_mutf7, format_imap_folder_display_name};

        fn encode_mutf7_for_test(s: &str) -> String {
            const ALPHA: &[u8; 64] =
                b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+,";
            let mut out = String::new();
            let mut non_ascii: Vec<u16> = Vec::new();
            let flush = |buf: &mut Vec<u16>, out: &mut String| {
                if buf.is_empty() {
                    return;
                }
                out.push('&');
                let mut bit_buf: u32 = 0;
                let mut bits: u32 = 0;
                for unit in buf.drain(..) {
                    for b in unit.to_be_bytes() {
                        bit_buf = (bit_buf << 8) | (b as u32);
                        bits += 8;
                        while bits >= 6 {
                            bits -= 6;
                            out.push(ALPHA[((bit_buf >> bits) & 0x3F) as usize] as char);
                        }
                    }
                }
                if bits > 0 {
                    out.push(ALPHA[((bit_buf << (6 - bits)) & 0x3F) as usize] as char);
                }
                out.push('-');
            };
            for ch in s.chars() {
                if ch == '&' {
                    flush(&mut non_ascii, &mut out);
                    out.push_str("&-");
                } else if (' '..='~').contains(&ch) {
                    flush(&mut non_ascii, &mut out);
                    out.push(ch);
                } else {
                    let mut u16buf = [0u16; 2];
                    non_ascii.extend_from_slice(ch.encode_utf16(&mut u16buf));
                }
            }
            flush(&mut non_ascii, &mut out);
            out
        }

        // Known real Gmail mUTF-7 strings + encoded roundtrips
        assert_eq!(decode_imap_mutf7("&Tgtm+DBN-"), "下書き");
        assert_eq!(decode_imap_mutf7("&MFkweTBmMG4w4TD8MOs-"), "すべてのメール");
        assert_eq!(decode_imap_mutf7("R&-D"), "R&D");

        for sample in ["送信済み", "ゴミ箱", "迷惑メール", "スター付き", "重要", "仕事&プライベート"] {
            let encoded = encode_mutf7_for_test(sample);
            assert_eq!(decode_imap_mutf7(&encoded), sample);
        }

        let work_enc = encode_mutf7_for_test("仕事");
        let inv_enc = encode_mutf7_for_test("請求書");
        assert_eq!(
            format_imap_folder_display_name("[Gmail]/&MFkweTBmMG4w4TD8MOs-", Some("/")),
            "すべてのメール"
        );
        assert_eq!(
            format_imap_folder_display_name("INBOX.&Tgtm+DBN-", Some(".")),
            "下書き"
        );
        assert_eq!(
            format_imap_folder_display_name(&format!("{work_enc}/{inv_enc}"), Some("/")),
            "仕事 / 請求書"
        );
    }

    #[test]
    fn parser_auto_detects_plain_vs_html_emails_accurately() {
        use parser::parse_raw_email;
        // 1. Pure text/plain email must NOT set html_zstd even though mail-parser copies text into html_body()
        let plain_raw = concat!(
            "From: sender@example.jp\r\n",
            "Subject: Pure Plain\r\n",
            "Content-Type: text/plain; charset=UTF-8\r\n",
            "\r\n",
            "こんにちは <taro@example.jp>\r\n1 < 2 > 0\r\n2行目のテキストです。\r\n"
        );
        let parsed_plain = parse_raw_email(1, plain_raw.as_bytes(), false, false, None, None)
            .expect("plain parse");
        assert!(
            parsed_plain.html_zstd.is_none(),
            "Pure text/plain must not generate html_zstd"
        );
        assert!(parsed_plain.plain_text.contains("2行目のテキストです。"));

        // 2. Pure text/html email must populate html_zstd AND clean tag-free plain_text/snippet
        let html_only_raw = concat!(
            "From: news@example.jp\r\n",
            "Subject: HTML Newsletter\r\n",
            "Content-Type: text/html; charset=UTF-8\r\n",
            "\r\n",
            "<!DOCTYPE html><html><head><style>body{color:red}</style></head><body><h1>お知らせ</h1><p>セール開始しました</p></body></html>"
        );
        let parsed_html = parse_raw_email(2, html_only_raw.as_bytes(), false, false, None, None)
            .expect("html parse");
        assert!(parsed_html.html_zstd.is_some());
        assert!(!parsed_html.plain_text.contains("<!DOCTYPE"));
        assert!(!parsed_html.snippet.contains("<html>"));
        assert!(parsed_html.plain_text.contains("セール開始しました"));

        // 3. Mislabeled text/plain containing actual HTML document must be auto-promoted to HTML
        let mislabeled_raw = concat!(
            "From: auto@example.jp\r\n",
            "Subject: Mislabeled HTML\r\n",
            "Content-Type: text/plain; charset=UTF-8\r\n",
            "\r\n",
            "<html><body><div>ご注文ありがとうございます。</div><p>合計: 1,200円</p></body></html>"
        );
        let parsed_mislabeled = parse_raw_email(3, mislabeled_raw.as_bytes(), false, false, None, None)
            .expect("mislabeled parse");
        assert!(parsed_mislabeled.html_zstd.is_some());
        assert!(parsed_mislabeled.plain_text.contains("合計: 1,200円"));
        assert!(!parsed_mislabeled.plain_text.contains("<html>"));

        // 4. Plain-text technical/prose emails with tag-prefix collisions, unclosed prose,
        //    single root mention, or Markdown backticks must NOT be promoted to HTML
        let prose_with_pseudo_tags = concat!(
            "From: dev@example.jp\r\n",
            "Subject: Bug report\r\n",
            "Content-Type: text/plain; charset=UTF-8\r\n",
            "\r\n",
            "確認事項:\r\n",
            "<header_id> と <trace> と <old> と <branch> のログ。\r\n",
            "また HTMLの<body>タグや <div> と <p> についても議論しました。\r\n",
            "コード例: `<div>sample</div>` と `<span>ok</span>`。\r\n",
            "```html\r\n<div class=\"code\"><p>Sample</p></div>\r\n```\r\n",
            "条件式: if x <b and y > z なら分岐します。\r\n"
        );
        let parsed_prose = parse_raw_email(4, prose_with_pseudo_tags.as_bytes(), false, false, None, None)
            .expect("prose parse");
        assert!(
            parsed_prose.html_zstd.is_none(),
            "Prose/code mentions of tags must remain plain text"
        );
        assert!(parsed_prose.plain_text.contains("<trace>"));

        // 5. Minimal text/html emails using non-structural formatting tags (<b>, <hr>, <h3>)
        //    must STILL be preserved as HTML
        let minimal_html_raw = concat!(
            "From: alert@example.jp\r\n",
            "Subject: Build OK\r\n",
            "Content-Type: text/html; charset=UTF-8\r\n",
            "\r\n",
            "<h3>Build succeeded</h3><b>Server 1</b><hr>All checks passed."
        );
        let parsed_minimal = parse_raw_email(5, minimal_html_raw.as_bytes(), false, false, None, None)
            .expect("minimal html parse");
        assert!(parsed_minimal.html_zstd.is_some());

        // 6. multipart/alternative with empty table placeholder in text/html and real text/plain
        //    must keep plain_text cleanly without empty HTML override
        let empty_html_alt = concat!(
            "From: mailer@example.jp\r\n",
            "Subject: Alt test\r\n",
            "MIME-Version: 1.0\r\n",
            "Content-Type: multipart/alternative; boundary=\"b1\"\r\n",
            "\r\n",
            "--b1\r\n",
            "Content-Type: text/plain; charset=UTF-8\r\n\r\n",
            "本文はこちらです。\r\n",
            "--b1\r\n",
            "Content-Type: text/html; charset=UTF-8\r\n\r\n",
            "<html><body><table><tr><td>&nbsp;</td></tr></table></body></html>\r\n",
            "--b1--\r\n"
        );
        let parsed_alt = parse_raw_email(6, empty_html_alt.as_bytes(), false, false, None, None)
            .expect("alt parse");
        assert!(
            parsed_alt.html_zstd.is_none(),
            "Empty layout-only HTML table must not produce html_zstd"
        );
        assert!(parsed_alt.plain_text.contains("本文はこちらです。"));

        // 7. Headless HTML with >9KB <style> block before <div> (mislabeled text/plain)
        //    must still be detected within the 32KB scan window
        let large_css_padding = "body{color:#111} ".repeat(600); // ~10.2KB
        let large_headless_html = format!(
            "From: news@example.jp\r\nSubject: Big CSS\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n<style>{large_css_padding}</style><div class=\"wrap\"><p>重要なお知らせ</p></div>"
        );
        let parsed_large = parse_raw_email(7, large_headless_html.as_bytes(), false, false, None, None)
            .expect("large headless parse");
        assert!(
            parsed_large.html_zstd.is_some(),
            "32KB window must catch HTML fragments after >9KB style blocks"
        );
    }

    #[test]
    fn recovers_malformed_unspaced_and_truncated_iso2022jp_rfc2047_subjects() {
        use base64::engine::general_purpose::STANDARD as B64;
        use base64::Engine as _;
        use encoding::decode_rfc2047_lenient;
        use encoding_rs::ISO_2022_JP;
        use parser::parse_raw_email;

        let (part1, _, _) = ISO_2022_JP.encode("こんにちは");
        let (part2, _, _) = ISO_2022_JP.encode("連絡事項");
        let b64_1 = B64.encode(part1.as_ref());
        let b64_2 = B64.encode(part2.as_ref());

        // Adjacent encoded words + missing space after "Re:" + broken suffix without space
        let decoded_direct = decode_rfc2047_lenient(&format!(
            "Re:=?ISO-2022-JP?B?{b64_1}?= =?ISO-2022-JP?B?{b64_2}?=(急ぎ)"
        ));
        assert_eq!(decoded_direct, "Re:こんにちは連絡事項(急ぎ)");

        // Truncated ISO-2022-JP header missing closing ?=
        let truncated_decoded = decode_rfc2047_lenient(&format!("=?ISO-2022-JP?B?{b64_1}"));
        assert_eq!(truncated_decoded, "こんにちは");

        // Full RFC5322 email with unspaced Re:=?ISO-2022-JP?B?... that mail-parser leaves raw
        let (sender_jis, _, _) = ISO_2022_JP.encode("田中太郎");
        let b64_sender = B64.encode(sender_jis.as_ref());
        let raw_mail = format!(
            "From: =?ISO-2022-JP?B?{b64_sender}?=<tanaka@example.jp>\r\n\
             Subject: Re:=?ISO-2022-JP?B?{b64_1}?=\r\n\
             To: =?ISO-2022-JP?B?{b64_2}?= <dest@example.jp>\r\n\
             Content-Type: text/plain; charset=UTF-8\r\n\r\n\
             本文テストです。\r\n"
        );
        let rec = parse_raw_email(10, raw_mail.as_bytes(), false, false, None, None)
            .expect("parse raw header");
        let subj = rec.subject.unwrap_or_default();
        assert!(
            !subj.contains("=?ISO-2022-JP"),
            "Raw RFC2047 token must not leak into subject: {subj}"
        );
        assert_eq!(subj, "Re:こんにちは");
        assert_eq!(rec.sender_name.as_deref(), Some("田中太郎"));
    }

    #[test]
    fn decodes_unpadded_base64_and_resolves_hierarchical_imap_part_ids_and_fallback() {
        use base64::engine::general_purpose::STANDARD_NO_PAD;
        use base64::Engine as _;
        use bodystructure::{decode_part_payload, transfer_encoding_from_mime_header, PartTransferEncoding};
        use parser::parse_raw_email;

        // 1. Unpadded Base64 + stray whitespace + trailing NUL must decode cleanly to PDF bytes
        let pdf_bytes = b"%PDF-1.7\n%KazeMail Test Payload!";
        let unpadded = STANDARD_NO_PAD.encode(pdf_bytes);
        let noisy = format!("{}\r\n\0", unpadded);
        let decoded = decode_part_payload(noisy.as_bytes(), PartTransferEncoding::Base64);
        assert_eq!(decoded, pdf_bytes);

        // 2. Folded / case-varied Content-Transfer-Encoding header detection
        let mime_hdr = b"Content-Type: application/pdf;\r\n name=\"doc.pdf\"\r\nCONTENT-TRANSFER-ENCODING: Base64\r\n";
        assert_eq!(
            transfer_encoding_from_mime_header(mime_hdr),
            PartTransferEncoding::Base64
        );

        // 3. Raw multipart/mixed -> (multipart/alternative -> (plain, html), pdf) assigns IMAP part_id "2"
        let b64_pdf = base64::engine::general_purpose::STANDARD.encode(pdf_bytes);
        let raw_eml = format!(
            "From: a@example.jp\r\nTo: b@example.jp\r\nSubject: Nested PDF\r\n\
             Content-Type: multipart/mixed; boundary=\"mix\"\r\n\r\n\
             --mix\r\n\
             Content-Type: multipart/alternative; boundary=\"alt\"\r\n\r\n\
             --alt\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nHello\r\n\
             --alt\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n<p>Hello</p>\r\n\
             --alt--\r\n\
             --mix\r\n\
             Content-Type: application/pdf; name=\"report.pdf\"\r\n\
             Content-Transfer-Encoding: base64\r\n\
             Content-Disposition: attachment; filename=\"report.pdf\"\r\n\r\n\
             {b64_pdf}\r\n\
             --mix--\r\n"
        );
        let parsed = parse_raw_email(55, raw_eml.as_bytes(), false, false, None, None)
            .expect("parse nested");
        assert_eq!(parsed.attachments.len(), 1);
        assert_eq!(parsed.attachments[0].part_id, "2");
        assert_eq!(parsed.attachments[0].filename, "report.pdf");

        // 4. Mock IMAP server that rejects BODY.PEEK[2.MIME] -> falls back to direct part + BODYSTRUCTURE
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind loopback");
        let port = listener.local_addr().unwrap().port();
        let server = thread::spawn(move || {
            let (mut stream, _) = listener.accept().expect("accept");
            let mut reader = BufReader::new(stream.try_clone().unwrap());
            stream
                .write_all(b"* OK [CAPABILITY IMAP4rev1 IDLE] FallbackMock ready\r\n")
                .unwrap();

            let mut line = String::new();
            while reader.read_line(&mut line).unwrap_or(0) > 0 {
                let trimmed = line.trim().to_string();
                line.clear();
                let mut parts = trimmed.splitn(2, ' ');
                let tag = parts.next().unwrap_or("");
                let rest = parts.next().unwrap_or("");
                let upper = rest.to_ascii_uppercase();

                if upper.starts_with("LOGIN ") {
                    stream.write_all(format!("{tag} OK LOGIN completed\r\n").as_bytes()).unwrap();
                } else if upper.starts_with("EXAMINE ") {
                    stream.write_all(b"* 1 EXISTS\r\n* OK [UIDVALIDITY 11] ok\r\n").unwrap();
                    stream.write_all(format!("{tag} OK [READ-ONLY] EXAMINE ok\r\n").as_bytes()).unwrap();
                } else if upper.contains("BODY.PEEK[2.MIME]") {
                    stream.write_all(format!("{tag} BAD Unsupported MIME section\r\n").as_bytes()).unwrap();
                } else if upper.starts_with("UID FETCH 201 (UID BODY.PEEK[2])") {
                    let resp = format!(
                        "* 1 FETCH (UID 201 BODY[2] {{{}}}\r\n{b64_pdf})\r\n",
                        b64_pdf.len(),
                    );
                    stream.write_all(resp.as_bytes()).unwrap();
                    stream.write_all(format!("{tag} OK UID FETCH completed\r\n").as_bytes()).unwrap();
                } else if upper.starts_with("UID FETCH 201 (UID BODYSTRUCTURE)") {
                    stream.write_all(
                        b"* 1 FETCH (UID 201 BODYSTRUCTURE ((\"TEXT\" \"PLAIN\" (\"CHARSET\" \"UTF-8\") NIL NIL \"7BIT\" 5 1 NIL NIL NIL)(\"APPLICATION\" \"PDF\" (\"NAME\" \"report.pdf\") NIL NIL \"BASE64\" 48 NIL (\"ATTACHMENT\" (\"FILENAME\" \"report.pdf\")) NIL) \"MIXED\" (\"BOUNDARY\" \"mix\") NIL NIL))\r\n",
                    ).unwrap();
                    stream.write_all(format!("{tag} OK UID FETCH completed\r\n").as_bytes()).unwrap();
                } else if upper == "LOGOUT" {
                    stream.write_all(b"* BYE bye\r\n").unwrap();
                    stream.write_all(format!("{tag} OK LOGOUT\r\n").as_bytes()).unwrap();
                    break;
                } else {
                    stream.write_all(format!("{tag} BAD Unexpected: {rest}\r\n").as_bytes()).unwrap();
                }
            }
        });

        let client = connect_and_auth(ImapConnectConfig {
            host: "127.0.0.1".to_string(),
            port,
            security: ImapSecurity::Plaintext,
            auth: ImapAuth::Password {
                username: "u@example.jp".to_string(),
                password: "pw".to_string(),
            },
            timeout_secs: 10,
        })
        .expect("connect");

        let downloaded = client
            .fetch_message_part("INBOX".to_string(), 201, "2".to_string(), None)
            .expect("fetch part fallback");
        assert_eq!(downloaded, pdf_bytes);
        client.logout().expect("logout");
        server.join().expect("server");
    }
}
