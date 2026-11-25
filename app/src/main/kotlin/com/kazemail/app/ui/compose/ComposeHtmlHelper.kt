package com.kazemail.app.ui.compose

import com.kazemail.app.ui.viewer.HtmlEmailDetector

enum class HtmlFormatAction {
    BOLD,
    ITALIC,
    UNDERLINE,
    HEADING,
    BULLET_LIST,
    ACCENT_COLOR,
    PARAGRAPH,
    LINE_BREAK,
}

data class HtmlTextEditResult(
    val newText: String,
    val selectionStart: Int,
    val selectionEnd: Int,
)

/**
 * Pure-Kotlin helpers for HTML email composition, plain <-> HTML conversion,
 * formatting toolbar edits, signature formatting, and outgoing MIME HTML wrapping.
 */
object ComposeHtmlHelper {
    const val SIGNATURE_DATA_ATTR = "data-kazemail-signature=\"1\""
    const val QUOTE_DATA_ATTR = "data-kazemail-quote=\"1\""
    const val FORWARD_DATA_ATTR = "data-kazemail-forward=\"1\""

    private val sigOpenTagRegex = Regex("""<div\b[^>]*\bdata-kazemail-signature\s*=\s*["']1["'][^>]*>""", RegexOption.IGNORE_CASE)
    private val quoteOpenTagRegex = Regex("""<blockquote\b[^>]*\bdata-kazemail-quote\s*=\s*["']1["'][^>]*>""", RegexOption.IGNORE_CASE)
    private val forwardOpenTagRegex = Regex("""<div\b[^>]*\bdata-kazemail-forward\s*=\s*["']1["'][^>]*>""", RegexOption.IGNORE_CASE)
    private val plainStyleSigHtmlRegex = Regex("""(?is)(?:<p[^>]*>\s*)?--\s*(?:<br\s*/?>|</p>|\r?\n)""")

    private val doctypeRegex = Regex("""(?is)<!doctype\b[^>]*>""")
    private val headBlockRegex = Regex("""(?is)<head\b[^>]*>.*?</head>""")
    private val scriptBlockRegex = Regex("""(?is)<script\b[^>]*>.*?</script>""")
    private val styleBlockRegex = Regex("""(?is)<style\b[^>]*>.*?</style>""")
    private val htmlShellTagRegex = Regex("""(?is)</?html\b[^>]*>""")
    private val bodyShellTagRegex = Regex("""(?is)</?body\b[^>]*>""")
    private val htmlDocCheckRegex = Regex("""(?is)<html\b""")

    private val markdownFenceStartRegex = Regex("""(?is)^\s*```(?:html)?\s*\r?\n?""")
    private val markdownFenceEndRegex = Regex("""(?is)\r?\n?```\s*$""")
    private val brWithNewlineRegex = Regex("""(?is)<br\s*/?>[ \t]*\r?\n""")
    private val listItemOpenRegex = Regex("""(?is)<li\b[^>]*>""")
    private val trivialParagraphOrBreakTagRegex = Regex("""(?is)</?(?:p|br)\b\s*/?>""")

    fun escapeHtml(raw: String): String = buildString(raw.length + 16) {
        for (ch in raw) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(ch)
            }
        }
    }

    fun plainTextToHtmlFragment(plain: String): String {
        val normalized = plain.replace("\r\n", "\n").trim()
        if (normalized.isEmpty()) return ""
        val paragraphs = normalized.split(Regex("""\n{2,}"""))
        return paragraphs.joinToString("\n") { para ->
            val escapedLines = escapeHtml(para.trimEnd()).replace("\n", "<br>\n")
            "<p>$escapedLines</p>"
        }
    }

    fun htmlToPlainText(rawHtml: String): String {
        if (rawHtml.isBlank()) return ""
        val normalized = rawHtml
            .replace(brWithNewlineRegex, "\n")
            .replace(listItemOpenRegex, "\n• ")
        return HtmlEmailDetector.stripHtmlFallbackText(normalized)
    }

    fun isStrictSignatureDelimiterLine(line: String): Boolean {
        val clean = line.trimEnd('\r')
        return clean == "--" || clean == "-- "
    }

    /**
     * Returns true when [html] contains actual HTML markup beyond simple `<p>` / `<br>` paragraphs,
     * such as bold, links, headings, lists, styles, tables, or images.
     */
    fun containsRichBodyMarkup(html: String): Boolean {
        val trimmed = html.trim()
        if (trimmed.isEmpty()) return false
        val withoutSimpleBreaks = trimmed.replace(trivialParagraphOrBreakTagRegex, "")
        return HtmlEmailDetector.containsActualHtml(withoutSimpleBreaks)
    }

    fun formatSignatureAsPlain(rawSignature: String?): String? {
        val trimmed = rawSignature?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (HtmlEmailDetector.containsActualHtml(trimmed)) {
            htmlToPlainText(trimmed).trim().takeIf { it.isNotEmpty() }
        } else {
            trimmed
        }
    }

    fun formatSignaturePlainBlock(rawSignature: String?): String? {
        val plain = formatSignatureAsPlain(rawSignature) ?: return null
        val normalized = plain.replace("\r\n", "\n")
        val lines = normalized.lines()
        val firstLine = lines.firstOrNull().orEmpty()
        return if (isStrictSignatureDelimiterLine(firstLine)) {
            val rest = lines.drop(1).joinToString("\n").trimStart('\n')
            if (rest.isEmpty()) "-- " else "-- \n$rest"
        } else {
            "-- \n$normalized"
        }
    }

    fun formatSignatureBlockAsHtml(rawSignature: String?): String {
        val trimmed = rawSignature?.trim()?.takeIf { it.isNotEmpty() } ?: return ""
        val isHtmlSig = HtmlEmailDetector.containsActualHtml(trimmed)
        return if (isHtmlSig) {
            "<div $SIGNATURE_DATA_ATTR style=\"margin-top: 16px;\">$trimmed</div>"
        } else {
            val normalized = trimmed.replace("\r\n", "\n")
            val lines = normalized.lines()
            val firstLine = lines.firstOrNull().orEmpty()
            val prefixed = if (isStrictSignatureDelimiterLine(firstLine)) {
                val rest = lines.drop(1).joinToString("\n").trimStart('\n')
                if (rest.isEmpty()) "-- " else "-- \n$rest"
            } else {
                "-- \n$normalized"
            }
            val inner = escapeHtml(prefixed).replace("\n", "<br>\n")
            "<div $SIGNATURE_DATA_ATTR style=\"margin-top: 16px; color: #475569;\">$inner</div>"
        }
    }

    fun composePlainBody(
        editableBody: String,
        signatureRaw: String?,
        includeSignature: Boolean,
        quotePlain: String,
        includeQuote: Boolean,
    ): String {
        val head = editableBody.trimEnd('\r', '\n')
        val sig = if (includeSignature) formatSignaturePlainBlock(signatureRaw).orEmpty() else ""
        val quote = if (includeQuote) quotePlain.trimStart('\r', '\n') else ""
        return listOfNotNull(
            head.takeIf { it.isNotBlank() || (sig.isEmpty() && quote.isEmpty()) },
            sig.takeIf { it.isNotEmpty() },
            quote.takeIf { it.isNotEmpty() },
        ).joinToString("\n\n")
    }

    fun composeHtmlBody(
        editableHtml: String,
        signatureRaw: String?,
        includeSignature: Boolean,
        quoteHtml: String?,
        includeQuote: Boolean,
    ): String {
        val head = editableHtml.trim()
        val sig = if (includeSignature) formatSignatureBlockAsHtml(signatureRaw) else ""
        val quote = if (includeQuote) quoteHtml?.trim().orEmpty() else ""
        return listOfNotNull(
            head.takeIf { it.isNotEmpty() },
            sig.takeIf { it.isNotEmpty() },
            quote.takeIf { it.isNotEmpty() },
        ).joinToString("\n")
    }

    fun formatQuotedReplyHtml(
        headerLine: String,
        bodyHtmlOrNull: String?,
        fallbackPlainText: String,
    ): String {
        val quoteInner = when {
            !bodyHtmlOrNull.isNullOrBlank() && HtmlEmailDetector.containsActualHtml(bodyHtmlOrNull) ->
                extractEmbeddableBodyFragment(bodyHtmlOrNull)
            else -> plainTextToHtmlFragment(fallbackPlainText).ifBlank { "<p></p>" }
        }
        val escapedHeader = escapeHtml(headerLine.trim())
        return "<blockquote $QUOTE_DATA_ATTR style=\"margin: 16px 0 0 0; padding-left: 12px; border-left: 2px solid #cbd5e1; color: #475569;\">\n" +
            "<p><strong>$escapedHeader</strong></p>\n" +
            "$quoteInner\n" +
            "</blockquote>"
    }

    fun formatForwardedHtml(
        headerLinesPlain: String,
        bodyHtmlOrNull: String?,
        fallbackPlainText: String,
    ): String {
        val metaHtml = plainTextToHtmlFragment(headerLinesPlain)
        val innerHtml = when {
            !bodyHtmlOrNull.isNullOrBlank() && HtmlEmailDetector.containsActualHtml(bodyHtmlOrNull) ->
                extractEmbeddableBodyFragment(bodyHtmlOrNull)
            else -> plainTextToHtmlFragment(fallbackPlainText)
        }
        return "<div $FORWARD_DATA_ATTR style=\"margin-top: 16px; padding-top: 12px; border-top: 1px solid #cbd5e1;\">\n" +
            "<p style=\"color: #475569;\"><strong>---------- 転送メッセージ ----------</strong></p>\n" +
            "$metaHtml\n" +
            "$innerHtml\n" +
            "</div>"
    }

    fun extractEmbeddableBodyFragment(rawHtml: String): String {
        return rawHtml
            .replace(doctypeRegex, "")
            .replace(headBlockRegex, "")
            .replace(scriptBlockRegex, "")
            .replace(styleBlockRegex, "")
            .replace(htmlShellTagRegex, "")
            .replace(bodyShellTagRegex, "")
            .trim()
    }

    fun sanitizeAiHtmlOutput(rawOutput: String): String {
        val unfenced = rawOutput
            .replace(markdownFenceStartRegex, "")
            .replace(markdownFenceEndRegex, "")
            .trim()
        if (unfenced.isEmpty()) return ""
        val extracted = if (htmlDocCheckRegex.containsMatchIn(unfenced)) {
            extractEmbeddableBodyFragment(unfenced)
        } else {
            unfenced
        }
        return if (HtmlEmailDetector.containsActualHtml(extracted)) {
            extracted
        } else {
            plainTextToHtmlFragment(extracted)
        }
    }

    fun buildFullOutgoingHtmlDocument(fragmentOrDoc: String): String {
        val trimmed = fragmentOrDoc.trim()
        if (trimmed.isEmpty()) {
            return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head><body></body></html>"
        }
        if (htmlDocCheckRegex.containsMatchIn(trimmed)) {
            return trimmed
        }
        return """
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            </head>
            <body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Hiragino Sans', 'Noto Sans JP', sans-serif; font-size: 14px; line-height: 1.6; color: #1e293b;">
            $trimmed
            </body>
            </html>
        """.trimIndent()
    }

    fun convertPlainComposeBodyToHtml(plainBody: String): String {
        val normalized = plainBody.replace("\r\n", "\n")
        if (normalized.isBlank()) return ""
        if (HtmlEmailDetector.looksLikeRawHtmlDocument(normalized)) {
            return extractEmbeddableBodyFragment(normalized)
        }
        val (headPlain, footerPlain) = ComposeAiBodyMerger.splitEditableAndFooter(normalized)
        val parts = mutableListOf<String>()
        if (headPlain.trim().isNotEmpty()) {
            parts += plainTextToHtmlFragment(headPlain)
        }
        if (footerPlain.isNotBlank()) {
            val (sigPlain, quotePlain) = splitPlainSignatureAndQuote(footerPlain)
            if (!sigPlain.isNullOrBlank()) {
                parts += formatSignatureBlockAsHtml(sigPlain)
            }
            if (quotePlain.isNotBlank()) {
                val trimmedQuote = quotePlain.trim()
                parts += if (trimmedQuote.startsWith("---------- 転送メッセージ ----------")) {
                    val afterBanner = trimmedQuote.removePrefix("---------- 転送メッセージ ----------").trimStart('\r', '\n')
                    "<div $FORWARD_DATA_ATTR style=\"margin-top: 16px; padding-top: 12px; border-top: 1px solid #cbd5e1;\">\n" +
                        "<p style=\"color: #475569;\"><strong>---------- 転送メッセージ ----------</strong></p>\n" +
                        plainTextToHtmlFragment(afterBanner) + "\n</div>"
                } else {
                    val cleanedLines = trimmedQuote.lines().joinToString("\n") { line ->
                        line.removePrefix("> ").removePrefix(">")
                    }
                    "<blockquote $QUOTE_DATA_ATTR style=\"margin: 16px 0 0 0; padding-left: 12px; border-left: 2px solid #cbd5e1; color: #475569;\">\n" +
                        plainTextToHtmlFragment(cleanedLines) + "\n</blockquote>"
                }
            }
        }
        return parts.joinToString("\n")
    }

    fun convertHtmlComposeBodyToPlain(htmlBody: String): String {
        if (htmlBody.isBlank()) return ""
        val (headHtml, sigHtml, quoteHtml) = splitHtmlDraftSignatureQuote(htmlBody)
        val headPlain = htmlToPlainText(headHtml)
        val sigPlain = sigHtml?.let { raw ->
            val stripped = htmlToPlainText(raw).trimStart()
            if (stripped.isBlank()) {
                null
            } else {
                val lines = stripped.lines()
                val firstLine = lines.firstOrNull().orEmpty()
                if (isStrictSignatureDelimiterLine(firstLine)) {
                    val rest = lines.drop(1).joinToString("\n").trimStart('\n')
                    if (rest.isEmpty()) "-- " else "-- \n$rest"
                } else {
                    "-- \n$stripped"
                }
            }
        }
        val quotePlain = quoteHtml?.let(::convertQuoteHtmlToPlain)
        return listOfNotNull(
            headPlain.takeIf { it.isNotEmpty() },
            sigPlain,
            quotePlain,
        ).joinToString("\n\n")
    }

    fun convertQuoteHtmlToPlain(quoteHtml: String): String? {
        val stripped = htmlToPlainText(quoteHtml).trim()
        if (stripped.isBlank()) return null
        if (quoteHtml.contains("data-kazemail-forward", ignoreCase = true)) {
            return stripped
        }
        val lines = stripped.lines()
        val header = lines.firstOrNull().orEmpty()
        val rest = lines.drop(1)
        return if (rest.isEmpty()) {
            header
        } else {
            buildString {
                append(header).append('\n')
                rest.forEach { append("> ").append(it).append('\n') }
            }.trimEnd()
        }
    }

    fun splitPlainDraftParts(fullPlain: String): Triple<String, String?, String?> {
        val (head, footer) = ComposeAiBodyMerger.splitEditableAndFooter(fullPlain)
        val (sig, quote) = splitPlainSignatureAndQuote(footer)
        return Triple(head.trimEnd('\r', '\n'), sig, quote.takeIf { it.isNotBlank() })
    }

    fun extractInnerFromSavedSignatureDiv(sigHtmlBlock: String): String {
        val openMatch = sigOpenTagRegex.find(sigHtmlBlock) ?: return sigHtmlBlock.trim()
        val closeEnd = findMatchingCloseTagEnd(sigHtmlBlock, openMatch.range.last + 1, "div")
        val contentEnd = if (closeEnd >= 6 && sigHtmlBlock.substring(closeEnd - 6, closeEnd).equals("</div>", ignoreCase = true)) {
            closeEnd - 6
        } else {
            closeEnd
        }
        val inner = sigHtmlBlock.substring(openMatch.range.last + 1, contentEnd).trim()
        return if (!HtmlEmailDetector.containsActualHtml(inner.replace(Regex("(?i)<br\\s*/?>"), ""))) {
            val stripped = htmlToPlainText(inner).trim()
            val lines = stripped.lines()
            if (lines.isNotEmpty() && isStrictSignatureDelimiterLine(lines.first())) {
                lines.drop(1).joinToString("\n").trimStart('\n')
            } else {
                stripped
            }
        } else {
            inner
        }
    }

    /**
     * Replaces or inserts the active account signature while keeping editable draft text
     * and any quoted/forwarded history intact.
     */
    fun replaceSignatureInPlainBody(
        fullBody: String,
        newSignatureRaw: String?,
    ): String {
        val (headPlain, footerPlain) = ComposeAiBodyMerger.splitEditableAndFooter(fullBody)
        val (_, quotePlain) = splitPlainSignatureAndQuote(footerPlain)
        val formattedSig = formatSignatureAsPlain(newSignatureRaw)
        val headTrimmedEnd = headPlain.trimEnd()
        val parts = buildList {
            if (headTrimmedEnd.isNotEmpty()) add(headTrimmedEnd)
            if (formattedSig != null) add("-- \n$formattedSig")
            if (quotePlain.isNotBlank()) add(quotePlain.trimStart('\r', '\n'))
        }
        if (parts.isEmpty()) return ""
        return if (headTrimmedEnd.isEmpty() && formattedSig != null) {
            "\n\n" + parts.joinToString("\n\n")
        } else {
            parts.joinToString("\n\n")
        }
    }

    fun replaceSignatureInHtmlBody(
        fullHtml: String,
        newSignatureRaw: String?,
    ): String {
        val (headHtml, _, quoteHtml) = splitHtmlDraftSignatureQuote(fullHtml)
        val newSigHtml = formatSignatureBlockAsHtml(newSignatureRaw)
        val parts = buildList {
            if (headHtml.isNotBlank()) add(headHtml.trimEnd())
            if (newSigHtml.isNotEmpty()) add(newSigHtml)
            if (!quoteHtml.isNullOrBlank()) add(quoteHtml.trim())
        }
        if (parts.isEmpty()) return ""
        return if (headHtml.isBlank() && newSigHtml.isNotEmpty()) {
            "<p><br></p>\n" + parts.joinToString("\n")
        } else {
            parts.joinToString("\n")
        }
    }

    /**
     * Splits an HTML compose body into `(editableHeadHtml, signatureHtmlOrNull, quoteOrForwardHtmlOrNull)`.
     */
    fun splitHtmlDraftSignatureQuote(fullHtml: String): Triple<String, String?, String?> {
        val sigMatch = sigOpenTagRegex.find(fullHtml)
        val quoteMatch = quoteOpenTagRegex.find(fullHtml)
        val forwardMatch = forwardOpenTagRegex.find(fullHtml)

        val quoteOrFwdStart = listOfNotNull(quoteMatch?.range?.first, forwardMatch?.range?.first).minOrNull()
        val quoteOrFwdEnd = when (quoteOrFwdStart) {
            quoteMatch?.range?.first -> quoteMatch?.let { findMatchingCloseTagEnd(fullHtml, it.range.last + 1, "blockquote") }
            forwardMatch?.range?.first -> forwardMatch?.let { findMatchingCloseTagEnd(fullHtml, it.range.last + 1, "div") }
            else -> null
        }

        if (sigMatch != null) {
            val sigStart = sigMatch.range.first
            val sigEnd = findMatchingCloseTagEnd(fullHtml, sigMatch.range.last + 1, "div")
            val head = fullHtml.substring(0, sigStart).trimEnd()
            val sig = fullHtml.substring(sigStart, sigEnd).trim()
            val afterSig = fullHtml.substring(sigEnd).trim()
            val quote = afterSig.takeIf { it.isNotEmpty() }
            return Triple(head, sig, quote)
        }

        if (quoteOrFwdStart != null) {
            val beforeQuote = fullHtml.substring(0, quoteOrFwdStart)
            val plainSigMatch = plainStyleSigHtmlRegex.find(beforeQuote)
            return if (plainSigMatch != null) {
                val head = beforeQuote.substring(0, plainSigMatch.range.first).trimEnd()
                val sig = beforeQuote.substring(plainSigMatch.range.first).trim()
                val quote = fullHtml.substring(quoteOrFwdStart, quoteOrFwdEnd ?: fullHtml.length).trim()
                Triple(head, sig, quote.ifEmpty { null })
            } else {
                val head = beforeQuote.trimEnd()
                val quote = fullHtml.substring(quoteOrFwdStart).trim()
                Triple(head, null, quote.ifEmpty { null })
            }
        }

        val plainSigOnly = plainStyleSigHtmlRegex.find(fullHtml)
        return if (plainSigOnly != null) {
            val head = fullHtml.substring(0, plainSigOnly.range.first).trimEnd()
            val sig = fullHtml.substring(plainSigOnly.range.first).trim()
            Triple(head, sig.ifEmpty { null }, null)
        } else {
            Triple(fullHtml, null, null)
        }
    }

    fun applyFormattingAction(
        text: String,
        selectionStart: Int,
        selectionEnd: Int,
        action: HtmlFormatAction,
    ): HtmlTextEditResult {
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val selected = text.substring(start, end)
        val before = text.substring(0, start)
        val after = text.substring(end)

        return when (action) {
            HtmlFormatAction.BOLD -> wrapTag(before, selected, after, "<b>", "</b>")
            HtmlFormatAction.ITALIC -> wrapTag(before, selected, after, "<i>", "</i>")
            HtmlFormatAction.UNDERLINE -> wrapTag(before, selected, after, "<u>", "</u>")
            HtmlFormatAction.HEADING -> wrapTag(before, selected, after, "<h3>", "</h3>")
            HtmlFormatAction.ACCENT_COLOR ->
                wrapTag(before, selected, after, "<span style=\"color:#C62828\">", "</span>")
            HtmlFormatAction.PARAGRAPH -> wrapTag(before, selected, after, "<p>", "</p>")
            HtmlFormatAction.LINE_BREAK -> {
                val insertion = "<br>\n"
                val newText = before + insertion + after
                val pos = before.length + insertion.length
                HtmlTextEditResult(newText, pos, pos)
            }
            HtmlFormatAction.BULLET_LIST -> {
                if (selected.isNotEmpty()) {
                    val items = selected.lines()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .joinToString("\n") { "  <li>$it</li>" }
                    val block = "<ul>\n$items\n</ul>"
                    val newText = before + block + after
                    val pos = before.length + block.length
                    HtmlTextEditResult(newText, pos, pos)
                } else {
                    val prefix = "<ul>\n  <li>"
                    val suffix = "</li>\n</ul>"
                    val newText = before + prefix + suffix + after
                    val cursor = before.length + prefix.length
                    HtmlTextEditResult(newText, cursor, cursor)
                }
            }
        }
    }

    fun insertLink(
        text: String,
        selectionStart: Int,
        selectionEnd: Int,
        rawUrl: String,
        rawLabel: String,
    ): HtmlTextEditResult {
        val normalizedUrl = normalizeLinkUrl(rawUrl)
        if (normalizedUrl.isEmpty()) {
            val safePos = selectionEnd.coerceIn(0, text.length)
            return HtmlTextEditResult(text, safePos, safePos)
        }
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val selected = text.substring(start, end)
        val displayLabel = rawLabel.trim().ifEmpty { selected.ifBlank { normalizedUrl } }
        val anchor = "<a href=\"${escapeHtml(normalizedUrl)}\">${escapeHtml(displayLabel)}</a>"
        val newText = text.substring(0, start) + anchor + text.substring(end)
        val cursor = start + anchor.length
        return HtmlTextEditResult(newText, cursor, cursor)
    }

    fun normalizeLinkUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return ""
        val lower = trimmed.lowercase()
        return when {
            lower.startsWith("http://") ||
                lower.startsWith("https://") ||
                lower.startsWith("mailto:") ||
                lower.startsWith("tel:") -> trimmed
            trimmed.contains('@') && !trimmed.contains('/') -> "mailto:$trimmed"
            else -> "https://$trimmed"
        }
    }

    private fun wrapTag(
        before: String,
        selected: String,
        after: String,
        openTag: String,
        closeTag: String,
    ): HtmlTextEditResult {
        val newText = before + openTag + selected + closeTag + after
        val cursor = if (selected.isEmpty()) {
            before.length + openTag.length
        } else {
            before.length + openTag.length + selected.length + closeTag.length
        }
        return HtmlTextEditResult(newText, cursor, cursor)
    }

    private fun splitPlainSignatureAndQuote(footerPlain: String): Pair<String?, String> {
        val trimmed = footerPlain.trimStart('\r', '\n')
        if (trimmed.isEmpty()) return null to ""
        val sigHeaderRegex = Regex("""^-- ?(?:\r?\n|$)""")
        val sigMatch = sigHeaderRegex.find(trimmed) ?: return null to trimmed
        val afterSigHeader = trimmed.substring(sigMatch.range.last + 1)
        val quoteOrForwardMatch = listOfNotNull(
            Regex("""(?:\n\n|\n)---------- 転送メッセージ ----------""").find(afterSigHeader),
            Regex("""(?:\n\n|\n)\d{4}/\d{2}/\d{2} [^\n]+:\n>""").find(afterSigHeader),
        ).minByOrNull { it.range.first }

        return if (quoteOrForwardMatch != null) {
            val sigBody = afterSigHeader.substring(0, quoteOrForwardMatch.range.first).trim()
            val quote = afterSigHeader.substring(quoteOrForwardMatch.range.first).trimStart('\r', '\n')
            sigBody.takeIf { it.isNotEmpty() } to quote
        } else {
            afterSigHeader.trim().takeIf { it.isNotEmpty() } to ""
        }
    }

    private fun findMatchingCloseTagEnd(
        html: String,
        searchStart: Int,
        tagName: String,
    ): Int {
        val tagRegex = Regex("""<(/?)$tagName\b[^>]*>""", RegexOption.IGNORE_CASE)
        var depth = 1
        var cursor = searchStart
        while (cursor < html.length) {
            val match = tagRegex.find(html, cursor) ?: return html.length
            if (match.groupValues[1] == "/") {
                depth -= 1
                if (depth == 0) {
                    return match.range.last + 1
                }
            } else {
                depth += 1
            }
            cursor = match.range.last + 1
        }
        return html.length
    }
}
