package com.kazemail.app.sync

import java.nio.charset.Charset
import java.util.Base64
import java.util.Locale

/**
 * Pure Kotlin lenient RFC 2047 encoded-word decoder matching Rust's recovery behavior.
 * Used both to heal already-persisted subjects/sender names in SQLite and as a display safeguard
 * for malformed/unspaced/truncated `=?ISO-2022-JP?B?...?=` headers.
 */
object Rfc2047HeaderDecoder {

    private data class EncodedChunk(
        val endIdx: Int,
        val charset: String,
        val bytes: ByteArray,
    )

    fun needsDecoding(input: String?): Boolean =
        !input.isNullOrEmpty() && input.contains("=?")

    fun decodeIfNeeded(input: String?): String? {
        if (input == null) return null
        return if (!input.contains("=?")) input else decodeLenient(input)
    }

    fun decodeLenient(input: String): String {
        if (!input.contains("=?")) return input

        val out = StringBuilder(input.length)
        var i = 0
        val len = input.length

        while (i < len) {
            if (i + 2 <= len && input.startsWith("=?", startIndex = i)) {
                val first = parseSingleChunk(input, i)
                if (first != null) {
                    var curCharset = first.charset
                    val combined = ArrayList<Byte>(first.bytes.size * 2)
                    first.bytes.forEach { combined.add(it) }
                    var cursor = first.endIdx

                    while (true) {
                        var wsEnd = cursor
                        while (wsEnd < len && (input[wsEnd] == ' ' || input[wsEnd] == '\t' || input[wsEnd] == '\r' || input[wsEnd] == '\n')) {
                            wsEnd += 1
                        }
                        if (wsEnd + 2 <= len && input.startsWith("=?", startIndex = wsEnd)) {
                            val next = parseSingleChunk(input, wsEnd)
                            if (next != null) {
                                if (next.charset.equals(curCharset, ignoreCase = true)) {
                                    next.bytes.forEach { combined.add(it) }
                                } else {
                                    out.append(decodeCharsetBytes(combined.toByteArray(), curCharset))
                                    combined.clear()
                                    curCharset = next.charset
                                    next.bytes.forEach { combined.add(it) }
                                }
                                cursor = next.endIdx
                                continue
                            }
                        }
                        break
                    }

                    out.append(decodeCharsetBytes(combined.toByteArray(), curCharset))
                    i = cursor
                    continue
                }
            }
            out.append(input[i])
            i += 1
        }
        return out.toString()
    }

    private fun parseSingleChunk(input: String, start: Int): EncodedChunk? {
        val len = input.length
        if (start + 7 > len || !input.startsWith("=?", startIndex = start)) return null
        val csStart = start + 2
        val q1 = input.indexOf('?', startIndex = csStart)
        if (q1 <= csStart || q1 - csStart > 48) return null
        val charset = input.substring(csStart, q1)
        if (charset.any { it.isWhitespace() || it.code < 0x21 || it.code >= 0x7F }) return null

        if (q1 + 2 >= len) return null
        val enc = input[q1 + 1].uppercaseChar()
        if ((enc != 'B' && enc != 'Q') || input[q1 + 2] != '?') return null

        val payloadStart = q1 + 3
        val closeIdx = input.indexOf("?=", startIndex = payloadStart)
        if (closeIdx >= 0) {
            val rawPayload = input.substring(payloadStart, closeIdx)
            val bytes = if (enc == 'B') decodeBase64Lenient(rawPayload) else decodeQpPayload(rawPayload)
            bytes ?: return null
            return EncodedChunk(
                endIdx = closeIdx + 2,
                charset = charset,
                bytes = bytes,
            )
        }

        // Truncated Base64 encoded-word without closing ?=
        if (enc == 'B') {
            var p = payloadStart
            while (p < len && isBase64OrPad(input[p])) {
                p += 1
            }
            if (p - payloadStart >= 4) {
                val rawPayload = input.substring(payloadStart, p)
                val bytes = decodeBase64Lenient(rawPayload) ?: return null
                return EncodedChunk(
                    endIdx = p,
                    charset = charset,
                    bytes = bytes,
                )
            }
        }
        return null
    }

    private fun isBase64OrPad(ch: Char): Boolean =
        ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '+' || ch == '/' || ch == '='

    private fun decodeBase64Lenient(raw: String): ByteArray? {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '+' || ch == '/') {
                sb.append(ch)
            }
        }
        if (sb.isEmpty()) return ByteArray(0)
        if (sb.length % 4 == 1) {
            sb.deleteCharAt(sb.length - 1)
        }
        if (sb.isEmpty()) return null
        return runCatching { Base64.getDecoder().decode(sb.toString()) }.getOrNull()
    }

    private fun decodeQpPayload(raw: String): ByteArray {
        val bytes = ArrayList<Byte>(raw.length)
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            when {
                ch == '_' -> {
                    bytes.add(' '.code.toByte())
                    i += 1
                }
                ch == '=' && i + 2 < raw.length -> {
                    val hi = raw[i + 1].digitToIntOrNull(16)
                    val lo = raw[i + 2].digitToIntOrNull(16)
                    if (hi != null && lo != null) {
                        bytes.add(((hi shl 4) or lo).toByte())
                        i += 3
                    } else {
                        bytes.add(ch.code.toByte())
                        i += 1
                    }
                }
                else -> {
                    bytes.add(ch.code.toByte())
                    i += 1
                }
            }
        }
        return bytes.toByteArray()
    }

    private fun decodeCharsetBytes(bytes: ByteArray, declaredCharset: String): String {
        if (bytes.isEmpty()) return ""
        val charset = resolveCharset(declaredCharset)
        return String(bytes, charset)
    }

    private fun resolveCharset(rawLabel: String): Charset {
        val normalized = rawLabel.trim().lowercase(Locale.US)
        val mapped = when (normalized) {
            "cp932", "windows-31j", "ms932", "x-sjis", "sjis", "shift-jis", "shift_jis" -> "MS932"
            "iso-2022-jp", "csiso2022jp", "jis", "iso2022jp", "iso-2022-jp-1" -> "ISO-2022-JP"
            "euc-jp", "euc_jp", "x-euc-jp", "cseucpkdfmtjapanese" -> "EUC-JP"
            else -> normalized
        }
        return runCatching { Charset.forName(mapped) }.getOrDefault(Charsets.UTF_8)
    }
}
