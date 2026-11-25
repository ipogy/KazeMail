use base64::alphabet::STANDARD as BASE64_STANDARD_ALPHABET;
use base64::engine::{DecodePaddingMode, GeneralPurpose, GeneralPurposeConfig};
use base64::Engine as _;
use imap_proto::types::{BodyContentCommon, BodyStructure, ContentEncoding};

use crate::encoding::decode_bytes_with_japanese_fallback;
use crate::models::ParsedAttachmentMeta;

const PERMISSIVE_BASE64_ENGINE: GeneralPurpose = GeneralPurpose::new(
    &BASE64_STANDARD_ALPHABET,
    GeneralPurposeConfig::new().with_decode_padding_mode(DecodePaddingMode::Indifferent),
);

#[derive(Debug, Clone)]
pub struct TextPartSpec {
    pub part_id: String,
    pub is_html: bool,
    pub charset: Option<String>,
    pub transfer_encoding: PartTransferEncoding,
    pub octets: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PartTransferEncoding {
    None,
    Base64,
    QuotedPrintable,
}

#[derive(Debug, Clone, Default)]
pub struct BodyStructurePlan {
    pub text_parts: Vec<TextPartSpec>,
    pub html_parts: Vec<TextPartSpec>,
    pub attachments: Vec<ParsedAttachmentMeta>,
}

pub fn inspect_bodystructure(bs: &BodyStructure<'_>) -> BodyStructurePlan {
    let mut plan = BodyStructurePlan::default();
    walk_bodystructure(bs, &[], true, &mut plan);
    plan
}

fn walk_bodystructure(
    bs: &BodyStructure<'_>,
    path: &[u32],
    is_root: bool,
    plan: &mut BodyStructurePlan,
) {
    match bs {
        BodyStructure::Multipart { bodies, .. } => {
            for (idx, child) in bodies.iter().enumerate() {
                let mut child_path = path.to_vec();
                child_path.push((idx + 1) as u32);
                walk_bodystructure(child, &child_path, false, plan);
            }
        }
        BodyStructure::Text { common, other, .. } => {
            let part_id = if is_root || path.is_empty() {
                "1".to_string()
            } else {
                format_part_path(path)
            };
            let subtype = common.ty.subtype.as_ref().to_ascii_lowercase();
            let disp = disposition_type(common);
            let filename = extract_filename(common);

            if disp.as_deref() == Some("attachment")
                || (filename.is_some() && subtype != "plain" && subtype != "html")
            {
                plan.attachments.push(ParsedAttachmentMeta {
                    part_id,
                    filename: filename.unwrap_or_else(|| format!("attachment.{subtype}")),
                    mime_type: format!("{}/{}", common.ty.ty.to_ascii_lowercase(), subtype),
                    size_bytes: estimate_decoded_bytes(other.octets, &other.transfer_encoding),
                });
            } else {
                let spec = TextPartSpec {
                    part_id,
                    is_html: subtype == "html",
                    charset: extract_param(&common.ty.params, "charset"),
                    transfer_encoding: map_encoding(&other.transfer_encoding),
                    octets: other.octets,
                };
                if spec.is_html {
                    plan.html_parts.push(spec);
                } else {
                    plan.text_parts.push(spec);
                }
            }
        }
        BodyStructure::Basic { common, other, .. } => {
            let part_id = if is_root || path.is_empty() {
                "1".to_string()
            } else {
                format_part_path(path)
            };
            let ty = common.ty.ty.to_ascii_lowercase();
            let subtype = common.ty.subtype.to_ascii_lowercase();
            let disp = disposition_type(common);
            let filename = extract_filename(common);

            // Skip cryptographic signatures unless explicitly marked as an attachment with a filename
            let is_detached_sig = ty == "application"
                && (subtype == "pgp-signature" || subtype == "pkcs7-signature")
                && filename.is_none();

            if !is_detached_sig && (disp.as_deref() == Some("attachment") || filename.is_some() || ty != "multipart") {
                let fallback_name = if subtype.is_empty() {
                    "attachment.bin".to_string()
                } else {
                    format!("attachment.{subtype}")
                };
                plan.attachments.push(ParsedAttachmentMeta {
                    part_id,
                    filename: filename.unwrap_or(fallback_name),
                    mime_type: format!("{ty}/{subtype}"),
                    size_bytes: estimate_decoded_bytes(other.octets, &other.transfer_encoding),
                });
            }
        }
        BodyStructure::Message {
            common,
            other,
            body,
            ..
        } => {
            let part_id = if is_root || path.is_empty() {
                "1".to_string()
            } else {
                format_part_path(path)
            };
            let disp = disposition_type(common);
            let filename = extract_filename(common);
            if disp.as_deref() == Some("attachment") || filename.is_some() {
                plan.attachments.push(ParsedAttachmentMeta {
                    part_id,
                    filename: filename.unwrap_or_else(|| "forwarded_message.eml".to_string()),
                    mime_type: "message/rfc822".to_string(),
                    size_bytes: estimate_decoded_bytes(other.octets, &other.transfer_encoding),
                });
            } else {
                walk_bodystructure(body, path, false, plan);
            }
        }
    }
}

pub fn transfer_encoding_for_part_id(
    bs: &BodyStructure<'_>,
    target_part_id: &str,
) -> Option<PartTransferEncoding> {
    find_part_encoding(bs, &[], true, target_part_id)
}

fn find_part_encoding(
    bs: &BodyStructure<'_>,
    path: &[u32],
    is_root: bool,
    target_part_id: &str,
) -> Option<PartTransferEncoding> {
    match bs {
        BodyStructure::Multipart { bodies, .. } => {
            for (idx, child) in bodies.iter().enumerate() {
                let mut child_path = path.to_vec();
                child_path.push((idx + 1) as u32);
                if let Some(enc) = find_part_encoding(child, &child_path, false, target_part_id) {
                    return Some(enc);
                }
            }
            None
        }
        BodyStructure::Text { other, .. } | BodyStructure::Basic { other, .. } => {
            let part_id = if is_root || path.is_empty() {
                "1".to_string()
            } else {
                format_part_path(path)
            };
            if part_id == target_part_id {
                Some(map_encoding(&other.transfer_encoding))
            } else {
                None
            }
        }
        BodyStructure::Message { other, body, .. } => {
            let part_id = if is_root || path.is_empty() {
                "1".to_string()
            } else {
                format_part_path(path)
            };
            if part_id == target_part_id {
                Some(map_encoding(&other.transfer_encoding))
            } else {
                find_part_encoding(body, path, false, target_part_id)
            }
        }
    }
}

pub fn decode_part_payload(
    raw_bytes: &[u8],
    encoding: PartTransferEncoding,
) -> Vec<u8> {
    match encoding {
        PartTransferEncoding::None => raw_bytes.to_vec(),
        PartTransferEncoding::Base64 => {
            let cleaned_no_ws: Vec<u8> = raw_bytes
                .iter()
                .copied()
                .filter(|b| !b.is_ascii_whitespace())
                .collect();
            if let Ok(decoded) = PERMISSIVE_BASE64_ENGINE.decode(&cleaned_no_ws) {
                return decoded;
            }
            // Strip any stray '=' padding or trailing non-Base64 characters and decode permissively.
            let mut stripped = Vec::with_capacity(cleaned_no_ws.len());
            for b in cleaned_no_ws {
                if b == b'=' {
                    break;
                }
                if b.is_ascii_alphanumeric() || b == b'+' || b == b'/' {
                    stripped.push(b);
                }
            }
            PERMISSIVE_BASE64_ENGINE
                .decode(&stripped)
                .unwrap_or_else(|_| raw_bytes.to_vec())
        }
        PartTransferEncoding::QuotedPrintable => decode_quoted_printable(raw_bytes),
    }
}

pub fn decode_text_part_bytes(
    raw_bytes: &[u8],
    encoding: PartTransferEncoding,
    charset: Option<&str>,
) -> String {
    let transfer_decoded = decode_part_payload(raw_bytes, encoding);
    let (text, _) = decode_bytes_with_japanese_fallback(&transfer_decoded, charset);
    text
}

pub fn decode_quoted_printable(input: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(input.len());
    let mut i = 0;
    while i < input.len() {
        if input[i] == b'=' {
            if i + 2 < input.len() && input[i + 1] == b'\r' && input[i + 2] == b'\n' {
                i += 3;
                continue;
            }
            if i + 1 < input.len() && input[i + 1] == b'\n' {
                i += 2;
                continue;
            }
            if i + 2 < input.len() {
                if let (Some(hi), Some(lo)) = (hex_val(input[i + 1]), hex_val(input[i + 2])) {
                    out.push((hi << 4) | lo);
                    i += 3;
                    continue;
                }
            }
        }
        out.push(input[i]);
        i += 1;
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

fn format_part_path(path: &[u32]) -> String {
    path.iter()
        .map(|n| n.to_string())
        .collect::<Vec<_>>()
        .join(".")
}

fn disposition_type(common: &BodyContentCommon<'_>) -> Option<String> {
    common
        .disposition
        .as_ref()
        .map(|d| d.ty.to_ascii_lowercase())
}

fn extract_filename(common: &BodyContentCommon<'_>) -> Option<String> {
    if let Some(disp) = common.disposition.as_ref() {
        if let Some(name) = extract_param_or_rfc2231(&disp.params, "filename") {
            return Some(name);
        }
    }
    extract_param_or_rfc2231(&common.ty.params, "name")
}

fn extract_param_or_rfc2231(
    params: &Option<Vec<(std::borrow::Cow<'_, str>, std::borrow::Cow<'_, str>)>>,
    key: &str,
) -> Option<String> {
    let list = params.as_ref()?;
    if let Some((_, raw)) = list.iter().find(|(k, _)| k.eq_ignore_ascii_case(key)) {
        return Some(decode_rfc2047_word_or_raw(raw));
    }

    let star_key = format!("{key}*");
    if let Some((_, raw)) = list.iter().find(|(k, _)| k.eq_ignore_ascii_case(&star_key)) {
        return Some(decode_rfc2231_value(raw));
    }

    // Check RFC 2231 continuation chunks like filename*0*, filename*1*, ...
    let prefix = format!("{key}*");
    let mut chunks: Vec<(usize, bool, &str)> = list
        .iter()
        .filter_map(|(k, v)| {
            if k.len() <= prefix.len() || !k[..prefix.len()].eq_ignore_ascii_case(&prefix) {
                return None;
            }
            let suffix = &k[prefix.len()..];
            let (digits, encoded) = if let Some(d) = suffix.strip_suffix('*') {
                (d, true)
            } else {
                (suffix, false)
            };
            let idx = digits.parse::<usize>().ok()?;
            Some((idx, encoded, v.as_ref()))
        })
        .collect();
    if chunks.is_empty() {
        return None;
    }
    chunks.sort_by_key(|(idx, _, _)| *idx);

    let mut charset: Option<String> = None;
    let mut bytes_out = Vec::new();
    for (pos, (_, encoded, val)) in chunks.iter().enumerate() {
        let fragment = if pos == 0 && *encoded {
            if let Some(first_quote) = val.find('\'') {
                if let Some(rel_second) = val[first_quote + 1..].find('\'') {
                    let cs = &val[..first_quote];
                    if !cs.is_empty() {
                        charset = Some(cs.to_string());
                    }
                    &val[first_quote + 1 + rel_second + 1..]
                } else {
                    *val
                }
            } else {
                *val
            }
        } else {
            *val
        };
        if *encoded {
            bytes_out.extend_from_slice(&percent_decode_bytes(fragment));
        } else {
            bytes_out.extend_from_slice(fragment.as_bytes());
        }
    }
    let (decoded, _) = decode_bytes_with_japanese_fallback(&bytes_out, charset.as_deref());
    let trimmed = decoded.trim();
    if trimmed.is_empty() {
        None
    } else {
        Some(trimmed.to_string())
    }
}

fn decode_rfc2231_value(raw: &str) -> String {
    let trimmed = raw.trim();
    if let Some(first_quote) = trimmed.find('\'') {
        if let Some(rel_second) = trimmed[first_quote + 1..].find('\'') {
            let cs = trimmed[..first_quote].trim();
            let rest = &trimmed[first_quote + 1 + rel_second + 1..];
            let raw_bytes = percent_decode_bytes(rest);
            let charset = if cs.is_empty() { None } else { Some(cs) };
            let (decoded, _) = decode_bytes_with_japanese_fallback(&raw_bytes, charset);
            return decoded;
        }
    }
    let raw_bytes = percent_decode_bytes(trimmed);
    let (decoded, _) = decode_bytes_with_japanese_fallback(&raw_bytes, None);
    decoded
}

fn percent_decode_bytes(input: &str) -> Vec<u8> {
    let bytes = input.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            if let (Some(hi), Some(lo)) = (hex_val(bytes[i + 1]), hex_val(bytes[i + 2])) {
                out.push((hi << 4) | lo);
                i += 3;
                continue;
            }
        }
        out.push(bytes[i]);
        i += 1;
    }
    out
}

fn extract_param(
    params: &Option<Vec<(std::borrow::Cow<'_, str>, std::borrow::Cow<'_, str>)>>,
    key: &str,
) -> Option<String> {
    params.as_ref().and_then(|list| {
        list.iter()
            .find(|(k, _)| k.eq_ignore_ascii_case(key))
            .map(|(_, v)| v.to_string())
    })
}

pub fn decode_rfc2047_word_or_raw(value: &str) -> String {
    let trimmed = value.trim();
    if trimmed.contains("=?") {
        let synthetic = format!("Subject: {trimmed}\r\n\r\n");
        if let Some(msg) = mail_parser::MessageParser::new().parse(synthetic.as_bytes()) {
            if let Some(subj) = msg.subject() {
                if !subj.contains('\u{FFFD}') {
                    return subj.to_string();
                }
            }
        }
    }
    let (decoded, _) = decode_bytes_with_japanese_fallback(trimmed.as_bytes(), None);
    decoded
}

fn map_encoding(enc: &ContentEncoding<'_>) -> PartTransferEncoding {
    match enc {
        ContentEncoding::Base64 => PartTransferEncoding::Base64,
        ContentEncoding::QuotedPrintable => PartTransferEncoding::QuotedPrintable,
        _ => PartTransferEncoding::None,
    }
}

fn estimate_decoded_bytes(octets: u32, enc: &ContentEncoding<'_>) -> u64 {
    match enc {
        ContentEncoding::Base64 => ((octets as u64) * 3) / 4,
        _ => octets as u64,
    }
}

pub fn transfer_encoding_from_mime_header(mime_header_bytes: &[u8]) -> PartTransferEncoding {
    let mut synthetic = Vec::with_capacity(mime_header_bytes.len() + 4);
    synthetic.extend_from_slice(mime_header_bytes);
    if !synthetic.ends_with(b"\r\n\r\n") {
        synthetic.extend_from_slice(b"\r\n\r\n");
    }
    if let Some(msg) = mail_parser::MessageParser::new().parse(&synthetic) {
        if let Some(cte) = msg.root_part().content_transfer_encoding() {
            let lower = cte.trim().to_ascii_lowercase();
            return match lower.as_str() {
                "base64" => PartTransferEncoding::Base64,
                "quoted-printable" => PartTransferEncoding::QuotedPrintable,
                _ => PartTransferEncoding::None,
            };
        }
    }
    // Fallback direct line scan for partial/unusual MIME header blocks
    let text = String::from_utf8_lossy(mime_header_bytes);
    let unfolded = text.replace("\r\n ", " ").replace("\r\n\t", " ");
    for line in unfolded.lines() {
        if let Some((name, value)) = line.split_once(':') {
            if name.trim().eq_ignore_ascii_case("content-transfer-encoding") {
                return match value.trim().to_ascii_lowercase().as_str() {
                    "base64" => PartTransferEncoding::Base64,
                    "quoted-printable" => PartTransferEncoding::QuotedPrintable,
                    _ => PartTransferEncoding::None,
                };
            }
        }
    }
    PartTransferEncoding::None
}
