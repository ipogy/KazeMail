package com.kazemail.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import com.kazemail.app.notifications.MailNotificationManager
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

data class MailtoPayload(
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val subject: String = "",
    val body: String = "",
)

data class ExternalComposeRequest(
    val eventId: Long,
    val toAddresses: List<String> = emptyList(),
    val ccAddresses: List<String> = emptyList(),
    val bccAddresses: List<String> = emptyList(),
    val subject: String = "",
    val bodyText: String = "",
    val attachmentUris: List<Uri> = emptyList(),
)

sealed interface IncomingMailIntent {
    data object None : IncomingMailIntent
    data class ImportArchive(val uris: List<Uri>) : IncomingMailIntent
    data class Compose(val request: ExternalComposeRequest) : IncomingMailIntent
}

/**
 * RFC 6068 mailto: URI および Android の共有 / メール送信インテント (ACTION_SENDTO / VIEW / SEND / SEND_MULTIPLE)
 * を解析し、アーカイブ取込と新規作成画面へのプリフィルを安全に振り分ける。
 */
object ExternalMailIntentHelper {

    fun isMailtoUriString(rawUri: String?): Boolean =
        rawUri != null && rawUri.trimStart().startsWith("mailto:", ignoreCase = true)

    fun parseMailtoUriString(rawUri: String?): MailtoPayload {
        if (rawUri.isNullOrBlank()) return MailtoPayload()
        val trimmed = rawUri.trimStart()
        if (!trimmed.startsWith("mailto:", ignoreCase = true)) return MailtoPayload()

        val schemeSpecific = trimmed.substring(7)
        val qIdx = schemeSpecific.indexOf('?')
        val rawPath = if (qIdx >= 0) schemeSpecific.substring(0, qIdx) else schemeSpecific
        val rawQuery = if (qIdx >= 0) schemeSpecific.substring(qIdx + 1) else null

        val to = mutableListOf<String>()
        val cc = mutableListOf<String>()
        val bcc = mutableListOf<String>()
        var subject = ""
        var body = ""

        if (rawPath.isNotEmpty()) {
            addSplitAddresses(to, percentDecode(rawPath))
        }

        if (!rawQuery.isNullOrEmpty()) {
            rawQuery.split('&').forEach { pair ->
                if (pair.isEmpty()) return@forEach
                val eqIdx = pair.indexOf('=')
                val rawKey = if (eqIdx >= 0) pair.substring(0, eqIdx) else pair
                val rawValue = if (eqIdx >= 0) pair.substring(eqIdx + 1) else ""
                val key = percentDecode(rawKey).trim().lowercase()
                val decodedValue = percentDecode(rawValue)
                when (key) {
                    "to" -> addSplitAddresses(to, decodedValue)
                    "cc" -> addSplitAddresses(cc, decodedValue)
                    "bcc" -> addSplitAddresses(bcc, decodedValue)
                    "subject" -> if (subject.isEmpty()) subject = normalizeLineEndings(decodedValue)
                    "body" -> if (body.isEmpty()) body = normalizeLineEndings(decodedValue)
                }
            }
        }

        return MailtoPayload(
            to = deduplicateAddresses(to),
            cc = deduplicateAddresses(cc),
            bcc = deduplicateAddresses(bcc),
            subject = subject,
            body = body,
        )
    }

    fun buildComposePayload(
        action: String?,
        dataUriString: String?,
        extraTo: List<String> = emptyList(),
        extraCc: List<String> = emptyList(),
        extraBcc: List<String> = emptyList(),
        extraSubject: String? = null,
        extraBody: String? = null,
    ): MailtoPayload {
        val fromMailto = parseMailtoUriString(dataUriString)
        val mergedTo = deduplicateAddresses(fromMailto.to + splitAddressList(extraTo))
        val mergedCc = deduplicateAddresses(fromMailto.cc + splitAddressList(extraCc))
        val mergedBcc = deduplicateAddresses(fromMailto.bcc + splitAddressList(extraBcc))

        val subject = fromMailto.subject.ifBlank {
            normalizeLineEndings(extraSubject.orEmpty())
        }
        val body = fromMailto.body.ifBlank {
            normalizeLineEndings(extraBody.orEmpty())
        }

        // ACTION_SENDTO without valid mailto or any fields still yields a clean empty Compose payload
        val _unusedAction = action
        return MailtoPayload(
            to = mergedTo,
            cc = mergedCc,
            bcc = mergedBcc,
            subject = subject,
            body = body,
        )
    }

    fun classifyIncomingIntent(
        context: Context,
        intent: Intent?,
        nextEventId: () -> Long,
    ): IncomingMailIntent {
        if (intent == null) return IncomingMailIntent.None
        if ((intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) {
            return IncomingMailIntent.None
        }
        if (intent.getLongExtra(MailNotificationManager.EXTRA_NAV_ACCOUNT_ID, -1L) > 0L) {
            return IncomingMailIntent.None
        }

        val action = intent.action ?: return IncomingMailIntent.None
        val data = intent.data
        val dataString = data?.toString()

        if (action == Intent.ACTION_SENDTO) {
            val payload = extractComposeRequest(intent, dataString, emptyList(), nextEventId)
            return IncomingMailIntent.Compose(payload)
        }

        if (action == Intent.ACTION_VIEW) {
            return if (isMailtoUriString(dataString)) {
                IncomingMailIntent.Compose(
                    extractComposeRequest(intent, dataString, emptyList(), nextEventId),
                )
            } else if (data != null) {
                IncomingMailIntent.ImportArchive(listOf(data))
            } else {
                IncomingMailIntent.None
            }
        }

        if (action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE) {
            val streamUris = extractStreamUris(intent)
            val extraTo = readAddressExtras(intent, Intent.EXTRA_EMAIL)
            val extraCc = readAddressExtras(intent, Intent.EXTRA_CC)
            val extraBcc = readAddressExtras(intent, Intent.EXTRA_BCC)
            val hasRecipientExtras = extraTo.isNotEmpty() || extraCc.isNotEmpty() || extraBcc.isNotEmpty()
            val hasMailtoData = isMailtoUriString(dataString)

            val isArchiveImport = !hasMailtoData &&
                !hasRecipientExtras &&
                streamUris.isNotEmpty() &&
                shouldTreatSendIntentAsArchiveImport(context, intent, streamUris)

            if (isArchiveImport) {
                return IncomingMailIntent.ImportArchive(streamUris)
            }

            return IncomingMailIntent.Compose(
                extractComposeRequest(intent, dataString, streamUris, nextEventId),
            )
        }

        return IncomingMailIntent.None
    }

    private fun extractComposeRequest(
        intent: Intent,
        dataUriString: String?,
        streamUris: List<Uri>,
        nextEventId: () -> Long,
    ): ExternalComposeRequest {
        val extraTo = readAddressExtras(intent, Intent.EXTRA_EMAIL)
        val extraCc = readAddressExtras(intent, Intent.EXTRA_CC)
        val extraBcc = readAddressExtras(intent, Intent.EXTRA_BCC)
        val extraSubject = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString()
        val extraBody = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()

        val payload = buildComposePayload(
            action = intent.action,
            dataUriString = dataUriString,
            extraTo = extraTo,
            extraCc = extraCc,
            extraBcc = extraBcc,
            extraSubject = extraSubject,
            extraBody = extraBody,
        )

        return ExternalComposeRequest(
            eventId = nextEventId(),
            toAddresses = payload.to,
            ccAddresses = payload.cc,
            bccAddresses = payload.bcc,
            subject = payload.subject,
            bodyText = payload.body,
            attachmentUris = streamUris,
        )
    }

    private fun readAddressExtras(intent: Intent, key: String): List<String> {
        intent.getStringArrayExtra(key)?.takeIf { it.isNotEmpty() }?.let { return it.toList() }
        intent.getStringArrayListExtra(key)?.takeIf { it.isNotEmpty() }?.let { return it }
        intent.getStringExtra(key)?.takeIf { it.isNotBlank() }?.let { return listOf(it) }
        return emptyList()
    }

    private fun extractStreamUris(intent: Intent): List<Uri> {
        val fromExtra = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java),
            )
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(
                intent,
                Intent.EXTRA_STREAM,
                Uri::class.java,
            ).orEmpty()
            else -> emptyList()
        }
        if (fromExtra.isNotEmpty()) return fromExtra

        val clip = intent.clipData ?: return emptyList()
        val collected = mutableListOf<Uri>()
        for (i in 0 until clip.itemCount) {
            clip.getItemAt(i)?.uri?.let { collected += it }
        }
        return collected
    }

    private fun shouldTreatSendIntentAsArchiveImport(
        context: Context,
        intent: Intent,
        uris: List<Uri>,
    ): Boolean {
        val intentMime = intent.type?.lowercase().orEmpty()
        if (intentMime == "message/rfc822" || intentMime == "application/mbox") {
            return true
        }
        return uris.all { uri -> isArchiveOrConfigUri(context, uri) }
    }

    private fun isArchiveOrConfigUri(context: Context, uri: Uri): Boolean {
        val mime = context.contentResolver.getType(uri)?.lowercase().orEmpty()
        if (mime == "message/rfc822" || mime == "application/mbox") {
            return true
        }
        var displayName = uri.lastPathSegment.orEmpty()
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        c.getString(idx)?.let { displayName = it }
                    }
                }
            }
        }
        val lower = displayName.lowercase()
        return lower.endsWith(".eml") || lower.endsWith(".mbox") || lower.endsWith(".k9s")
    }

    private fun splitAddressList(items: List<String>): List<String> {
        val out = mutableListOf<String>()
        items.forEach { addSplitAddresses(out, it) }
        return out
    }

    private fun addSplitAddresses(target: MutableList<String>, raw: String) {
        raw.split(',', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { target += it }
    }

    private fun deduplicateAddresses(items: List<String>): List<String> {
        val seen = LinkedHashSet<String>()
        val result = mutableListOf<String>()
        for (item in items) {
            val trimmed = item.trim()
            if (trimmed.isEmpty()) continue
            val key = trimmed.lowercase()
            if (seen.add(key)) {
                result += trimmed
            }
        }
        return result
    }

    private fun normalizeLineEndings(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n')

    /**
     * Percent-decodes UTF-8 `%XX` sequences according to RFC 3986 / RFC 6068 without
     * mutating literal `'+'` characters (preserving sub-addressed emails like `user+tag@example.com`).
     */
    internal fun percentDecode(input: String): String {
        if ('%' !in input) return input
        val out = ByteArrayOutputStream(input.length)
        var i = 0
        while (i < input.length) {
            val ch = input[i]
            if (ch == '%' && i + 2 < input.length) {
                val hi = Character.digit(input[i + 1], 16)
                val lo = Character.digit(input[i + 2], 16)
                if (hi >= 0 && lo >= 0) {
                    out.write((hi shl 4) or lo)
                    i += 3
                    continue
                }
            }
            val bytes = ch.toString().toByteArray(StandardCharsets.UTF_8)
            out.write(bytes)
            i++
        }
        return out.toByteArray().toString(StandardCharsets.UTF_8)
    }
}
