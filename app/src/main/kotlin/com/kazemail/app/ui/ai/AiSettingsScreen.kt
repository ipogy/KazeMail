package com.kazemail.app.ui.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kazemail.app.ai.AiConnectionTestResult
import com.kazemail.app.ai.AiConnectionTestStatus
import com.kazemail.app.ai.AiProviderProfileSummary
import com.kazemail.app.ai.AiProviderType
import com.kazemail.app.ai.AiReadinessStatus
import com.kazemail.app.ai.AiReplyTone
import com.kazemail.app.ai.AiWritingGoal
import com.kazemail.app.data.db.AiWritingPresetEntity
import com.kazemail.app.ui.common.DateFormatters
import com.kazemail.app.ui.theme.KazeIcons
import com.kazemail.app.ui.theme.LocalKazeDarkTheme
import com.kazemail.app.ui.theme.SyncGreenDark
import com.kazemail.app.ui.theme.SyncGreenLight

@Composable
fun AiSettingsScreen(
    viewModel: AiSettingsViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.statusMessage) {
        val msg = state.statusMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearStatusMessage()
    }

    AiSettingsContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onNavigateBack = onNavigateBack,
        onRequestToggleEnabled = viewModel::onToggleFeaturesRequested,
        onAcceptConsent = viewModel::acceptPrivacyConsentAndEnable,
        onRevokeConsent = viewModel::revokePrivacyConsent,
        onSaveProfile = viewModel::saveProfile,
        onTestSavedProfile = viewModel::testSavedProfileConnection,
        onTestDraftConnection = viewModel::testDraftConnection,
        onClearDraftTestResult = viewModel::clearDraftTestResult,
        onSetDefaultProfile = viewModel::setDefaultProfile,
        onDeleteProfile = viewModel::deleteProfile,
        onSetSubjectProfile = viewModel::setSubjectProfileId,
        onSaveSubjectModelOverride = viewModel::saveSubjectModelOverride,
        onSaveSubjectCustomInstruction = viewModel::saveSubjectCustomInstruction,
        onTestSubjectConfig = viewModel::testSubjectConfigurationConnection,
        onSelectDefaultTone = viewModel::setDefaultTone,
        onSelectDefaultPreferHtml = viewModel::setDefaultPreferHtml,
        onSaveCustomInstruction = viewModel::saveCustomSystemInstruction,
        onToggleStripQuotes = viewModel::setStripQuotedReplies,
        onToggleRedactSensitive = viewModel::setRedactSensitivePatterns,
        onSelectMaxContextChars = viewModel::setMaxContextChars,
        onToggleIncludeSignature = viewModel::setIncludeSignatureGuidance,
        onSavePreset = viewModel::savePreset,
        onDeletePreset = viewModel::deletePreset,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AiSettingsContent(
    state: AiSettingsUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateBack: () -> Unit,
    onRequestToggleEnabled: (Boolean) -> Boolean,
    onAcceptConsent: () -> Unit,
    onRevokeConsent: () -> Unit,
    onSaveProfile: (
        profileId: Long?,
        displayName: String,
        providerType: AiProviderType,
        baseUrl: String,
        modelId: String,
        rawApiKeyInput: String?,
        setAsDefault: Boolean,
        onSaved: () -> Unit,
    ) -> Unit,
    onTestSavedProfile: (Long) -> Unit,
    onTestDraftConnection: (
        providerType: AiProviderType,
        baseUrl: String,
        modelId: String,
        rawApiKeyInput: String?,
        existingProfileId: Long?,
    ) -> Unit,
    onClearDraftTestResult: () -> Unit,
    onSetDefaultProfile: (Long) -> Unit,
    onDeleteProfile: (Long) -> Unit,
    onSetSubjectProfile: (Long?) -> Unit,
    onSaveSubjectModelOverride: (String) -> Unit,
    onSaveSubjectCustomInstruction: (String) -> Unit,
    onTestSubjectConfig: () -> Unit,
    onSelectDefaultTone: (AiReplyTone) -> Unit,
    onSelectDefaultPreferHtml: (Boolean) -> Unit,
    onSaveCustomInstruction: (String) -> Unit,
    onToggleStripQuotes: (Boolean) -> Unit,
    onToggleRedactSensitive: (Boolean) -> Unit,
    onSelectMaxContextChars: (Int) -> Unit,
    onToggleIncludeSignature: (Boolean) -> Unit,
    onSavePreset: (
        presetId: Long?,
        title: String,
        goal: AiWritingGoal,
        tone: AiReplyTone,
        instructionPrompt: String,
        onSaved: () -> Unit,
    ) -> Unit,
    onDeletePreset: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showConsentDialog by remember { mutableStateOf(false) }
    var showCustomInstructionDialog by remember { mutableStateOf(false) }
    var showSubjectModelDialog by remember { mutableStateOf(false) }
    var showSubjectInstructionDialog by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<AiProviderProfileSummary?>(null) }
    var showProfileDialog by remember { mutableStateOf(false) }
    var pendingDeleteProfile by remember { mutableStateOf<AiProviderProfileSummary?>(null) }
    var editingPreset by remember { mutableStateOf<AiWritingPresetEntity?>(null) }
    var showPresetDialog by remember { mutableStateOf(false) }
    var pendingDeletePreset by remember { mutableStateOf<AiWritingPresetEntity?>(null) }

    if (showConsentDialog) {
        AiPrivacyConsentDialog(
            alreadyConsented = state.privacyConsentAccepted,
            onAccept = {
                showConsentDialog = false
                onAcceptConsent()
            },
            onRevoke = {
                showConsentDialog = false
                onRevokeConsent()
            },
            onDismiss = { showConsentDialog = false },
        )
    }

    if (showSubjectModelDialog) {
        val defaultProfile = state.profiles.firstOrNull { it.isDefault } ?: state.profiles.firstOrNull()
        val resolvedProfile = state.subjectProfileId?.let { id ->
            state.profiles.firstOrNull { it.profileId == id }
        } ?: defaultProfile
        SubjectModelOverrideDialog(
            currentOverride = state.subjectModelOverride,
            resolvedProfile = resolvedProfile,
            onSave = { modelId ->
                showSubjectModelDialog = false
                onSaveSubjectModelOverride(modelId)
            },
            onDismiss = { showSubjectModelDialog = false },
        )
    }

    if (showSubjectInstructionDialog) {
        SubjectCustomInstructionDialog(
            currentInstruction = state.subjectCustomInstruction,
            onSave = { instruction ->
                showSubjectInstructionDialog = false
                onSaveSubjectCustomInstruction(instruction)
            },
            onDismiss = { showSubjectInstructionDialog = false },
        )
    }

    if (showCustomInstructionDialog) {
        var draftInstruction by remember(state.customSystemInstruction) {
            mutableStateOf(state.customSystemInstruction)
        }
        AlertDialog(
            onDismissRequest = { showCustomInstructionDialog = false },
            title = { Text("共通の執筆方針・補足指示") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "返信や新規下書きの生成時に常時添えられる基本方針です（例: 「送り手名義は株式会社〇〇の田中」「結論を先に書く」）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = draftInstruction,
                        onValueChange = { draftInstruction = it },
                        label = { Text("補足プロンプト（任意）") },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSaveCustomInstruction(draftInstruction)
                        showCustomInstructionDialog = false
                    },
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomInstructionDialog = false }) {
                    Text("キャンセル")
                }
            },
        )
    }

    if (showProfileDialog) {
        AiProfileEditorDialog(
            existing = editingProfile,
            isTesting = state.draftTestInProgress,
            testResult = state.draftTestResult,
            onTestConnection = { provider, baseUrl, modelId, rawKey ->
                onTestDraftConnection(provider, baseUrl, modelId, rawKey, editingProfile?.profileId)
            },
            onSave = { displayName, provider, baseUrl, modelId, rawKey, setAsDefault ->
                onSaveProfile(
                    editingProfile?.profileId,
                    displayName,
                    provider,
                    baseUrl,
                    modelId,
                    rawKey,
                    setAsDefault,
                ) {
                    showProfileDialog = false
                    editingProfile = null
                }
            },
            onDismiss = {
                onClearDraftTestResult()
                showProfileDialog = false
                editingProfile = null
            },
        )
    }

    pendingDeleteProfile?.let { prof ->
        AlertDialog(
            onDismissRequest = { pendingDeleteProfile = null },
            title = { Text("AIプロファイルを削除") },
            text = { Text("「${prof.displayName}」および Keystore に保存されている API キー設定を削除しますか？") },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteProfile(prof.profileId)
                        pendingDeleteProfile = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("削除")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteProfile = null }) {
                    Text("キャンセル")
                }
            },
        )
    }

    if (showPresetDialog) {
        AiPresetEditorDialog(
            existing = editingPreset,
            onSave = { title, goal, tone, prompt ->
                onSavePreset(editingPreset?.presetId, title, goal, tone, prompt) {
                    showPresetDialog = false
                    editingPreset = null
                }
            },
            onDismiss = {
                showPresetDialog = false
                editingPreset = null
            },
        )
    }

    pendingDeletePreset?.let { preset ->
        AlertDialog(
            onDismissRequest = { pendingDeletePreset = null },
            title = { Text("テンプレートを削除") },
            text = { Text("「${preset.title}」を削除しますか？") },
            confirmButton = {
                Button(
                    onClick = {
                        onDeletePreset(preset.presetId)
                        pendingDeletePreset = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("削除")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeletePreset = null }) {
                    Text("キャンセル")
                }
            },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("AI機能・返信アシスト設定") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "ai_readiness_banner") {
                AiReadinessBannerCard(
                    state = state,
                    onOpenConsent = { showConsentDialog = true },
                    onAddProvider = {
                        editingProfile = null
                        onClearDraftTestResult()
                        showProfileDialog = true
                    },
                )
            }

            item(key = "section_ai_activation") {
                AiSectionCard(title = "有効化・セキュリティ") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val next = !state.featuresEnabled
                                if (!onRequestToggleEnabled(next)) {
                                    showConsentDialog = true
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("AI機能を有効にする", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "オンにしても、ユーザーが返信作成等で明示実行しない限りメール内容は外部へ送信されません",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = state.featuresEnabled,
                            onCheckedChange = { next ->
                                if (!onRequestToggleEnabled(next)) {
                                    showConsentDialog = true
                                }
                            },
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showConsentDialog = true }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = KazeIcons.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("外部API送信ポリシー & Keystore保護", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = if (state.privacyConsentAccepted) {
                                    "同意済み • 自動送信なし（必ず作成画面で確認）"
                                } else {
                                    "未同意（タップして送信範囲と安全設計を確認）"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { showConsentDialog = true }) {
                            Text(if (state.privacyConsentAccepted) "確認" else "同意")
                        }
                    }
                }
            }

            item(key = "section_ai_providers") {
                AiSectionCard(title = "AIプロバイダ / APIキー (BYOK)") {
                    if (!state.hasLoaded) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else if (state.profiles.isEmpty()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "AIプロバイダがまだ登録されていません",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = "Gemini・OpenAI・Claude または OpenAI互換エンドポイントの API キーを登録すると、Android Keystore (AES-256-GCM) で二重暗号化して保存し、そのまま疎通テストできます。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        state.profiles.forEachIndexed { idx, profile ->
                            if (idx > 0) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                )
                            }
                            AiProfileRow(
                                profile = profile,
                                isTesting = state.testingProfileId == profile.profileId,
                                onTestConnection = { onTestSavedProfile(profile.profileId) },
                                onSetDefault = { onSetDefaultProfile(profile.profileId) },
                                onEdit = {
                                    editingProfile = profile
                                    onClearDraftTestResult()
                                    showProfileDialog = true
                                },
                                onDelete = { pendingDeleteProfile = profile },
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                    Button(
                        onClick = {
                            editingProfile = null
                            onClearDraftTestResult()
                            showProfileDialog = true
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("AIプロバイダを追加")
                    }
                }
            }

            item(key = "section_ai_subject_override") {
                val defaultProfile = remember(state.profiles) {
                    state.profiles.firstOrNull { it.isDefault } ?: state.profiles.firstOrNull()
                }
                val selectedSubjectProfile = remember(state.profiles, state.subjectProfileId) {
                    state.subjectProfileId?.let { id ->
                        state.profiles.firstOrNull { it.profileId == id }
                    }
                }
                val resolvedSubjectProfile = selectedSubjectProfile ?: defaultProfile
                val effectiveModelLabel = state.subjectModelOverride.trim().ifBlank { null }
                    ?: resolvedSubjectProfile?.modelId
                    ?: "未設定"
                var profileMenuExpanded by remember { mutableStateOf(false) }

                AiSectionCard(title = "件名作成 AI（別枠設定）") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "「本文から件名作成」だけ別のプロバイダ・軽量モデル・専用プロンプトを使えます。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { profileMenuExpanded = true },
                                enabled = state.profiles.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = when {
                                        selectedSubjectProfile != null ->
                                            "プロバイダ: ${selectedSubjectProfile.displayName} (${selectedSubjectProfile.providerType.displayLabel})"
                                        defaultProfile != null ->
                                            "プロバイダ: 標準と同じ (${defaultProfile.displayName})"
                                        else -> "プロバイダ: 未登録"
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                            }
                            DropdownMenu(
                                expanded = profileMenuExpanded,
                                onDismissRequest = { profileMenuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (defaultProfile != null) {
                                                "標準と同じ (${defaultProfile.displayName})"
                                            } else {
                                                "標準と同じ"
                                            },
                                        )
                                    },
                                    onClick = {
                                        profileMenuExpanded = false
                                        onSetSubjectProfile(null)
                                    },
                                )
                                state.profiles.forEach { profile ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "${profile.displayName} (${profile.providerType.displayLabel} / ${profile.modelId})",
                                            )
                                        },
                                        onClick = {
                                            profileMenuExpanded = false
                                            onSetSubjectProfile(profile.profileId)
                                        },
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = resolvedSubjectProfile != null) {
                                    showSubjectModelDialog = true
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("件名用モデル ID", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = if (state.subjectModelOverride.isNotBlank()) {
                                        "上書き指定: ${state.subjectModelOverride}"
                                    } else {
                                        "プロファイルのモデルを使用 ($effectiveModelLabel)"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TextButton(
                                onClick = { showSubjectModelDialog = true },
                                enabled = resolvedSubjectProfile != null,
                            ) {
                                Text("変更")
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showSubjectInstructionDialog = true }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("件名作成専用の追加指示", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = state.subjectCustomInstruction.ifBlank {
                                        "未設定（例: 20文字以内、【要確認】を先頭に付ける など）"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TextButton(onClick = { showSubjectInstructionDialog = true }) {
                                Text("編集")
                            }
                        }

                        OutlinedButton(
                            onClick = onTestSubjectConfig,
                            enabled = resolvedSubjectProfile != null && !state.testingSubjectConfig,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (state.testingSubjectConfig) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("件名 AI 接続テスト中…")
                            } else {
                                Icon(
                                    Icons.Filled.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("件名作成 AI の接続をテスト")
                            }
                        }
                    }
                }
            }

            item(key = "section_ai_writing_style") {
                AiSectionCard(title = "標準の文体・執筆ポリシー") {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            text = "既定の返信トーン",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AiReplyTone.entries.forEach { tone ->
                                FilterChip(
                                    selected = state.defaultTone == tone,
                                    onClick = { onSelectDefaultTone(tone) },
                                    label = { Text(tone.displayLabel) },
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = state.defaultTone.promptDirective,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    )
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            text = "既定の AI 本文出力形式",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = !state.defaultPreferHtml,
                                onClick = { onSelectDefaultPreferHtml(false) },
                                label = { Text("プレーンテキスト（標準）") },
                            )
                            FilterChip(
                                selected = state.defaultPreferHtml,
                                onClick = { onSelectDefaultPreferHtml(true) },
                                label = { Text("HTML形式") },
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (state.defaultPreferHtml) {
                                "AI で本文を作成する際の初期選択を HTML 形式にします（生成画面でも切替可能）。"
                            } else {
                                "AI で本文を作成する際の初期選択をプレーンテキストにします（生成画面でも切替可能）。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showCustomInstructionDialog = true }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("共通の補足プロンプト（あなたの立場・文体）", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = state.customSystemInstruction.ifBlank { "未設定（タップして入力）" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { showCustomInstructionDialog = true }) {
                            Icon(Icons.Filled.Edit, contentDescription = "補足プロンプトを編集")
                        }
                    }
                }
            }

            item(key = "section_ai_context_privacy") {
                AiSectionCard(title = "文脈抽出・プライバシーガード") {
                    AiSwitchRow(
                        title = "過去の引用（> 始まり）を除外して送信",
                        subtitle = "深い引用連鎖を省き、最新のやり取りだけに絞ってトークン消費と漏洩リスクを減らします",
                        checked = state.stripQuotedReplies,
                        onCheckedChange = onToggleStripQuotes,
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    )
                    AiSwitchRow(
                        title = "APIキー・カード番号・認証コードを自動マスク",
                        subtitle = "AI API へ渡す直前に秘密トークンやワンタイムコード等を [REDACTED] に置換します",
                        checked = state.redactSensitivePatterns,
                        onCheckedChange = onToggleRedactSensitive,
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    )
                    AiSwitchRow(
                        title = "署名の重複生成を防ぐヒントを含める",
                        subtitle = "作成画面の自動署名を考慮し、AI下書き末尾への二重署名を抑制します",
                        checked = state.includeSignatureGuidance,
                        onCheckedChange = onToggleIncludeSignature,
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    )
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            text = "送信コンテキスト上限文字数",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "スレッドが長い場合、最新メールを優先して上限文字数内に収めます",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(3000 to "3,000字", 6000 to "6,000字", 12000 to "12,000字").forEach { (chars, label) ->
                                FilterChip(
                                    selected = state.maxContextChars == chars,
                                    onClick = { onSelectMaxContextChars(chars) },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }
                }
            }

            item(key = "section_ai_presets") {
                AiSectionCard(title = "返信・作成テンプレート (${state.presets.size})") {
                    if (state.presets.isEmpty()) {
                        Text(
                            text = "テンプレートを読み込み中…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    } else {
                        state.presets.forEachIndexed { idx, preset ->
                            if (idx > 0) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = preset.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    val goalLabel = AiWritingGoal.fromStorage(preset.goal).displayLabel
                                    val toneLabel = AiReplyTone.fromStorage(preset.tone).displayLabel
                                    Text(
                                        text = "$goalLabel • $toneLabel",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = preset.instructionPrompt,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        editingPreset = preset
                                        showPresetDialog = true
                                    },
                                ) {
                                    Icon(Icons.Filled.Edit, contentDescription = "テンプレートを編集")
                                }
                                IconButton(onClick = { pendingDeletePreset = preset }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "テンプレートを削除",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                    OutlinedButton(
                        onClick = {
                            editingPreset = null
                            showPresetDialog = true
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("テンプレートを追加")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiReadinessBannerCard(
    state: AiSettingsUiState,
    onOpenConsent: () -> Unit,
    onAddProvider: () -> Unit,
) {
    val isDark = LocalKazeDarkTheme.current
    val syncGreen = if (isDark) SyncGreenDark else SyncGreenLight
    val containerColor = when (state.readiness.status) {
        AiReadinessStatus.READY -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    Surface(
        color = containerColor,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = KazeIcons.AutoAwesome,
                    contentDescription = null,
                    tint = if (state.readiness.isReady) syncGreen else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = state.readiness.summaryLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (state.readiness.actionHint.isNotBlank()) {
                Text(
                    text = state.readiness.actionHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AssistChip(
                    onClick = onOpenConsent,
                    label = {
                        Text(if (state.privacyConsentAccepted) "外部送信ポリシー: 同意済" else "外部送信ポリシー: 未同意")
                    },
                )
                AssistChip(
                    onClick = onAddProvider,
                    label = {
                        Text(if (state.profiles.isNotEmpty()) "APIプロファイル: ${state.profiles.size}件" else "APIプロファイル: 未登録")
                    },
                )
                AssistChip(
                    onClick = {},
                    label = { Text("手動確認送信: 常時ON") },
                )
            }
        }
    }
}

@Composable
private fun AiProfileRow(
    profile: AiProviderProfileSummary,
    isTesting: Boolean,
    onTestConnection: () -> Unit,
    onSetDefault: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val isDark = LocalKazeDarkTheme.current
    val okColor = if (isDark) SyncGreenDark else SyncGreenLight

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = profile.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (profile.isDefault) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.extraSmall,
                        ) {
                            Text(
                                text = "標準",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Text(
                    text = "${profile.providerType.displayLabel} • ${profile.modelId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(profile.baseUrl)
                        profile.apiKeyHint?.let { append(" • Keystore: $it") }
                            ?: append(if (profile.providerType.requiresApiKey) " • キー未設定" else " • キー任意")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onTestConnection, enabled = !isTesting) {
                if (isTesting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = "接続テスト")
                }
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "編集")
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "削除",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }

        val statusColor = when (profile.lastTestStatus) {
            AiConnectionTestStatus.SUCCESS -> okColor
            AiConnectionTestStatus.FAILED -> MaterialTheme.colorScheme.error
            AiConnectionTestStatus.UNTESTED -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val testedTime = profile.lastTestedAt?.let { " (${DateFormatters.formatMonthDayTime(it)})" }.orEmpty()
            val detail = profile.lastTestMessage ?: profile.lastTestStatus.displayLabel
            Text(
                text = "${profile.lastTestStatus.displayLabel}$testedTime: $detail",
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!profile.isDefault) {
                TextButton(onClick = onSetDefault) {
                    Text("標準にする")
                }
            }
        }
    }
}

@Composable
private fun AiPrivacyConsentDialog(
    alreadyConsented: Boolean,
    onAccept: () -> Unit,
    onRevoke: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI機能のデータ送信と安全設計") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "KazeMail で AI 返信・下書き支援を利用する前に、以下の動作方針を確認してください。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "1. 明示操作時のみ送信: ユーザーが AI 生成を実行したメールの件名・本文（および直近スレッド）、ユーザーが明示的に追加選択した参考メールや画像・ファイル、指示文のみを設定済み AI プロバイダへ HTTPS 送信します。未選択のメールや添付ファイルを勝手には送信しません。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "2. Keystore 二重保護: 登録した API キーは Android Keystore (AES-256-GCM) と SQLCipher 暗号化 DB で保護されます。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "3. 自動送信禁止: AI は下書き提案のみを行い、人間の確認・編集なしにメールを送信することはありません。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Button(onClick = onAccept) {
                Text(if (alreadyConsented) "同意を維持" else "同意して有効化")
            }
        },
        dismissButton = {
            Row {
                if (alreadyConsented) {
                    TextButton(
                        onClick = onRevoke,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text("同意を取り消す")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("閉じる")
                }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiProfileEditorDialog(
    existing: AiProviderProfileSummary?,
    isTesting: Boolean,
    testResult: AiConnectionTestResult?,
    onTestConnection: (
        providerType: AiProviderType,
        baseUrl: String,
        modelId: String,
        rawApiKeyInput: String?,
    ) -> Unit,
    onSave: (
        displayName: String,
        providerType: AiProviderType,
        baseUrl: String,
        modelId: String,
        rawApiKeyInput: String?,
        setAsDefault: Boolean,
    ) -> Unit,
    onDismiss: () -> Unit,
) {
    var providerType by remember(existing) {
        mutableStateOf(existing?.providerType ?: AiProviderType.GEMINI)
    }
    var displayName by remember(existing) { mutableStateOf(existing?.displayName ?: "") }
    var baseUrl by remember(existing) {
        mutableStateOf(existing?.baseUrl ?: (existing?.providerType ?: AiProviderType.GEMINI).defaultBaseUrl)
    }
    var modelId by remember(existing) {
        mutableStateOf(existing?.modelId ?: (existing?.providerType ?: AiProviderType.GEMINI).defaultModelId)
    }
    var apiKeyInput by remember(existing) { mutableStateOf("") }
    var showApiKey by remember { mutableStateOf(false) }
    var setAsDefault by remember(existing) { mutableStateOf(existing?.isDefault ?: true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "AIプロバイダを追加" else "AIプロバイダを編集") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("プロバイダ", style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AiProviderType.entries.forEach { candidate ->
                        FilterChip(
                            selected = providerType == candidate,
                            onClick = {
                                if (providerType != candidate) {
                                    providerType = candidate
                                    baseUrl = candidate.defaultBaseUrl
                                    modelId = candidate.defaultModelId
                                }
                            },
                            label = { Text(candidate.displayLabel) },
                        )
                    }
                }

                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("表示名（空欄なら自動命名）") },
                    placeholder = { Text("${providerType.displayLabel} (標準)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = { Text("モデル ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (providerType.suggestedModels.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        providerType.suggestedModels.forEach { suggested ->
                            AssistChip(
                                onClick = { modelId = suggested },
                                label = { Text(suggested, maxLines = 1) },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { apiKeyInput = it },
                    label = {
                        Text(
                            if (existing?.hasStoredApiKey == true) {
                                "API キー（変更する場合のみ入力）"
                            } else if (providerType.requiresApiKey) {
                                "API キー（Keystore 暗号化保存）"
                            } else {
                                "API キー（任意）"
                            },
                        )
                    },
                    placeholder = {
                        Text(
                            existing?.apiKeyHint?.let { "保存済み: $it" }
                                ?: providerType.apiKeyPlaceholder,
                        )
                    },
                    visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showApiKey = !showApiKey }) {
                            Icon(
                                imageVector = if (showApiKey) KazeIcons.VisibilityOff else KazeIcons.Visibility,
                                contentDescription = if (showApiKey) "キーを隠す" else "キーを表示",
                            )
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("エンドポイント Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { setAsDefault = !setAsDefault },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("標準プロバイダに設定", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = setAsDefault, onCheckedChange = { setAsDefault = it })
                }

                OutlinedButton(
                    onClick = { onTestConnection(providerType, baseUrl, modelId, apiKeyInput) },
                    enabled = !isTesting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("接続テスト中…")
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("この設定で接続テスト")
                    }
                }

                testResult?.let { res ->
                    Surface(
                        color = if (res.success) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.errorContainer
                        },
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = res.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (res.success) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onErrorContainer
                                },
                            )
                            res.sampleReply?.let { sample ->
                                Text(
                                    text = "応答サンプル: $sample",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(displayName, providerType, baseUrl, modelId, apiKeyInput, setAsDefault)
                },
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        },
    )
}

@Composable
private fun AiPresetEditorDialog(
    existing: AiWritingPresetEntity?,
    onSave: (title: String, goal: AiWritingGoal, tone: AiReplyTone, prompt: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember(existing) { mutableStateOf(existing?.title ?: "") }
    var goal by remember(existing) {
        mutableStateOf(AiWritingGoal.fromStorage(existing?.goal))
    }
    var tone by remember(existing) {
        mutableStateOf(AiReplyTone.fromStorage(existing?.tone))
    }
    var prompt by remember(existing) { mutableStateOf(existing?.instructionPrompt ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "テンプレートを追加" else "テンプレートを編集") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("テンプレート名") },
                    placeholder = { Text("例: 日程調整の候補提示") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("用途", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AiWritingGoal.entries.forEach { g ->
                        FilterChip(
                            selected = goal == g,
                            onClick = { goal = g },
                            label = { Text(g.displayLabel) },
                        )
                    }
                }
                Text("トーン", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AiReplyTone.entries.forEach { t ->
                        FilterChip(
                            selected = tone == t,
                            onClick = { tone = t },
                            label = { Text(t.displayLabel) },
                        )
                    }
                }
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("AIへの指示内容") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(title, goal, tone, prompt) }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        },
    )
}

@Composable
private fun AiSectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.large,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun AiSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubjectModelOverrideDialog(
    currentOverride: String,
    resolvedProfile: AiProviderProfileSummary?,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var modelId by remember(currentOverride) { mutableStateOf(currentOverride) }
    val suggestedModels = resolvedProfile?.providerType?.suggestedModels.orEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("件名作成用モデル ID") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = if (resolvedProfile != null) {
                        "プロファイル「${resolvedProfile.displayName}」の既定 (${resolvedProfile.modelId}) の代わりに使うモデル名を指定できます。軽量モデルにすると高速・低コストになります。"
                    } else {
                        "空欄にすると選択中プロファイルの既定モデルを使用します。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = { Text("モデル ID（空欄でプロファイル既定）") },
                    placeholder = { Text(resolvedProfile?.modelId ?: "例: gemini-3.8-flash") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (suggestedModels.isNotEmpty()) {
                    Text(
                        text = "候補モデル（タップで入力）",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        suggestedModels.forEach { suggested ->
                            FilterChip(
                                selected = modelId.trim() == suggested,
                                onClick = { modelId = suggested },
                                label = { Text(suggested, maxLines = 1) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(modelId.trim()) }) {
                Text("保存")
            }
        },
        dismissButton = {
            Row {
                if (currentOverride.isNotBlank() || modelId.isNotBlank()) {
                    TextButton(onClick = { onSave("") }) {
                        Text("標準に戻す")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("キャンセル")
                }
            }
        },
    )
}

@Composable
private fun SubjectCustomInstructionDialog(
    currentInstruction: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var instruction by remember(currentInstruction) { mutableStateOf(currentInstruction) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("件名作成専用の追加指示") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "「本文から件名作成」を実行するときだけ追加されるプロンプトです。返信本文などの生成には影響しません。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = instruction,
                    onValueChange = { instruction = it },
                    label = { Text("専用指示（任意）") },
                    placeholder = { Text("例: 20文字以内で簡潔に / 先頭に【要確認】などを付ける") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(instruction.trim()) }) {
                Text("保存")
            }
        },
        dismissButton = {
            Row {
                if (currentInstruction.isNotBlank() || instruction.isNotBlank()) {
                    TextButton(onClick = { onSave("") }) {
                        Text("クリア")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("キャンセル")
                }
            }
        },
    )
}
