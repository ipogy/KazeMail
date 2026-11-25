package com.kazemail.app.ui.compose

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kazemail.app.ai.AiClarificationAnswer
import com.kazemail.app.ai.AiClarificationAnswerReducer
import com.kazemail.app.ai.AiClarificationPlan
import com.kazemail.app.ai.AiClarificationQuestion
import com.kazemail.app.ai.AiCompletionRequest
import com.kazemail.app.ai.AiCompletionResult
import com.kazemail.app.ai.AiPreparationService
import com.kazemail.app.ai.AiPreparedEmailContext
import com.kazemail.app.ai.AiPreparedTextDocument
import com.kazemail.app.ai.AiPromptBuilder
import com.kazemail.app.ai.AiReadinessState
import com.kazemail.app.ai.AiReadinessStatus
import com.kazemail.app.ai.AiReferenceEmailSummary
import com.kazemail.app.ai.AiReferenceFileSpec
import com.kazemail.app.ai.AiReferenceStaging
import com.kazemail.app.ai.AiReplyTone
import com.kazemail.app.ai.AiWritingGoal
import com.kazemail.app.ai.AndroidAiBinaryMediaEncoder
import com.kazemail.app.data.db.AccountEntity
import com.kazemail.app.data.db.AiWritingPresetEntity
import com.kazemail.app.data.db.DraftAttachmentRef
import com.kazemail.app.data.db.KazeMailDatabase
import com.kazemail.app.data.db.MessageBodyEntity
import com.kazemail.app.data.db.MessageMetaEntity
import com.kazemail.app.data.model.AccountSignatureResolver
import com.kazemail.app.di.KazeMailContainer
import com.kazemail.app.send.OutboxManager
import com.kazemail.app.sync.SyncScheduler
import com.kazemail.app.ui.viewer.HtmlEmailDetector
import com.kazemail.app.ui.viewer.RecipientDisplay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class RecipientParseResult(
    val valid: List<String>,
    val invalid: List<String>,
)

data class ComposeUiState(
    val isLoading: Boolean = true,
    val accountId: Long = 0L,
    val fromEmail: String = "",
    val fromName: String? = null,
    val draftId: Long? = null,
    val replyToMetaId: Long? = null,
    val mode: String = MODE_NEW,
    val toInput: String = "",
    val ccInput: String = "",
    val bccInput: String = "",
    val showCcBcc: Boolean = false,
    val subject: String = "",
    val bodyText: String = "",
    val isHtmlMode: Boolean = false,
    val bodyHtml: String = "",
    val htmlSnapshotText: String? = null,
    val pendingHtmlOverwriteConfirm: Boolean = false,
    val hasManuallySelectedFormat: Boolean = false,
    val availableAccounts: List<AccountEntity> = emptyList(),
    val accountDefaultSignature: String? = null,
    val signatureOverrideRaw: String? = null,
    val includeSignature: Boolean = false,
    val activeRawSignature: String? = null,
    val quotePlain: String = "",
    val quoteHtml: String? = null,
    val includeQuote: Boolean = false,
    val inReplyTo: String? = null,
    val references: List<String> = emptyList(),
    val attachments: List<DraftAttachmentRef> = emptyList(),
    val draftSaveStatus: String = "ローカル自動保存待機",
    val errorMessage: String? = null,
    val isSending: Boolean = false,
    val queuedAndShouldClose: Boolean = false,
    val discardedAndShouldClose: Boolean = false,
    val aiReadiness: AiReadinessState = AiReadinessState(
        status = AiReadinessStatus.FEATURE_DISABLED,
        summaryLabel = "AI準備状態を確認中…",
        actionHint = "",
    ),
    val aiPresets: List<AiWritingPresetEntity> = emptyList(),
    val isPreparingAiContext: Boolean = false,
    val aiPreparedContext: AiPreparedEmailContext? = null,
    val aiSelectedGoalOverride: AiWritingGoal? = null,
    val aiSelectedTone: AiReplyTone = AiReplyTone.FORMAL_BUSINESS,
    val aiPreferHtmlOutput: Boolean = false,
    val aiInstructionInput: String = "",
    val aiBuiltPromptPreview: AiCompletionRequest? = null,
    val isGeneratingAiDraft: Boolean = false,
    val aiGeneratedDraft: AiCompletionResult? = null,
    val aiGeneratedDraftIsHtml: Boolean = false,
    val aiGenerationError: String? = null,
    val previousBodyBeforeAi: String? = null,
    val previousBodyHtmlBeforeAi: String? = null,
    val previousIsHtmlModeBeforeAi: Boolean? = null,
    val aiAutoSheetRequested: Boolean = false,
    val aiReferenceEmails: List<AiReferenceEmailSummary> = emptyList(),
    val aiReferenceEmailContexts: List<AiPreparedEmailContext> = emptyList(),
    val aiReferenceFiles: List<AiReferenceFileSpec> = emptyList(),
    val aiReferenceTextDocuments: List<AiPreparedTextDocument> = emptyList(),
    val aiBinaryDescriptorLabels: List<String> = emptyList(),
    val aiReferenceWarning: String? = null,
    val aiEmailPickerSearchQuery: String = "",
    val aiEmailPickerCandidates: List<AiReferenceEmailSummary> = emptyList(),
    val isSearchingAiEmailPicker: Boolean = false,
    val isConfiguredSignatureActive: Boolean = false,
    val isGeneratingAiQuestions: Boolean = false,
    val aiClarificationPlan: AiClarificationPlan? = null,
    val aiClarificationAnswers: Map<String, AiClarificationAnswer> = emptyMap(),
    val aiClarificationError: String? = null,
    val isGeneratingAiSubject: Boolean = false,
    val aiSubjectSuggestions: List<String> = emptyList(),
    val aiSubjectSourceLabel: String? = null,
    val aiSubjectError: String? = null,
    val previousSubjectBeforeAi: String? = null,
) {
    val effectiveSignatureRaw: String?
        get() = if (!includeSignature) {
            null
        } else {
            (signatureOverrideRaw ?: accountDefaultSignature ?: activeRawSignature)?.takeIf { it.isNotBlank() }
        }

    companion object {
        const val MODE_NEW = "NEW"
        const val MODE_REPLY = "REPLY"
        const val MODE_REPLY_ALL = "REPLY_ALL"
        const val MODE_FORWARD = "FORWARD"
    }
}

internal data class DraftAutoSaveSnapshot(
    val toInput: String,
    val ccInput: String,
    val bccInput: String,
    val subject: String,
    val bodyText: String,
    val isHtml: Boolean,
    val bodyHtml: String,
    val includeSignature: Boolean,
    val effectiveSignatureRaw: String?,
    val includeQuote: Boolean,
    val quotePlain: String,
    val quoteHtml: String?,
    val attachments: List<DraftAttachmentRef>,
) {
    fun hasAnyContent(): Boolean =
        toInput.isNotBlank() ||
            ccInput.isNotBlank() ||
            bccInput.isNotBlank() ||
            subject.isNotBlank() ||
            bodyText.isNotBlank() ||
            bodyHtml.isNotBlank() ||
            attachments.isNotEmpty()
}

object ComposeHelper {
    private val json = Json { ignoreUnknownKeys = true }
    private val basicEmailRegex = Regex("^[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+$")

    fun parseAddressInput(raw: String): RecipientParseResult {
        val tokens = raw.split(',', '、', '，', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val valid = mutableListOf<String>()
        val invalid = mutableListOf<String>()
        val seen = linkedSetOf<String>()
        for (token in tokens) {
            val bare = extractEmailToken(token)
            if (basicEmailRegex.matches(bare)) {
                val lower = bare.lowercase(Locale.US)
                if (seen.add(lower)) {
                    valid += bare
                }
            } else {
                invalid += token
            }
        }
        return RecipientParseResult(valid = valid, invalid = invalid)
    }

    private fun extractEmailToken(token: String): String {
        val start = token.lastIndexOf('<')
        val end = token.lastIndexOf('>')
        return if (start >= 0 && end > start + 1) {
            token.substring(start + 1, end).trim()
        } else {
            token.trim().trim('"', '<', '>')
        }
    }

    fun buildQuotedReplyData(
        mode: String,
        selfEmail: String,
        meta: MessageMetaEntity,
        body: MessageBodyEntity?,
    ): ReplyPrefill {
        val selfLower = selfEmail.trim().lowercase(Locale.US)
        val rawHeaders = body?.rawHeaders ?: ""
        val toListFromMeta = runCatching {
            json.decodeFromString<List<RecipientDisplay>>(meta.recipientTo ?: "[]").map { it.address }
        }.getOrDefault(emptyList())
        val ccFromHeaders = extractHeaderAddressList(rawHeaders, "Cc")
        val refsFromHeaders = extractReferencesHeader(rawHeaders)
        val combinedRefs = buildList {
            refsFromHeaders.forEach { if (it !in this) add(it) }
            meta.inReplyTo?.trim()?.takeIf { it.isNotEmpty() }?.let {
                val wrapped = wrapMessageId(it)
                if (wrapped !in this) add(wrapped)
            }
            meta.messageIdHeader?.trim()?.takeIf { it.isNotEmpty() }?.let {
                val wrapped = wrapMessageId(it)
                if (wrapped !in this) add(wrapped)
            }
        }

        val senderBare = extractEmailToken(meta.senderAddress)
        val df = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
        val dateStr = if (meta.receivedAt > 0L) df.format(Date(meta.receivedAt)) else "--"
        val senderLabel = meta.senderName?.takeIf { it.isNotBlank() }?.let { "$it <$senderBare>" } ?: senderBare
        val origPlain = body?.plainText ?: meta.snippet ?: ""

        return when (mode) {
            ComposeUiState.MODE_FORWARD -> {
                val subj = ensureSubjectPrefix(meta.subject, "Fwd: ")
                val quoted = buildString {
                    append("\n\n---------- 転送メッセージ ----------\n")
                    append("差出人: ").append(senderLabel).append('\n')
                    append("日時: ").append(dateStr).append('\n')
                    append("件名: ").append(meta.subject ?: "(件名なし)").append('\n')
                    if (toListFromMeta.isNotEmpty()) {
                        append("宛先: ").append(toListFromMeta.joinToString(", ")).append('\n')
                    }
                    append('\n')
                    append(origPlain)
                }
                ReplyPrefill(
                    to = emptyList(),
                    cc = emptyList(),
                    subject = subj,
                    bodyText = quoted,
                    inReplyTo = null,
                    references = emptyList(),
                )
            }
            ComposeUiState.MODE_REPLY_ALL -> {
                val toCandidates = buildList {
                    add(senderBare)
                    toListFromMeta.forEach { addr ->
                        val bare = extractEmailToken(addr)
                        if (bare.lowercase(Locale.US) != selfLower && none { it.equals(bare, true) }) {
                            add(bare)
                        }
                    }
                }
                val ccCandidates = ccFromHeaders.filter { addr ->
                    val bare = extractEmailToken(addr)
                    bare.lowercase(Locale.US) != selfLower &&
                        toCandidates.none { it.equals(bare, true) }
                }.distinctBy { it.lowercase(Locale.US) }
                ReplyPrefill(
                    to = toCandidates,
                    cc = ccCandidates,
                    subject = ensureSubjectPrefix(meta.subject, "Re: "),
                    bodyText = formatQuotedReplyBody(senderLabel, dateStr, origPlain),
                    inReplyTo = meta.messageIdHeader?.let(::wrapMessageId),
                    references = combinedRefs,
                )
            }
            else -> {
                ReplyPrefill(
                    to = listOf(senderBare),
                    cc = emptyList(),
                    subject = ensureSubjectPrefix(meta.subject, "Re: "),
                    bodyText = formatQuotedReplyBody(senderLabel, dateStr, origPlain),
                    inReplyTo = meta.messageIdHeader?.let(::wrapMessageId),
                    references = combinedRefs,
                )
            }
        }
    }

    private fun formatQuotedReplyBody(senderLabel: String, dateStr: String, origPlain: String): String {
        val quotedLines = origPlain.lines().joinToString("\n") { line ->
            if (line.startsWith(">")) ">$line" else "> $line"
        }
        return "\n\n$dateStr $senderLabel:\n$quotedLines"
    }

    fun buildQuotedReplyHtml(
        mode: String,
        meta: MessageMetaEntity,
        body: MessageBodyEntity?,
        decompressedHtml: String?,
        fallbackPlain: String,
    ): String {
        val df = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
        val dateStr = if (meta.receivedAt > 0L) df.format(Date(meta.receivedAt)) else "--"
        val senderBare = extractEmailToken(meta.senderAddress)
        val senderLabel = meta.senderName?.takeIf { it.isNotBlank() }?.let { "$it <$senderBare>" } ?: senderBare
        return if (mode == ComposeUiState.MODE_FORWARD) {
            val toList = runCatching {
                json.decodeFromString<List<RecipientDisplay>>(meta.recipientTo ?: "[]").map { it.address }
            }.getOrDefault(emptyList())
            val metaHeader = buildString {
                append("差出人: ").append(senderLabel).append('\n')
                append("日時: ").append(dateStr).append('\n')
                append("件名: ").append(meta.subject ?: "(件名なし)")
                if (toList.isNotEmpty()) {
                    append("\n宛先: ").append(toList.joinToString(", "))
                }
            }
            ComposeHtmlHelper.formatForwardedHtml(
                headerLinesPlain = metaHeader,
                bodyHtmlOrNull = decompressedHtml,
                fallbackPlainText = body?.plainText ?: meta.snippet ?: fallbackPlain,
            )
        } else {
            ComposeHtmlHelper.formatQuotedReplyHtml(
                headerLine = "$dateStr $senderLabel:",
                bodyHtmlOrNull = decompressedHtml,
                fallbackPlainText = body?.plainText ?: meta.snippet ?: fallbackPlain,
            )
        }
    }

    private fun ensureSubjectPrefix(subject: String?, prefix: String): String {
        val clean = subject?.trim().orEmpty()
        if (clean.isEmpty()) return prefix.trimEnd()
        val barePrefix = prefix.trim().lowercase(Locale.US)
        return if (clean.lowercase(Locale.US).startsWith(barePrefix)) clean else "$prefix$clean"
    }

    private fun wrapMessageId(id: String): String {
        val t = id.trim()
        return if (t.startsWith("<") && t.endsWith(">")) t else "<${t.trim('<', '>')}>"
    }

    private fun extractHeaderAddressList(rawHeaders: String, headerName: String): List<String> {
        val unfolded = rawHeaders.replace("\r\n ", " ").replace("\r\n\t", " ")
        val targetPrefix = "$headerName:"
        val line = unfolded.lines().firstOrNull {
            it.startsWith(targetPrefix, ignoreCase = true)
        } ?: return emptyList()
        val value = line.substringAfter(':').trim()
        return value.split(',')
            .map { extractEmailToken(it) }
            .filter { basicEmailRegex.matches(it) }
    }

    private fun extractReferencesHeader(rawHeaders: String): List<String> {
        val unfolded = rawHeaders.replace("\r\n ", " ").replace("\r\n\t", " ")
        val line = unfolded.lines().firstOrNull {
            it.startsWith("References:", ignoreCase = true)
        } ?: return emptyList()
        val value = line.substringAfter(':')
        return Regex("<[^>]+>|\\S+@\\S+")
            .findAll(value)
            .map { wrapMessageId(it.value) }
            .toList()
    }
}

data class ReplyPrefill(
    val to: List<String>,
    val cc: List<String>,
    val subject: String,
    val bodyText: String,
    val inReplyTo: String?,
    val references: List<String>,
)

/**
 * Splits a Compose body into editable top draft content and trailing preserved footer
 * (email signature "-- \n" and/or quoted/forwarded thread history) so AI edits never
 * wipe the user's signature or quoted reply context.
 */
object ComposeAiBodyMerger {
    private val signatureBoundaryRegex = Regex("""(?:\r?\n\r?\n|\r?\n|^)-- ?\r?\n""")
    private val forwardBoundaryRegex = Regex("""(?:\n\n|\n|^)---------- 転送メッセージ ----------""")
    private val replyQuoteBoundaryRegex = Regex("""(?:\n\n|\n|^)\d{4}/\d{2}/\d{2} [^\n]+:\n>""")

    fun splitEditableAndFooter(fullBody: String): Pair<String, String> {
        val candidates = listOfNotNull(
            signatureBoundaryRegex.find(fullBody)?.range?.first,
            forwardBoundaryRegex.find(fullBody)?.range?.first,
            replyQuoteBoundaryRegex.find(fullBody)?.range?.first,
        )
        val splitIdx = candidates.minOrNull() ?: return fullBody to ""
        val head = fullBody.substring(0, splitIdx)
        val tail = fullBody.substring(splitIdx)
        return head to tail
    }

    fun extractEditableDraftSection(fullBody: String): String =
        splitEditableAndFooter(fullBody).first.trim()

    fun extractEditableDraftHtml(fullHtml: String): String =
        ComposeHtmlHelper.htmlToPlainText(ComposeHtmlHelper.splitHtmlDraftSignatureQuote(fullHtml).first)

    fun extractEditableRawHtmlSection(fullHtml: String): String =
        ComposeHtmlHelper.splitHtmlDraftSignatureQuote(fullHtml).first.trim()

    fun containsSignatureBlock(text: String): Boolean =
        signatureBoundaryRegex.containsMatchIn(text)

    private fun stripDuplicateAiTrailingSignature(generatedBody: String): String {
        val matches = signatureBoundaryRegex.findAll(generatedBody).toList()
        val lastMatch = matches.lastOrNull() ?: return generatedBody
        if (lastMatch.range.first == 0) return generatedBody
        val head = generatedBody.substring(0, lastMatch.range.first).trimEnd()
        return if (head.isNotEmpty()) head else generatedBody
    }

    fun mergeGeneratedBody(
        currentBody: String,
        generatedBody: String,
        appendToExisting: Boolean,
        configuredSignature: String? = null,
    ): String {
        val rawClean = generatedBody.trim()
        if (rawClean.isEmpty()) return currentBody

        val (existingDraft, existingFooter) = splitEditableAndFooter(currentBody)
        val trimmedSig = ComposeHtmlHelper.formatSignatureAsPlain(configuredSignature)
        val existingFooterHasSig = containsSignatureBlock(existingFooter)
        val willIncludeSignature = existingFooterHasSig || trimmedSig != null

        val cleanGenerated = if (willIncludeSignature) {
            stripDuplicateAiTrailingSignature(rawClean)
        } else {
            rawClean
        }

        val draftSection = if (appendToExisting) {
            val trimmedExisting = existingDraft.trim()
            if (trimmedExisting.isEmpty()) cleanGenerated else "$trimmedExisting\n\n$cleanGenerated"
        } else {
            cleanGenerated
        }

        val normalizedExistingFooter = existingFooter.trimStart('\r', '\n')
        val finalFooter = when {
            existingFooterHasSig -> normalizedExistingFooter
            trimmedSig != null && normalizedExistingFooter.isNotEmpty() ->
                "-- \n$trimmedSig\n\n$normalizedExistingFooter"
            trimmedSig != null -> "-- \n$trimmedSig"
            else -> normalizedExistingFooter
        }

        return if (finalFooter.isEmpty()) draftSection else "$draftSection\n\n$finalFooter"
    }

    fun mergeGeneratedHtmlBody(
        currentHtml: String,
        generatedHtml: String,
        appendToExisting: Boolean,
        configuredSignature: String? = null,
    ): String {
        val sanitized = ComposeHtmlHelper.sanitizeAiHtmlOutput(generatedHtml)
        if (sanitized.isBlank()) return currentHtml

        val (existingDraftHtml, existingSigHtml, existingQuoteHtml) =
            ComposeHtmlHelper.splitHtmlDraftSignatureQuote(currentHtml)
        val configuredSigHtml = ComposeHtmlHelper.formatSignatureBlockAsHtml(configuredSignature)
        val willIncludeSignature = !existingSigHtml.isNullOrBlank() || configuredSigHtml.isNotEmpty()

        val cleanedGeneratedHtml = if (willIncludeSignature) {
            ComposeHtmlHelper.splitHtmlDraftSignatureQuote(sanitized).first.ifBlank { sanitized }
        } else {
            sanitized
        }

        val draftHtmlSection = if (appendToExisting) {
            val trimmedExisting = existingDraftHtml.trim()
            if (trimmedExisting.isEmpty()) cleanedGeneratedHtml else "$trimmedExisting\n$cleanedGeneratedHtml"
        } else {
            cleanedGeneratedHtml
        }

        val finalSigHtml = existingSigHtml?.takeIf { it.isNotBlank() } ?: configuredSigHtml
        return listOfNotNull(
            draftHtmlSection.takeIf { it.isNotBlank() },
            finalSigHtml.takeIf { it.isNotBlank() },
            existingQuoteHtml?.takeIf { it.isNotBlank() },
        ).joinToString("\n")
    }
}

data class ComposeExternalPrefill(
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val subject: String = "",
    val bodyText: String = "",
    val attachmentUris: List<Uri> = emptyList(),
)

@OptIn(FlowPreview::class)
class ComposeViewModel(
    private val db: KazeMailDatabase,
    private val outboxManager: OutboxManager,
    private val onEnqueueOutboxWorker: () -> Unit,
    private val accountId: Long,
    private val initialDraftId: Long?,
    private val replyToMetaId: Long?,
    private val initialMode: String,
    private val autoSaveDebounceMs: Long = AUTO_SAVE_DEBOUNCE_MS,
    private val signatureProvider: () -> String? = { null },
    private val signatureResolver: ((AccountEntity) -> String?)? = null,
    private val aiPreparationService: AiPreparationService? = null,
    initialAiTone: AiReplyTone = AiReplyTone.FORMAL_BUSINESS,
    initialAiInstruction: String = "",
    initialAiPreferHtml: Boolean = false,
    private val autoTriggerAiOnStart: Boolean = false,
    private val externalPrefill: ComposeExternalPrefill? = null,
    private val appContext: Context? = null,
    private val initialAiReferenceMetaIds: List<Long> = emptyList(),
    private val initialAiReferenceFiles: List<AiReferenceFileSpec> = emptyList(),
    private val initialAiClarificationPlan: AiClarificationPlan? = null,
    private val initialAiClarificationAnswers: Map<String, AiClarificationAnswer> = emptyMap(),
    private val decompressHtml: suspend (ByteArray) -> String? = { null },
) : ViewModel() {

    constructor(
        container: KazeMailContainer,
        accountId: Long,
        draftId: Long?,
        replyToMetaId: Long?,
        mode: String,
        initialAiTone: String? = null,
        initialAiInstruction: String? = null,
        initialAiPreferHtml: Boolean? = null,
        autoTriggerAi: Boolean = false,
        externalPrefill: ComposeExternalPrefill? = null,
        context: Context? = null,
        initialAiReferenceMetaIds: List<Long> = emptyList(),
        initialAiReferenceFiles: List<AiReferenceFileSpec> = emptyList(),
        initialAiClarificationPlan: AiClarificationPlan? = null,
        initialAiClarificationAnswers: Map<String, AiClarificationAnswer> = emptyMap(),
    ) : this(
        db = container.database,
        outboxManager = container.outboxManager,
        onEnqueueOutboxWorker = { container.syncScheduler.enqueueOutboxSend() },
        accountId = accountId,
        initialDraftId = draftId,
        replyToMetaId = replyToMetaId,
        initialMode = mode,
        signatureProvider = {
            if (container.appPreferences.signatureEnabled.value) {
                container.appPreferences.signatureText.value.takeIf { it.isNotBlank() }
            } else {
                null
            }
        },
        signatureResolver = { targetAccount ->
            AccountSignatureResolver.resolveActiveSignatureText(
                account = targetAccount,
                legacyEnabled = container.appPreferences.signatureEnabled.value,
                legacyText = container.appPreferences.signatureText.value,
            )
        },
        aiPreparationService = container.aiPreparationService,
        initialAiTone = initialAiTone?.let { AiReplyTone.fromStorage(it) }
            ?: container.appPreferences.aiDefaultTone.value,
        initialAiInstruction = initialAiInstruction?.trim().orEmpty(),
        initialAiPreferHtml = initialAiPreferHtml ?: container.appPreferences.aiDefaultPreferHtml.value,
        autoTriggerAiOnStart = autoTriggerAi,
        externalPrefill = externalPrefill,
        appContext = context?.applicationContext,
        initialAiReferenceMetaIds = initialAiReferenceMetaIds,
        initialAiReferenceFiles = initialAiReferenceFiles,
        initialAiClarificationPlan = initialAiClarificationPlan,
        initialAiClarificationAnswers = initialAiClarificationAnswers,
        decompressHtml = { compressed -> container.messageRepository.decompressHtmlInMemory(compressed) },
    )

    private val _uiState = MutableStateFlow(
        ComposeUiState(
            accountId = accountId,
            mode = initialMode,
            aiSelectedTone = initialAiTone,
            aiPreferHtmlOutput = initialAiPreferHtml,
            aiInstructionInput = initialAiInstruction,
            isConfiguredSignatureActive = !signatureProvider().isNullOrBlank(),
            aiClarificationPlan = initialAiClarificationPlan,
            aiClarificationAnswers = initialAiClarificationAnswers,
        ),
    )
    val uiState: StateFlow<ComposeUiState> = _uiState.asStateFlow()

    private val autoSaveTrigger = MutableStateFlow<DraftAutoSaveSnapshot?>(null)
    private var aiGenerationJob: Job? = null
    private var aiClarificationJob: Job? = null
    private var aiSubjectJob: Job? = null
    private var aiPickerSearchJob: Job? = null
    private var initialAiReferencesApplied = false

    init {
        loadInitialState()
        observeAiPreparationState()
        viewModelScope.launch {
            autoSaveTrigger
                .filterNotNull()
                .debounce(autoSaveDebounceMs)
                .collect { snap ->
                    val current = _uiState.value
                    if (
                        !current.isLoading &&
                        !current.isSending &&
                        !current.queuedAndShouldClose &&
                        !current.discardedAndShouldClose &&
                        snap.hasAnyContent()
                    ) {
                        saveDraftInternal(snap)
                    }
                }
        }
    }

    private fun loadInitialState() {
        viewModelScope.launch {
            runCatching {
                val allAccounts = db.accountDao().getAll()
                val account = allAccounts.firstOrNull { it.accountId == accountId }
                    ?: allAccounts.firstOrNull()
                    ?: error("アカウントが見つかりません")

                if (initialDraftId != null && initialDraftId > 0L) {
                    val draft = db.draftDao().getById(initialDraftId)
                        ?: error("下書きが見つかりません")
                    val draftAccount = allAccounts.firstOrNull { it.accountId == draft.accountId } ?: account
                    val accountSig = resolveSignatureForAccount(draftAccount)
                    val to = outboxManager.decodeStringList(draft.toAddressesJson)
                    val cc = outboxManager.decodeStringList(draft.ccAddressesJson)
                    val bcc = outboxManager.decodeStringList(draft.bccAddressesJson)
                    val refs = outboxManager.decodeStringList(draft.referencesJson)
                    val atts = outboxManager.decodeAttachmentList(draft.attachmentsJson)
                    val draftIsHtml = draft.isHtml || !draft.bodyHtml.isNullOrBlank()
                    val (headPlain, headHtml, includeSig, sigOverride, qPlain, qHtml, includeQ) = if (draftIsHtml) {
                        val rawHtml = draft.bodyHtml?.takeIf { it.isNotBlank() }
                            ?: ComposeHtmlHelper.convertPlainComposeBodyToHtml(draft.bodyText)
                        val (parsedHead, parsedSigHtml, parsedQuoteHtml) =
                            ComposeHtmlHelper.splitHtmlDraftSignatureQuote(rawHtml)
                        val expectedDefaultSigHtml = ComposeHtmlHelper.formatSignatureBlockAsHtml(accountSig).trim()
                        val resolvedIncludeSig = !parsedSigHtml.isNullOrBlank()
                        val resolvedOverride = if (!parsedSigHtml.isNullOrBlank() && parsedSigHtml.trim() != expectedDefaultSigHtml) {
                            ComposeHtmlHelper.extractInnerFromSavedSignatureDiv(parsedSigHtml)
                        } else {
                            null
                        }
                        val resolvedQuotePlain = parsedQuoteHtml
                            ?.let { ComposeHtmlHelper.convertQuoteHtmlToPlain(it) }
                            .orEmpty()
                        LoadedDraftParts(
                            headPlain = ComposeHtmlHelper.htmlToPlainText(parsedHead),
                            headHtml = parsedHead,
                            includeSignature = resolvedIncludeSig,
                            signatureOverrideRaw = resolvedOverride,
                            quotePlain = resolvedQuotePlain,
                            quoteHtml = parsedQuoteHtml,
                            includeQuote = !parsedQuoteHtml.isNullOrBlank(),
                        )
                    } else {
                        val (parsedHead, parsedSigInner, parsedQuotePlain) =
                            ComposeHtmlHelper.splitPlainDraftParts(draft.bodyText)
                        val resolvedIncludeSig = !parsedSigInner.isNullOrBlank()
                        val normalizedParsedSig = ComposeHtmlHelper.formatSignaturePlainBlock(parsedSigInner)
                        val normalizedDefaultSig = ComposeHtmlHelper.formatSignaturePlainBlock(accountSig)
                        val resolvedOverride = if (resolvedIncludeSig && normalizedParsedSig != normalizedDefaultSig) {
                            parsedSigInner
                        } else {
                            null
                        }
                        LoadedDraftParts(
                            headPlain = parsedHead,
                            headHtml = "",
                            includeSignature = resolvedIncludeSig,
                            signatureOverrideRaw = resolvedOverride,
                            quotePlain = parsedQuotePlain.orEmpty(),
                            quoteHtml = null,
                            includeQuote = !parsedQuotePlain.isNullOrBlank(),
                        )
                    }
                    val effSig = if (includeSig) (sigOverride ?: accountSig)?.takeIf { it.isNotBlank() } else null
                    ComposeUiState(
                        isLoading = false,
                        accountId = draftAccount.accountId,
                        fromEmail = draftAccount.emailAddress,
                        fromName = draftAccount.displayName,
                        availableAccounts = allAccounts,
                        accountDefaultSignature = accountSig,
                        signatureOverrideRaw = sigOverride,
                        includeSignature = includeSig,
                        activeRawSignature = effSig,
                        draftId = draft.draftId,
                        replyToMetaId = draft.replyToMetaId,
                        mode = draft.mode,
                        toInput = to.joinToString(", "),
                        ccInput = cc.joinToString(", "),
                        bccInput = bcc.joinToString(", "),
                        showCcBcc = cc.isNotEmpty() || bcc.isNotEmpty(),
                        subject = draft.subject,
                        bodyText = headPlain,
                        isHtmlMode = draftIsHtml,
                        bodyHtml = headHtml,
                        htmlSnapshotText = if (draftIsHtml) headPlain else null,
                        hasManuallySelectedFormat = true,
                        quotePlain = qPlain,
                        quoteHtml = qHtml,
                        includeQuote = includeQ,
                        inReplyTo = draft.inReplyTo,
                        references = refs,
                        attachments = atts,
                        draftSaveStatus = "ローカル下書きを読み込みました",
                    )
                } else if (replyToMetaId != null && replyToMetaId > 0L) {
                    val meta = db.messageMetaDao().getById(replyToMetaId)
                        ?: error("返信対象メールが見つかりません")
                    val body = db.messageBodyDao().getByMetaId(meta.id)
                    val activeSig = resolveSignatureForAccount(account)
                    val sigIsHtml = !activeSig.isNullOrBlank() && HtmlEmailDetector.containsActualHtml(activeSig)
                    val decompressedOrigHtml = body?.htmlZstd?.let { zstdBytes ->
                        runCatching { decompressHtml(zstdBytes) }.getOrNull()
                    }?.takeIf { HtmlEmailDetector.containsActualHtml(it) }
                    val prefill = ComposeHelper.buildQuotedReplyData(
                        mode = initialMode,
                        selfEmail = account.emailAddress,
                        meta = meta,
                        body = body,
                    )
                    val cleanQuotePlain = prefill.bodyText.trimStart('\r', '\n')
                    val builtQuoteHtml = ComposeHelper.buildQuotedReplyHtml(
                        mode = initialMode,
                        meta = meta,
                        body = body,
                        decompressedHtml = decompressedOrigHtml,
                        fallbackPlain = cleanQuotePlain,
                    )
                    ComposeUiState(
                        isLoading = false,
                        accountId = account.accountId,
                        fromEmail = account.emailAddress,
                        fromName = account.displayName,
                        availableAccounts = allAccounts,
                        accountDefaultSignature = activeSig,
                        includeSignature = !activeSig.isNullOrBlank(),
                        activeRawSignature = activeSig,
                        replyToMetaId = meta.id,
                        mode = initialMode,
                        toInput = prefill.to.joinToString(", "),
                        ccInput = prefill.cc.joinToString(", "),
                        showCcBcc = prefill.cc.isNotEmpty(),
                        subject = prefill.subject,
                        bodyText = "",
                        isHtmlMode = sigIsHtml,
                        bodyHtml = "",
                        htmlSnapshotText = if (sigIsHtml) "" else null,
                        quotePlain = cleanQuotePlain,
                        quoteHtml = builtQuoteHtml,
                        includeQuote = cleanQuotePlain.isNotBlank() || builtQuoteHtml.isNotBlank(),
                        inReplyTo = prefill.inReplyTo,
                        references = prefill.references,
                        draftSaveStatus = "ローカル自動保存待機（500ms）",
                    )
                } else {
                    val activeSig = resolveSignatureForAccount(account)
                    val sigIsHtml = !activeSig.isNullOrBlank() && HtmlEmailDetector.containsActualHtml(activeSig)
                    val prefill = externalPrefill
                    val prefillBody = prefill?.bodyText.orEmpty()
                    val prefillHtml = if (sigIsHtml && prefillBody.isNotBlank()) {
                        ComposeHtmlHelper.plainTextToHtmlFragment(prefillBody)
                    } else {
                        ""
                    }
                    val ctx = appContext
                    val (prefilledAttachments, failedAttachmentCount) =
                        if (prefill != null && prefill.attachmentUris.isNotEmpty() && ctx != null) {
                            withContext(Dispatchers.IO) {
                                val rootDir = File(ctx.filesDir, "composer_attachments").apply { mkdirs() }
                                var failed = 0
                                val copied = prefill.attachmentUris.mapNotNull { uri ->
                                    runCatching { copyUriToLocalCache(ctx, uri, rootDir) }
                                        .onFailure { failed++ }
                                        .getOrNull()
                                }
                                copied to failed
                            }
                        } else {
                            emptyList<DraftAttachmentRef>() to 0
                        }
                    ComposeUiState(
                        isLoading = false,
                        accountId = account.accountId,
                        fromEmail = account.emailAddress,
                        fromName = account.displayName,
                        availableAccounts = allAccounts,
                        accountDefaultSignature = activeSig,
                        includeSignature = !activeSig.isNullOrBlank(),
                        activeRawSignature = activeSig,
                        mode = ComposeUiState.MODE_NEW,
                        toInput = prefill?.to?.joinToString(", ").orEmpty(),
                        ccInput = prefill?.cc?.joinToString(", ").orEmpty(),
                        bccInput = prefill?.bcc?.joinToString(", ").orEmpty(),
                        showCcBcc = prefill?.let { it.cc.isNotEmpty() || it.bcc.isNotEmpty() } ?: false,
                        subject = prefill?.subject.orEmpty(),
                        bodyText = prefillBody,
                        isHtmlMode = sigIsHtml,
                        bodyHtml = prefillHtml,
                        htmlSnapshotText = if (sigIsHtml) prefillBody else null,
                        attachments = prefilledAttachments,
                        errorMessage = if (failedAttachmentCount > 0) {
                            "一部の共有添付ファイルを読み込めませんでした（${failedAttachmentCount}件）"
                        } else {
                            null
                        },
                        draftSaveStatus = if (prefill != null) {
                            "外部アプリから作成内容を読み込みました"
                        } else {
                            "ローカル自動保存待機（500ms）"
                        },
                    )
                }
            }.onSuccess { loaded ->
                _uiState.update { current ->
                    loaded.copy(
                        aiReadiness = current.aiReadiness,
                        aiPresets = current.aiPresets,
                        aiSelectedTone = current.aiSelectedTone,
                        aiPreferHtmlOutput = current.aiPreferHtmlOutput,
                        aiInstructionInput = current.aiInstructionInput,
                        aiAutoSheetRequested = autoTriggerAiOnStart,
                        isConfiguredSignatureActive = loaded.includeSignature && !loaded.effectiveSignatureRaw.isNullOrBlank(),
                        aiClarificationPlan = current.aiClarificationPlan,
                        aiClarificationAnswers = current.aiClarificationAnswers,
                    )
                }
                if (autoTriggerAiOnStart) {
                    prepareAiAssistSheet(autoGenerate = true)
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "作成画面の初期化失敗: ${err.message}",
                    )
                }
            }
        }
    }

    private data class LoadedDraftParts(
        val headPlain: String,
        val headHtml: String,
        val includeSignature: Boolean,
        val signatureOverrideRaw: String?,
        val quotePlain: String,
        val quoteHtml: String?,
        val includeQuote: Boolean,
    )

    fun updateToInput(value: String) = updateDraftContent { copy(toInput = value) }
    fun updateCcInput(value: String) = updateDraftContent { copy(ccInput = value) }
    fun updateBccInput(value: String) = updateDraftContent { copy(bccInput = value) }
    fun updateSubject(value: String) = updateDraftContent { copy(subject = value) }
    fun updateBodyText(value: String) = updateDraftContent { copy(bodyText = value) }

    fun updateBodyHtml(value: String) = updateDraftContent { copy(bodyHtml = value) }

    fun setIncludeSignature(enabled: Boolean) {
        updateDraftContent {
            val hasSigText = !(signatureOverrideRaw ?: accountDefaultSignature).isNullOrBlank()
            copy(
                includeSignature = enabled,
                activeRawSignature = if (enabled) (signatureOverrideRaw ?: accountDefaultSignature) else null,
                isConfiguredSignatureActive = enabled && hasSigText,
            )
        }
    }

    fun updateSignatureOverride(raw: String) {
        updateDraftContent {
            val overrideOrNull = if (raw == (accountDefaultSignature ?: "")) null else raw
            val hasText = raw.isNotBlank()
            copy(
                signatureOverrideRaw = overrideOrNull,
                includeSignature = hasText,
                activeRawSignature = raw.takeIf { hasText },
                isConfiguredSignatureActive = hasText,
            )
        }
    }

    fun resetSignatureOverride() {
        updateDraftContent {
            val defaultSig = accountDefaultSignature
            val hasSig = !defaultSig.isNullOrBlank()
            copy(
                signatureOverrideRaw = null,
                includeSignature = hasSig,
                activeRawSignature = defaultSig,
                isConfiguredSignatureActive = hasSig,
            )
        }
    }

    fun setIncludeQuote(enabled: Boolean) {
        updateDraftContent { copy(includeQuote = enabled) }
    }

    fun selectFromAccount(newAccountId: Long) {
        val current = _uiState.value
        val target = current.availableAccounts.firstOrNull { it.accountId == newAccountId } ?: return
        if (target.accountId == current.accountId) return
        val newSig = resolveSignatureForAccount(target)
        val sigIsHtml = !newSig.isNullOrBlank() && HtmlEmailDetector.containsActualHtml(newSig)
        updateDraftContent {
            val canAutoSwitchMode = !hasManuallySelectedFormat && bodyText.isBlank() && bodyHtml.isBlank()
            val nextIsHtml = if (canAutoSwitchMode) sigIsHtml else isHtmlMode
            copy(
                accountId = target.accountId,
                fromEmail = target.emailAddress,
                fromName = target.displayName,
                accountDefaultSignature = newSig,
                signatureOverrideRaw = null,
                includeSignature = !newSig.isNullOrBlank(),
                activeRawSignature = newSig,
                isConfiguredSignatureActive = !newSig.isNullOrBlank(),
                isHtmlMode = nextIsHtml,
                htmlSnapshotText = if (nextIsHtml && htmlSnapshotText == null) bodyText else htmlSnapshotText,
            )
        }
        recomputePromptPreview()
    }

    fun toggleHtmlMode(enableHtml: Boolean) {
        val current = _uiState.value
        if (current.isHtmlMode == enableHtml) return
        if (enableHtml) {
            val hasPreservedHtml = current.bodyHtml.isNotBlank() && current.htmlSnapshotText != null
            if (hasPreservedHtml && current.bodyText == current.htmlSnapshotText) {
                _uiState.update {
                    it.copy(
                        isHtmlMode = true,
                        hasManuallySelectedFormat = true,
                        pendingHtmlOverwriteConfirm = false,
                    )
                }
                recomputePromptPreview()
                return
            }
            if (hasPreservedHtml &&
                current.bodyText != current.htmlSnapshotText &&
                ComposeHtmlHelper.containsRichBodyMarkup(current.bodyHtml)
            ) {
                _uiState.update { it.copy(pendingHtmlOverwriteConfirm = true) }
                return
            }
            val converted = ComposeHtmlHelper.plainTextToHtmlFragment(current.bodyText)
            updateDraftContent {
                copy(
                    isHtmlMode = true,
                    hasManuallySelectedFormat = true,
                    bodyHtml = converted,
                    htmlSnapshotText = current.bodyText,
                    pendingHtmlOverwriteConfirm = false,
                )
            }
            recomputePromptPreview()
        } else {
            val plain = ComposeHtmlHelper.htmlToPlainText(current.bodyHtml)
            updateDraftContent {
                copy(
                    isHtmlMode = false,
                    hasManuallySelectedFormat = true,
                    bodyText = plain,
                    htmlSnapshotText = plain,
                    pendingHtmlOverwriteConfirm = false,
                )
            }
            recomputePromptPreview()
        }
    }

    fun confirmSwitchToHtmlWithTextChanges(applyTextChanges: Boolean) {
        val current = _uiState.value
        if (applyTextChanges) {
            val converted = ComposeHtmlHelper.plainTextToHtmlFragment(current.bodyText)
            updateDraftContent {
                copy(
                    isHtmlMode = true,
                    hasManuallySelectedFormat = true,
                    bodyHtml = converted,
                    htmlSnapshotText = current.bodyText,
                    pendingHtmlOverwriteConfirm = false,
                )
            }
        } else {
            val restoredPlain = ComposeHtmlHelper.htmlToPlainText(current.bodyHtml)
            updateDraftContent {
                copy(
                    isHtmlMode = true,
                    hasManuallySelectedFormat = true,
                    bodyText = restoredPlain,
                    htmlSnapshotText = restoredPlain,
                    pendingHtmlOverwriteConfirm = false,
                )
            }
        }
        recomputePromptPreview()
    }

    fun dismissHtmlOverwriteConfirm() {
        _uiState.update { it.copy(pendingHtmlOverwriteConfirm = false) }
    }

    fun buildCombinedPlainBody(state: ComposeUiState = _uiState.value): String {
        val headPlain = if (state.isHtmlMode) {
            ComposeHtmlHelper.htmlToPlainText(state.bodyHtml)
        } else {
            state.bodyText
        }
        return ComposeHtmlHelper.composePlainBody(
            editableBody = headPlain,
            signatureRaw = state.effectiveSignatureRaw,
            includeSignature = state.includeSignature,
            quotePlain = state.quotePlain,
            includeQuote = state.includeQuote,
        )
    }

    fun buildCombinedHtmlBody(state: ComposeUiState = _uiState.value): String {
        val headHtml = if (state.isHtmlMode) {
            state.bodyHtml
        } else {
            ComposeHtmlHelper.plainTextToHtmlFragment(state.bodyText)
        }
        val effectiveQuoteHtml = state.quoteHtml ?: state.quotePlain
            .takeIf { it.isNotBlank() }
            ?.let { ComposeHtmlHelper.convertPlainComposeBodyToHtml(it) }
        return ComposeHtmlHelper.composeHtmlBody(
            editableHtml = headHtml,
            signatureRaw = state.effectiveSignatureRaw,
            includeSignature = state.includeSignature,
            quoteHtml = effectiveQuoteHtml,
            includeQuote = state.includeQuote,
        )
    }

    private fun resolveSignatureForAccount(account: AccountEntity?): String? {
        if (signatureResolver != null && account != null) {
            return signatureResolver.invoke(account)?.takeIf { it.isNotBlank() }
        }
        if (account != null && account.signatureConfigured) {
            return account.signatureText.takeIf { account.signatureEnabled && it.isNotBlank() }
        }
        return signatureProvider()?.takeIf { it.isNotBlank() }
    }

    private fun resolveActiveSignatureForCurrentState(): String? {
        val current = _uiState.value
        if (!current.includeSignature) return null
        val override = current.signatureOverrideRaw?.takeIf { it.isNotBlank() }
        if (override != null) return override
        val activeAcc = current.availableAccounts.firstOrNull { it.accountId == current.accountId }
        return resolveSignatureForAccount(activeAcc) ?: current.accountDefaultSignature?.takeIf { it.isNotBlank() }
    }

    fun toggleCcBcc() {
        _uiState.update { it.copy(showCcBcc = !it.showCcBcc) }
    }

    private fun observeAiPreparationState() {
        val service = aiPreparationService ?: return
        viewModelScope.launch {
            runCatching { service.ensureDefaultPresetsSeeded() }
        }
        viewModelScope.launch {
            service.observeReadinessState().collect { readiness ->
                _uiState.update { it.copy(aiReadiness = readiness) }
            }
        }
        viewModelScope.launch {
            service.observePresets().collect { presets ->
                _uiState.update { it.copy(aiPresets = presets) }
            }
        }
    }

    fun prepareAiAssistSheet(autoGenerate: Boolean = false) {
        val service = aiPreparationService ?: return
        val current = _uiState.value
        val pendingPrimaryMetaId = (current.replyToMetaId ?: replyToMetaId)
            ?.takeIf { it > 0L && current.aiPreparedContext == null }
        val needsInitialExtraRefs = !initialAiReferencesApplied &&
            (initialAiReferenceMetaIds.isNotEmpty() || initialAiReferenceFiles.isNotEmpty())

        if (pendingPrimaryMetaId == null && !needsInitialExtraRefs) {
            recomputePromptPreview()
            if (autoGenerate) {
                generateAiDraft()
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isPreparingAiContext = true, aiGenerationError = null) }
            runCatching {
                val resolvedAccountId = current.accountId.takeIf { it > 0L } ?: accountId
                val primaryContext = if (pendingPrimaryMetaId != null) {
                    val meta = db.messageMetaDao().getById(pendingPrimaryMetaId)
                    service.prepareEmailContext(
                        accountId = resolvedAccountId,
                        metaId = pendingPrimaryMetaId,
                        threadId = meta?.threadId,
                        loadFullThread = !meta?.threadId.isNullOrBlank(),
                    )
                } else {
                    _uiState.value.aiPreparedContext
                }

                if (needsInitialExtraRefs) {
                    initialAiReferencesApplied = true
                }
                val refEmails = if (needsInitialExtraRefs && initialAiReferenceMetaIds.isNotEmpty()) {
                    service.loadReferenceEmailSummaries(initialAiReferenceMetaIds)
                } else {
                    _uiState.value.aiReferenceEmails
                }
                val refEmailContexts = if (refEmails.isNotEmpty()) {
                    service.prepareExtraEmailContexts(
                        metaIds = refEmails.map { it.metaId },
                        fallbackSelfEmail = _uiState.value.fromEmail,
                    )
                } else {
                    emptyList()
                }
                val refFiles = if (needsInitialExtraRefs && initialAiReferenceFiles.isNotEmpty()) {
                    initialAiReferenceFiles
                } else {
                    _uiState.value.aiReferenceFiles
                }
                val refBundle = service.prepareFileReferenceBundle(refFiles)
                QuadRefInit(primaryContext, refEmails, refEmailContexts, refFiles, refBundle)
            }.onSuccess { initData ->
                _uiState.update { state ->
                    val updated = state.copy(
                        isPreparingAiContext = false,
                        aiPreparedContext = initData.primaryContext,
                        aiReferenceEmails = initData.refEmails,
                        aiReferenceEmailContexts = initData.refEmailContexts,
                        aiReferenceFiles = initData.refFiles,
                        aiReferenceTextDocuments = initData.refBundle.inlineTextDocuments,
                        aiBinaryDescriptorLabels = initData.refBundle.binaryDescriptorLabels,
                        aiReferenceWarning = initData.refBundle.skippedNotes.firstOrNull(),
                    )
                    updated.copy(aiBuiltPromptPreview = buildPromptForState(updated))
                }
                if (autoGenerate) {
                    generateAiDraft()
                }
            }.onFailure {
                _uiState.update { state ->
                    state.copy(
                        isPreparingAiContext = false,
                        aiBuiltPromptPreview = buildPromptForState(state),
                    )
                }
                if (autoGenerate) {
                    generateAiDraft()
                }
            }
        }
    }

    fun consumeAiAutoSheetTrigger() {
        _uiState.update { it.copy(aiAutoSheetRequested = false) }
    }

    fun selectAiTone(tone: AiReplyTone) {
        _uiState.update { state ->
            val updated = state.copy(aiSelectedTone = tone, aiGenerationError = null)
            updated.copy(aiBuiltPromptPreview = buildPromptForState(updated))
        }
    }

    fun selectAiPreferHtmlOutput(preferHtml: Boolean) {
        _uiState.update { state ->
            val updated = state.copy(aiPreferHtmlOutput = preferHtml, aiGenerationError = null)
            updated.copy(aiBuiltPromptPreview = buildPromptForState(updated))
        }
    }

    fun selectAiGoal(goal: AiWritingGoal) {
        _uiState.update { state ->
            val updated = state.copy(aiSelectedGoalOverride = goal, aiGenerationError = null)
            updated.copy(aiBuiltPromptPreview = buildPromptForState(updated))
        }
    }

    fun applyAiPreset(preset: AiWritingPresetEntity) {
        val tone = AiReplyTone.fromStorage(preset.tone)
        val presetGoal = AiWritingGoal.fromStorage(preset.goal).takeIf { it.isDraftingGoal }
        _uiState.update { state ->
            val updated = state.copy(
                aiSelectedTone = tone,
                aiSelectedGoalOverride = presetGoal ?: state.aiSelectedGoalOverride,
                aiInstructionInput = preset.instructionPrompt,
                aiGenerationError = null,
            )
            updated.copy(aiBuiltPromptPreview = buildPromptForState(updated))
        }
    }

    fun updateAiInstruction(instruction: String) {
        _uiState.update { state ->
            val updated = state.copy(aiInstructionInput = instruction, aiGenerationError = null)
            updated.copy(aiBuiltPromptPreview = buildPromptForState(updated))
        }
    }

    fun availableAiWritingGoals(): List<AiWritingGoal> {
        val state = _uiState.value
        val baseGoal = when (state.mode) {
            ComposeUiState.MODE_REPLY -> AiWritingGoal.REPLY
            ComposeUiState.MODE_REPLY_ALL -> AiWritingGoal.REPLY_ALL
            else -> AiWritingGoal.NEW_DRAFT
        }
        val editableDraft = if (state.isHtmlMode) {
            ComposeAiBodyMerger.extractEditableDraftHtml(state.bodyHtml)
        } else {
            ComposeAiBodyMerger.extractEditableDraftSection(state.bodyText)
        }
        return buildList {
            add(baseGoal)
            add(AiWritingGoal.EXPAND_BULLETS)
            if (editableDraft.isNotBlank()) {
                add(AiWritingGoal.REWRITE_TONE)
            }
        }.distinct()
    }

    fun resolveAiWritingGoal(): AiWritingGoal = resolveAiWritingGoal(_uiState.value)

    private fun resolveAiWritingGoal(state: ComposeUiState): AiWritingGoal {
        state.aiSelectedGoalOverride?.takeIf { it.isDraftingGoal }?.let { return it }
        return when (state.mode) {
            ComposeUiState.MODE_REPLY -> AiWritingGoal.REPLY
            ComposeUiState.MODE_REPLY_ALL -> AiWritingGoal.REPLY_ALL
            else -> {
                val editable = if (state.isHtmlMode) {
                    ComposeAiBodyMerger.extractEditableDraftHtml(state.bodyHtml)
                } else {
                    ComposeAiBodyMerger.extractEditableDraftSection(state.bodyText)
                }
                if (state.aiInstructionInput.isNotBlank() && editable.isBlank()) {
                    AiWritingGoal.EXPAND_BULLETS
                } else {
                    AiWritingGoal.NEW_DRAFT
                }
            }
        }
    }

    private fun recomputePromptPreview() {
        _uiState.update { state ->
            state.copy(aiBuiltPromptPreview = buildPromptForState(state))
        }
    }

    private fun buildPromptForState(state: ComposeUiState): AiCompletionRequest {
        val goal = resolveAiWritingGoal(state)
        val editableDraft = if (state.isHtmlMode) {
            ComposeAiBodyMerger.extractEditableRawHtmlSection(state.bodyHtml)
        } else {
            ComposeAiBodyMerger.extractEditableDraftSection(state.bodyText)
        }
        val answers = AiClarificationAnswerReducer.orderedAnswers(
            plan = state.aiClarificationPlan,
            answers = state.aiClarificationAnswers,
        )
        return aiPreparationService?.buildPromptPreview(
            goal = goal,
            tone = state.aiSelectedTone,
            context = state.aiPreparedContext,
            userInstruction = state.aiInstructionInput,
            currentSubject = state.subject,
            currentBodyDraft = editableDraft,
            extraReferenceEmails = state.aiReferenceEmailContexts,
            extraTextDocuments = state.aiReferenceTextDocuments,
            binaryAttachmentDescriptors = state.aiBinaryDescriptorLabels,
            clarificationAnswers = answers,
            preferHtmlOutput = state.aiPreferHtmlOutput,
        ) ?: AiPromptBuilder.buildDraftRequest(
            goal = goal,
            tone = state.aiSelectedTone,
            context = state.aiPreparedContext,
            userInstruction = state.aiInstructionInput,
            currentSubject = state.subject,
            currentBodyDraft = editableDraft,
            customSystemInstruction = "",
            includeSignatureGuidance = true,
            extraReferenceEmails = state.aiReferenceEmailContexts,
            extraTextDocuments = state.aiReferenceTextDocuments,
            binaryAttachmentDescriptors = state.aiBinaryDescriptorLabels,
            clarificationAnswers = answers,
            preferHtmlOutput = state.aiPreferHtmlOutput,
        )
    }

    fun requestAiClarificationQuestions() {
        val service = aiPreparationService ?: return
        aiClarificationJob?.cancel()
        aiClarificationJob = viewModelScope.launch {
            val snapshot = _uiState.value
            val editableDraft = if (snapshot.isHtmlMode) {
                ComposeAiBodyMerger.extractEditableDraftHtml(snapshot.bodyHtml)
            } else {
                ComposeAiBodyMerger.extractEditableDraftSection(snapshot.bodyText)
            }
            val prevAnswers = AiClarificationAnswerReducer.orderedAnswers(
                plan = snapshot.aiClarificationPlan,
                answers = snapshot.aiClarificationAnswers,
            )
            val activeSig = resolveActiveSignatureForCurrentState()
            _uiState.update {
                it.copy(
                    isGeneratingAiQuestions = true,
                    aiClarificationError = null,
                    isConfiguredSignatureActive = !activeSig.isNullOrBlank(),
                )
            }
            try {
                val request = service.buildClarificationPrompt(
                    targetGoal = resolveAiWritingGoal(snapshot),
                    tone = snapshot.aiSelectedTone,
                    context = snapshot.aiPreparedContext,
                    userInstruction = snapshot.aiInstructionInput,
                    currentSubject = snapshot.subject,
                    currentBodyDraft = editableDraft,
                    extraReferenceEmails = snapshot.aiReferenceEmailContexts,
                    extraTextDocuments = snapshot.aiReferenceTextDocuments,
                    binaryAttachmentDescriptors = snapshot.aiBinaryDescriptorLabels,
                    previousAnswers = prevAnswers,
                )
                val plan = service.generateClarificationQuestions(
                    request = request,
                    referenceFiles = snapshot.aiReferenceFiles,
                )
                _uiState.update { state ->
                    val updated = state.copy(
                        isGeneratingAiQuestions = false,
                        aiClarificationPlan = plan,
                        aiClarificationAnswers = emptyMap(),
                        aiClarificationError = null,
                    )
                    updated.copy(aiBuiltPromptPreview = buildPromptForState(updated))
                }
            } catch (ce: CancellationException) {
                _uiState.update { it.copy(isGeneratingAiQuestions = false) }
                throw ce
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        isGeneratingAiQuestions = false,
                        aiClarificationError = t.message ?: "逆質問の作成中にエラーが発生しました",
                    )
                }
            }
        }
    }

    fun cancelAiClarificationQuestions() {
        aiClarificationJob?.cancel()
        aiClarificationJob = null
        _uiState.update { it.copy(isGeneratingAiQuestions = false) }
    }

    fun selectClarificationOption(questionId: String, option: String) {
        val question = _uiState.value.aiClarificationPlan?.questions?.firstOrNull { it.id == questionId }
            ?: return
        _uiState.update { state ->
            val nextAnswers = AiClarificationAnswerReducer.toggleOption(state.aiClarificationAnswers, question, option)
            val updated = state.copy(aiClarificationAnswers = nextAnswers, aiGenerationError = null)
            updated.copy(aiBuiltPromptPreview = buildPromptForState(updated))
        }
    }

    fun updateClarificationCustomInput(questionId: String, input: String) {
        val question = _uiState.value.aiClarificationPlan?.questions?.firstOrNull { it.id == questionId }
            ?: return
        _uiState.update { state ->
            val nextAnswers = AiClarificationAnswerReducer.updateCustomInput(state.aiClarificationAnswers, question, input)
            val updated = state.copy(aiClarificationAnswers = nextAnswers, aiGenerationError = null)
            updated.copy(aiBuiltPromptPreview = buildPromptForState(updated))
        }
    }

    fun clearClarificationQuestions() {
        aiClarificationJob?.cancel()
        aiClarificationJob = null
        _uiState.update { state ->
            val updated = state.copy(
                isGeneratingAiQuestions = false,
                aiClarificationPlan = null,
                aiClarificationAnswers = emptyMap(),
                aiClarificationError = null,
            )
            updated.copy(aiBuiltPromptPreview = buildPromptForState(updated))
        }
    }

    fun generateAiSubjectFromBody() {
        val service = aiPreparationService ?: return
        aiSubjectJob?.cancel()
        aiSubjectJob = viewModelScope.launch {
            val snapshot = _uiState.value
            val editableFromBody = if (snapshot.isHtmlMode) {
                ComposeAiBodyMerger.extractEditableDraftHtml(snapshot.bodyHtml)
            } else {
                ComposeAiBodyMerger.extractEditableDraftSection(snapshot.bodyText)
            }
            val generatedFallback = snapshot.aiGeneratedDraft?.text.orEmpty()
                .let { raw -> if (snapshot.isHtmlMode) ComposeHtmlHelper.htmlToPlainText(raw) else raw.trim() }
            val editableBody = editableFromBody.ifBlank { generatedFallback }
            if (editableBody.isBlank() &&
                snapshot.aiInstructionInput.isBlank() &&
                snapshot.aiPreparedContext == null &&
                snapshot.aiReferenceEmails.isEmpty() &&
                snapshot.aiReferenceFiles.isEmpty()
            ) {
                _uiState.update {
                    it.copy(
                        isGeneratingAiSubject = false,
                        aiSubjectError = "件名を作成するには先に本文（またはAI指示）を入力してください",
                    )
                }
                return@launch
            }
            _uiState.update {
                it.copy(
                    isGeneratingAiSubject = true,
                    aiSubjectError = null,
                )
            }
            try {
                val request = service.buildSubjectFromBodyPrompt(
                    bodyDraft = editableBody.ifBlank { snapshot.aiInstructionInput },
                    currentSubject = snapshot.subject,
                    tone = snapshot.aiSelectedTone,
                    isReplyMode = (snapshot.replyToMetaId ?: 0L) > 0L,
                    context = snapshot.aiPreparedContext,
                    extraReferenceEmails = snapshot.aiReferenceEmailContexts,
                    extraTextDocuments = snapshot.aiReferenceTextDocuments,
                    binaryAttachmentDescriptors = snapshot.aiBinaryDescriptorLabels,
                )
                val result = service.generateSubjectSuggestions(
                    request = request,
                    referenceFiles = snapshot.aiReferenceFiles,
                )
                val beforeSubject = _uiState.value.subject
                val topSubject = result.subjects.first()
                updateDraftContent {
                    copy(
                        subject = topSubject,
                        isGeneratingAiSubject = false,
                        aiSubjectSuggestions = result.subjects,
                        aiSubjectSourceLabel = "${result.providerType.displayLabel} / ${result.modelId}",
                        aiSubjectError = null,
                        previousSubjectBeforeAi = previousSubjectBeforeAi ?: beforeSubject,
                    )
                }
            } catch (ce: CancellationException) {
                _uiState.update { it.copy(isGeneratingAiSubject = false) }
                throw ce
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        isGeneratingAiSubject = false,
                        aiSubjectError = t.message ?: "件名作成中にエラーが発生しました",
                    )
                }
            }
        }
    }

    fun applyAiSubjectCandidate(candidate: String) {
        val before = _uiState.value.subject
        updateDraftContent {
            copy(
                subject = candidate,
                previousSubjectBeforeAi = previousSubjectBeforeAi ?: before,
            )
        }
    }

    fun undoLastAiSubject() {
        val prev = _uiState.value.previousSubjectBeforeAi ?: return
        updateDraftContent {
            copy(
                subject = prev,
                previousSubjectBeforeAi = null,
            )
        }
    }

    fun dismissAiSubjectSuggestions() {
        _uiState.update {
            it.copy(
                aiSubjectSuggestions = emptyList(),
                aiSubjectSourceLabel = null,
                aiSubjectError = null,
            )
        }
    }

    fun openAiEmailPicker() {
        updateAiEmailPickerSearch(_uiState.value.aiEmailPickerSearchQuery)
    }

    fun updateAiEmailPickerSearch(query: String) {
        val service = aiPreparationService ?: return
        _uiState.update { it.copy(aiEmailPickerSearchQuery = query, isSearchingAiEmailPicker = true) }
        aiPickerSearchJob?.cancel()
        aiPickerSearchJob = viewModelScope.launch {
            val accId = _uiState.value.accountId.takeIf { it > 0L } ?: accountId
            val exclude = setOfNotNull(_uiState.value.replyToMetaId ?: replyToMetaId)
            val results = runCatching {
                service.searchReferenceEmails(
                    accountId = accId,
                    query = query,
                    excludeMetaIds = exclude,
                )
            }.getOrDefault(emptyList())
            _uiState.update {
                it.copy(
                    aiEmailPickerCandidates = results,
                    isSearchingAiEmailPicker = false,
                )
            }
        }
    }

    fun toggleAiReferenceEmail(summary: AiReferenceEmailSummary) {
        val current = _uiState.value.aiReferenceEmails
        val exists = current.any { it.metaId == summary.metaId }
        val updatedList = if (exists) {
            current.filterNot { it.metaId == summary.metaId }
        } else {
            if (current.size >= AiPreparationService.MAX_EXTRA_REFERENCE_EMAILS) {
                _uiState.update {
                    it.copy(aiReferenceWarning = "追加参照メールは最大 ${AiPreparationService.MAX_EXTRA_REFERENCE_EMAILS} 通までです")
                }
                return
            }
            current + summary
        }
        applyUpdatedReferenceEmails(updatedList)
    }

    fun removeAiReferenceEmail(metaId: Long) {
        val updatedList = _uiState.value.aiReferenceEmails.filterNot { it.metaId == metaId }
        applyUpdatedReferenceEmails(updatedList)
    }

    private fun applyUpdatedReferenceEmails(updatedList: List<AiReferenceEmailSummary>) {
        val service = aiPreparationService
        _uiState.update { it.copy(aiReferenceEmails = updatedList, aiReferenceWarning = null) }
        if (service == null) return
        viewModelScope.launch {
            val contexts = runCatching {
                service.prepareExtraEmailContexts(
                    metaIds = updatedList.map { it.metaId },
                    fallbackSelfEmail = _uiState.value.fromEmail,
                )
            }.getOrDefault(emptyList())
            _uiState.update { state ->
                val updated = state.copy(aiReferenceEmailContexts = contexts)
                updated.copy(aiBuiltPromptPreview = buildPromptForState(updated))
            }
        }
    }

    fun addAiReferencePickedUris(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val service = aiPreparationService ?: return
        viewModelScope.launch {
            val staged = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    runCatching { AiReferenceStaging.stageUri(context, uri) }.getOrNull()
                }
            }
            if (staged.isEmpty()) {
                _uiState.update { it.copy(aiReferenceWarning = "選択したファイルを読み込めませんでした") }
                return@launch
            }
            mergeNewReferenceFiles(staged, service)
        }
    }

    fun toggleDraftAttachmentForAi(context: Context, filePath: String) {
        val service = aiPreparationService ?: return
        val customId = "draft:$filePath"
        if (_uiState.value.aiReferenceFiles.any { it.id == customId }) {
            removeAiReferenceFile(customId)
            return
        }
        val draftAtt = _uiState.value.attachments.firstOrNull { it.filePath == filePath } ?: return
        viewModelScope.launch {
            val staged = withContext(Dispatchers.IO) {
                runCatching {
                    AiReferenceStaging.stageLocalFile(
                        context = context,
                        sourceFile = File(draftAtt.filePath),
                        displayName = draftAtt.filename,
                        rawMimeType = draftAtt.mimeType,
                        customId = customId,
                    )
                }.getOrNull()
            }
            if (staged == null) {
                _uiState.update { it.copy(aiReferenceWarning = "添付ファイルを開けませんでした: ${draftAtt.filename}") }
                return@launch
            }
            mergeNewReferenceFiles(listOf(staged), service)
        }
    }

    fun removeAiReferenceFile(fileId: String) {
        val service = aiPreparationService ?: return
        val updatedFiles = _uiState.value.aiReferenceFiles.filterNot { it.id == fileId }
        viewModelScope.launch {
            val bundle = service.prepareFileReferenceBundle(updatedFiles)
            _uiState.update { state ->
                val updated = state.copy(
                    aiReferenceFiles = updatedFiles,
                    aiReferenceTextDocuments = bundle.inlineTextDocuments,
                    aiBinaryDescriptorLabels = bundle.binaryDescriptorLabels,
                    aiReferenceWarning = null,
                )
                updated.copy(aiBuiltPromptPreview = buildPromptForState(updated))
            }
        }
    }

    private suspend fun mergeNewReferenceFiles(
        newFiles: List<AiReferenceFileSpec>,
        service: AiPreparationService,
    ) {
        val current = _uiState.value.aiReferenceFiles.toMutableList()
        var warning: String? = null
        var binaryCount = current.count { it.kind.isBinarySupported }
        for (item in newFiles) {
            if (!item.kind.isSupportedForAi) {
                warning = item.warningNote ?: "${item.displayName} は非対応の形式です"
                continue
            }
            if (item.kind.isBinarySupported && binaryCount >= AndroidAiBinaryMediaEncoder.MAX_BINARY_FILES) {
                warning = "画像・PDF は最大 ${AndroidAiBinaryMediaEncoder.MAX_BINARY_FILES} 件まで追加できます"
                continue
            }
            if (current.none { it.id == item.id }) {
                current += item
                if (item.kind.isBinarySupported) binaryCount++
            }
        }
        val bundle = service.prepareFileReferenceBundle(current)
        val combinedWarning = warning ?: bundle.skippedNotes.firstOrNull()
        _uiState.update { state ->
            val updated = state.copy(
                aiReferenceFiles = current,
                aiReferenceTextDocuments = bundle.inlineTextDocuments,
                aiBinaryDescriptorLabels = bundle.binaryDescriptorLabels,
                aiReferenceWarning = combinedWarning,
            )
            updated.copy(aiBuiltPromptPreview = buildPromptForState(updated))
        }
    }

    private data class QuadRefInit(
        val primaryContext: AiPreparedEmailContext?,
        val refEmails: List<AiReferenceEmailSummary>,
        val refEmailContexts: List<AiPreparedEmailContext>,
        val refFiles: List<AiReferenceFileSpec>,
        val refBundle: com.kazemail.app.ai.AiPreparedReferenceBundle,
    )

    fun generateAiDraft() {
        val service = aiPreparationService ?: return
        aiGenerationJob?.cancel()
        aiGenerationJob = viewModelScope.launch {
            val snapshot = _uiState.value
            val goal = resolveAiWritingGoal(snapshot)
            val editableDraft = if (snapshot.isHtmlMode) {
                ComposeAiBodyMerger.extractEditableDraftHtml(snapshot.bodyHtml)
            } else {
                ComposeAiBodyMerger.extractEditableDraftSection(snapshot.bodyText)
            }
            if (goal == AiWritingGoal.REWRITE_TONE && editableDraft.isBlank()) {
                _uiState.update {
                    it.copy(
                        isGeneratingAiDraft = false,
                        aiGenerationError = "トーン調整する本文が入力されていません",
                    )
                }
                return@launch
            }
            if (goal == AiWritingGoal.NEW_DRAFT &&
                snapshot.aiInstructionInput.isBlank() &&
                snapshot.subject.isBlank() &&
                editableDraft.isBlank() &&
                snapshot.aiPreparedContext == null &&
                snapshot.aiReferenceEmails.isEmpty() &&
                snapshot.aiReferenceFiles.isEmpty() &&
                snapshot.aiClarificationAnswers.values.none { it.hasAnswer }
            ) {
                _uiState.update {
                    it.copy(
                        isGeneratingAiDraft = false,
                        aiGenerationError = "件名・指示メモ、逆質問への回答、または参照メール/資料を入力してください",
                    )
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    isGeneratingAiDraft = true,
                    aiGenerationError = null,
                    aiBuiltPromptPreview = buildPromptForState(it),
                )
            }

            try {
                val draftSnapshot = _uiState.value
                val request = buildPromptForState(draftSnapshot)
                val result = service.generateCompletion(
                    request = request,
                    referenceFiles = draftSnapshot.aiReferenceFiles,
                )
                _uiState.update {
                    it.copy(
                        isGeneratingAiDraft = false,
                        aiGeneratedDraft = result,
                        aiGeneratedDraftIsHtml = draftSnapshot.aiPreferHtmlOutput,
                        aiGenerationError = null,
                    )
                }
            } catch (ce: CancellationException) {
                _uiState.update { it.copy(isGeneratingAiDraft = false) }
                throw ce
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        isGeneratingAiDraft = false,
                        aiGenerationError = t.message ?: "AI 生成中にエラーが発生しました",
                    )
                }
            }
        }
    }

    fun cancelAiGeneration() {
        aiGenerationJob?.cancel()
        aiGenerationJob = null
        _uiState.update { it.copy(isGeneratingAiDraft = false) }
    }

    fun applyGeneratedAiDraft(append: Boolean) {
        val current = _uiState.value
        val generated = current.aiGeneratedDraft ?: return
        val activeSig = resolveActiveSignatureForCurrentState()
        val beforeText = current.bodyText
        val beforeHtml = current.bodyHtml
        val beforeIsHtml = current.isHtmlMode
        val treatAsHtml = current.aiGeneratedDraftIsHtml || HtmlEmailDetector.containsActualHtml(generated.text)
        val targetIsHtmlMode = beforeIsHtml || treatAsHtml

        if (targetIsHtmlMode) {
            val baseHtml = if (beforeIsHtml) {
                beforeHtml
            } else {
                ComposeHtmlHelper.plainTextToHtmlFragment(beforeText)
            }
            val htmlInput = if (treatAsHtml) {
                generated.text
            } else {
                val mergedPlainOnly = ComposeAiBodyMerger.mergeGeneratedBody(
                    currentBody = "",
                    generatedBody = generated.text,
                    appendToExisting = false,
                    configuredSignature = if (current.includeSignature) activeSig else null,
                )
                val cleanPlainOnly = ComposeAiBodyMerger.extractEditableDraftSection(mergedPlainOnly)
                ComposeHtmlHelper.plainTextToHtmlFragment(cleanPlainOnly)
            }
            val mergedHtml = ComposeAiBodyMerger.mergeGeneratedHtmlBody(
                currentHtml = baseHtml,
                generatedHtml = htmlInput,
                appendToExisting = append,
                configuredSignature = null,
            )
            // Keep bodyHtml focused cleanly on the editable draft only (signature/quote stay separated)
            val cleanDraftHtml = ComposeHtmlHelper.splitHtmlDraftSignatureQuote(mergedHtml).first
            val cleanDraftPlain = ComposeHtmlHelper.htmlToPlainText(cleanDraftHtml)
            updateDraftContent {
                copy(
                    isHtmlMode = true,
                    hasManuallySelectedFormat = if (!beforeIsHtml) true else hasManuallySelectedFormat,
                    bodyText = cleanDraftPlain,
                    bodyHtml = cleanDraftHtml,
                    htmlSnapshotText = cleanDraftPlain,
                    previousBodyBeforeAi = beforeText,
                    previousBodyHtmlBeforeAi = beforeHtml,
                    previousIsHtmlModeBeforeAi = beforeIsHtml,
                    activeRawSignature = activeSig,
                    isConfiguredSignatureActive = current.includeSignature && activeSig != null,
                    aiGenerationError = null,
                )
            }
        } else {
            val mergedPlain = ComposeAiBodyMerger.mergeGeneratedBody(
                currentBody = beforeText,
                generatedBody = generated.text,
                appendToExisting = append,
                configuredSignature = if (current.includeSignature) activeSig else null,
            )
            val cleanDraftPlain = ComposeAiBodyMerger.extractEditableDraftSection(mergedPlain)
            updateDraftContent {
                copy(
                    isHtmlMode = false,
                    bodyText = cleanDraftPlain,
                    htmlSnapshotText = null,
                    previousBodyBeforeAi = beforeText,
                    previousBodyHtmlBeforeAi = null,
                    previousIsHtmlModeBeforeAi = beforeIsHtml,
                    activeRawSignature = activeSig,
                    isConfiguredSignatureActive = current.includeSignature && activeSig != null,
                    aiGenerationError = null,
                )
            }
        }
    }

    fun undoLastAiApply() {
        val current = _uiState.value
        val prevText = current.previousBodyBeforeAi
        val prevHtml = current.previousBodyHtmlBeforeAi
        val prevIsHtml = current.previousIsHtmlModeBeforeAi
        if (prevText == null && prevHtml == null) return
        val restoredIsHtml = prevIsHtml ?: current.isHtmlMode
        updateDraftContent {
            copy(
                isHtmlMode = restoredIsHtml,
                bodyText = prevText ?: bodyText,
                bodyHtml = prevHtml ?: bodyHtml,
                htmlSnapshotText = if (restoredIsHtml) (prevText ?: bodyText) else null,
                previousBodyBeforeAi = null,
                previousBodyHtmlBeforeAi = null,
                previousIsHtmlModeBeforeAi = null,
            )
        }
    }

    fun attachPickedUris(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val copied = withContext(Dispatchers.IO) {
                val rootDir = File(context.filesDir, "composer_attachments").apply { mkdirs() }
                uris.mapNotNull { uri ->
                    runCatching { copyUriToLocalCache(context, uri, rootDir) }.getOrNull()
                }
            }
            if (copied.isNotEmpty()) {
                updateDraftContent { copy(attachments = attachments + copied) }
            } else {
                _uiState.update { it.copy(errorMessage = "添付ファイルの読み込みに失敗しました") }
            }
        }
    }

    fun removeAttachment(index: Int) {
        updateDraftContent {
            val updated = attachments.toMutableList().also {
                if (index in it.indices) it.removeAt(index)
            }
            copy(attachments = updated)
        }
    }

    private fun updateDraftContent(transform: ComposeUiState.() -> ComposeUiState) {
        var snapshot: DraftAutoSaveSnapshot? = null
        _uiState.update { prev ->
            val next = prev.transform().copy(
                errorMessage = null,
                draftSaveStatus = "ローカル下書き保存予定…",
            )
            snapshot = DraftAutoSaveSnapshot(
                toInput = next.toInput,
                ccInput = next.ccInput,
                bccInput = next.bccInput,
                subject = next.subject,
                bodyText = next.bodyText,
                isHtml = next.isHtmlMode,
                bodyHtml = next.bodyHtml,
                includeSignature = next.includeSignature,
                effectiveSignatureRaw = next.effectiveSignatureRaw,
                includeQuote = next.includeQuote,
                quotePlain = next.quotePlain,
                quoteHtml = next.quoteHtml,
                attachments = next.attachments,
            )
            next
        }
        snapshot?.let { autoSaveTrigger.value = it }
    }

    private suspend fun saveDraftInternal(snap: DraftAutoSaveSnapshot) {
        val s = _uiState.value
        if (s.accountId <= 0L) return
        val toParsed = ComposeHelper.parseAddressInput(snap.toInput).valid
        val ccParsed = ComposeHelper.parseAddressInput(snap.ccInput).valid
        val bccParsed = ComposeHelper.parseAddressInput(snap.bccInput).valid
        val combinedPlainBody = ComposeHtmlHelper.composePlainBody(
            editableBody = if (snap.isHtml) ComposeHtmlHelper.htmlToPlainText(snap.bodyHtml) else snap.bodyText,
            signatureRaw = snap.effectiveSignatureRaw,
            includeSignature = snap.includeSignature,
            quotePlain = snap.quotePlain,
            includeQuote = snap.includeQuote,
        )
        val combinedHtmlBody = if (snap.isHtml) {
            val quoteHtml = snap.quoteHtml ?: snap.quotePlain
                .takeIf { it.isNotBlank() }
                ?.let { ComposeHtmlHelper.convertPlainComposeBodyToHtml(it) }
            ComposeHtmlHelper.composeHtmlBody(
                editableHtml = snap.bodyHtml,
                signatureRaw = snap.effectiveSignatureRaw,
                includeSignature = snap.includeSignature,
                quoteHtml = quoteHtml,
                includeQuote = snap.includeQuote,
            )
        } else {
            null
        }
        runCatching {
            outboxManager.saveDraft(
                draftId = s.draftId,
                accountId = s.accountId,
                replyToMetaId = s.replyToMetaId,
                mode = s.mode,
                to = toParsed,
                cc = ccParsed,
                bcc = bccParsed,
                subject = snap.subject,
                bodyText = combinedPlainBody,
                inReplyTo = s.inReplyTo,
                references = s.references,
                attachments = snap.attachments,
                isHtml = snap.isHtml,
                bodyHtml = combinedHtmlBody,
            )
        }.onSuccess { savedId ->
            val timeLabel = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            _uiState.update {
                it.copy(
                    draftId = savedId,
                    draftSaveStatus = "ローカル下書き保存済み ($timeLabel)",
                )
            }
        }.onFailure { err ->
            _uiState.update {
                it.copy(draftSaveStatus = "下書き保存エラー: ${err.message}")
            }
        }
    }

    fun flushDraftAndNavigateBack(onDone: () -> Unit) {
        val s = _uiState.value
        val snap = DraftAutoSaveSnapshot(
            toInput = s.toInput,
            ccInput = s.ccInput,
            bccInput = s.bccInput,
            subject = s.subject,
            bodyText = s.bodyText,
            isHtml = s.isHtmlMode,
            bodyHtml = s.bodyHtml,
            includeSignature = s.includeSignature,
            effectiveSignatureRaw = s.effectiveSignatureRaw,
            includeQuote = s.includeQuote,
            quotePlain = s.quotePlain,
            quoteHtml = s.quoteHtml,
            attachments = s.attachments,
        )
        if (!snap.hasAnyContent() || s.queuedAndShouldClose || s.discardedAndShouldClose) {
            onDone()
            return
        }
        viewModelScope.launch {
            saveDraftInternal(snap)
            onDone()
        }
    }

    fun discardDraft() {
        val currentDraftId = _uiState.value.draftId
        viewModelScope.launch {
            if (currentDraftId != null && currentDraftId > 0L) {
                runCatching { outboxManager.deleteDraft(currentDraftId) }
            }
            _uiState.update { it.copy(discardedAndShouldClose = true) }
        }
    }

    fun queueSendToOutbox() {
        val s = _uiState.value
        if (s.isSending) return

        val toResult = ComposeHelper.parseAddressInput(s.toInput)
        val ccResult = ComposeHelper.parseAddressInput(s.ccInput)
        val bccResult = ComposeHelper.parseAddressInput(s.bccInput)

        val invalidTokens = toResult.invalid + ccResult.invalid + bccResult.invalid
        if (invalidTokens.isNotEmpty()) {
            _uiState.update {
                it.copy(errorMessage = "メールアドレス形式が不正です: ${invalidTokens.joinToString(", ")}")
            }
            return
        }

        if (toResult.valid.isEmpty() && ccResult.valid.isEmpty() && bccResult.valid.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "宛先（To / Cc / Bcc）を1件以上入力してください") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSending = true, errorMessage = null) }
            val effectivePlainBody = buildCombinedPlainBody(s)
            val effectiveHtmlBody = if (s.isHtmlMode) {
                buildCombinedHtmlBody(s).takeIf { it.isNotBlank() }
            } else {
                null
            }
            runCatching {
                outboxManager.enqueueSend(
                    accountId = s.accountId,
                    draftId = s.draftId,
                    to = toResult.valid,
                    cc = ccResult.valid,
                    bcc = bccResult.valid,
                    subject = s.subject.ifBlank { "(件名なし)" },
                    bodyText = effectivePlainBody,
                    inReplyTo = s.inReplyTo,
                    references = s.references,
                    attachments = s.attachments,
                    bodyHtml = effectiveHtmlBody,
                )
                onEnqueueOutboxWorker()
            }.onSuccess {
                _uiState.update { it.copy(isSending = false, queuedAndShouldClose = true) }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isSending = false,
                        errorMessage = "Outbox投入エラー: ${err.message}",
                    )
                }
            }
        }
    }

    private fun copyUriToLocalCache(
        context: Context,
        uri: Uri,
        rootDir: File,
    ): DraftAttachmentRef {
        val resolver = context.contentResolver
        var displayName = "attachment.bin"
        var reportedSize = -1L

        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) {
                        cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { displayName = it }
                    }
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        reportedSize = cursor.getLong(sizeIdx)
                    }
                }
            }

        val mimeType = resolver.getType(uri) ?: "application/octet-stream"
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val destFile = File(rootDir, "${UUID.randomUUID()}_$safeName")

        resolver.openInputStream(uri)?.use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: error("Cannot open URI input stream")

        val actualSize = if (reportedSize > 0L) reportedSize else destFile.length()
        return DraftAttachmentRef(
            filePath = destFile.absolutePath,
            filename = displayName,
            mimeType = mimeType,
            sizeBytes = actualSize,
        )
    }

    companion object {
        const val AUTO_SAVE_DEBOUNCE_MS = 500L

        fun factory(
            container: KazeMailContainer,
            accountId: Long,
            draftId: Long?,
            replyToMetaId: Long?,
            mode: String,
            initialAiTone: String? = null,
            initialAiInstruction: String? = null,
            initialAiPreferHtml: Boolean? = null,
            autoTriggerAi: Boolean = false,
            externalPrefill: ComposeExternalPrefill? = null,
            context: Context? = null,
            initialAiReferenceMetaIds: List<Long> = emptyList(),
            initialAiReferenceFiles: List<AiReferenceFileSpec> = emptyList(),
            initialAiClarificationPlan: AiClarificationPlan? = null,
            initialAiClarificationAnswers: Map<String, AiClarificationAnswer> = emptyMap(),
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ComposeViewModel(
                    container = container,
                    accountId = accountId,
                    draftId = draftId,
                    replyToMetaId = replyToMetaId,
                    mode = mode,
                    initialAiTone = initialAiTone,
                    initialAiInstruction = initialAiInstruction,
                    initialAiPreferHtml = initialAiPreferHtml,
                    autoTriggerAi = autoTriggerAi,
                    externalPrefill = externalPrefill,
                    context = context,
                    initialAiReferenceMetaIds = initialAiReferenceMetaIds,
                    initialAiReferenceFiles = initialAiReferenceFiles,
                    initialAiClarificationPlan = initialAiClarificationPlan,
                    initialAiClarificationAnswers = initialAiClarificationAnswers,
                ) as T
        }
    }
}
