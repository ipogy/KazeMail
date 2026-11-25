package com.kazemail.app.data

import java.io.File
import java.io.FileInputStream
import java.util.Locale

internal object AttachmentMetadataHelper {
    private val GENERIC_MIME_TYPES = setOf(
        "",
        "*/*",
        "application/octet-stream",
        "binary/octet-stream",
        "application/download",
        "application/force-download",
        "application/unknown",
    )

    private val PDF_MIME_ALIASES = setOf(
        "application/pdf",
        "application/x-pdf",
        "application/acrobat",
        "applications/vnd.pdf",
        "text/pdf",
        "text/x-pdf",
    )

    private val EXTENSION_MIME_FALLBACK = mapOf(
        "pdf" to "application/pdf",
        "txt" to "text/plain",
        "log" to "text/plain",
        "csv" to "text/csv",
        "json" to "application/json",
        "xml" to "text/xml",
        "html" to "text/html",
        "htm" to "text/html",
        "eml" to "message/rfc822",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "bmp" to "image/bmp",
        "zip" to "application/zip",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    )

    fun normalizeAndResolveMimeType(
        fileName: String,
        rawMimeType: String,
        headerSample: ByteArray? = null,
        systemExtensionMime: (String) -> String? = { null },
    ): String {
        val cleaned = rawMimeType
            .substringBefore(';')
            .trim()
            .lowercase(Locale.ROOT)

        if (cleaned in PDF_MIME_ALIASES) {
            return "application/pdf"
        }

        val magicMime = detectMimeFromMagicBytes(headerSample)
        if (magicMime != null && (cleaned in GENERIC_MIME_TYPES || magicMime == "application/pdf")) {
            return magicMime
        }

        if (cleaned !in GENERIC_MIME_TYPES) {
            return cleaned
        }

        val ext = fileName.substringAfterLast('.', "").trim().lowercase(Locale.ROOT)
        if (ext.isNotEmpty() && '.' in fileName) {
            EXTENSION_MIME_FALLBACK[ext]?.let { return it }
            systemExtensionMime(ext)
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase(Locale.ROOT)
                ?.takeIf { it.isNotEmpty() && it !in GENERIC_MIME_TYPES }
                ?.let { return it }
        }

        return "*/*"
    }

    fun detectMimeFromMagicBytes(headerSample: ByteArray?): String? {
        if (headerSample == null || headerSample.isEmpty()) return null
        val pdfPrefix = "%PDF-".toByteArray(Charsets.US_ASCII)
        if (headerSample.startsWithBytes(pdfPrefix)) {
            return "application/pdf"
        }
        val trimmedAscii = headerSample.toString(Charsets.US_ASCII).trimStart()
        if (trimmedAscii.startsWith("%PDF-")) {
            return "application/pdf"
        }
        if (headerSample.size >= 8 &&
            headerSample[0] == 0x89.toByte() &&
            headerSample[1] == 0x50.toByte() &&
            headerSample[2] == 0x4E.toByte() &&
            headerSample[3] == 0x47.toByte()
        ) {
            return "image/png"
        }
        if (headerSample.size >= 3 &&
            headerSample[0] == 0xFF.toByte() &&
            headerSample[1] == 0xD8.toByte() &&
            headerSample[2] == 0xFF.toByte()
        ) {
            return "image/jpeg"
        }
        val gifAscii = headerSample.take(6).toByteArray().toString(Charsets.US_ASCII)
        if (gifAscii == "GIF87a" || gifAscii == "GIF89a") {
            return "image/gif"
        }
        return null
    }

    fun isCachedAttachmentInvalid(file: File, declaredFileName: String, rawMimeType: String): Boolean {
        if (!file.exists() || file.length() <= 0L) return true
        val sample = readHeaderSample(file, maxBytes = 32) ?: return true
        if (sample.isEmpty()) return true
        val ext = declaredFileName.substringAfterLast('.', "").trim().lowercase(Locale.ROOT)
        val mime = rawMimeType.substringBefore(';').trim().lowercase(Locale.ROOT)
        val expectsPdf = ('.' in declaredFileName && ext == "pdf") || mime in PDF_MIME_ALIASES
        if (expectsPdf) {
            val ascii = sample.toString(Charsets.US_ASCII).trimStart()
            if (ascii.startsWith("JVBERi0") && !ascii.startsWith("%PDF-")) {
                return true
            }
        }
        return false
    }

    fun buildSafeAttachmentFileName(
        rawFilename: String,
        rawMimeType: String,
        payloadPrefix: ByteArray? = null,
    ): String {
        val cleaned = rawFilename
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .replace("..", "_")
            .trim()
            .trim('.')
            .ifBlank { "attachment" }

        val resolvedMime = normalizeAndResolveMimeType(cleaned, rawMimeType, payloadPrefix)
        val currentExt = cleaned.substringAfterLast('.', "").trim().lowercase(Locale.ROOT)
        val hasExtension = '.' in cleaned && currentExt.length in 1..10
        val expectedExt = when (resolvedMime) {
            "application/pdf" -> "pdf"
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/gif" -> "gif"
            else -> null
        }

        val withExt = when {
            expectedExt == "pdf" && (!('.' in cleaned) || currentExt != "pdf") -> "$cleaned.pdf"
            !hasExtension && expectedExt != null -> "$cleaned.$expectedExt"
            else -> cleaned
        }

        return if (withExt.length <= 120) {
            withExt
        } else {
            val dot = withExt.lastIndexOf('.')
            if (dot > 0 && withExt.length - dot <= 16) {
                val extPart = withExt.substring(dot)
                val stemMax = (120 - extPart.length).coerceAtLeast(16)
                withExt.take(stemMax) + extPart
            } else {
                withExt.take(120)
            }
        }
    }

    fun readHeaderSample(file: File, maxBytes: Int = 32): ByteArray? =
        runCatching {
            FileInputStream(file).use { input ->
                val buf = ByteArray(maxBytes)
                val read = input.read(buf)
                if (read <= 0) ByteArray(0) else buf.copyOf(read)
            }
        }.getOrNull()

    private fun ByteArray.startsWithBytes(prefix: ByteArray): Boolean {
        if (this.size < prefix.size) return false
        for (i in prefix.indices) {
            if (this[i] != prefix[i]) return false
        }
        return true
    }
}
