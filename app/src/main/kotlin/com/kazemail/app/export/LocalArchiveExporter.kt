package com.kazemail.app.export

import com.kazemail.app.data.db.AttachmentEntity
import com.kazemail.app.data.db.KazeMailDatabase
import com.kazemail.app.data.db.MessageBodyEntity
import com.kazemail.app.data.db.MessageMetaEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class MboxExportResult(
    val exportedCount: Int,
    val totalBytes: Long,
)

@Serializable
private data class ExportRecipient(
    val name: String? = null,
    val address: String,
)

object LocalArchiveExporter {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun buildEmlBytes(
        meta: MessageMetaEntity,
        body: MessageBodyEntity?,
        attachments: List<AttachmentEntity>,
        decompressHtml: suspend (ByteArray) -> String,
    ): ByteArray {
        val out = StringBuilder(4096)

        val formattedDate = formatRfc2822Date(meta.receivedAt)
        val fromHeader = formatAddressHeader(meta.senderName, meta.senderAddress)
        val toHeaders = parseRecipients(meta.recipientTo)
        val subjectHeader = encodeRfc2047IfNeeded(stripCrlf(meta.subject ?: ""))
        val msgId = wrapAngle(meta.messageIdHeader ?: "km-export-${meta.id}@kazemail.local")

        out.append("Date: ").append(formattedDate).append("\r\n")
        out.append("From: ").append(fromHeader).append("\r\n")
        if (toHeaders.isNotEmpty()) {
            out.append("To: ").append(toHeaders.joinToString(", ")).append("\r\n")
        }
        out.append("Subject: ").append(subjectHeader).append("\r\n")
        out.append("Message-ID: ").append(msgId).append("\r\n")
        meta.inReplyTo?.takeIf { it.isNotBlank() }?.let { replyTo ->
            out.append("In-Reply-To: ").append(wrapAngle(replyTo)).append("\r\n")
        }
        out.append("MIME-Version: 1.0\r\n")

        val plain = body?.plainText ?: meta.snippet ?: ""
        val html = body?.htmlZstd?.let { zstdBytes ->
            runCatching { decompressHtml(zstdBytes) }.getOrNull()?.takeIf { it.isNotBlank() }
        }
        val cachedFiles = attachments.mapNotNull { att ->
            val path = att.localFilePath ?: return@mapNotNull null
            val file = File(path)
            if (file.exists() && file.isFile) att to file else null
        }

        val boundarySeed = "${meta.id}_${meta.receivedAt}"
        when {
            cachedFiles.isEmpty() && html == null -> {
                appendBase64TextPart(out, "text/plain", plain)
            }
            cachedFiles.isEmpty() && html != null -> {
                val altBoundary = "=_KazeExportAlt_$boundarySeed"
                out.append("Content-Type: multipart/alternative; boundary=\"$altBoundary\"\r\n\r\n")
                out.append("--$altBoundary\r\n")
                appendBase64TextPart(out, "text/plain", plain)
                out.append("\r\n--$altBoundary\r\n")
                appendBase64TextPart(out, "text/html", html)
                out.append("\r\n--$altBoundary--\r\n")
            }
            else -> {
                val mixBoundary = "=_KazeExportMix_$boundarySeed"
                out.append("Content-Type: multipart/mixed; boundary=\"$mixBoundary\"\r\n\r\n")
                out.append("--$mixBoundary\r\n")
                if (html != null) {
                    val altBoundary = "=_KazeExportAlt_$boundarySeed"
                    out.append("Content-Type: multipart/alternative; boundary=\"$altBoundary\"\r\n\r\n")
                    out.append("--$altBoundary\r\n")
                    appendBase64TextPart(out, "text/plain", plain)
                    out.append("\r\n--$altBoundary\r\n")
                    appendBase64TextPart(out, "text/html", html)
                    out.append("\r\n--$altBoundary--\r\n")
                } else {
                    appendBase64TextPart(out, "text/plain", plain)
                }

                for ((att, file) in cachedFiles) {
                    val safeName = encodeRfc2047IfNeeded(stripCrlf(att.filename).replace("\"", "_"))
                    val safeMime = stripCrlf(att.mimeType).ifBlank { "application/octet-stream" }
                    out.append("\r\n--$mixBoundary\r\n")
                    out.append("Content-Type: $safeMime; name=\"$safeName\"\r\n")
                    out.append("Content-Transfer-Encoding: base64\r\n")
                    out.append("Content-Disposition: attachment; filename=\"$safeName\"\r\n\r\n")
                    appendWrappedBase64(out, file.readBytes())
                }
                out.append("\r\n--$mixBoundary--\r\n")
            }
        }

        return out.toString().toByteArray(StandardCharsets.UTF_8)
    }

    /**
     * Streams a folder's locally stored messages into standard mboxo format in chunks of 100
     * so even large archives never exhaust memory.
     */
    suspend fun exportFolderToMbox(
        db: KazeMailDatabase,
        folderId: Long,
        outputStream: OutputStream,
        decompressHtml: suspend (ByteArray) -> String,
    ): MboxExportResult {
        var offset = 0
        var exported = 0
        var totalBytes = 0L
        val chunkSize = 100

        while (true) {
            val metas = db.messageMetaDao().listByFolderPaged(folderId, chunkSize, offset)
            if (metas.isEmpty()) break

            for (meta in metas) {
                val body = db.messageBodyDao().getByMetaId(meta.id)
                val attachments = db.attachmentDao().getByMetaId(meta.id)
                val emlBytes = buildEmlBytes(meta, body, attachments, decompressHtml)

                val envelopeFrom = meta.senderAddress.trim().ifBlank { "MAILER-DAEMON" }.replace(" ", "_")
                val fromLine = "From $envelopeFrom ${formatMboxTimestamp(meta.receivedAt)}\n"
                val mboxBody = escapeMboxFromLines(emlBytes.toString(StandardCharsets.UTF_8))
                    .replace("\r\n", "\n")

                val headerBytes = fromLine.toByteArray(StandardCharsets.UTF_8)
                val contentBytes = (mboxBody + "\n").toByteArray(StandardCharsets.UTF_8)
                outputStream.write(headerBytes)
                outputStream.write(contentBytes)
                totalBytes += headerBytes.size + contentBytes.size
                exported += 1
            }
            offset += metas.size
            if (metas.size < chunkSize) break
        }
        outputStream.flush()
        return MboxExportResult(exportedCount = exported, totalBytes = totalBytes)
    }

    internal fun escapeMboxFromLines(text: String): String =
        text.split("\n").joinToString("\n") { line ->
            if (line.startsWith("From ")) ">$line" else line
        }

    private fun appendBase64TextPart(out: StringBuilder, subtype: String, text: String) {
        out.append("Content-Type: $subtype; charset=\"utf-8\"\r\n")
        out.append("Content-Transfer-Encoding: base64\r\n\r\n")
        val normalized = text.replace("\r\n", "\n").replace("\n", "\r\n")
        appendWrappedBase64(out, normalized.toByteArray(StandardCharsets.UTF_8))
    }

    private fun appendWrappedBase64(out: StringBuilder, bytes: ByteArray) {
        val b64 = Base64.getEncoder().encodeToString(bytes)
        var i = 0
        while (i < b64.length) {
            val end = (i + 76).coerceAtMost(b64.length)
            out.append(b64, i, end).append("\r\n")
            i = end
        }
    }

    private fun parseRecipients(jsonStr: String?): List<String> {
        if (jsonStr.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<ExportRecipient>>(jsonStr)
                .filter { it.address.isNotBlank() }
                .map { formatAddressHeader(it.name, it.address) }
        }.getOrDefault(emptyList())
    }

    private fun formatAddressHeader(name: String?, address: String): String {
        val cleanAddr = stripCrlf(address).trim()
        val cleanName = stripCrlf(name ?: "").trim()
        return if (cleanName.isEmpty()) {
            "<$cleanAddr>"
        } else {
            "${encodeRfc2047IfNeeded(cleanName)} <$cleanAddr>"
        }
    }

    private fun encodeRfc2047IfNeeded(value: String): String {
        if (value.isEmpty()) return ""
        val isPureAscii = value.all { it.code in 0x20..0x7E } && !value.contains("=?")
        if (isPureAscii) return value

        val words = mutableListOf<String>()
        val sb = StringBuilder()
        var utf8BytesCount = 0
        for (ch in value) {
            val chLen = ch.toString().toByteArray(StandardCharsets.UTF_8).size
            if (utf8BytesCount + chLen > 42 && sb.isNotEmpty()) {
                val b64 = Base64.getEncoder().encodeToString(sb.toString().toByteArray(StandardCharsets.UTF_8))
                words += "=?UTF-8?B?$b64?="
                sb.clear()
                utf8BytesCount = 0
            }
            sb.append(ch)
            utf8BytesCount += chLen
        }
        if (sb.isNotEmpty()) {
            val b64 = Base64.getEncoder().encodeToString(sb.toString().toByteArray(StandardCharsets.UTF_8))
            words += "=?UTF-8?B?$b64?="
        }
        return words.joinToString("\r\n ")
    }

    private fun wrapAngle(raw: String): String {
        val clean = stripCrlf(raw).trim()
        return if (clean.startsWith("<") && clean.endsWith(">")) clean else "<${clean.trim('<', '>')}>"
    }

    private fun stripCrlf(s: String): String = s.replace("\r", "").replace("\n", "")

    private fun formatRfc2822Date(epochMs: Long): String {
        val df = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)
        df.timeZone = TimeZone.getTimeZone("UTC")
        return df.format(Date(if (epochMs > 0L) epochMs else 0L))
    }

    private fun formatMboxTimestamp(epochMs: Long): String {
        val df = SimpleDateFormat("EEE MMM dd HH:mm:ss yyyy", Locale.US)
        df.timeZone = TimeZone.getTimeZone("UTC")
        return df.format(Date(if (epochMs > 0L) epochMs else 0L))
    }
}
