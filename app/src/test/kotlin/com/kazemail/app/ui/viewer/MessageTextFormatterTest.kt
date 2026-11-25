package com.kazemail.app.ui.viewer

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageTextFormatterTest {

    @Test
    fun splitsQuotedReplyBlocksAndPreservesPrimaryContent() {
        val body = """
            お疲れ様です。明日の資料を確認しました。
            問題ありませんので進めてください。

            2026/11/19 10:00 田中 <tanaka@example.jp>さんのメール:
            > 先日お送りした資料をご確認ください。
            > 修正点があればお知らせください。
            > よろしくお願いいたします。
        """.trimIndent()

        val segments = MessageTextFormatter.splitQuotedContent(body)
        assertEquals(2, segments.size)
        assertTrue(segments[0] is BodySegment.Content)
        val content = (segments[0] as BodySegment.Content).text
        assertTrue(content.contains("問題ありませんので進めてください。"))

        assertTrue(segments[1] is BodySegment.Quote)
        val quote = segments[1] as BodySegment.Quote
        assertEquals(4, quote.lineCount)
        assertTrue(quote.text.contains("先日お送りした資料をご確認ください。"))
    }

    @Test
    fun splitsForwardedDividerCleanly() {
        val body = """
            確認お願いします。
            -----Original Message-----
            From: alice@example.com
            Subject: Contract
        """.trimIndent()

        val segments = MessageTextFormatter.splitQuotedContent(body)
        assertEquals(2, segments.size)
        assertEquals("確認お願いします。", (segments[0] as BodySegment.Content).text)
        assertTrue((segments[1] as BodySegment.Quote).text.startsWith("-----Original Message-----"))
    }

    @Test
    fun autoLinksUrlsEmailsAndJapanesePhoneNumbersWithoutLinkingDates() {
        val sample = "会議日時: 2026-11-20 詳細: https://example.jp/docs/123。" +
            " 連絡先: support@kazemail.app または 03-1234-5678 / +81-90-1111-2222 まで。"
        val links = MessageTextFormatter.detectLinks(sample)

        assertEquals(4, links.size)
        val expectedUrl = "https://example.jp/docs/123"
        val urlStart = sample.indexOf(expectedUrl)
        assertEquals(
            DetectedLink(
                start = urlStart,
                end = urlStart + expectedUrl.length,
                value = expectedUrl,
                kind = DetectedLinkKind.URL,
            ),
            links[0],
        )
        assertEquals("support@kazemail.app", links[1].value)
        assertEquals(DetectedLinkKind.EMAIL, links[1].kind)
        assertEquals("03-1234-5678", links[2].value)
        assertEquals(DetectedLinkKind.PHONE, links[2].kind)
        assertEquals("+81-90-1111-2222", links[3].value)
        assertEquals(DetectedLinkKind.PHONE, links[3].kind)

        val annotated = MessageTextFormatter.buildLinkedAnnotatedString(
            text = sample,
            linkColor = Color.Blue,
            onLinkClick = {},
        )
        assertEquals(sample, annotated.text)
    }

    @Test
    fun htmlEmailDetectorDistinguishesPlainTextFromActualHtml() {
        assertEquals(
            false,
            HtmlEmailDetector.containsActualHtml("こんにちは <taro@example.jp>\n1 < 2 > 0\n通常テキストです"),
        )
        assertEquals(
            false,
            HtmlEmailDetector.containsActualHtml("ログ確認: <trace> <header_id> <old> <branch>"),
        )
        assertEquals(
            false,
            HtmlEmailDetector.looksLikeRawHtmlDocument("議事録: A < B かつ <info@example.jp> へ返信"),
        )
        assertEquals(
            false,
            HtmlEmailDetector.looksLikeRawHtmlDocument("HTMLの<html>や<head>や<body>、それに<div>と<p>、`<div>a</div>`と`<span>b</span>`について"),
        )
        assertEquals(
            false,
            HtmlEmailDetector.looksLikeRawHtmlDocument("条件: if a <b and c > d となる場合"),
        )
        assertEquals(
            false,
            HtmlEmailDetector.looksLikeRawHtmlDocument("```html\n<div class=\"x\"><p>sample</p></div>\n```"),
        )
        assertEquals(
            true,
            HtmlEmailDetector.containsActualHtml("<div><p>お知らせ</p></div>"),
        )
        assertEquals(
            true,
            HtmlEmailDetector.containsActualHtml("<h3>完了</h3><b>Build OK</b><hr>"),
        )
        assertEquals(
            true,
            HtmlEmailDetector.looksLikeRawHtmlDocument("<!DOCTYPE html><html><body><p>合計: 1000円</p></body></html>"),
        )
        assertEquals(
            true,
            HtmlEmailDetector.looksLikeRawHtmlDocument("<div style=\"color:red\">重要なお知らせ</div>"),
        )
        assertEquals(
            true,
            HtmlEmailDetector.looksLikeRawHtmlDocument("1行目<br>2行目<br>3行目"),
        )
        val bigStyleHtml = "<style>${"a{color:red}".repeat(1000)}</style><div>本文</div>"
        assertEquals(
            true,
            HtmlEmailDetector.looksLikeRawHtmlDocument(bigStyleHtml),
        )
        val stripped = HtmlEmailDetector.stripHtmlFallbackText(
            "<!-- [if mso]><xml>ignore</xml><![endif]--><head><title>Hidden</title></head>" +
                "<style>.x{color:red}</style><table><tr><td>項目</td><td>1,200&#x5186;</td></tr></table>" +
                "<p>確認&amp;承認 &#39;OK&#39;</p>",
        )
        assertTrue(stripped.contains("項目 1,200円"))
        assertTrue(stripped.contains("確認&承認 'OK'"))
        assertEquals(false, stripped.contains("<style>"))
        assertEquals(false, stripped.contains("Hidden"))
        assertEquals(false, stripped.contains("ignore"))
    }

    @Test
    fun wrappedHtmlEnablesPinchZoomStripsLockingViewportAndUsesSlimMargins() {
        val raw = """
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
            <meta name="color-scheme" content="light dark">
            <p>重要なお知らせ</p>
        """.trimIndent()
        val wrapped = prepareWrappedHtml(raw, useDarkInvert = false)
        assertEquals(false, wrapped.contains("user-scalable=no"))
        assertEquals(false, wrapped.contains("maximum-scale=1.0"))
        assertEquals(false, wrapped.contains("light dark"))
        assertTrue(wrapped.contains("user-scalable=yes"))
        assertTrue(wrapped.contains("maximum-scale=5.0"))
        assertTrue(wrapped.contains("color-scheme: light only;"))
        assertTrue(wrapped.contains("color: #111111;"))
        assertTrue(wrapped.contains("background-color: #FFFFFF;"))
        assertTrue(wrapped.contains("margin: 2px 3px;"))
        assertTrue(wrapped.contains("<p>重要なお知らせ</p>"))

        val wrappedDark = prepareWrappedHtml(raw, useDarkInvert = true)
        assertTrue(wrappedDark.contains("filter: invert(0.92) hue-rotate(180deg);"))
        assertTrue(wrappedDark.contains("img, picture, video, svg"))

        val wrappedFit = prepareWrappedHtml(raw, useDarkInvert = false, fitScreenWidth = true)
        assertTrue(wrappedFit.contains("overflow-x: hidden;"))
        assertTrue(wrappedFit.contains("box-sizing: border-box; max-width: 100%;"))
    }

    @Test
    fun parsesFoldedQuotedAndRfc2047CcHeadersCleanly() {
        val rawHeaders = """
            From: Alice <alice@example.jp>
            To: Bob <bob@example.jp>
            Cc: "Tanaka, Taro" <taro@example.jp>,
             =?UTF-8?B?5L2Q6JekIOiKseWtkA==?= <hanako@example.jp>,
             dev-team@example.jp
            Subject: Weekly sync
        """.trimIndent()

        val parsed = MessageDetailViewModel.parseCcFromRawHeaders(rawHeaders)
        assertEquals(3, parsed.size)
        assertEquals(RecipientDisplay(name = "Tanaka, Taro", address = "taro@example.jp"), parsed[0])
        assertEquals(RecipientDisplay(name = "佐藤 花子", address = "hanako@example.jp"), parsed[1])
        assertEquals(RecipientDisplay(name = null, address = "dev-team@example.jp"), parsed[2])
    }

    @Test
    fun resolvesPdfMimeAliasesMagicBytesSafeFilenamesAndCorruptBase64Cache() {
        val pdfBytes = "%PDF-1.7\n...".toByteArray(Charsets.US_ASCII)
        assertEquals(
            "application/pdf",
            com.kazemail.app.data.AttachmentMetadataHelper.normalizeAndResolveMimeType(
                fileName = "payload.bin",
                rawMimeType = "application/octet-stream",
                headerSample = pdfBytes,
            ),
        )
        assertEquals(
            "application/pdf",
            com.kazemail.app.data.AttachmentMetadataHelper.normalizeAndResolveMimeType(
                fileName = "invoice.pdf",
                rawMimeType = "APPLICATION/X-PDF; name=\"invoice.pdf\"",
                headerSample = null,
            ),
        )
        assertEquals(
            "請求書2026.11.pdf",
            com.kazemail.app.data.AttachmentMetadataHelper.buildSafeAttachmentFileName(
                rawFilename = "請求書2026.11",
                rawMimeType = "application/pdf",
                payloadPrefix = pdfBytes,
            ),
        )
        val tmpCorrupt = kotlin.io.path.createTempFile("kaze_corrupt_", ".pdf").toFile()
        val tmpValid = kotlin.io.path.createTempFile("kaze_valid_", ".pdf").toFile()
        try {
            tmpCorrupt.writeText("JVBERi0xLjQK...")
            tmpValid.writeBytes(pdfBytes)
            assertTrue(
                com.kazemail.app.data.AttachmentMetadataHelper.isCachedAttachmentInvalid(
                    tmpCorrupt,
                    "invoice.pdf",
                    "application/pdf",
                ),
            )
            assertEquals(
                false,
                com.kazemail.app.data.AttachmentMetadataHelper.isCachedAttachmentInvalid(
                    tmpValid,
                    "invoice.pdf",
                    "application/pdf",
                ),
            )
        } finally {
            tmpCorrupt.delete()
            tmpValid.delete()
        }
    }
}
