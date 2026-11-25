package com.kazemail.app.ui.account

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import java.security.MessageDigest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kazemail.app.auth.OAuth2ProviderPreset
import com.kazemail.app.auth.OAuth2Providers
import com.kazemail.app.data.db.AccountEntity
import com.kazemail.app.di.KazeMailContainer
import com.kazemail.app.importexport.K9sImportedAccount
import com.kazemail.app.importexport.K9sSettingsParser
import com.kazemail.app.security.StoredAccountCredentials
import com.kazemail.app.security.StoredSecurityMode
import com.kazemail.app.sync.CoreImapAuth
import com.kazemail.app.sync.CoreImapConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AuthModeTab {
    PASSWORD,
    OAUTH2,
}

data class MailServerPreset(
    val id: String,
    val label: String,
    val imapHost: String,
    val imapPort: Int,
    val smtpHost: String,
    val smtpPort: Int,
    val securityMode: StoredSecurityMode,
    val helperHint: String?,
)

object MailServerPresets {
    val Manual = MailServerPreset(
        id = "manual",
        label = "手動設定",
        imapHost = "",
        imapPort = 993,
        smtpHost = "",
        smtpPort = 465,
        securityMode = StoredSecurityMode.IMPLICIT_TLS,
        helperHint = null,
    )

    val Gmail = MailServerPreset(
        id = "gmail",
        label = "Gmail",
        imapHost = "imap.gmail.com",
        imapPort = 993,
        smtpHost = "smtp.gmail.com",
        smtpPort = 465,
        securityMode = StoredSecurityMode.IMPLICIT_TLS,
        helperHint = "パスワード認証では Google アカウントの「アプリパスワード」が必要です（OAuth2 タブも利用可能）。",
    )

    val YahooJp = MailServerPreset(
        id = "yahoo_jp",
        label = "Yahoo!メール",
        imapHost = "imap.mail.yahoo.co.jp",
        imapPort = 993,
        smtpHost = "smtp.mail.yahoo.co.jp",
        smtpPort = 465,
        securityMode = StoredSecurityMode.IMPLICIT_TLS,
        helperHint = "Yahoo! JAPAN 設定側で IMAP/SMTP アクセスが有効になっている必要があります。",
    )

    val ICloud = MailServerPreset(
        id = "icloud",
        label = "iCloud",
        imapHost = "imap.mail.me.com",
        imapPort = 993,
        smtpHost = "smtp.mail.me.com",
        smtpPort = 587,
        securityMode = StoredSecurityMode.IMPLICIT_TLS,
        helperHint = "Apple Account で発行した「App用パスワード」を入力してください。",
    )

    val Outlook = MailServerPreset(
        id = "outlook",
        label = "Outlook",
        imapHost = "outlook.office365.com",
        imapPort = 993,
        smtpHost = "smtp.office365.com",
        smtpPort = 587,
        securityMode = StoredSecurityMode.IMPLICIT_TLS,
        helperHint = "個人向け Microsoft アカウントは近年 OAuth2 のみを要求する場合があります。",
    )

    val presets: List<MailServerPreset> = listOf(Manual, Gmail, YahooJp, ICloud, Outlook)

    fun suggestForEmail(email: String): MailServerPreset? {
        val domain = email.trim().substringAfter('@', "").lowercase()
        return when {
            domain == "gmail.com" || domain == "googlemail.com" -> Gmail
            domain == "yahoo.co.jp" || domain == "ymail.ne.jp" -> YahooJp
            domain == "icloud.com" || domain == "me.com" || domain == "mac.com" -> ICloud
            domain == "outlook.com" || domain == "outlook.jp" || domain == "hotmail.com" || domain == "live.jp" -> Outlook
            else -> null
        }
    }
}

data class AddAccountUiState(
    val editingAccountId: Long? = null,
    val selectedMailPresetId: String = MailServerPresets.Manual.id,
    val serverHintMessage: String? = null,
    val suggestedPreset: MailServerPreset? = null,
    val authTab: AuthModeTab = AuthModeTab.PASSWORD,
    val email: String = "",
    val displayName: String = "",
    val imapHost: String = "",
    val imapPort: String = "993",
    val smtpHost: String = "",
    val smtpPort: String = "465",
    val securityMode: StoredSecurityMode = StoredSecurityMode.IMPLICIT_TLS,
    val username: String = "",
    val password: String = "",
    val passwordVisible: Boolean = false,
    val selectedPreset: OAuth2ProviderPreset = OAuth2Providers.Gmail,
    val oauthClientId: String = "",
    val useCustomOAuthClientId: Boolean = false,
    val appPackageName: String = "com.kazemail.app",
    val appSigningSha1: String = "",
    val customAuthEndpoint: String = "",
    val customTokenEndpoint: String = "",
    val customScope: String = OAuth2Providers.Custom.scope,
    val oauthCompletedEmail: String? = null,
    val oauthAuthStateJson: String? = null,
    val oauthAccessTokenPreview: String? = null,
    val isTestingOrSaving: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val savedSuccessfully: Boolean = false,
    val importedK9sAccounts: List<K9sImportedAccount> = emptyList(),
    val showK9sPickerDialog: Boolean = false,
)

class AddAccountViewModel(
    private val container: KazeMailContainer,
    private val editAccountId: Long? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddAccountUiState(editingAccountId = editAccountId))
    val uiState: StateFlow<AddAccountUiState> = _uiState.asStateFlow()

    init {
        if (editAccountId != null && editAccountId > 0L) {
            loadAccountForEditing(editAccountId)
        }
    }

    private fun loadAccountForEditing(accountId: Long) {
        viewModelScope.launch {
            runCatching<Pair<AccountEntity, StoredAccountCredentials?>?> {
                val account = container.database.accountDao().getById(accountId) ?: return@runCatching null
                val creds = container.credentialStore.loadCredentials(accountId)
                account to creds
            }.onSuccess { loaded ->
                val (account, creds) = loaded ?: return@onSuccess
                _uiState.update { cur ->
                    when (creds) {
                        is StoredAccountCredentials.OAuth2 -> {
                            val preset = OAuth2Providers.presets.firstOrNull { it.id == creds.providerId }
                                ?: OAuth2Providers.Gmail
                            val savedClientId = runCatching {
                                net.openid.appauth.AuthState.jsonDeserialize(creds.authStateJson)
                                    .lastAuthorizationResponse?.request?.clientId
                            }.getOrNull().orEmpty()
                            val builtInId = OAuth2Providers.sanitizeClientId(preset.defaultClientId)
                            val isCustomOverride = savedClientId.isNotBlank() && savedClientId != builtInId
                            cur.copy(
                                editingAccountId = account.accountId,
                                authTab = AuthModeTab.OAUTH2,
                                selectedPreset = preset,
                                oauthClientId = if (isCustomOverride) savedClientId else "",
                                useCustomOAuthClientId = isCustomOverride,
                                email = account.emailAddress,
                                displayName = account.displayName.orEmpty(),
                                imapHost = account.imapHost,
                                imapPort = account.imapPort.toString(),
                                smtpHost = account.smtpHost,
                                smtpPort = account.smtpPort.toString(),
                                securityMode = creds.securityMode,
                                oauthCompletedEmail = creds.email,
                                oauthAuthStateJson = creds.authStateJson,
                                oauthAccessTokenPreview = "loaded_existing_state",
                                statusMessage = "既存アカウントを読み込みました。設定を変更して保存できます。",
                            )
                        }
                        is StoredAccountCredentials.Password -> cur.copy(
                            editingAccountId = account.accountId,
                            authTab = AuthModeTab.PASSWORD,
                            email = account.emailAddress,
                            displayName = account.displayName.orEmpty(),
                            imapHost = account.imapHost,
                            imapPort = account.imapPort.toString(),
                            smtpHost = account.smtpHost,
                            smtpPort = account.smtpPort.toString(),
                            securityMode = creds.securityMode,
                            username = creds.username,
                            password = creds.password,
                            statusMessage = "既存アカウントを読み込みました。設定を変更して保存できます。",
                        )
                        null -> cur.copy(
                            editingAccountId = account.accountId,
                            email = account.emailAddress,
                            displayName = account.displayName.orEmpty(),
                            imapHost = account.imapHost,
                            imapPort = account.imapPort.toString(),
                            smtpHost = account.smtpHost,
                            smtpPort = account.smtpPort.toString(),
                            statusMessage = "パスワードまたは認証を再設定して保存してください。",
                        )
                    }
                }
            }
        }
    }

    fun applyMailServerPreset(preset: MailServerPreset) {
        _uiState.update { state ->
            if (preset.id == MailServerPresets.Manual.id) {
                state.copy(
                    selectedMailPresetId = preset.id,
                    serverHintMessage = null,
                    errorMessage = null,
                )
            } else {
                state.copy(
                    selectedMailPresetId = preset.id,
                    imapHost = preset.imapHost,
                    imapPort = preset.imapPort.toString(),
                    smtpHost = preset.smtpHost,
                    smtpPort = preset.smtpPort.toString(),
                    securityMode = preset.securityMode,
                    serverHintMessage = preset.helperHint,
                    suggestedPreset = null,
                    errorMessage = null,
                )
            }
        }
    }

    fun onEmailChanged(newEmail: String) {
        _uiState.update { state ->
            val suggestion = if (state.imapHost.isBlank()) {
                MailServerPresets.suggestForEmail(newEmail)
            } else {
                null
            }
            state.copy(
                email = newEmail,
                suggestedPreset = suggestion,
                errorMessage = null,
            )
        }
    }

    fun onSecurityModeChanged(newMode: StoredSecurityMode) {
        _uiState.update { state ->
            val nextImapPort = when (state.imapPort.trim()) {
                "993", "143", "" -> if (newMode == StoredSecurityMode.IMPLICIT_TLS) "993" else "143"
                else -> state.imapPort
            }
            val nextSmtpPort = when (state.smtpPort.trim()) {
                "465", "587", "25", "" -> when (newMode) {
                    StoredSecurityMode.IMPLICIT_TLS -> "465"
                    StoredSecurityMode.START_TLS -> "587"
                    StoredSecurityMode.PLAINTEXT -> "25"
                }
                else -> state.smtpPort
            }
            state.copy(
                securityMode = newMode,
                imapPort = nextImapPort,
                smtpPort = nextSmtpPort,
                errorMessage = null,
            )
        }
    }

    fun togglePasswordVisible() {
        _uiState.update { it.copy(passwordVisible = !it.passwordVisible) }
    }

    fun setAuthTab(tab: AuthModeTab) {
        _uiState.update { state ->
            if (tab == AuthModeTab.OAUTH2 && state.imapHost.isBlank()) {
                val p = state.selectedPreset
                state.copy(
                    authTab = tab,
                    imapHost = p.imapHost,
                    imapPort = p.imapPort.toString(),
                    smtpHost = p.smtpHost,
                    smtpPort = p.smtpPort.toString(),
                    errorMessage = null,
                )
            } else {
                state.copy(authTab = tab, errorMessage = null)
            }
        }
    }

    fun selectPreset(preset: OAuth2ProviderPreset) {
        _uiState.update {
            it.copy(
                selectedPreset = preset,
                imapHost = preset.imapHost,
                imapPort = preset.imapPort.toString(),
                smtpHost = preset.smtpHost,
                smtpPort = preset.smtpPort.toString(),
                securityMode = StoredSecurityMode.IMPLICIT_TLS,
                useCustomOAuthClientId = preset.id == OAuth2Providers.Custom.id,
                customAuthEndpoint = preset.authorizationEndpoint,
                customTokenEndpoint = preset.tokenEndpoint,
                customScope = preset.scope,
                errorMessage = null,
            )
        }
    }

    fun setUseCustomOAuthClientId(enabled: Boolean) {
        _uiState.update { it.copy(useCustomOAuthClientId = enabled, errorMessage = null) }
    }

    fun prepareAndCreateOAuthIntentForPreset(preset: OAuth2ProviderPreset): Intent? {
        _uiState.update {
            it.copy(
                authTab = AuthModeTab.OAUTH2,
                selectedPreset = preset,
                imapHost = preset.imapHost,
                imapPort = preset.imapPort.toString(),
                smtpHost = preset.smtpHost,
                smtpPort = preset.smtpPort.toString(),
                securityMode = StoredSecurityMode.IMPLICIT_TLS,
                useCustomOAuthClientId = false,
                customAuthEndpoint = preset.authorizationEndpoint,
                customTokenEndpoint = preset.tokenEndpoint,
                customScope = preset.scope,
                errorMessage = null,
            )
        }
        return createOAuthIntent()
    }

    fun switchGmailToAppPasswordPreset() {
        _uiState.update {
            it.copy(
                authTab = AuthModeTab.PASSWORD,
                selectedMailPresetId = MailServerPresets.Gmail.id,
                imapHost = MailServerPresets.Gmail.imapHost,
                imapPort = MailServerPresets.Gmail.imapPort.toString(),
                smtpHost = MailServerPresets.Gmail.smtpHost,
                smtpPort = MailServerPresets.Gmail.smtpPort.toString(),
                securityMode = MailServerPresets.Gmail.securityMode,
                serverHintMessage = MailServerPresets.Gmail.helperHint,
                errorMessage = null,
            )
        }
    }

    fun updateField(block: AddAccountUiState.() -> AddAccountUiState) {
        _uiState.update { it.block().copy(errorMessage = null) }
    }

    fun importFromK9sUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isTestingOrSaving = true,
                    statusMessage = ".k9s 設定ファイルを解析中…",
                    errorMessage = null,
                )
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        K9sSettingsParser.parseStream(stream)
                    } ?: error(".k9s ファイルを開けませんでした")
                }
            }.onSuccess { result ->
                val imapAccounts = result.accounts.filter { it.isImapSupported }
                when {
                    result.accounts.isEmpty() -> {
                        _uiState.update {
                            it.copy(
                                isTestingOrSaving = false,
                                statusMessage = null,
                                errorMessage = ".k9s ファイル内にアカウント定義が見つかりませんでした",
                            )
                        }
                    }
                    imapAccounts.isEmpty() -> {
                        _uiState.update {
                            it.copy(
                                isTestingOrSaving = false,
                                statusMessage = null,
                                errorMessage = "検出されたアカウントはいずれも POP3 等のため非対応です（KazeMail は IMAP 対応）",
                            )
                        }
                    }
                    result.accounts.size == 1 -> {
                        applyK9sAccount(imapAccounts.single())
                    }
                    else -> {
                        _uiState.update {
                            it.copy(
                                isTestingOrSaving = false,
                                importedK9sAccounts = result.accounts,
                                showK9sPickerDialog = true,
                                statusMessage = "${result.accounts.size} 件のアカウントを検出しました。取り込むアカウントを選択してください。",
                            )
                        }
                    }
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isTestingOrSaving = false,
                        statusMessage = null,
                        errorMessage = ".k9s 読み込みエラー: ${err.message}",
                    )
                }
            }
        }
    }

    fun dismissK9sPickerDialog() {
        _uiState.update { it.copy(showK9sPickerDialog = false) }
    }

    fun applyK9sAccount(acc: K9sImportedAccount) {
        if (!acc.isImapSupported) {
            _uiState.update {
                it.copy(
                    isTestingOrSaving = false,
                    showK9sPickerDialog = false,
                    errorMessage = "${acc.email.ifBlank { acc.accountName }} は ${acc.incomingType} のため非対応です",
                )
            }
            return
        }
        val preset = OAuth2Providers.presets.firstOrNull { it.id == acc.suggestedOAuthPresetId }
            ?: OAuth2Providers.Custom
        val targetTab = if (acc.usesOAuth2) AuthModeTab.OAUTH2 else AuthModeTab.PASSWORD
        val importedPassword = acc.incomingPassword.orEmpty()
        val guidance = when {
            acc.usesOAuth2 -> ".k9s から読み込み完了 (${acc.email})。OAuth2 Client ID を入力して「OAuth2でサインイン」を実行してください。"
            importedPassword.isNotBlank() -> ".k9s から読み込み完了 (${acc.email})。内容を確認し「接続検証して保存」を押してください。"
            else -> ".k9s から読み込み完了 (${acc.email})。K-9 のエクスポート仕様上パスワードは含まれないため、パスワード / アプリパスワードを入力して保存してください。"
        }

        _uiState.update {
            it.copy(
                authTab = targetTab,
                selectedPreset = preset,
                email = acc.email,
                displayName = acc.displayName,
                imapHost = acc.imapHost,
                imapPort = acc.imapPort.toString(),
                smtpHost = acc.smtpHost,
                smtpPort = acc.smtpPort.toString(),
                securityMode = acc.imapSecurity,
                username = acc.username,
                password = importedPassword,
                customAuthEndpoint = preset.authorizationEndpoint,
                customTokenEndpoint = preset.tokenEndpoint,
                customScope = preset.scope,
                isTestingOrSaving = false,
                showK9sPickerDialog = false,
                statusMessage = guidance,
                errorMessage = null,
            )
        }
    }

    fun loadAppSigningInfo(context: Context) {
        val pkgName = context.packageName
        val sha1 = runCatching {
            val certs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = context.packageManager.getPackageInfo(
                    pkgName,
                    PackageManager.GET_SIGNING_CERTIFICATES,
                )
                info.signingInfo?.apkContentsSigners
            } else {
                legacySignatures(context, pkgName)
            }
            val firstCert = certs?.firstOrNull()?.toByteArray() ?: return@runCatching ""
            val digest = MessageDigest.getInstance("SHA-1").digest(firstCert)
            digest.joinToString(":") { byte -> "%02X".format(byte) }
        }.getOrDefault("")
        _uiState.update {
            it.copy(
                appPackageName = pkgName,
                appSigningSha1 = sha1,
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun legacySignatures(context: Context, pkgName: String) =
        context.packageManager.getPackageInfo(pkgName, PackageManager.GET_SIGNATURES).signatures

    fun createOAuthIntent(): Intent? {
        val state = _uiState.value
        val effectivePreset = if (state.selectedPreset.id == OAuth2Providers.Custom.id) {
            state.selectedPreset.copy(
                authorizationEndpoint = state.customAuthEndpoint.trim(),
                tokenEndpoint = state.customTokenEndpoint.trim(),
                scope = state.customScope.trim(),
            )
        } else {
            state.selectedPreset
        }
        val usingManualClientId = state.useCustomOAuthClientId ||
            !OAuth2Providers.hasBuiltInClientId(effectivePreset) ||
            effectivePreset.id == OAuth2Providers.Custom.id
        if (usingManualClientId) {
            val cleanedManual = OAuth2Providers.sanitizeClientId(state.oauthClientId)
            if (cleanedManual != state.oauthClientId) {
                _uiState.update { it.copy(oauthClientId = cleanedManual) }
            }
        }
        val clientId = OAuth2Providers.resolveClientId(
            preset = effectivePreset,
            manualOverride = state.oauthClientId,
            useManualOverride = state.useCustomOAuthClientId,
        )
        val validationError = OAuth2Providers.validateClientId(effectivePreset, clientId)
        if (validationError != null) {
            _uiState.update { it.copy(errorMessage = validationError) }
            return null
        }
        if (effectivePreset.authorizationEndpoint.isBlank() || effectivePreset.tokenEndpoint.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Authorization / Token Endpoint を入力してください") }
            return null
        }

        return runCatching {
            container.oauth2Manager.buildAuthorizationIntent(
                preset = effectivePreset,
                clientId = clientId,
                loginHintEmail = state.email.takeIf { it.isNotBlank() },
            )
        }.onFailure { err ->
            _uiState.update { it.copy(errorMessage = "OAuth2起動失敗: ${err.message}") }
        }.getOrNull()
    }

    fun handleOAuthActivityResult(data: Intent?) {
        if (data == null) {
            _uiState.update { it.copy(errorMessage = "OAuth2認証がキャンセルされました") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isTestingOrSaving = true, statusMessage = "OAuth2トークン交換中…") }
            runCatching {
                container.oauth2Manager.exchangeAuthorizationResult(
                    intent = data,
                    fallbackEmail = _uiState.value.email.trim(),
                )
            }.onSuccess { res ->
                _uiState.update {
                    it.copy(
                        isTestingOrSaving = false,
                        email = res.emailHint.ifBlank { it.email },
                        oauthCompletedEmail = res.emailHint,
                        oauthAuthStateJson = res.updatedAuthStateJson,
                        oauthAccessTokenPreview = res.accessToken,
                        statusMessage = "OAuth2トークン取得完了 (${res.emailHint})",
                        errorMessage = null,
                    )
                }
            }.onFailure { err ->
                val rawMsg = err.message.orEmpty()
                val formatted = if (rawMsg.contains("invalid_client", ignoreCase = true) ||
                    rawMsg.contains("not found", ignoreCase = true)
                ) {
                    "OAuth2トークン取得エラー ($rawMsg): 指定された Client ID が Google / Microsoft 側で見つかりません。Client ID 全文、クライアント種別（Android）、パッケージ名 (${_uiState.value.appPackageName}) と SHA-1 一致を確認してください。"
                } else {
                    "OAuth2トークン取得エラー: $rawMsg"
                }
                _uiState.update {
                    it.copy(
                        isTestingOrSaving = false,
                        statusMessage = null,
                        errorMessage = formatted,
                    )
                }
            }
        }
    }

    fun testAndSaveAccount() {
        val s = _uiState.value
        val email = s.email.trim()
        val imapHost = s.imapHost.trim()
        val imapPort = s.imapPort.toIntOrNull() ?: 993
        val smtpHost = s.smtpHost.trim().ifBlank { imapHost.replaceFirst("imap.", "smtp.") }
        val smtpPort = s.smtpPort.toIntOrNull() ?: 465

        if (email.isEmpty() || imapHost.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "メールアドレスとIMAPホストを入力してください") }
            return
        }

        viewModelScope.launch {
            val (coreAuth, storedCreds, authType) = when (s.authTab) {
                AuthModeTab.PASSWORD -> {
                    val user = s.username.trim().ifBlank { email }
                    if (s.password.isEmpty()) {
                        _uiState.update { it.copy(errorMessage = "パスワード / アプリパスワードを入力してください") }
                        return@launch
                    }
                    Triple(
                        CoreImapAuth.Password(user, s.password),
                        StoredAccountCredentials.Password(user, s.password, s.securityMode),
                        "PASSWORD",
                    )
                }
                AuthModeTab.OAUTH2 -> {
                    val authJson = s.oauthAuthStateJson
                    if (authJson.isNullOrBlank()) {
                        _uiState.update { it.copy(errorMessage = "先に「OAuth2でサインイン」を実行してください") }
                        return@launch
                    }
                    val freshToken = if (s.oauthAccessTokenPreview == "loaded_existing_state" || s.oauthAccessTokenPreview.isNullOrBlank()) {
                        runCatching {
                            container.oauth2Manager.ensureFreshAccessToken(authJson, email).accessToken
                        }.getOrElse { err ->
                            _uiState.update { it.copy(errorMessage = "OAuth2トークン更新失敗: ${err.message}。再度サインインしてください。") }
                            return@launch
                        }
                    } else {
                        s.oauthAccessTokenPreview
                    }
                    Triple(
                        CoreImapAuth.OAuth2(email, freshToken),
                        StoredAccountCredentials.OAuth2(
                            email = email,
                            providerId = s.selectedPreset.id,
                            authStateJson = authJson,
                            securityMode = s.securityMode,
                        ),
                        "OAUTH2",
                    )
                }
            }

            _uiState.update {
                it.copy(
                    isTestingOrSaving = true,
                    statusMessage = "Rust IMAPコアで接続・READ-ONLY検証中…",
                    errorMessage = null,
                )
            }

            runCatching {
                val folders = container.syncEngine.testConnectionAndDiscoverFolders(
                    CoreImapConfig(
                        host = imapHost,
                        port = imapPort,
                        security = s.securityMode,
                        auth = coreAuth,
                    ),
                )
                val existing = s.editingAccountId?.let { container.database.accountDao().getById(it) }
                    ?: container.database.accountDao().getByEmail(email)
                val accountId = if (existing == null) {
                    container.database.accountDao().insert(
                        AccountEntity(
                            emailAddress = email,
                            displayName = s.displayName.trim().ifBlank { null },
                            imapHost = imapHost,
                            imapPort = imapPort,
                            smtpHost = smtpHost,
                            smtpPort = smtpPort,
                            authType = authType,
                        ),
                    )
                } else {
                    container.database.accountDao().updateConnectionConfig(
                        accountId = existing.accountId,
                        displayName = s.displayName.trim().ifBlank { null },
                        imapHost = imapHost,
                        imapPort = imapPort,
                        smtpHost = smtpHost,
                        smtpPort = smtpPort,
                        authType = authType,
                    )
                    existing.accountId
                }
                container.credentialStore.saveCredentials(accountId, storedCreds)
                _uiState.update {
                    it.copy(
                        statusMessage = "接続成功 (${folders.size}フォルダ検出)。フェーズ1初期同期を開始中…",
                    )
                }
                val p1 = container.syncEngine.runPhase1InboxSync(accountId)
                if (!p1.backfillCompleted) {
                    container.syncScheduler.enqueueInitialBackfill(
                        accountId = accountId,
                        wifiOnly = container.appPreferences.wifiOnlyBackfill.value,
                    )
                }
                container.syncScheduler.ensurePeriodicIncrementalSync(
                    container.appPreferences.periodicSyncIntervalMinutes.value,
                )
            }.onSuccess {
                _uiState.update {
                    it.copy(
                        isTestingOrSaving = false,
                        savedSuccessfully = true,
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isTestingOrSaving = false,
                        statusMessage = null,
                        errorMessage = "接続・保存エラー: ${err.message}",
                    )
                }
            }
        }
    }

    companion object {
        fun factory(
            container: KazeMailContainer,
            editAccountId: Long? = null,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AddAccountViewModel(container, editAccountId) as T
            }
    }
}
