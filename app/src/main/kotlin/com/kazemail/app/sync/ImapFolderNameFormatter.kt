package com.kazemail.app.sync

import java.util.Locale

object ImapFolderNameFormatter {
    fun decodeModifiedUtf7(input: String): String {
        if (!input.contains('&')) return input
        val out = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            if (input[i] != '&') {
                out.append(input[i])
                i++
                continue
            }
            val end = input.indexOf('-', startIndex = i + 1)
            if (end < 0) {
                out.append('&')
                i++
                continue
            }
            if (end == i + 1) {
                out.append('&')
                i = end + 1
                continue
            }
            val decoded = decodeMutf7Block(input.substring(i + 1, end))
            if (decoded != null) {
                out.append(decoded)
                i = end + 1
            } else {
                out.append('&')
                i++
            }
        }
        return out.toString()
    }

    fun formatDisplayName(fullPath: String, storedFolderName: String? = null): String {
        if (fullPath.equals("INBOX", ignoreCase = true)) {
            return "受信トレイ"
        }
        val base = storedFolderName?.takeIf { it.isNotBlank() } ?: fullPath
        val unescaped = unescapeQuoted(base)
        val decoded = decodeModifiedUtf7(unescaped)
        if (decoded.contains('&') && !fullPath.equals(base, ignoreCase = true)) {
            return decoded
        }
        val clean = if (decoded.startsWith("[Gmail]/", ignoreCase = true)) {
            decoded.substringAfter('/')
        } else if (decoded.startsWith("[Google Mail]/", ignoreCase = true)) {
            decoded.substringAfter('/')
        } else if (decoded.startsWith("INBOX/", ignoreCase = true)) {
            decoded.substringAfter('/')
        } else if (decoded.startsWith("INBOX.", ignoreCase = true)) {
            decoded.substringAfter('.')
        } else {
            decoded
        }
        return clean.ifBlank { decoded }
    }

    private fun unescapeQuoted(raw: String): String {
        if (!raw.contains('\\')) return raw
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '\\' && i + 1 < raw.length) {
                sb.append(raw[i + 1])
                i += 2
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }

    private fun decodeMutf7Block(block: String): String? {
        if (block.isEmpty()) return ""
        var bitBuf = 0
        var bits = 0
        val bytes = ArrayList<Byte>((block.length * 3) / 4 + 1)
        for (ch in block) {
            val val6 = when (ch) {
                in 'A'..'Z' -> ch - 'A'
                in 'a'..'z' -> ch - 'a' + 26
                in '0'..'9' -> ch - '0' + 52
                '+' -> 62
                ',' -> 63
                else -> return null
            }
            bitBuf = (bitBuf shl 6) or val6
            bits += 6
            if (bits >= 8) {
                bits -= 8
                bytes.add(((bitBuf ushr bits) and 0xFF).toByte())
            }
        }
        if (bytes.isEmpty() || bytes.size % 2 != 0) return null
        val chars = CharArray(bytes.size / 2)
        for (idx in chars.indices) {
            val hi = bytes[idx * 2].toInt() and 0xFF
            val lo = bytes[idx * 2 + 1].toInt() and 0xFF
            chars[idx] = ((hi shl 8) or lo).toChar()
        }
        return String(chars)
    }

    fun sortKey(fullPath: String, displayName: String): String {
        val upper = fullPath.uppercase(Locale.US)
        return when {
            upper == "INBOX" -> "0_INBOX"
            upper.contains("SENT") || displayName.contains("送信") -> "1_$displayName"
            upper.contains("DRAFT") || displayName.contains("下書") -> "2_$displayName"
            upper.contains("ARCHIVE") || displayName.contains("すべてのメール") -> "3_$displayName"
            upper.contains("JUNK") || upper.contains("SPAM") || displayName.contains("迷惑") -> "4_$displayName"
            upper.contains("TRASH") || displayName.contains("ゴミ箱") || displayName.contains("ごみ箱") -> "5_$displayName"
            else -> "6_$displayName"
        }
    }
}
