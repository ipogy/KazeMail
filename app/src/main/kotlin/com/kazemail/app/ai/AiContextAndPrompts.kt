package com.kazemail.app.ai

import com.kazemail.app.data.LoadedMessageDetail
import com.kazemail.app.sync.Rfc2047HeaderDecoder
import com.kazemail.app.ui.viewer.BodySegment
import com.kazemail.app.ui.viewer.HtmlEmailDetector
import com.kazemail.app.ui.viewer.MessageTextFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RedactionResult(
    val text: String,
    val redactionCount: Int,
)

/**
 * Redacts high-risk secrets (API keys, bearer tokens, card numbers, sensitive URL query params,
 * and OTP/password snippets) before sending email text to external LLM APIs.
 */
object AiSensitiveDataRedactor {
    private val apiKeyOrBearerRegex = Regex(
        """\b(?:Bearer\s+[A-Za-z0-9._\-]{16,}|sk-(?:proj-|ant-)?[A-Za-z0-9_\-]{16,}|AIza[0-9A-Za-z_\-]{20,}|gh[pousr]_[A-Za-z0-9]{20,})\b""",
    )
    private val sensitiveUrlParamRegex = Regex(
        """([?&](?:token|auth|key|api_key|apikey|secret|password|passwd|sig|signature|access_token)=)([^&\s<>"']+)""",
        RegexOption.IGNORE_CASE,
    )
    private val cardNumberCandidateRegex = Regex("""(?<!\d)(?:\d[ \-]?){13,19}(?!\d)""")
    private val otpOrPasswordLineRegex = Regex(
        """((?:パスワード|仮パスワード|パスコード|認証コード|確認コード|ワンタイムパスワード|OTP|PIN|password|passcode|verification code)\s*[:：=]\s*)([^\s\n]{4,32})""",
        RegexOption.IGNORE_CASE,
    )

    fun redact(rawText: String): RedactionResult {
        if (rawText.isEmpty()) return RedactionResult(rawText, 0)
        var count = 0

        var text = apiKeyOrBearerRegex.replace(rawText) {
            count += 1
            "[REDACTED_TOKEN]"
        }
        text = sensitiveUrlParamRegex.replace(text) { match ->
            count += 1
            match.groupValues[1] + "[REDACTED]"
        }
        text = otpOrPasswordLineRegex.replace(text) { match ->
            count += 1
            match.groupValues[1] + "[REDACTED_SECRET]"
        }
        text = cardNumberCandidateRegex.replace(text) { match ->
            val digits = match.value.count { it.isDigit() }
            if (digits in 13..19 && (match.value.contains('-') || match.value.contains(' ') || digits >= 14)) {
                count += 1
                "[REDACTED_NUMBER]"
            } else {
                match.value
            }
        }
        return RedactionResult(text = text, redactionCount = count)
    }
}

/**
 * Extracts, strips redundant quotes, and truncates conversation context within token budgets.
 * Never transmits attachment binary payloads — only attachment filenames/MIME metadata.
 */
class AiEmailContextBuilder(
    private val decompressHtml: suspend (ByteArray) -> String? = { null },
) {
    suspend fun buildThreadContext(
        conversation: List<LoadedMessageDetail>,
        selfEmail: String,
        stripQuotedReplies: Boolean = true,
        redactSensitive: Boolean = true,
        maxChars: Int = 6000,
        maxMessages: Int = 5,
    ): AiPreparedEmailContext {
        if (conversation.isEmpty()) {
            return AiPreparedEmailContext(
                threadSubject = "",
                selfEmail = selfEmail,
                replyToAddress = null,
                includedMessageCount = 0,
                totalMessageCount = 0,
                strippedQuoteBlocks = 0,
                redactionCount = 0,
                wasTruncated = false,
                formattedContext = "",
            )
        }

        val sorted = conversation.sortedBy { it.meta.receivedAt }
        val latest = sorted.last()
        val recentSlice = if (sorted.size > maxMessages) {
            sorted.takeLast(maxMessages)
        } else {
            sorted
        }
        var wasTruncated = sorted.size > recentSlice.size
        var totalStrippedQuotes = 0
        var totalRedactions = 0

        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

        val formattedBlocks = mutableListOf<String>()
        val targetChars = maxChars.coerceAtLeast(1000)

        // Format newest-first inside budget so critical latest mail is always kept, then reverse back chronologically.
        var accumulatedChars = 0
        val reversedSlice = recentSlice.asReversed()
        for ((revIdx, detail) in reversedSlice.withIndex()) {
            val meta = detail.meta
            val senderName = meta.senderName?.let { Rfc2047HeaderDecoder.decodeIfNeeded(it) }
            val senderLabel = senderName?.takeIf { it.isNotBlank() }?.let { "$it <${meta.senderAddress}>" }
                ?: meta.senderAddress
            val subject = meta.subject?.let { Rfc2047HeaderDecoder.decodeIfNeeded(it) } ?: "(件名なし)"
            val dateLabel = if (meta.receivedAt > 0L) df.format(Date(meta.receivedAt)) else "--"
            val chronologicalTag = if (revIdx == 0) "最新メール" else "直前のやり取り"

            var bodyText = resolvePlainBody(detail)
            if (stripQuotedReplies && bodyText.isNotBlank()) {
                val segments = MessageTextFormatter.splitQuotedContent(bodyText)
                val quoteBlocks = segments.count { it is BodySegment.Quote }
                val primaryContent = segments.filterIsInstance<BodySegment.Content>()
                    .joinToString("\n\n") { it.text }
                    .trim()
                if (primaryContent.isNotEmpty()) {
                    totalStrippedQuotes += quoteBlocks
                    bodyText = primaryContent
                }
            }

            if (redactSensitive && bodyText.isNotBlank()) {
                val redacted = AiSensitiveDataRedactor.redact(bodyText)
                totalRedactions += redacted.redactionCount
                bodyText = redacted.text
            }

            val cleanSubject = if (redactSensitive) {
                val r = AiSensitiveDataRedactor.redact(subject)
                totalRedactions += r.redactionCount
                r.text
            } else {
                subject
            }

            val attachmentSummary = if (detail.attachments.isNotEmpty()) {
                detail.attachments.joinToString(", ") { "${it.filename} (${it.mimeType})" }
            } else {
                null
            }

            val blockHeader = buildString {
                append("--- メール (").append(chronologicalTag).append(" / ").append(dateLabel).append(") ---\n")
                append("差出人: ").append(senderLabel).append('\n')
                append("件名: ").append(cleanSubject).append('\n')
                if (attachmentSummary != null) {
                    append("添付メタデータ: ").append(attachmentSummary).append('\n')
                }
                append('\n')
            }

            val remaining = targetChars - accumulatedChars
            if (formattedBlocks.isNotEmpty() && remaining <= blockHeader.length + 120) {
                wasTruncated = true
                break
            }

            val allowedBodyLen = (remaining - blockHeader.length - 40).coerceAtLeast(240)
            val finalBody = if (bodyText.length > allowedBodyLen) {
                wasTruncated = true
                bodyText.take(allowedBodyLen).trimEnd() + "\n…(省略)"
            } else {
                bodyText
            }

            val block = blockHeader + finalBody
            formattedBlocks += block
            accumulatedChars += block.length + 2
        }

        val chronological = formattedBlocks.asReversed().joinToString("\n\n")
        val threadSubject = latest.meta.subject?.let { Rfc2047HeaderDecoder.decodeIfNeeded(it) } ?: "(件名なし)"
        val replyTo = latest.meta.senderAddress.takeIf { it.isNotBlank() }

        return AiPreparedEmailContext(
            threadSubject = threadSubject,
            selfEmail = selfEmail,
            replyToAddress = replyTo,
            includedMessageCount = formattedBlocks.size,
            totalMessageCount = sorted.size,
            strippedQuoteBlocks = totalStrippedQuotes,
            redactionCount = totalRedactions,
            wasTruncated = wasTruncated,
            formattedContext = chronological,
        )
    }

    private suspend fun resolvePlainBody(detail: LoadedMessageDetail): String {
        val rawPlain = detail.body?.plainText?.trim().orEmpty()
        if (rawPlain.isNotEmpty()) {
            return if (HtmlEmailDetector.looksLikeRawHtmlDocument(rawPlain)) {
                HtmlEmailDetector.stripHtmlFallbackText(rawPlain)
            } else {
                rawPlain
            }
        }
        val zstdBytes = detail.body?.htmlZstd
        if (zstdBytes != null) {
            val html = runCatching { decompressHtml(zstdBytes) }.getOrNull().orEmpty()
            if (html.isNotBlank()) {
                return HtmlEmailDetector.stripHtmlFallbackText(html)
            }
        }
        return detail.meta.snippet?.trim().orEmpty()
    }
}

object AiPromptBuilder {

    fun buildDraftRequest(
        goal: AiWritingGoal,
        tone: AiReplyTone,
        context: AiPreparedEmailContext?,
        userInstruction: String,
        currentSubject: String,
        currentBodyDraft: String,
        customSystemInstruction: String,
        includeSignatureGuidance: Boolean,
        extraReferenceEmails: List<AiPreparedEmailContext> = emptyList(),
        extraTextDocuments: List<AiPreparedTextDocument> = emptyList(),
        binaryAttachmentDescriptors: List<String> = emptyList(),
        clarificationAnswers: List<AiClarificationAnswer> = emptyList(),
        preferHtmlOutput: Boolean = false,
    ): AiCompletionRequest {
        val answeredClarifications = clarificationAnswers.filter { it.hasAnswer }
        val systemPrompt = buildString {
            var ruleIdx = 1
            appendLine("あなたはメールクライアント KazeMail に組み込まれた安全で正確な日本語メール作成アシスタントです。")
            appendLine("【厳守ルール】")
            appendLine("${ruleIdx++}. 元のメール・追加参照資料・添付画像/PDF・ユーザー指示に書かれていない事実・金額・日程・URL・添付ファイルの存在を創作しないこと。")
            appendLine("${ruleIdx++}. 未確定の情報や確認が必要な箇所がある場合は、必要に応じて `[要確認: ...]` のプレースホルダで示すこと。")
            appendLine("${ruleIdx++}. 指示された文体（${tone.displayLabel}: ${tone.promptDirective}）を一貫して保つこと。")
            if (includeSignatureGuidance) {
                appendLine("${ruleIdx++}. 署名ブロック（-- や連絡先フッター）はアプリ側で自動挿入するため、ユーザー指示にない署名ブロックは付けないこと。")
            }
            if (answeredClarifications.isNotEmpty()) {
                appendLine("${ruleIdx++}. ユーザーが選択・自由入力した【AIからの逆質問への回答】の内容を漏らさず最優先で本文に反映すること。")
            }
            if (preferHtmlOutput) {
                appendLine("${ruleIdx++}. 本文は送信前の HTML メール用本文フラグメント（<p>, <br>, <b>, <i>, <u>, <h3>, <ul>, <li>, <table>, <a href=\"...\"> 等）のみを出力し、<!DOCTYPE> や <html>・<head>・<body> タグ、前置き、Markdown のコードフェンス（```）は付けないこと。")
            } else {
                appendLine("${ruleIdx++}. 本文はプレーンテキスト形式のみで出力し、HTMLタグ（<p>, <br>, <b> 等）や前置き（「以下が下書きです」等）・Markdown のコードフェンスは付けないこと。")
            }
            if (customSystemInstruction.isNotBlank()) {
                appendLine()
                appendLine("【ユーザー共通の執筆方針】")
                appendLine(customSystemInstruction.trim())
            }
        }.trim()

        val userPrompt = buildString {
            appendLine("タスク: ${goal.displayLabel}")
            appendLine("文体・トーン: ${tone.displayLabel}")
            if (!context?.selfEmail.isNullOrBlank()) {
                appendLine("送信者（自分）: ${context.selfEmail}")
            }
            if (currentSubject.isNotBlank()) {
                appendLine("現在の件名: ${currentSubject.trim()}")
            }

            if (userInstruction.isNotBlank()) {
                appendLine()
                appendLine("【ユーザーからの返信・作成指示】")
                appendLine(userInstruction.trim())
            }

            if (answeredClarifications.isNotEmpty()) {
                appendLine()
                appendLine("【AIからの逆質問への回答】")
                answeredClarifications.forEachIndexed { idx, qa ->
                    appendLine("${idx + 1}. Q: ${qa.questionText}")
                    val parts = buildList {
                        if (qa.selectedOptions.isNotEmpty()) {
                            add("選択: ${qa.selectedOptions.joinToString(" / ")}")
                        }
                        if (qa.customInput.isNotBlank()) {
                            add("補足・自由入力: ${qa.customInput.trim()}")
                        }
                    }
                    appendLine("   A: ${parts.joinToString(" | ")}")
                }
            }

            if ((goal == AiWritingGoal.REWRITE_TONE || goal == AiWritingGoal.NEW_DRAFT || goal == AiWritingGoal.REPLY || goal == AiWritingGoal.REPLY_ALL) &&
                currentBodyDraft.isNotBlank() &&
                (goal == AiWritingGoal.REWRITE_TONE || answeredClarifications.isNotEmpty())
            ) {
                appendLine()
                appendLine("【現在作成中の本文・下書き】")
                appendLine(currentBodyDraft.trim())
            }

            if (context != null && context.formattedContext.isNotBlank()) {
                appendLine()
                appendLine("【参照スレッド文脈（${context.includedMessageCount}/${context.totalMessageCount}件）】")
                appendLine(context.formattedContext)
            }

            appendExtraReferences(
                extraReferenceEmails = extraReferenceEmails,
                extraTextDocuments = extraTextDocuments,
                binaryAttachmentDescriptors = binaryAttachmentDescriptors,
            )
        }.trim()

        return AiCompletionRequest(
            goal = goal,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            temperature = when (goal) {
                AiWritingGoal.REWRITE_TONE -> 0.2
                else -> 0.3
            },
            maxOutputTokens = 2048,
        )
    }

    fun buildClarificationQuestionsRequest(
        targetGoal: AiWritingGoal,
        tone: AiReplyTone,
        context: AiPreparedEmailContext?,
        userInstruction: String,
        currentSubject: String,
        currentBodyDraft: String,
        customSystemInstruction: String,
        extraReferenceEmails: List<AiPreparedEmailContext> = emptyList(),
        extraTextDocuments: List<AiPreparedTextDocument> = emptyList(),
        binaryAttachmentDescriptors: List<String> = emptyList(),
        previousAnswers: List<AiClarificationAnswer> = emptyList(),
    ): AiCompletionRequest {
        val systemPrompt = buildString {
            appendLine("あなたはメール作成前に不足情報や判断ポイントを確認する日本語メールアシスタントです。")
            appendLine("【出力形式（厳守）】")
            appendLine("必ず次の JSON オブジェクトのみを出力し、前後の解説文や Markdown コードブロックは付けないこと。")
            appendLine("""{"summary":"確認ポイントの短い一言","questions":[{"id":"q1","question":"質問文","allowMultiple":false,"options":["選択肢1","選択肢2","選択肢3"],"customPlaceholder":"自由入力の例…"}]}""")
            appendLine("【ルール】")
            appendLine("1. 元のメール・添付資料・指示文の内容から、返信や新規作成にあたりユーザーに確認すべき重要な意思決定・不足情報・詳細を 2〜4 問作成すること。")
            appendLine("2. 各質問には、ワンタップで選びやすい具体的な日本語の options を 2〜5 個用意すること（カスタム自由入力枠はアプリ側で常設するため、選択肢に「その他」だけの項目は原則入れないこと）。")
            appendLine("3. 複数選択できる質問のみ allowMultiple: true、単一選択は false にすること。")
            appendLine("4. まだ決まっていない前提や事実を勝手に仮定せず、この質問で確認すること。")
            if (customSystemInstruction.isNotBlank()) {
                appendLine()
                appendLine("【ユーザー共通の執筆方針】")
                appendLine(customSystemInstruction.trim())
            }
        }.trim()

        val answered = previousAnswers.filter { it.hasAnswer }
        val userPrompt = buildString {
            appendLine("想定タスク: ${targetGoal.displayLabel}")
            appendLine("想定トーン: ${tone.displayLabel}")
            if (!context?.selfEmail.isNullOrBlank()) {
                appendLine("送信者（自分）: ${context.selfEmail}")
            }
            if (currentSubject.isNotBlank()) {
                appendLine("現在の件名: ${currentSubject.trim()}")
            }
            if (userInstruction.isNotBlank()) {
                appendLine()
                appendLine("【ユーザーのメモ・指示】")
                appendLine(userInstruction.trim())
            }
            if (currentBodyDraft.isNotBlank()) {
                appendLine()
                appendLine("【入力済みの本文】")
                appendLine(currentBodyDraft.trim())
            }
            if (answered.isNotEmpty()) {
                appendLine()
                appendLine("【すでに回答済みの内容（重複質問は避け、追加確認があれば質問）】")
                answered.forEachIndexed { idx, qa ->
                    val ansText = listOfNotNull(
                        qa.selectedOptions.takeIf { it.isNotEmpty() }?.joinToString(", "),
                        qa.customInput.takeIf { it.isNotBlank() },
                    ).joinToString(" / ")
                    appendLine("${idx + 1}. ${qa.questionText} -> $ansText")
                }
            }
            if (context != null && context.formattedContext.isNotBlank()) {
                appendLine()
                appendLine("【参照スレッド文脈（${context.includedMessageCount}/${context.totalMessageCount}件）】")
                appendLine(context.formattedContext)
            }
            appendExtraReferences(
                extraReferenceEmails = extraReferenceEmails,
                extraTextDocuments = extraTextDocuments,
                binaryAttachmentDescriptors = binaryAttachmentDescriptors,
            )
        }.trim()

        return AiCompletionRequest(
            goal = AiWritingGoal.CLARIFY_QUESTIONS,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            temperature = 0.25,
            maxOutputTokens = 1600,
        )
    }

    fun buildSubjectFromBodyRequest(
        bodyDraft: String,
        currentSubject: String,
        tone: AiReplyTone,
        isReplyMode: Boolean,
        context: AiPreparedEmailContext?,
        customSystemInstruction: String,
        extraReferenceEmails: List<AiPreparedEmailContext> = emptyList(),
        extraTextDocuments: List<AiPreparedTextDocument> = emptyList(),
        binaryAttachmentDescriptors: List<String> = emptyList(),
        subjectCustomInstruction: String = "",
    ): AiCompletionRequest {
        val systemPrompt = buildString {
            appendLine("あなたはメールの本文から簡潔で分かりやすい件名案を提案するアシスタントです。")
            appendLine("【出力形式（厳守）】")
            appendLine("必ず次の JSON オブジェクトのみを出力し、前後の解説や Markdown コードブロックは付けないこと。")
            appendLine("""{"subjects":["最有力件名案","代替案1","代替案2"]}""")
            appendLine("【ルール】")
            appendLine("1. 本文・資料に書かれた用件、日付、依頼事項がひと目で分かる日本語の件名を 3 件提案すること。")
            appendLine("2. 各件名は簡潔（おおむね 15〜38 文字程度）にし、「件名:」などの接頭辞は付けないこと。")
            appendLine("3. 文体トーン: ${tone.displayLabel}")
            if (isReplyMode) {
                appendLine("4. 返信メールとして自然なら既存件名や Re: の文脈に馴染む件名にすること。")
            }
            if (customSystemInstruction.isNotBlank()) {
                appendLine()
                appendLine("【ユーザー共通の指示】")
                appendLine(customSystemInstruction.trim())
            }
            if (subjectCustomInstruction.isNotBlank()) {
                appendLine()
                appendLine("【件名作成専用の指示】")
                appendLine(subjectCustomInstruction.trim())
            }
        }.trim()

        val userPrompt = buildString {
            appendLine("タスク: 本文からメール件名を3件作成")
            if (currentSubject.isNotBlank()) {
                appendLine("現在の件名（参考）: ${currentSubject.trim()}")
            }
            if (bodyDraft.isNotBlank()) {
                appendLine()
                appendLine("【作成対象の本文】")
                appendLine(bodyDraft.trim())
            }
            if (context != null && context.formattedContext.isNotBlank()) {
                appendLine()
                appendLine("【返信元スレッド参考（${context.includedMessageCount}/${context.totalMessageCount}件）】")
                appendLine(context.formattedContext)
            }
            appendExtraReferences(
                extraReferenceEmails = extraReferenceEmails,
                extraTextDocuments = extraTextDocuments,
                binaryAttachmentDescriptors = binaryAttachmentDescriptors,
            )
        }.trim()

        return AiCompletionRequest(
            goal = AiWritingGoal.GENERATE_SUBJECT,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            temperature = 0.3,
            maxOutputTokens = 2048,
        )
    }

    fun parseClarificationPlanResponse(
        rawText: String,
        resultMeta: AiCompletionResult? = null,
    ): AiClarificationPlan {
        val cleaned = stripMarkdownCodeFences(rawText)
        val jsonSlice = extractOutermostJsonObject(cleaned)
        if (jsonSlice != null) {
            val parsedObj = runCatching {
                lenientJson.parseToJsonElement(jsonSlice) as? JsonObject
            }.getOrNull()
            if (parsedObj != null) {
                val summary = parsedObj["summary"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val rawQs = (parsedObj["questions"] as? JsonArray).orEmpty()
                val questions = rawQs.mapIndexedNotNull { idx, elem ->
                    val obj = elem as? JsonObject ?: return@mapIndexedNotNull null
                    val qText = obj["question"]?.jsonPrimitive?.contentOrNull?.trim()
                        ?.takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
                    val id = obj["id"]?.jsonPrimitive?.contentOrNull?.trim()
                        ?.takeIf { it.isNotEmpty() } ?: "q${idx + 1}"
                    val allowMulti = obj["allowMultiple"]?.jsonPrimitive?.booleanOrNull ?: false
                    val options = (obj["options"] as? JsonArray)
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
                        ?.distinct()
                        .orEmpty()
                    val placeholder = obj["customPlaceholder"]?.jsonPrimitive?.contentOrNull?.trim()
                        ?.takeIf { it.isNotEmpty() } ?: "自由入力・補足があれば記入…"
                    AiClarificationQuestion(
                        id = id,
                        question = qText,
                        allowMultiple = allowMulti,
                        options = options,
                        customPlaceholder = placeholder,
                    )
                }
                if (questions.isNotEmpty()) {
                    return AiClarificationPlan(
                        summary = summary.ifBlank { "内容をより正確にするため、以下の点を確認してください。" },
                        questions = questions,
                        providerType = resultMeta?.providerType,
                        modelId = resultMeta?.modelId,
                        latencyMs = resultMeta?.latencyMs ?: 0L,
                    )
                }
            }
        }

        // Fallback if a model returned plain numbered bullets instead of JSON
        val bulletRegex = Regex("""^\s*(?:\d+[.)．]|[-*・])\s*(.+)$""")
        val fallbackQuestions = cleaned.lines()
            .mapNotNull { line -> bulletRegex.find(line)?.groupValues?.getOrNull(1)?.trim() }
            .filter { it.length >= 4 }
            .take(5)
            .mapIndexed { idx, qText ->
                AiClarificationQuestion(
                    id = "q${idx + 1}",
                    question = qText,
                    allowMultiple = false,
                    options = listOf("問題なし・進める", "条件を指定する"),
                    customPlaceholder = "回答や補足を入力…",
                )
            }
        return if (fallbackQuestions.isNotEmpty()) {
            AiClarificationPlan(
                summary = "AIからの確認事項",
                questions = fallbackQuestions,
                rawFallbackText = cleaned,
                providerType = resultMeta?.providerType,
                modelId = resultMeta?.modelId,
                latencyMs = resultMeta?.latencyMs ?: 0L,
            )
        } else {
            AiClarificationPlan(
                summary = "AIからの確認事項",
                questions = listOf(
                    AiClarificationQuestion(
                        id = "q1",
                        question = cleaned.take(300).ifBlank { "補足したい内容や要望があれば入力してください。" },
                        allowMultiple = false,
                        options = listOf("このまま作成する"),
                        customPlaceholder = "追加の条件・詳細を入力…",
                    ),
                ),
                rawFallbackText = cleaned,
                providerType = resultMeta?.providerType,
                modelId = resultMeta?.modelId,
                latencyMs = resultMeta?.latencyMs ?: 0L,
            )
        }
    }

    fun parseSubjectSuggestionsResponse(rawText: String): List<String> {
        val cleaned = stripMarkdownCodeFences(rawText)
        val jsonSlice = extractOutermostJsonObject(cleaned)
        if (jsonSlice != null) {
            val parsedObj = runCatching {
                lenientJson.parseToJsonElement(jsonSlice) as? JsonObject
            }.getOrNull()
            val arr = parsedObj?.get("subjects") as? JsonArray
            if (arr != null) {
                val fromJson = arr.mapNotNull { elem ->
                    elem.jsonPrimitive.contentOrNull?.let(::normalizeSubjectCandidate)
                }.distinct().take(3)
                if (fromJson.isNotEmpty()) return fromJson
            }
        }

        return cleaned.lines()
            .mapNotNull(::normalizeSubjectCandidate)
            .distinct()
            .take(3)
    }

    private fun normalizeSubjectCandidate(raw: String): String? {
        val stripped = raw
            .replace(Regex("""^\s*(?:\d+[.)．、]|[-*・•])\s*"""), "")
            .replace(Regex("""^(?:件名|Subject)\s*[:：]\s*""", RegexOption.IGNORE_CASE), "")
            .trim()
            .removeSurrounding("\"", "\"")
            .removeSurrounding("「", "」")
            .trim()
        return stripped.takeIf { it.isNotEmpty() }
    }

    private fun stripMarkdownCodeFences(rawText: String): String {
        val cleaned = rawText.trim()
        if (cleaned.startsWith("```") && cleaned.endsWith("```") && cleaned.length >= 6) {
            val firstNewline = cleaned.indexOf('\n')
            val lastFence = cleaned.lastIndexOf("```")
            if (firstNewline > 0 && lastFence > firstNewline) {
                return cleaned.substring(firstNewline + 1, lastFence).trim()
            }
        }
        return cleaned
    }

    private fun extractOutermostJsonObject(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start >= 0 && end > start) text.substring(start, end + 1) else null
    }

    private val lenientJson = Json { ignoreUnknownKeys = true }

    fun buildSummaryRequest(
        context: AiPreparedEmailContext?,
        subjectFallback: String,
        userInstruction: String = "",
        customSystemInstruction: String = "",
        extraReferenceEmails: List<AiPreparedEmailContext> = emptyList(),
        extraTextDocuments: List<AiPreparedTextDocument> = emptyList(),
        binaryAttachmentDescriptors: List<String> = emptyList(),
    ): AiCompletionRequest {
        val systemPrompt = buildString {
            appendLine("あなたはメールクライアント KazeMail に組み込まれた正確で簡潔な日本語メール要約アシスタントです。")
            appendLine("【厳守ルール】")
            appendLine("1. 提供されたメール本文・ヘッダー・追加参照メール・添付画像/PDFに書かれていない事実、金額、期限、結論を推測・創作しないこと。")
            appendLine("2. 以下の3項目で簡潔に整理し、各項目は箇条書きで端的にまとめること。")
            appendLine("   ■ 要旨（結論・目的を1〜2行）")
            appendLine("   ■ 要対応・確認事項（返信や行動が必要な点。なければ「特になし」）")
            appendLine("   ■ 主な日程・数値・固有名詞（資料に記載がある場合のみ）")
            appendLine("3. 前置き（「以下が要約です」等）や Markdown のコードフェンスは付けないこと。")
            if (customSystemInstruction.isNotBlank()) {
                appendLine()
                appendLine("【ユーザー共通の指示】")
                appendLine(customSystemInstruction.trim())
            }
        }.trim()

        val resolvedSubject = context?.threadSubject?.takeIf { it.isNotBlank() } ?: subjectFallback.trim()
        val userPrompt = buildString {
            appendLine("タスク: ${AiWritingGoal.SUMMARIZE.displayLabel}")
            if (resolvedSubject.isNotBlank()) {
                appendLine("件名: $resolvedSubject")
            }
            if (userInstruction.isNotBlank()) {
                appendLine()
                appendLine("【要約時の着眼点】")
                appendLine(userInstruction.trim())
            }
            if (context != null && context.formattedContext.isNotBlank()) {
                appendLine()
                appendLine("【対象スレッド文脈（${context.includedMessageCount}/${context.totalMessageCount}件）】")
                appendLine(context.formattedContext)
            }
            appendExtraReferences(
                extraReferenceEmails = extraReferenceEmails,
                extraTextDocuments = extraTextDocuments,
                binaryAttachmentDescriptors = binaryAttachmentDescriptors,
            )
        }.trim()

        return AiCompletionRequest(
            goal = AiWritingGoal.SUMMARIZE,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            temperature = 0.2,
            maxOutputTokens = 2048,
        )
    }

    private fun StringBuilder.appendExtraReferences(
        extraReferenceEmails: List<AiPreparedEmailContext>,
        extraTextDocuments: List<AiPreparedTextDocument>,
        binaryAttachmentDescriptors: List<String>,
    ) {
        if (extraReferenceEmails.isNotEmpty()) {
            extraReferenceEmails.forEachIndexed { idx, refCtx ->
                if (refCtx.formattedContext.isNotBlank()) {
                    appendLine()
                    appendLine("【追加参照メール ${idx + 1}: ${refCtx.threadSubject.ifBlank { "(件名なし)" }}】")
                    appendLine(refCtx.formattedContext)
                }
            }
        }
        if (extraTextDocuments.isNotEmpty()) {
            extraTextDocuments.forEach { doc ->
                if (doc.content.isNotBlank()) {
                    appendLine()
                    appendLine("【参照ドキュメント: ${doc.displayName} (${doc.mimeType})】")
                    appendLine(doc.content)
                }
            }
        }
        if (binaryAttachmentDescriptors.isNotEmpty()) {
            appendLine()
            appendLine("【添付画像・PDF資料（合計${binaryAttachmentDescriptors.size}件）】")
            binaryAttachmentDescriptors.forEach { desc ->
                appendLine("- $desc")
            }
        }
    }

    fun sanitizeGeneratedOutput(rawText: String, goal: AiWritingGoal): String {
        var cleaned = stripMarkdownCodeFences(rawText)
        if (goal.isDraftingGoal) {
            cleaned = cleaned.replace("\r\n", "\n").trim()
        }
        return cleaned
    }
}
