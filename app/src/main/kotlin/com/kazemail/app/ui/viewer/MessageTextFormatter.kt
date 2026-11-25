package com.kazemail.app.ui.viewer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration

sealed interface BodySegment {
    data class Content(val text: String) : BodySegment
    data class Quote(val text: String, val lineCount: Int) : BodySegment
}

enum class DetectedLinkKind {
    URL,
    EMAIL,
    PHONE,
}

data class DetectedLink(
    val start: Int,
    val end: Int,
    val value: String,
    val kind: DetectedLinkKind,
)

/**
 * Splits plain-text email bodies into primary content and collapsible quote blocks,
 * and auto-links strictly URLs, email addresses, and phone numbers (Requirement 4.2.2).
 */
object MessageTextFormatter {

    private val urlRegex = Regex("""https?://[^\s<>"'\)\]]+""", RegexOption.IGNORE_CASE)
    private val emailRegex = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
    private val phoneRegex = Regex(
        """(?<![\dA-Za-z\-])(?:\+\d{1,3}[\s-]?(?:\(?\d{1,4}\)?[-\s]?\d{1,4}[-\s]?\d{3,4})|(?:\(0\d{1,4}\)|0\d{1,4})[-\s]?\d{1,4}[-\s]?\d{3,4})(?![\dA-Za-z])""",
    )
    private val attributionLineRegex = Regex(
        """^((On .+ wrote:)|(.+さんのメール:)|(\d{4}/\d{1,2}/\d{1,2}.+wrote:)|(-{3,}\s*(Original Message|元のメッセージ)\s*-{3,}))\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val forwardDividerRegex = Regex(
        """^-{5,}\s*(Original Message|Forwarded message|転送されたメッセージ|元のメッセージ)\s*-{5,}\s*$""",
        RegexOption.IGNORE_CASE,
    )

    fun splitQuotedContent(rawText: String): List<BodySegment> {
        if (rawText.isEmpty()) return emptyList()
        val lines = rawText.lines()
        val segments = mutableListOf<BodySegment>()
        val contentBuf = mutableListOf<String>()
        val quoteBuf = mutableListOf<String>()

        fun flushContent() {
            if (contentBuf.isNotEmpty()) {
                val joined = contentBuf.joinToString("\n").trimEnd()
                if (joined.isNotEmpty()) {
                    segments += BodySegment.Content(joined)
                }
                contentBuf.clear()
            }
        }

        fun flushQuote() {
            if (quoteBuf.isNotEmpty()) {
                val joined = quoteBuf.joinToString("\n").trimEnd()
                if (joined.isNotEmpty()) {
                    segments += BodySegment.Quote(joined, quoteBuf.size)
                }
                quoteBuf.clear()
            }
        }

        var idx = 0
        while (idx < lines.size) {
            val line = lines[idx]
            val trimmed = line.trimStart()

            if (forwardDividerRegex.matches(trimmed)) {
                flushQuote()
                flushContent()
                val rest = lines.subList(idx, lines.size)
                segments += BodySegment.Quote(rest.joinToString("\n").trimEnd(), rest.size)
                return segments
            }

            val isQuotedLine = trimmed.startsWith(">")
            val isAttributionBeforeQuote = !isQuotedLine &&
                attributionLineRegex.matches(trimmed) &&
                lines.getOrNull(idx + 1)?.trimStart()?.startsWith(">") == true

            if (isQuotedLine || isAttributionBeforeQuote) {
                flushContent()
                quoteBuf += line
            } else {
                // Keep a single short quote line inline unless >= 2 quote lines accumulated
                if (quoteBuf.size == 1 && !attributionLineRegex.matches(quoteBuf[0].trimStart())) {
                    contentBuf += quoteBuf
                    quoteBuf.clear()
                } else {
                    flushQuote()
                }
                contentBuf += line
            }
            idx += 1
        }

        if (quoteBuf.size == 1 && !attributionLineRegex.matches(quoteBuf[0].trimStart())) {
            contentBuf += quoteBuf
            quoteBuf.clear()
        } else {
            flushQuote()
        }
        flushContent()
        return segments
    }

    fun detectLinks(text: String): List<DetectedLink> {
        val candidates = mutableListOf<DetectedLink>()

        for (m in urlRegex.findAll(text)) {
            val trimmed = trimTrailingUrlPunctuation(m.value)
            if (trimmed.isNotEmpty()) {
                candidates += DetectedLink(
                    start = m.range.first,
                    end = m.range.first + trimmed.length,
                    value = trimmed,
                    kind = DetectedLinkKind.URL,
                )
            }
        }

        for (m in emailRegex.findAll(text)) {
            candidates += DetectedLink(
                start = m.range.first,
                end = m.range.last + 1,
                value = m.value,
                kind = DetectedLinkKind.EMAIL,
            )
        }

        for (m in phoneRegex.findAll(text)) {
            val digitsOnly = m.value.count { it.isDigit() }
            if (digitsOnly in 9..15) {
                candidates += DetectedLink(
                    start = m.range.first,
                    end = m.range.last + 1,
                    value = m.value,
                    kind = DetectedLinkKind.PHONE,
                )
            }
        }

        // Resolve overlaps deterministically: URL > EMAIL > PHONE, earlier start wins
        candidates.sortWith(
            compareBy<DetectedLink> { it.start }
                .thenBy { it.kind.ordinal }
                .thenByDescending { it.end - it.start },
        )

        val accepted = mutableListOf<DetectedLink>()
        var cursor = 0
        for (c in candidates) {
            if (c.start >= cursor) {
                accepted += c
                cursor = c.end
            }
        }
        return accepted
    }

    fun buildLinkedAnnotatedString(
        text: String,
        linkColor: Color,
        onLinkClick: (DetectedLink) -> Unit,
    ): AnnotatedString {
        val links = detectLinks(text)
        return buildAnnotatedString {
            append(text)
            val styles = TextLinkStyles(
                style = SpanStyle(
                    color = linkColor,
                    textDecoration = TextDecoration.Underline,
                ),
            )
            for (link in links) {
                addLink(
                    clickable = LinkAnnotation.Clickable(
                        tag = link.kind.name,
                        styles = styles,
                        linkInteractionListener = { onLinkClick(link) },
                    ),
                    start = link.start,
                    end = link.end,
                )
            }
        }
    }

    private fun trimTrailingUrlPunctuation(raw: String): String {
        var end = raw.length
        while (end > 0) {
            val last = raw[end - 1]
            if (last in charArrayOf('.', ',', ';', ':', '!', '?', '。', '、', '」', '』')) {
                end -= 1
            } else {
                break
            }
        }
        return raw.substring(0, end)
    }
}
