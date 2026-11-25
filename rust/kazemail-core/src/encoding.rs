use encoding_rs::{Encoding, EUC_JP, ISO_2022_JP, SHIFT_JIS, UTF_8, WINDOWS_1252};

/// Decodes raw bytes using the declared charset when available, with legacy Japanese
/// recovery across ISO-2022-JP (JIS), Shift_JIS / CP932, EUC-JP, and UTF-8.
///
/// Guarantees no panics and avoids returning an empty string when recoverable text exists.
pub fn decode_bytes_with_japanese_fallback(
    bytes: &[u8],
    declared_charset: Option<&str>,
) -> (String, bool) {
    if bytes.is_empty() {
        return (String::new(), false);
    }

    if let Some(label) = declared_charset.map(str::trim).filter(|s| !s.is_empty()) {
        if let Some(enc) = resolve_encoding(label) {
            let (decoded, _, had_errors) = enc.decode(bytes);
            if !had_errors && !looks_like_unconverted_iso2022(&decoded) {
                return (decoded.into_owned(), false);
            }
            // Repair malformed ISO-2022-JP sequences if declared JIS/ISO-2022-JP failed
            if enc == ISO_2022_JP || contains_iso2022_escape(bytes) {
                if let Some(repaired) = recover_iso2022_jp_mixed(bytes) {
                    return (repaired, true);
                }
            }
            // If declared UTF-8/other had errors, try Japanese fallback candidates below
            let candidate = pick_best_japanese_candidate(bytes, Some(enc));
            if replacement_count(&candidate) < replacement_count(&decoded)
                || looks_like_unconverted_iso2022(&decoded)
            {
                return (candidate, true);
            }
            return (decoded.into_owned(), had_errors);
        }
    }

    // No or unknown declared charset
    if contains_iso2022_escape(bytes) {
        let (decoded, _, had_errors) = ISO_2022_JP.decode(bytes);
        if !had_errors {
            return (decoded.into_owned(), true);
        }
        if let Some(repaired) = recover_iso2022_jp_mixed(bytes) {
            return (repaired, true);
        }
    }

    if let Ok(utf8) = std::str::from_utf8(bytes) {
        return (utf8.to_string(), false);
    }

    let best = pick_best_japanese_candidate(bytes, None);
    (best, true)
}

fn resolve_encoding(label: &str) -> Option<&'static Encoding> {
    let lower = label.trim().to_ascii_lowercase();
    match lower.as_str() {
        "cp932" | "windows-31j" | "ms932" | "x-sjis" | "shift_jis" | "shift-jis" | "sjis" => {
            Some(SHIFT_JIS)
        }
        "iso-2022-jp" | "csiso2022jp" | "jis" | "iso2022jp" | "iso-2022-jp-1" => {
            Some(ISO_2022_JP)
        }
        "euc-jp" | "euc_jp" | "x-euc-jp" | "cseucpkdfmtjapanese" => Some(EUC_JP),
        _ => Encoding::for_label(lower.as_bytes()),
    }
}

pub fn contains_iso2022_escape(bytes: &[u8]) -> bool {
    bytes.windows(3).any(|w| {
        w == b"\x1b$B" || w == b"\x1b$@" || w == b"\x1b(B" || w == b"\x1b(J" || w == b"\x1b(I"
    })
}

fn looks_like_unconverted_iso2022(s: &str) -> bool {
    let bytes = s.as_bytes();
    contains_iso2022_escape(bytes)
}

fn replacement_count(s: &str) -> usize {
    s.chars().filter(|&c| c == '\u{FFFD}').count()
}

fn japanese_char_count(s: &str) -> usize {
    s.chars()
        .filter(|&c| {
            ('\u{3040}'..='\u{30FF}').contains(&c)
                || ('\u{4E00}'..='\u{9FFF}').contains(&c)
                || ('\u{FF00}'..='\u{FFEF}').contains(&c)
        })
        .count()
}

fn score_decoded(s: &str) -> i64 {
    let rep = replacement_count(s) as i64;
    let jp = japanese_char_count(s) as i64;
    let ctrl = s
        .chars()
        .filter(|&c| c.is_control() && c != '\n' && c != '\r' && c != '\t')
        .count() as i64;
    (jp * 4) - (rep * 20) - (ctrl * 10)
}

fn pick_best_japanese_candidate(bytes: &[u8], preferred: Option<&'static Encoding>) -> String {
    let mut candidates: Vec<&'static Encoding> = Vec::with_capacity(5);
    if let Some(enc) = preferred {
        candidates.push(enc);
    }
    for enc in [UTF_8, ISO_2022_JP, SHIFT_JIS, EUC_JP, WINDOWS_1252] {
        if !candidates.contains(&enc) {
            candidates.push(enc);
        }
    }

    let mut best_str: Option<String> = None;
    let mut best_score = i64::MIN;

    for enc in candidates {
        let (decoded, _, had_errors) = enc.decode(bytes);
        if !had_errors && !looks_like_unconverted_iso2022(&decoded) && enc != WINDOWS_1252 {
            let jp_chars = japanese_char_count(decoded.as_ref());
            let s = score_decoded(&decoded);
            if enc == UTF_8 || jp_chars > 0 {
                return decoded.into_owned();
            }
            if s > best_score {
                best_score = s;
                best_str = Some(decoded.into_owned());
            }
            continue;
        }
        let s = score_decoded(&decoded);
        if s > best_score {
            best_score = s;
            best_str = Some(decoded.into_owned());
        }
    }

    best_str.unwrap_or_else(|| String::from_utf8_lossy(bytes).into_owned())
}

/// Recovers mixed/corrupted ISO-2022-JP streams where valid JIS segments coexist with
/// stray Shift_JIS/UTF-8 bytes or missing reset ESC(B sequences.
fn recover_iso2022_jp_mixed(bytes: &[u8]) -> Option<String> {
    if !bytes.contains(&0x1b) {
        return None;
    }

    let mut out = String::with_capacity(bytes.len());
    let mut i = 0;
    let mut in_jis = false;
    let mut chunk_buf: Vec<u8> = Vec::new();

    let flush_ascii_or_fallback = |buf: &mut Vec<u8>, out: &mut String| {
        if buf.is_empty() {
            return;
        }
        if let Ok(s) = std::str::from_utf8(buf) {
            out.push_str(s);
        } else {
            let (sjis, _, sjis_err) = SHIFT_JIS.decode(buf);
            if !sjis_err {
                out.push_str(&sjis);
            } else {
                let (euc, _, euc_err) = EUC_JP.decode(buf);
                if !euc_err {
                    out.push_str(&euc);
                } else {
                    out.push_str(&sjis);
                }
            }
        }
        buf.clear();
    };

    let flush_jis = |buf: &mut Vec<u8>, out: &mut String| {
        if buf.is_empty() {
            return;
        }
        let mut framed = Vec::with_capacity(buf.len() + 6);
        framed.extend_from_slice(b"\x1b$B");
        framed.extend_from_slice(buf);
        framed.extend_from_slice(b"\x1b(B");
        let (decoded, _, _) = ISO_2022_JP.decode(&framed);
        out.push_str(&decoded);
        buf.clear();
    };

    while i < bytes.len() {
        if bytes[i] == 0x1b && i + 2 < bytes.len() {
            let seq = &bytes[i..i + 3];
            if seq == b"\x1b$B" || seq == b"\x1b$@" {
                if in_jis {
                    flush_jis(&mut chunk_buf, &mut out);
                } else {
                    flush_ascii_or_fallback(&mut chunk_buf, &mut out);
                }
                in_jis = true;
                i += 3;
                continue;
            } else if seq == b"\x1b(B" || seq == b"\x1b(J" {
                if in_jis {
                    flush_jis(&mut chunk_buf, &mut out);
                } else {
                    flush_ascii_or_fallback(&mut chunk_buf, &mut out);
                }
                in_jis = false;
                i += 3;
                continue;
            } else if seq == b"\x1b(I" {
                // Half-width katakana mode in ISO-2022-JP: decode via ISO_2022_JP per byte
                if in_jis {
                    flush_jis(&mut chunk_buf, &mut out);
                } else {
                    flush_ascii_or_fallback(&mut chunk_buf, &mut out);
                }
                in_jis = false;
                i += 3;
                let mut hw = vec![0x1b, b'(', b'I'];
                while i < bytes.len() && bytes[i] != 0x1b && bytes[i] >= 0x21 && bytes[i] <= 0x5f {
                    hw.push(bytes[i]);
                    i += 1;
                }
                hw.extend_from_slice(b"\x1b(B");
                let (decoded, _, _) = ISO_2022_JP.decode(&hw);
                out.push_str(&decoded);
                continue;
            }
        }

        // In JIS mode, CR/LF/8-bit bytes indicate a broken shift-out; recover gracefully
        if in_jis && (bytes[i] == b'\r' || bytes[i] == b'\n' || bytes[i] >= 0x80) {
            flush_jis(&mut chunk_buf, &mut out);
            in_jis = false;
        }
        chunk_buf.push(bytes[i]);
        i += 1;
    }

    if in_jis {
        flush_jis(&mut chunk_buf, &mut out);
    } else {
        flush_ascii_or_fallback(&mut chunk_buf, &mut out);
    }

    if out.is_empty() {
        None
    } else {
        Some(out)
    }
}

/// Decodes raw header field bytes that may contain unencoded legacy Shift_JIS/ISO-2022-JP text
/// or malformed / unspaced / truncated RFC 2047 encoded-words (`=?ISO-2022-JP?B?...?=`).
pub fn recover_header_text_if_needed(parsed: Option<&str>, raw_header_value: Option<&[u8]>) -> Option<String> {
    if let Some(s) = parsed {
        let contains_rfc2047 = contains_encoded_word_prefix(s.as_bytes());
        if !contains_rfc2047 && !s.contains('\u{FFFD}') && !looks_like_unconverted_iso2022(s) {
            return Some(s.to_string());
        }
        if contains_rfc2047 {
            let decoded_from_parsed = decode_rfc2047_lenient(s);
            if !decoded_from_parsed.is_empty()
                && !contains_encoded_word_prefix(decoded_from_parsed.as_bytes())
                && !decoded_from_parsed.contains('\u{FFFD}')
                && !looks_like_unconverted_iso2022(&decoded_from_parsed)
            {
                return Some(decoded_from_parsed);
            }
        }
    }

    if let Some(raw) = raw_header_value {
        let trimmed = trim_ascii_ws(raw);
        if trimmed.is_empty() {
            return parsed.map(decode_rfc2047_lenient);
        }
        return Some(decode_raw_header_bytes(trimmed));
    }

    parsed.map(decode_rfc2047_lenient)
}

fn decode_raw_header_bytes(raw: &[u8]) -> String {
    let trimmed = trim_ascii_ws(raw);
    if contains_encoded_word_prefix(trimmed) {
        let ascii_or_utf8 = String::from_utf8_lossy(trimmed);
        let decoded = decode_rfc2047_lenient(&ascii_or_utf8);
        if !looks_like_unconverted_iso2022(&decoded) && !decoded.contains('\u{FFFD}') {
            return decoded;
        }
    }
    let (decoded, _) = decode_bytes_with_japanese_fallback(trimmed, None);
    if contains_encoded_word_prefix(decoded.as_bytes()) {
        decode_rfc2047_lenient(&decoded)
    } else {
        decoded
    }
}

fn contains_encoded_word_prefix(bytes: &[u8]) -> bool {
    bytes.windows(2).any(|w| w == b"=?")
}

struct EncodedWordMatch {
    end_idx: usize,
    charset: String,
    payload_bytes: Vec<u8>,
}

/// Lenient RFC 2047 decoder handling missing spaces around `=?...`, adjacent encoded-words
/// with split multi-byte characters, missing Base64 `=` padding, and truncated `?=` tails.
pub fn decode_rfc2047_lenient(input: &str) -> String {
    if !input.contains("=?") {
        return input.to_string();
    }

    let bytes = input.as_bytes();
    let mut out = String::with_capacity(input.len());
    let mut i = 0usize;

    while i < bytes.len() {
        if i + 2 <= bytes.len() && &bytes[i..i + 2] == b"=?" {
            if let Some(first) = parse_single_encoded_word(bytes, i) {
                let mut cur_charset = first.charset;
                let mut combined_bytes = first.payload_bytes;
                let mut cursor = first.end_idx;

                loop {
                    let mut ws_end = cursor;
                    while ws_end < bytes.len()
                        && matches!(bytes[ws_end], b' ' | b'\t' | b'\r' | b'\n')
                    {
                        ws_end += 1;
                    }
                    if ws_end + 2 <= bytes.len() && &bytes[ws_end..ws_end + 2] == b"=?" {
                        if let Some(next_ew) = parse_single_encoded_word(bytes, ws_end) {
                            if next_ew.charset.eq_ignore_ascii_case(&cur_charset) {
                                combined_bytes.extend_from_slice(&next_ew.payload_bytes);
                            } else {
                                let (decoded_chunk, _) = decode_bytes_with_japanese_fallback(
                                    &combined_bytes,
                                    Some(&cur_charset),
                                );
                                out.push_str(&decoded_chunk);
                                cur_charset = next_ew.charset;
                                combined_bytes = next_ew.payload_bytes;
                            }
                            cursor = next_ew.end_idx;
                            continue;
                        }
                    }
                    break;
                }

                let (decoded_chunk, _) =
                    decode_bytes_with_japanese_fallback(&combined_bytes, Some(&cur_charset));
                out.push_str(&decoded_chunk);
                i = cursor;
                continue;
            }
        }

        if let Some(ch) = input[i..].chars().next() {
            out.push(ch);
            i += ch.len_utf8();
        } else {
            break;
        }
    }

    out
}

fn parse_single_encoded_word(bytes: &[u8], start: usize) -> Option<EncodedWordMatch> {
    if start + 7 > bytes.len() || &bytes[start..start + 2] != b"=?" {
        return None;
    }
    let cs_start = start + 2;
    let cs_rel_end = bytes[cs_start..bytes.len().min(cs_start + 48)]
        .iter()
        .position(|&b| b == b'?')?;
    let cs_end = cs_start + cs_rel_end;
    if cs_end == cs_start {
        return None;
    }
    let charset_bytes = &bytes[cs_start..cs_end];
    if charset_bytes
        .iter()
        .any(|&b| b.is_ascii_whitespace() || b < 0x21 || b >= 0x7F)
    {
        return None;
    }
    let charset = std::str::from_utf8(charset_bytes).ok()?.to_string();

    if cs_end + 2 >= bytes.len() {
        return None;
    }
    let enc_byte = bytes[cs_end + 1].to_ascii_uppercase();
    if (enc_byte != b'B' && enc_byte != b'Q') || bytes[cs_end + 2] != b'?' {
        return None;
    }
    let payload_start = cs_end + 3;

    // Look for closing `?=`
    if let Some(rel_close) = bytes[payload_start..]
        .windows(2)
        .position(|w| w == b"?=")
    {
        let payload_end = payload_start + rel_close;
        let payload = &bytes[payload_start..payload_end];
        let decoded = if enc_byte == b'B' {
            decode_b64_payload(payload)?
        } else {
            decode_qp_header_payload(payload)
        };
        return Some(EncodedWordMatch {
            end_idx: payload_end + 2,
            charset,
            payload_bytes: decoded,
        });
    }

    // Fallback for long Base64 header words truncated without closing `?=`
    if enc_byte == b'B' {
        let mut payload_end = payload_start;
        while payload_end < bytes.len() && is_b64_or_pad(bytes[payload_end]) {
            payload_end += 1;
        }
        if payload_end - payload_start >= 4 {
            let decoded = decode_b64_payload(&bytes[payload_start..payload_end])?;
            return Some(EncodedWordMatch {
                end_idx: payload_end,
                charset,
                payload_bytes: decoded,
            });
        }
    }

    None
}

fn is_b64_or_pad(b: u8) -> bool {
    b.is_ascii_alphanumeric() || b == b'+' || b == b'/' || b == b'='
}

fn decode_b64_payload(raw: &[u8]) -> Option<Vec<u8>> {
    use base64::engine::general_purpose::STANDARD_NO_PAD;
    use base64::Engine as _;

    let mut clean: Vec<u8> = raw
        .iter()
        .copied()
        .filter(|&b| b.is_ascii_alphanumeric() || b == b'+' || b == b'/')
        .collect();
    if clean.is_empty() {
        return Some(Vec::new());
    }
    if clean.len() % 4 == 1 {
        clean.pop();
    }
    if clean.is_empty() {
        return None;
    }
    STANDARD_NO_PAD.decode(&clean).ok()
}

fn decode_qp_header_payload(raw: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(raw.len());
    let mut i = 0usize;
    while i < raw.len() {
        match raw[i] {
            b'_' => {
                out.push(b' ');
                i += 1;
            }
            b'=' if i + 2 < raw.len() => {
                if let (Some(hi), Some(lo)) = (hex_val(raw[i + 1]), hex_val(raw[i + 2])) {
                    out.push((hi << 4) | lo);
                    i += 3;
                    continue;
                }
                out.push(b'=');
                i += 1;
            }
            other => {
                out.push(other);
                i += 1;
            }
        }
    }
    out
}

fn hex_val(b: u8) -> Option<u8> {
    match b {
        b'0'..=b'9' => Some(b - b'0'),
        b'a'..=b'f' => Some(b - b'a' + 10),
        b'A'..=b'F' => Some(b - b'A' + 10),
        _ => None,
    }
}

fn trim_ascii_ws(mut b: &[u8]) -> &[u8] {
    while let Some((&first, rest)) = b.split_first() {
        if first.is_ascii_whitespace() {
            b = rest;
        } else {
            break;
        }
    }
    while let Some((&last, rest)) = b.split_last() {
        if last.is_ascii_whitespace() {
            b = rest;
        } else {
            break;
        }
    }
    b
}
