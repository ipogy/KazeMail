package com.kazemail.app.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kazemail.app.ai.AiConnectionTestResult
import com.kazemail.app.ai.AiProviderProfileSummary
import com.kazemail.app.ai.AiProviderType
import com.kazemail.app.ai.AiReadinessState
import com.kazemail.app.ai.AiReadinessStatus
import com.kazemail.app.ai.AiReplyTone
import com.kazemail.app.ai.AiWritingGoal
import com.kazemail.app.data.db.AiWritingPresetEntity
import com.kazemail.app.di.KazeMailContainer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AiSettingsUiState(
    val hasLoaded: Boolean = false,
    val featuresEnabled: Boolean = false,
    val privacyConsentAccepted: Boolean = false,
    val readiness: AiReadinessState = AiReadinessState(
        status = AiReadinessStatus.FEATURE_DISABLED,
        summaryLabel = "確認中…",
        actionHint = "",
    ),
    val profiles: List<AiProviderProfileSummary> = emptyList(),
    val presets: List<AiWritingPresetEntity> = emptyList(),
    val defaultTone: AiReplyTone = AiReplyTone.FORMAL_BUSINESS,
    val defaultPreferHtml: Boolean = false,
    val stripQuotedReplies: Boolean = true,
    val redactSensitivePatterns: Boolean = true,
    val maxContextChars: Int = 6000,
    val includeSignatureGuidance: Boolean = true,
    val customSystemInstruction: String = "",
    val subjectProfileId: Long? = null,
    val subjectModelOverride: String = "",
    val subjectCustomInstruction: String = "",
    val testingSubjectConfig: Boolean = false,
    val testingProfileId: Long? = null,
    val draftTestInProgress: Boolean = false,
    val draftTestResult: AiConnectionTestResult? = null,
    val statusMessage: String? = null,
)

private data class AiToggleAndPrivacySnapshot(
    val featuresEnabled: Boolean,
    val privacyConsentAccepted: Boolean,
    val defaultTone: AiReplyTone,
    val defaultPreferHtml: Boolean,
    val stripQuotedReplies: Boolean,
    val redactSensitivePatterns: Boolean,
    val maxContextChars: Int,
    val includeSignatureGuidance: Boolean,
    val customSystemInstruction: String,
    val subjectProfileId: Long?,
    val subjectModelOverride: String,
    val subjectCustomInstruction: String,
)

private data class AiTransientUiSnapshot(
    val testingProfileId: Long?,
    val testingSubjectConfig: Boolean,
    val draftTestInProgress: Boolean,
    val draftTestResult: AiConnectionTestResult?,
    val statusMessage: String?,
)

@OptIn(ExperimentalCoroutinesApi::class)
class AiSettingsViewModel(
    private val container: KazeMailContainer,
) : ViewModel() {

    private val prefs = container.appPreferences
    private val testingProfileId = MutableStateFlow<Long?>(null)
    private val testingSubjectConfig = MutableStateFlow(false)
    private val draftTestInProgress = MutableStateFlow(false)
    private val draftTestResult = MutableStateFlow<AiConnectionTestResult?>(null)
    private val statusMessage = MutableStateFlow<String?>(null)

    private val serviceFlow = flow {
        container.awaitDatabase()
        val service = container.aiPreparationService
        runCatching { service.ensureDefaultPresetsSeeded() }
        emit(service)
    }.shareIn(viewModelScope, SharingStarted.Eagerly, replay = 1)

    private val prefsFlow = combine(
        combine(
            prefs.aiFeaturesEnabled,
            prefs.aiPrivacyConsentAccepted,
            prefs.aiDefaultTone,
            prefs.aiDefaultPreferHtml,
            prefs.aiStripQuotedReplies,
        ) { enabled, consent, tone, defaultPreferHtml, strip ->
            listOf(enabled, consent, tone, defaultPreferHtml, strip)
        },
        combine(
            prefs.aiRedactSensitivePatterns,
            prefs.aiMaxContextChars,
            prefs.aiIncludeSignatureGuidance,
            prefs.aiCustomSystemInstruction,
        ) { redact, maxChars, includeSig, customInst ->
            listOf(redact, maxChars, includeSig, customInst)
        },
        combine(
            prefs.aiSubjectProfileId,
            prefs.aiSubjectModelOverride,
            prefs.aiSubjectCustomInstruction,
            ::Triple,
        ),
    ) { first, second, (subProfileId, subModel, subInst) ->
        AiToggleAndPrivacySnapshot(
            featuresEnabled = first[0] as Boolean,
            privacyConsentAccepted = first[1] as Boolean,
            defaultTone = first[2] as AiReplyTone,
            defaultPreferHtml = first[3] as Boolean,
            stripQuotedReplies = first[4] as Boolean,
            redactSensitivePatterns = second[0] as Boolean,
            maxContextChars = second[1] as Int,
            includeSignatureGuidance = second[2] as Boolean,
            customSystemInstruction = second[3] as String,
            subjectProfileId = subProfileId,
            subjectModelOverride = subModel,
            subjectCustomInstruction = subInst,
        )
    }

    private val profilesWithLoadedFlow = serviceFlow
        .flatMapLatest { svc ->
            svc.observeProfiles().map { true to it }
        }
        .onStart { emit(false to emptyList()) }

    private val presetsFlow = serviceFlow
        .flatMapLatest { svc -> svc.observePresets() }
        .onStart { emit(emptyList()) }

    private val readinessFlow = serviceFlow
        .flatMapLatest { svc -> svc.observeReadinessState() }
        .onStart { emit(initialReadiness()) }

    private val transientFlow = combine(
        testingProfileId,
        testingSubjectConfig,
        draftTestInProgress,
        draftTestResult,
        statusMessage,
    ) { testingId, testingSub, testingDraft, draftResult, msg ->
        AiTransientUiSnapshot(
            testingProfileId = testingId,
            testingSubjectConfig = testingSub,
            draftTestInProgress = testingDraft,
            draftTestResult = draftResult,
            statusMessage = msg,
        )
    }

    val uiState: StateFlow<AiSettingsUiState> = combine(
        profilesWithLoadedFlow,
        presetsFlow,
        readinessFlow,
        prefsFlow,
        transientFlow,
    ) { (loaded, profiles), presets, readiness, prefSnap, transient ->
        AiSettingsUiState(
            hasLoaded = loaded,
            featuresEnabled = prefSnap.featuresEnabled,
            privacyConsentAccepted = prefSnap.privacyConsentAccepted,
            readiness = readiness,
            profiles = profiles,
            presets = presets,
            defaultTone = prefSnap.defaultTone,
            defaultPreferHtml = prefSnap.defaultPreferHtml,
            stripQuotedReplies = prefSnap.stripQuotedReplies,
            redactSensitivePatterns = prefSnap.redactSensitivePatterns,
            maxContextChars = prefSnap.maxContextChars,
            includeSignatureGuidance = prefSnap.includeSignatureGuidance,
            customSystemInstruction = prefSnap.customSystemInstruction,
            subjectProfileId = prefSnap.subjectProfileId,
            subjectModelOverride = prefSnap.subjectModelOverride,
            subjectCustomInstruction = prefSnap.subjectCustomInstruction,
            testingSubjectConfig = transient.testingSubjectConfig,
            testingProfileId = transient.testingProfileId,
            draftTestInProgress = transient.draftTestInProgress,
            draftTestResult = transient.draftTestResult,
            statusMessage = transient.statusMessage,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), buildInitialUiState())

    private fun initialReadiness(): AiReadinessState =
        AiReadinessState(
            status = if (prefs.aiFeaturesEnabled.value) {
                if (prefs.aiPrivacyConsentAccepted.value) AiReadinessStatus.NO_PROFILE else AiReadinessStatus.CONSENT_REQUIRED
            } else {
                AiReadinessStatus.FEATURE_DISABLED
            },
            summaryLabel = if (prefs.aiFeaturesEnabled.value) "読み込み中…" else "AI機能はオフです",
            actionHint = "",
        )

    private fun buildInitialUiState(): AiSettingsUiState = AiSettingsUiState(
        hasLoaded = false,
        featuresEnabled = prefs.aiFeaturesEnabled.value,
        privacyConsentAccepted = prefs.aiPrivacyConsentAccepted.value,
        readiness = initialReadiness(),
        defaultTone = prefs.aiDefaultTone.value,
        defaultPreferHtml = prefs.aiDefaultPreferHtml.value,
        stripQuotedReplies = prefs.aiStripQuotedReplies.value,
        redactSensitivePatterns = prefs.aiRedactSensitivePatterns.value,
        maxContextChars = prefs.aiMaxContextChars.value,
        includeSignatureGuidance = prefs.aiIncludeSignatureGuidance.value,
        customSystemInstruction = prefs.aiCustomSystemInstruction.value,
        subjectProfileId = prefs.aiSubjectProfileId.value,
        subjectModelOverride = prefs.aiSubjectModelOverride.value,
        subjectCustomInstruction = prefs.aiSubjectCustomInstruction.value,
    )

    /**
     * Returns true if applied immediately, or false when privacy consent dialog must be shown first.
     */
    fun onToggleFeaturesRequested(enabled: Boolean): Boolean {
        if (enabled && !prefs.aiPrivacyConsentAccepted.value) {
            return false
        }
        prefs.setAiFeaturesEnabled(enabled)
        statusMessage.value = if (enabled) "AI機能を有効にしました" else "AI機能をオフにしました"
        return true
    }

    fun acceptPrivacyConsentAndEnable() {
        prefs.setAiPrivacyConsentAccepted(true)
        prefs.setAiFeaturesEnabled(true)
        statusMessage.value = "外部送信ポリシーに同意し、AI機能を有効にしました"
    }

    fun revokePrivacyConsent() {
        prefs.setAiFeaturesEnabled(false)
        prefs.setAiPrivacyConsentAccepted(false)
        statusMessage.value = "AI機能を無効化し、同意を取り消しました"
    }

    fun setDefaultTone(tone: AiReplyTone) {
        prefs.setAiDefaultTone(tone)
    }

    fun setDefaultPreferHtml(preferHtml: Boolean) {
        prefs.setAiDefaultPreferHtml(preferHtml)
    }

    fun setStripQuotedReplies(strip: Boolean) {
        prefs.setAiStripQuotedReplies(strip)
    }

    fun setRedactSensitivePatterns(redact: Boolean) {
        prefs.setAiRedactSensitivePatterns(redact)
    }

    fun setMaxContextChars(chars: Int) {
        prefs.setAiMaxContextChars(chars)
    }

    fun setIncludeSignatureGuidance(include: Boolean) {
        prefs.setAiIncludeSignatureGuidance(include)
    }

    fun saveCustomSystemInstruction(instruction: String) {
        prefs.setAiCustomSystemInstruction(instruction)
        statusMessage.value = "執筆方針（補足プロンプト）を保存しました"
    }

    fun setSubjectProfileId(profileId: Long?) {
        prefs.setAiSubjectProfileId(profileId)
        statusMessage.value = if (profileId == null) {
            "件名作成AIを「標準プロファイルと同じ」に設定しました"
        } else {
            "件名作成用のAIプロファイルを切り替えました"
        }
    }

    fun saveSubjectModelOverride(modelId: String) {
        prefs.setAiSubjectModelOverride(modelId)
        statusMessage.value = if (modelId.isBlank()) {
            "件名作成モデルをプロファイル既定に戻しました"
        } else {
            "件名作成モデルを「${modelId.trim()}」に設定しました"
        }
    }

    fun saveSubjectCustomInstruction(instruction: String) {
        prefs.setAiSubjectCustomInstruction(instruction)
        statusMessage.value = "件名作成専用の追加プロンプトを保存しました"
    }

    fun testSubjectConfigurationConnection() {
        if (testingSubjectConfig.value) return
        viewModelScope.launch {
            testingSubjectConfig.value = true
            runCatching {
                container.awaitDatabase()
                container.aiPreparationService.testSubjectConfigurationConnection()
            }.onSuccess { res ->
                statusMessage.value = "件名作成AIテスト: ${res.message}"
            }.onFailure { err ->
                statusMessage.value = "件名作成AIテストエラー: ${err.message}"
            }
            testingSubjectConfig.value = false
        }
    }

    fun saveProfile(
        profileId: Long?,
        displayName: String,
        providerType: AiProviderType,
        baseUrl: String,
        modelId: String,
        rawApiKeyInput: String?,
        setAsDefault: Boolean,
        onSaved: () -> Unit,
    ) {
        viewModelScope.launch {
            runCatching {
                container.awaitDatabase()
                container.aiPreparationService.saveProfile(
                    profileId = profileId,
                    displayName = displayName,
                    providerType = providerType,
                    baseUrl = baseUrl,
                    modelId = modelId,
                    rawApiKeyInput = rawApiKeyInput,
                    keepExistingKeyIfBlank = profileId != null,
                    setAsDefault = setAsDefault,
                )
            }.onSuccess {
                draftTestResult.value = null
                statusMessage.value = "AIプロファイルを Android Keystore で暗号化保存しました"
                onSaved()
            }.onFailure { err ->
                statusMessage.value = err.message ?: "プロファイルの保存に失敗しました"
            }
        }
    }

    fun setDefaultProfile(profileId: Long) {
        viewModelScope.launch {
            runCatching {
                container.awaitDatabase()
                container.aiPreparationService.setDefaultProfile(profileId)
            }.onSuccess {
                statusMessage.value = "標準で使用する AI プロファイルを変更しました"
            }.onFailure { err ->
                statusMessage.value = "切り替えエラー: ${err.message}"
            }
        }
    }

    fun deleteProfile(profileId: Long) {
        viewModelScope.launch {
            runCatching {
                container.awaitDatabase()
                container.aiPreparationService.deleteProfile(profileId)
            }.onSuccess {
                statusMessage.value = "AIプロファイルを削除しました"
            }.onFailure { err ->
                statusMessage.value = "削除エラー: ${err.message}"
            }
        }
    }

    fun testSavedProfileConnection(profileId: Long) {
        if (testingProfileId.value != null) return
        viewModelScope.launch {
            testingProfileId.value = profileId
            runCatching {
                container.awaitDatabase()
                container.aiPreparationService.testSavedProfileConnection(profileId)
            }.onSuccess { res ->
                statusMessage.value = res.message
            }.onFailure { err ->
                statusMessage.value = "接続テストエラー: ${err.message}"
            }
            testingProfileId.value = null
        }
    }

    fun testDraftConnection(
        providerType: AiProviderType,
        baseUrl: String,
        modelId: String,
        rawApiKeyInput: String?,
        existingProfileId: Long?,
    ) {
        if (draftTestInProgress.value) return
        viewModelScope.launch {
            draftTestInProgress.value = true
            draftTestResult.value = null
            val res = runCatching {
                container.awaitDatabase()
                container.aiPreparationService.testDraftConnection(
                    providerType = providerType,
                    baseUrl = baseUrl,
                    modelId = modelId,
                    rawApiKeyInput = rawApiKeyInput,
                    existingProfileId = existingProfileId,
                )
            }.getOrElse { err ->
                AiConnectionTestResult(
                    success = false,
                    providerType = providerType,
                    modelId = modelId,
                    latencyMs = 0L,
                    message = err.message ?: "接続テストに失敗しました",
                )
            }
            draftTestResult.value = res
            draftTestInProgress.value = false
        }
    }

    fun clearDraftTestResult() {
        draftTestResult.value = null
    }

    fun savePreset(
        presetId: Long?,
        title: String,
        goal: AiWritingGoal,
        tone: AiReplyTone,
        instructionPrompt: String,
        onSaved: () -> Unit,
    ) {
        viewModelScope.launch {
            runCatching {
                container.awaitDatabase()
                container.aiPreparationService.savePreset(
                    presetId = presetId,
                    title = title,
                    goal = goal,
                    tone = tone,
                    instructionPrompt = instructionPrompt,
                )
            }.onSuccess {
                statusMessage.value = "テンプレートを保存しました"
                onSaved()
            }.onFailure { err ->
                statusMessage.value = err.message ?: "テンプレート保存に失敗しました"
            }
        }
    }

    fun deletePreset(presetId: Long) {
        viewModelScope.launch {
            runCatching {
                container.awaitDatabase()
                container.aiPreparationService.deletePreset(presetId)
            }.onSuccess {
                statusMessage.value = "テンプレートを削除しました"
            }.onFailure { err ->
                statusMessage.value = "削除エラー: ${err.message}"
            }
        }
    }

    fun clearStatusMessage() {
        statusMessage.value = null
    }

    companion object {
        fun factory(container: KazeMailContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AiSettingsViewModel(container) as T
            }
    }
}
