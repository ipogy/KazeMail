package com.kazemail.app.ui.inbox

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kazemail.app.data.InboxQuickFilter
import com.kazemail.app.data.db.AccountEntity
import com.kazemail.app.data.db.DraftEntity
import com.kazemail.app.data.db.FolderEntity
import com.kazemail.app.data.db.FolderSyncStateEntity
import com.kazemail.app.data.db.KazeMailDatabase
import com.kazemail.app.data.db.LocalCategorySummaryTuple
import com.kazemail.app.data.db.MessageMetaEntity
import com.kazemail.app.data.db.OutboxMessageEntity
import com.kazemail.app.data.db.ThreadSummaryTuple
import com.kazemail.app.di.KazeMailContainer
import com.kazemail.app.export.LocalArchiveExporter
import com.kazemail.app.sync.BackfillWorkProgress
import com.kazemail.app.sync.ImapFolderNameFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

enum class FolderSelection {
    SERVER_FOLDER,
    LOCAL_CATEGORY,
    LOCAL_ARCHIVE,
    DRAFTS,
    OUTBOX,
}

data class DrawerFolderItem(
    val folder: FolderEntity,
    val syncState: FolderSyncStateEntity?,
)

data class SelectionKey(
    val primaryMetaId: Long,
    val accountId: Long,
    val folderId: Long,
    val threadKey: String?,
)

sealed interface PendingArchiveConfirmation {
    data class SingleMessage(val metaId: Long) : PendingArchiveConfirmation
    data class Thread(
        val accountId: Long,
        val folderId: Long,
        val threadKey: String,
        val fallbackMetaId: Long,
    ) : PendingArchiveConfirmation
    data class Bulk(val items: List<SelectionKey>) : PendingArchiveConfirmation
}

data class InboxUiState(
    val hasLoadedAccounts: Boolean = false,
    val accounts: List<AccountEntity> = emptyList(),
    val selectedAccountId: Long? = null,
    val folders: List<DrawerFolderItem> = emptyList(),
    val selectedFolderId: Long? = null,
    val selectedLocalCategory: String? = null,
    val localCategories: List<LocalCategorySummaryTuple> = emptyList(),
    val folderSelectionMode: FolderSelection = FolderSelection.SERVER_FOLDER,
    val quickFilter: InboxQuickFilter = InboxQuickFilter.ALL,
    val locallyArchivedCount: Int = 0,
    val drafts: List<DraftEntity> = emptyList(),
    val draftCount: Int = 0,
    val outboxItems: List<OutboxMessageEntity> = emptyList(),
    val outboxPendingCount: Int = 0,
    val isThreadMode: Boolean = true,
    val previewLines: Int = 2,
    val showAvatars: Boolean = true,
    val confirmArchive: Boolean = false,
    val pageSize: Int = 100,
    val currentPageIndex: Int = 0,
    val totalItemsInView: Int = 0,
    val totalPages: Int = 1,
    val isPageLoaded: Boolean = false,
    val threadPageItems: List<ThreadSummaryTuple> = emptyList(),
    val flatPageItems: List<MessageMetaEntity> = emptyList(),
    val archivedPageItems: List<MessageMetaEntity> = emptyList(),
    val isSearchActive: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<MessageMetaEntity> = emptyList(),
    val isSearching: Boolean = false,
    val isRefreshing: Boolean = false,
    val backfillProgress: BackfillWorkProgress? = null,
    val totalStoredCount: Int = 0,
    val statusMessage: String? = null,
    val selectedItems: Map<Long, SelectionKey> = emptyMap(),
    val pendingArchiveConfirmation: PendingArchiveConfirmation? = null,
    val scrollResetEpoch: Int = 0,
)

data class UndoArchivePrompt(
    val metaIds: List<Long>,
    val message: String,
)

private data class InboxDisplayPrefsSnapshot(
    val previewLines: Int,
    val showAvatars: Boolean,
    val confirmArchive: Boolean,
    val backfillProgress: BackfillWorkProgress?,
)

private data class PageScopeKey(
    val accountId: Long?,
    val folderId: Long?,
    val localCategory: String?,
    val mode: FolderSelection,
    val threaded: Boolean,
    val filter: InboxQuickFilter,
    val pageSize: Int,
)

private data class InboxPageContentSnapshot(
    val pageSize: Int = 100,
    val currentPageIndex: Int = 0,
    val totalItemsInView: Int = 0,
    val totalPages: Int = 1,
    val isPageLoaded: Boolean = false,
    val threadPageItems: List<ThreadSummaryTuple> = emptyList(),
    val flatPageItems: List<MessageMetaEntity> = emptyList(),
    val archivedPageItems: List<MessageMetaEntity> = emptyList(),
) {
    val itemCount: Int
        get() = when {
            threadPageItems.isNotEmpty() -> threadPageItems.size
            flatPageItems.isNotEmpty() -> flatPageItems.size
            else -> archivedPageItems.size
        }
}

/**
 * Tracks list scroll offset across forward/back navigation while resetting cleanly
 * only when the user switches account, folder, filter, thread mode, or page.
 */
class InboxScrollTracker {
    var savedIndex: Int = 0
        private set
    var savedOffset: Int = 0
        private set
    var resetEpoch: Int = 0
        private set

    fun updateFromLayout(totalItemsCount: Int, firstVisibleIndex: Int, firstVisibleOffset: Int) {
        if (totalItemsCount <= 0) return
        savedIndex = firstVisibleIndex.coerceAtLeast(0)
        savedOffset = firstVisibleOffset.coerceAtLeast(0)
    }

    fun markContextReset(): Int {
        savedIndex = 0
        savedOffset = 0
        resetEpoch += 1
        return resetEpoch
    }
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class InboxViewModel(
    private val container: KazeMailContainer,
) : ViewModel() {

    private val databaseFlow = flow {
        emit(container.awaitDatabase())
    }.shareIn(viewModelScope, SharingStarted.Eagerly, replay = 1)

    private suspend fun db(): KazeMailDatabase = container.awaitDatabase()
    private val repo get() = container.messageRepository
    private val prefs = container.appPreferences

    private val selectedAccountId = MutableStateFlow<Long?>(null)
    private val selectedFolderId = MutableStateFlow<Long?>(null)
    private val selectedLocalCategory = MutableStateFlow<String?>(null)
    private val folderSelectionMode = MutableStateFlow(FolderSelection.SERVER_FOLDER)

    init {
        if (prefs.headersHealedVersion.value < HEADERS_HEALED_SCHEMA_VERSION) {
            viewModelScope.launch(Dispatchers.IO) {
                delay(2500L)
                db()
                val ok = runCatching { repo.healStoredEncodedHeaders() }.isSuccess
                if (ok) {
                    prefs.setHeadersHealedVersion(HEADERS_HEALED_SCHEMA_VERSION)
                }
            }
        }
    }
    private val quickFilter = MutableStateFlow(InboxQuickFilter.ALL)
    private val isThreadMode = MutableStateFlow(prefs.defaultThreadMode.value)
    private val currentPageIndex = MutableStateFlow(0)
    private val isSearchActive = MutableStateFlow(false)
    private val searchQuery = MutableStateFlow("")
    private val isRefreshing = MutableStateFlow(false)
    private val foregroundSyncCooldownMutex = Mutex()
    private var lastForegroundSyncMs: Long = 0L
    private val statusMessage = MutableStateFlow<String?>(null)
    private val selectedItems = MutableStateFlow<Map<Long, SelectionKey>>(emptyMap())
    private val pendingArchiveConfirmation = MutableStateFlow<PendingArchiveConfirmation?>(null)
    private val scrollTracker = InboxScrollTracker()
    private val scrollResetEpoch = MutableStateFlow(0)

    val savedScrollIndex: Int
        get() = scrollTracker.savedIndex
    val savedScrollOffset: Int
        get() = scrollTracker.savedOffset

    fun saveScrollPosition(totalItemsCount: Int, index: Int, offset: Int) {
        scrollTracker.updateFromLayout(
            totalItemsCount = totalItemsCount,
            firstVisibleIndex = index,
            firstVisibleOffset = offset,
        )
    }

    private fun markScrollReset() {
        scrollResetEpoch.value = scrollTracker.markContextReset()
    }

    private val _undoEvents = MutableSharedFlow<UndoArchivePrompt>(extraBufferCapacity = 8)
    val undoEvents: SharedFlow<UndoArchivePrompt> = _undoEvents.asSharedFlow()

    private val accountsWithLoadedFlow = databaseFlow
        .flatMapLatest { database -> database.accountDao().observeAll().map { true to it } }
        .onStart { emit(false to emptyList()) }
    private val accountsFlow = accountsWithLoadedFlow
        .map { it.second }
        .distinctUntilChanged()
    private val foldersFlow = databaseFlow
        .flatMapLatest { database -> database.folderDao().observeAll() }
        .onStart { emit(emptyList()) }
    private val syncStatesFlow = databaseFlow
        .flatMapLatest { database -> database.folderSyncStateDao().observeAll() }
        .onStart { emit(emptyList()) }
    private val totalCountFlow = databaseFlow
        .flatMapLatest { database -> database.messageMetaDao().observeTotalMessageCount() }
        .onStart { emit(0) }

    private val activeContextFlow = combine(
        accountsFlow,
        foldersFlow,
        selectedAccountId,
        selectedFolderId,
        folderSelectionMode,
    ) { accounts, folders, accId, fldId, mode ->
        val activeAcc = accId ?: accounts.firstOrNull()?.accountId
        val accountFolders = folders.filter { it.accountId == activeAcc }
        val activeFld = fldId?.takeIf { id -> accountFolders.any { it.folderId == id } }
            ?: accountFolders.firstOrNull { it.fullPath.equals("INBOX", ignoreCase = true) }?.folderId
            ?: accountFolders.firstOrNull()?.folderId
        Triple(activeAcc, activeFld, mode)
    }.distinctUntilChanged()

    private val backfillProgressFlow = activeContextFlow
        .map { it.first }
        .distinctUntilChanged()
        .flatMapLatest { accId ->
            if (accId == null) {
                flowOf(null)
            } else {
                flow { emit(container.awaitSyncScheduler()) }
                    .flatMapLatest { scheduler -> scheduler.observeBackfillWorkInfo(accId) }
            }
        }
        .onStart { emit(null) }

    private val displayPrefsFlow = combine(
        prefs.previewLines,
        prefs.showAvatars,
        prefs.confirmArchive,
        backfillProgressFlow,
    ) { lines, avatars, confirm, backfill ->
        InboxDisplayPrefsSnapshot(
            previewLines = lines,
            showAvatars = avatars,
            confirmArchive = confirm,
            backfillProgress = backfill,
        )
    }

    private val locallyArchivedCountFlow = activeContextFlow
        .map { it.first }
        .distinctUntilChanged()
        .flatMapLatest { accId ->
            if (accId == null) flowOf(0)
            else databaseFlow.flatMapLatest { database -> database.messageLocalStateDao().observeLocallyArchivedCount(accId) }
        }
        .onStart { emit(0) }

    private val localCategorySummariesFlow = activeContextFlow
        .map { it.first }
        .distinctUntilChanged()
        .flatMapLatest { accId ->
            if (accId == null) flowOf(emptyList())
            else databaseFlow.flatMapLatest { database -> database.messageLocalStateDao().observeLocalCategorySummaries(accId) }
        }
        .onStart { emit(emptyList()) }

    private val draftsAndOutboxFlow = activeContextFlow
        .map { it.first }
        .distinctUntilChanged()
        .flatMapLatest { accId ->
            if (accId == null) {
                flowOf(emptyList<DraftEntity>() to emptyList<OutboxMessageEntity>())
            } else {
                databaseFlow.flatMapLatest { database ->
                    combine(
                        database.draftDao().observeByAccount(accId),
                        database.outboxDao().observeByAccount(accId),
                    ) { d, o -> d to o }
                }
            }
        }
        .onStart { emit(emptyList<DraftEntity>() to emptyList()) }

    private val pageContentFlow: Flow<InboxPageContentSnapshot> = combine(
        activeContextFlow,
        selectedLocalCategory,
        isThreadMode,
        quickFilter,
        prefs.itemsPerPage,
    ) { activeCtx, localCat, threaded, filter, pageSize ->
        PageScopeKey(
            accountId = activeCtx.first,
            folderId = activeCtx.second,
            localCategory = if (activeCtx.third == FolderSelection.LOCAL_CATEGORY) localCat else null,
            mode = activeCtx.third,
            threaded = threaded,
            filter = filter,
            pageSize = pageSize,
        )
    }.distinctUntilChanged().flatMapLatest { scope ->
        val sanitizedPageSize = scope.pageSize.coerceIn(50, 200)
        databaseFlow.flatMapLatest { database ->
            val totalCountFlowForScope: Flow<Int>? = when {
                scope.mode == FolderSelection.SERVER_FOLDER && scope.folderId != null -> {
                    if (scope.threaded) {
                        repo.observeThreadCount(scope.folderId, scope.filter)
                    } else {
                        repo.observeFlatCount(scope.folderId, scope.filter)
                    }
                }
                scope.mode == FolderSelection.LOCAL_CATEGORY && scope.accountId != null && !scope.localCategory.isNullOrBlank() -> {
                    if (scope.threaded) {
                        repo.observeLocalCategoryThreadCount(scope.accountId, scope.localCategory, scope.filter)
                    } else {
                        repo.observeLocalCategoryFlatCount(scope.accountId, scope.localCategory, scope.filter)
                    }
                }
                scope.mode == FolderSelection.LOCAL_ARCHIVE && scope.accountId != null -> {
                    database.messageLocalStateDao().observeLocallyArchivedCount(scope.accountId)
                }
                else -> null
            }

            if (totalCountFlowForScope == null) {
                flowOf(
                    InboxPageContentSnapshot(
                        pageSize = sanitizedPageSize,
                        currentPageIndex = 0,
                        totalItemsInView = 0,
                        totalPages = 1,
                        isPageLoaded = true,
                    ),
                )
            } else {
                val itemsFlow: Flow<InboxPageContentSnapshot> = currentPageIndex
                    .map { it.coerceAtLeast(0) }
                    .distinctUntilChanged()
                    .flatMapLatest { requestedPage ->
                        when {
                            scope.mode == FolderSelection.SERVER_FOLDER && scope.folderId != null && scope.threaded -> {
                                repo.observeThreadPage(
                                    folderId = scope.folderId,
                                    filter = scope.filter,
                                    pageIndex = requestedPage,
                                    pageSize = sanitizedPageSize,
                                ).map { threads ->
                                    InboxPageContentSnapshot(
                                        pageSize = sanitizedPageSize,
                                        isPageLoaded = true,
                                        threadPageItems = threads,
                                    )
                                }
                            }
                            scope.mode == FolderSelection.SERVER_FOLDER && scope.folderId != null -> {
                                repo.observeFlatPage(
                                    folderId = scope.folderId,
                                    filter = scope.filter,
                                    pageIndex = requestedPage,
                                    pageSize = sanitizedPageSize,
                                ).map { flat ->
                                    InboxPageContentSnapshot(
                                        pageSize = sanitizedPageSize,
                                        isPageLoaded = true,
                                        flatPageItems = flat,
                                    )
                                }
                            }
                            scope.mode == FolderSelection.LOCAL_CATEGORY && scope.accountId != null && !scope.localCategory.isNullOrBlank() && scope.threaded -> {
                                repo.observeLocalCategoryThreadPage(
                                    accountId = scope.accountId,
                                    categoryName = scope.localCategory,
                                    filter = scope.filter,
                                    pageIndex = requestedPage,
                                    pageSize = sanitizedPageSize,
                                ).map { threads ->
                                    InboxPageContentSnapshot(
                                        pageSize = sanitizedPageSize,
                                        isPageLoaded = true,
                                        threadPageItems = threads,
                                    )
                                }
                            }
                            scope.mode == FolderSelection.LOCAL_CATEGORY && scope.accountId != null && !scope.localCategory.isNullOrBlank() -> {
                                repo.observeLocalCategoryFlatPage(
                                    accountId = scope.accountId,
                                    categoryName = scope.localCategory,
                                    filter = scope.filter,
                                    pageIndex = requestedPage,
                                    pageSize = sanitizedPageSize,
                                ).map { flat ->
                                    InboxPageContentSnapshot(
                                        pageSize = sanitizedPageSize,
                                        isPageLoaded = true,
                                        flatPageItems = flat,
                                    )
                                }
                            }
                            scope.mode == FolderSelection.LOCAL_ARCHIVE && scope.accountId != null -> {
                                repo.observeArchivedPage(
                                    accountId = scope.accountId,
                                    pageIndex = requestedPage,
                                    pageSize = sanitizedPageSize,
                                ).map { archived ->
                                    InboxPageContentSnapshot(
                                        pageSize = sanitizedPageSize,
                                        isPageLoaded = true,
                                        archivedPageItems = archived,
                                    )
                                }
                            }
                            else -> flowOf(InboxPageContentSnapshot(pageSize = sanitizedPageSize, isPageLoaded = true))
                        }
                    }

                // Execute page fetch and total count in parallel so inbox items render immediately on page 0.
                combine(
                    itemsFlow.onStart {
                        emit(InboxPageContentSnapshot(pageSize = sanitizedPageSize, isPageLoaded = false))
                    },
                    totalCountFlowForScope.onStart { emit(-1) },
                    currentPageIndex,
                ) { slice, rawTotal, requestedPage ->
                    val safeRequested = requestedPage.coerceAtLeast(0)
                    if (rawTotal >= 0) {
                        val totalPages = if (rawTotal <= 0) 1 else (rawTotal + sanitizedPageSize - 1) / sanitizedPageSize
                        val clampedPage = safeRequested.coerceIn(0, totalPages - 1)
                        if (currentPageIndex.value != clampedPage) {
                            currentPageIndex.value = clampedPage
                            markScrollReset()
                        }
                        slice.copy(
                            currentPageIndex = clampedPage,
                            totalItemsInView = rawTotal,
                            totalPages = totalPages,
                        )
                    } else {
                        val inferredTotal = maxOf(slice.itemCount + safeRequested * sanitizedPageSize, slice.itemCount)
                        val inferredPages = if (safeRequested == 0 && slice.itemCount < sanitizedPageSize) {
                            1
                        } else {
                            maxOf(safeRequested + 1, 1)
                        }
                        slice.copy(
                            currentPageIndex = safeRequested,
                            totalItemsInView = inferredTotal,
                            totalPages = inferredPages,
                        )
                    }
                }
            }
        }
    }

    private val searchStateFlow = searchQuery
        .debounce(150L)
        .combine(activeContextFlow.map { it.first }.distinctUntilChanged()) { q, accId -> q to accId }
        .flatMapLatest { (query, accId) ->
            if (query.isBlank()) {
                flowOf(false to emptyList<MessageMetaEntity>())
            } else {
                flow {
                    emit(true to emptyList())
                    db()
                    val hits = runCatching {
                        repo.searchLocal(query = query, accountId = accId, limit = 200)
                    }.getOrDefault(emptyList())
                    emit(false to hits)
                }
            }
        }

    val uiState: StateFlow<InboxUiState> = combine(
        accountsWithLoadedFlow,
        foldersFlow,
        syncStatesFlow,
        activeContextFlow,
        locallyArchivedCountFlow,
        isThreadMode,
        quickFilter,
        displayPrefsFlow,
        pageContentFlow,
        isSearchActive,
        searchQuery,
        searchStateFlow,
        isRefreshing,
        totalCountFlow,
        statusMessage,
        draftsAndOutboxFlow,
        selectedItems,
        pendingArchiveConfirmation,
        scrollResetEpoch,
        selectedLocalCategory,
        localCategorySummariesFlow,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val accountsPair = values[0] as Pair<Boolean, List<AccountEntity>>
        val hasLoadedAccounts = accountsPair.first
        val accounts = accountsPair.second
        @Suppress("UNCHECKED_CAST")
        val allFolders = values[1] as List<FolderEntity>
        @Suppress("UNCHECKED_CAST")
        val syncStates = values[2] as List<FolderSyncStateEntity>
        @Suppress("UNCHECKED_CAST")
        val activeCtx = values[3] as Triple<Long?, Long?, FolderSelection>
        val archivedCount = values[4] as Int
        val threaded = values[5] as Boolean
        val filter = values[6] as InboxQuickFilter
        val disp = values[7] as InboxDisplayPrefsSnapshot
        val pageSnapshot = values[8] as InboxPageContentSnapshot
        val searchOpen = values[9] as Boolean
        val query = values[10] as String
        @Suppress("UNCHECKED_CAST")
        val searchPair = values[11] as Pair<Boolean, List<MessageMetaEntity>>
        val refreshing = values[12] as Boolean
        val totalStored = values[13] as Int
        val banner = values[14] as String?
        @Suppress("UNCHECKED_CAST")
        val draftOutboxPair = values[15] as Pair<List<DraftEntity>, List<OutboxMessageEntity>>
        @Suppress("UNCHECKED_CAST")
        val selection = values[16] as Map<Long, SelectionKey>
        val archiveConfirm = values[17] as PendingArchiveConfirmation?
        val resetEpoch = values[18] as Int
        val activeLocalCategory = values[19] as String?
        @Suppress("UNCHECKED_CAST")
        val localCategorySummaries = values[20] as List<LocalCategorySummaryTuple>

        val drafts = draftOutboxPair.first
        val outboxItems = draftOutboxPair.second
        val pendingOutboxCount = outboxItems.count {
            it.status == OutboxMessageEntity.STATUS_QUEUED ||
                it.status == OutboxMessageEntity.STATUS_SENDING ||
                it.status == OutboxMessageEntity.STATUS_FAILED
        }

        val stateMap = syncStates.associateBy { it.folderId }
        val accountFolders = allFolders
            .filter { it.accountId == activeCtx.first }
            .sortedBy {
                val display = ImapFolderNameFormatter.formatDisplayName(it.fullPath, it.folderName)
                ImapFolderNameFormatter.sortKey(it.fullPath, display)
            }
            .map { DrawerFolderItem(it, stateMap[it.folderId]) }

        InboxUiState(
            hasLoadedAccounts = hasLoadedAccounts,
            accounts = accounts,
            selectedAccountId = activeCtx.first,
            folders = accountFolders,
            selectedFolderId = activeCtx.second,
            selectedLocalCategory = if (activeCtx.third == FolderSelection.LOCAL_CATEGORY) activeLocalCategory else null,
            localCategories = localCategorySummaries,
            folderSelectionMode = activeCtx.third,
            quickFilter = filter,
            locallyArchivedCount = archivedCount,
            drafts = drafts,
            draftCount = drafts.size,
            outboxItems = outboxItems,
            outboxPendingCount = pendingOutboxCount,
            isThreadMode = threaded,
            previewLines = disp.previewLines,
            showAvatars = disp.showAvatars,
            confirmArchive = disp.confirmArchive,
            pageSize = pageSnapshot.pageSize,
            currentPageIndex = pageSnapshot.currentPageIndex,
            totalItemsInView = pageSnapshot.totalItemsInView,
            totalPages = pageSnapshot.totalPages,
            isPageLoaded = pageSnapshot.isPageLoaded,
            threadPageItems = pageSnapshot.threadPageItems,
            flatPageItems = pageSnapshot.flatPageItems,
            archivedPageItems = pageSnapshot.archivedPageItems,
            isSearchActive = searchOpen,
            searchQuery = query,
            isSearching = searchPair.first,
            searchResults = searchPair.second,
            isRefreshing = refreshing,
            backfillProgress = disp.backfillProgress,
            totalStoredCount = totalStored,
            statusMessage = banner,
            selectedItems = selection,
            pendingArchiveConfirmation = archiveConfirm,
            scrollResetEpoch = resetEpoch,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), buildInitialState())

    private fun buildInitialState(): InboxUiState = InboxUiState(
        hasLoadedAccounts = false,
        isThreadMode = prefs.defaultThreadMode.value,
        previewLines = prefs.previewLines.value,
        showAvatars = prefs.showAvatars.value,
        confirmArchive = prefs.confirmArchive.value,
        pageSize = prefs.itemsPerPage.value.coerceIn(50, 200),
    )

    fun selectAccount(accountId: Long) {
        selectedAccountId.value = accountId
        selectedFolderId.value = null
        selectedLocalCategory.value = null
        folderSelectionMode.value = FolderSelection.SERVER_FOLDER
        currentPageIndex.value = 0
        selectedItems.value = emptyMap()
        markScrollReset()
    }

    fun selectServerFolder(folderId: Long) {
        selectedFolderId.value = folderId
        selectedLocalCategory.value = null
        folderSelectionMode.value = FolderSelection.SERVER_FOLDER
        currentPageIndex.value = 0
        selectedItems.value = emptyMap()
        markScrollReset()
    }

    fun selectLocalCategory(categoryName: String) {
        selectedLocalCategory.value = categoryName
        folderSelectionMode.value = FolderSelection.LOCAL_CATEGORY
        currentPageIndex.value = 0
        selectedItems.value = emptyMap()
        markScrollReset()
    }

    fun selectLocalArchive() {
        selectedLocalCategory.value = null
        folderSelectionMode.value = FolderSelection.LOCAL_ARCHIVE
        currentPageIndex.value = 0
        selectedItems.value = emptyMap()
        markScrollReset()
    }

    fun selectDrafts() {
        selectedLocalCategory.value = null
        folderSelectionMode.value = FolderSelection.DRAFTS
        currentPageIndex.value = 0
        selectedItems.value = emptyMap()
        markScrollReset()
    }

    fun selectOutbox() {
        selectedLocalCategory.value = null
        folderSelectionMode.value = FolderSelection.OUTBOX
        currentPageIndex.value = 0
        selectedItems.value = emptyMap()
        markScrollReset()
    }

    fun setQuickFilter(filter: InboxQuickFilter) {
        quickFilter.value = filter
        currentPageIndex.value = 0
        selectedItems.value = emptyMap()
        markScrollReset()
    }

    fun goToPage(pageIndex: Int) {
        val maxPage = (uiState.value.totalPages - 1).coerceAtLeast(0)
        val targetPage = pageIndex.coerceIn(0, maxPage)
        val changed = targetPage != currentPageIndex.value
        currentPageIndex.value = targetPage
        selectedItems.value = emptyMap()
        if (changed) {
            markScrollReset()
        }
    }

    fun goToPreviousPage() {
        goToPage(uiState.value.currentPageIndex - 1)
    }

    fun goToNextPage() {
        goToPage(uiState.value.currentPageIndex + 1)
    }

    fun selectAllOnCurrentPage() {
        val state = uiState.value
        val isFolderOrCategory = state.folderSelectionMode == FolderSelection.SERVER_FOLDER ||
            state.folderSelectionMode == FolderSelection.LOCAL_CATEGORY
        val keys: List<SelectionKey> = when {
            state.folderSelectionMode == FolderSelection.LOCAL_ARCHIVE -> state.archivedPageItems.map { msg ->
                SelectionKey(
                    primaryMetaId = msg.id,
                    accountId = msg.accountId,
                    folderId = msg.folderId,
                    threadKey = null,
                )
            }
            isFolderOrCategory && state.isThreadMode ->
                state.threadPageItems.map { thread ->
                    SelectionKey(
                        primaryMetaId = thread.latestMetaId,
                        accountId = thread.accountId,
                        folderId = thread.folderId,
                        threadKey = thread.threadKey,
                    )
                }
            isFolderOrCategory -> state.flatPageItems.map { msg ->
                SelectionKey(
                    primaryMetaId = msg.id,
                    accountId = msg.accountId,
                    folderId = msg.folderId,
                    threadKey = null,
                )
            }
            else -> emptyList()
        }
        selectedItems.value = keys.associateBy { it.primaryMetaId }
    }

    fun toggleSelection(key: SelectionKey) {
        selectedItems.update { map ->
            if (key.primaryMetaId in map) {
                map - key.primaryMetaId
            } else {
                map + (key.primaryMetaId to key)
            }
        }
    }

    fun clearSelection() {
        selectedItems.value = emptyMap()
    }

    fun markSelectedRead(isRead: Boolean) {
        val items = selectedItems.value.values.toList()
        if (items.isEmpty()) return
        selectedItems.value = emptyMap()
        viewModelScope.launch {
            val allMetaIds = resolveAllMetaIds(items)
            repo.setBulkReadState(allMetaIds, isRead)
            statusMessage.value = if (isRead) {
                "${allMetaIds.size} 件を既読にしました"
            } else {
                "${allMetaIds.size} 件を未読にしました"
            }
        }
    }

    fun requestArchiveSelected() {
        val items = selectedItems.value.values.toList()
        if (items.isEmpty()) return
        if (prefs.confirmArchive.value) {
            pendingArchiveConfirmation.value = PendingArchiveConfirmation.Bulk(items)
        } else {
            selectedItems.value = emptyMap()
            performBulkArchive(items)
        }
    }

    fun confirmPendingArchive() {
        val pending = pendingArchiveConfirmation.value ?: return
        pendingArchiveConfirmation.value = null
        when (pending) {
            is PendingArchiveConfirmation.SingleMessage -> performSingleArchive(pending.metaId)
            is PendingArchiveConfirmation.Thread -> performThreadArchive(
                pending.accountId,
                pending.folderId,
                pending.threadKey,
                pending.fallbackMetaId,
            )
            is PendingArchiveConfirmation.Bulk -> {
                selectedItems.value = emptyMap()
                performBulkArchive(pending.items)
            }
        }
    }

    fun dismissArchiveConfirmation() {
        pendingArchiveConfirmation.value = null
    }

    private suspend fun resolveAllMetaIds(items: List<SelectionKey>): List<Long> {
        val ids = linkedSetOf<Long>()
        for (item in items) {
            val threadKey = item.threadKey
            if (!threadKey.isNullOrBlank() && !threadKey.startsWith("id:")) {
                val threadMetas = db().messageMetaDao().getThreadMessages(item.accountId, threadKey)
                    .filter { it.folderId == item.folderId }
                if (threadMetas.isNotEmpty()) {
                    threadMetas.forEach { ids += it.id }
                } else {
                    ids += item.primaryMetaId
                }
            } else {
                ids += item.primaryMetaId
            }
        }
        return ids.toList()
    }

    private fun performBulkArchive(items: List<SelectionKey>) {
        viewModelScope.launch {
            val allMetaIds = resolveAllMetaIds(items)
            repo.archiveMessagesLocally(allMetaIds)
            _undoEvents.tryEmit(UndoArchivePrompt(allMetaIds, "${allMetaIds.size} 件をアーカイブしました"))
        }
    }

    fun discardDraft(draftId: Long) {
        viewModelScope.launch {
            container.outboxManager.deleteDraft(draftId)
        }
    }

    fun retryOutboxItem(outboxId: Long) {
        viewModelScope.launch {
            container.outboxManager.retryOutboxItem(outboxId)
            container.syncScheduler.enqueueOutboxSend()
            statusMessage.value = "送信キューを再試行予約しました"
        }
    }

    fun flushAllOutboxNow() {
        container.syncScheduler.enqueueOutboxSend()
        statusMessage.value = "Outbox バックグラウンド送信を予約しました"
    }

    fun moveOutboxToDraft(outboxId: Long, onDraftReady: (accountId: Long, draftId: Long) -> Unit) {
        viewModelScope.launch {
            val accId = uiState.value.selectedAccountId ?: return@launch
            val draftId = container.outboxManager.moveOutboxItemToDraft(outboxId)
            if (draftId != null) {
                onDraftReady(accId, draftId)
            }
        }
    }

    fun notifyMessageQueued() {
        statusMessage.value = "送信トレイ (Outbox) に格納しました（接続時にバックグラウンド送信）"
    }

    fun exportActiveFolderToMbox(context: Context, destinationUri: Uri) {
        val folderId = uiState.value.selectedFolderId ?: return
        viewModelScope.launch {
            statusMessage.value = "MBOX エクスポートを作成中…"
            runCatching {
                val res = withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(destinationUri)?.use { out ->
                        LocalArchiveExporter.exportFolderToMbox(
                            db = db(),
                            folderId = folderId,
                            outputStream = out,
                            decompressHtml = { zstdBytes ->
                                repo.decompressHtmlInMemory(zstdBytes)
                            },
                        )
                    } ?: error("出力ファイルを開けませんでした")
                }
                res
            }.onSuccess { res ->
                statusMessage.value = "MBOX エクスポート完了: ${res.exportedCount} 件 (${res.totalBytes} bytes)"
            }.onFailure { err ->
                statusMessage.value = "MBOX エクスポートエラー: ${err.message}"
            }
        }
    }

    fun importArchiveFiles(
        context: Context,
        uris: List<Uri>,
        onK9sFileDetected: (Uri) -> Unit,
    ) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            statusMessage.value = "ファイルを解析・インポート中…"
            val currentAccId = uiState.value.selectedAccountId
            var firstK9sUri: Uri? = null
            var totalImported = 0
            var totalSkipped = 0
            var totalErrors = 0
            var importedAccId: Long? = null
            var importedFolderId: Long? = null

            runCatching {
                withContext(Dispatchers.IO) {
                    for (uri in uris) {
                        val kind = detectImportFileKind(context, uri)
                        when (kind) {
                            ImportFileKind.K9S -> {
                                if (firstK9sUri == null) firstK9sUri = uri
                            }
                            ImportFileKind.MBOX -> {
                                context.contentResolver.openInputStream(uri)?.use { stream ->
                                    val res = container.localArchiveImporter.importMboxStream(
                                        targetAccountId = currentAccId,
                                        inputStream = stream,
                                    )
                                    totalImported += res.importedCount
                                    totalSkipped += res.skippedDuplicates
                                    totalErrors += res.errorCount
                                    importedAccId = res.accountId
                                    importedFolderId = res.folderId
                                } ?: run { totalErrors += 1 }
                            }
                            ImportFileKind.EML -> {
                                context.contentResolver.openInputStream(uri)?.use { stream ->
                                    val res = container.localArchiveImporter.importEmlStream(
                                        targetAccountId = currentAccId,
                                        inputStream = stream,
                                    )
                                    totalImported += res.importedCount
                                    totalSkipped += res.skippedDuplicates
                                    totalErrors += res.errorCount
                                    importedAccId = res.accountId
                                    importedFolderId = res.folderId
                                } ?: run { totalErrors += 1 }
                            }
                        }
                    }
                }
            }.onSuccess {
                if (importedAccId != null && importedFolderId != null) {
                    selectedAccountId.value = importedAccId
                    selectedFolderId.value = importedFolderId
                    folderSelectionMode.value = FolderSelection.SERVER_FOLDER
                    currentPageIndex.value = 0
                    markScrollReset()
                }
                firstK9sUri?.let { k9s ->
                    statusMessage.value = ".k9s アカウント設定ファイルを開きます…"
                    onK9sFileDetected(k9s)
                    return@onSuccess
                }
                statusMessage.value = buildString {
                    append("インポート完了: 取込 ${totalImported}件")
                    if (totalSkipped > 0) append(" / 重複スキップ ${totalSkipped}件")
                    if (totalErrors > 0) append(" / 失敗 ${totalErrors}件")
                }
            }.onFailure { err ->
                statusMessage.value = "インポートエラー: ${err.message}"
            }
        }
    }

    private enum class ImportFileKind { K9S, MBOX, EML }

    private fun detectImportFileKind(context: Context, uri: Uri): ImportFileKind {
        var displayName = uri.lastPathSegment.orEmpty()
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        c.getString(idx)?.let { displayName = it }
                    }
                }
            }
        }
        val lowerName = displayName.lowercase()
        if (lowerName.endsWith(".k9s") || lowerName.endsWith(".xml")) return ImportFileKind.K9S
        if (lowerName.endsWith(".mbox")) return ImportFileKind.MBOX

        val mime = context.contentResolver.getType(uri)?.lowercase().orEmpty()
        if (mime == "application/mbox") return ImportFileKind.MBOX
        if (mime == "application/zip" || mime == "text/xml" || mime == "application/xml") {
            return ImportFileKind.K9S
        }

        val head = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val buf = ByteArray(1024)
                val n = stream.read(buf)
                if (n > 0) buf.copyOf(n) else ByteArray(0)
            }
        }.getOrNull() ?: ByteArray(0)

        if (head.size >= 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()) {
            return ImportFileKind.K9S
        }
        val headText = String(head, StandardCharsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (headText.startsWith("<k9settings") ||
            (headText.startsWith("<?xml") && headText.contains("<k9settings"))
        ) {
            return ImportFileKind.K9S
        }
        val firstLine = String(head, StandardCharsets.ISO_8859_1).lineSequence().firstOrNull().orEmpty()
        if (firstLine.startsWith("From ") && !firstLine.startsWith("From:")) {
            return ImportFileKind.MBOX
        }
        return ImportFileKind.EML
    }

    fun toggleThreadMode() {
        isThreadMode.value = !isThreadMode.value
        currentPageIndex.value = 0
        selectedItems.value = emptyMap()
        markScrollReset()
    }

    fun setSearchActive(active: Boolean) {
        isSearchActive.value = active
        if (!active) {
            searchQuery.value = ""
        }
    }

    fun updateSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun appendSearchOperator(token: String) {
        val current = searchQuery.value.trimEnd()
        searchQuery.value = if (current.isEmpty()) token else "$current $token"
    }

    fun onAppForegrounded() {
        if (prefs.periodicSyncIntervalMinutes.value <= 0L) return
        viewModelScope.launch(Dispatchers.IO) {
            val previousSyncMs: Long
            foregroundSyncCooldownMutex.withLock {
                val now = System.currentTimeMillis()
                if (lastForegroundSyncMs > 0L && now - lastForegroundSyncMs < FOREGROUND_SYNC_COOLDOWN_MS) {
                    return@launch
                }
                previousSyncMs = lastForegroundSyncMs
                lastForegroundSyncMs = now
            }

            if (previousSyncMs == 0L) {
                delay(1200L)
            }
            if (!container.hasInternetConnection()) {
                foregroundSyncCooldownMutex.withLock {
                    if (lastForegroundSyncMs >= previousSyncMs) {
                        lastForegroundSyncMs = previousSyncMs
                    }
                }
                return@launch
            }

            val accounts = runCatching { db().accountDao().getAll() }.getOrNull() ?: return@launch
            val remoteAccounts = accounts.filter { !it.authType.equals("LOCAL", ignoreCase = true) }
            if (remoteAccounts.isEmpty()) return@launch

            val activeId = uiState.value.selectedAccountId
            val orderedAccounts = if (activeId != null) {
                remoteAccounts.sortedByDescending { it.accountId == activeId }
            } else {
                remoteAccounts
            }

            var anySucceeded = false
            for (acc in orderedAccounts) {
                try {
                    container.syncEngine.runIncrementalSync(acc.accountId)
                    anySucceeded = true
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    // Silent foreground sync keeps UI clean on transient offline/VPN errors.
                }
            }
            foregroundSyncCooldownMutex.withLock {
                lastForegroundSyncMs = if (anySucceeded) System.currentTimeMillis() else previousSyncMs
            }
        }
    }

    fun refreshActiveAccount() {
        val accId = uiState.value.selectedAccountId ?: return
        if (isRefreshing.value) return
        viewModelScope.launch {
            isRefreshing.value = true
            runCatching {
                val delta = container.syncEngine.runIncrementalSync(accId)
                foregroundSyncCooldownMutex.withLock {
                    lastForegroundSyncMs = System.currentTimeMillis()
                }
                statusMessage.value =
                    "同期完了: 新規 ${delta.newlySyncedCount} 件 / フラグ更新 ${delta.flagUpdatedCount} 件"
            }.onFailure { err ->
                statusMessage.value = "同期エラー: ${err.message}"
            }
            isRefreshing.value = false
        }
    }

    fun requestArchiveSingleMessage(metaId: Long) {
        if (prefs.confirmArchive.value) {
            pendingArchiveConfirmation.value = PendingArchiveConfirmation.SingleMessage(metaId)
        } else {
            performSingleArchive(metaId)
        }
    }

    private fun performSingleArchive(metaId: Long) {
        viewModelScope.launch {
            repo.archiveMessagesLocally(listOf(metaId))
            _undoEvents.tryEmit(UndoArchivePrompt(listOf(metaId), "アーカイブしました"))
        }
    }

    fun requestArchiveThreadInFolder(
        accountId: Long,
        folderId: Long,
        threadKey: String,
        fallbackMetaId: Long,
    ) {
        if (prefs.confirmArchive.value) {
            pendingArchiveConfirmation.value = PendingArchiveConfirmation.Thread(
                accountId = accountId,
                folderId = folderId,
                threadKey = threadKey,
                fallbackMetaId = fallbackMetaId,
            )
        } else {
            performThreadArchive(accountId, folderId, threadKey, fallbackMetaId)
        }
    }

    private fun performThreadArchive(
        accountId: Long,
        folderId: Long,
        threadKey: String,
        fallbackMetaId: Long,
    ) {
        viewModelScope.launch {
            val archivedIds = repo.archiveThreadInFolderLocally(accountId, folderId, threadKey)
                .ifEmpty {
                    repo.archiveMessagesLocally(listOf(fallbackMetaId))
                    listOf(fallbackMetaId)
                }
            _undoEvents.tryEmit(UndoArchivePrompt(archivedIds, "スレッドをアーカイブしました (${archivedIds.size}件)"))
        }
    }

    fun notifyExternalArchived(metaIds: List<Long>) {
        if (metaIds.isEmpty()) return
        _undoEvents.tryEmit(UndoArchivePrompt(metaIds, "アーカイブしました"))
    }

    fun undoArchive(metaIds: List<Long>) {
        viewModelScope.launch {
            repo.unarchiveMessagesLocally(metaIds)
        }
    }

    fun toggleStar(metaId: Long, currentlyFlagged: Boolean) {
        viewModelScope.launch {
            repo.toggleFlagged(metaId, currentlyFlagged)
        }
    }

    fun enqueueBackgroundBackfill() {
        val accId = uiState.value.selectedAccountId ?: return
        container.syncScheduler.enqueueInitialBackfill(accId, prefs.wifiOnlyBackfill.value)
        statusMessage.value = "バックグラウンド全件同期を予約しました"
    }

    fun clearStatusMessage() {
        statusMessage.value = null
    }

    companion object {
        private const val HEADERS_HEALED_SCHEMA_VERSION = 1
        private const val FOREGROUND_SYNC_COOLDOWN_MS = 2 * 60 * 1000L

        fun factory(container: KazeMailContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    InboxViewModel(container) as T
            }
    }
}
