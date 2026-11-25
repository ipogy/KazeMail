package com.kazemail.app.ui.viewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import com.kazemail.app.ui.theme.KazeIcons
import com.kazemail.app.ui.theme.LocalKazeDarkTheme
import com.kazemail.app.ui.theme.StarAmberDark
import com.kazemail.app.ui.theme.StarAmberLight
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kazemail.app.ai.AiClarificationAnswer
import com.kazemail.app.ai.AiClarificationQuestion
import com.kazemail.app.ai.AiPreparationService
import com.kazemail.app.ai.AiReadinessStatus
import com.kazemail.app.ai.AiReferenceFileSpec
import com.kazemail.app.ai.AiWritingGoal
import com.kazemail.app.data.db.AttachmentEntity
import com.kazemail.app.ui.ai.AiAttachableAttachmentCandidate
import com.kazemail.app.ui.ai.AiPreparationSheet
import com.kazemail.app.ui.ai.AiReferenceEmailPickerDialog
import com.kazemail.app.ui.common.DateFormatters
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MessageDetailScreen(
    viewModel: MessageDetailViewModel,
    onNavigateBack: () -> Unit,
    onArchivedWithUndo: (List<Long>) -> Unit,
    onComposeReply: (accountId: Long, metaId: Long, mode: String) -> Unit,
    onOpenAiSettings: () -> Unit = {},
    onComposeAiReply: (
        accountId: Long,
        metaId: Long,
        mode: String,
        aiTone: String?,
        aiInstruction: String?,
        aiPreferHtml: Boolean,
        autoTriggerAi: Boolean,
        aiReferenceMetaIds: List<Long>,
        aiReferenceFilesJson: String?,
        aiClarificationQuestionsJson: String?,
        aiClarificationAnswersJson: String?,
        aiClarificationSummary: String?,
    ) -> Unit = { accId, id, mode, _, _, _, _, _, _, _, _, _ -> onComposeReply(accId, id, mode) },
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val attachmentsRootDir = remember(context) { File(context.filesDir, "attachments") }
    var pendingExportMetaId by remember { mutableStateOf<Long?>(null) }
    var showArchiveConfirmDialog by remember { mutableStateOf(false) }
    var showAiPrepSheet by remember { mutableStateOf(false) }
    var showAiEmailPicker by remember { mutableStateOf(false) }

    val aiReferenceFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.addAiReferencePickedUris(context, uris)
        }
    }

    if (showArchiveConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showArchiveConfirmDialog = false },
            title = { Text("ローカルアーカイブ") },
            text = { Text("この会話をローカルアーカイブへ移動しますか？（メールサーバー上のデータは変更・削除されません）") },
            confirmButton = {
                Button(
                    onClick = {
                        showArchiveConfirmDialog = false
                        viewModel.archiveConversation()
                    },
                ) {
                    Text("アーカイブ")
                }
            },
            dismissButton = {
                TextButton(onClick = { showArchiveConfirmDialog = false }) {
                    Text("キャンセル")
                }
            },
        )
    }

    val emlExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("message/rfc822"),
    ) { uri ->
        val targetId = pendingExportMetaId
        pendingExportMetaId = null
        if (uri != null && targetId != null) {
            viewModel.exportMessageToEml(context, uri, targetId)
        }
    }

    LaunchedEffect(state.archivedAndShouldClose) {
        if (state.archivedAndShouldClose) {
            onArchivedWithUndo(state.cards.map { it.detail.meta.id })
            onNavigateBack()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.subject,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    if (state.cards.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                viewModel.prepareAiReplySheet()
                                showAiPrepSheet = true
                            },
                        ) {
                            Icon(KazeIcons.AutoAwesome, contentDescription = "AI返信準備")
                        }
                        IconButton(
                            onClick = {
                                if (viewModel.confirmArchiveRequired()) {
                                    showArchiveConfirmDialog = true
                                } else {
                                    viewModel.archiveConversation()
                                }
                            },
                        ) {
                            Icon(KazeIcons.Archive, contentDescription = "アーカイブ")
                        }
                    }
                },
            )
        },
        bottomBar = {
            val primaryCard = state.cards.lastOrNull()
            if (!state.isLoading && state.errorMessage == null && primaryCard != null) {
                Surface(
                    tonalElevation = 3.dp,
                    shadowElevation = 4.dp,
                ) {
                    Column(modifier = Modifier.navigationBarsPadding()) {
                        HorizontalDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilledTonalButton(
                                onClick = {
                                    onComposeReply(
                                        primaryCard.detail.meta.accountId,
                                        primaryCard.detail.meta.id,
                                        "REPLY",
                                    )
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                            ) {
                                Text("返信", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            FilledTonalButton(
                                onClick = {
                                    onComposeReply(
                                        primaryCard.detail.meta.accountId,
                                        primaryCard.detail.meta.id,
                                        "REPLY_ALL",
                                    )
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                            ) {
                                Text("全員に返信", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            OutlinedButton(
                                onClick = {
                                    onComposeReply(
                                        primaryCard.detail.meta.accountId,
                                        primaryCard.detail.meta.id,
                                        "FORWARD",
                                    )
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                            ) {
                                Text("転送", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            state.errorMessage != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = state.errorMessage ?: "",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            else -> {
                val hasHtmlVisible = state.cards.any {
                    it.isExpanded && it.showHtmlMode && it.decompressedHtml != null
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(
                        horizontal = if (hasHtmlVisible) 4.dp else 12.dp,
                        vertical = 10.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    state.infoBanner?.let { banner ->
                        item(key = "info_banner") {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = banner,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(12.dp),
                                )
                            }
                        }
                    }

                    if (state.cards.isNotEmpty() && state.aiReadiness.status != AiReadinessStatus.FEATURE_DISABLED) {
                        item(key = "ai_quick_bar") {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                AssistChip(
                                    enabled = state.aiReadiness.isReady && !state.isSummarizingAi,
                                    onClick = viewModel::summarizeConversationWithAi,
                                    leadingIcon = {
                                        Icon(
                                            imageVector = KazeIcons.AutoAwesome,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    },
                                    label = {
                                        Text(
                                            if (state.aiSummaryResult != null) "AIで再要約" else "AIで要約",
                                        )
                                    },
                                )
                                AssistChip(
                                    onClick = {
                                        viewModel.prepareAiReplySheet()
                                        showAiPrepSheet = true
                                    },
                                    label = { Text("AI返信・指示作成") },
                                )
                            }
                        }
                    }

                    if (state.isSummarizingAi || state.aiSummaryResult != null || state.aiSummaryError != null) {
                        item(key = "ai_summary_card") {
                            AiConversationSummaryCard(
                                isSummarizing = state.isSummarizingAi,
                                result = state.aiSummaryResult,
                                contextStats = state.aiSummaryContext,
                                extraEmailCount = state.aiSummaryExtraEmailCount,
                                extraFileCount = state.aiSummaryExtraFileCount,
                                error = state.aiSummaryError,
                                onCancel = viewModel::cancelAiSummary,
                                onRetry = viewModel::summarizeConversationWithAi,
                                onCopy = { text ->
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    clipboard?.setPrimaryClip(ClipData.newPlainText("AIメール要約", text))
                                    viewModel.reportInfoBanner("AI 要約をクリップボードにコピーしました")
                                },
                                onOpenAiSettings = onOpenAiSettings,
                                onDismiss = viewModel::dismissAiSummary,
                            )
                        }
                    }

                    items(state.cards, key = { it.detail.meta.id }) { card ->
                        MessageConversationCard(
                            card = card,
                            onToggleExpand = { viewModel.toggleCardExpanded(card.detail.meta.id) },
                            onToggleHeaderDetails = { viewModel.toggleHeaderDetails(card.detail.meta.id) },
                            onToggleFlagged = { viewModel.toggleFlagged(card.detail.meta.id) },
                            onMarkUnread = { viewModel.markUnread(card.detail.meta.id) },
                            onReply = {
                                onComposeReply(card.detail.meta.accountId, card.detail.meta.id, "REPLY")
                            },
                            onReplyAll = {
                                onComposeReply(card.detail.meta.accountId, card.detail.meta.id, "REPLY_ALL")
                            },
                            onForward = {
                                onComposeReply(card.detail.meta.accountId, card.detail.meta.id, "FORWARD")
                            },
                            onExportEml = {
                                pendingExportMetaId = card.detail.meta.id
                                val baseName = (card.detail.meta.subject ?: "message")
                                    .replace(Regex("[^A-Za-z0-9\\u3040-\\u30FF\\u4E00-\\u9FFF._-]"), "_")
                                    .take(40)
                                    .ifBlank { "message" }
                                emlExportLauncher.launch("${baseName}_${card.detail.meta.id}.eml")
                            },
                            onToggleHtml = { viewModel.toggleHtmlMode(card.detail.meta.id) },
                            onAllowExternalImages = { viewModel.allowExternalImages(card.detail.meta.id) },
                            onBlockedExternalResource = { viewModel.markBlockedExternalResource(card.detail.meta.id) },
                            onToggleRawHeaders = { viewModel.toggleRawHeaders(card.detail.meta.id) },
                            onToggleQuote = { idx -> viewModel.toggleQuote(card.detail.meta.id, idx) },
                            onAttachmentClick = { att ->
                                viewModel.downloadAttachment(
                                    targetMetaId = card.detail.meta.id,
                                    attachmentId = att.attachmentId,
                                    attachmentsRootDir = attachmentsRootDir,
                                    onReadyToOpen = { file, mimeType ->
                                        AttachmentOpener.openFileWithSystemViewer(
                                            context = context,
                                            file = file,
                                            rawMimeType = mimeType,
                                            onError = viewModel::reportInfoBanner,
                                        )
                                    },
                                )
                            },
                            onLinkClick = { link -> handleDetectedLink(context, link) },
                            onExternalUrlClick = { url -> openUrlSafely(context, url) },
                        )
                    }
                }
            }
        }
    }

    val primaryCard = state.cards.lastOrNull()
    if (showAiPrepSheet && primaryCard != null) {
        val conversationAttachmentsForAi = state.cards.flatMap { it.detail.attachments }.map { att ->
            AiAttachableAttachmentCandidate(
                id = att.attachmentId.toString(),
                label = att.filename,
                isDownloading = att.attachmentId in state.downloadingAiAttachmentIds,
                isAlreadyAdded = state.aiReferenceFiles.any { it.id == "att:${att.attachmentId}" },
            )
        }
        AiPreparationSheet(
            title = "AI アシスタント（要約・返信）",
            readiness = state.aiReadiness,
            isPreparingContext = state.isPreparingAiContext,
            preparedContext = state.aiPreparedContext,
            selectedGoal = AiWritingGoal.REPLY,
            selectedTone = state.aiSelectedTone,
            presets = state.aiPresets,
            instructionInput = state.aiInstructionInput,
            builtPromptPreview = state.aiBuiltPromptPreview,
            primaryActionLabel = "この指示でAI返信作成",
            onSelectTone = viewModel::selectAiTone,
            onApplyPreset = viewModel::applyAiPreset,
            onInstructionChange = viewModel::updateAiInstruction,
            onOpenAiSettings = onOpenAiSettings,
            preferHtmlOutput = state.aiPreferHtmlOutput,
            onSelectPreferHtmlOutput = viewModel::selectAiPreferHtmlOutput,
            onPrimaryAction = {
                val filesJson = if (state.aiReferenceFiles.isNotEmpty()) {
                    Json.encodeToString(ListSerializer(AiReferenceFileSpec.serializer()), state.aiReferenceFiles)
                } else {
                    null
                }
                val questionsJson = state.aiClarificationPlan?.questions?.takeIf { it.isNotEmpty() }?.let {
                    Json.encodeToString(ListSerializer(AiClarificationQuestion.serializer()), it)
                }
                val answersJson = state.aiClarificationAnswers.values.filter { it.hasAnswer }
                    .takeIf { it.isNotEmpty() }
                    ?.let { Json.encodeToString(ListSerializer(AiClarificationAnswer.serializer()), it) }
                onComposeAiReply(
                    primaryCard.detail.meta.accountId,
                    primaryCard.detail.meta.id,
                    "REPLY",
                    state.aiSelectedTone.storageValue,
                    state.aiInstructionInput,
                    state.aiPreferHtmlOutput,
                    true,
                    state.aiReferenceEmails.map { it.metaId },
                    filesJson,
                    questionsJson,
                    answersJson,
                    state.aiClarificationPlan?.summary,
                )
            },
            onSummarizeAction = viewModel::summarizeConversationWithAi,
            onDismiss = { showAiPrepSheet = false },
            referenceEmails = state.aiReferenceEmails,
            onOpenReferenceEmailPicker = {
                viewModel.openAiEmailPicker()
                showAiEmailPicker = true
            },
            onRemoveReferenceEmail = viewModel::removeAiReferenceEmail,
            referenceFiles = state.aiReferenceFiles,
            onPickReferenceFiles = { aiReferenceFilePicker.launch(arrayOf("*/*")) },
            onRemoveReferenceFile = viewModel::removeAiReferenceFile,
            existingAttachmentsForAi = conversationAttachmentsForAi,
            onToggleExistingAttachmentForAi = { idStr ->
                idStr.toLongOrNull()?.let { attId ->
                    viewModel.toggleConversationAttachmentForAi(context, attId, attachmentsRootDir)
                }
            },
            referenceWarningMessage = state.aiReferenceWarning,
            isGeneratingQuestions = state.isGeneratingAiQuestions,
            clarificationPlan = state.aiClarificationPlan,
            clarificationAnswers = state.aiClarificationAnswers,
            clarificationError = state.aiClarificationError,
            onRequestClarificationQuestions = viewModel::requestAiClarificationQuestions,
            onCancelClarificationQuestions = viewModel::cancelAiClarificationQuestions,
            onSelectClarificationOption = viewModel::selectClarificationOption,
            onUpdateClarificationCustomInput = viewModel::updateClarificationCustomInput,
            onClearClarificationQuestions = viewModel::clearClarificationQuestions,
        )
    }

    if (showAiEmailPicker) {
        AiReferenceEmailPickerDialog(
            candidates = state.aiEmailPickerCandidates,
            selectedMetaIds = state.aiReferenceEmails.map { it.metaId }.toSet(),
            searchQuery = state.aiEmailPickerSearchQuery,
            isLoading = state.isSearchingAiEmailPicker,
            maxSelections = AiPreparationService.MAX_EXTRA_REFERENCE_EMAILS,
            onSearchQueryChange = viewModel::updateAiEmailPickerSearch,
            onToggleSelect = viewModel::toggleAiReferenceEmail,
            onDismiss = { showAiEmailPicker = false },
        )
    }
}

@Composable
private fun AiConversationSummaryCard(
    isSummarizing: Boolean,
    result: com.kazemail.app.ai.AiCompletionResult?,
    contextStats: com.kazemail.app.ai.AiPreparedEmailContext?,
    extraEmailCount: Int,
    extraFileCount: Int,
    error: String?,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onCopy: (String) -> Unit,
    onOpenAiSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = KazeIcons.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "AI 要約",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (result != null) {
                    Text(
                        text = "${result.modelId} • ${result.latencyMs}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (isSummarizing) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        text = "サニタイズ済みメール文脈を要約中…",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onCancel) {
                        Text("中止")
                    }
                }
            }

            if (error != null) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenAiSettings) {
                        Text("AI設定")
                    }
                    OutlinedButton(onClick = onRetry) {
                        Text("再試行")
                    }
                    TextButton(onClick = onDismiss) {
                        Text("閉じる")
                    }
                }
            }

            if (result != null) {
                contextStats?.let { stats ->
                    val extraNote = buildList {
                        if (extraEmailCount > 0) add("別メール ${extraEmailCount}通")
                        if (extraFileCount > 0) add("資料 ${extraFileCount}件")
                    }.joinToString("・")
                    val label = buildString {
                        append("対象 ${stats.includedMessageCount}/${stats.totalMessageCount}通 • 機密マスク ${stats.redactionCount}件")
                        if (extraNote.isNotEmpty()) append(" • 追加参照: $extraNote")
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SelectionContainer {
                    Text(
                        text = result.text,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { onCopy(result.text) }) {
                        Text("コピー")
                    }
                    TextButton(onClick = onRetry, enabled = !isSummarizing) {
                        Text("再要約")
                    }
                    TextButton(onClick = onDismiss) {
                        Text("閉じる")
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageConversationCard(
    card: MessageCardUiState,
    onToggleExpand: () -> Unit,
    onToggleHeaderDetails: () -> Unit,
    onToggleFlagged: () -> Unit,
    onMarkUnread: () -> Unit,
    onReply: () -> Unit,
    onReplyAll: () -> Unit,
    onForward: () -> Unit,
    onExportEml: () -> Unit,
    onToggleHtml: () -> Unit,
    onAllowExternalImages: () -> Unit,
    onBlockedExternalResource: () -> Unit,
    onToggleRawHeaders: () -> Unit,
    onToggleQuote: (Int) -> Unit,
    onAttachmentClick: (AttachmentEntity) -> Unit,
    onLinkClick: (DetectedLink) -> Unit,
    onExternalUrlClick: (String) -> Unit,
) {
    val meta = card.detail.meta
    val body = card.detail.body
    val dateLabel = DateFormatters.formatDateTime(meta.receivedAt)
    val fullDateLabel = DateFormatters.formatDateTimeSeconds(meta.receivedAt)
    val isDark = LocalKazeDarkTheme.current
    var showCardMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = meta.senderName?.takeIf { it.isNotBlank() } ?: meta.senderAddress,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${meta.senderAddress} • $dateLabel",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (card.isExpanded) {
                        if (!card.showHeaderDetails) {
                            if (card.recipients.isNotEmpty()) {
                                Text(
                                    text = "宛先: ${formatRecipientDisplayList(card.recipients, includeAddress = false)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (card.ccRecipients.isNotEmpty()) {
                                Text(
                                    text = "Cc: ${formatRecipientDisplayList(card.ccRecipients, includeAddress = false)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (card.recipients.isNotEmpty() || card.ccRecipients.isNotEmpty() || meta.receivedAt > 0L) {
                            Text(
                                text = if (card.showHeaderDetails) "宛先・詳細を隠す ▴" else "宛先・送信詳細を表示 ▾",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable(onClick = onToggleHeaderDetails)
                                    .padding(top = 2.dp, bottom = 2.dp),
                            )
                        }
                    }
                }
                IconButton(onClick = onToggleFlagged) {
                    Icon(
                        imageVector = if (meta.isFlagged) Icons.Filled.Star else Icons.Outlined.Star,
                        contentDescription = "スター切替",
                        tint = if (meta.isFlagged) {
                            if (isDark) StarAmberDark else StarAmberLight
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                if (card.isExpanded) {
                    Box {
                        IconButton(onClick = { showCardMenu = true }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "メール操作メニュー",
                            )
                        }
                        DropdownMenu(
                            expanded = showCardMenu,
                            onDismissRequest = { showCardMenu = false },
                        ) {
                            if (card.canRenderHtml) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            when {
                                                card.isDecompressingHtml -> "HTML解凍中…"
                                                card.showHtmlMode -> "テキスト表示に戻す"
                                                else -> "HTMLを表示"
                                            },
                                        )
                                    },
                                    enabled = !card.isDecompressingHtml,
                                    onClick = {
                                        showCardMenu = false
                                        onToggleHtml()
                                    },
                                )
                            }
                            if (!body?.rawHeaders.isNullOrBlank()) {
                                DropdownMenuItem(
                                    text = { Text(if (card.showRawHeaders) "生ヘッダを隠す" else "詳細ヘッダ (Raw)") },
                                    onClick = {
                                        showCardMenu = false
                                        onToggleRawHeaders()
                                    },
                                )
                            }
                            if (card.canRenderHtml || !body?.rawHeaders.isNullOrBlank()) {
                                HorizontalDivider()
                            }
                            DropdownMenuItem(
                                text = { Text("このメールに返信") },
                                onClick = {
                                    showCardMenu = false
                                    onReply()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("全員に返信") },
                                onClick = {
                                    showCardMenu = false
                                    onReplyAll()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("転送") },
                                onClick = {
                                    showCardMenu = false
                                    onForward()
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("未読にする") },
                                onClick = {
                                    showCardMenu = false
                                    onMarkUnread()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("EML保存") },
                                onClick = {
                                    showCardMenu = false
                                    onExportEml()
                                },
                            )
                        }
                    }
                }
            }

            if (!card.isExpanded) {
                if (!meta.snippet.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = meta.snippet,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                return@Card
            }

            if (card.showHeaderDetails) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                ) {
                    SelectionContainer {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            val senderDisplay = meta.senderName?.takeIf { it.isNotBlank() }
                                ?.let { "$it <${meta.senderAddress}>" }
                                ?: meta.senderAddress
                            Text(
                                text = "送信元: $senderDisplay",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (card.recipients.isNotEmpty()) {
                                Text(
                                    text = "宛先 (To): ${formatRecipientDisplayList(card.recipients, includeAddress = true)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            if (card.ccRecipients.isNotEmpty()) {
                                Text(
                                    text = "Cc: ${formatRecipientDisplayList(card.ccRecipients, includeAddress = true)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            Text(
                                text = "受信日時: $fullDateLabel",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (card.canRenderHtml) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AssistChip(
                        onClick = onToggleHtml,
                        enabled = !card.isDecompressingHtml,
                        label = {
                            Text(
                                when {
                                    card.isDecompressingHtml -> "HTML解凍中…"
                                    card.showHtmlMode -> "テキスト表示に戻す"
                                    else -> "HTMLを表示"
                                },
                            )
                        },
                    )
                }
            }

            if (card.detail.isBodyTruncated) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Info,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "サイズ超過のため先頭500KBのみローカル保存されています（OOM保護）",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }

            if (card.showRawHeaders && !body?.rawHeaders.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                ) {
                    SelectionContainer {
                        Text(
                            text = body.rawHeaders,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )

            if (card.showHtmlMode && card.decompressedHtml != null) {
                SandboxedHtmlViewer(
                    html = card.decompressedHtml,
                    allowExternalImages = card.allowExternalImages,
                    isDarkSurface = isDark,
                    onBlockedExternalResource = onBlockedExternalResource,
                    onExternalLinkClick = onExternalUrlClick,
                    onAllowExternalImages = onAllowExternalImages,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp),
                )
            } else {
                val rawPlain = body?.plainText ?: meta.snippet ?: ""
                val displayPlain = if (card.canRenderHtml && HtmlEmailDetector.looksLikeRawHtmlDocument(rawPlain)) {
                    HtmlEmailDetector.stripHtmlFallbackText(rawPlain)
                } else {
                    rawPlain
                }
                Box(modifier = Modifier.padding(horizontal = 12.dp)) {
                    PlainTextBodySection(
                        plainText = displayPlain,
                        expandedQuoteIndices = card.expandedQuoteIndices,
                        onToggleQuote = onToggleQuote,
                        onLinkClick = onLinkClick,
                    )
                }
            }

            if (card.detail.attachments.isNotEmpty()) {
                Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "添付ファイル (${card.detail.attachments.size})",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        card.detail.attachments.forEach { att ->
                            val isDownloading = att.attachmentId in card.downloadingAttachmentIds
                            val isCached = !att.localFilePath.isNullOrBlank() &&
                                File(att.localFilePath).let { it.exists() && it.length() > 0L }
                            Surface(
                                onClick = { onAttachmentClick(att) },
                                enabled = !isDownloading,
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        KazeIcons.AttachFile,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = att.filename,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = "${att.mimeType} • ${formatBytes(att.sizeBytes)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    if (isDownloading) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        Surface(
                                            shape = MaterialTheme.shapes.extraSmall,
                                            color = if (isCached) {
                                                MaterialTheme.colorScheme.primaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.surfaceContainerHighest
                                            },
                                        ) {
                                            Text(
                                                text = if (isCached) "保存済" else "取得",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (isCached) {
                                                    MaterialTheme.colorScheme.onPrimaryContainer
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatRecipientDisplayList(
    recipients: List<RecipientDisplay>,
    includeAddress: Boolean,
): String = recipients.joinToString(", ") { recipient ->
    val cleanName = recipient.name?.takeIf { it.isNotBlank() }
    if (includeAddress) {
        cleanName?.let { "$it <${recipient.address}>" } ?: recipient.address
    } else {
        cleanName ?: recipient.address
    }
}

@Composable
private fun PlainTextBodySection(
    plainText: String,
    expandedQuoteIndices: Set<Int>,
    onToggleQuote: (Int) -> Unit,
    onLinkClick: (DetectedLink) -> Unit,
) {
    val segments = remember(plainText) { MessageTextFormatter.splitQuotedContent(plainText) }
    val linkColor = MaterialTheme.colorScheme.primary

    if (segments.isEmpty()) {
        Text(
            text = "（本文がありません）",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            segments.forEachIndexed { idx, segment ->
                when (segment) {
                    is BodySegment.Content -> {
                        val annotated = remember(segment.text, linkColor) {
                            MessageTextFormatter.buildLinkedAnnotatedString(
                                text = segment.text,
                                linkColor = linkColor,
                                onLinkClick = onLinkClick,
                            )
                        }
                        Text(
                            text = annotated,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    is BodySegment.Quote -> {
                        val expanded = idx in expandedQuoteIndices
                        OutlinedButton(onClick = { onToggleQuote(idx) }) {
                            Text(if (expanded) "引用を隠す" else "引用を表示 (${segment.lineCount}行)")
                        }
                        if (expanded) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                val annotatedQuote = remember(segment.text, linkColor) {
                                    MessageTextFormatter.buildLinkedAnnotatedString(
                                        text = segment.text,
                                        linkColor = linkColor,
                                        onLinkClick = onLinkClick,
                                    )
                                }
                                Text(
                                    text = annotatedQuote,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(10.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun handleDetectedLink(context: Context, link: DetectedLink) {
    when (link.kind) {
        DetectedLinkKind.URL -> openUrlSafely(context, link.value)
        DetectedLinkKind.EMAIL -> runCatching {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${link.value}"))
            context.startActivity(intent)
        }
        DetectedLinkKind.PHONE -> runCatching {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${link.value}"))
            context.startActivity(intent)
        }
    }
}

private fun openUrlSafely(context: Context, url: String) {
    val uri = Uri.parse(url)
    val scheme = uri.scheme?.lowercase()
    when (scheme) {
        "http", "https" -> runCatching {
            CustomTabsIntent.Builder().build().launchUrl(context, uri)
        }.onFailure {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        }
        "mailto", "tel" -> runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        }
    }
}

private fun formatBytes(bytes: Long): String =
    when {
        bytes < 1024L -> "${bytes}B"
        bytes < 1024L * 1024L -> "${bytes / 1024L}KB"
        else -> String.format(Locale.US, "%.1fMB", bytes / (1024.0 * 1024.0))
    }
