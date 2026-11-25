package com.kazemail.app.ui.account

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kazemail.app.auth.OAuth2Providers
import com.kazemail.app.security.StoredSecurityMode
import com.kazemail.app.ui.theme.KazeIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAccountScreen(
    viewModel: AddAccountViewModel,
    onNavigateBack: () -> Unit,
    initialK9sUriString: String? = null,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val isEditing = state.editingAccountId != null

    val k9sPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.importFromK9sUri(context, uri)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadAppSigningInfo(context)
    }

    LaunchedEffect(initialK9sUriString) {
        if (!initialK9sUriString.isNullOrBlank()) {
            runCatching { Uri.parse(initialK9sUriString) }.getOrNull()?.let { uri ->
                viewModel.importFromK9sUri(context, uri)
            }
        }
    }

    val oauthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        viewModel.handleOAuthActivityResult(result.data)
    }

    fun launchOAuthIntent(intent: android.content.Intent?) {
        intent ?: return
        runCatching { oauthLauncher.launch(intent) }.onFailure { err ->
            viewModel.updateField {
                copy(errorMessage = "ブラウザ/CustomTabs起動失敗: ${err.message}")
            }
        }
    }

    LaunchedEffect(state.savedSuccessfully) {
        if (state.savedSuccessfully) {
            onNavigateBack()
        }
    }

    if (state.showK9sPickerDialog && state.importedK9sAccounts.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = viewModel::dismissK9sPickerDialog,
            title = { Text(".k9s から取り込むアカウントを選択") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.importedK9sAccounts.forEach { acc ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = acc.isImapSupported) {
                                    viewModel.applyK9sAccount(acc)
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = if (acc.isImapSupported) {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLowest
                                },
                            ),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = acc.email.ifBlank { acc.accountName },
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                val detail = if (acc.isImapSupported) {
                                    "IMAP ${acc.imapHost}:${acc.imapPort} / SMTP ${acc.smtpHost}:${acc.smtpPort} (${if (acc.usesOAuth2) "XOAuth2" else acc.imapAuthType})"
                                } else {
                                    "${acc.incomingType} (非対応: IMAP のみ対応)"
                                }
                                Text(
                                    text = detail,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissK9sPickerDialog) {
                    Text("閉じる")
                }
            },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(if (isEditing) "アカウント編集" else "アカウント追加") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.isTestingOrSaving) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (!isEditing) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.large,
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "K-9 Mail / Thunderbird (.k9s) から移行",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "エクスポートした .k9s ファイルから IMAP / SMTP / TLS / ユーザー設定を自動入力します（ZIP・XML 両対応）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = { k9sPickerLauncher.launch(arrayOf("*/*")) },
                            enabled = !state.isTestingOrSaving,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(".k9s ファイルを選択して読み込む")
                        }
                    }
                }

                val builtInQuickPresets = OAuth2Providers.presets.filter {
                    it.id != OAuth2Providers.Custom.id && OAuth2Providers.hasBuiltInClientId(it)
                }
                if (builtInQuickPresets.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = MaterialTheme.shapes.large,
                        tonalElevation = 1.dp,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "ワンタップでログイン",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            builtInQuickPresets.forEach { preset ->
                                Button(
                                    onClick = {
                                        launchOAuthIntent(
                                            viewModel.prepareAndCreateOAuthIntentForPreset(preset),
                                        )
                                    },
                                    enabled = !state.isTestingOrSaving,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(OAuth2Providers.signInButtonLabel(preset))
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            }

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = state.authTab == AuthModeTab.PASSWORD,
                    onClick = { viewModel.setAuthTab(AuthModeTab.PASSWORD) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) {
                    Text("パスワード")
                }
                SegmentedButton(
                    selected = state.authTab == AuthModeTab.OAUTH2,
                    onClick = { viewModel.setAuthTab(AuthModeTab.OAUTH2) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) {
                    Text("OAuth2")
                }
            }

            if (state.authTab == AuthModeTab.PASSWORD && !isEditing) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "プロバイダ設定プリセット",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MailServerPresets.presets.forEach { preset ->
                            FilterChip(
                                selected = state.selectedMailPresetId == preset.id,
                                onClick = { viewModel.applyMailServerPreset(preset) },
                                label = { Text(preset.label) },
                            )
                        }
                    }
                }
            }

            if (state.authTab == AuthModeTab.OAUTH2) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OAuth2Providers.presets.forEach { preset ->
                        FilterChip(
                            selected = state.selectedPreset.id == preset.id,
                            onClick = { viewModel.selectPreset(preset) },
                            label = { Text(preset.displayName) },
                        )
                    }
                }

                val hasBuiltInClient = OAuth2Providers.hasBuiltInClientId(state.selectedPreset)
                val showManualClientInputs =
                    state.selectedPreset.id == OAuth2Providers.Custom.id ||
                    !hasBuiltInClient ||
                    state.useCustomOAuthClientId

                if (!showManualClientInputs) {
                    Button(
                        onClick = { launchOAuthIntent(viewModel.createOAuthIntent()) },
                        enabled = !state.isTestingOrSaving,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (state.oauthAuthStateJson != null) {
                                "認証済み (${state.oauthCompletedEmail ?: state.email}) — 再ログイン"
                            } else {
                                OAuth2Providers.signInButtonLabel(state.selectedPreset)
                            },
                        )
                    }
                    TextButton(onClick = { viewModel.setUseCustomOAuthClientId(true) }) {
                        Text("詳細設定（独自の Client ID を上書き指定）")
                    }
                } else {
                    if (hasBuiltInClient && state.selectedPreset.id != OAuth2Providers.Custom.id) {
                        TextButton(onClick = { viewModel.setUseCustomOAuthClientId(false) }) {
                            Text("組み込み Client ID を使用してシンプル表示に戻す")
                        }
                    }

                    OutlinedTextField(
                        value = state.oauthClientId,
                        onValueChange = { v -> viewModel.updateField { copy(oauthClientId = v) } },
                        label = { Text("OAuth2 Client ID") },
                        placeholder = {
                            Text(
                                when {
                                    OAuth2Providers.isGooglePreset(state.selectedPreset) ->
                                        "123456789012-xxxx.apps.googleusercontent.com"
                                    OAuth2Providers.isMicrosoftPreset(state.selectedPreset) ->
                                        "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
                                    else -> "プロバイダの OAuth2 Client ID"
                                },
                            )
                        },
                        supportingText = {
                            Text(
                                when {
                                    OAuth2Providers.isGooglePreset(state.selectedPreset) ->
                                        "末尾 .apps.googleusercontent.com の Android 用 Client ID（下の案内参照）"
                                    OAuth2Providers.isMicrosoftPreset(state.selectedPreset) ->
                                        "Microsoft Entra ID のアプリケーション (クライアント) ID（UUID形式）"
                                    else -> "末尾のスペース・引用符を除いて入力してください"
                                },
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (state.selectedPreset.id == OAuth2Providers.Custom.id) {
                        OutlinedTextField(
                            value = state.customAuthEndpoint,
                            onValueChange = { v -> viewModel.updateField { copy(customAuthEndpoint = v) } },
                            label = { Text("Authorization Endpoint") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = state.customTokenEndpoint,
                            onValueChange = { v -> viewModel.updateField { copy(customTokenEndpoint = v) } },
                            label = { Text("Token Endpoint") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = state.customScope,
                            onValueChange = { v -> viewModel.updateField { copy(customScope = v) } },
                            label = { Text("Scopes") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    OutlinedButton(
                        onClick = { launchOAuthIntent(viewModel.createOAuthIntent()) },
                        enabled = !state.isTestingOrSaving,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (state.oauthAuthStateJson != null) {
                                "OAuth2認証済み (${state.oauthCompletedEmail ?: state.email})"
                            } else {
                                OAuth2Providers.signInButtonLabel(state.selectedPreset)
                            },
                        )
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (!hasBuiltInClient && state.selectedPreset.id != OAuth2Providers.Custom.id) {
                                Text(
                                    text = "※ このビルドには `oauth.properties` の Client ID が未設定です。プロジェクト直下の oauth.properties に Client ID を書いて再ビルドすると、上の入力欄が消えてボタン1タップでログインできるようになります。",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                            if (state.selectedPreset.id == OAuth2Providers.Gmail.id) {
                                Button(
                                    onClick = viewModel::switchGmailToAppPasswordPreset,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("個人 Gmail はアプリパスワード方式へ切替 (設定不要・推奨)")
                                }
                                Text(
                                    text = "Google Cloud Console で「Android」型 Client ID を作成し、以下の値を登録してください：",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    text = "パッケージ名: ${state.appPackageName}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                if (state.appSigningSha1.isNotBlank()) {
                                    Text(
                                        text = "APK 署名 SHA-1: ${state.appSigningSha1}",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                Text(
                                    text = "詳細設定で「カスタム URI スキーム」を有効化 (${OAuth2Providers.REDIRECT_URI}) し、同意画面が「テスト中」なら対象 Gmail をテストユーザーに追加してください。",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    AssistChip(
                                        onClick = {
                                            copyOAuthValue(context, "Package Name", state.appPackageName) {
                                                viewModel.updateField { copy(statusMessage = "パッケージ名をコピーしました: ${state.appPackageName}") }
                                            }
                                        },
                                        label = { Text("パッケージ名をコピー") },
                                    )
                                    if (state.appSigningSha1.isNotBlank()) {
                                        AssistChip(
                                            onClick = {
                                                copyOAuthValue(context, "APK SHA-1", state.appSigningSha1) {
                                                    viewModel.updateField { copy(statusMessage = "SHA-1 をコピーしました: ${state.appSigningSha1}") }
                                                }
                                            },
                                            label = { Text("SHA-1をコピー") },
                                        )
                                    }
                                    AssistChip(
                                        onClick = {
                                            copyOAuthValue(context, "Redirect URI", OAuth2Providers.REDIRECT_URI) {
                                                viewModel.updateField { copy(statusMessage = "Redirect URI をコピーしました") }
                                            }
                                        },
                                        label = { Text("Redirect URIをコピー") },
                                    )
                                }
                            } else {
                                Text(
                                    text = "Redirect URI: ${state.selectedPreset.redirectUri}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = when (state.selectedPreset.id) {
                                        OAuth2Providers.Outlook.id, OAuth2Providers.OutlookConsumers.id ->
                                            "Microsoft Entra ID でプラットフォーム「パブリック クライアント/ネイティブ (モバイルとデスクトップ)」を選び、Redirect URI に「${OAuth2Providers.MICROSOFT_REDIRECT_URI}」（スラッシュ2本 `://`）を登録したアプリケーション (クライアント) ID（UUID形式）を入力してください。"
                                        else ->
                                            "OAuth2 プロバイダに「${state.selectedPreset.redirectUri}」を許可リダイレクトURIとして登録し、Client ID と各エンドポイントを入力してください。"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                AssistChip(
                                    onClick = {
                                        val targetUri = state.selectedPreset.redirectUri
                                        copyOAuthValue(context, "Redirect URI", targetUri) {
                                            viewModel.updateField { copy(statusMessage = "Redirect URI をコピーしました: $targetUri") }
                                        }
                                    },
                                    label = { Text("Redirect URIをコピー") },
                                )
                            }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = state.email,
                onValueChange = viewModel::onEmailChanged,
                enabled = !isEditing,
                label = { Text("メールアドレス") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            state.suggestedPreset?.let { suggested ->
                AssistChip(
                    onClick = { viewModel.applyMailServerPreset(suggested) },
                    label = { Text("${suggested.label} の接続設定を自動入力") },
                )
            }

            state.serverHintMessage?.let { hint ->
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }

            OutlinedTextField(
                value = state.displayName,
                onValueChange = { v -> viewModel.updateField { copy(displayName = v) } },
                label = { Text("表示名 (任意)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.imapHost,
                    onValueChange = { v -> viewModel.updateField { copy(imapHost = v) } },
                    label = { Text("IMAPホスト") },
                    singleLine = true,
                    modifier = Modifier.weight(2f),
                )
                OutlinedTextField(
                    value = state.imapPort,
                    onValueChange = { v -> viewModel.updateField { copy(imapPort = v) } },
                    label = { Text("Port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StoredSecurityMode.entries.forEach { mode ->
                    FilterChip(
                        selected = state.securityMode == mode,
                        onClick = { viewModel.onSecurityModeChanged(mode) },
                        label = {
                            Text(
                                when (mode) {
                                    StoredSecurityMode.IMPLICIT_TLS -> "SSL/TLS"
                                    StoredSecurityMode.START_TLS -> "STARTTLS"
                                    StoredSecurityMode.PLAINTEXT -> "平文(検証用)"
                                },
                            )
                        },
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.smtpHost,
                    onValueChange = { v -> viewModel.updateField { copy(smtpHost = v) } },
                    label = { Text("SMTPホスト (任意)") },
                    singleLine = true,
                    modifier = Modifier.weight(2f),
                )
                OutlinedTextField(
                    value = state.smtpPort,
                    onValueChange = { v -> viewModel.updateField { copy(smtpPort = v) } },
                    label = { Text("Port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }

            if (state.authTab == AuthModeTab.PASSWORD) {
                OutlinedTextField(
                    value = state.username,
                    onValueChange = { v -> viewModel.updateField { copy(username = v) } },
                    label = { Text("ユーザー名 (空欄時はメールアドレス)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.password,
                    onValueChange = { v -> viewModel.updateField { copy(password = v) } },
                    label = { Text("パスワード / アプリパスワード") },
                    visualTransformation = if (state.passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = viewModel::togglePasswordVisible) {
                            Icon(
                                imageVector = if (state.passwordVisible) {
                                    KazeIcons.VisibilityOff
                                } else {
                                    KazeIcons.Visibility
                                },
                                contentDescription = if (state.passwordVisible) "パスワードを隠す" else "パスワードを表示",
                            )
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            state.statusMessage?.let { msg ->
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(msg, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }

            state.errorMessage?.let { err ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        err,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = viewModel::testAndSaveAccount,
                enabled = !state.isTestingOrSaving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (isEditing) {
                        "接続を再検証して設定を更新"
                    } else {
                        "接続検証して保存・フェーズ1初期同期を開始"
                    },
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private fun copyOAuthValue(
    context: Context,
    label: String,
    value: String,
    onCopied: () -> Unit,
) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, value))
    onCopied()
}
