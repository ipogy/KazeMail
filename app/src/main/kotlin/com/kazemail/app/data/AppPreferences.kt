package com.kazemail.app.data

import android.content.Context
import android.content.SharedPreferences
import com.kazemail.app.ai.AiReplyTone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppThemeMode(val storageValue: String, val displayLabel: String) {
    SYSTEM("SYSTEM", "端末の設定に合わせる"),
    LIGHT("LIGHT", "ライト"),
    DARK("DARK", "ダーク"),
    ;

    companion object {
        fun fromStorage(raw: String?): AppThemeMode =
            entries.firstOrNull { it.storageValue.equals(raw, ignoreCase = true) } ?: SYSTEM
    }
}

class AppPreferences(
    private val prefs: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    private val _themeMode = MutableStateFlow(
        AppThemeMode.fromStorage(prefs.getString(KEY_THEME_MODE, AppThemeMode.SYSTEM.storageValue)),
    )
    val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    private val _notificationsEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_NOTIFICATIONS_ENABLED, true),
    )
    val notificationsEnabled: StateFlow<Boolean> = _notificationsEnabled.asStateFlow()

    private val _periodicSyncIntervalMinutes = MutableStateFlow(
        prefs.getLong(KEY_PERIODIC_SYNC_MINUTES, DEFAULT_PERIODIC_SYNC_MINUTES),
    )
    val periodicSyncIntervalMinutes: StateFlow<Long> = _periodicSyncIntervalMinutes.asStateFlow()

    private val _wifiOnlyBackfill = MutableStateFlow(
        prefs.getBoolean(KEY_WIFI_ONLY_BACKFILL, false),
    )
    val wifiOnlyBackfill: StateFlow<Boolean> = _wifiOnlyBackfill.asStateFlow()

    private val _defaultThreadMode = MutableStateFlow(
        prefs.getBoolean(KEY_DEFAULT_THREAD_MODE, true),
    )
    val defaultThreadMode: StateFlow<Boolean> = _defaultThreadMode.asStateFlow()

    private val _previewLines = MutableStateFlow(
        prefs.getInt(KEY_PREVIEW_LINES, 2).coerceIn(1, 2),
    )
    val previewLines: StateFlow<Int> = _previewLines.asStateFlow()

    private val _itemsPerPage = MutableStateFlow(
        sanitizePageSize(prefs.getInt(KEY_ITEMS_PER_PAGE, DEFAULT_ITEMS_PER_PAGE)),
    )
    val itemsPerPage: StateFlow<Int> = _itemsPerPage.asStateFlow()

    private val _showAvatars = MutableStateFlow(
        prefs.getBoolean(KEY_SHOW_AVATARS, true),
    )
    val showAvatars: StateFlow<Boolean> = _showAvatars.asStateFlow()

    private val _confirmArchive = MutableStateFlow(
        prefs.getBoolean(KEY_CONFIRM_ARCHIVE, false),
    )
    val confirmArchive: StateFlow<Boolean> = _confirmArchive.asStateFlow()

    private val _defaultBlockExternalImages = MutableStateFlow(
        prefs.getBoolean(KEY_BLOCK_EXTERNAL_IMAGES, true),
    )
    val defaultBlockExternalImages: StateFlow<Boolean> = _defaultBlockExternalImages.asStateFlow()

    private val _autoShowHtml = MutableStateFlow(
        prefs.getBoolean(KEY_AUTO_SHOW_HTML, true),
    )
    val autoShowHtml: StateFlow<Boolean> = _autoShowHtml.asStateFlow()

    private val _collapseQuotesByDefault = MutableStateFlow(
        prefs.getBoolean(KEY_COLLAPSE_QUOTES, true),
    )
    val collapseQuotesByDefault: StateFlow<Boolean> = _collapseQuotesByDefault.asStateFlow()

    private val _signatureEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_SIGNATURE_ENABLED, false),
    )
    val signatureEnabled: StateFlow<Boolean> = _signatureEnabled.asStateFlow()

    private val _signatureText = MutableStateFlow(
        prefs.getString(KEY_SIGNATURE_TEXT, DEFAULT_SIGNATURE) ?: DEFAULT_SIGNATURE,
    )
    val signatureText: StateFlow<String> = _signatureText.asStateFlow()

    private val _headersHealedVersion = MutableStateFlow(
        prefs.getInt(KEY_HEADERS_HEALED_VERSION, 0),
    )
    val headersHealedVersion: StateFlow<Int> = _headersHealedVersion.asStateFlow()

    private val _aiFeaturesEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_AI_FEATURES_ENABLED, false),
    )
    val aiFeaturesEnabled: StateFlow<Boolean> = _aiFeaturesEnabled.asStateFlow()

    private val _aiPrivacyConsentAccepted = MutableStateFlow(
        prefs.getBoolean(KEY_AI_PRIVACY_CONSENT_ACCEPTED, false),
    )
    val aiPrivacyConsentAccepted: StateFlow<Boolean> = _aiPrivacyConsentAccepted.asStateFlow()

    private val _aiDefaultTone = MutableStateFlow(
        AiReplyTone.fromStorage(prefs.getString(KEY_AI_DEFAULT_TONE, AiReplyTone.FORMAL_BUSINESS.storageValue)),
    )
    val aiDefaultTone: StateFlow<AiReplyTone> = _aiDefaultTone.asStateFlow()

    private val _aiDefaultPreferHtml = MutableStateFlow(
        prefs.getBoolean(KEY_AI_DEFAULT_PREFER_HTML, false),
    )
    val aiDefaultPreferHtml: StateFlow<Boolean> = _aiDefaultPreferHtml.asStateFlow()

    private val _aiStripQuotedReplies = MutableStateFlow(
        prefs.getBoolean(KEY_AI_STRIP_QUOTED_REPLIES, true),
    )
    val aiStripQuotedReplies: StateFlow<Boolean> = _aiStripQuotedReplies.asStateFlow()

    private val _aiRedactSensitivePatterns = MutableStateFlow(
        prefs.getBoolean(KEY_AI_REDACT_SENSITIVE, true),
    )
    val aiRedactSensitivePatterns: StateFlow<Boolean> = _aiRedactSensitivePatterns.asStateFlow()

    private val _aiMaxContextChars = MutableStateFlow(
        sanitizeAiMaxContextChars(prefs.getInt(KEY_AI_MAX_CONTEXT_CHARS, DEFAULT_AI_MAX_CONTEXT_CHARS)),
    )
    val aiMaxContextChars: StateFlow<Int> = _aiMaxContextChars.asStateFlow()

    private val _aiIncludeSignatureGuidance = MutableStateFlow(
        prefs.getBoolean(KEY_AI_INCLUDE_SIGNATURE_GUIDANCE, true),
    )
    val aiIncludeSignatureGuidance: StateFlow<Boolean> = _aiIncludeSignatureGuidance.asStateFlow()

    private val _aiCustomSystemInstruction = MutableStateFlow(
        prefs.getString(KEY_AI_CUSTOM_SYSTEM_INSTRUCTION, "") ?: "",
    )
    val aiCustomSystemInstruction: StateFlow<String> = _aiCustomSystemInstruction.asStateFlow()

    private val _aiSubjectProfileId = MutableStateFlow(
        prefs.getLong(KEY_AI_SUBJECT_PROFILE_ID, 0L).takeIf { it > 0L },
    )
    val aiSubjectProfileId: StateFlow<Long?> = _aiSubjectProfileId.asStateFlow()

    private val _aiSubjectModelOverride = MutableStateFlow(
        prefs.getString(KEY_AI_SUBJECT_MODEL_OVERRIDE, "") ?: "",
    )
    val aiSubjectModelOverride: StateFlow<String> = _aiSubjectModelOverride.asStateFlow()

    private val _aiSubjectCustomInstruction = MutableStateFlow(
        prefs.getString(KEY_AI_SUBJECT_CUSTOM_INSTRUCTION, "") ?: "",
    )
    val aiSubjectCustomInstruction: StateFlow<String> = _aiSubjectCustomInstruction.asStateFlow()

    fun setThemeMode(mode: AppThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.storageValue).apply()
        _themeMode.value = mode
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFICATIONS_ENABLED, enabled).apply()
        _notificationsEnabled.value = enabled
    }

    fun setPeriodicSyncIntervalMinutes(minutes: Long) {
        val sanitized = when (minutes) {
            0L, 15L, 30L, 60L -> minutes
            else -> DEFAULT_PERIODIC_SYNC_MINUTES
        }
        prefs.edit().putLong(KEY_PERIODIC_SYNC_MINUTES, sanitized).apply()
        _periodicSyncIntervalMinutes.value = sanitized
    }

    fun setWifiOnlyBackfill(wifiOnly: Boolean) {
        prefs.edit().putBoolean(KEY_WIFI_ONLY_BACKFILL, wifiOnly).apply()
        _wifiOnlyBackfill.value = wifiOnly
    }

    fun setDefaultThreadMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DEFAULT_THREAD_MODE, enabled).apply()
        _defaultThreadMode.value = enabled
    }

    fun setPreviewLines(lines: Int) {
        val clamped = lines.coerceIn(1, 2)
        prefs.edit().putInt(KEY_PREVIEW_LINES, clamped).apply()
        _previewLines.value = clamped
    }

    fun setItemsPerPage(count: Int) {
        val sanitized = sanitizePageSize(count)
        prefs.edit().putInt(KEY_ITEMS_PER_PAGE, sanitized).apply()
        _itemsPerPage.value = sanitized
    }

    private fun sanitizePageSize(count: Int): Int =
        when (count) {
            50, 100, 200 -> count
            else -> DEFAULT_ITEMS_PER_PAGE
        }

    fun setShowAvatars(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_AVATARS, show).apply()
        _showAvatars.value = show
    }

    fun setConfirmArchive(confirm: Boolean) {
        prefs.edit().putBoolean(KEY_CONFIRM_ARCHIVE, confirm).apply()
        _confirmArchive.value = confirm
    }

    fun setDefaultBlockExternalImages(block: Boolean) {
        prefs.edit().putBoolean(KEY_BLOCK_EXTERNAL_IMAGES, block).apply()
        _defaultBlockExternalImages.value = block
    }

    fun setAutoShowHtml(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SHOW_HTML, enabled).apply()
        _autoShowHtml.value = enabled
    }

    fun setCollapseQuotesByDefault(collapse: Boolean) {
        prefs.edit().putBoolean(KEY_COLLAPSE_QUOTES, collapse).apply()
        _collapseQuotesByDefault.value = collapse
    }

    fun setSignature(enabled: Boolean, text: String) {
        val trimmed = text.trimEnd()
        prefs.edit()
            .putBoolean(KEY_SIGNATURE_ENABLED, enabled)
            .putString(KEY_SIGNATURE_TEXT, trimmed)
            .apply()
        _signatureEnabled.value = enabled
        _signatureText.value = trimmed
    }

    fun setHeadersHealedVersion(version: Int) {
        prefs.edit().putInt(KEY_HEADERS_HEALED_VERSION, version).apply()
        _headersHealedVersion.value = version
    }

    fun setAiFeaturesEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AI_FEATURES_ENABLED, enabled).apply()
        _aiFeaturesEnabled.value = enabled
    }

    fun setAiPrivacyConsentAccepted(accepted: Boolean) {
        prefs.edit().putBoolean(KEY_AI_PRIVACY_CONSENT_ACCEPTED, accepted).apply()
        _aiPrivacyConsentAccepted.value = accepted
    }

    fun setAiDefaultTone(tone: AiReplyTone) {
        prefs.edit().putString(KEY_AI_DEFAULT_TONE, tone.storageValue).apply()
        _aiDefaultTone.value = tone
    }

    fun setAiDefaultPreferHtml(preferHtml: Boolean) {
        prefs.edit().putBoolean(KEY_AI_DEFAULT_PREFER_HTML, preferHtml).apply()
        _aiDefaultPreferHtml.value = preferHtml
    }

    fun setAiStripQuotedReplies(strip: Boolean) {
        prefs.edit().putBoolean(KEY_AI_STRIP_QUOTED_REPLIES, strip).apply()
        _aiStripQuotedReplies.value = strip
    }

    fun setAiRedactSensitivePatterns(redact: Boolean) {
        prefs.edit().putBoolean(KEY_AI_REDACT_SENSITIVE, redact).apply()
        _aiRedactSensitivePatterns.value = redact
    }

    fun setAiMaxContextChars(chars: Int) {
        val sanitized = sanitizeAiMaxContextChars(chars)
        prefs.edit().putInt(KEY_AI_MAX_CONTEXT_CHARS, sanitized).apply()
        _aiMaxContextChars.value = sanitized
    }

    fun setAiIncludeSignatureGuidance(include: Boolean) {
        prefs.edit().putBoolean(KEY_AI_INCLUDE_SIGNATURE_GUIDANCE, include).apply()
        _aiIncludeSignatureGuidance.value = include
    }

    fun setAiCustomSystemInstruction(instruction: String) {
        val trimmed = instruction.trim()
        prefs.edit().putString(KEY_AI_CUSTOM_SYSTEM_INSTRUCTION, trimmed).apply()
        _aiCustomSystemInstruction.value = trimmed
    }

    fun setAiSubjectProfileId(profileId: Long?) {
        val normalized = profileId?.takeIf { it > 0L }
        prefs.edit().putLong(KEY_AI_SUBJECT_PROFILE_ID, normalized ?: 0L).apply()
        _aiSubjectProfileId.value = normalized
    }

    fun setAiSubjectModelOverride(modelId: String) {
        val trimmed = modelId.trim()
        prefs.edit().putString(KEY_AI_SUBJECT_MODEL_OVERRIDE, trimmed).apply()
        _aiSubjectModelOverride.value = trimmed
    }

    fun setAiSubjectCustomInstruction(instruction: String) {
        val trimmed = instruction.trim()
        prefs.edit().putString(KEY_AI_SUBJECT_CUSTOM_INSTRUCTION, trimmed).apply()
        _aiSubjectCustomInstruction.value = trimmed
    }

    private fun sanitizeAiMaxContextChars(chars: Int): Int =
        when (chars) {
            3000, 6000, 12000 -> chars
            else -> DEFAULT_AI_MAX_CONTEXT_CHARS
        }

    companion object {
        private const val PREFS_NAME = "kazemail_app_prefs"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
        private const val KEY_PERIODIC_SYNC_MINUTES = "periodic_sync_minutes"
        private const val KEY_WIFI_ONLY_BACKFILL = "wifi_only_backfill"
        private const val KEY_DEFAULT_THREAD_MODE = "default_thread_mode"
        private const val KEY_PREVIEW_LINES = "preview_lines"
        private const val KEY_ITEMS_PER_PAGE = "items_per_page"
        private const val KEY_SHOW_AVATARS = "show_avatars"
        private const val KEY_CONFIRM_ARCHIVE = "confirm_archive"
        private const val KEY_BLOCK_EXTERNAL_IMAGES = "block_external_images"
        private const val KEY_AUTO_SHOW_HTML = "auto_show_html"
        private const val KEY_COLLAPSE_QUOTES = "collapse_quotes_by_default"
        private const val KEY_SIGNATURE_ENABLED = "signature_enabled"
        private const val KEY_SIGNATURE_TEXT = "signature_text"
        private const val KEY_HEADERS_HEALED_VERSION = "headers_healed_version"
        private const val KEY_AI_FEATURES_ENABLED = "ai_features_enabled"
        private const val KEY_AI_PRIVACY_CONSENT_ACCEPTED = "ai_privacy_consent_accepted"
        private const val KEY_AI_DEFAULT_TONE = "ai_default_tone"
        private const val KEY_AI_DEFAULT_PREFER_HTML = "ai_default_prefer_html"
        private const val KEY_AI_STRIP_QUOTED_REPLIES = "ai_strip_quoted_replies"
        private const val KEY_AI_REDACT_SENSITIVE = "ai_redact_sensitive_patterns"
        private const val KEY_AI_MAX_CONTEXT_CHARS = "ai_max_context_chars"
        private const val KEY_AI_INCLUDE_SIGNATURE_GUIDANCE = "ai_include_signature_guidance"
        private const val KEY_AI_CUSTOM_SYSTEM_INSTRUCTION = "ai_custom_system_instruction"
        private const val KEY_AI_SUBJECT_PROFILE_ID = "ai_subject_profile_id"
        private const val KEY_AI_SUBJECT_MODEL_OVERRIDE = "ai_subject_model_override"
        private const val KEY_AI_SUBJECT_CUSTOM_INSTRUCTION = "ai_subject_custom_instruction"
        const val DEFAULT_PERIODIC_SYNC_MINUTES = 15L
        const val DEFAULT_ITEMS_PER_PAGE = 100
        const val DEFAULT_AI_MAX_CONTEXT_CHARS = 6000
        const val DEFAULT_SIGNATURE = "Sent from KazeMail"
    }
}
