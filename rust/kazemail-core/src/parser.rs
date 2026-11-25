use mail_parser::{DecodeProblems, HeaderName, Message, MessageParser, MessagePart, PartKind, html_to_text};

use crate::compression::compress_zstd;
use crate::encoding::{
    contains_iso2022_escape, decode_bytes_with_japanese_fallback, recover_header_text_if_needed,
};
use crate::error::KazeCoreError;
use crate::models::{ParsedAttachmentMeta, ParsedEmailRecord, ParsedRecipient};

pub const MAX_STORED_BODY_BYTES: usize = 500 * 1024;
pub const GIANT_EMAIL_THRESHOLD_BYTES: u64 = 10 * 1024 * 1024;
const MAX_RAW_HEADERS_BYTES: usize = 64 * 1024;
const SNIPPET_MAX_CHARS: usize = 80;

pub fn parse_raw_email(
    uid: u32,
    raw_bytes: &[u8],
    is_read: bool,
    is_flagged: bool,
    internal_date_ms: Option<i64>,
    reported_size_bytes: Option<u64>,
) -> Result<ParsedEmailRecord, KazeCoreError> {
    let parser = MessageParser::new();
    let msg = parser
        .parse(raw_bytes)
        .ok_or_else(|| KazeCoreError::ParseError {
            detail: format!("Failed to parse RFC5322 message for UID {uid}"),
        })?;

    let (mut plain_text, mut html_text, mut truncated) = extract_bodies_with_fallback(&msg);
    let attachments = extract_attachments_from_message(&msg);

    if plain_text.is_empty() {
        if let Some(ref html) = html_text {
            plain_text = html_to_text(html);
        }
    }

    let (plain_bounded, plain_cut) = truncate_utf8_bytes(&plain_text, MAX_STORED_BODY_BYTES);
    plain_text = plain_bounded;
    truncated |= plain_cut;

    let html_zstd = match html_text.take() {
        Some(html) if !html.is_empty() => {
            let (html_bounded, html_cut) = truncate_utf8_bytes(&html, MAX_STORED_BODY_BYTES);
            truncated |= html_cut;
            Some(compress_zstd(html_bounded.as_bytes())?)
        }
        _ => None,
    };

    build_record_from_message(
        uid,
        &msg,
        plain_text,
        html_zstd,
        attachments,
        is_read,
        is_flagged,
        internal_date_ms,
        reported_size_bytes.unwrap_or(raw_bytes.len() as u64),
        truncated,
    )
}

/// Builds a ParsedEmailRecord from fetched header bytes + selectively fetched text/html bodies +
/// BODYSTRUCTURE attachment metadata (avoiding downloading attachment binaries or >512KB payloads).
pub fn parse_header_and_parts(
    uid: u32,
    header_bytes: &[u8],
    plain_parts: Vec<String>,
    html_parts: Vec<String>,
    attachments: Vec<ParsedAttachmentMeta>,
    is_read: bool,
    is_flagged: bool,
    internal_date_ms: Option<i64>,
    reported_size_bytes: u64,
    pre_truncated: bool,
) -> Result<ParsedEmailRecord, KazeCoreError> {
    let mut header_msg_bytes = Vec::with_capacity(header_bytes.len() + 4);
    header_msg_bytes.extend_from_slice(header_bytes);
    if !header_msg_bytes.ends_with(b"\r\n\r\n") {
        header_msg_bytes.extend_from_slice(b"\r\n\r\n");
    }
    let parser = MessageParser::new();
    let msg = parser
        .parse(&header_msg_bytes)
        .ok_or_else(|| KazeCoreError::ParseError {
            detail: format!("Failed to parse headers for UID {uid}"),
        })?;

    let mut plain_text = plain_parts
        .into_iter()
        .filter(|s| !s.is_empty())
        .collect::<Vec<_>>()
        .join("\n\n");
    let mut html_joined = html_parts
        .into_iter()
        .filter(|s| !s.is_empty())
        .collect::<Vec<_>>()
        .join("\n");

    if html_joined.is_empty() && looks_like_html_document(&plain_text) {
        html_joined = plain_text.clone();
        plain_text = html_to_text(&html_joined);
    } else if plain_text.is_empty() && !html_joined.is_empty() {
        plain_text = html_to_text(&html_joined);
    }

    let (plain_bounded, plain_cut) = truncate_utf8_bytes(&plain_text, MAX_STORED_BODY_BYTES);
    plain_text = plain_bounded;
    let mut truncated = pre_truncated || plain_cut;

    let html_zstd = if !html_joined.is_empty() {
        let (html_bounded, html_cut) = truncate_utf8_bytes(&html_joined, MAX_STORED_BODY_BYTES);
        truncated |= html_cut;
        Some(compress_zstd(html_bounded.as_bytes())?)
    } else {
        None
    };

    build_record_from_message(
        uid,
        &msg,
        plain_text,
        html_zstd,
        attachments,
        is_read,
        is_flagged,
        internal_date_ms,
        reported_size_bytes,
        truncated,
    )
}

#[allow(clippy::too_many_arguments)]
fn build_record_from_message(
    uid: u32,
    msg: &Message<'_>,
    plain_text: String,
    html_zstd: Option<Vec<u8>>,
    attachments: Vec<ParsedAttachmentMeta>,
    is_read: bool,
    is_flagged: bool,
    internal_date_ms: Option<i64>,
    size_bytes: u64,
    is_truncated: bool,
) -> Result<ParsedEmailRecord, KazeCoreError> {
    let headers = msg.headers();

    let raw_subject = headers.get("Subject").map(|h| h.raw_value());
    let subject = recover_header_text_if_needed(msg.subject(), raw_subject);

    let (sender_name, sender_address) = extract_sender(msg);
    let recipients = extract_recipients_to(msg);
    let recipients_to_json =
        serde_json::to_string(&recipients).map_err(|e| KazeCoreError::ParseError {
            detail: format!("Failed to serialize recipients: {e}"),
        })?;

    let message_id_header = msg.message_id().map(normalize_msg_id).filter(|s| !s.is_empty());
    let in_reply_to = msg
        .in_reply_to()
        .and_then(|list| list.first())
        .map(normalize_msg_id)
        .filter(|s| !s.is_empty());

    let references: Vec<String> = msg
        .references()
        .map(|list| {
            list.iter()
                .map(normalize_msg_id)
                .filter(|s| !s.is_empty())
                .collect()
        })
        .unwrap_or_default();

    let thread_key = compute_thread_key(
        message_id_header.as_deref(),
        in_reply_to.as_deref(),
        &references,
        subject.as_deref(),
        uid,
    );

    let received_at_ms = msg
        .date()
        .map(|dt| dt.to_timestamp() * 1000)
        .or(internal_date_ms)
        .unwrap_or(0);

    let snippet = build_snippet(&plain_text);

    let raw_headers_slice = msg.root_part().raw_headers();
    let (raw_headers_decoded, _) = decode_bytes_with_japanese_fallback(raw_headers_slice, Some("utf-8"));
    let (raw_headers, _) = truncate_utf8_bytes(&raw_headers_decoded, MAX_RAW_HEADERS_BYTES);

    let has_attachments = !attachments.is_empty();

    Ok(ParsedEmailRecord {
        uid,
        message_id_header,
        in_reply_to,
        references,
        thread_key,
        sender_name,
        sender_address,
        recipients_to_json,
        subject,
        snippet,
        received_at_ms,
        is_read,
        is_flagged,
        has_attachments,
        size_bytes,
        plain_text,
        html_zstd,
        raw_headers,
        is_truncated,
        attachments,
    })
}

fn extract_bodies_with_fallback(msg: &Message<'_>) -> (String, Option<String>, bool) {
    let mut plain_segments = Vec::new();
    let mut html_segments = Vec::new();
    let mut truncated = false;

    for part in msg.html_body() {
        match part.kind() {
            PartKind::Html => {
                let (text, cut) = decode_part_text_bounded(&part);
                truncated |= cut;
                if has_meaningful_html_content(&text) {
                    html_segments.push(text);
                }
            }
            PartKind::Text => {
                // mail-parser 1.0 copies plain-text parts into html_body() as stand-ins.
                // Only treat a text/plain part as HTML when its body actually contains HTML markup.
                let (text, cut) = decode_part_text_bounded(&part);
                truncated |= cut;
                if looks_like_html_document(&text) {
                    html_segments.push(text);
                }
            }
            _ => {}
        }
    }

    for part in msg.text_body() {
        match part.kind() {
            PartKind::Text => {
                let (text, cut) = decode_part_text_bounded(&part);
                truncated |= cut;
                if !text.is_empty() {
                    if looks_like_html_document(&text) {
                        if html_segments.is_empty() {
                            html_segments.push(text.clone());
                        }
                        let stripped = html_to_text(&text);
                        if !stripped.trim().is_empty() {
                            plain_segments.push(stripped);
                        }
                    } else {
                        plain_segments.push(text);
                    }
                }
            }
            PartKind::Html => {
                // mail-parser copies text/html into text_body() when no text/plain alternative exists.
                // Convert HTML to readable plain text so snippets/plaintext never expose raw HTML tags.
                let (html, cut) = decode_part_text_bounded(&part);
                truncated |= cut;
                let converted = html_to_text(&html);
                if !converted.trim().is_empty() {
                    plain_segments.push(converted);
                }
                if has_meaningful_html_content(&html) && html_segments.is_empty() {
                    html_segments.push(html);
                }
            }
            _ => {}
        }
    }

    let plain = plain_segments.join("\n\n");
    let html = if html_segments.is_empty() {
        None
    } else {
        Some(html_segments.join("\n"))
    };
    (plain, html, truncated)
}

const MAX_HTML_DETECT_SAMPLE_BYTES: usize = 32 * 1024;

const HTML_DETECT_TAGS: &[&[u8]] = &[
    b"html",
    b"head",
    b"body",
    b"meta",
    b"style",
    b"title",
    b"link",
    b"div",
    b"table",
    b"tbody",
    b"thead",
    b"tfoot",
    b"tr",
    b"td",
    b"th",
    b"p",
    b"br",
    b"hr",
    b"a",
    b"img",
    b"span",
    b"font",
    b"center",
    b"ul",
    b"ol",
    b"li",
    b"dl",
    b"dt",
    b"dd",
    b"h1",
    b"h2",
    b"h3",
    b"h4",
    b"h5",
    b"h6",
    b"blockquote",
    b"pre",
    b"code",
    b"strong",
    b"b",
    b"em",
    b"i",
    b"u",
    b"s",
    b"small",
    b"big",
    b"sub",
    b"sup",
    b"section",
    b"article",
    b"header",
    b"footer",
    b"main",
    b"nav",
    b"aside",
    b"figure",
    b"figcaption",
    b"svg",
];

#[derive(Default)]
struct HtmlScanSummary {
    has_doctype_html: bool,
    has_html_open: bool,
    has_html_close: bool,
    has_head_open: bool,
    has_head_close: bool,
    has_body_open: bool,
    has_body_close: bool,
    has_body_attrs: bool,
    distinct_non_root_tags: usize,
    total_recognized_tags: usize,
    closing_tag_count: usize,
    attribute_tag_count: usize,
    br_count: usize,
    has_media_or_link: bool,
}

fn find_html_tag_index(raw_name: &[u8]) -> Option<usize> {
    if raw_name.is_empty() || raw_name.len() > 12 {
        return None;
    }
    HTML_DETECT_TAGS
        .iter()
        .position(|&tag| tag.eq_ignore_ascii_case(raw_name))
}

fn slice_utf8_prefix(text: &str, max_bytes: usize) -> &[u8] {
    if text.len() <= max_bytes {
        return text.as_bytes();
    }
    let mut end = max_bytes;
    while end > 0 && !text.is_char_boundary(end) {
        end -= 1;
    }
    &text.as_bytes()[..end]
}

fn starts_with_ignore_ascii_case(haystack: &[u8], needle: &[u8]) -> bool {
    haystack.len() >= needle.len() && haystack[..needle.len()].eq_ignore_ascii_case(needle)
}

fn contains_ascii_attribute(slice: &[u8], attr_with_eq: &[u8]) -> bool {
    if slice.len() < attr_with_eq.len() {
        return false;
    }
    for pos in 0..=(slice.len() - attr_with_eq.len()) {
        let boundary_ok = pos == 0 || slice[pos - 1].is_ascii_whitespace();
        if boundary_ok && slice[pos..pos + attr_with_eq.len()].eq_ignore_ascii_case(attr_with_eq) {
            return true;
        }
    }
    false
}

fn find_closing_gt_in_open_tag(bytes: &[u8], name_end: usize) -> Option<(usize, bool)> {
    let limit = bytes.len().min(name_end + 2048);
    let mut in_quote: Option<u8> = None;
    let mut has_eq = false;
    let mut trailing_words = 0usize;
    let mut in_word = false;
    let mut last_non_ws: Option<u8> = None;

    for pos in name_end..limit {
        let b = bytes[pos];
        if let Some(q) = in_quote {
            if b == q {
                in_quote = None;
            }
            continue;
        }
        match b {
            b'>' => {
                // Reject plain-text prose like "<b and c > d" (multiple non-attribute tokens without '=')
                let self_closing_slash_only = last_non_ws == Some(b'/') && trailing_words == 1;
                if !has_eq && trailing_words >= 2 && !self_closing_slash_only {
                    return None;
                }
                return Some((pos, has_eq));
            }
            b'<' => return None,
            b'"' | b'\'' => {
                in_quote = Some(b);
                if !in_word {
                    trailing_words += 1;
                    in_word = true;
                }
                last_non_ws = Some(b);
            }
            _ if b.is_ascii_whitespace() => {
                in_word = false;
            }
            b'=' => {
                has_eq = true;
                last_non_ws = Some(b);
            }
            _ => {
                if !in_word {
                    if !(b == b'/' && pos + 1 < bytes.len() && bytes[pos + 1] == b'>') {
                        trailing_words += 1;
                    }
                    in_word = true;
                }
                last_non_ws = Some(b);
            }
        }
    }
    None
}

fn find_fenced_code_end(bytes: &[u8], line_start: usize) -> Option<usize> {
    let mut pos = line_start;
    let mut spaces = 0usize;
    while pos < bytes.len() && bytes[pos] == b' ' && spaces < 4 {
        spaces += 1;
        pos += 1;
    }
    if spaces >= 4 || pos + 3 > bytes.len() || &bytes[pos..pos + 3] != b"```" {
        return None;
    }
    // Advance past opening fence line
    while pos < bytes.len() && bytes[pos] != b'\n' {
        pos += 1;
    }
    if pos < bytes.len() {
        pos += 1;
    }
    while pos < bytes.len() {
        let cur_line_start = pos;
        let mut p = cur_line_start;
        let mut s = 0usize;
        while p < bytes.len() && bytes[p] == b' ' && s < 4 {
            s += 1;
            p += 1;
        }
        let is_close = s < 4 && p + 3 <= bytes.len() && &bytes[p..p + 3] == b"```";
        while pos < bytes.len() && bytes[pos] != b'\n' {
            pos += 1;
        }
        if pos < bytes.len() {
            pos += 1;
        }
        if is_close {
            return Some(pos);
        }
    }
    Some(bytes.len())
}

fn find_inline_backtick_end(bytes: &[u8], open_tick: usize) -> Option<usize> {
    let limit = bytes.len().min(open_tick + 256);
    for pos in (open_tick + 1)..limit {
        let b = bytes[pos];
        if b == b'\n' || b == b'\r' {
            return None;
        }
        if b == b'`' {
            return Some(pos + 1);
        }
    }
    None
}

fn analyze_html_structure(text: &str, ignore_markdown_code: bool) -> HtmlScanSummary {
    let bytes = slice_utf8_prefix(text, MAX_HTML_DETECT_SAMPLE_BYTES);
    let mut summary = HtmlScanSummary::default();
    let mut seen_tags = [false; HTML_DETECT_TAGS.len()];
    let mut i = 0usize;
    let mut at_line_start = true;

    while i < bytes.len() {
        if ignore_markdown_code && at_line_start {
            if let Some(end_idx) = find_fenced_code_end(bytes, i) {
                i = end_idx;
                at_line_start = true;
                continue;
            }
        }
        if ignore_markdown_code && bytes[i] == b'`' {
            if let Some(end_idx) = find_inline_backtick_end(bytes, i) {
                i = end_idx;
                at_line_start = false;
                continue;
            }
        }

        if bytes[i] != b'<' {
            at_line_start = bytes[i] == b'\n';
            i += 1;
            continue;
        }
        at_line_start = false;

        // Skip HTML comments <!-- ... --> so commented markup doesn't skew counts
        if bytes[i..].starts_with(b"<!--") {
            if let Some(rel_end) = bytes[i + 4..].windows(3).position(|w| w == b"-->") {
                i += 4 + rel_end + 3;
            } else {
                break;
            }
            continue;
        }

        // Check <!DOCTYPE html ...>
        if starts_with_ignore_ascii_case(&bytes[i..], b"<!doctype") {
            let mut p = i + 9;
            let mut ws = 0usize;
            while p < bytes.len() && bytes[p].is_ascii_whitespace() {
                ws += 1;
                p += 1;
            }
            if ws >= 1 && starts_with_ignore_ascii_case(&bytes[p..], b"html") {
                let after = p + 4;
                let boundary = bytes.get(after).is_some_and(|&b| b == b'>' || b.is_ascii_whitespace());
                if boundary {
                    if let Some(rel_gt) = bytes[after..bytes.len().min(after + 512)]
                        .iter()
                        .position(|&b| b == b'>')
                    {
                        summary.has_doctype_html = true;
                        summary.total_recognized_tags += 1;
                        i = after + rel_gt + 1;
                        continue;
                    }
                }
            }
            i += 1;
            continue;
        }

        let is_closing = bytes.get(i + 1) == Some(&b'/');
        let name_start = if is_closing { i + 2 } else { i + 1 };
        let mut name_end = name_start;
        while name_end < bytes.len() && bytes[name_end].is_ascii_alphanumeric() {
            name_end += 1;
        }
        let raw_name = &bytes[name_start..name_end];
        let Some(tag_idx) = find_html_tag_index(raw_name) else {
            i += 1;
            continue;
        };

        if is_closing {
            let mut p = name_end;
            let limit = bytes.len().min(name_end + 32);
            while p < limit && bytes[p].is_ascii_whitespace() {
                p += 1;
            }
            if bytes.get(p) != Some(&b'>') {
                i += 1;
                continue;
            }
            summary.closing_tag_count += 1;
            summary.total_recognized_tags += 1;
            let tag_bytes = HTML_DETECT_TAGS[tag_idx];
            let is_root = matches!(tag_bytes, b"html" | b"head" | b"body");
            if !seen_tags[tag_idx] {
                seen_tags[tag_idx] = true;
                if !is_root {
                    summary.distinct_non_root_tags += 1;
                }
            }
            match tag_bytes {
                b"html" => summary.has_html_close = true,
                b"head" => summary.has_head_close = true,
                b"body" => summary.has_body_close = true,
                _ => {}
            }
            i = p + 1;
            continue;
        }

        // Opening or void tag: verify immediate boundary after tag name
        let boundary_ok = match bytes.get(name_end) {
            Some(&b) if b == b'>' || b.is_ascii_whitespace() => true,
            Some(&b'/') => bytes
                .get(name_end + 1)
                .is_some_and(|&n| n == b'>' || n.is_ascii_whitespace()),
            _ => false,
        };
        if !boundary_ok {
            i += 1;
            continue;
        }

        let Some((gt_pos, has_eq)) = find_closing_gt_in_open_tag(bytes, name_end) else {
            i += 1;
            continue;
        };

        let inner = &bytes[name_end..gt_pos];
        let tag_bytes = HTML_DETECT_TAGS[tag_idx];
        let is_root = matches!(tag_bytes, b"html" | b"head" | b"body");
        summary.total_recognized_tags += 1;
        if !seen_tags[tag_idx] {
            seen_tags[tag_idx] = true;
            if !is_root {
                summary.distinct_non_root_tags += 1;
            }
        }
        if has_eq {
            summary.attribute_tag_count += 1;
        }

        match tag_bytes {
            b"html" => summary.has_html_open = true,
            b"head" => summary.has_head_open = true,
            b"body" => {
                summary.has_body_open = true;
                if has_eq {
                    summary.has_body_attrs = true;
                }
            }
            b"br" => summary.br_count += 1,
            b"hr" | b"img" | b"svg" => summary.has_media_or_link = true,
            b"a" if contains_ascii_attribute(inner, b"href=") => {
                summary.has_media_or_link = true;
            }
            _ => {}
        }

        i = gt_pos + 1;
    }

    summary
}

fn has_meaningful_html_content(html: &str) -> bool {
    let trimmed = html.trim();
    if trimmed.is_empty() || !trimmed.contains('<') || !trimmed.contains('>') {
        return false;
    }
    let summary = analyze_html_structure(trimmed, false);
    if !summary.has_doctype_html && summary.total_recognized_tags == 0 {
        return false;
    }
    if summary.has_media_or_link {
        return true;
    }
    !html_to_text(trimmed).trim().is_empty()
}

fn looks_like_html_document(text: &str) -> bool {
    let trimmed = text.trim();
    if trimmed.is_empty() || !trimmed.contains('<') || !trimmed.contains('>') {
        return false;
    }
    let s = analyze_html_structure(trimmed, true);
    if s.has_doctype_html {
        return true;
    }
    let has_markup_evidence = s.closing_tag_count >= 1 || s.attribute_tag_count >= 1;
    if !has_markup_evidence && s.br_count < 2 {
        return false;
    }
    if s.has_html_open && (s.has_body_open || s.has_head_open || s.has_html_close || s.distinct_non_root_tags >= 1) {
        return true;
    }
    if s.has_body_open && (s.has_body_close || s.has_body_attrs || s.distinct_non_root_tags >= 1) {
        return true;
    }
    if s.has_head_open && (s.has_head_close || s.distinct_non_root_tags >= 1) {
        return true;
    }
    if s.distinct_non_root_tags >= 2 {
        return true;
    }
    if s.attribute_tag_count >= 1 && s.closing_tag_count >= 1 {
        return true;
    }
    s.br_count >= 2
}

fn decode_part_text_bounded(part: &MessagePart<'_>) -> (String, bool) {
    let raw_decoded = part.decoded();
    let mut truncated = false;
    let bounded_bytes: &[u8] = if raw_decoded.len() > MAX_STORED_BODY_BYTES * 2 {
        truncated = true;
        &raw_decoded[..MAX_STORED_BODY_BYTES * 2]
    } else {
        &raw_decoded
    };

    let declared_charset = part
        .content_type()
        .and_then(|ct| ct.attribute("charset"));

    if let Some((text, problems)) = part.text_checked() {
        let needs_fallback = problems
            .intersects(DecodeProblems::MALFORMED_CHARSET | DecodeProblems::UNKNOWN_CHARSET)
            || text.contains('\u{FFFD}')
            || contains_iso2022_escape(text.as_bytes());
        if !needs_fallback && !truncated {
            return (text.into_owned(), false);
        }
    }

    let (recovered, _) = decode_bytes_with_japanese_fallback(bounded_bytes, declared_charset);
    let (bounded_str, str_cut) = truncate_utf8_bytes(&recovered, MAX_STORED_BODY_BYTES);
    (bounded_str, truncated || str_cut)
}

fn extract_attachments_from_message(msg: &Message<'_>) -> Vec<ParsedAttachmentMeta> {
    let mut part_id_map = std::collections::HashMap::new();
    let mut path = Vec::new();
    collect_imap_part_ids(msg.root_part(), &mut path, true, &mut part_id_map);

    msg.attachments()
        .enumerate()
        .map(|(idx, part)| {
            let ct = part
                .content_type()
                .map(|c| {
                    let sub = c.subtype().unwrap_or("octet-stream");
                    format!("{}/{}", c.ctype().to_ascii_lowercase(), sub.to_ascii_lowercase())
                })
                .unwrap_or_else(|| "application/octet-stream".to_string());
            let filename = part
                .attachment_name()
                .map(|n| recover_header_text_if_needed(Some(n), None).unwrap_or_else(|| n.to_string()))
                .unwrap_or_else(|| format!("attachment_{}.bin", idx + 1));
            let imap_part_id = part_id_map
                .get(&part.id())
                .cloned()
                .unwrap_or_else(|| part.id().to_string());
            ParsedAttachmentMeta {
                part_id: imap_part_id,
                filename,
                mime_type: ct,
                size_bytes: part.decoded_len() as u64,
            }
        })
        .collect()
}

fn collect_imap_part_ids(
    part: MessagePart<'_>,
    path: &mut Vec<u32>,
    is_root: bool,
    out: &mut std::collections::HashMap<mail_parser::PartId, String>,
) {
    let current_id = if is_root || path.is_empty() {
        "1".to_string()
    } else {
        path.iter()
            .map(|n| n.to_string())
            .collect::<Vec<_>>()
            .join(".")
    };

    match part.kind() {
        PartKind::Multipart => {
            for (idx, child) in part.children().enumerate() {
                path.push((idx + 1) as u32);
                collect_imap_part_ids(child, path, false, out);
                path.pop();
            }
        }
        PartKind::Message(nested) => {
            out.insert(part.id(), current_id);
            collect_imap_part_ids(nested.root_part(), path, is_root, out);
        }
        _ => {
            out.insert(part.id(), current_id);
        }
    }
}

fn extract_sender(msg: &Message<'_>) -> (Option<String>, String) {
    let raw_from = msg.headers().get(HeaderName::From).map(|h| h.raw_value());
    if let Some(addr_list) = msg.from() {
        if let Some(addr) = addr_list.first() {
            let name = addr
                .name()
                .and_then(|n| recover_header_text_if_needed(Some(n), raw_from));
            let address = addr
                .address()
                .map(str::to_string)
                .unwrap_or_else(|| "unknown@unknown".to_string());
            return (name, address);
        }
    }
    (None, "unknown@unknown".to_string())
}

fn extract_recipients_to(msg: &Message<'_>) -> Vec<ParsedRecipient> {
    let Some(to_list) = msg.to() else {
        return Vec::new();
    };
    to_list
        .mailboxes()
        .filter_map(|mailbox| {
            mailbox.address().map(|address| ParsedRecipient {
                name: mailbox
                    .name()
                    .and_then(|n| recover_header_text_if_needed(Some(n), None)),
                address: address.to_string(),
            })
        })
        .collect()
}

pub fn build_snippet(plain_text: &str) -> String {
    let mut out = String::with_capacity(SNIPPET_MAX_CHARS * 3);
    let mut prev_space = true;
    let mut count = 0usize;

    for ch in plain_text.chars() {
        if count >= SNIPPET_MAX_CHARS {
            break;
        }
        if ch.is_whitespace() {
            if !prev_space {
                out.push(' ');
                prev_space = true;
                count += 1;
            }
        } else {
            out.push(ch);
            prev_space = false;
            count += 1;
        }
    }
    out.trim_end().to_string()
}

pub fn truncate_utf8_bytes(input: &str, max_bytes: usize) -> (String, bool) {
    if input.len() <= max_bytes {
        return (input.to_string(), false);
    }
    let mut end = max_bytes;
    while end > 0 && !input.is_char_boundary(end) {
        end -= 1;
    }
    (input[..end].to_string(), true)
}

fn normalize_msg_id(raw: &str) -> String {
    raw.trim()
        .trim_start_matches('<')
        .trim_end_matches('>')
        .trim()
        .to_string()
}

pub fn compute_thread_key(
    message_id: Option<&str>,
    in_reply_to: Option<&str>,
    references: &[String],
    subject: Option<&str>,
    uid: u32,
) -> String {
    if let Some(root_ref) = references.first().filter(|s| !s.is_empty()) {
        return format!("mid:{root_ref}");
    }
    if let Some(reply) = in_reply_to.filter(|s| !s.is_empty()) {
        return format!("mid:{reply}");
    }
    if let Some(mid) = message_id.filter(|s| !s.is_empty()) {
        return format!("mid:{mid}");
    }
    let normalized_subj = normalize_subject_for_thread(subject.unwrap_or_default());
    if !normalized_subj.is_empty() {
        format!("subj:{normalized_subj}")
    } else {
        format!("uid:{uid}")
    }
}

fn normalize_subject_for_thread(subj: &str) -> String {
    let mut s = subj.trim();
    loop {
        let lower = s.to_ascii_lowercase();
        let rest = if lower.starts_with("re:") || lower.starts_with("re：") {
            &s[3..]
        } else if lower.starts_with("fw:") || lower.starts_with("fwd:") {
            if lower.starts_with("fwd:") {
                &s[4..]
            } else {
                &s[3..]
            }
        } else {
            break;
        };
        s = rest.trim_start();
    }
    s.to_string()
}
