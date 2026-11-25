package com.kazemail.app.ui.ai

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kazemail.app.ai.AiClarificationAnswer
import com.kazemail.app.ai.AiClarificationPlan
import com.kazemail.app.ai.AiCompletionRequest
import com.kazemail.app.ai.AiCompletionResult
import com.kazemail.app.ai.AiPreparedEmailContext
import com.kazemail.app.ai.AiReadinessState
import com.kazemail.app.ai.AiReferenceEmailSummary
import com.kazemail.app.ai.AiReferenceFileKind
import com.kazemail.app.ai.AiReferenceFileSpec
import com.kazemail.app.ai.AiReplyTone
import com.kazemail.app.ai.AiWritingGoal
import com.kazemail.app.data.db.AiWritingPresetEntity
import com.kazemail.app.ui.theme.KazeIcons
import com.kazemail.app.ui.theme.LocalKazeDarkTheme
import com.kazemail.app.ui.theme.SyncGreenDark
import com.kazemail.app.ui.theme.SyncGreenLight
import java.util.Locale

/**
 * Shared AI assistant bottom sheet rendered from MessageDetailScreen and ComposeScreen.
 * Supports sanitized context preview, prompt/tone tuning, live reply/draft generation,
 * safe human-reviewed insertion into Compose, and thread summarization triggers.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AiPreparationSheet(
    title: String,
    readiness: AiReadinessState,
    isPreparingContext: Boolean,
    preparedContext: AiPreparedEmailContext?,
    selectedGoal: AiWritingGoal,
    selectedTone: AiReplyTone,
    presets: List<AiWritingPresetEntity>,
    instructionInput: String,
    builtPromptPreview: AiCompletionRequest?,
    primaryActionLabel: String?,
    onSelectTone: (AiReplyTone) -> Unit,
    onApplyPreset: (AiWritingPresetEntity) -> Unit,
    onInstructionChange: (String) -> Unit,
    onOpenAiSettings: () -> Unit,
    onPrimaryAction: (() -> Unit)?,
    onDismiss: () -> Unit,
    preferHtmlOutput: Boolean = false,
    onSelectPreferHtmlOutput: ((Boolean) -> Unit)? = null,
    availableGoals: List<AiWritingGoal> = emptyList(),
    onSelectGoal: ((AiWritingGoal) -> Unit)? = null,
    isGenerating: Boolean = false,
    generatedResult: AiCompletionResult? = null,
    generatedResultIsHtml: Boolean = false,
    generationError: String? = null,
    onGenerate: (() -> Unit)? = null,
    onCancelGeneration: (() -> Unit)? = null,
    onApplyReplaceDraft: (() -> Unit)? = null,
    onApplyAppendDraft: (() -> Unit)? = null,
    onSummarizeAction: (() -> Unit)? = null,
    referenceEmails: List<AiReferenceEmailSummary> = emptyList(),
    onOpenReferenceEmailPicker: (() -> Unit)? = null,
    onRemoveReferenceEmail: ((Long) -> Unit)? = null,
    referenceFiles: List<AiReferenceFileSpec> = emptyList(),
    onPickReferenceFiles: (() -> Unit)? = null,
    onRemoveReferenceFile: ((String) -> Unit)? = null,
    existingAttachmentsForAi: List<AiAttachableAttachmentCandidate> = emptyList(),
    onToggleExistingAttachmentForAi: ((String) -> Unit)? = null,
    referenceWarningMessage: String? = null,
    isGeneratingQuestions: Boolean = false,
    clarificationPlan: AiClarificationPlan? = null,
    clarificationAnswers: Map<String, AiClarificationAnswer> = emptyMap(),
    clarificationError: String? = null,
    onRequestClarificationQuestions: (() -> Unit)? = null,
    onCancelClarificationQuestions: (() -> Unit)? = null,
    onSelectClarificationOption: ((questionId: String, option: String) -> Unit)? = null,
    onUpdateClarificationCustomInput: ((questionId: String, input: String) -> Unit)? = null,
    onClearClarificationQuestions: (() -> Unit)? = null,
    signatureStatusNote: String? = null,
    onGenerateSubjectFromBody: (() -> Unit)? = null,
    isGeneratingSubject: Boolean = false,
) {
    var showRawPreview by remember { mutableStateOf(false) }
    val isDark = LocalKazeDarkTheme.current
    val readyColor = if (isDark) SyncGreenDark else SyncGreenLight

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = KazeIcons.AutoAwesome,
                    contentDescription = null,
                    tint = if (readiness.isReady) readyColor else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = readiness.summaryLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (readiness.isReady) readyColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = {
                        onDismiss()
                        onOpenAiSettings()
                    },
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("AI設定")
                }
            }

            if (readiness.actionHint.isNotBlank()) {
                Surface(
                    color = if (readiness.isReady) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = readiness.actionHint,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }

            if (isPreparingContext) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("スレッド文脈と個人情報マスク状態を準備中…", style = MaterialTheme.typography.bodySmall)
                }
            } else if (preparedContext != null) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "抽出済みメール文脈（${selectedGoal.displayLabel}）",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AssistChip(
                            onClick = {},
                            label = {
                                Text("メール ${preparedContext.includedMessageCount}/${preparedContext.totalMessageCount}通")
                            },
                        )
                        AssistChip(
                            onClick = {},
                            label = { Text("${preparedContext.totalChars}文字") },
                        )
                        AssistChip(
                            onClick = {},
                            label = { Text("引用除外 ${preparedContext.strippedQuoteBlocks}件") },
                        )
                        AssistChip(
                            onClick = {},
                            label = { Text("機密マスク ${preparedContext.redactionCount}件") },
                        )
                    }
                }
            }

            if (onOpenReferenceEmailPicker != null || onPickReferenceFiles != null || existingAttachmentsForAi.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "AI に見せる追加資料・別メール（任意）",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "選択した別のメール（最大5通）、画像・PDF（最大4件）、テキスト / Word・Excel・PowerPoint 文書も AI の参照文脈に含めます。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (onOpenReferenceEmailPicker != null) {
                                OutlinedButton(onClick = onOpenReferenceEmailPicker) {
                                    Icon(Icons.Filled.Email, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("別のメールを追加")
                                }
                            }
                            if (onPickReferenceFiles != null) {
                                OutlinedButton(onClick = onPickReferenceFiles) {
                                    Icon(KazeIcons.AttachFile, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("画像・ファイルを追加")
                                }
                            }
                        }

                        if (existingAttachmentsForAi.isNotEmpty() && onToggleExistingAttachmentForAi != null) {
                            Text(
                                text = "このメール / 下書きの添付ファイルから追加:",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                existingAttachmentsForAi.forEach { candidate ->
                                    FilterChip(
                                        selected = candidate.isAlreadyAdded,
                                        enabled = !candidate.isDownloading,
                                        onClick = { onToggleExistingAttachmentForAi(candidate.id) },
                                        leadingIcon = if (candidate.isDownloading) {
                                            {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(14.dp),
                                                    strokeWidth = 2.dp,
                                                )
                                            }
                                        } else null,
                                        label = {
                                            Text(
                                                text = candidate.label,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        },
                                    )
                                }
                            }
                        }

                        if (referenceEmails.isNotEmpty() || referenceFiles.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                referenceEmails.forEach { email ->
                                    InputChip(
                                        selected = true,
                                        onClick = {},
                                        label = {
                                            Text(
                                                text = "メール: ${email.subject}",
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        },
                                        trailingIcon = if (onRemoveReferenceEmail != null) {
                                            {
                                                IconButton(
                                                    onClick = { onRemoveReferenceEmail(email.metaId) },
                                                    modifier = Modifier.size(18.dp),
                                                ) {
                                                    Icon(
                                                        Icons.Filled.Close,
                                                        contentDescription = "参照メールを外す",
                                                        modifier = Modifier.size(14.dp),
                                                    )
                                                }
                                            }
                                        } else null,
                                    )
                                }
                                referenceFiles.forEach { file ->
                                    val kindBadge = when (file.kind) {
                                        AiReferenceFileKind.IMAGE -> "画像"
                                        AiReferenceFileKind.PDF -> "PDF"
                                        AiReferenceFileKind.OFFICE_DOCUMENT -> "文書"
                                        AiReferenceFileKind.TEXT_DOCUMENT -> "テキスト"
                                        AiReferenceFileKind.UNSUPPORTED -> "非対応"
                                    }
                                    InputChip(
                                        selected = file.kind.isSupportedForAi,
                                        onClick = {},
                                        label = {
                                            Text(
                                                text = "$kindBadge: ${file.displayName} (${formatSheetBytes(file.sizeBytes)})",
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        },
                                        trailingIcon = if (onRemoveReferenceFile != null) {
                                            {
                                                IconButton(
                                                    onClick = { onRemoveReferenceFile(file.id) },
                                                    modifier = Modifier.size(18.dp),
                                                ) {
                                                    Icon(
                                                        Icons.Filled.Close,
                                                        contentDescription = "参照ファイルを外す",
                                                        modifier = Modifier.size(14.dp),
                                                    )
                                                }
                                            }
                                        } else null,
                                    )
                                }
                            }
                        }

                        if (!referenceWarningMessage.isNullOrBlank()) {
                            Text(
                                text = referenceWarningMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            if (availableGoals.size > 1 && onSelectGoal != null) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("生成モード", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        availableGoals.forEach { goal ->
                            FilterChip(
                                selected = selectedGoal == goal,
                                onClick = { onSelectGoal(goal) },
                                label = { Text(goal.displayLabel) },
                            )
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("トーン選択", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AiReplyTone.entries.forEach { tone ->
                        FilterChip(
                            selected = selectedTone == tone,
                            onClick = { onSelectTone(tone) },
                            label = { Text(tone.displayLabel) },
                        )
                    }
                }
            }

            if (onSelectPreferHtmlOutput != null) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("本文の出力形式", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = !preferHtmlOutput,
                            onClick = { onSelectPreferHtmlOutput(false) },
                            leadingIcon = if (!preferHtmlOutput) {
                                {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            } else null,
                            label = { Text("プレーンテキスト") },
                        )
                        FilterChip(
                            selected = preferHtmlOutput,
                            onClick = { onSelectPreferHtmlOutput(true) },
                            leadingIcon = if (preferHtmlOutput) {
                                {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            } else null,
                            label = { Text("HTML形式") },
                        )
                    }
                    Text(
                        text = if (preferHtmlOutput) {
                            "見出し・太字・箇条書き等を含む HTML 形式で作成し、HTML 編集へ反映します。"
                        } else {
                            "装飾タグを使わない通常のテキスト形式で作成します（デフォルト）。"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (presets.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("テンプレートから指示を適用", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        presets.forEach { preset ->
                            AssistChip(
                                onClick = { onApplyPreset(preset) },
                                label = { Text(preset.title) },
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = instructionInput,
                onValueChange = onInstructionChange,
                label = { Text("AIへの返信・作成指示メモ（箇条書き可）") },
                placeholder = { Text("例: 来週火曜14時で了承と伝える、資料の確認感謝を添える") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )

            if (onRequestClarificationQuestions != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "AI からの逆質問（選択＋カスタム入力）",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "条件やニュアンスが曖昧なとき、AI が確認質問と選択肢を出します。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (clarificationPlan != null && onClearClarificationQuestions != null) {
                                TextButton(onClick = onClearClarificationQuestions) {
                                    Text("クリア")
                                }
                            }
                        }

                        if (isGeneratingQuestions) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(
                                    text = "AI が確認すべき質問と選択肢を作成中…",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                )
                                if (onCancelClarificationQuestions != null) {
                                    TextButton(onClick = onCancelClarificationQuestions) {
                                        Text("中止")
                                    }
                                }
                            }
                        } else {
                            OutlinedButton(
                                enabled = readiness.isReady && !isPreparingContext && !isGenerating,
                                onClick = onRequestClarificationQuestions,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    if (clarificationPlan != null) {
                                        "逆質問を更新・追加で確認する"
                                    } else {
                                        "AIから逆質問してもらう"
                                    },
                                )
                            }
                        }

                        if (!clarificationError.isNullOrBlank()) {
                            Text(
                                text = clarificationError,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        if (clarificationPlan != null) {
                            if (clarificationPlan.summary.isNotBlank()) {
                                Text(
                                    text = clarificationPlan.summary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            clarificationPlan.questions.forEachIndexed { idx, q ->
                                val answerState = clarificationAnswers[q.id]
                                val selectedSet = answerState?.selectedOptions?.toSet().orEmpty()
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            text = buildString {
                                                append("${idx + 1}. ${q.question}")
                                                if (q.allowMultiple) append("（複数選択可）")
                                            },
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                        )
                                        if (q.options.isNotEmpty() && onSelectClarificationOption != null) {
                                            FlowRow(
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                q.options.forEach { option ->
                                                    val selected = option in selectedSet
                                                    FilterChip(
                                                        selected = selected,
                                                        onClick = { onSelectClarificationOption(q.id, option) },
                                                        leadingIcon = if (selected) {
                                                            {
                                                                Icon(
                                                                    imageVector = Icons.Filled.Check,
                                                                    contentDescription = null,
                                                                    modifier = Modifier.size(16.dp),
                                                                )
                                                            }
                                                        } else null,
                                                        label = { Text(option) },
                                                    )
                                                }
                                            }
                                        }
                                        if (onUpdateClarificationCustomInput != null) {
                                            OutlinedTextField(
                                                value = answerState?.customInput.orEmpty(),
                                                onValueChange = { onUpdateClarificationCustomInput(q.id, it) },
                                                label = { Text("カスタム回答・補足（任意）") },
                                                placeholder = { Text(q.customPlaceholder) },
                                                minLines = 1,
                                                maxLines = 3,
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                        }
                                    }
                                }
                            }
                            val answeredCount = clarificationAnswers.values.count { it.hasAnswer }
                            if (answeredCount > 0) {
                                Text(
                                    text = "✓ ${answeredCount}件の回答を下のAI本文生成に反映します",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = readyColor,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
            }

            if (builtPromptPreview != null || preparedContext != null) {
                OutlinedButton(
                    onClick = { showRawPreview = !showRawPreview },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (showRawPreview) "サニタイズ済み送信プレビューを隠す" else "AIへ渡すサニタイズ済み文脈をプレビュー")
                }
                if (showRawPreview) {
                    val previewText = buildString {
                        builtPromptPreview?.let { req ->
                            appendLine("=== System Prompt ===")
                            appendLine(req.systemPrompt)
                            appendLine()
                            appendLine("=== User Prompt ===")
                            appendLine(req.userPrompt)
                        } ?: preparedContext?.let { ctx ->
                            appendLine(ctx.formattedContext)
                        }
                    }.trim()
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp),
                    ) {
                        Text(
                            text = previewText.ifEmpty { "（参照本文なし：新規メール作成モード）" },
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .padding(10.dp),
                        )
                    }
                }
            }

            HorizontalDivider()

            if (generationError != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = generationError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }

            if (isGenerating) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            text = "${readiness.providerType?.displayLabel ?: "AI"} が文面を作成中…",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        if (onCancelGeneration != null) {
                            TextButton(onClick = onCancelGeneration) {
                                Text("中止")
                            }
                        }
                    }
                }
            }

            if (generatedResult != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "生成結果プレビュー",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            val formatLabel = if (generatedResultIsHtml) "HTML" else "テキスト"
                            Text(
                                text = "$formatLabel • ${generatedResult.modelId} • ${generatedResult.latencyMs}ms",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        SelectionContainer {
                            Text(
                                text = generatedResult.text,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 220.dp)
                                    .verticalScroll(rememberScrollState()),
                            )
                        }
                        if (onApplyReplaceDraft != null || onApplyAppendDraft != null) {
                            Text(
                                text = buildString {
                                    append("自動送信はされません。反映後に本文欄で確認・編集できます（署名・引用は保持されます）。")
                                    if (!signatureStatusNote.isNullOrBlank()) {
                                        append("\n")
                                        append(signatureStatusNote)
                                    }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (onApplyAppendDraft != null) {
                                    OutlinedButton(
                                        onClick = {
                                            onApplyAppendDraft()
                                            onDismiss()
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text("本文へ追記")
                                    }
                                }
                                if (onApplyReplaceDraft != null) {
                                    Button(
                                        onClick = {
                                            onApplyReplaceDraft()
                                            onDismiss()
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text("下書き欄へ反映")
                                    }
                                }
                            }
                        }
                        if (onGenerateSubjectFromBody != null) {
                            OutlinedButton(
                                enabled = readiness.isReady && !isGeneratingSubject && !isGenerating,
                                onClick = {
                                    onGenerateSubjectFromBody()
                                    onDismiss()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (isGeneratingSubject) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                }
                                Text("この本文から件名を作成")
                            }
                        }
                    }
                }
            }

            if (onGenerate != null) {
                val hasAnsweredClarifications = clarificationAnswers.values.any { it.hasAnswer }
                Button(
                    enabled = readiness.isReady && !isGenerating && !isPreparingContext && !isGeneratingQuestions,
                    onClick = onGenerate,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = KazeIcons.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        when {
                            !readiness.isReady -> "AI設定を完了すると生成できます"
                            hasAnsweredClarifications && generatedResult != null -> "逆質問の回答を反映して再生成"
                            hasAnsweredClarifications -> "逆質問の回答を加味して本文生成"
                            generatedResult != null -> "条件を変えて再生成"
                            selectedGoal == AiWritingGoal.REWRITE_TONE -> "AI で現在の下書きを整える"
                            selectedGoal == AiWritingGoal.EXPAND_BULLETS -> "AI でメモからメール作成"
                            else -> "AI で返信・下書きを生成"
                        },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (onSummarizeAction != null) {
                    OutlinedButton(
                        enabled = readiness.isReady,
                        onClick = {
                            onDismiss()
                            onSummarizeAction()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("このメールを要約")
                    }
                } else {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("閉じる")
                    }
                }
                if (primaryActionLabel != null && onPrimaryAction != null) {
                    Button(
                        enabled = readiness.isReady,
                        onClick = {
                            onDismiss()
                            onPrimaryAction()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(primaryActionLabel)
                    }
                } else if (onSummarizeAction != null) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("閉じる")
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

private fun formatSheetBytes(bytes: Long): String =
    when {
        bytes < 1024L -> "${bytes}B"
        bytes < 1024L * 1024L -> "${bytes / 1024L}KB"
        else -> String.format(Locale.US, "%.1fMB", bytes / (1024.0 * 1024.0))
    }
