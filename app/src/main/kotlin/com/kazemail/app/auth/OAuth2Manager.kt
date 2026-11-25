package com.kazemail.app.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.kazemail.app.BuildConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OAuth2ProviderPreset(
    val id: String,
    val displayName: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val scope: String,
    val imapHost: String,
    val imapPort: Int,
    val smtpHost: String,
    val smtpPort: Int,
    val sendOfflineAccessTypeParam: Boolean = false,
    val defaultClientId: String = "",
    val redirectUri: String = OAuth2Providers.REDIRECT_URI,
)

object OAuth2Providers {
    const val REDIRECT_URI = "com.kazemail.app:/oauth2redirect"
    const val MICROSOFT_REDIRECT_URI = "com.kazemail.app://oauth2redirect"

    val Gmail = OAuth2ProviderPreset(
        id = "gmail",
        displayName = "Gmail (Google)",
        authorizationEndpoint = "https://accounts.google.com/o/oauth2/v2/auth",
        tokenEndpoint = "https://oauth2.googleapis.com/token",
        scope = "openid email profile https://mail.google.com/",
        imapHost = "imap.gmail.com",
        imapPort = 993,
        smtpHost = "smtp.gmail.com",
        smtpPort = 465,
        sendOfflineAccessTypeParam = true,
        defaultClientId = BuildConfig.GMAIL_OAUTH_CLIENT_ID.trim(),
        redirectUri = REDIRECT_URI,
    )

    val Outlook = OAuth2ProviderPreset(
        id = "outlook",
        displayName = "Microsoft 365 / Outlook",
        authorizationEndpoint = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
        tokenEndpoint = "https://login.microsoftonline.com/common/oauth2/v2.0/token",
        scope = "openid email profile offline_access https://outlook.office.com/IMAP.AccessAsUser.All https://outlook.office.com/SMTP.Send",
        imapHost = "outlook.office365.com",
        imapPort = 993,
        smtpHost = "smtp.office365.com",
        smtpPort = 587,
        sendOfflineAccessTypeParam = false,
        defaultClientId = BuildConfig.OUTLOOK_OAUTH_CLIENT_ID.trim(),
        redirectUri = MICROSOFT_REDIRECT_URI,
    )

    val OutlookConsumers = OAuth2ProviderPreset(
        id = "outlook_consumers",
        displayName = "Outlook.com (個人MSA)",
        authorizationEndpoint = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize",
        tokenEndpoint = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
        scope = "openid email profile offline_access https://outlook.office.com/IMAP.AccessAsUser.All https://outlook.office.com/SMTP.Send",
        imapHost = "outlook.office365.com",
        imapPort = 993,
        smtpHost = "smtp.office365.com",
        smtpPort = 587,
        sendOfflineAccessTypeParam = false,
        defaultClientId = BuildConfig.OUTLOOK_CONSUMERS_OAUTH_CLIENT_ID
            .ifBlank { BuildConfig.OUTLOOK_OAUTH_CLIENT_ID }
            .trim(),
        redirectUri = MICROSOFT_REDIRECT_URI,
    )

    val Custom = OAuth2ProviderPreset(
        id = "custom",
        displayName = "Custom OAuth2 / OIDC",
        authorizationEndpoint = "",
        tokenEndpoint = "",
        scope = "openid email offline_access",
        imapHost = "",
        imapPort = 993,
        smtpHost = "",
        smtpPort = 465,
        sendOfflineAccessTypeParam = false,
    )

    val presets = listOf(Gmail, Outlook, OutlookConsumers, Custom)

    private val googleClientPattern =
        Regex("^[A-Za-z0-9][A-Za-z0-9._-]{2,}\\.apps\\.googleusercontent\\.com$", RegexOption.IGNORE_CASE)
    private val microsoftUuidPattern =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    fun sanitizeClientId(raw: String): String {
        var cleaned = raw.trim()
            .trim('"', '\'', '`', '<', '>')
            .replace(Regex("\\s+"), "")
        if (cleaned.startsWith("client_id=", ignoreCase = true)) {
            cleaned = cleaned.substringAfter('=').trim().trim('"', '\'')
        }
        return cleaned
    }

    fun isGooglePreset(preset: OAuth2ProviderPreset): Boolean =
        preset.id == Gmail.id ||
            preset.authorizationEndpoint.contains("accounts.google.com", ignoreCase = true)

    fun isMicrosoftPreset(preset: OAuth2ProviderPreset): Boolean =
        preset.id == Outlook.id ||
            preset.id == OutlookConsumers.id ||
            preset.authorizationEndpoint.contains("login.microsoftonline.com", ignoreCase = true)

    fun hasBuiltInClientId(preset: OAuth2ProviderPreset): Boolean =
        sanitizeClientId(preset.defaultClientId).isNotBlank()

    fun resolveClientId(
        preset: OAuth2ProviderPreset,
        manualOverride: String,
        useManualOverride: Boolean = false,
    ): String {
        val builtIn = sanitizeClientId(preset.defaultClientId)
        val manual = sanitizeClientId(manualOverride)
        return when {
            useManualOverride -> manual.ifBlank { builtIn }
            builtIn.isNotBlank() -> builtIn
            else -> manual
        }
    }

    fun signInButtonLabel(preset: OAuth2ProviderPreset): String =
        when (preset.id) {
            Gmail.id -> "Google でログイン"
            Outlook.id -> "Microsoft 365 / Outlook でログイン"
            OutlookConsumers.id -> "Outlook.com (個人MSA) でログイン"
            else -> "OAuth2 でログイン"
        }

    /**
     * Pre-flight format validation for OAuth2 Client IDs before launching Custom Tabs.
     * Prevents immediate browser-side `401: invalid_client` / "The OAuth client was not found"
     * errors when emails, Client Secrets, API keys, or truncated prefixes are entered.
     */
    fun validateClientId(preset: OAuth2ProviderPreset, rawClientId: String): String? {
        val clientId = sanitizeClientId(rawClientId)
        if (clientId.isEmpty()) {
            return "OAuth2 Client ID を入力してください"
        }
        if (clientId.contains('@')) {
            return "OAuth2 Client ID 欄にはメールアドレスではなく、クラウド管理画面で発行したクライアント ID を入力してください。"
        }
        if (isGooglePreset(preset)) {
            if (clientId.startsWith("GOCSPX-", ignoreCase = true)) {
                return "Client Secret（GOCSPX-...）が入力されています。末尾が「.apps.googleusercontent.com」の Client ID を入力してください。"
            }
            if (clientId.startsWith("AIza")) {
                return "Google API キー（AIza...）が入力されています。OAuth 2.0 クライアント ID（末尾が .apps.googleusercontent.com）が必要です。"
            }
            if (microsoftUuidPattern.matches(clientId)) {
                return "Microsoft 用の UUID 形式 Client ID が入力されています。Gmail 用の「数字-文字列.apps.googleusercontent.com」を入力するか、上のプリセットを Outlook に切り替えてください。"
            }
            if (!googleClientPattern.matches(clientId)) {
                return "Google OAuth2 Client ID の形式が正しくありません（例: 123456789012-xxxx.apps.googleusercontent.com）。Google Cloud Console で作成した「Android」用 Client ID 全文を入力するか、下の「アプリパスワード方式で Gmail を設定する」をご利用ください。"
            }
        } else if (isMicrosoftPreset(preset)) {
            if (googleClientPattern.matches(clientId)) {
                return "Google 用の Client ID が入力されています。Microsoft Entra ID の「アプリケーション (クライアント) ID」（UUID形式）を入力するか、上のプリセットを Gmail に切り替えてください。"
            }
            if (!microsoftUuidPattern.matches(clientId)) {
                return "Microsoft OAuth2 Client ID は UUID 形式（xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx）である必要があります。"
            }
        }
        return null
    }
}

data class OAuth2SessionResult(
    val emailHint: String,
    val accessToken: String,
    val updatedAuthStateJson: String,
)

/**
 * Manages PKCE OAuth2 authorization via Custom Tabs (AppAuth) and transparent access token refresh
 * before opening XOAUTH2 IMAP sessions.
 */
class OAuth2Manager(private val context: Context) {

    fun buildAuthorizationRequest(
        preset: OAuth2ProviderPreset,
        clientId: String,
        loginHintEmail: String? = null,
    ): AuthorizationRequest {
        val serviceConfig = AuthorizationServiceConfiguration(
            Uri.parse(preset.authorizationEndpoint),
            Uri.parse(preset.tokenEndpoint),
        )
        val cleanedClientId = OAuth2Providers.sanitizeClientId(clientId)
        val builder = AuthorizationRequest.Builder(
            serviceConfig,
            cleanedClientId,
            ResponseTypeValues.CODE,
            Uri.parse(preset.redirectUri.ifBlank { OAuth2Providers.REDIRECT_URI }),
        )
            .setScope(preset.scope.trim())
            .setPrompt(AuthorizationRequest.Prompt.CONSENT)

        val useOfflineParam = preset.sendOfflineAccessTypeParam ||
            preset.authorizationEndpoint.contains("accounts.google.com", ignoreCase = true)
        if (useOfflineParam) {
            builder.setAdditionalParameters(mapOf("access_type" to "offline"))
        }
        if (!loginHintEmail.isNullOrBlank()) {
            builder.setLoginHint(loginHintEmail.trim())
        }
        return builder.build()
    }

    fun buildAuthorizationIntent(
        preset: OAuth2ProviderPreset,
        clientId: String,
        loginHintEmail: String? = null,
    ): Intent {
        val authRequest = buildAuthorizationRequest(preset, clientId, loginHintEmail)
        val authService = AuthorizationService(context)
        return try {
            authService.getAuthorizationRequestIntent(authRequest)
        } finally {
            authService.dispose()
        }
    }

    suspend fun exchangeAuthorizationResult(
        intent: Intent,
        fallbackEmail: String,
    ): OAuth2SessionResult {
        val response = AuthorizationResponse.fromIntent(intent)
        val exception = AuthorizationException.fromIntent(intent)
        if (response == null) {
            if (exception != null) {
                val detail = listOfNotNull(exception.error, exception.errorDescription)
                    .filter { it.isNotBlank() }
                    .joinToString(": ")
                    .ifBlank { exception.message ?: "OAuth2 authorization cancelled or failed" }
                throw IllegalStateException(detail, exception)
            }
            throw IllegalStateException("OAuth2 authorization cancelled or failed")
        }

        val authState = AuthState(response, exception)
        val authService = AuthorizationService(context)
        return try {
            suspendCancellableCoroutine { cont ->
                val tokenRequest = response.createTokenExchangeRequest()
                authService.performTokenRequest(tokenRequest) { tokenResponse, tokenEx ->
                    authState.update(tokenResponse, tokenEx)
                    if (tokenEx != null || tokenResponse == null) {
                        val detail = if (tokenEx != null) {
                            listOfNotNull(tokenEx.error, tokenEx.errorDescription)
                                .filter { it.isNotBlank() }
                                .joinToString(": ")
                                .ifBlank { tokenEx.message ?: "Token exchange failed" }
                        } else {
                            "Token exchange returned empty response"
                        }
                        cont.resumeWithException(IllegalStateException(detail, tokenEx))
                        return@performTokenRequest
                    }
                    val accessToken = authState.accessToken
                    if (accessToken.isNullOrBlank()) {
                        cont.resumeWithException(IllegalStateException("Access token missing in OAuth2 response"))
                        return@performTokenRequest
                    }
                    val claims = runCatching { authState.parsedIdToken?.additionalClaims }.getOrNull()
                    val idEmail = listOf("email", "preferred_username", "upn", "unique_name")
                        .firstNotNullOfOrNull { key ->
                            (claims?.get(key) as? String)?.trim()?.takeIf { it.isNotBlank() }
                        }
                    cont.resume(
                        OAuth2SessionResult(
                            emailHint = idEmail ?: fallbackEmail,
                            accessToken = accessToken,
                            updatedAuthStateJson = authState.jsonSerializeString(),
                        ),
                    )
                }
            }
        } finally {
            authService.dispose()
        }
    }

    /**
     * Returns a guaranteed fresh access token, refreshing with `refresh_token` if expired,
     * along with the potentially updated serialized `AuthState` JSON.
     */
    suspend fun ensureFreshAccessToken(
        authStateJson: String,
        fallbackEmail: String,
    ): OAuth2SessionResult {
        val authState = AuthState.jsonDeserialize(authStateJson)
        val authService = AuthorizationService(context)
        return try {
            suspendCancellableCoroutine { cont ->
                authState.performActionWithFreshTokens(authService) { accessToken, _, ex ->
                    if (ex != null || accessToken.isNullOrBlank()) {
                        val detail = if (ex != null) {
                            listOfNotNull(ex.error, ex.errorDescription)
                                .filter { it.isNotBlank() }
                                .joinToString(": ")
                                .ifBlank { ex.message ?: "Unable to refresh OAuth2 access token" }
                        } else {
                            "Unable to refresh OAuth2 access token"
                        }
                        cont.resumeWithException(IllegalStateException(detail, ex))
                    } else {
                        cont.resume(
                            OAuth2SessionResult(
                                emailHint = fallbackEmail,
                                accessToken = accessToken,
                                updatedAuthStateJson = authState.jsonSerializeString(),
                            ),
                        )
                    }
                }
            }
        } finally {
            authService.dispose()
        }
    }
}
