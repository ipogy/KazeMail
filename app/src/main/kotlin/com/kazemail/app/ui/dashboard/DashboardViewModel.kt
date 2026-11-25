package com.kazemail.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kazemail.app.data.db.AccountEntity
import com.kazemail.app.data.db.FolderEntity
import com.kazemail.app.data.db.FolderSyncStateEntity
import com.kazemail.app.data.db.MessageMetaEntity
import com.kazemail.app.di.KazeMailContainer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FolderWithSyncStatus(
    val folder: FolderEntity,
    val syncState: FolderSyncStateEntity?,
)

data class DashboardUiState(
    val accounts: List<AccountEntity> = emptyList(),
    val selectedAccountId: Long? = null,
    val folders: List<FolderWithSyncStatus> = emptyList(),
    val selectedFolderId: Long? = null,
    val recentMessages: List<MessageMetaEntity> = emptyList(),
    val totalStoredMessages: Int = 0,
    val searchQuery: String = "",
    val searchResults: List<MessageMetaEntity> = emptyList(),
    val isSyncRunning: Boolean = false,
    val statusBanner: String? = null,
    val notificationsEnabled: Boolean = true,
    val periodicSyncIntervalMinutes: Long = 15L,
    val outboxPendingCount: Int = 0,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class DashboardViewModel(
    private val container: KazeMailContainer,
) : ViewModel() {
    private val databaseFlow = flow {
        emit(container.awaitDatabase())
    }.shareIn(viewModelScope, SharingStarted.Eagerly, replay = 1)

    private val selectedAccountId = MutableStateFlow<Long?>(null)
    private val selectedFolderId = MutableStateFlow<Long?>(null)
    private val searchQuery = MutableStateFlow("")
    private val isSyncRunning = MutableStateFlow(false)
    private val statusBanner = MutableStateFlow<String?>(null)

    private val accountsFlow = databaseFlow
        .flatMapLatest { it.accountDao().observeAll() }
        .onStart { emit(emptyList()) }
    private val foldersFlow = databaseFlow
        .flatMapLatest { it.folderDao().observeAll() }
        .onStart { emit(emptyList()) }
    private val syncStatesFlow = databaseFlow
        .flatMapLatest { it.folderSyncStateDao().observeAll() }
        .onStart { emit(emptyList()) }
    private val totalCountFlow = databaseFlow
        .flatMapLatest { it.messageMetaDao().observeTotalMessageCount() }
        .onStart { emit(0) }

    private val activeMessagesFlow = combine(
        accountsFlow,
        foldersFlow,
        selectedAccountId,
        selectedFolderId,
    ) { accounts, folders, accId, fldId ->
        val activeAcc = accId ?: accounts.firstOrNull()?.accountId
        val accFolders = folders.filter {
            it.accountId == activeAcc && !it.fullPath.startsWith("__archive__/")
        }
        val activeFld = fldId?.takeIf { id -> accFolders.any { it.folderId == id } }
            ?: accFolders.firstOrNull { it.fullPath.equals("INBOX", ignoreCase = true) }?.folderId
            ?: accFolders.firstOrNull()?.folderId
        activeFld
    }.flatMapLatest { folderId ->
        if (folderId == null) flowOf(emptyList())
        else databaseFlow.flatMapLatest { it.messageMetaDao().observeRecentByFolder(folderId, limit = 100) }
    }

    private val searchResultsFlow = searchQuery
        .debounce(150L)
        .flatMapLatest { query ->
            if (query.isBlank()) {
                flowOf(emptyList())
            } else {
                flow {
                    val hits = runCatching {
                        container.syncEngine.searchLocal(query, accountId = selectedAccountId.value)
                    }.getOrDefault(emptyList())
                    emit(hits)
                }
            }
        }

    private val settingsFlow = combine(
        container.appPreferences.notificationsEnabled,
        container.appPreferences.periodicSyncIntervalMinutes,
        accountsFlow,
    ) { notif, interval, accounts ->
        val accId = selectedAccountId.value ?: accounts.firstOrNull()?.accountId
        Triple(notif, interval, accId)
    }.flatMapLatest { (notif, interval, accId) ->
        if (accId == null) {
            flowOf(Triple(notif, interval, 0))
        } else {
            databaseFlow
                .flatMapLatest { it.outboxDao().observePendingCountByAccount(accId) }
                .combine(flowOf(notif to interval)) { count, pair ->
                    Triple(pair.first, pair.second, count)
                }
                .onStart { emit(Triple(notif, interval, 0)) }
        }
    }

    val uiState: StateFlow<DashboardUiState> = combine(
        accountsFlow,
        foldersFlow,
        syncStatesFlow,
        totalCountFlow,
        activeMessagesFlow,
        searchQuery,
        searchResultsFlow,
        isSyncRunning,
        statusBanner,
        settingsFlow,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val accounts = values[0] as List<AccountEntity>
        @Suppress("UNCHECKED_CAST")
        val allFolders = values[1] as List<FolderEntity>
        @Suppress("UNCHECKED_CAST")
        val syncStates = values[2] as List<FolderSyncStateEntity>
        val totalCount = values[3] as Int
        @Suppress("UNCHECKED_CAST")
        val messages = values[4] as List<MessageMetaEntity>
        val query = values[5] as String
        @Suppress("UNCHECKED_CAST")
        val searchHits = values[6] as List<MessageMetaEntity>
        val syncing = values[7] as Boolean
        val banner = values[8] as String?
        @Suppress("UNCHECKED_CAST")
        val settingsTriple = values[9] as Triple<Boolean, Long, Int>

        val activeAccountId = selectedAccountId.value ?: accounts.firstOrNull()?.accountId
        val stateMap = syncStates.associateBy { it.folderId }
        val accountFolders = allFolders
            .filter { it.accountId == activeAccountId }
            .map { FolderWithSyncStatus(it, stateMap[it.folderId]) }
        val activeFolderId = selectedFolderId.value
            ?.takeIf { id -> accountFolders.any { it.folder.folderId == id } }
            ?: accountFolders.firstOrNull { it.folder.fullPath.equals("INBOX", ignoreCase = true) }?.folder?.folderId
            ?: accountFolders.firstOrNull()?.folder?.folderId

        DashboardUiState(
            accounts = accounts,
            selectedAccountId = activeAccountId,
            folders = accountFolders,
            selectedFolderId = activeFolderId,
            recentMessages = messages,
            totalStoredMessages = totalCount,
            searchQuery = query,
            searchResults = searchHits,
            isSyncRunning = syncing,
            statusBanner = banner,
            notificationsEnabled = settingsTriple.first,
            periodicSyncIntervalMinutes = settingsTriple.second,
            outboxPendingCount = settingsTriple.third,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardUiState())

    fun selectAccount(accountId: Long) {
        selectedAccountId.value = accountId
        selectedFolderId.value = null
    }

    fun selectFolder(folderId: Long) {
        selectedFolderId.value = folderId
    }

    fun updateSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun triggerPhase1AndBackgroundBackfill(accountId: Long) {
        if (isSyncRunning.value) return
        viewModelScope.launch {
            isSyncRunning.value = true
            statusBanner.value = "フェーズ1: 受信トレイ最新100件を非破壊同期中…"
            runCatching {
                val p1 = container.syncEngine.runPhase1InboxSync(accountId)
                statusBanner.value = if (p1.backfillCompleted) {
                    "受信トレイ同期完了（新規 ${p1.newlySyncedCount} 件）"
                } else {
                    "フェーズ1完了（新規 ${p1.newlySyncedCount} 件）。フェーズ2全件バックフィルを予約しました。"
                }
                if (!p1.backfillCompleted) {
                    container.syncScheduler.enqueueInitialBackfill(
                        accountId = accountId,
                        wifiOnly = container.appPreferences.wifiOnlyBackfill.value,
                    )
                }
                container.syncScheduler.ensurePeriodicIncrementalSync(
                    container.appPreferences.periodicSyncIntervalMinutes.value,
                )
            }.onFailure { err ->
                statusBanner.value = "同期エラー: ${err.message}"
            }
            isSyncRunning.value = false
        }
    }

    fun triggerIncrementalSync(accountId: Long) {
        if (isSyncRunning.value) return
        viewModelScope.launch {
            isSyncRunning.value = true
            statusBanner.value = "CONDSTORE 差分同期を実行中…"
            runCatching {
                val delta = container.syncEngine.runIncrementalSync(accountId)
                statusBanner.value =
                    "差分同期完了: 新規 ${delta.newlySyncedCount} 件 / フラグ更新 ${delta.flagUpdatedCount} 件"
            }.onFailure { err ->
                statusBanner.value = "差分同期エラー: ${err.message}"
            }
            isSyncRunning.value = false
        }
    }

    fun enqueueBackgroundBackfill(accountId: Long) {
        container.syncScheduler.enqueueInitialBackfill(
            accountId = accountId,
            wifiOnly = container.appPreferences.wifiOnlyBackfill.value,
        )
        statusBanner.value = "WorkManager バックグラウンド全件同期をキューに投入しました"
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        container.appPreferences.setNotificationsEnabled(enabled)
        if (enabled) {
            container.notificationManager.ensureChannel()
        }
        statusBanner.value = if (enabled) "新着メール通知を有効にしました" else "新着メール通知を無効にしました"
    }

    fun setPeriodicSyncInterval(minutes: Long) {
        container.appPreferences.setPeriodicSyncIntervalMinutes(minutes)
        container.syncScheduler.updatePeriodicIncrementalSync(minutes)
        statusBanner.value = if (minutes <= 0L) {
            "バックグラウンド定期同期を手動のみに変更しました"
        } else {
            "バックグラウンド定期同期を ${minutes}分間隔 に設定しました"
        }
    }

    fun flushOutboxNow() {
        container.syncScheduler.enqueueOutboxSend()
        statusBanner.value = "送信トレイ (Outbox) のSMTP送信ワーカーを起動しました"
    }

    fun hasNotificationPermission(): Boolean =
        container.notificationManager.hasNotificationPermission()

    companion object {
        fun factory(container: KazeMailContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DashboardViewModel(container) as T
            }
    }
}
