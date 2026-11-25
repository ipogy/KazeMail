package com.kazemail.app.ui.viewer

import java.util.Locale

private const val MAX_HTML_DETECT_SAMPLE_CHARS = 32 * 1024

private data class HtmlScanSummary(
    var hasDoctypeHtml: Boolean = false,
    var hasHtmlOpen: Boolean = false,
    var hasHtmlClose: Boolean = false,
    var hasHeadOpen: Boolean = false,
    var hasHeadClose: Boolean = false,
    var hasBodyOpen: Boolean = false,
    var hasBodyClose: Boolean = false,
    var hasBodyAttrs: Boolean = false,
    val distinctNonRootTags: MutableSet<String> = HashSet(),
    var totalRecognizedTags: Int = 0,
    var closingTagCount: Int = 0,
    var attributeTagCount: Int = 0,
    var brCount: Int = 0,
)

object HtmlEmailDetector {
    private val htmlTags = setOf(
        "html",
        "head",
        "body",
        "meta",
        "style",
        "title",
        "link",
        "div",
        "table",
        "tbody",
        "thead",
        "tfoot",
        "tr",
        "td",
        "th",
        "p",
        "br",
        "hr",
        "a",
        "img",
        "span",
        "font",
        "center",
        "ul",
        "ol",
        "li",
        "dl",
        "dt",
        "dd",
        "h1",
        "h2",
        "h3",
        "h4",
        "h5",
        "h6",
        "blockquote",
        "pre",
        "code",
        "strong",
        "b",
        "em",
        "i",
        "u",
        "s",
        "small",
        "big",
        "sub",
        "sup",
        "section",
        "article",
        "header",
        "footer",
        "main",
        "nav",
        "aside",
        "figure",
        "figcaption",
        "svg",
    )

    private val rootTags = setOf("html", "head", "body")

    private val commentRegex = Regex("(?s)<!--.*?-->")
    private val headRegex = Regex("(?is)<head\\b[^>]*>.*?</head>")
    private val styleRegex = Regex("(?is)<style\\b[^>]*>.*?</style>")
    private val scriptRegex = Regex("(?is)<script\\b[^>]*>.*?</script>")
    private val lineBreakTagRegex = Regex("(?i)<(?:br|hr)\\s*/?>")
    private val blockCloseTagRegex = Regex("(?i)</(?:p|div|tr|li|h[1-6]|blockquote|pre|section|article|header|footer)>")
    private val cellCloseTagRegex = Regex("(?i)</(?:td|th)>")
    private val tagStripRegex = Regex("<[^>]+>")
    private val trailingSpacesRegex = Regex("[ \\t]+\\n")
    private val multiNewlinesRegex = Regex("\\n{3,}")
    private val numericEntityRegex = Regex("&#(x[0-9a-fA-F]+|\\d+);")

    /**
     * Verifies that a `text/html` MIME candidate actually contains valid HTML markup
     * rather than plain text accidentally copied into `html_zstd`.
     */
    fun containsActualHtml(candidate: String): Boolean {
        val trimmed = candidate.trim()
        if (trimmed.isEmpty() || !trimmed.contains('<') || !trimmed.contains('>')) {
            return false
        }
        val summary = analyzeStructure(trimmed, ignoreMarkdownCode = false)
        return summary.hasDoctypeHtml || summary.totalRecognizedTags >= 1
    }

    /**
     * Strict detector used when inspecting `plainText` (or mislabeled `text/plain` content).
     * Guards against tag-prefix collisions (`<header>`, `<trace>`, `<old>`), prose inequalities
     * (`<b and c > d`), single bare mentions, and Markdown code blocks/inline backticks.
     */
    fun looksLikeRawHtmlDocument(plainText: String): Boolean {
        val trimmed = plainText.trim()
        if (trimmed.isEmpty() || !trimmed.contains('<') || !trimmed.contains('>')) {
            return false
        }
        val s = analyzeStructure(trimmed, ignoreMarkdownCode = true)
        if (s.hasDoctypeHtml) return true
        val hasMarkupEvidence = s.closingTagCount >= 1 || s.attributeTagCount >= 1
        if (!hasMarkupEvidence && s.brCount < 2) {
            return false
        }
        if (s.hasHtmlOpen && (s.hasBodyOpen || s.hasHeadOpen || s.hasHtmlClose || s.distinctNonRootTags.isNotEmpty())) {
            return true
        }
        if (s.hasBodyOpen && (s.hasBodyClose || s.hasBodyAttrs || s.distinctNonRootTags.isNotEmpty())) {
            return true
        }
        if (s.hasHeadOpen && (s.hasHeadClose || s.distinctNonRootTags.isNotEmpty())) {
            return true
        }
        if (s.distinctNonRootTags.size >= 2) {
            return true
        }
        if (s.attributeTagCount >= 1 && s.closingTagCount >= 1) {
            return true
        }
        return s.brCount >= 2
    }

    fun stripHtmlFallbackText(rawHtml: String): String {
        val withoutBlocks = rawHtml
            .replace(commentRegex, " ")
            .replace(headRegex, " ")
            .replace(styleRegex, " ")
            .replace(scriptRegex, " ")
            .replace(lineBreakTagRegex, "\n")
            .replace(cellCloseTagRegex, " ")
            .replace(blockCloseTagRegex, "\n")
            .replace(tagStripRegex, "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&yen;", "¥")
            .replace("&copy;", "©")
            .replace("&ndash;", "–")
            .replace("&mdash;", "—")

        val decodedEntities = numericEntityRegex.replace(withoutBlocks) { match ->
            val rawNum = match.groupValues[1]
            val cp = runCatching {
                if (rawNum.startsWith("x", ignoreCase = true)) {
                    rawNum.substring(1).toInt(16)
                } else {
                    rawNum.toInt(10)
                }
            }.getOrNull()
            if (cp != null && cp > 0 && Character.isValidCodePoint(cp)) {
                String(Character.toChars(cp))
            } else {
                match.value
            }
        }

        return decodedEntities
            .replace("\r\n", "\n")
            .replace(trailingSpacesRegex, "\n")
            .replace(multiNewlinesRegex, "\n\n")
            .trim()
    }

    private fun analyzeStructure(text: String, ignoreMarkdownCode: Boolean): HtmlScanSummary {
        val len = minOf(text.length, MAX_HTML_DETECT_SAMPLE_CHARS)
        val summary = HtmlScanSummary()
        var i = 0
        var atLineStart = true

        while (i < len) {
            if (ignoreMarkdownCode && atLineStart) {
                val fenceEnd = findFencedCodeEnd(text, i, len)
                if (fenceEnd >= 0) {
                    i = fenceEnd
                    atLineStart = true
                    continue
                }
            }
            if (ignoreMarkdownCode && text[i] == '`') {
                val tickEnd = findInlineBacktickEnd(text, i, len)
                if (tickEnd >= 0) {
                    i = tickEnd
                    atLineStart = false
                    continue
                }
            }

            if (text[i] != '<') {
                atLineStart = text[i] == '\n'
                i += 1
                continue
            }
            atLineStart = false

            if (text.startsWith("<!--", startIndex = i)) {
                val closeComment = text.indexOf("-->", startIndex = i + 4)
                if (closeComment < 0 || closeComment >= len) break
                i = closeComment + 3
                continue
            }

            if (text.regionMatches(i, "<!doctype", 0, 9, ignoreCase = true)) {
                var p = i + 9
                var ws = 0
                while (p < len && text[p].isAsciiWhitespace()) {
                    ws += 1
                    p += 1
                }
                if (ws >= 1 && text.regionMatches(p, "html", 0, 4, ignoreCase = true)) {
                    val after = p + 4
                    val boundary = after < len && (text[after] == '>' || text[after].isAsciiWhitespace())
                    if (boundary) {
                        val gtSearchLimit = minOf(len, after + 512)
                        val gt = findCharBeforeLimit(text, after, gtSearchLimit, '>')
                        if (gt >= 0) {
                            summary.hasDoctypeHtml = true
                            summary.totalRecognizedTags += 1
                            i = gt + 1
                            continue
                        }
                    }
                }
                i += 1
                continue
            }

            val isClosing = i + 1 < len && text[i + 1] == '/'
            val nameStart = if (isClosing) i + 2 else i + 1
            var nameEnd = nameStart
            while (nameEnd < len && text[nameEnd].isAsciiAlphaNum()) {
                nameEnd += 1
            }
            if (nameEnd == nameStart || nameEnd - nameStart > 12) {
                i += 1
                continue
            }
            val tagName = text.substring(nameStart, nameEnd).lowercase(Locale.US)
            if (tagName !in htmlTags) {
                i += 1
                continue
            }

            if (isClosing) {
                var p = nameEnd
                val closeLimit = minOf(len, nameEnd + 32)
                while (p < closeLimit && text[p].isAsciiWhitespace()) {
                    p += 1
                }
                if (p >= closeLimit || text[p] != '>') {
                    i += 1
                    continue
                }
                summary.closingTagCount += 1
                summary.totalRecognizedTags += 1
                if (tagName !in rootTags) {
                    summary.distinctNonRootTags.add(tagName)
                }
                when (tagName) {
                    "html" -> summary.hasHtmlClose = true
                    "head" -> summary.hasHeadClose = true
                    "body" -> summary.hasBodyClose = true
                }
                i = p + 1
                continue
            }

            val next = if (nameEnd < len) text[nameEnd] else '\u0000'
            val boundaryOk = next == '>' ||
                next.isAsciiWhitespace() ||
                (next == '/' && nameEnd + 1 < len && (text[nameEnd + 1] == '>' || text[nameEnd + 1].isAsciiWhitespace()))
            if (!boundaryOk) {
                i += 1
                continue
            }

            val openMatch = findClosingGtInOpenTag(text, nameEnd, len)
            if (openMatch == null) {
                i += 1
                continue
            }
            val (gtPos, hasEq) = openMatch

            summary.totalRecognizedTags += 1
            if (tagName !in rootTags) {
                summary.distinctNonRootTags.add(tagName)
            }
            if (hasEq) {
                summary.attributeTagCount += 1
            }
            when (tagName) {
                "html" -> summary.hasHtmlOpen = true
                "head" -> summary.hasHeadOpen = true
                "body" -> {
                    summary.hasBodyOpen = true
                    if (hasEq) summary.hasBodyAttrs = true
                }
                "br" -> summary.brCount += 1
            }

            i = gtPos + 1
        }

        return summary
    }

    private fun findFencedCodeEnd(text: String, lineStart: Int, len: Int): Int {
        var pos = lineStart
        var spaces = 0
        while (pos < len && text[pos] == ' ' && spaces < 4) {
            spaces += 1
            pos += 1
        }
        if (spaces >= 4 || pos + 3 > len || !text.startsWith("```", startIndex = pos)) {
            return -1
        }
        while (pos < len && text[pos] != '\n') pos += 1
        if (pos < len) pos += 1
        while (pos < len) {
            var p = pos
            var s = 0
            while (p < len && text[p] == ' ' && s < 4) {
                s += 1
                p += 1
            }
            val isClose = s < 4 && p + 3 <= len && text.startsWith("```", startIndex = p)
            while (pos < len && text[pos] != '\n') pos += 1
            if (pos < len) pos += 1
            if (isClose) return pos
        }
        return len
    }

    private fun findInlineBacktickEnd(text: String, openTick: Int, len: Int): Int {
        val limit = minOf(len, openTick + 256)
        for (pos in (openTick + 1) until limit) {
            val ch = text[pos]
            if (ch == '\n' || ch == '\r') return -1
            if (ch == '`') return pos + 1
        }
        return -1
    }

    private fun findClosingGtInOpenTag(text: String, nameEnd: Int, len: Int): Pair<Int, Boolean>? {
        val limit = minOf(len, nameEnd + 2048)
        var quote: Char? = null
        var hasEq = false
        var trailingWords = 0
        var inWord = false
        var lastNonWs: Char? = null

        for (pos in nameEnd until limit) {
            val ch = text[pos]
            if (quote != null) {
                if (ch == quote) quote = null
                continue
            }
            when {
                ch == '>' -> {
                    val selfClosingSlashOnly = lastNonWs == '/' && trailingWords == 1
                    if (!hasEq && trailingWords >= 2 && !selfClosingSlashOnly) {
                        return null
                    }
                    return pos to hasEq
                }
                ch == '<' -> return null
                ch == '"' || ch == '\'' -> {
                    quote = ch
                    if (!inWord) {
                        trailingWords += 1
                        inWord = true
                    }
                    lastNonWs = ch
                }
                ch.isAsciiWhitespace() -> inWord = false
                ch == '=' -> {
                    hasEq = true
                    lastNonWs = ch
                }
                else -> {
                    if (!inWord) {
                        if (!(ch == '/' && pos + 1 < len && text[pos + 1] == '>')) {
                            trailingWords += 1
                        }
                        inWord = true
                    }
                    lastNonWs = ch
                }
            }
        }
        return null
    }

    private fun findCharBeforeLimit(text: String, start: Int, limit: Int, target: Char): Int {
        for (pos in start until limit) {
            if (text[pos] == target) return pos
        }
        return -1
    }

    private fun Char.isAsciiWhitespace(): Boolean =
        this == ' ' || this == '\t' || this == '\n' || this == '\r'

    private fun Char.isAsciiAlphaNum(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
}
