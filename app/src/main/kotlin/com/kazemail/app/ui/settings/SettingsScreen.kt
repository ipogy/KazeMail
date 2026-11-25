package com.kazemail.app.ui.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kazemail.app.BuildConfig
import com.kazemail.app.DefaultMailAppHelper
import com.kazemail.app.data.AppThemeMode
import com.kazemail.app.data.db.AccountEntity
import com.kazemail.app.data.model.AccountSignatureResolver
import com.kazemail.app.ui.common.DateFormatters
import com.kazemail.app.ui.theme.KazeIcons
import com.kazemail.app.ui.viewer.HtmlEmailDetector
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onAddAccountClick: () -> Unit,
    onEditAccountClick: (Long) -> Unit,
    onImportK9sUri: (Uri) -> Unit,
    onImportArchiveUris: (List<Uri>) -> Unit,
    onOpenSyncDiagnostics: () -> Unit,
    onOpenAiSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var pendingDeleteAccount by remember { mutableStateOf<AccountEntity?>(null) }
    var editingSignatureAccount by remember { mutableStateOf<AccountEntity?>(null) }
    var showSignatureDialog by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.onPermissionResult(granted)
    }

    val roleRequestLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        viewModel.onRoleRequestFinished()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshOsIntegrationStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val k9sPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            onImportK9sUri(uri)
        }
    }

    val archivePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            onImportArchiveUris(uris)
        }
    }

    LaunchedEffect(state.statusMessage) {
        val msg = state.statusMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearStatusMessage()
    }

    pendingDeleteAccount?.let { acc ->
        AlertDialog(
            onDismissRequest = { pendingDeleteAccount = null },
            title = { Text("アカウントを端末から削除") },
            text = {
                Text(
                    "「${acc.emailAddress}」の端末内ローカルデータ（同期済みメール・下書き・認証情報）を削除します。\n\n※非破壊原則により、メールサーバー上のメールやフォルダには一切変更・削除を行いません。",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteAccountLocally(acc.accountId, acc.emailAddress)
                        pendingDeleteAccount = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("端末から削除")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteAccount = null }) {
                    Text("キャンセル")
                }
            },
        )
    }

    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { Text("添付キャッシュを削除") },
            text = {
                val cacheText = state.attachmentCacheBytes?.let { formatStorageBytes(it) } ?: "計算中…"
                Text("端末にダウンロード済みの添付ファイル（$cacheText）を削除します。メール本文やメタデータは保持され、必要な添付ファイルは後から再取得できます。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearAttachmentCache()
                        showClearCacheDialog = false
                    },
                ) {
                    Text("削除する")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) {
                    Text("キャンセル")
                }
            },
        )
    }

    editingSignatureAccount?.let { targetAccount ->
        val resolvedSig = AccountSignatureResolver.resolveState(
            account = targetAccount,
            legacyEnabled = state.signatureEnabled,
            legacyText = state.signatureText,
        )
        AccountSignatureEditDialog(
            title = "署名設定 (${targetAccount.emailAddress})",
            initialEnabled = resolvedSig.enabled,
            initialText = resolvedSig.text,
            inheritedFromLegacyGlobal = resolvedSig.inheritedFromLegacyGlobal,
            onDismiss = { editingSignatureAccount = null },
            onSave = { enabled, text ->
                viewModel.saveAccountSignature(
                    accountId = targetAccount.accountId,
                    emailAddress = targetAccount.emailAddress,
                    enabled = enabled,
                    text = text,
                )
                editingSignatureAccount = null
            },
        )
    }

    if (showSignatureDialog) {
        AccountSignatureEditDialog(
            title = "共通既定のメール署名",
            initialEnabled = state.signatureEnabled,
            initialText = state.signatureText,
            inheritedFromLegacyGlobal = false,
            onDismiss = { showSignatureDialog = false },
            onSave = { enabled, text ->
                viewModel.saveSignature(enabled, text)
                showSignatureDialog = false
            },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("設定") },
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
            item(key = "section_accounts") {
                SettingsSectionCard(title = "アカウント") {
                    if (!state.hasLoadedAccounts) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else if (state.accounts.isEmpty()) {
                        Text(
                            text = "登録済みのアカウントはありません",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    } else {
                        state.accounts.forEachIndexed { idx, summary ->
                            val acc = summary.account
                            if (idx > 0) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                )
                            }
                            AccountSettingsRow(
                                summary = summary,
                                isSyncing = state.syncingAccountId == acc.accountId,
                                onSyncNow = { viewModel.syncAccountNow(acc.accountId) },
                                onEdit = { onEditAccountClick(acc.accountId) },
                                onDelete = { pendingDeleteAccount = acc },
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = onAddAccountClick,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("アカウントを追加")
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = { k9sPickerLauncher.launch(arrayOf("*/*")) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(".k9s 移行", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            OutlinedButton(
                                onClick = { archivePickerLauncher.launch(arrayOf("*/*")) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(".eml/.mbox 取込", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }

            item(key = "section_sync") {
                SettingsSectionCard(title = "同期・バックグラウンド") {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            text = "定期バックグラウンド同期",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "OS の省電力制御下でバックグラウンド定期同期を実行し、アプリ起動・復帰時にも軽量な自動差分チェックを行います",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf(
                                15L to "15分ごと",
                                30L to "30分ごと",
                                60L to "1時間ごと",
                                0L to "手動のみ",
                            ).forEach { (minutes, label) ->
                                FilterChip(
                                    selected = state.periodicSyncIntervalMinutes == minutes,
                                    onClick = { viewModel.setPeriodicSyncInterval(minutes) },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    )
                    SettingsSwitchRow(
                        title = "Wi-Fi 接続時のみ過去メールを全件同期",
                        subtitle = "フェーズ2の大量バックフィル時にモバイルデータ通信を消費しません",
                        checked = state.wifiOnlyBackfill,
                        onCheckedChange = viewModel::setWifiOnlyBackfill,
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    )
                    SettingsActionRow(
                        title = "フォルダ別同期ステータス・詳細診断",
                        subtitle = "全件バックフィル実行、Outbox 強制送信、UIDVALIDITY 状態を確認",
                        onClick = onOpenSyncDiagnostics,
                    )
                }
            }

            item(key = "section_ai_assistant") {
                SettingsSectionCard(title = "AI機能・返信アシスタント") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenAiSettings)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = KazeIcons.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("AIプロバイダ・返信テンプレート・安全設定", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = state.aiReadinessSummary,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (state.aiReady) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item(key = "section_notifications") {
                SettingsSectionCard(title = "通知") {
                    if (!state.hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Notifications,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("OS の通知権限が未許可です", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "新着メールを受け取るには通知権限を許可してください",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(
                                onClick = {
                                    notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                },
                            ) {
                                Text("許可")
                            }
                        }
                    } else {
                        SettingsSwitchRow(
                            title = "新着メール通知",
                            subtitle = "バックグラウンド同期で受信トレイに新着があると通知します",
                            checked = state.notificationsEnabled,
                            onCheckedChange = viewModel::setNotificationsEnabled,
                        )
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                        )
                        SettingsActionRow(
                            title = "端末の通知チャンネル設定を開く",
                            subtitle = "通知音・バイブレーション・ロック画面表示を設定",
                            onClick = {
                                runCatching {
                                    val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                        putExtra(Settings.EXTRA_CHANNEL_ID, viewModel.notificationChannelId())
                                    }
                                    context.startActivity(intent)
                                }
                            },
                        )
                    }
                }
            }

            item(key = "section_default_mail_app") {
                val defaultStatus = state.defaultMailAppStatus
                val statusTitle = when {
                    defaultStatus.isPrimaryDefault -> "設定済み（KazeMail がデフォルトです）"
                    defaultStatus.isPartiallyDefault && defaultStatus.isDefaultAppEmail ->
                        "一部設定済み（標準メールアプリ: KazeMail）"
                    defaultStatus.isPartiallyDefault ->
                        "一部設定済み（mailto: リンクで KazeMail を選択）"
                    !defaultStatus.currentMailAppLabel.isNullOrBlank() ->
                        "未設定（現在: ${defaultStatus.currentMailAppLabel}）"
                    !defaultStatus.currentMailtoLabel.isNullOrBlank() ->
                        "未設定（mailto: 現在 ${defaultStatus.currentMailtoLabel}）"
                    else -> "未設定（他のアプリと併用中）"
                }
                val statusSubtitle = when {
                    defaultStatus.isPrimaryDefault ->
                        "Web の mailto: リンクや他アプリのメール起動が KazeMail で開きます"
                    defaultStatus.isPartiallyDefault ->
                        "端末の「デフォルトアプリ → メールアプリ」設定で統一できます"
                    else ->
                        "端末の「デフォルトアプリ → メールアプリ」で KazeMail を選択してください"
                }

                SettingsSectionCard(title = "デフォルトアプリ") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val roleIntent = DefaultMailAppHelper.buildRoleRequestIntentOrNull(context)
                                if (roleIntent != null) {
                                    runCatching { roleRequestLauncher.launch(roleIntent) }
                                        .onFailure {
                                            if (!DefaultMailAppHelper.openDefaultMailAppSettings(context)) {
                                                viewModel.showStatusMessage("端末の設定画面を開けませんでした")
                                            }
                                        }
                                } else if (!DefaultMailAppHelper.openDefaultMailAppSettings(context)) {
                                    viewModel.showStatusMessage("端末の設定画面を開けませんでした")
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (defaultStatus.isPrimaryDefault) {
                                Icons.Filled.CheckCircle
                            } else {
                                Icons.Filled.Email
                            },
                            contentDescription = null,
                            tint = if (defaultStatus.isPrimaryDefault) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("デフォルトのメールアプリ", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = statusTitle,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = if (defaultStatus.isPrimaryDefault) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            Text(
                                text = statusSubtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "端末のデフォルトアプリ設定を開く",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    )
                    SettingsActionRow(
                        title = "KazeMail のアプリ情報・既定設定を開く",
                        subtitle = "設定変更後に戻るとステータスが自動更新されます",
                        onClick = {
                            val started = runCatching {
                                context.startActivity(DefaultMailAppHelper.buildAppDetailsIntent(context))
                                true
                            }.getOrDefault(false)
                            if (!started) {
                                viewModel.showStatusMessage("アプリ情報画面を開けませんでした")
                            }
                        },
                    )
                }
            }

            item(key = "section_display") {
                SettingsSectionCard(title = "表示・操作") {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            text = "外観テーマ",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AppThemeMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = state.themeMode == mode,
                                    onClick = { viewModel.setThemeMode(mode) },
                                    label = { Text(mode.displayLabel) },
                                )
                            }
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    )
                    SettingsSwitchRow(
                        title = "スレッド表示を既定にする",
                        subtitle = "同一件名・返信チェーンをまとめて表示します（一覧上部でも切替可）",
                        checked = state.defaultThreadMode,
                        onCheckedChange = viewModel::setDefaultThreadMode,
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    )
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            text = "一覧の本文スニペット行数",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(1 to "1行（コンパクト）", 2 to "2行（標準）").forEach { (lines, label) ->
                                FilterChip(
                                    selected = state.previewLines == lines,
                                    onClick = { viewModel.setPreviewLines(lines) },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    )
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            text = "1ページあたりの表示件数",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "件数を絞ってページ切り替えすることでメモリと描画を軽量化します",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(50 to "50件", 100 to "100件", 200 to "200件").forEach { (count, label) ->
                                FilterChip(
                                    selected = state.itemsPerPage == count,
                                    onClick = { viewModel.setItemsPerPage(count) },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    )
                    SettingsSwitchRow(
                        title = "送信者アバターを表示",
                        subtitle = "受信トレイの送信者アイコンを表示します",
                        checked = state.showAvatars,
                        onCheckedChange = viewModel::setShowAvatars,
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    )
                    SettingsSwitchRow(
                        title = "アーカイブ前に確認する",
                        subtitle = "誤スワイプによる意図しないアーカイブを防ぎます",
                        checked = state.confirmArchive,
                        onCheckedChange = viewModel::setConfirmArchive,
                    )
                }
            }

            item(key = "section_viewer_compose") {
                SettingsSectionCard(title = "閲覧・作成・署名") {
                    SettingsSwitchRow(
                        title = "HTMLメールの自動判定・表示",
                        subtitle = "HTMLメールと自動判定したメッセージのみリッチ表示します（テキストメールはそのまま表示）",
                        checked = state.autoShowHtml,
                        onCheckedChange = viewModel::setAutoShowHtml,
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    )
                    SettingsSwitchRow(
                        title = "HTMLメールの外部画像を初期ブロック",
                        subtitle = "トラッキングピクセルを防ぎ、必要時のみ画面上で許可します",
                        checked = state.defaultBlockExternalImages,
                        onCheckedChange = viewModel::setDefaultBlockExternalImages,
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    )
                    SettingsSwitchRow(
                        title = "メール閲覧時に引用文を折りたたむ",
                        subtitle = "過去の引用（> 始まり）を折りたたみ表示にします",
                        checked = state.collapseQuotesByDefault,
                        onCheckedChange = viewModel::setCollapseQuotesByDefault,
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    )
                    if (state.accounts.isEmpty()) {
                        SettingsActionRow(
                            title = "メール署名（既定）",
                            subtitle = if (state.signatureEnabled) {
                                "有効: ${state.signatureText.lineSequence().firstOrNull().orEmpty()}"
                            } else {
                                "無効（タップして署名を設定）"
                            },
                            onClick = { showSignatureDialog = true },
                        )
                    } else {
                        state.accounts.forEachIndexed { idx, summary ->
                            val acc = summary.account
                            val resolved = AccountSignatureResolver.resolveState(
                                account = acc,
                                legacyEnabled = state.signatureEnabled,
                                legacyText = state.signatureText,
                            )
                            val firstLine = resolved.text
                                .let { raw ->
                                    if (HtmlEmailDetector.containsActualHtml(raw)) {
                                        HtmlEmailDetector.stripHtmlFallbackText(raw)
                                    } else {
                                        raw
                                    }
                                }
                                .lineSequence()
                                .firstOrNull { it.isNotBlank() }
                                .orEmpty()
                            SettingsActionRow(
                                title = "署名: ${acc.emailAddress}",
                                subtitle = when {
                                    resolved.enabled && firstLine.isNotBlank() ->
                                        if (resolved.inheritedFromLegacyGlobal) {
                                            "有効（共通設定を引継中）: $firstLine"
                                        } else {
                                            "有効: $firstLine"
                                        }
                                    resolved.enabled -> "有効（署名未入力）"
                                    else -> "無効（タップして設定）"
                                },
                                onClick = { editingSignatureAccount = acc },
                            )
                            if (idx < state.accounts.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                )
                            }
                        }
                    }
                }
            }

            item(key = "section_storage_security") {
                SettingsSectionCard(title = "ストレージ・保護・アプリ情報") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("ローカル蓄積・添付キャッシュ", style = MaterialTheme.typography.titleSmall)
                            val countText = state.totalStoredMessages?.let { "${it} 件" } ?: "集計中…"
                            val cacheText = state.attachmentCacheBytes?.let { formatStorageBytes(it) } ?: "計算中…"
                            Text(
                                text = "保存メール $countText / 添付キャッシュ $cacheText",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { showClearCacheDialog = true }) {
                            Text("キャッシュ削除")
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            imageVector = KazeIcons.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "非破壊プロトコル & SQLCipher 暗号化有効",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = "KazeMail は IMAP の EXAMINE / BODY.PEEK のみを使用し、サーバー削除 (EXPUNGE) や破壊的操作を行いません。ローカルDBと認証情報は Android Keystore AES-256-GCM で保護されています。\nVersion ${BuildConfig.VERSION_NAME}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(
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
private fun AccountSettingsRow(
    summary: AccountSettingsSummary,
    isSyncing: Boolean,
    onSyncNow: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val acc = summary.account
    val lastSync = acc.lastSyncAt?.takeIf { it > 0L }?.let { DateFormatters.formatMonthDayTime(it) } ?: "未同期"
    val countLabel = summary.messageCount?.let { "${it}件蓄積 • " } ?: ""
    val initial = (acc.displayName?.takeIf { it.isNotBlank() } ?: acc.emailAddress)
        .firstOrNull()?.uppercaseChar()?.toString() ?: "K"
    val avatarColor = remember(acc.emailAddress) { deterministicAvatarColor(acc.emailAddress) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(avatarColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initial,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = acc.displayName?.takeIf { it.isNotBlank() } ?: acc.emailAddress,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = acc.emailAddress,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${acc.authType} • ${countLabel}最終同期: $lastSync",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!acc.authType.equals("LOCAL", ignoreCase = true)) {
            IconButton(onClick = onSyncNow, enabled = !isSyncing) {
                if (isSyncing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = "今すぐ同期")
                }
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "アカウント設定を編集")
            }
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "端末から削除",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun SettingsSwitchRow(
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

@Composable
private fun SettingsActionRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun deterministicAvatarColor(seed: String): Color {
    val palette = listOf(
        Color(0xFF0B556B),
        Color(0xFF2B6CB0),
        Color(0xFF2F855A),
        Color(0xFF6B46C1),
        Color(0xFFB7791F),
        Color(0xFFC05621),
        Color(0xFF97266D),
        Color(0xFF2C7A7B),
    )
    return palette[abs(seed.hashCode()) % palette.size]
}

private fun formatStorageBytes(bytes: Long): String =
    when {
        bytes < 1024L -> "${bytes} B"
        bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }
