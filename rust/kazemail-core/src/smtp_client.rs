use std::collections::HashSet;
use std::fs;
use std::io::{self, BufRead, BufReader, Read, Write};
use std::net::TcpStream;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine as _;
use chrono::Local;
use rustls_connector::TlsStream;

use crate::error::KazeCoreError;
use crate::imap_client::{connect_tcp_with_timeout, upgrade_tcp_to_tls};
use crate::models::{
    OutgoingAttachmentSpec, OutgoingEmailMessage, SmtpAuth, SmtpConnectConfig, SmtpSecurity,
    SmtpSendResult,
};

static MSG_ID_SEQ: AtomicU64 = AtomicU64::new(1);

#[derive(Debug)]
enum SmtpSocket {
    Plain(TcpStream),
    Tls(Box<TlsStream<TcpStream>>),
}

impl Read for SmtpSocket {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        match self {
            SmtpSocket::Plain(s) => s.read(buf),
            SmtpSocket::Tls(s) => s.read(buf),
        }
    }
}

impl Write for SmtpSocket {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        match self {
            SmtpSocket::Plain(s) => s.write(buf),
            SmtpSocket::Tls(s) => s.write(buf),
        }
    }

    fn flush(&mut self) -> io::Result<()> {
        match self {
            SmtpSocket::Plain(s) => s.flush(),
            SmtpSocket::Tls(s) => s.flush(),
        }
    }
}

struct SmtpSession {
    reader: BufReader<SmtpSocket>,
}

impl SmtpSession {
    fn connect_plain(tcp: TcpStream) -> Self {
        Self {
            reader: BufReader::new(SmtpSocket::Plain(tcp)),
        }
    }

    fn connect_tls(tls: TlsStream<TcpStream>) -> Self {
        Self {
            reader: BufReader::new(SmtpSocket::Tls(Box::new(tls))),
        }
    }

    fn upgrade_starttls(self, host: &str) -> Result<Self, KazeCoreError> {
        let socket = self.reader.into_inner();
        let tcp = match socket {
            SmtpSocket::Plain(s) => s,
            SmtpSocket::Tls(_) => {
                return Err(KazeCoreError::TlsError {
                    detail: "SMTP connection is already using TLS".to_string(),
                });
            }
        };
        let tls = upgrade_tcp_to_tls(host, tcp)?;
        Ok(Self::connect_tls(tls))
    }

    fn send_line(&mut self, line: &str) -> Result<(), KazeCoreError> {
        let sanitized = strip_crlf(line);
        let socket = self.reader.get_mut();
        socket.write_all(sanitized.as_bytes())?;
        socket.write_all(b"\r\n")?;
        socket.flush()?;
        Ok(())
    }

    fn write_dot_stuffed_data(&mut self, rfc5322_bytes: &[u8]) -> Result<(), KazeCoreError> {
        let socket = self.reader.get_mut();
        for raw_line in rfc5322_bytes.split_inclusive(|b| *b == b'\n') {
            let line = raw_line
                .strip_suffix(b"\n")
                .unwrap_or(raw_line);
            let line = line.strip_suffix(b"\r").unwrap_or(line);
            if line.starts_with(b".") {
                socket.write_all(b".")?;
            }
            socket.write_all(line)?;
            socket.write_all(b"\r\n")?;
        }
        socket.write_all(b".\r\n")?;
        socket.flush()?;
        Ok(())
    }

    fn read_reply(&mut self) -> Result<(u16, String), KazeCoreError> {
        let mut lines = Vec::new();
        let final_code: u16;

        loop {
            let mut line = String::new();
            let n = self.reader.read_line(&mut line)?;
            if n == 0 {
                return Err(KazeCoreError::ConnectionFailed {
                    detail: "SMTP server closed connection unexpectedly".to_string(),
                });
            }
            let trimmed = line.trim_end_matches(&['\r', '\n'][..]);
            if trimmed.len() < 3 || !trimmed.as_bytes()[0..3].iter().all(|b| b.is_ascii_digit()) {
                return Err(KazeCoreError::ProtocolError {
                    detail: format!("Malformed SMTP reply line: {trimmed}"),
                });
            }
            let code: u16 = trimmed[0..3].parse().map_err(|_| KazeCoreError::ProtocolError {
                detail: format!("Invalid SMTP status code in: {trimmed}"),
            })?;
            let sep = trimmed.as_bytes().get(3).copied();
            let text = trimmed.get(4..).unwrap_or("").to_string();
            lines.push(text);
            if sep != Some(b'-') {
                final_code = code;
                break;
            }
        }

        Ok((final_code, lines.join("\n")))
    }

    fn command(&mut self, cmd: &str, expected_codes: &[u16]) -> Result<(u16, String), KazeCoreError> {
        self.send_line(cmd)?;
        let (code, text) = self.read_reply()?;
        if expected_codes.contains(&code) {
            Ok((code, text))
        } else {
            Err(classify_smtp_error(code, &text))
        }
    }
}

fn classify_smtp_error(code: u16, detail: &str) -> KazeCoreError {
    if matches!(code, 530 | 534 | 535 | 538) {
        KazeCoreError::AuthFailed {
            detail: format!("SMTP {code}: {detail}"),
        }
    } else {
        KazeCoreError::ProtocolError {
            detail: format!("SMTP {code}: {detail}"),
        }
    }
}

pub fn send_smtp_message(
    config: SmtpConnectConfig,
    message: OutgoingEmailMessage,
) -> Result<SmtpSendResult, KazeCoreError> {
    if config.host.trim().is_empty() {
        return Err(KazeCoreError::InvalidInput {
            detail: "SMTP host cannot be empty".to_string(),
        });
    }
    if config.port == 0 {
        return Err(KazeCoreError::InvalidInput {
            detail: "SMTP port must be greater than 0".to_string(),
        });
    }

    let from_bare = extract_bare_email(&message.from_address)?;
    let envelope_recipients = collect_envelope_recipients(&message)?;
    let resolved_message_id = ensure_message_id(message.message_id.as_deref(), &from_bare);
    let rfc5322_bytes = build_rfc5322_message(&message, &resolved_message_id)?;

    let timeout = Duration::from_secs(config.timeout_secs.max(10) as u64);
    let tcp = connect_tcp_with_timeout(&config.host, config.port, timeout)?;

    let mut session = match config.security {
        SmtpSecurity::ImplicitTls => {
            let tls = upgrade_tcp_to_tls(&config.host, tcp)?;
            let mut s = SmtpSession::connect_tls(tls);
            expect_greeting(&mut s)?;
            s
        }
        SmtpSecurity::StartTls | SmtpSecurity::Plaintext => {
            let mut s = SmtpSession::connect_plain(tcp);
            expect_greeting(&mut s)?;
            s
        }
    };

    let (_, mut ehlo_text) = session.command("EHLO kazemail.local", &[250])?;
    if config.security == SmtpSecurity::StartTls {
        session.command("STARTTLS", &[220])?;
        session = session.upgrade_starttls(&config.host)?;
        let (_, tls_ehlo) = session.command("EHLO kazemail.local", &[250])?;
        ehlo_text = tls_ehlo;
    }

    authenticate_smtp(&mut session, &ehlo_text, &config.auth)?;

    session.command(&format!("MAIL FROM:<{from_bare}>"), &[250])?;
    for rcpt in &envelope_recipients {
        session.command(&format!("RCPT TO:<{rcpt}>"), &[250, 251])?;
    }

    session.command("DATA", &[354])?;
    session.write_dot_stuffed_data(&rfc5322_bytes)?;
    let (_, server_response) = session.read_reply().and_then(|(code, text)| {
        if code == 250 {
            Ok((code, text))
        } else {
            Err(classify_smtp_error(code, &text))
        }
    })?;

    let _ = session.send_line("QUIT");
    let _ = session.read_reply();

    Ok(SmtpSendResult {
        message_id: resolved_message_id,
        server_response,
    })
}

fn expect_greeting(session: &mut SmtpSession) -> Result<(), KazeCoreError> {
    let (code, text) = session.read_reply()?;
    if code == 220 {
        Ok(())
    } else {
        Err(KazeCoreError::ProtocolError {
            detail: format!("Unexpected SMTP greeting {code}: {text}"),
        })
    }
}

fn authenticate_smtp(
    session: &mut SmtpSession,
    ehlo_text: &str,
    auth: &SmtpAuth,
) -> Result<(), KazeCoreError> {
    let advertised_mechs = parse_advertised_auth_mechs(ehlo_text);

    match auth {
        SmtpAuth::OAuth2 {
            email,
            access_token,
        } => {
            let bare_email = extract_bare_email(email)?;
            let xoauth = format!("user={bare_email}\x01auth=Bearer {access_token}\x01\x01");
            let b64 = BASE64.encode(xoauth.as_bytes());
            session.send_line(&format!("AUTH XOAUTH2 {b64}"))?;
            let (code, text) = session.read_reply()?;
            match code {
                235 => Ok(()),
                334 => {
                    // Server returned base64-encoded SASL challenge error; send blank line to finish exchange.
                    let _ = session.send_line("");
                    let (final_code, final_text) = session.read_reply().unwrap_or((code, text));
                    Err(KazeCoreError::AuthFailed {
                        detail: format!("SMTP XOAUTH2 failed ({final_code}): {final_text}"),
                    })
                }
                _ => Err(KazeCoreError::AuthFailed {
                    detail: format!("SMTP XOAUTH2 failed ({code}): {text}"),
                }),
            }
        }
        SmtpAuth::Password { username, password } => {
            let user_clean = strip_crlf(username);
            let pass_clean = strip_crlf(password);
            let use_login_only =
                !advertised_mechs.is_empty() && !advertised_mechs.contains("PLAIN") && advertised_mechs.contains("LOGIN");

            if use_login_only {
                session.command("AUTH LOGIN", &[334])?;
                session.command(&BASE64.encode(user_clean.as_bytes()), &[334])?;
                let (code, text) = session.send_line_expect_auth(&BASE64.encode(pass_clean.as_bytes()))?;
                if code == 235 {
                    Ok(())
                } else {
                    Err(KazeCoreError::AuthFailed {
                        detail: format!("SMTP AUTH LOGIN failed ({code}): {text}"),
                    })
                }
            } else {
                let mut payload = Vec::with_capacity(user_clean.len() + pass_clean.len() + 2);
                payload.push(0);
                payload.extend_from_slice(user_clean.as_bytes());
                payload.push(0);
                payload.extend_from_slice(pass_clean.as_bytes());
                let b64 = BASE64.encode(&payload);

                let (code, text) = session.send_line_expect_auth(&format!("AUTH PLAIN {b64}"))?;
                if code == 235 {
                    Ok(())
                } else if code == 334 {
                    let (c2, t2) = session.send_line_expect_auth(&b64)?;
                    if c2 == 235 {
                        Ok(())
                    } else {
                        Err(KazeCoreError::AuthFailed {
                            detail: format!("SMTP AUTH PLAIN failed ({c2}): {t2}"),
                        })
                    }
                } else {
                    Err(KazeCoreError::AuthFailed {
                        detail: format!("SMTP AUTH PLAIN failed ({code}): {text}"),
                    })
                }
            }
        }
    }
}

impl SmtpSession {
    fn send_line_expect_auth(&mut self, line: &str) -> Result<(u16, String), KazeCoreError> {
        self.send_line(line)?;
        self.read_reply()
    }
}

fn parse_advertised_auth_mechs(ehlo_text: &str) -> HashSet<String> {
    let mut set = HashSet::new();
    for line in ehlo_text.lines() {
        let upper = line.trim().to_ascii_uppercase();
        if let Some(rest) = upper.strip_prefix("AUTH") {
            let rest = rest.trim_start_matches(&[' ', '='][..]);
            for token in rest.split_whitespace() {
                set.insert(token.to_string());
            }
        }
    }
    set
}

fn collect_envelope_recipients(msg: &OutgoingEmailMessage) -> Result<Vec<String>, KazeCoreError> {
    let mut seen = HashSet::new();
    let mut out = Vec::new();
    for raw in msg
        .to_addresses
        .iter()
        .chain(msg.cc_addresses.iter())
        .chain(msg.bcc_addresses.iter())
    {
        let trimmed = raw.trim();
        if trimmed.is_empty() {
            continue;
        }
        let bare = extract_bare_email(trimmed)?;
        let key = bare.to_ascii_lowercase();
        if seen.insert(key) {
            out.push(bare);
        }
    }
    if out.is_empty() {
        return Err(KazeCoreError::InvalidInput {
            detail: "At least one recipient (To/Cc/Bcc) is required".to_string(),
        });
    }
    Ok(out)
}

pub fn ensure_message_id(explicit: Option<&str>, from_bare_email: &str) -> String {
    if let Some(id) = explicit.map(str::trim).filter(|s| !s.is_empty()) {
        let clean = strip_crlf(id);
        if clean.starts_with('<') && clean.ends_with('>') {
            return clean;
        }
        return format!("<{clean}>");
    }
    let domain = from_bare_email
        .split('@')
        .nth(1)
        .filter(|d| !d.is_empty())
        .unwrap_or("kazemail.local");
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default();
    let seq = MSG_ID_SEQ.fetch_add(1, Ordering::Relaxed);
    format!("<km.{}.{}.{seq}@{domain}>", now.as_secs(), now.subsec_nanos())
}

pub fn build_rfc5322_message(
    msg: &OutgoingEmailMessage,
    resolved_message_id: &str,
) -> Result<Vec<u8>, KazeCoreError> {
    let from_bare = extract_bare_email(&msg.from_address)?;
    let from_header = format_mailbox(msg.from_name.as_deref(), &from_bare);

    let to_formatted = format_address_list(&msg.to_addresses)?;
    let cc_formatted = format_address_list(&msg.cc_addresses)?;

    let mut raw = String::with_capacity(4096);
    raw.push_str(&format!("Date: {}\r\n", Local::now().to_rfc2822()));
    raw.push_str(&format!("From: {from_header}\r\n"));
    if !to_formatted.is_empty() {
        raw.push_str(&format!("To: {}\r\n", to_formatted.join(",\r\n ")));
    }
    if !cc_formatted.is_empty() {
        raw.push_str(&format!("Cc: {}\r\n", cc_formatted.join(",\r\n ")));
    }
    // Note: Bcc MUST NOT appear in RFC 5322 DATA headers.
    raw.push_str(&format!("Subject: {}\r\n", encode_rfc2047_if_needed(&strip_crlf(&msg.subject))));
    raw.push_str(&format!("Message-ID: {}\r\n", strip_crlf(resolved_message_id)));

    if let Some(reply_to) = msg.in_reply_to.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
        raw.push_str(&format!("In-Reply-To: {}\r\n", wrap_angle_id(reply_to)));
    }
    let clean_refs: Vec<String> = msg
        .references
        .iter()
        .map(|r| r.trim())
        .filter(|r| !r.is_empty())
        .map(wrap_angle_id)
        .collect();
    if !clean_refs.is_empty() {
        raw.push_str(&format!("References: {}\r\n", clean_refs.join("\r\n ")));
    }
    raw.push_str("MIME-Version: 1.0\r\n");

    let has_attachments = !msg.attachments.is_empty();
    let has_html = msg.html_body.as_deref().is_some_and(|s| !s.is_empty());
    let now_nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();

    if !has_attachments && !has_html {
        append_text_part_headers_and_body(&mut raw, "text/plain", &msg.plain_text);
    } else if !has_attachments {
        let alt_boundary = format!("=_KazeAlt_{now_nanos}");
        raw.push_str(&format!(
            "Content-Type: multipart/alternative; boundary=\"{alt_boundary}\"\r\n\r\n"
        ));
        raw.push_str(&format!("--{alt_boundary}\r\n"));
        append_text_part_headers_and_body(&mut raw, "text/plain", &msg.plain_text);
        if let Some(html) = msg.html_body.as_deref().filter(|s| !s.is_empty()) {
            raw.push_str(&format!("\r\n--{alt_boundary}\r\n"));
            append_text_part_headers_and_body(&mut raw, "text/html", html);
        }
        raw.push_str(&format!("\r\n--{alt_boundary}--\r\n"));
    } else {
        let mixed_boundary = format!("=_KazeMix_{now_nanos}");
        raw.push_str(&format!(
            "Content-Type: multipart/mixed; boundary=\"{mixed_boundary}\"\r\n\r\n"
        ));
        raw.push_str(&format!("--{mixed_boundary}\r\n"));

        if let Some(html) = msg.html_body.as_deref().filter(|s| !s.is_empty()) {
            let alt_boundary = format!("=_KazeAlt_{now_nanos}");
            raw.push_str(&format!(
                "Content-Type: multipart/alternative; boundary=\"{alt_boundary}\"\r\n\r\n"
            ));
            raw.push_str(&format!("--{alt_boundary}\r\n"));
            append_text_part_headers_and_body(&mut raw, "text/plain", &msg.plain_text);
            raw.push_str(&format!("\r\n--{alt_boundary}\r\n"));
            append_text_part_headers_and_body(&mut raw, "text/html", html);
            raw.push_str(&format!("\r\n--{alt_boundary}--\r\n"));
        } else {
            append_text_part_headers_and_body(&mut raw, "text/plain", &msg.plain_text);
        }

        for att in &msg.attachments {
            append_attachment_part(&mut raw, &mixed_boundary, att)?;
        }
        raw.push_str(&format!("\r\n--{mixed_boundary}--\r\n"));
    }

    Ok(raw.into_bytes())
}

fn append_text_part_headers_and_body(out: &mut String, subtype: &str, text: &str) {
    out.push_str(&format!(
        "Content-Type: {subtype}; charset=\"utf-8\"\r\nContent-Transfer-Encoding: base64\r\n\r\n"
    ));
    let normalized = text.replace("\r\n", "\n").replace('\n', "\r\n");
    push_wrapped_base64(out, normalized.as_bytes());
}

fn append_attachment_part(
    out: &mut String,
    boundary: &str,
    att: &OutgoingAttachmentSpec,
) -> Result<(), KazeCoreError> {
    let bytes = fs::read(&att.file_path).map_err(|e| KazeCoreError::IoError {
        detail: format!("Failed to read attachment '{}': {e}", att.filename),
    })?;
    let clean_name = strip_crlf(&att.filename).replace('"', "_");
    let encoded_name = encode_rfc2047_if_needed(&clean_name);
    let clean_mime = sanitize_mime_type(&att.mime_type);

    out.push_str(&format!("\r\n--{boundary}\r\n"));
    out.push_str(&format!(
        "Content-Type: {clean_mime}; name=\"{encoded_name}\"\r\n"
    ));
    out.push_str("Content-Transfer-Encoding: base64\r\n");
    out.push_str(&format!(
        "Content-Disposition: attachment; filename=\"{encoded_name}\"\r\n\r\n"
    ));
    push_wrapped_base64(out, &bytes);
    Ok(())
}

fn push_wrapped_base64(out: &mut String, bytes: &[u8]) {
    let encoded = BASE64.encode(bytes);
    for chunk in encoded.as_bytes().chunks(76) {
        if let Ok(line) = std::str::from_utf8(chunk) {
            out.push_str(line);
            out.push_str("\r\n");
        }
    }
}

fn format_address_list(list: &[String]) -> Result<Vec<String>, KazeCoreError> {
    let mut formatted = Vec::new();
    for raw in list {
        let trimmed = raw.trim();
        if trimmed.is_empty() {
            continue;
        }
        let bare = extract_bare_email(trimmed)?;
        formatted.push(format!("<{bare}>"));
    }
    Ok(formatted)
}

fn format_mailbox(display_name: Option<&str>, bare_email: &str) -> String {
    let name = display_name.map(strip_crlf).unwrap_or_default();
    let trimmed = name.trim();
    if trimmed.is_empty() {
        format!("<{bare_email}>")
    } else if trimmed.is_ascii() && !trimmed.chars().any(|c| matches!(c, '"' | '\\' | '<' | '>' | ',' | ';')) {
        format!("\"{trimmed}\" <{bare_email}>")
    } else {
        format!("{} <{bare_email}>", encode_rfc2047_if_needed(trimmed))
    }
}

fn extract_bare_email(raw: &str) -> Result<String, KazeCoreError> {
    let clean = strip_crlf(raw);
    let trimmed = clean.trim();
    let candidate = if let (Some(start), Some(end)) = (trimmed.rfind('<'), trimmed.rfind('>')) {
        if end > start + 1 {
            &trimmed[start + 1..end]
        } else {
            trimmed
        }
    } else {
        trimmed
    };
    let final_addr = candidate.trim().trim_matches(&['<', '>', '"'][..]);
    if final_addr.is_empty()
        || !final_addr.contains('@')
        || final_addr.starts_with('@')
        || final_addr.ends_with('@')
        || final_addr.contains(char::is_whitespace)
    {
        return Err(KazeCoreError::InvalidInput {
            detail: format!("Invalid email address: '{raw}'"),
        });
    }
    Ok(final_addr.to_string())
}

fn encode_rfc2047_if_needed(value: &str) -> String {
    if value.is_ascii() && !value.contains("=?") {
        return value.to_string();
    }
    if value.is_empty() {
        return String::new();
    }

    let mut words = Vec::new();
    let mut chunk_bytes = String::new();
    for ch in value.chars() {
        if chunk_bytes.len() + ch.len_utf8() > 42 && !chunk_bytes.is_empty() {
            words.push(format!("=?UTF-8?B?{}?=", BASE64.encode(chunk_bytes.as_bytes())));
            chunk_bytes.clear();
        }
        chunk_bytes.push(ch);
    }
    if !chunk_bytes.is_empty() {
        words.push(format!("=?UTF-8?B?{}?=", BASE64.encode(chunk_bytes.as_bytes())));
    }
    words.join("\r\n ")
}

fn wrap_angle_id(raw: &str) -> String {
    let clean = strip_crlf(raw);
    let t = clean.trim();
    if t.starts_with('<') && t.ends_with('>') {
        t.to_string()
    } else {
        format!("<{}>", t.trim_matches(&['<', '>'][..]))
    }
}

fn sanitize_mime_type(mime: &str) -> String {
    let clean = strip_crlf(mime);
    let trimmed = clean.trim();
    if trimmed.contains('/') && !trimmed.contains(char::is_whitespace) && !trimmed.contains(';') {
        trimmed.to_string()
    } else {
        "application/octet-stream".to_string()
    }
}

fn strip_crlf(s: &str) -> String {
    s.chars().filter(|&c| c != '\r' && c != '\n').collect()
}
