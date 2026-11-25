package com.kazemail.app.filter

import com.kazemail.app.auth.OAuth2Providers
import com.kazemail.app.data.db.LocalFilterRuleEntity
import com.kazemail.app.data.db.MessageMetaEntity
import com.kazemail.app.sync.Rfc2047HeaderDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset
import java.util.Base64

class LocalFilterAndRfc2047Test {

    private fun sampleMeta(
        id: Long = 1L,
        senderName: String? = "株式会社テスト",
        senderAddress: String = "billing@example.jp",
        recipientTo: String? = """[{"name":"自分","address":"me@example.com"}]""",
        subject: String? = "【重要】10月分ご請求書のご案内",
        snippet: String? = "お支払い期限は月末です。",
        hasAttachments: Boolean = true,
    ) = MessageMetaEntity(
        id = id,
        accountId = 1L,
        folderId = 1L,
        uid = id,
        senderName = senderName,
        senderAddress = senderAddress,
        recipientTo = recipientTo,
        subject = subject,
        snippet = snippet,
        receivedAt = 10_000L,
        hasAttachments = hasAttachments,
    )

    @Test
    fun localFilterEvaluatesAndOrOperatorsHeadersAndStopProcessing() {
        val ruleHigh = LocalFilterRuleEntity(
            ruleId = 1L,
            accountId = 1L,
            name = "請求書",
            isEnabled = true,
            priority = 1,
            matchMode = LocalFilterRuleEntity.MATCH_MODE_ALL,
            conditionsJson = LocalFilterEngine.encodeConditions(
                listOf(
                    LocalFilterCondition(FilterField.SENDER_ANY, FilterOperator.CONTAINS, "billing"),
                    LocalFilterCondition(FilterField.SUBJECT, FilterOperator.REGEX, "請求書|領収書"),
                    LocalFilterCondition(FilterField.HAS_ATTACHMENTS, FilterOperator.IS_TRUE),
                ),
            ),
            targetLocalFolder = "請求・経理",
            hideFromFolder = true,
            markFlagged = true,
            stopProcessing = true,
        )
        val ruleLowFallback = LocalFilterRuleEntity(
            ruleId = 2L,
            accountId = 1L,
            name = "アーカイブ",
            isEnabled = true,
            priority = 2,
            matchMode = LocalFilterRuleEntity.MATCH_MODE_ANY,
            conditionsJson = LocalFilterEngine.encodeConditions(
                listOf(LocalFilterCondition(FilterField.SUBJECT, FilterOperator.CONTAINS, "ご案内")),
            ),
            archiveLocally = true,
            markRead = true,
        )

        val stoppedOutcome = LocalFilterEngine.evaluate(
            rules = listOf(ruleLowFallback, ruleHigh),
            target = FilterEvaluationTarget(meta = sampleMeta()),
        )
        assertEquals(listOf(1L), stoppedOutcome.matchedRuleIds)
        assertEquals("請求・経理", stoppedOutcome.targetLocalFolder)
        assertTrue(stoppedOutcome.hideFromFolder)
        assertTrue(stoppedOutcome.markFlagged)
        assertFalse(stoppedOutcome.archiveLocally)
        assertFalse(stoppedOutcome.markRead)

        // When stopProcessing is false, subsequent matching rules combine actions cleanly
        val combinedOutcome = LocalFilterEngine.evaluate(
            rules = listOf(ruleHigh.copy(stopProcessing = false), ruleLowFallback),
            target = FilterEvaluationTarget(meta = sampleMeta()),
        )
        assertEquals(listOf(1L, 2L), combinedOutcome.matchedRuleIds)
        assertEquals("請求・経理", combinedOutcome.targetLocalFolder)
        assertTrue(combinedOutcome.archiveLocally)
        assertTrue(combinedOutcome.markRead)
        assertTrue(combinedOutcome.markFlagged)
    }

    @Test
    fun localFilterHandlesOperatorsCaseSensitivityInvalidRegexAndTemplates() {
        val newsletterTarget = FilterEvaluationTarget(
            meta = sampleMeta(
                senderName = "News",
                senderAddress = "noreply@shop.example.jp",
                subject = "Weekend Sale",
                hasAttachments = false,
            ),
            rawHeaders = "List-Unsubscribe: <https://shop.example.jp/unsub>\r\nPrecedence: bulk",
        )

        val templateRules = FilterRuleTemplates.presets.mapIndexed { idx, spec ->
            LocalFilterRuleEntity(
                ruleId = idx + 10L,
                accountId = 1L,
                name = spec.name,
                isEnabled = true,
                priority = idx + 1,
                matchMode = spec.matchMode,
                conditionsJson = LocalFilterEngine.encodeConditions(spec.conditions),
                targetLocalFolder = spec.targetLocalFolder,
                hideFromFolder = spec.hideFromFolder,
                archiveLocally = spec.archiveLocally,
                markRead = spec.markRead,
                markFlagged = spec.markFlagged,
                suppressNotification = spec.suppressNotification,
            )
        }

        val newsOutcome = LocalFilterEngine.evaluate(templateRules, newsletterTarget)
        assertEquals("メルマガ", newsOutcome.targetLocalFolder)
        assertTrue(newsOutcome.hideFromFolder)
        assertTrue(newsOutcome.markRead)
        assertTrue(newsOutcome.suppressNotification)

        val invalidRegexRule = LocalFilterRuleEntity(
            ruleId = 99L,
            accountId = 1L,
            name = "Broken Regex",
            conditionsJson = LocalFilterEngine.encodeConditions(
                listOf(LocalFilterCondition(FilterField.SUBJECT, FilterOperator.REGEX, "[unclosed(")),
            ),
            markRead = true,
        )
        val safeResult = LocalFilterEngine.evaluate(listOf(invalidRegexRule), newsletterTarget)
        assertTrue(safeResult.matchedRuleIds.isEmpty())
        assertNull(safeResult.targetLocalFolder)

        assertTrue(
            LocalFilterEngine.matchesCondition(
                LocalFilterCondition(FilterField.SUBJECT, FilterOperator.STARTS_WITH, "Weekend", caseSensitive = true),
                newsletterTarget,
            ),
        )
        assertFalse(
            LocalFilterEngine.matchesCondition(
                LocalFilterCondition(FilterField.SUBJECT, FilterOperator.STARTS_WITH, "weekend", caseSensitive = true),
                newsletterTarget,
            ),
        )
        assertTrue(
            LocalFilterEngine.matchesCondition(
                LocalFilterCondition(FilterField.SENDER_ADDRESS, FilterOperator.NOT_CONTAINS, "vip"),
                newsletterTarget,
            ),
        )
        assertFalse(
            LocalFilterEngine.matchesCondition(
                LocalFilterCondition(FilterField.SENDER_ADDRESS, FilterOperator.NOT_CONTAINS, "shop"),
                newsletterTarget,
            ),
        )
    }

    @Test
    fun rfc2047DecoderRecoversStandardUnspacedAdjacentSplitAndTruncatedJapaneseHeaders() {
        val iso2022Jp = Charset.forName("ISO-2022-JP")
        val shiftJis = Charset.forName("Shift_JIS")
        val b64 = Base64.getEncoder()

        val rawKanji = b64.encodeToString("KazeMail重要なお知らせ".toByteArray(iso2022Jp))
        val unspaced = "Re:=?ISO-2022-JP?B?$rawKanji?=(至急)"
        assertEquals("Re:KazeMail重要なお知らせ(至急)", Rfc2047HeaderDecoder.decodeLenient(unspaced))

        // Split raw ISO-2022-JP bytes across two adjacent encoded words (escape sequence in first chunk only)
        val fullBytes = "会議資料のご確認をお願いいたします".toByteArray(iso2022Jp)
        val mid = (fullBytes.size / 2)
        val part1 = b64.encodeToString(fullBytes.copyOfRange(0, mid))
        val part2 = b64.encodeToString(fullBytes.copyOfRange(mid, fullBytes.size))
        val adjacentSplit = "=?ISO-2022-JP?B?$part1?= =?ISO-2022-JP?B?$part2?="
        assertEquals("会議資料のご確認をお願いいたします", Rfc2047HeaderDecoder.decodeLenient(adjacentSplit))

        // Truncated encoded word without closing '?='
        val truncToken = "=?ISO-2022-JP?B?${b64.encodeToString("日本語テスト".toByteArray(iso2022Jp))}"
        assertEquals("日本語テスト", Rfc2047HeaderDecoder.decodeLenient(truncToken))

        // Quoted-Printable underscores + Shift_JIS
        assertEquals("Hello Kaze!", Rfc2047HeaderDecoder.decodeLenient("=?UTF-8?Q?Hello_Kaze=21?="))
        val sjisEncoded = "=?Shift_JIS?B?${b64.encodeToString("請求書".toByteArray(shiftJis))}?="
        assertEquals("請求書", Rfc2047HeaderDecoder.decodeLenient(sjisEncoded))
        assertEquals("通常の件名", Rfc2047HeaderDecoder.decodeIfNeeded("通常の件名"))
    }

    @Test
    fun oauth2ProvidersExposeSafeProviderSpecificEndpointsAndScopes() {
        val gmail = OAuth2Providers.Gmail
        assertTrue(gmail.sendOfflineAccessTypeParam)
        assertTrue(gmail.scope.contains("openid"))
        assertTrue(gmail.scope.contains("https://mail.google.com/"))

        val outlookWork = OAuth2Providers.Outlook
        val outlookPersonal = OAuth2Providers.OutlookConsumers
        assertFalse(outlookWork.sendOfflineAccessTypeParam)
        assertFalse(outlookPersonal.sendOfflineAccessTypeParam)
        assertTrue(outlookWork.authorizationEndpoint.contains("/common/"))
        assertTrue(outlookPersonal.authorizationEndpoint.contains("/consumers/"))
        assertTrue(outlookWork.scope.contains("IMAP.AccessAsUser.All"))
        assertTrue(outlookPersonal.scope.contains("offline_access"))

        // Client ID sanitization & pre-flight validation
        assertEquals(
            "123456789012-abc.apps.googleusercontent.com",
            OAuth2Providers.sanitizeClientId(" client_id=\"123456789012-abc.apps.googleusercontent.com\"\n "),
        )
        assertNull(
            OAuth2Providers.validateClientId(
                OAuth2Providers.Gmail,
                "123456789012-abc123.apps.googleusercontent.com",
            ),
        )
        assertTrue(
            OAuth2Providers.validateClientId(OAuth2Providers.Gmail, "user@gmail.com")!!
                .contains("メールアドレス"),
        )
        assertTrue(
            OAuth2Providers.validateClientId(OAuth2Providers.Gmail, "GOCSPX-secret123")!!
                .contains("Client Secret"),
        )
        assertTrue(
            OAuth2Providers.validateClientId(OAuth2Providers.Gmail, "123456789012")!!
                .contains(".apps.googleusercontent.com"),
        )
        assertNull(
            OAuth2Providers.validateClientId(
                OAuth2Providers.OutlookConsumers,
                "11111111-2222-3333-4444-555555555555",
            ),
        )
        assertTrue(
            OAuth2Providers.validateClientId(
                OAuth2Providers.Outlook,
                "123456789012-abc.apps.googleusercontent.com",
            )!!.contains("Google 用"),
        )

        // Built-in client ID resolution & override behavior
        val unconfigured = OAuth2Providers.Gmail.copy(defaultClientId = "")
        val configured = OAuth2Providers.Gmail.copy(defaultClientId = "999999-builtin.apps.googleusercontent.com")
        assertFalse(OAuth2Providers.hasBuiltInClientId(unconfigured))
        assertTrue(OAuth2Providers.hasBuiltInClientId(configured))
        assertEquals(
            "999999-builtin.apps.googleusercontent.com",
            OAuth2Providers.resolveClientId(configured, manualOverride = "111-manual.apps.googleusercontent.com", useManualOverride = false),
        )
        assertEquals(
            "111-manual.apps.googleusercontent.com",
            OAuth2Providers.resolveClientId(configured, manualOverride = "111-manual.apps.googleusercontent.com", useManualOverride = true),
        )
        assertEquals(
            "111-manual.apps.googleusercontent.com",
            OAuth2Providers.resolveClientId(unconfigured, manualOverride = "111-manual.apps.googleusercontent.com", useManualOverride = false),
        )
        assertEquals("Google でログイン", OAuth2Providers.signInButtonLabel(OAuth2Providers.Gmail))
        assertEquals("Microsoft 365 / Outlook でログイン", OAuth2Providers.signInButtonLabel(OAuth2Providers.Outlook))
        assertEquals("com.kazemail.app:/oauth2redirect", OAuth2Providers.Gmail.redirectUri)
        assertEquals("com.kazemail.app://oauth2redirect", OAuth2Providers.Outlook.redirectUri)
        assertEquals("com.kazemail.app://oauth2redirect", OAuth2Providers.OutlookConsumers.redirectUri)
    }
}
