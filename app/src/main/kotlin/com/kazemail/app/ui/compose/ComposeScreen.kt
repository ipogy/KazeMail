package com.kazemail.app.ui.compose

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import com.kazemail.app.ui.theme.KazeIcons
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kazemail.app.ai.AiPreparationService
import com.kazemail.app.ui.ai.AiAttachableAttachmentCandidate
import com.kazemail.app.ui.ai.AiPreparationSheet
import com.kazemail.app.ui.ai.AiReferenceEmailPickerDialog
import com.kazemail.app.ui.viewer.HtmlEmailDetector
import com.kazemail.app.ui.viewer.SandboxedHtmlViewer
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ComposeScreen(
    viewModel: ComposeViewModel,
    onNavigateBack: () -> Unit,
    onMessageQueued: () -> Unit,
    onOpenAiSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val uiScope = rememberCoroutineScope()
    var showDiscardConfirmDialog by remember { mutableStateOf(false) }
    var showAiPrepSheet by remember { mutableStateOf(false) }
    var showAiEmailPicker by remember { mutableStateOf(false) }
    var showFromAccountMenu by remember { mutableStateOf(false) }
    var showHtmlPreviewTab by remember { mutableStateOf(false) }
    var wantHtmlPreviewAfterConfirm by remember { mutableStateOf(false) }
    var showHtmlLinkDialog by remember { mutableStateOf(false) }
    var showPerEmailSignatureDialog by remember { mutableStateOf(false) }
    var showQuoteExpanded by remember { mutableStateOf(false) }
    var htmlFieldValue by remember { mutableStateOf(TextFieldValue(state.bodyHtml)) }

    LaunchedEffect(state.bodyHtml) {
        if (state.bodyHtml != htmlFieldValue.text) {
            htmlFieldValue = TextFieldValue(
                text = state.bodyHtml,
                selection = TextRange(state.bodyHtml.length),
            )
        }
    }

    LaunchedEffect(state.isHtmlMode, state.pendingHtmlOverwriteConfirm) {
        if (!state.isHtmlMode) {
            showHtmlPreviewTab = false
        } else if (wantHtmlPreviewAfterConfirm && !state.pendingHtmlOverwriteConfirm) {
            showHtmlPreviewTab = true
            wantHtmlPreviewAfterConfirm = false
        }
    }

    val hasDraftContent = state.toInput.isNotBlank() ||
        state.ccInput.isNotBlank() ||
        state.bccInput.isNotBlank() ||
        state.subject.isNotBlank() ||
        state.bodyText.isNotBlank() ||
        state.bodyHtml.isNotBlank() ||
        state.signatureOverrideRaw != null ||
        state.attachments.isNotEmpty()

    val attachmentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.attachPickedUris(context, uris)
        }
    }

    val aiReferenceFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.addAiReferencePickedUris(context, uris)
        }
    }

    LaunchedEffect(state.queuedAndShouldClose) {
        if (state.queuedAndShouldClose) {
            onMessageQueued()
            onNavigateBack()
        }
    }

    LaunchedEffect(state.discardedAndShouldClose) {
        if (state.discardedAndShouldClose) {
            onNavigateBack()
        }
    }

    LaunchedEffect(state.aiAutoSheetRequested) {
        if (state.aiAutoSheetRequested) {
            showAiPrepSheet = true
            viewModel.consumeAiAutoSheetTrigger()
        }
    }

    BackHandler(enabled = !state.isSending) {
        viewModel.flushDraftAndNavigateBack(onNavigateBack)
    }

    if (showDiscardConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirmDialog = false },
            title = { Text("下書きを破棄しますか？") },
            text = { Text("入力中の本文・宛先・添付ファイルは元に戻せません。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardConfirmDialog = false
                        viewModel.discardDraft()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("破棄")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirmDialog = false }) {
                    Text("キャンセル")
                }
            },
        )
    }

    val screenTitle = when (state.mode) {
        ComposeUiState.MODE_REPLY -> "返信"
        ComposeUiState.MODE_REPLY_ALL -> "全員に返信"
        ComposeUiState.MODE_FORWARD -> "転送"
        else -> "新規メール"
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(screenTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = state.draftSaveStatus,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        enabled = !state.isSending,
                        onClick = { viewModel.flushDraftAndNavigateBack(onNavigateBack) },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "下書き保存して戻る")
                    }
                },
                actions = {
                    IconButton(
                        enabled = !state.isSending && !state.isLoading,
                        onClick = {
                            viewModel.prepareAiAssistSheet()
                            showAiPrepSheet = true
                        },
                    ) {
                        Icon(KazeIcons.AutoAwesome, contentDescription = "AI下書き準備")
                    }
                    IconButton(
                        enabled = !state.isSending,
                        onClick = { attachmentPicker.launch(arrayOf("*/*")) },
                    ) {
                        Icon(KazeIcons.AttachFile, contentDescription = "ファイルを添付")
                    }
                    TextButton(
                        enabled = !state.isSending,
                        onClick = {
                            if (hasDraftContent) {
                                showDiscardConfirmDialog = true
                            } else {
                                viewModel.discardDraft()
                            }
                        },
                    ) {
                        Text("破棄")
                    }
                    IconButton(
                        enabled = !state.isSending && !state.isLoading,
                        onClick = viewModel::queueSendToOutbox,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "送信キューへ")
                    }
                },
            )
        },
    ) { innerPadding ->
        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            state.errorMessage?.let { err ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                val canSwitchFromAccount = state.availableAccounts.size > 1 && !state.isSending
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    val fromLabel = state.fromName?.takeIf { it.isNotBlank() }?.let {
                        "$it <${state.fromEmail}>"
                    } ?: state.fromEmail
                    Box(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .let { base ->
                                    if (canSwitchFromAccount) {
                                        base.clickable { showFromAccountMenu = true }
                                    } else {
                                        base
                                    }
                                }
                                .padding(vertical = 4.dp),
                        ) {
                            Text(
                                text = "差出人: $fromLabel",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f, fill = false),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (canSwitchFromAccount) {
                                Icon(
                                    imageVector = Icons.Filled.ArrowDropDown,
                                    contentDescription = "差出人アドレスを切り替え",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = showFromAccountMenu,
                            onDismissRequest = { showFromAccountMenu = false },
                        ) {
                            state.availableAccounts.forEach { acc ->
                                val accLabel = acc.displayName?.takeIf { it.isNotBlank() }?.let {
                                    "$it <${acc.emailAddress}>"
                                } ?: acc.emailAddress
                                DropdownMenuItem(
                                    text = { Text(accLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    onClick = {
                                        showFromAccountMenu = false
                                        viewModel.selectFromAccount(acc.accountId)
                                    },
                                )
                            }
                        }
                    }
                    TextButton(onClick = viewModel::toggleCcBcc) {
                        Text(if (state.showCcBcc) "Cc/Bcc 隠す" else "Cc/Bcc")
                    }
                }
            }

            OutlinedTextField(
                value = state.toInput,
                onValueChange = viewModel::updateToInput,
                label = { Text("宛先 To (カンマ区切り)") },
                placeholder = { Text("alice@example.jp, bob@example.jp") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.showCcBcc) {
                OutlinedTextField(
                    value = state.ccInput,
                    onValueChange = viewModel::updateCcInput,
                    label = { Text("Cc") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.bccInput,
                    onValueChange = viewModel::updateBccInput,
                    label = { Text("Bcc") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            OutlinedTextField(
                value = state.subject,
                onValueChange = viewModel::updateSubject,
                label = { Text("件名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AssistChip(
                    onClick = {
                        viewModel.prepareAiAssistSheet()
                        showAiPrepSheet = true
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = KazeIcons.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    label = {
                        Text(
                            text = if (state.aiReadiness.isReady) {
                                "AIで下書き・返信生成 (${state.aiReadiness.providerType?.displayLabel ?: "AI"})"
                            } else {
                                "AI設定を確認: ${state.aiReadiness.summaryLabel}"
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
                AssistChip(
                    enabled = !state.isGeneratingAiSubject && !state.isSending,
                    onClick = {
                        if (state.aiReadiness.isReady) {
                            viewModel.generateAiSubjectFromBody()
                        } else {
                            onOpenAiSettings()
                        }
                    },
                    leadingIcon = if (state.isGeneratingAiSubject) {
                        {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    } else null,
                    label = {
                        Text(if (state.isGeneratingAiSubject) "件名を作成中…" else "本文から件名作成")
                    },
                )
                if (state.previousBodyBeforeAi != null) {
                    AssistChip(
                        onClick = viewModel::undoLastAiApply,
                        label = { Text("直前のAI反映を元に戻す") },
                    )
                }
                if (state.previousSubjectBeforeAi != null) {
                    AssistChip(
                        onClick = viewModel::undoLastAiSubject,
                        label = { Text("件名を元に戻す") },
                    )
                }
            }

            val subjectError = state.aiSubjectError
            if (subjectError != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = subjectError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = viewModel::dismissAiSubjectSuggestions,
                            modifier = Modifier.size(20.dp),
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "閉じる", modifier = Modifier.size(14.dp))
                        }
                    }
                }
            } else if (state.aiSubjectSuggestions.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val headerText = if (!state.aiSubjectSourceLabel.isNullOrBlank()) {
                                "AI 件名候補 (${state.aiSubjectSourceLabel})・タップして切替"
                            } else {
                                "AI 件名候補（タップして切替）"
                            }
                            Text(
                                text = headerText,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = viewModel::dismissAiSubjectSuggestions,
                                modifier = Modifier.size(20.dp),
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = "候補を閉じる", modifier = Modifier.size(14.dp))
                            }
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            state.aiSubjectSuggestions.forEach { candidate ->
                                FilterChip(
                                    selected = state.subject == candidate,
                                    onClick = { viewModel.applyAiSubjectCandidate(candidate) },
                                    label = {
                                        Text(
                                            text = candidate,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }

            if (state.attachments.isNotEmpty()) {
                Text(
                    text = "添付ファイル (${state.attachments.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    state.attachments.forEachIndexed { idx, att ->
                        InputChip(
                            selected = true,
                            onClick = {},
                            label = {
                                Text(
                                    text = "${att.filename} (${formatComposeBytes(att.sizeBytes)})",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingIcon = {
                                IconButton(
                                    onClick = { viewModel.removeAttachment(idx) },
                                    modifier = Modifier.size(18.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "添付を削除",
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            },
                        )
                    }
                }
            }

            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth(),
            ) {
                SegmentedButton(
                    selected = !state.isHtmlMode,
                    onClick = {
                        wantHtmlPreviewAfterConfirm = false
                        showHtmlPreviewTab = false
                        viewModel.toggleHtmlMode(false)
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                    label = { Text("テキスト", maxLines = 1) },
                )
                SegmentedButton(
                    selected = state.isHtmlMode && !showHtmlPreviewTab,
                    onClick = {
                        wantHtmlPreviewAfterConfirm = false
                        showHtmlPreviewTab = false
                        viewModel.toggleHtmlMode(true)
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                    label = { Text("HTML入力", maxLines = 1) },
                )
                SegmentedButton(
                    selected = state.isHtmlMode && showHtmlPreviewTab,
                    onClick = {
                        if (!state.isHtmlMode) {
                            wantHtmlPreviewAfterConfirm = true
                            viewModel.toggleHtmlMode(true)
                        } else {
                            showHtmlPreviewTab = true
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                    label = { Text("プレビュー", maxLines = 1) },
                )
            }

            if (!state.isHtmlMode) {
                OutlinedTextField(
                    value = state.bodyText,
                    onValueChange = viewModel::updateBodyText,
                    label = { Text("本文 (テキスト / 署名・引用は下で自動付与)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 220.dp),
                )
            } else if (showHtmlPreviewTab) {
                val combinedPreviewHtml = remember(
                    state.bodyHtml,
                    state.includeSignature,
                    state.signatureOverrideRaw,
                    state.accountDefaultSignature,
                    state.includeQuote,
                    state.quoteHtml,
                    state.quotePlain,
                ) {
                    ComposeHtmlHelper.buildFullOutgoingHtmlDocument(viewModel.buildCombinedHtmlBody(state))
                }
                Surface(
                    tonalElevation = 1.dp,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "HTMLメール送信プレビュー（本文＋署名＋引用・テキスト版も自動生成）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SandboxedHtmlViewer(
                            html = combinedPreviewHtml,
                            allowExternalImages = true,
                            isDarkSurface = isSystemInDarkTheme(),
                            onBlockedExternalResource = {},
                            onExternalLinkClick = { url ->
                                uiScope.launch {
                                    snackbarHostState.showSnackbar("プレビュー内リンク: $url")
                                }
                            },
                            fitScreenWidth = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 200.dp),
                        )
                    }
                }
            } else {
                val applyFormat: (HtmlFormatAction) -> Unit = { action ->
                    val res = ComposeHtmlHelper.applyFormattingAction(
                        text = htmlFieldValue.text,
                        selectionStart = htmlFieldValue.selection.start,
                        selectionEnd = htmlFieldValue.selection.end,
                        action = action,
                    )
                    htmlFieldValue = TextFieldValue(
                        text = res.newText,
                        selection = TextRange(res.selectionStart, res.selectionEnd),
                    )
                    viewModel.updateBodyHtml(res.newText)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AssistChip(onClick = { applyFormat(HtmlFormatAction.BOLD) }, label = { Text("太字") })
                    AssistChip(onClick = { applyFormat(HtmlFormatAction.ITALIC) }, label = { Text("斜体") })
                    AssistChip(onClick = { applyFormat(HtmlFormatAction.UNDERLINE) }, label = { Text("下線") })
                    AssistChip(onClick = { applyFormat(HtmlFormatAction.HEADING) }, label = { Text("見出し") })
                    AssistChip(onClick = { applyFormat(HtmlFormatAction.BULLET_LIST) }, label = { Text("箇条書き") })
                    AssistChip(onClick = { applyFormat(HtmlFormatAction.ACCENT_COLOR) }, label = { Text("赤字") })
                    AssistChip(onClick = { showHtmlLinkDialog = true }, label = { Text("リンク") })
                    AssistChip(onClick = { applyFormat(HtmlFormatAction.PARAGRAPH) }, label = { Text("段落 <p>") })
                    AssistChip(onClick = { applyFormat(HtmlFormatAction.LINE_BREAK) }, label = { Text("改行 <br>") })
                }
                OutlinedTextField(
                    value = htmlFieldValue,
                    onValueChange = { updated ->
                        val prevText = htmlFieldValue.text
                        htmlFieldValue = updated
                        if (updated.text != prevText) {
                            viewModel.updateBodyHtml(updated.text)
                        }
                    },
                    label = { Text("本文 HTML (タグ入力・署名と引用は下で自動付与)") },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 220.dp),
                )
            }

            // Separated Signature Card (safe from body tab switches!)
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val activeRawSig = (state.signatureOverrideRaw ?: state.accountDefaultSignature)
                        ?.takeIf { it.isNotBlank() }
                    val sigIsHtml = activeRawSig?.let { HtmlEmailDetector.containsActualHtml(it) } == true
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "メール署名 (${state.fromEmail})",
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val subtitle = when {
                                !state.includeSignature -> "オフ（このメールでは付与しません）"
                                activeRawSig == null -> "未設定（設定画面または右の編集から追加）"
                                else -> buildString {
                                    append("挿入ON・")
                                    append(if (sigIsHtml) "HTML署名" else "テキスト署名")
                                    if (state.signatureOverrideRaw != null) {
                                        append("（このメールのみ変更）")
                                    }
                                }
                            }
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { showPerEmailSignatureDialog = true }) {
                            Text("確認・編集")
                        }
                        Switch(
                            checked = state.includeSignature,
                            onCheckedChange = viewModel::setIncludeSignature,
                        )
                    }
                    if (state.includeSignature && activeRawSig != null) {
                        val previewSnippet = remember(activeRawSig, sigIsHtml) {
                            if (sigIsHtml) {
                                ComposeHtmlHelper.htmlToPlainText(activeRawSig)
                            } else {
                                activeRawSig
                            }
                        }
                        if (previewSnippet.isNotBlank()) {
                            Text(
                                text = previewSnippet,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            // Separated Quote / Forward Card (collapsible)
            if (state.quotePlain.isNotBlank() || !state.quoteHtml.isNullOrBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = if (state.mode == ComposeUiState.MODE_FORWARD) {
                                    "転送メッセージを含める"
                                } else {
                                    "引用メッセージを含める"
                                },
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { showQuoteExpanded = !showQuoteExpanded }) {
                                Text(if (showQuoteExpanded) "閉じる" else "内容を見る")
                            }
                            Switch(
                                checked = state.includeQuote,
                                onCheckedChange = viewModel::setIncludeQuote,
                            )
                        }
                        if (showQuoteExpanded && state.quotePlain.isNotBlank()) {
                            Text(
                                text = state.quotePlain,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 8,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    enabled = !state.isSending,
                    onClick = { attachmentPicker.launch(arrayOf("*/*")) },
                ) {
                    Icon(KazeIcons.AttachFile, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("添付追加")
                }

                Spacer(modifier = Modifier.weight(1f))

                Button(
                    enabled = !state.isSending,
                    onClick = viewModel::queueSendToOutbox,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (state.isSending) "キュー投入中…" else "送信 (Outbox)")
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showAiPrepSheet) {
        val isReplyContext = (state.replyToMetaId ?: 0L) > 0L
        val showUndoSnackbar = {
            uiScope.launch {
                val res = snackbarHostState.showSnackbar(
                    message = "AI の生成文面を本文へ反映しました（自動送信されません）",
                    actionLabel = "元に戻す",
                    duration = SnackbarDuration.Long,
                )
                if (res == SnackbarResult.ActionPerformed) {
                    viewModel.undoLastAiApply()
                }
            }
        }
        AiPreparationSheet(
            title = if (isReplyContext) {
                "AI 返信・下書きアシスタント"
            } else {
                "AI 新規作成アシスタント"
            },
            readiness = state.aiReadiness,
            isPreparingContext = state.isPreparingAiContext,
            preparedContext = state.aiPreparedContext,
            selectedGoal = viewModel.resolveAiWritingGoal(),
            selectedTone = state.aiSelectedTone,
            presets = state.aiPresets,
            instructionInput = state.aiInstructionInput,
            builtPromptPreview = state.aiBuiltPromptPreview,
            primaryActionLabel = null,
            onSelectTone = viewModel::selectAiTone,
            onApplyPreset = viewModel::applyAiPreset,
            onInstructionChange = viewModel::updateAiInstruction,
            onOpenAiSettings = onOpenAiSettings,
            onPrimaryAction = null,
            onDismiss = { showAiPrepSheet = false },
            preferHtmlOutput = state.aiPreferHtmlOutput,
            onSelectPreferHtmlOutput = viewModel::selectAiPreferHtmlOutput,
            availableGoals = viewModel.availableAiWritingGoals(),
            onSelectGoal = viewModel::selectAiGoal,
            isGenerating = state.isGeneratingAiDraft,
            generatedResult = state.aiGeneratedDraft,
            generatedResultIsHtml = state.aiGeneratedDraftIsHtml,
            generationError = state.aiGenerationError,
            onGenerate = viewModel::generateAiDraft,
            onCancelGeneration = viewModel::cancelAiGeneration,
            onApplyReplaceDraft = {
                viewModel.applyGeneratedAiDraft(append = false)
                showUndoSnackbar()
            },
            onApplyAppendDraft = {
                viewModel.applyGeneratedAiDraft(append = true)
                showUndoSnackbar()
            },
            referenceEmails = state.aiReferenceEmails,
            onOpenReferenceEmailPicker = {
                viewModel.openAiEmailPicker()
                showAiEmailPicker = true
            },
            onRemoveReferenceEmail = viewModel::removeAiReferenceEmail,
            referenceFiles = state.aiReferenceFiles,
            onPickReferenceFiles = { aiReferenceFilePicker.launch(arrayOf("*/*")) },
            onRemoveReferenceFile = viewModel::removeAiReferenceFile,
            existingAttachmentsForAi = state.attachments.map { att ->
                AiAttachableAttachmentCandidate(
                    id = att.filePath,
                    label = "${att.filename} (${formatComposeBytes(att.sizeBytes)})",
                    isAlreadyAdded = state.aiReferenceFiles.any { it.id == "draft:${att.filePath}" },
                )
            },
            onToggleExistingAttachmentForAi = { filePath ->
                viewModel.toggleDraftAttachmentForAi(context, filePath)
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
            signatureStatusNote = if (state.isConfiguredSignatureActive) {
                "✓ ${state.fromEmail} の設定済み署名が反映時に本文末尾へ自動適用されます"
            } else {
                "※ ${state.fromEmail} の署名は現在オフ（アカウント設定／設定画面から個別設定可能）"
            },
            onGenerateSubjectFromBody = viewModel::generateAiSubjectFromBody,
            isGeneratingSubject = state.isGeneratingAiSubject,
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

    if (showHtmlLinkDialog) {
        val currentSelection = htmlFieldValue.text.substring(
            minOf(htmlFieldValue.selection.start, htmlFieldValue.selection.end).coerceIn(0, htmlFieldValue.text.length),
            maxOf(htmlFieldValue.selection.start, htmlFieldValue.selection.end).coerceIn(0, htmlFieldValue.text.length),
        )
        var urlInput by remember(showHtmlLinkDialog) { mutableStateOf("") }
        var labelInput by remember(showHtmlLinkDialog) { mutableStateOf(currentSelection) }
        AlertDialog(
            onDismissRequest = { showHtmlLinkDialog = false },
            title = { Text("リンクを挿入") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        label = { Text("URL / メールアドレス") },
                        placeholder = { Text("https://example.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = labelInput,
                        onValueChange = { labelInput = it },
                        label = { Text("表示テキスト（空欄ならURL）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = urlInput.isNotBlank(),
                    onClick = {
                        val res = ComposeHtmlHelper.insertLink(
                            text = htmlFieldValue.text,
                            selectionStart = htmlFieldValue.selection.start,
                            selectionEnd = htmlFieldValue.selection.end,
                            rawUrl = urlInput,
                            rawLabel = labelInput,
                        )
                        htmlFieldValue = TextFieldValue(
                            text = res.newText,
                            selection = TextRange(res.selectionStart, res.selectionEnd),
                        )
                        viewModel.updateBodyHtml(res.newText)
                        showHtmlLinkDialog = false
                    },
                ) {
                    Text("挿入")
                }
            },
                    dismissButton = {
                TextButton(onClick = { showHtmlLinkDialog = false }) {
                    Text("キャンセル")
                }
            },
        )
    }

    if (showPerEmailSignatureDialog) {
        val currentRaw = state.signatureOverrideRaw ?: state.accountDefaultSignature.orEmpty()
        var draftInclude by remember(showPerEmailSignatureDialog, state.includeSignature) {
            mutableStateOf(state.includeSignature)
        }
        var draftRawSig by remember(showPerEmailSignatureDialog, currentRaw) {
            mutableStateOf(currentRaw)
        }
        AlertDialog(
            onDismissRequest = { showPerEmailSignatureDialog = false },
            title = { Text("このメールの署名設定") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "このメールに署名を挿入する",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = draftInclude, onCheckedChange = { draftInclude = it })
                    }
                    OutlinedTextField(
                        value = draftRawSig,
                        onValueChange = { draftRawSig = it },
                        label = { Text("署名内容（プレーン／HTML可・今回限り）") },
                        minLines = 4,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (HtmlEmailDetector.containsActualHtml(draftRawSig)) {
                        Text(
                            text = "HTML署名として保持されます（テキスト形式時はタグ除去して自動挿入）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (state.signatureOverrideRaw != null) {
                        TextButton(
                            onClick = {
                                viewModel.resetSignatureOverride()
                                showPerEmailSignatureDialog = false
                            },
                        ) {
                            Text("${state.fromEmail} の既定署名に戻す")
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.updateSignatureOverride(draftRawSig)
                        viewModel.setIncludeSignature(draftInclude)
                        showPerEmailSignatureDialog = false
                    },
                ) {
                    Text("適用")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPerEmailSignatureDialog = false }) {
                    Text("キャンセル")
                }
            },
        )
    }

    if (state.pendingHtmlOverwriteConfirm) {
        AlertDialog(
            onDismissRequest = {
                wantHtmlPreviewAfterConfirm = false
                viewModel.dismissHtmlOverwriteConfirm()
            },
            title = { Text("HTML書式の扱いを確認") },
            text = {
                Text(
                    "テキストモードで本文が変更されています。テキスト側の変更をHTMLへ反映すると、" +
                        "以前のHTML装飾（太字・リンク・箇条書き等）はリセットされます。（署名は自動で保持されます）",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.confirmSwitchToHtmlWithTextChanges(applyTextChanges = true)
                    },
                ) {
                    Text("テキスト変更を反映")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.confirmSwitchToHtmlWithTextChanges(applyTextChanges = false)
                    },
                ) {
                    Text("元のHTMLを復元")
                }
            },
        )
    }
}

private fun formatComposeBytes(bytes: Long): String =
    when {
        bytes < 1024L -> "${bytes}B"
        bytes < 1024L * 1024L -> "${bytes / 1024L}KB"
        else -> String.format(Locale.US, "%.1fMB", bytes / (1024.0 * 1024.0))
    }
