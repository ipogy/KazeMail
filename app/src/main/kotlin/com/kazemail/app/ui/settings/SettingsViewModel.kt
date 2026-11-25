package com.kazemail.app.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kazemail.app.DefaultMailAppHelper
import com.kazemail.app.DefaultMailAppStatus
import com.kazemail.app.data.AppThemeMode
import com.kazemail.app.data.db.AccountEntity
import com.kazemail.app.di.KazeMailContainer
import com.kazemail.app.notifications.MailNotificationManager
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.withContext
import java.io.File

data class AccountSettingsSummary(
    val account: AccountEntity,
    val messageCount: Int? = null,
)

data class SettingsUiState(
    val hasLoadedAccounts: Boolean = false,
    val accounts: List<AccountSettingsSummary> = emptyList(),
    val totalStoredMessages: Int? = null,
    val attachmentCacheBytes: Long? = null,
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val notificationsEnabled: Boolean = true,
    val hasNotificationPermission: Boolean = true,
    val periodicSyncIntervalMinutes: Long = 15L,
    val wifiOnlyBackfill: Boolean = false,
    val defaultThreadMode: Boolean = true,
    val previewLines: Int = 2,
    val itemsPerPage: Int = 100,
    val showAvatars: Boolean = true,
    val confirmArchive: Boolean = false,
    val autoShowHtml: Boolean = true,
    val defaultBlockExternalImages: Boolean = true,
    val collapseQuotesByDefault: Boolean = true,
    val signatureEnabled: Boolean = false,
    val signatureText: String = "",
    val syncingAccountId: Long? = null,
    val statusMessage: String? = null,
    val aiReadinessSummary: String = "確認中…",
    val aiReady: Boolean = false,
    val defaultMailAppStatus: DefaultMailAppStatus = DefaultMailAppStatus(),
)

private data class DisplayAndViewerPrefs(
    val themeMode: AppThemeMode,
    val defaultThreadMode: Boolean,
    val previewLines: Int,
    val itemsPerPage: Int,
    val showAvatars: Boolean,
    val confirmArchive: Boolean,
    val autoShowHtml: Boolean,
    val blockExternalImages: Boolean,
    val collapseQuotes: Boolean,
)

private data class SyncAndNotifPrefs(
    val notificationsEnabled: Boolean,
    val hasPermission: Boolean,
    val periodicSyncIntervalMinutes: Long,
    val wifiOnlyBackfill: Boolean,
    val signatureEnabled: Boolean,
    val signatureText: String,
)

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(
    private val container: KazeMailContainer,
    private val appContext: Context,
) : ViewModel() {

    private val attachmentsRootDir = File(appContext.filesDir, "attachments")
    private val cacheBytesFlow = MutableStateFlow<Long?>(null)
    private val syncingAccountId = MutableStateFlow<Long?>(null)
    private val statusMessage = MutableStateFlow<String?>(null)
    private val notifPermissionState = MutableStateFlow(checkNotificationPermission())
    private val defaultMailAppState = MutableStateFlow(DefaultMailAppHelper.inspectStatus(appContext))

    private val prefs = container.appPreferences
    private val databaseFlow = flow {
        emit(container.awaitDatabase())
    }.shareIn(viewModelScope, SharingStarted.Eagerly, replay = 1)

    private val displayAndViewerFlow = combine(
        combine(prefs.themeMode, prefs.defaultThreadMode, prefs.previewLines, prefs.showAvatars) {
                theme, thread, lines, avatars ->
            Quad(theme, thread, lines, avatars)
        },
        combine(
            prefs.confirmArchive,
            prefs.autoShowHtml,
            prefs.defaultBlockExternalImages,
            prefs.collapseQuotesByDefault,
        ) { confirm, autoHtml, blockImg, collapseQuotes ->
            Quad(confirm, autoHtml, blockImg, collapseQuotes)
        },
        prefs.itemsPerPage,
    ) { (theme, thread, lines, avatars), (confirm, autoHtml, blockImg, collapseQuotes), itemsPerPage ->
        DisplayAndViewerPrefs(
            themeMode = theme,
            defaultThreadMode = thread,
            previewLines = lines,
            itemsPerPage = itemsPerPage,
            showAvatars = avatars,
            confirmArchive = confirm,
            autoShowHtml = autoHtml,
            blockExternalImages = blockImg,
            collapseQuotes = collapseQuotes,
        )
    }

    private val syncAndNotifFlow = combine(
        combine(prefs.notificationsEnabled, notifPermissionState, prefs.periodicSyncIntervalMinutes) {
                notif, perm, interval ->
            Triple(notif, perm, interval)
        },
        combine(prefs.wifiOnlyBackfill, prefs.signatureEnabled, prefs.signatureText) {
                wifiOnly, sigEnabled, sigText ->
            Triple(wifiOnly, sigEnabled, sigText)
        },
    ) { (notif, perm, interval), (wifiOnly, sigEnabled, sigText) ->
        SyncAndNotifPrefs(
            notificationsEnabled = notif,
            hasPermission = perm,
            periodicSyncIntervalMinutes = interval,
            wifiOnlyBackfill = wifiOnly,
            signatureEnabled = sigEnabled,
            signatureText = sigText,
        )
    }

    private val accountsWithLoadedFlow = databaseFlow
        .flatMapLatest { db ->
            db.accountDao().observeAll().map { true to it }
        }
        .onStart { emit(false to emptyList()) }

    private val countMapFlow = databaseFlow
        .flatMapLatest { db ->
            db.messageMetaDao().observeMessageCountsByAccount().map<_, Map<Long, Int>?> { tuples ->
                tuples.associate { it.accountId to it.count }
            }
        }
        .onStart { emit(null) }

    private val totalMessagesFlow = databaseFlow
        .flatMapLatest { db ->
            db.messageMetaDao().observeTotalMessageCount().map<Int, Int?> { it }
        }
        .onStart { emit(null) }

    private val aiReadinessFlow = databaseFlow
        .flatMapLatest {
            container.aiPreparationService.observeReadinessState()
                .map { state -> state.summaryLabel to state.isReady }
        }
        .onStart {
            emit(initialAiReadinessLabel() to false)
        }

    private val repoOverviewFlow = combine(
        accountsWithLoadedFlow,
        countMapFlow,
        totalMessagesFlow,
        cacheBytesFlow,
        aiReadinessFlow,
    ) { (loaded, accounts), countMap, totalMessages, cacheBytes, aiReadiness ->
        RepoOverviewSnapshot(loaded, accounts, countMap, totalMessages, cacheBytes, aiReadiness.first, aiReadiness.second)
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        repoOverviewFlow,
        displayAndViewerFlow,
        syncAndNotifFlow,
        combine(syncingAccountId, statusMessage, defaultMailAppState, ::Triple),
    ) { overview, disp, sn, (activeSyncAcc, status, defaultMailApp) ->
        val summaries = overview.accounts.map { acc ->
            AccountSettingsSummary(
                account = acc,
                messageCount = overview.countMap?.let { map -> map[acc.accountId] ?: 0 },
            )
        }

        SettingsUiState(
            hasLoadedAccounts = overview.hasLoadedAccounts,
            accounts = summaries,
            totalStoredMessages = overview.totalMessages,
            attachmentCacheBytes = overview.cacheBytes,
            themeMode = disp.themeMode,
            notificationsEnabled = sn.notificationsEnabled,
            hasNotificationPermission = sn.hasPermission,
            periodicSyncIntervalMinutes = sn.periodicSyncIntervalMinutes,
            wifiOnlyBackfill = sn.wifiOnlyBackfill,
            defaultThreadMode = disp.defaultThreadMode,
            previewLines = disp.previewLines,
            itemsPerPage = disp.itemsPerPage,
            showAvatars = disp.showAvatars,
            confirmArchive = disp.confirmArchive,
            autoShowHtml = disp.autoShowHtml,
            defaultBlockExternalImages = disp.blockExternalImages,
            collapseQuotesByDefault = disp.collapseQuotes,
            signatureEnabled = sn.signatureEnabled,
            signatureText = sn.signatureText,
            syncingAccountId = activeSyncAcc,
            statusMessage = status,
            aiReadinessSummary = overview.aiSummary,
            aiReady = overview.aiReady,
            defaultMailAppStatus = defaultMailApp,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), buildInitialUiState())

    private data class RepoOverviewSnapshot(
        val hasLoadedAccounts: Boolean,
        val accounts: List<AccountEntity>,
        val countMap: Map<Long, Int>?,
        val totalMessages: Int?,
        val cacheBytes: Long?,
        val aiSummary: String,
        val aiReady: Boolean,
    )

    private fun buildInitialUiState(): SettingsUiState = SettingsUiState(
        themeMode = prefs.themeMode.value,
        notificationsEnabled = prefs.notificationsEnabled.value,
        hasNotificationPermission = notifPermissionState.value,
        periodicSyncIntervalMinutes = prefs.periodicSyncIntervalMinutes.value,
        wifiOnlyBackfill = prefs.wifiOnlyBackfill.value,
        defaultThreadMode = prefs.defaultThreadMode.value,
        previewLines = prefs.previewLines.value,
        itemsPerPage = prefs.itemsPerPage.value,
        showAvatars = prefs.showAvatars.value,
        confirmArchive = prefs.confirmArchive.value,
        autoShowHtml = prefs.autoShowHtml.value,
        defaultBlockExternalImages = prefs.defaultBlockExternalImages.value,
        collapseQuotesByDefault = prefs.collapseQuotesByDefault.value,
        signatureEnabled = prefs.signatureEnabled.value,
        signatureText = prefs.signatureText.value,
        aiReadinessSummary = initialAiReadinessLabel(),
        aiReady = false,
        defaultMailAppStatus = defaultMailAppState.value,
    )

    private fun initialAiReadinessLabel(): String =
        if (prefs.aiFeaturesEnabled.value) {
            if (prefs.aiPrivacyConsentAccepted.value) "AI設定を読み込み中…" else "外部送信ポリシーへの同意が必要です"
        } else {
            "AI機能はオフです（プロバイダ・Keystore設定可）"
        }

    init {
        refreshStorageMetrics()
    }

    fun refreshStorageMetrics() {
        viewModelScope.launch {
            cacheBytesFlow.value = withContext(Dispatchers.IO) {
                container.messageRepository.calculateAttachmentCacheBytes(attachmentsRootDir)
            }
            refreshOsIntegrationStatus()
        }
    }

    fun refreshOsIntegrationStatus() {
        notifPermissionState.value = checkNotificationPermission()
        defaultMailAppState.value = DefaultMailAppHelper.inspectStatus(appContext)
    }

    fun onRoleRequestFinished() {
        refreshOsIntegrationStatus()
        if (defaultMailAppState.value.isPrimaryDefault) {
            statusMessage.value = "KazeMail をデフォルトのメールアプリに設定しました"
        }
    }

    fun showStatusMessage(message: String) {
        statusMessage.value = message
    }

    fun onPermissionResult(granted: Boolean) {
        notifPermissionState.value = granted
        if (granted) {
            prefs.setNotificationsEnabled(true)
            container.notificationManager.ensureChannel()
            statusMessage.value = "新着メール通知を有効にしました"
        }
    }

    fun setThemeMode(mode: AppThemeMode) {
        prefs.setThemeMode(mode)
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        prefs.setNotificationsEnabled(enabled)
        if (enabled) {
            container.notificationManager.ensureChannel()
        }
    }

    fun setPeriodicSyncInterval(minutes: Long) {
        prefs.setPeriodicSyncIntervalMinutes(minutes)
        container.syncScheduler.updatePeriodicIncrementalSync(minutes)
        statusMessage.value = if (minutes <= 0L) {
            "バックグラウンド定期同期を手動のみに変更しました"
        } else {
            "定期バックグラウンド同期を ${minutes}分間隔 に設定しました"
        }
    }

    fun setWifiOnlyBackfill(wifiOnly: Boolean) {
        prefs.setWifiOnlyBackfill(wifiOnly)
    }

    fun setDefaultThreadMode(enabled: Boolean) {
        prefs.setDefaultThreadMode(enabled)
    }

    fun setPreviewLines(lines: Int) {
        prefs.setPreviewLines(lines)
    }

    fun setItemsPerPage(count: Int) {
        prefs.setItemsPerPage(count)
    }

    fun setShowAvatars(show: Boolean) {
        prefs.setShowAvatars(show)
    }

    fun setConfirmArchive(confirm: Boolean) {
        prefs.setConfirmArchive(confirm)
    }

    fun setAutoShowHtml(enabled: Boolean) {
        prefs.setAutoShowHtml(enabled)
    }

    fun setDefaultBlockExternalImages(block: Boolean) {
        prefs.setDefaultBlockExternalImages(block)
    }

    fun setCollapseQuotesByDefault(collapse: Boolean) {
        prefs.setCollapseQuotesByDefault(collapse)
    }

    fun saveSignature(enabled: Boolean, text: String) {
        prefs.setSignature(enabled, text)
        statusMessage.value = if (enabled) "共通既定のメール署名を保存しました" else "共通既定のメール署名を無効にしました"
    }

    fun saveAccountSignature(
        accountId: Long,
        emailAddress: String,
        enabled: Boolean,
        text: String,
    ) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    container.awaitDatabase().accountDao().updateSignature(accountId, enabled, text)
                }
            }.onSuccess {
                statusMessage.value = if (enabled) {
                    "$emailAddress の署名を保存しました"
                } else {
                    "$emailAddress の署名を無効にしました"
                }
            }.onFailure { err ->
                statusMessage.value = "署名の保存に失敗しました: ${err.message}"
            }
        }
    }

    fun syncAccountNow(accountId: Long) {
        if (syncingAccountId.value != null) return
        viewModelScope.launch {
            syncingAccountId.value = accountId
            runCatching {
                val delta = container.syncEngine.runIncrementalSync(accountId)
                statusMessage.value =
                    "同期完了: 新着 ${delta.newlySyncedCount} 件 / フラグ更新 ${delta.flagUpdatedCount} 件"
            }.onFailure { err ->
                statusMessage.value = "同期エラー: ${err.message}"
            }
            syncingAccountId.value = null
        }
    }

    fun deleteAccountLocally(accountId: Long, emailAddress: String) {
        viewModelScope.launch {
            runCatching {
                container.syncScheduler.cancelBackfill(accountId)
                container.messageRepository.deleteLocalAccount(accountId)
            }.onSuccess {
                statusMessage.value = "$emailAddress を端末内から削除しました（サーバー上のメールは保持されています）"
                refreshStorageMetrics()
            }.onFailure { err ->
                statusMessage.value = "アカウント削除エラー: ${err.message}"
            }
        }
    }

    fun clearAttachmentCache() {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    container.messageRepository.clearAttachmentCache(attachmentsRootDir)
                }
            }.onSuccess { clearedBytes ->
                cacheBytesFlow.value = 0L
                val mb = clearedBytes / (1024.0 * 1024.0)
                statusMessage.value = if (clearedBytes == 0L) {
                    "削除対象の添付キャッシュはありません"
                } else {
                    String.format("添付キャッシュを解放しました (%.1f MB)", mb)
                }
            }.onFailure { err ->
                statusMessage.value = "キャッシュ削除エラー: ${err.message}"
            }
        }
    }

    fun clearStatusMessage() {
        statusMessage.value = null
    }

    fun notificationChannelId(): String = MailNotificationManager.CHANNEL_ID

    private fun checkNotificationPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    companion object {
        fun factory(container: KazeMailContainer, context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SettingsViewModel(container, context.applicationContext) as T
            }
    }
}

private data class Quad<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
)
