package com.kazemail.app.ui.viewer

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kazemail.app.ai.AiClarificationAnswer
import com.kazemail.app.ai.AiClarificationAnswerReducer
import com.kazemail.app.ai.AiClarificationPlan
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
import com.kazemail.app.data.AttachmentMetadataHelper
import com.kazemail.app.data.LoadedMessageDetail
import com.kazemail.app.data.db.AiWritingPresetEntity
import com.kazemail.app.di.KazeMailContainer
import com.kazemail.app.export.LocalArchiveExporter
import com.kazemail.app.sync.Rfc2047HeaderDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class RecipientDisplay(
    val name: String? = null,
    val address: String,
)

data class MessageCardUiState(
    val detail: LoadedMessageDetail,
    val recipients: List<RecipientDisplay>,
    val ccRecipients: List<RecipientDisplay> = emptyList(),
    val isExpanded: Boolean,
    val showHeaderDetails: Boolean = false,
    val canRenderHtml: Boolean = false,
    val showHtmlMode: Boolean = false,
    val decompressedHtml: String? = null,
    val isDecompressingHtml: Boolean = false,
    val allowExternalImages: Boolean = false,
    val blockedExternalResources: Boolean = false,
    val showRawHeaders: Boolean = false,
    val expandedQuoteIndices: Set<Int> = emptySet(),
    val downloadingAttachmentIds: Set<Long> = emptySet(),
)

data class MessageDetailUiState(
    val isLoading: Boolean = true,
    val subject: String = "",
    val cards: List<MessageCardUiState> = emptyList(),
    val errorMessage: String? = null,
    val infoBanner: String? = null,
    val archivedAndShouldClose: Boolean = false,
    val aiReadiness: AiReadinessState = AiReadinessState(
        status = AiReadinessStatus.FEATURE_DISABLED,
        summaryLabel = "AI準備状態を確認中…",
        actionHint = "",
    ),
    val aiPresets: List<AiWritingPresetEntity> = emptyList(),
    val isPreparingAiContext: Boolean = false,
    val aiPreparedContext: AiPreparedEmailContext? = null,
    val aiSelectedTone: AiReplyTone = AiReplyTone.FORMAL_BUSINESS,
    val aiPreferHtmlOutput: Boolean = false,
    val aiInstructionInput: String = "",
    val aiBuiltPromptPreview: AiCompletionRequest? = null,
    val isSummarizingAi: Boolean = false,
    val aiSummaryResult: AiCompletionResult? = null,
    val aiSummaryError: String? = null,
    val aiSummaryContext: AiPreparedEmailContext? = null,
    val aiSummaryExtraEmailCount: Int = 0,
    val aiSummaryExtraFileCount: Int = 0,
    val aiReferenceEmails: List<AiReferenceEmailSummary> = emptyList(),
    val aiReferenceEmailContexts: List<AiPreparedEmailContext> = emptyList(),
    val aiReferenceFiles: List<AiReferenceFileSpec> = emptyList(),
    val aiReferenceTextDocuments: List<AiPreparedTextDocument> = emptyList(),
    val aiBinaryDescriptorLabels: List<String> = emptyList(),
    val aiReferenceWarning: String? = null,
    val aiEmailPickerSearchQuery: String = "",
    val aiEmailPickerCandidates: List<AiReferenceEmailSummary> = emptyList(),
    val isSearchingAiEmailPicker: Boolean = false,
    val downloadingAiAttachmentIds: Set<Long> = emptySet(),
    val isGeneratingAiQuestions: Boolean = false,
    val aiClarificationPlan: AiClarificationPlan? = null,
    val aiClarificationAnswers: Map<String, AiClarificationAnswer> = emptyMap(),
    val aiClarificationError: String? = null,
)

class MessageDetailViewModel(
    private val container: KazeMailContainer,
    private val accountId: Long,
    private val metaId: Long,
    private val threadId: String?,
    private val isThreadMode: Boolean,
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }
    private val _uiState = MutableStateFlow(
        MessageDetailUiState(
            aiSelectedTone = container.appPreferences.aiDefaultTone.value,
            aiPreferHtmlOutput = container.appPreferences.aiDefaultPreferHtml.value,
        ),
    )
    val uiState: StateFlow<MessageDetailUiState> = _uiState.asStateFlow()
    private var aiSummaryJob: Job? = null
    private var aiPickerSearchJob: Job? = null
    private var aiClarificationJob: Job? = null

    init {
        load()
        observeAiState()
    }

    fun load() {
        viewModelScope.launch {
            runCatching {
                val autoShowHtml = container.appPreferences.autoShowHtml.value
                val defaultAllowExternal = !container.appPreferences.defaultBlockExternalImages.value
                val collapseQuotes = container.appPreferences.collapseQuotesByDefault.value
                val items = container.messageRepository.loadConversation(
                    accountId = accountId,
                    metaId = metaId,
                    threadId = threadId,
                    loadFullThread = isThreadMode,
                )
                container.messageRepository.markConversationRead(items.map { it.meta })
                items.mapIndexed { idx, item ->
                    val isTargetOrLast = item.meta.id == metaId || idx == items.lastIndex
                    val initialExpandedQuotes = if (collapseQuotes) {
                        emptySet()
                    } else {
                        val plain = item.body?.plainText ?: item.meta.snippet ?: ""
                        MessageTextFormatter.splitQuotedContent(plain).indices.toSet()
                    }
                    val zstdBytes = item.body?.htmlZstd
                    val rawPlain = item.body?.plainText ?: ""
                    val plainLooksHtml = zstdBytes == null && HtmlEmailDetector.looksLikeRawHtmlDocument(rawPlain)

                    val resolvedHtml: String? = if (zstdBytes != null) {
                        val decoded = withContext(Dispatchers.IO) {
                            runCatching {
                                container.messageRepository.decompressHtmlInMemory(zstdBytes)
                            }.getOrNull()
                        }
                        decoded?.takeIf { HtmlEmailDetector.containsActualHtml(it) }
                    } else if (plainLooksHtml) {
                        rawPlain
                    } else {
                        null
                    }

                    val decodedMeta = item.meta.copy(
                        isRead = true,
                        senderName = item.meta.senderName?.let { Rfc2047HeaderDecoder.decodeIfNeeded(it) },
                        subject = item.meta.subject?.let { Rfc2047HeaderDecoder.decodeIfNeeded(it) },
                    )
                    val canRenderHtml = resolvedHtml != null
                    MessageCardUiState(
                        detail = item.copy(meta = decodedMeta),
                        recipients = parseRecipients(item.meta.recipientTo),
                        ccRecipients = parseCcFromRawHeaders(item.body?.rawHeaders),
                        isExpanded = isTargetOrLast,
                        canRenderHtml = canRenderHtml,
                        showHtmlMode = canRenderHtml && autoShowHtml,
                        decompressedHtml = resolvedHtml,
                        allowExternalImages = defaultAllowExternal,
                        expandedQuoteIndices = initialExpandedQuotes,
                    )
                }
            }.onSuccess { cards ->
                val subj = cards.lastOrNull()?.detail?.meta?.subject
                    ?.let { Rfc2047HeaderDecoder.decodeIfNeeded(it) }
                    ?: "(件名なし)"
                _uiState.update { current ->
                    current.copy(
                        isLoading = false,
                        subject = subj,
                        cards = cards,
                        errorMessage = null,
                    )
                }
            }.onFailure { err ->
                _uiState.update { current ->
                    current.copy(
                        isLoading = false,
                        errorMessage = "本文読み込みエラー: ${err.message}",
                    )
                }
            }
        }
    }

    fun toggleCardExpanded(targetMetaId: Long) {
        val current = _uiState.value.cards.firstOrNull { it.detail.meta.id == targetMetaId } ?: return
        val willExpand = !current.isExpanded
        updateCard(targetMetaId) { it.copy(isExpanded = !it.isExpanded) }
        if (
            willExpand &&
            container.appPreferences.autoShowHtml.value &&
            current.canRenderHtml &&
            !current.showHtmlMode
        ) {
            toggleHtmlMode(targetMetaId)
        }
    }

    fun toggleQuote(targetMetaId: Long, segmentIndex: Int) {
        updateCard(targetMetaId) { card ->
            val next = if (segmentIndex in card.expandedQuoteIndices) {
                card.expandedQuoteIndices - segmentIndex
            } else {
                card.expandedQuoteIndices + segmentIndex
            }
            card.copy(expandedQuoteIndices = next)
        }
    }

    fun toggleRawHeaders(targetMetaId: Long) {
        updateCard(targetMetaId) { it.copy(showRawHeaders = !it.showRawHeaders) }
    }

    fun toggleHeaderDetails(targetMetaId: Long) {
        updateCard(targetMetaId) { it.copy(showHeaderDetails = !it.showHeaderDetails) }
    }

    private fun observeAiState() {
        viewModelScope.launch {
            container.awaitDatabase()
            val service = container.aiPreparationService
            runCatching { service.ensureDefaultPresetsSeeded() }
            launch {
                service.observeReadinessState().collect { readiness ->
                    _uiState.update { it.copy(aiReadiness = readiness) }
                }
            }
            launch {
                service.observePresets().collect { presets ->
                    _uiState.update { it.copy(aiPresets = presets) }
                }
            }
        }
    }

    fun prepareAiReplySheet() {
        viewModelScope.launch {
            _uiState.update { it.copy(isPreparingAiContext = true) }
            runCatching {
                container.awaitDatabase()
                container.aiPreparationService.prepareEmailContext(
                    accountId = accountId,
                    metaId = metaId,
                    threadId = threadId,
                    loadFullThread = isThreadMode,
                )
            }.onSuccess { context ->
                _uiState.update { state ->
                    val updated = state.copy(
                        isPreparingAiContext = false,
                        aiPreparedContext = context,
                    )
                    updated.copy(aiBuiltPromptPreview = buildPreviewPrompt(updated))
                }
            }.onFailure {
                _uiState.update { it.copy(isPreparingAiContext = false) }
            }
        }
    }

    fun selectAiTone(tone: AiReplyTone) {
        _uiState.update { state ->
            val updated = state.copy(aiSelectedTone = tone)
            updated.copy(aiBuiltPromptPreview = buildPreviewPrompt(updated))
        }
    }

    fun selectAiPreferHtmlOutput(preferHtml: Boolean) {
        _uiState.update { state ->
            val updated = state.copy(aiPreferHtmlOutput = preferHtml)
            updated.copy(aiBuiltPromptPreview = buildPreviewPrompt(updated))
        }
    }

    fun applyAiPreset(preset: AiWritingPresetEntity) {
        val tone = AiReplyTone.fromStorage(preset.tone)
        _uiState.update { state ->
            val updated = state.copy(
                aiSelectedTone = tone,
                aiInstructionInput = preset.instructionPrompt,
            )
            updated.copy(aiBuiltPromptPreview = buildPreviewPrompt(updated))
        }
    }

    fun updateAiInstruction(instruction: String) {
        _uiState.update { state ->
            val updated = state.copy(aiInstructionInput = instruction)
            updated.copy(aiBuiltPromptPreview = buildPreviewPrompt(updated))
        }
    }

    private fun buildPreviewPrompt(state: MessageDetailUiState): AiCompletionRequest =
        AiPromptBuilder.buildDraftRequest(
            goal = AiWritingGoal.REPLY,
            tone = state.aiSelectedTone,
            context = state.aiPreparedContext,
            userInstruction = state.aiInstructionInput,
            currentSubject = "Re: ${state.subject}",
            currentBodyDraft = "",
            customSystemInstruction = container.appPreferences.aiCustomSystemInstruction.value,
            includeSignatureGuidance = container.appPreferences.aiIncludeSignatureGuidance.value,
            extraReferenceEmails = state.aiReferenceEmailContexts,
            extraTextDocuments = state.aiReferenceTextDocuments,
            binaryAttachmentDescriptors = state.aiBinaryDescriptorLabels,
            clarificationAnswers = AiClarificationAnswerReducer.orderedAnswers(
                plan = state.aiClarificationPlan,
                answers = state.aiClarificationAnswers,
            ),
            preferHtmlOutput = state.aiPreferHtmlOutput,
        )

    fun requestAiClarificationQuestions() {
        aiClarificationJob?.cancel()
        aiClarificationJob = viewModelScope.launch {
            val snapshot = _uiState.value
            val prevAnswers = AiClarificationAnswerReducer.orderedAnswers(
                plan = snapshot.aiClarificationPlan,
                answers = snapshot.aiClarificationAnswers,
            )
            _uiState.update { it.copy(isGeneratingAiQuestions = true, aiClarificationError = null) }
            try {
                container.awaitDatabase()
                val service = container.aiPreparationService
                val ctx = snapshot.aiPreparedContext ?: service.prepareEmailContext(
                    accountId = accountId,
                    metaId = metaId,
                    threadId = threadId,
                    loadFullThread = isThreadMode,
                )
                val request = service.buildClarificationPrompt(
                    targetGoal = AiWritingGoal.REPLY,
                    tone = snapshot.aiSelectedTone,
                    context = ctx,
                    userInstruction = snapshot.aiInstructionInput,
                    currentSubject = "Re: ${snapshot.subject}",
                    currentBodyDraft = "",
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
                        aiPreparedContext = ctx,
                        isGeneratingAiQuestions = false,
                        aiClarificationPlan = plan,
                        aiClarificationAnswers = emptyMap(),
                        aiClarificationError = null,
                    )
                    updated.copy(aiBuiltPromptPreview = buildPreviewPrompt(updated))
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
            val updated = state.copy(aiClarificationAnswers = nextAnswers)
            updated.copy(aiBuiltPromptPreview = buildPreviewPrompt(updated))
        }
    }

    fun updateClarificationCustomInput(questionId: String, input: String) {
        val question = _uiState.value.aiClarificationPlan?.questions?.firstOrNull { it.id == questionId }
            ?: return
        _uiState.update { state ->
            val nextAnswers = AiClarificationAnswerReducer.updateCustomInput(state.aiClarificationAnswers, question, input)
            val updated = state.copy(aiClarificationAnswers = nextAnswers)
            updated.copy(aiBuiltPromptPreview = buildPreviewPrompt(updated))
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
            updated.copy(aiBuiltPromptPreview = buildPreviewPrompt(updated))
        }
    }

    fun openAiEmailPicker() {
        updateAiEmailPickerSearch(_uiState.value.aiEmailPickerSearchQuery)
    }

    fun updateAiEmailPickerSearch(query: String) {
        _uiState.update { it.copy(aiEmailPickerSearchQuery = query, isSearchingAiEmailPicker = true) }
        aiPickerSearchJob?.cancel()
        aiPickerSearchJob = viewModelScope.launch {
            val excludeIds = buildSet {
                add(metaId)
                _uiState.value.cards.forEach { add(it.detail.meta.id) }
            }
            val candidates = runCatching {
                container.awaitDatabase()
                container.aiPreparationService.searchReferenceEmails(
                    accountId = accountId,
                    query = query,
                    excludeMetaIds = excludeIds,
                )
            }.getOrDefault(emptyList())
            _uiState.update {
                it.copy(
                    aiEmailPickerCandidates = candidates,
                    isSearchingAiEmailPicker = false,
                )
            }
        }
    }

    fun toggleAiReferenceEmail(summary: AiReferenceEmailSummary) {
        val current = _uiState.value.aiReferenceEmails
        val updated = if (current.any { it.metaId == summary.metaId }) {
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
        applyUpdatedReferenceEmails(updated)
    }

    fun removeAiReferenceEmail(refMetaId: Long) {
        val updated = _uiState.value.aiReferenceEmails.filterNot { it.metaId == refMetaId }
        applyUpdatedReferenceEmails(updated)
    }

    private fun applyUpdatedReferenceEmails(updatedList: List<AiReferenceEmailSummary>) {
        _uiState.update { it.copy(aiReferenceEmails = updatedList, aiReferenceWarning = null) }
        viewModelScope.launch {
            val contexts = runCatching {
                container.awaitDatabase()
                val acc = container.awaitDatabase().accountDao().getById(accountId)
                container.aiPreparationService.prepareExtraEmailContexts(
                    metaIds = updatedList.map { it.metaId },
                    fallbackSelfEmail = acc?.emailAddress.orEmpty(),
                )
            }.getOrDefault(emptyList())
            _uiState.update { state ->
                val updated = state.copy(aiReferenceEmailContexts = contexts)
                updated.copy(aiBuiltPromptPreview = buildPreviewPrompt(updated))
            }
        }
    }

    fun addAiReferencePickedUris(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
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
            mergeNewReferenceFiles(staged)
        }
    }

    fun toggleConversationAttachmentForAi(
        context: Context,
        attachmentId: Long,
        attachmentsRootDir: File,
    ) {
        val customId = "att:$attachmentId"
        if (_uiState.value.aiReferenceFiles.any { it.id == customId }) {
            removeAiReferenceFile(customId)
            return
        }
        val match = _uiState.value.cards.firstNotNullOfOrNull { card ->
            card.detail.attachments.firstOrNull { it.attachmentId == attachmentId }?.let { card.detail.meta.id to it }
        } ?: return
        val (ownerMetaId, att) = match

        viewModelScope.launch {
            val cached = att.localFilePath?.let(::File)?.takeIf {
                !AttachmentMetadataHelper.isCachedAttachmentInvalid(it, att.filename, att.mimeType)
            }
            val sourceFile = if (cached != null) {
                cached
            } else {
                _uiState.update { it.copy(downloadingAiAttachmentIds = it.downloadingAiAttachmentIds + attachmentId) }
                val downloaded = runCatching {
                    container.messageRepository.downloadAttachment(
                        accountId = accountId,
                        metaId = ownerMetaId,
                        attachmentId = attachmentId,
                        attachmentsRootDir = attachmentsRootDir,
                    )
                }.onFailure { err ->
                    _uiState.update {
                        it.copy(
                            downloadingAiAttachmentIds = it.downloadingAiAttachmentIds - attachmentId,
                            aiReferenceWarning = "添付の取得に失敗しました: ${err.message ?: err.javaClass.simpleName}",
                        )
                    }
                }.getOrNull() ?: return@launch

                updateCard(ownerMetaId) { c ->
                    val updatedAtts = c.detail.attachments.map { a ->
                        if (a.attachmentId == attachmentId) a.copy(localFilePath = downloaded.absolutePath) else a
                    }
                    c.copy(detail = c.detail.copy(attachments = updatedAtts))
                }
                _uiState.update { it.copy(downloadingAiAttachmentIds = it.downloadingAiAttachmentIds - attachmentId) }
                downloaded
            }

            val staged = withContext(Dispatchers.IO) {
                runCatching {
                    AiReferenceStaging.stageLocalFile(
                        context = context,
                        sourceFile = sourceFile,
                        displayName = att.filename,
                        rawMimeType = att.mimeType,
                        customId = customId,
                    )
                }.getOrNull()
            }
            if (staged == null) {
                _uiState.update { it.copy(aiReferenceWarning = "添付を読み込めませんでした: ${att.filename}") }
                return@launch
            }
            mergeNewReferenceFiles(listOf(staged))
        }
    }

    fun removeAiReferenceFile(fileId: String) {
        val updatedFiles = _uiState.value.aiReferenceFiles.filterNot { it.id == fileId }
        viewModelScope.launch {
            val bundle = container.aiPreparationService.prepareFileReferenceBundle(updatedFiles)
            _uiState.update { state ->
                val updated = state.copy(
                    aiReferenceFiles = updatedFiles,
                    aiReferenceTextDocuments = bundle.inlineTextDocuments,
                    aiBinaryDescriptorLabels = bundle.binaryDescriptorLabels,
                    aiReferenceWarning = null,
                )
                updated.copy(aiBuiltPromptPreview = buildPreviewPrompt(updated))
            }
        }
    }

    private suspend fun mergeNewReferenceFiles(newFiles: List<AiReferenceFileSpec>) {
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
        val bundle = container.aiPreparationService.prepareFileReferenceBundle(current)
        val combinedWarning = warning ?: bundle.skippedNotes.firstOrNull()
        _uiState.update { state ->
            val updated = state.copy(
                aiReferenceFiles = current,
                aiReferenceTextDocuments = bundle.inlineTextDocuments,
                aiBinaryDescriptorLabels = bundle.binaryDescriptorLabels,
                aiReferenceWarning = combinedWarning,
            )
            updated.copy(aiBuiltPromptPreview = buildPreviewPrompt(updated))
        }
    }

    fun summarizeConversationWithAi() {
        aiSummaryJob?.cancel()
        aiSummaryJob = viewModelScope.launch {
            val snapshot = _uiState.value
            _uiState.update {
                it.copy(
                    isSummarizingAi = true,
                    aiSummaryError = null,
                )
            }
            try {
                container.awaitDatabase()
                val service = container.aiPreparationService
                val context = snapshot.aiPreparedContext ?: service.prepareEmailContext(
                    accountId = accountId,
                    metaId = metaId,
                    threadId = threadId,
                    loadFullThread = isThreadMode,
                )
                val prompt = service.buildSummaryPrompt(
                    context = context,
                    subjectFallback = snapshot.subject,
                    userInstruction = snapshot.aiInstructionInput,
                    extraReferenceEmails = snapshot.aiReferenceEmailContexts,
                    extraTextDocuments = snapshot.aiReferenceTextDocuments,
                    binaryAttachmentDescriptors = snapshot.aiBinaryDescriptorLabels,
                )
                val result = service.generateCompletion(
                    request = prompt,
                    referenceFiles = snapshot.aiReferenceFiles,
                )
                _uiState.update {
                    it.copy(
                        isSummarizingAi = false,
                        aiPreparedContext = context,
                        aiSummaryContext = context,
                        aiSummaryResult = result,
                        aiSummaryError = null,
                        aiSummaryExtraEmailCount = snapshot.aiReferenceEmails.size,
                        aiSummaryExtraFileCount = snapshot.aiReferenceFiles.size,
                    )
                }
            } catch (ce: CancellationException) {
                _uiState.update { it.copy(isSummarizingAi = false) }
                throw ce
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        isSummarizingAi = false,
                        aiSummaryError = t.message ?: "AI 要約中にエラーが発生しました",
                    )
                }
            }
        }
    }

    fun cancelAiSummary() {
        aiSummaryJob?.cancel()
        aiSummaryJob = null
        _uiState.update { it.copy(isSummarizingAi = false) }
    }

    fun dismissAiSummary() {
        aiSummaryJob?.cancel()
        aiSummaryJob = null
        _uiState.update {
            it.copy(
                isSummarizingAi = false,
                aiSummaryResult = null,
                aiSummaryError = null,
            )
        }
    }

    fun toggleHtmlMode(targetMetaId: Long) {
        val current = _uiState.value.cards.firstOrNull { it.detail.meta.id == targetMetaId } ?: return
        if (current.showHtmlMode) {
            updateCard(targetMetaId) { it.copy(showHtmlMode = false) }
            return
        }
        if (current.decompressedHtml != null) {
            updateCard(targetMetaId) { it.copy(showHtmlMode = true) }
            return
        }
        val zstdBytes = current.detail.body?.htmlZstd ?: return
        viewModelScope.launch {
            updateCard(targetMetaId) { it.copy(isDecompressingHtml = true) }
            runCatching {
                container.messageRepository.decompressHtmlInMemory(zstdBytes)
            }.onSuccess { html ->
                updateCard(targetMetaId) {
                    it.copy(
                        isDecompressingHtml = false,
                        decompressedHtml = html,
                        showHtmlMode = true,
                    )
                }
            }.onFailure { err ->
                updateCard(targetMetaId) { it.copy(isDecompressingHtml = false) }
                _uiState.update { it.copy(infoBanner = "HTML解凍エラー: ${err.message}") }
            }
        }
    }

    fun allowExternalImages(targetMetaId: Long) {
        updateCard(targetMetaId) {
            it.copy(allowExternalImages = true, blockedExternalResources = false)
        }
    }

    fun markBlockedExternalResource(targetMetaId: Long) {
        updateCard(targetMetaId) {
            if (it.allowExternalImages || it.blockedExternalResources) it
            else it.copy(blockedExternalResources = true)
        }
    }

    fun toggleFlagged(targetMetaId: Long) {
        val card = _uiState.value.cards.firstOrNull { it.detail.meta.id == targetMetaId } ?: return
        val currentFlagged = card.detail.meta.isFlagged
        viewModelScope.launch {
            container.messageRepository.toggleFlagged(targetMetaId, currentFlagged)
            updateCard(targetMetaId) {
                it.copy(detail = it.detail.copy(meta = it.detail.meta.copy(isFlagged = !currentFlagged)))
            }
        }
    }

    fun markUnread(targetMetaId: Long) {
        viewModelScope.launch {
            container.messageRepository.setReadState(targetMetaId, false)
            updateCard(targetMetaId) {
                it.copy(detail = it.detail.copy(meta = it.detail.meta.copy(isRead = false)))
            }
            _uiState.update { it.copy(infoBanner = "未読に戻しました") }
        }
    }

    fun downloadAttachment(
        targetMetaId: Long,
        attachmentId: Long,
        attachmentsRootDir: File,
        onReadyToOpen: (File, String) -> Unit,
    ) {
        val card = _uiState.value.cards.firstOrNull { it.detail.meta.id == targetMetaId } ?: return
        val att = card.detail.attachments.firstOrNull { it.attachmentId == attachmentId } ?: return

        _uiState.update { it.copy(infoBanner = null) }

        att.localFilePath?.let { path ->
            val existing = File(path)
            if (!AttachmentMetadataHelper.isCachedAttachmentInvalid(existing, att.filename, att.mimeType)) {
                onReadyToOpen(existing, att.mimeType)
                return
            }
        }

        viewModelScope.launch {
            updateCard(targetMetaId) {
                it.copy(downloadingAttachmentIds = it.downloadingAttachmentIds + attachmentId)
            }
            runCatching {
                container.messageRepository.downloadAttachment(
                    accountId = accountId,
                    metaId = targetMetaId,
                    attachmentId = attachmentId,
                    attachmentsRootDir = attachmentsRootDir,
                )
            }.onSuccess { file ->
                updateCard(targetMetaId) { c ->
                    val updatedAtts = c.detail.attachments.map { a ->
                        if (a.attachmentId == attachmentId) a.copy(localFilePath = file.absolutePath) else a
                    }
                    c.copy(
                        detail = c.detail.copy(attachments = updatedAtts),
                        downloadingAttachmentIds = c.downloadingAttachmentIds - attachmentId,
                    )
                }
                onReadyToOpen(file, att.mimeType)
            }.onFailure { err ->
                updateCard(targetMetaId) {
                    it.copy(downloadingAttachmentIds = it.downloadingAttachmentIds - attachmentId)
                }
                _uiState.update { it.copy(infoBanner = "添付取得エラー: ${err.message ?: err.javaClass.simpleName}") }
            }
        }
    }

    fun reportInfoBanner(message: String) {
        _uiState.update { it.copy(infoBanner = message) }
    }

    fun confirmArchiveRequired(): Boolean = container.appPreferences.confirmArchive.value

    fun archiveConversation(): List<Long> {
        val ids = _uiState.value.cards.map { it.detail.meta.id }
        viewModelScope.launch {
            container.messageRepository.archiveMessagesLocally(ids)
            _uiState.update { it.copy(archivedAndShouldClose = true) }
        }
        return ids
    }

    fun exportMessageToEml(context: Context, destinationUri: Uri, targetMetaId: Long) {
        val card = _uiState.value.cards.firstOrNull { it.detail.meta.id == targetMetaId } ?: return
        viewModelScope.launch {
            runCatching {
                val emlBytes = LocalArchiveExporter.buildEmlBytes(
                    meta = card.detail.meta,
                    body = card.detail.body,
                    attachments = card.detail.attachments,
                    decompressHtml = { compressed ->
                        card.decompressedHtml ?: container.messageRepository.decompressHtmlInMemory(compressed)
                    },
                )
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(destinationUri)?.use { out ->
                        out.write(emlBytes)
                        out.flush()
                    } ?: error("出力ストリームを開けませんでした")
                }
                emlBytes.size
            }.onSuccess { byteCount ->
                _uiState.update {
                    it.copy(infoBanner = "EMLエクスポート完了 (${byteCount} bytes)")
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(infoBanner = "EMLエクスポートエラー: ${err.message}")
                }
            }
        }
    }

    private fun updateCard(targetMetaId: Long, transform: (MessageCardUiState) -> MessageCardUiState) {
        _uiState.update { state ->
            state.copy(
                cards = state.cards.map { card ->
                    if (card.detail.meta.id == targetMetaId) transform(card) else card
                },
            )
        }
    }

    private fun parseRecipients(rawJson: String?): List<RecipientDisplay> {
        if (rawJson.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<RecipientDisplay>>(rawJson).map { r ->
                r.copy(name = r.name?.let { Rfc2047HeaderDecoder.decodeIfNeeded(it) })
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        internal fun parseCcFromRawHeaders(rawHeaders: String?): List<RecipientDisplay> {
            if (rawHeaders.isNullOrBlank()) return emptyList()
            val unfolded = rawHeaders.replace(Regex("\r?\n[ \t]+"), " ")
            val ccRawValues = unfolded.lineSequence()
                .mapNotNull { line ->
                    val colonIndex = line.indexOf(':')
                    if (colonIndex <= 0) return@mapNotNull null
                    val headerName = line.substring(0, colonIndex).trim()
                    if (headerName.equals("cc", ignoreCase = true)) {
                        line.substring(colonIndex + 1).trim().takeIf { it.isNotEmpty() }
                    } else {
                        null
                    }
                }
                .toList()
            if (ccRawValues.isEmpty()) return emptyList()

            return ccRawValues
                .flatMap(::splitHeaderAddressList)
                .mapNotNull(::parseSingleAddressToken)
                .distinctBy { it.address.lowercase() }
        }

        private fun splitHeaderAddressList(raw: String): List<String> {
            val result = mutableListOf<String>()
            val current = StringBuilder()
            var inQuotes = false
            var escaped = false
            var angleDepth = 0

            for (ch in raw) {
                if (escaped) {
                    current.append(ch)
                    escaped = false
                    continue
                }
                when (ch) {
                    '\\' if inQuotes -> {
                        current.append(ch)
                        escaped = true
                    }
                    '"' -> {
                        inQuotes = !inQuotes
                        current.append(ch)
                    }
                    '<' if !inQuotes -> {
                        angleDepth++
                        current.append(ch)
                    }
                    '>' if !inQuotes && angleDepth > 0 -> {
                        angleDepth--
                        current.append(ch)
                    }
                    ',' if !inQuotes && angleDepth == 0 -> {
                        val token = current.toString().trim()
                        if (token.isNotEmpty()) result += token
                        current.clear()
                    }
                    else -> current.append(ch)
                }
            }
            val trailing = current.toString().trim()
            if (trailing.isNotEmpty()) result += trailing
            return result
        }

        private fun parseSingleAddressToken(token: String): RecipientDisplay? {
            val trimmed = token.trim()
            if (trimmed.isEmpty()) return null
            val angleMatch = ANGLE_ADDRESS_REGEX.find(trimmed)
            if (angleMatch != null) {
                val rawName = angleMatch.groupValues[1].trim().removeSurrounding("\"").trim()
                val address = angleMatch.groupValues[2].trim()
                val decodedName = rawName.takeIf { it.isNotEmpty() }?.let {
                    Rfc2047HeaderDecoder.decodeIfNeeded(it)
                }
                return RecipientDisplay(name = decodedName, address = address)
            }

            val decoded = (Rfc2047HeaderDecoder.decodeIfNeeded(trimmed) ?: trimmed).trim()
            val emailMatch = BARE_EMAIL_REGEX.find(decoded) ?: return null
            val address = emailMatch.groupValues[1]
            val beforeEmail = decoded.substring(0, emailMatch.range.first).trim().removeSurrounding("\"").trim()
            return RecipientDisplay(
                name = beforeEmail.takeIf { it.isNotEmpty() },
                address = address,
            )
        }

        private val ANGLE_ADDRESS_REGEX = Regex("""^(.*)<([^<>\s]+@[^<>\s]+)>\s*$""")
        private val BARE_EMAIL_REGEX = Regex("""([^\s<>"]+@[^\s<>"]+)""")

        fun factory(
            container: KazeMailContainer,
            accountId: Long,
            metaId: Long,
            threadId: String?,
            isThreadMode: Boolean,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MessageDetailViewModel(container, accountId, metaId, threadId, isThreadMode) as T
        }
    }
}
