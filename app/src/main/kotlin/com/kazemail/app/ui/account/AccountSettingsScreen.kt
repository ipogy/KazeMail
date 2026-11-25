package com.kazemail.app.ui.account

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kazemail.app.data.db.LocalFilterRuleEntity
import com.kazemail.app.filter.FilterField
import com.kazemail.app.filter.FilterOperator
import com.kazemail.app.filter.FilterRuleTemplates
import com.kazemail.app.filter.LocalFilterCondition
import com.kazemail.app.filter.LocalFilterEngine
import com.kazemail.app.ui.common.DateFormatters
import com.kazemail.app.ui.settings.AccountSignatureEditDialog
import com.kazemail.app.ui.viewer.HtmlEmailDetector

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AccountSettingsScreen(
    viewModel: AccountSettingsViewModel,
    onNavigateBack: () -> Unit,
    onEditConnectionClick: (Long) -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenSyncDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showSignatureDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.statusMessage) {
        val msg = state.statusMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearStatusMessage()
    }

    LaunchedEffect(state.deletedAccount) {
        if (state.deletedAccount) {
            onNavigateBack()
        }
    }

    if (showDeleteConfirm && state.account != null) {
        val email = state.account?.emailAddress.orEmpty()
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("アカウントを端末から削除") },
            text = {
                Text("$email の受信データ・ローカルルール・資格情報を端末から削除します（メールサーバー上のデータは変更・削除されません）。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        viewModel.deleteAccountLocally()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("端末から削除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("キャンセル")
                }
            },
        )
    }

    state.editingRuleDraft?.let { draft ->
        LocalFilterRuleEditorDialog(
            initialDraft = draft,
            suggestedCategories = state.existingCategories.map { it.categoryName },
            onDismiss = viewModel::dismissRuleEditor,
            onSave = viewModel::saveRuleDraft,
        )
    }

    if (showSignatureDialog && state.account != null) {
        AccountSignatureEditDialog(
            title = "署名設定 (${state.account?.emailAddress.orEmpty()})",
            initialEnabled = state.signatureEnabled,
            initialText = state.signatureText,
            inheritedFromLegacyGlobal = state.signatureInheritedFromLegacy,
            onDismiss = { showSignatureDialog = false },
            onSave = { enabled, text ->
                viewModel.saveAccountSignature(enabled, text)
                showSignatureDialog = false
            },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "アカウント設定",
                            maxLines = 1,
                        )
                        state.account?.let { acc ->
                            Text(
                                text = acc.emailAddress,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenAppSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "アプリ全体設定")
                    }
                },
            )
        },
    ) { innerPadding ->
        val account = state.account
        if (!state.hasLoadedAccount) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            return@Scaffold
        }
        if (account == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("アカウントが見つかりません")
            }
            return@Scaffold
        }

        val isLocalOnly = account.authType.equals("LOCAL", ignoreCase = true)

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.isBusy) {
                item(key = "busy") {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            item(key = "account_overview") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = account.displayName?.takeIf { it.isNotBlank() } ?: account.emailAddress,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        val syncText = account.lastSyncAt?.let { DateFormatters.formatMonthDayTime(it) } ?: "未同期"
                        val countText = state.messageCount?.let { "$it 件" } ?: "集計中…"
                        Text(
                            text = if (isLocalOnly) {
                                "ローカル保管専用 • 保存済み $countText"
                            } else {
                                "IMAP: ${account.imapHost}:${account.imapPort} (${account.authType}) • 保存 $countText • 最終同期: $syncText"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (!isLocalOnly) {
                                OutlinedButton(onClick = { onEditConnectionClick(account.accountId) }) {
                                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("接続・認証を編集")
                                }
                                OutlinedButton(
                                    onClick = viewModel::syncAccountNow,
                                    enabled = !state.isBusy,
                                ) {
                                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("今すぐ同期")
                                }
                                TextButton(onClick = viewModel::enqueueBackfill) {
                                    Text("全件バックフィル")
                                }
                            }
                            TextButton(onClick = onOpenSyncDiagnostics) {
                                Text("同期診断")
                            }
                            TextButton(onClick = onOpenAppSettings) {
                                Text("アプリ全体設定")
                            }
                        }
                    }
                }
            }

            item(key = "account_signature") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "このメールアドレスの署名",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                val statusLabel = when {
                                    state.signatureEnabled && state.signatureText.isNotBlank() ->
                                        if (state.signatureInheritedFromLegacy) {
                                            "有効（共通設定を引継中）"
                                        } else {
                                            "有効（個別設定）"
                                        }
                                    state.signatureEnabled -> "有効（署名テキスト未入力）"
                                    else -> "無効"
                                }
                                Text(
                                    text = statusLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            OutlinedButton(onClick = { showSignatureDialog = true }) {
                                Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("署名を編集")
                            }
                        }
                        if (state.signatureText.isNotBlank()) {
                            val previewText = if (HtmlEmailDetector.containsActualHtml(state.signatureText)) {
                                HtmlEmailDetector.stripHtmlFallbackText(state.signatureText)
                            } else {
                                state.signatureText
                            }
                            Text(
                                text = previewText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            item(key = "rules_header") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "自動振り分けルール（クライアント側フィルタ）",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = "サーバーのフォルダやフラグを変更せず、この端末内のローカル分類・アーカイブ・自動既読・★付与・通知抑制を行います。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = viewModel::openNewRuleEditor,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("新規ルール")
                        }
                        OutlinedButton(
                            onClick = viewModel::applyRulesToInboxNow,
                            enabled = !state.isBusy && state.rules.any { it.isEnabled },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("既存受信トレイに適用")
                        }
                    }

                    Text(
                        text = "テンプレートから1タップ追加",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterRuleTemplates.presets.forEach { tpl ->
                            AssistChip(
                                onClick = { viewModel.addTemplateRule(tpl) },
                                label = { Text(tpl.label) },
                            )
                        }
                    }
                }
            }

            if (state.rules.isEmpty()) {
                item(key = "rules_empty") {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "ルールはまだ登録されていません",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = "「新規ルール」または上のテンプレートから、AND/OR 複数条件・正規表現を使った自動振り分けを作成できます。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                itemsIndexed(state.rules, key = { _, r -> r.ruleId }) { idx, rule ->
                    FilterRuleCard(
                        index = idx,
                        totalCount = state.rules.size,
                        rule = rule,
                        onToggleEnabled = { enabled -> viewModel.toggleRuleEnabled(rule, enabled) },
                        onMoveUp = { viewModel.moveRulePriority(rule.ruleId, -1) },
                        onMoveDown = { viewModel.moveRulePriority(rule.ruleId, 1) },
                        onEdit = { viewModel.openRuleEditor(rule) },
                        onDelete = { viewModel.deleteRule(rule.ruleId) },
                    )
                }
            }

            item(key = "danger_zone") {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                OutlinedButton(
                    onClick = { showDeleteConfirm = true },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("このアカウントを端末から削除（サーバーには残ります）")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterRuleCard(
    index: Int,
    totalCount: Int,
    rule: LocalFilterRuleEntity,
    onToggleEnabled: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val conditions = remember(rule.conditionsJson) {
        LocalFilterEngine.decodeConditions(rule.conditionsJson)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${index + 1}. ${rule.name}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (rule.matchMode == LocalFilterRuleEntity.MATCH_MODE_ANY) {
                            "いずれか一致 (OR) • ${conditions.size}条件"
                        } else {
                            "すべて一致 (AND) • ${conditions.size}条件"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Switch(
                    checked = rule.isEnabled,
                    onCheckedChange = onToggleEnabled,
                )
            }

            conditions.forEach { cond ->
                Text(
                    text = "• ${LocalFilterEngine.formatConditionSummary(cond)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                rule.targetLocalFolder?.takeIf { it.isNotBlank() }?.let { folder ->
                    val modeLabel = if (rule.hideFromFolder) "仕分け(INBOXから移動)" else "ラベル付与"
                    FilterActionTag("$modeLabel: $folder")
                }
                if (rule.archiveLocally) FilterActionTag("ローカルアーカイブ")
                if (rule.markRead) FilterActionTag("自動既読")
                if (rule.markFlagged) FilterActionTag("★スター")
                if (rule.suppressNotification) FilterActionTag("通知オフ")
                if (rule.stopProcessing) FilterActionTag("一致で停止")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onMoveUp, enabled = index > 0) {
                        Text("▲ 優先度上")
                    }
                    TextButton(onClick = onMoveDown, enabled = index + 1 < totalCount) {
                        Text("▼ 下")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onEdit) {
                        Text("編集")
                    }
                    TextButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text("削除")
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterActionTag(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LocalFilterRuleEditorDialog(
    initialDraft: LocalFilterRuleDraft,
    suggestedCategories: List<String>,
    onDismiss: () -> Unit,
    onSave: (LocalFilterRuleDraft) -> Unit,
) {
    var draft by remember(initialDraft) { mutableStateOf(initialDraft) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Filled.Close, contentDescription = "閉じる")
                        }
                        Text(
                            text = if (draft.ruleId == 0L) "自動振り分けルール作成" else "自動振り分けルール編集",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Button(onClick = { onSave(draft) }) {
                        Text("保存")
                    }
                }
                HorizontalDivider()

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = draft.name,
                        onValueChange = { draft = draft.copy(name = it) },
                        label = { Text("ルール名（例: Amazon注文・請求書）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Text(
                        text = "条件の結合方法",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = draft.matchMode == LocalFilterRuleEntity.MATCH_MODE_ALL,
                            onClick = { draft = draft.copy(matchMode = LocalFilterRuleEntity.MATCH_MODE_ALL) },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        ) {
                            Text("すべて一致 (AND)")
                        }
                        SegmentedButton(
                            selected = draft.matchMode == LocalFilterRuleEntity.MATCH_MODE_ANY,
                            onClick = { draft = draft.copy(matchMode = LocalFilterRuleEntity.MATCH_MODE_ANY) },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        ) {
                            Text("いずれか一致 (OR)")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "一致条件 (${draft.conditions.size})",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        TextButton(
                            onClick = {
                                draft = draft.copy(
                                    conditions = draft.conditions + LocalFilterCondition(),
                                )
                            },
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("条件を追加")
                        }
                    }

                    draft.conditions.forEachIndexed { idx, condition ->
                        FilterConditionEditorCard(
                            index = idx,
                            condition = condition,
                            canDelete = draft.conditions.size > 1,
                            onChange = { updated ->
                                val next = draft.conditions.toMutableList()
                                next[idx] = updated
                                draft = draft.copy(conditions = next)
                            },
                            onDelete = {
                                val next = draft.conditions.toMutableList()
                                next.removeAt(idx)
                                draft = draft.copy(conditions = next)
                            },
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Text(
                        text = "実行アクション（複数組み合わせ可能）",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )

                    OutlinedTextField(
                        value = draft.targetLocalFolder,
                        onValueChange = { draft = draft.copy(targetLocalFolder = it) },
                        label = { Text("ローカル仕分け先フォルダ名（空欄なら移動なし）") },
                        placeholder = { Text("例: 請求書 / 買い物 / 仕事") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (suggestedCategories.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            suggestedCategories.forEach { cat ->
                                FilterChip(
                                    selected = draft.targetLocalFolder == cat,
                                    onClick = { draft = draft.copy(targetLocalFolder = cat) },
                                    label = { Text(cat) },
                                )
                            }
                        }
                    }

                    if (draft.targetLocalFolder.isNotBlank()) {
                        RuleSwitchRow(
                            title = "受信トレイ一覧から隠す（仕分けフォルダのみで表示）",
                            subtitle = "オフにすると受信トレイにも残しつつローカルフォルダで絞り込めます",
                            checked = draft.hideFromFolder,
                            onCheckedChange = { draft = draft.copy(hideFromFolder = it) },
                        )
                    }

                    RuleSwitchRow(
                        title = "自動でローカルアーカイブへ移す",
                        checked = draft.archiveLocally,
                        onCheckedChange = { draft = draft.copy(archiveLocally = it) },
                    )
                    RuleSwitchRow(
                        title = "自動でローカル既読にする",
                        checked = draft.markRead,
                        onCheckedChange = { draft = draft.copy(markRead = it) },
                    )
                    RuleSwitchRow(
                        title = "自動でスター (★) を付ける",
                        checked = draft.markFlagged,
                        onCheckedChange = { draft = draft.copy(markFlagged = it) },
                    )
                    RuleSwitchRow(
                        title = "新着通知を出さない（通知抑制）",
                        checked = draft.suppressNotification,
                        onCheckedChange = { draft = draft.copy(suppressNotification = it) },
                    )
                    RuleSwitchRow(
                        title = "このルールに一致したら以降のルール評価を停止",
                        checked = draft.stopProcessing,
                        onCheckedChange = { draft = draft.copy(stopProcessing = it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterConditionEditorCard(
    index: Int,
    condition: LocalFilterCondition,
    canDelete: Boolean,
    onChange: (LocalFilterCondition) -> Unit,
    onDelete: () -> Unit,
) {
    var fieldMenuOpen by remember { mutableStateOf(false) }
    var opMenuOpen by remember { mutableStateOf(false) }

    val availableOperators = if (condition.field == FilterField.HAS_ATTACHMENTS) {
        listOf(FilterOperator.IS_TRUE, FilterOperator.IS_FALSE)
    } else {
        listOf(
            FilterOperator.CONTAINS,
            FilterOperator.NOT_CONTAINS,
            FilterOperator.EQUALS,
            FilterOperator.STARTS_WITH,
            FilterOperator.ENDS_WITH,
            FilterOperator.REGEX,
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "条件 ${index + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (canDelete) {
                    TextButton(onClick = onDelete) {
                        Text("削除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { fieldMenuOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(condition.field.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(
                        expanded = fieldMenuOpen,
                        onDismissRequest = { fieldMenuOpen = false },
                    ) {
                        FilterField.entries.forEach { field ->
                            DropdownMenuItem(
                                text = { Text(field.label) },
                                onClick = {
                                    fieldMenuOpen = false
                                    val nextOp = if (field == FilterField.HAS_ATTACHMENTS) {
                                        FilterOperator.IS_TRUE
                                    } else if (condition.operator == FilterOperator.IS_TRUE || condition.operator == FilterOperator.IS_FALSE) {
                                        FilterOperator.CONTAINS
                                    } else {
                                        condition.operator
                                    }
                                    onChange(condition.copy(field = field, operator = nextOp))
                                },
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { opMenuOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(condition.operator.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(
                        expanded = opMenuOpen,
                        onDismissRequest = { opMenuOpen = false },
                    ) {
                        availableOperators.forEach { op ->
                            DropdownMenuItem(
                                text = { Text(op.label) },
                                onClick = {
                                    opMenuOpen = false
                                    onChange(condition.copy(operator = op))
                                },
                            )
                        }
                    }
                }
            }

            if (condition.field != FilterField.HAS_ATTACHMENTS) {
                OutlinedTextField(
                    value = condition.value,
                    onValueChange = { onChange(condition.copy(value = it)) },
                    label = {
                        Text(
                            if (condition.operator == FilterOperator.REGEX) {
                                "正規表現パターン (例: 請求|領収書)"
                            } else {
                                "一致させるキーワード"
                            },
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "大文字・小文字を区別する",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Switch(
                        checked = condition.caseSensitive,
                        onCheckedChange = { onChange(condition.copy(caseSensitive = it)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RuleSwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
