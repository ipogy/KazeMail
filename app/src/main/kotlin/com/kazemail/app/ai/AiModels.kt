package com.kazemail.app.ai

import kotlinx.serialization.Serializable

@Serializable
enum class AiReferenceFileKind {
    IMAGE,
    PDF,
    TEXT_DOCUMENT,
    OFFICE_DOCUMENT,
    UNSUPPORTED,
    ;

    val isBinarySupported: Boolean
        get() = this == IMAGE || this == PDF

    val isSupportedForAi: Boolean
        get() = this != UNSUPPORTED
}

@Serializable
data class AiReferenceFileSpec(
    val id: String,
    val displayName: String,
    val mimeType: String,
    val localPath: String,
    val sizeBytes: Long,
    val kind: AiReferenceFileKind,
    val extractedPreview: String? = null,
    val warningNote: String? = null,
)

data class AiReferenceEmailSummary(
    val metaId: Long,
    val accountId: Long,
    val senderLabel: String,
    val subject: String,
    val snippet: String,
    val receivedAt: Long,
    val hasAttachments: Boolean,
)

data class AiBinaryAttachment(
    val displayName: String,
    val mimeType: String,
    val base64Data: String,
    val byteSize: Int,
)

data class AiPreparedTextDocument(
    val displayName: String,
    val mimeType: String,
    val content: String,
    val wasTruncated: Boolean,
    val redactionCount: Int,
)

data class AiPreparedReferenceBundle(
    val extraEmailContexts: List<AiPreparedEmailContext> = emptyList(),
    val inlineTextDocuments: List<AiPreparedTextDocument> = emptyList(),
    val binaryAttachments: List<AiBinaryAttachment> = emptyList(),
    val binaryDescriptorLabels: List<String> = emptyList(),
    val skippedNotes: List<String> = emptyList(),
    val totalRedactions: Int = 0,
) {
    val hasAnyReference: Boolean
        get() = extraEmailContexts.isNotEmpty() ||
            inlineTextDocuments.isNotEmpty() ||
            binaryAttachments.isNotEmpty() ||
            binaryDescriptorLabels.isNotEmpty()
}

enum class AiProviderType(
    val storageValue: String,
    val displayLabel: String,
    val defaultBaseUrl: String,
    val defaultModelId: String,
    val suggestedModels: List<String>,
    val requiresApiKey: Boolean,
    val apiKeyPlaceholder: String,
) {
    GEMINI(
        storageValue = "GEMINI",
        displayLabel = "Google Gemini",
        defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta",
        defaultModelId = "gemini-4-hydrogen",
        suggestedModels = listOf(
            "gemini-4-hydrogen",
            "gemini-4-neon",
            "gemini-4-carbon",
            "gemini-3.8-flash",
            "gemini-3.6-flash",
        ),
        requiresApiKey = true,
        apiKeyPlaceholder = "AIza...",
    ),
    OPENAI(
        storageValue = "OPENAI",
        displayLabel = "OpenAI",
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModelId = "gpt-6-luna",
        suggestedModels = listOf(
            "gpt-6-luna",
            "gpt-6.2-sol",
            "gpt-6.1-terra",
            "gpt-6.2-astra",
        ),
        requiresApiKey = true,
        apiKeyPlaceholder = "sk-...",
    ),
    ANTHROPIC(
        storageValue = "ANTHROPIC",
        displayLabel = "Anthropic Claude",
        defaultBaseUrl = "https://api.anthropic.com/v1",
        defaultModelId = "claude-sonnet-5-5",
        suggestedModels = listOf(
            "claude-sonnet-5-5",
            "claude-haiku-5-5",
            "claude-opus-5-5",
            "claude-fable-5-2",
        ),
        requiresApiKey = true,
        apiKeyPlaceholder = "sk-ant-...",
    ),
    OPENAI_COMPATIBLE(
        storageValue = "OPENAI_COMPATIBLE",
        displayLabel = "OpenAI互換カスタム",
        defaultBaseUrl = "https://openrouter.ai/api/v1",
        defaultModelId = "openrouter/auto",
        suggestedModels = listOf(
            "openrouter/auto",
            "google/gemini-4-hydrogen",
            "anthropic/claude-sonnet-5.5",
            "deepseek/deepseek-v4.1-flash",
            "qwen/qwen3.8-max",
            "qwen2.5:14b",
        ),
        requiresApiKey = false,
        apiKeyPlaceholder = "Bearerトークン（不要なら空欄可）",
    ),
    ;

    companion object {
        fun fromStorage(raw: String?): AiProviderType =
            entries.firstOrNull { it.storageValue.equals(raw, ignoreCase = true) } ?: GEMINI
    }
}

enum class AiReplyTone(
    val storageValue: String,
    val displayLabel: String,
    val promptDirective: String,
) {
    FORMAL_BUSINESS(
        storageValue = "FORMAL_BUSINESS",
        displayLabel = "ビジネス敬語",
        promptDirective = "礼儀正しく自然な日本のビジネスメール調（適切な敬語・クッション言葉・簡潔な結び）で作成する。",
    ),
    POLITE(
        storageValue = "POLITE",
        displayLabel = "標準・丁寧",
        promptDirective = "過度に堅苦しくなりすぎない丁寧なです・ます調で、読みやすく自然に作成する。",
    ),
    CONCISE(
        storageValue = "CONCISE",
        displayLabel = "簡潔・要点のみ",
        promptDirective = "挨拶を最小限にし、結論と確認事項・依頼事項だけを短く明瞭に伝える。",
    ),
    APOLOGY(
        storageValue = "APOLOGY",
        displayLabel = "お詫び・配慮",
        promptDirective = "相手への配慮とお詫びを丁寧に述べつつ、経緯・対応方針・今後の予定を誤解なく伝える。",
    ),
    CASUAL(
        storageValue = "CASUAL",
        displayLabel = "カジュアル",
        promptDirective = "同僚や親しい関係向けに、柔らかい敬語または親しみやすいトーンで作成する。",
    ),
    ;

    companion object {
        fun fromStorage(raw: String?): AiReplyTone =
            entries.firstOrNull { it.storageValue.equals(raw, ignoreCase = true) } ?: FORMAL_BUSINESS
    }
}

enum class AiWritingGoal(
    val storageValue: String,
    val displayLabel: String,
) {
    REPLY("REPLY", "返信下書き"),
    REPLY_ALL("REPLY_ALL", "全員へ返信下書き"),
    NEW_DRAFT("NEW_DRAFT", "新規下書き"),
    REWRITE_TONE("REWRITE_TONE", "トーン・表現調整"),
    EXPAND_BULLETS("EXPAND_BULLETS", "箇条書きから本文作成"),
    SUMMARIZE("SUMMARIZE", "スレッド・メール要約"),
    CLARIFY_QUESTIONS("CLARIFY_QUESTIONS", "逆質問（情報確認）"),
    GENERATE_SUBJECT("GENERATE_SUBJECT", "本文から件名作成"),
    ;

    val isDraftingGoal: Boolean
        get() = this != SUMMARIZE && this != CLARIFY_QUESTIONS && this != GENERATE_SUBJECT

    companion object {
        fun fromStorage(raw: String?): AiWritingGoal =
            entries.firstOrNull { it.storageValue.equals(raw, ignoreCase = true) } ?: REPLY
    }
}

@Serializable
data class AiClarificationQuestion(
    val id: String,
    val question: String,
    val allowMultiple: Boolean = false,
    val options: List<String> = emptyList(),
    val customPlaceholder: String = "自由入力・補足があれば記入…",
)

@Serializable
data class AiClarificationAnswer(
    val questionId: String,
    val questionText: String,
    val selectedOptions: List<String> = emptyList(),
    val customInput: String = "",
) {
    val hasAnswer: Boolean
        get() = selectedOptions.isNotEmpty() || customInput.isNotBlank()
}

data class AiClarificationPlan(
    val summary: String = "",
    val questions: List<AiClarificationQuestion> = emptyList(),
    val rawFallbackText: String? = null,
    val providerType: AiProviderType? = null,
    val modelId: String? = null,
    val latencyMs: Long = 0L,
)

data class AiSubjectSuggestionsResult(
    val subjects: List<String>,
    val providerType: AiProviderType,
    val modelId: String,
    val latencyMs: Long,
)

object AiClarificationAnswerReducer {
    fun toggleOption(
        answers: Map<String, AiClarificationAnswer>,
        question: AiClarificationQuestion,
        option: String,
    ): Map<String, AiClarificationAnswer> {
        val current = answers[question.id] ?: AiClarificationAnswer(
            questionId = question.id,
            questionText = question.question,
        )
        val nextSelected = if (question.allowMultiple) {
            if (option in current.selectedOptions) {
                current.selectedOptions - option
            } else {
                current.selectedOptions + option
            }
        } else {
            if (current.selectedOptions == listOf(option)) {
                emptyList()
            } else {
                listOf(option)
            }
        }
        val updated = current.copy(questionText = question.question, selectedOptions = nextSelected)
        return answers.toMutableMap().apply {
            if (updated.hasAnswer) put(question.id, updated) else remove(question.id)
        }
    }

    fun updateCustomInput(
        answers: Map<String, AiClarificationAnswer>,
        question: AiClarificationQuestion,
        customInput: String,
    ): Map<String, AiClarificationAnswer> {
        val current = answers[question.id] ?: AiClarificationAnswer(
            questionId = question.id,
            questionText = question.question,
        )
        val updated = current.copy(questionText = question.question, customInput = customInput)
        return answers.toMutableMap().apply {
            if (updated.hasAnswer) put(question.id, updated) else remove(question.id)
        }
    }

    fun orderedAnswers(
        plan: AiClarificationPlan?,
        answers: Map<String, AiClarificationAnswer>,
    ): List<AiClarificationAnswer> {
        if (answers.isEmpty()) return emptyList()
        val orderedIds = plan?.questions?.map { it.id }.orEmpty()
        val prioritized = orderedIds.mapNotNull { id -> answers[id]?.takeIf { it.hasAnswer } }
        val remainder = answers.values.filter { it.hasAnswer && it.questionId !in orderedIds }
        return prioritized + remainder
    }
}

enum class AiConnectionTestStatus(val storageValue: String, val displayLabel: String) {
    UNTESTED("UNTESTED", "未テスト"),
    SUCCESS("SUCCESS", "接続OK"),
    FAILED("FAILED", "接続エラー"),
    ;

    companion object {
        fun fromStorage(raw: String?): AiConnectionTestStatus =
            entries.firstOrNull { it.storageValue.equals(raw, ignoreCase = true) } ?: UNTESTED
    }
}

enum class AiReadinessStatus {
    READY,
    FEATURE_DISABLED,
    CONSENT_REQUIRED,
    NO_PROFILE,
    PROFILE_DISABLED,
    MISSING_API_KEY,
    INVALID_ENDPOINT,
}

data class AiReadinessState(
    val status: AiReadinessStatus,
    val activeProfileId: Long? = null,
    val activeProfileName: String? = null,
    val providerType: AiProviderType? = null,
    val modelId: String? = null,
    val maskedKeyHint: String? = null,
    val lastTestStatus: AiConnectionTestStatus? = null,
    val summaryLabel: String,
    val actionHint: String,
) {
    val isReady: Boolean get() = status == AiReadinessStatus.READY
}

data class AiProviderProfileSummary(
    val profileId: Long,
    val displayName: String,
    val providerType: AiProviderType,
    val baseUrl: String,
    val modelId: String,
    val hasStoredApiKey: Boolean,
    val apiKeyHint: String?,
    val isDefault: Boolean,
    val isEnabled: Boolean,
    val lastTestStatus: AiConnectionTestStatus,
    val lastTestMessage: String?,
    val lastTestedAt: Long?,
    val updatedAt: Long,
)

data class AiCompletionRequest(
    val goal: AiWritingGoal,
    val systemPrompt: String,
    val userPrompt: String,
    val temperature: Double? = 0.3,
    val maxOutputTokens: Int = 2048,
    val isProbe: Boolean = false,
    val attachments: List<AiBinaryAttachment> = emptyList(),
)

data class AiCompletionResult(
    val text: String,
    val providerType: AiProviderType,
    val modelId: String,
    val latencyMs: Long,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val finishReason: String? = null,
)

data class AiConnectionTestResult(
    val success: Boolean,
    val providerType: AiProviderType,
    val modelId: String,
    val latencyMs: Long,
    val httpStatus: Int? = null,
    val message: String,
    val sampleReply: String? = null,
)

data class AiPreparedEmailContext(
    val threadSubject: String,
    val selfEmail: String,
    val replyToAddress: String?,
    val includedMessageCount: Int,
    val totalMessageCount: Int,
    val strippedQuoteBlocks: Int,
    val redactionCount: Int,
    val wasTruncated: Boolean,
    val formattedContext: String,
) {
    val totalChars: Int get() = formattedContext.length
}

class AiClientException(
    val kind: Kind,
    override val message: String,
    val httpStatus: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Kind {
        INVALID_CONFIG,
        MISSING_API_KEY,
        AUTH_FAILED,
        MODEL_NOT_FOUND,
        RATE_LIMITED,
        NETWORK_ERROR,
        HTTP_ERROR,
        PARSE_ERROR,
        POLICY_BLOCKED,
    }
}
