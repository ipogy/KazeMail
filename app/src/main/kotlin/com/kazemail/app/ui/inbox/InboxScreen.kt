package com.kazemail.app.ui.inbox

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kazemail.app.data.InboxQuickFilter
import com.kazemail.app.data.db.DraftEntity
import com.kazemail.app.data.db.MessageMetaEntity
import com.kazemail.app.data.db.OutboxMessageEntity
import com.kazemail.app.data.db.ThreadSummaryTuple
import com.kazemail.app.sync.ImapFolderNameFormatter
import com.kazemail.app.sync.Rfc2047HeaderDecoder
import com.kazemail.app.ui.common.DateFormatters
import com.kazemail.app.ui.theme.KazeIcons
import com.kazemail.app.ui.theme.LocalKazeDarkTheme
import com.kazemail.app.ui.theme.StarAmberDark
import com.kazemail.app.ui.theme.StarAmberLight
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    viewModel: InboxViewModel,
    onOpenMessage: (accountId: Long, metaId: Long, threadId: String?, isThreadMode: Boolean) -> Unit,
    onComposeNew: (accountId: Long) -> Unit,
    onOpenDraft: (accountId: Long, draftId: Long) -> Unit,
    onAddAccountClick: () -> Unit,
    onImportK9sUri: (Uri) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAccountSettings: (Long) -> Unit,
    onOpenSyncDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = viewModel.savedScrollIndex,
        initialFirstVisibleItemScrollOffset = viewModel.savedScrollOffset,
    )
    var lastHandledResetEpoch by rememberSaveable { mutableIntStateOf(state.scrollResetEpoch) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val isDark = LocalKazeDarkTheme.current

    LaunchedEffect(listState) {
        snapshotFlow {
            Triple(
                listState.layoutInfo.totalItemsCount,
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
            )
        }.collect { (totalCount, idx, offset) ->
            viewModel.saveScrollPosition(totalCount, idx, offset)
        }
    }

    DisposableEffect(listState) {
        onDispose {
            viewModel.saveScrollPosition(
                totalItemsCount = listState.layoutInfo.totalItemsCount,
                index = listState.firstVisibleItemIndex,
                offset = listState.firstVisibleItemScrollOffset,
            )
        }
    }

    LaunchedEffect(state.scrollResetEpoch) {
        if (state.scrollResetEpoch != lastHandledResetEpoch) {
            lastHandledResetEpoch = state.scrollResetEpoch
            listState.scrollToItem(0)
        }
    }

    val isSelectionActive = state.selectedItems.isNotEmpty()
    // Compose BackHandler evaluates last-registered first: Drawer -> Selection -> Search
    BackHandler(enabled = state.isSearchActive) {
        viewModel.setSearchActive(false)
    }
    BackHandler(enabled = isSelectionActive) {
        viewModel.clearSelection()
    }
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    val mboxExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/mbox"),
    ) { uri ->
        if (uri != null) {
            viewModel.exportActiveFolderToMbox(context, uri)
        }
    }

    val k9sFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            onImportK9sUri(uri)
        }
    }

    val archiveImportPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.importArchiveFiles(context, uris, onK9sFileDetected = onImportK9sUri)
        }
    }

    // Exact 5-second Undo Snackbar for non-destructive Archive
    LaunchedEffect(viewModel) {
        viewModel.undoEvents.collect { prompt ->
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = withTimeoutOrNull(5000L) {
                snackbarHostState.showSnackbar(
                    message = prompt.message,
                    actionLabel = "元に戻す",
                    duration = SnackbarDuration.Indefinite,
                )
            }
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.undoArchive(prompt.metaIds)
            } else {
                snackbarHostState.currentSnackbarData?.dismiss()
            }
        }
    }

    state.pendingArchiveConfirmation?.let {
        AlertDialog(
            onDismissRequest = viewModel::dismissArchiveConfirmation,
            title = { Text("ローカルアーカイブ") },
            text = { Text("選択したメールをローカルアーカイブへ移動しますか？（サーバー上のメールは削除・変更されません）") },
            confirmButton = {
                Button(onClick = viewModel::confirmPendingArchive) {
                    Text("アーカイブ")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissArchiveConfirmation) {
                    Text("キャンセル")
                }
            },
        )
    }

    val activeAccount = state.accounts.firstOrNull { it.accountId == state.selectedAccountId }
    val activeFolder = state.folders.firstOrNull { it.folder.folderId == state.selectedFolderId }?.folder
    val headerTitle = when (state.folderSelectionMode) {
        FolderSelection.LOCAL_ARCHIVE -> "アーカイブ (ローカル)"
        FolderSelection.LOCAL_CATEGORY -> state.selectedLocalCategory ?: "自動仕分けフォルダ"
        FolderSelection.DRAFTS -> "下書き (ローカル)"
        FolderSelection.OUTBOX -> "送信トレイ (Outbox)"
        FolderSelection.SERVER_FOLDER -> activeFolder?.let {
            ImapFolderNameFormatter.formatDisplayName(it.fullPath, it.folderName)
        } ?: "KazeMail"
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = !isSelectionActive,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier
                    .width(304.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "KazeMail",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = {
                                scope.launch { drawerState.close() }
                                onOpenSettings()
                            },
                        ) {
                            Icon(Icons.Filled.Settings, contentDescription = "設定")
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = KazeIcons.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "非破壊・暗号化DB • 蓄積 ${state.totalStoredCount} 件",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))

                if (state.accounts.isNotEmpty()) {
                    Text(
                        text = "アカウント",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    state.accounts.forEach { acc ->
                        val avatarColor = remember(acc.emailAddress) { deterministicAvatarColor(acc.emailAddress) }
                        val initial = (acc.displayName?.takeIf { it.isNotBlank() } ?: acc.emailAddress)
                            .firstOrNull()?.uppercaseChar()?.toString() ?: "K"
                        NavigationDrawerItem(
                            icon = {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(avatarColor),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = initial,
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            },
                            label = {
                                Text(acc.emailAddress, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            badge = {
                                Text(acc.authType, style = MaterialTheme.typography.labelSmall)
                            },
                            selected = acc.accountId == state.selectedAccountId,
                            onClick = {
                                viewModel.selectAccount(acc.accountId)
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                    )
                }

                Text(
                    text = "フォルダ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )

                Column {
                    state.folders.forEach { item ->
                        val f = item.folder
                        val isSelected = state.folderSelectionMode == FolderSelection.SERVER_FOLDER &&
                            f.folderId == state.selectedFolderId
                        val isInbox = f.fullPath.equals("INBOX", ignoreCase = true)
                        NavigationDrawerItem(
                            icon = {
                                Icon(
                                    imageVector = if (isInbox) KazeIcons.Inbox else KazeIcons.Folder,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                            },
                            label = {
                                val displayLabel = remember(f.folderId, f.fullPath, f.folderName) {
                                    ImapFolderNameFormatter.formatDisplayName(f.fullPath, f.folderName)
                                }
                                Text(
                                    text = displayLabel,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            badge = {
                                val badgeText = buildString {
                                    if (f.unreadCount > 0) append("${f.unreadCount} / ")
                                    append(f.totalCount)
                                }
                                Text(badgeText, style = MaterialTheme.typography.labelSmall)
                            },
                            selected = isSelected,
                            onClick = {
                                viewModel.selectServerFolder(f.folderId)
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }

                    NavigationDrawerItem(
                        icon = { Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        label = { Text("下書き (ローカル)") },
                        badge = {
                            if (state.draftCount > 0) {
                                Text("${state.draftCount}", style = MaterialTheme.typography.labelSmall)
                            }
                        },
                        selected = state.folderSelectionMode == FolderSelection.DRAFTS,
                        onClick = {
                            viewModel.selectDrafts()
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )

                    NavigationDrawerItem(
                        icon = {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        label = { Text("送信トレイ (Outbox)") },
                        badge = {
                            if (state.outboxPendingCount > 0) {
                                Text("${state.outboxPendingCount}", style = MaterialTheme.typography.labelSmall)
                            }
                        },
                        selected = state.folderSelectionMode == FolderSelection.OUTBOX,
                        onClick = {
                            viewModel.selectOutbox()
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )

                    NavigationDrawerItem(
                        icon = { Icon(KazeIcons.Archive, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        label = { Text("ローカルアーカイブ") },
                        badge = {
                            if (state.locallyArchivedCount > 0) {
                                Text("${state.locallyArchivedCount}", style = MaterialTheme.typography.labelSmall)
                            }
                        },
                        selected = state.folderSelectionMode == FolderSelection.LOCAL_ARCHIVE,
                        onClick = {
                            viewModel.selectLocalArchive()
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }

                if (state.localCategories.isNotEmpty()) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                    )
                    Text(
                        text = "ローカル自動仕分けフォルダ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                    Column {
                        state.localCategories.forEach { cat ->
                            val isSelected = state.folderSelectionMode == FolderSelection.LOCAL_CATEGORY &&
                                state.selectedLocalCategory == cat.categoryName
                            NavigationDrawerItem(
                                icon = {
                                    Icon(
                                        imageVector = KazeIcons.Folder,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                    )
                                },
                                label = {
                                    Text(
                                        text = cat.categoryName,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                badge = {
                                    val badgeText = buildString {
                                        if (cat.unreadCount > 0) append("${cat.unreadCount} / ")
                                        append(cat.totalCount)
                                    }
                                    Text(badgeText, style = MaterialTheme.typography.labelSmall)
                                },
                                selected = isSelected,
                                onClick = {
                                    viewModel.selectLocalCategory(cat.categoryName)
                                    scope.launch { drawerState.close() }
                                },
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                )
                if (activeFolder != null) {
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Filled.Email, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        label = { Text("このフォルダを MBOX 保存") },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            val safeFolder = activeFolder.folderName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                            mboxExportLauncher.launch("${safeFolder.ifBlank { "folder" }}.mbox")
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                NavigationDrawerItem(
                    icon = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    label = { Text("アカウントを追加") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onAddAccountClick()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    label = { Text(".k9s からアカウント移行") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        k9sFilePickerLauncher.launch(arrayOf("*/*"))
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Filled.Email, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    label = { Text(".eml / .mbox を取り込む") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        archiveImportPickerLauncher.launch(arrayOf("*/*"))
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                )
                if (activeAccount != null) {
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        label = { Text("このアカウントの設定・自動ルール") },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            onOpenAccountSettings(activeAccount.accountId)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                NavigationDrawerItem(
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    label = { Text("アプリ全体設定") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenSettings()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    label = { Text("同期状態・詳細診断") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenSyncDiagnostics()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        },
    ) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            floatingActionButton = {
                val accId = state.selectedAccountId
                if (accId != null && !isSelectionActive) {
                    ExtendedFloatingActionButton(
                        onClick = { onComposeNew(accId) },
                        icon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        text = { Text("作成") },
                    )
                }
            },
            topBar = {
                if (isSelectionActive) {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            titleContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            navigationIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            actionIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                        navigationIcon = {
                            IconButton(onClick = viewModel::clearSelection) {
                                Icon(Icons.Filled.Close, contentDescription = "選択解除")
                            }
                        },
                        title = {
                            Text(
                                text = "${state.selectedItems.size}件選択中",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        actions = {
                            TextButton(
                                onClick = viewModel::selectAllOnCurrentPage,
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                ),
                            ) {
                                Text(
                                    text = "全選択",
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                            IconButton(onClick = { viewModel.markSelectedRead(true) }) {
                                Icon(KazeIcons.MarkRead, contentDescription = "既読にする")
                            }
                            IconButton(onClick = { viewModel.markSelectedRead(false) }) {
                                Icon(Icons.Filled.Email, contentDescription = "未読にする")
                            }
                            if (state.folderSelectionMode == FolderSelection.LOCAL_ARCHIVE) {
                                IconButton(
                                    onClick = {
                                        viewModel.undoArchive(state.selectedItems.keys.toList())
                                        viewModel.clearSelection()
                                    },
                                ) {
                                    Icon(KazeIcons.Unarchive, contentDescription = "受信トレイに戻す")
                                }
                            } else {
                                IconButton(onClick = viewModel::requestArchiveSelected) {
                                    Icon(KazeIcons.Archive, contentDescription = "アーカイブ")
                                }
                            }
                        },
                    )
                } else {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    text = headerTitle,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val rangeText = if (state.totalItemsInView > 0) {
                                    val rangeStart = (state.currentPageIndex * state.pageSize + 1)
                                        .coerceAtMost(state.totalItemsInView)
                                    val rangeEnd = minOf(
                                        (state.currentPageIndex + 1) * state.pageSize,
                                        state.totalItemsInView,
                                    )
                                    "${rangeStart}–${rangeEnd}/${state.totalItemsInView}件"
                                } else {
                                    null
                                }
                                val subtitle = when {
                                    state.accounts.isEmpty() -> "ローカル完結・非破壊メール"
                                    state.folderSelectionMode == FolderSelection.DRAFTS ->
                                        "${activeAccount?.emailAddress.orEmpty()} • ローカル下書き (${state.draftCount}件)"
                                    state.folderSelectionMode == FolderSelection.OUTBOX ->
                                        "${activeAccount?.emailAddress.orEmpty()} • 送信待機 (${state.outboxPendingCount}件)"
                                    state.folderSelectionMode == FolderSelection.LOCAL_ARCHIVE -> buildString {
                                        append(activeAccount?.emailAddress.orEmpty())
                                        append(" • ローカルアーカイブ")
                                        if (rangeText != null) {
                                            append(" • ")
                                            append(rangeText)
                                        }
                                    }
                                    state.folderSelectionMode == FolderSelection.LOCAL_CATEGORY -> buildString {
                                        activeAccount?.emailAddress?.let {
                                            append(it)
                                            append(" • ")
                                        }
                                        append("自動仕分け")
                                        append(" • ")
                                        append(if (state.isThreadMode) "スレッド" else "時系列")
                                        if (rangeText != null) {
                                            append(" • ")
                                            append(rangeText)
                                        }
                                    }
                                    else -> buildString {
                                        activeAccount?.emailAddress?.let {
                                            append(it)
                                            append(" • ")
                                        }
                                        activeFolder?.let {
                                            if (it.unreadCount > 0) append("未読 ${it.unreadCount} • ")
                                        }
                                        append(if (state.isThreadMode) "スレッド" else "時系列")
                                        if (rangeText != null) {
                                            append(" • ")
                                            append(rangeText)
                                        }
                                    }
                                }
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Filled.Menu, contentDescription = "メニュー")
                            }
                        },
                        actions = {
                            if (state.accounts.isNotEmpty() && !activeAccount?.authType.equals("LOCAL", true)) {
                                IconButton(
                                    onClick = viewModel::refreshActiveAccount,
                                    enabled = !state.isRefreshing,
                                ) {
                                    if (state.isRefreshing) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        Icon(Icons.Filled.Refresh, contentDescription = "今すぐ同期")
                                    }
                                }
                            }
                            if (state.accounts.isNotEmpty() &&
                                (state.folderSelectionMode == FolderSelection.SERVER_FOLDER ||
                                    state.folderSelectionMode == FolderSelection.LOCAL_CATEGORY)
                            ) {
                                IconButton(onClick = viewModel::toggleThreadMode) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.List,
                                        contentDescription = if (state.isThreadMode) "時系列へ切替" else "スレッドへ切替",
                                    )
                                }
                            }
                            if (state.accounts.isNotEmpty()) {
                                IconButton(onClick = { viewModel.setSearchActive(!state.isSearchActive) }) {
                                    Icon(
                                        if (state.isSearchActive) Icons.Filled.Close else Icons.Filled.Search,
                                        contentDescription = "検索",
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    if (activeAccount != null) {
                                        onOpenAccountSettings(activeAccount.accountId)
                                    } else {
                                        onOpenSettings()
                                    }
                                },
                            ) {
                                Icon(
                                    Icons.Filled.Settings,
                                    contentDescription = if (activeAccount != null) {
                                        "アカウント設定・自動ルール"
                                    } else {
                                        "設定"
                                    },
                                )
                            }
                        },
                    )
                }
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                state.backfillProgress?.let { bp ->
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                            val label = if (bp.folderPath != null && bp.totalInFolder > 0) {
                                "全件同期中 (${bp.folderPath}: ${bp.syncedInFolder} / ${bp.totalInFolder}件)"
                            } else {
                                "バックグラウンド全件同期を実行中…"
                            }
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            if (bp.totalInFolder > 0) {
                                LinearProgressIndicator(
                                    progress = { (bp.syncedInFolder.toFloat() / bp.totalInFolder).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }

                state.statusMessage?.let { msg ->
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = viewModel::clearStatusMessage) {
                                Text("閉じる")
                            }
                        }
                    }
                }

                if (state.isSearchActive) {
                    SearchBarSection(
                        query = state.searchQuery,
                        isSearching = state.isSearching,
                        onQueryChange = viewModel::updateSearchQuery,
                        onInsertOperator = viewModel::appendSearchOperator,
                    )
                }

                if (!state.hasLoadedAccounts) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    return@Column
                }

                if (state.accounts.isEmpty()) {
                    EmptyAccountsState(
                        onAddAccountClick = onAddAccountClick,
                        onImportK9sClick = { k9sFilePickerLauncher.launch(arrayOf("*/*")) },
                        onImportMailArchiveClick = { archiveImportPickerLauncher.launch(arrayOf("*/*")) },
                        onOpenSettings = onOpenSettings,
                    )
                    return@Column
                }

                if (state.isSearchActive && state.searchQuery.isNotBlank()) {
                    SearchResultsList(
                        results = state.searchResults,
                        isSearching = state.isSearching,
                        showAvatars = state.showAvatars,
                        previewLines = state.previewLines,
                        isDark = isDark,
                        onMessageClick = { msg ->
                            onOpenMessage(msg.accountId, msg.id, msg.threadId, state.isThreadMode)
                        },
                        onToggleStar = { msg -> viewModel.toggleStar(msg.id, msg.isFlagged) },
                    )
                    return@Column
                }

                if (state.folderSelectionMode == FolderSelection.SERVER_FOLDER ||
                    state.folderSelectionMode == FolderSelection.LOCAL_CATEGORY
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        InboxQuickFilter.entries.forEach { filter ->
                            FilterChip(
                                selected = state.quickFilter == filter,
                                onClick = { viewModel.setQuickFilter(filter) },
                                label = { Text(filter.label) },
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }

                if ((state.folderSelectionMode == FolderSelection.SERVER_FOLDER ||
                        state.folderSelectionMode == FolderSelection.LOCAL_CATEGORY ||
                        state.folderSelectionMode == FolderSelection.LOCAL_ARCHIVE) &&
                    state.totalPages > 1
                ) {
                    CompactPaginationStrip(
                        currentPageIndex = state.currentPageIndex,
                        totalPages = state.totalPages,
                        pageSize = state.pageSize,
                        totalItems = state.totalItemsInView,
                        onPreviousPage = viewModel::goToPreviousPage,
                        onNextPage = viewModel::goToNextPage,
                    )
                }

                PullToRefreshBox(
                    isRefreshing = state.isRefreshing,
                    onRefresh = viewModel::refreshActiveAccount,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    when {
                        state.folderSelectionMode == FolderSelection.DRAFTS -> {
                            DraftsListSection(
                                drafts = state.drafts,
                                onOpenDraft = { draft -> onOpenDraft(draft.accountId, draft.draftId) },
                                onDiscardDraft = { draft -> viewModel.discardDraft(draft.draftId) },
                            )
                        }

                        state.folderSelectionMode == FolderSelection.OUTBOX -> {
                            OutboxListSection(
                                items = state.outboxItems,
                                onFlushNow = viewModel::flushAllOutboxNow,
                                onRetryItem = { item -> viewModel.retryOutboxItem(item.outboxId) },
                                onMoveBackToDraft = { item ->
                                    viewModel.moveOutboxToDraft(item.outboxId, onOpenDraft)
                                },
                            )
                        }

                        state.folderSelectionMode == FolderSelection.LOCAL_ARCHIVE -> {
                            if (!state.isPageLoaded && state.archivedPageItems.isEmpty()) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            } else if (state.archivedPageItems.isEmpty()) {
                                EmptyArchiveState()
                            } else {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(bottom = 88.dp),
                                ) {
                                    items(state.archivedPageItems, key = { it.id }) { msg ->
                                        FlatMessageRow(
                                            msg = msg,
                                            isArchiveView = true,
                                            isSelected = msg.id in state.selectedItems,
                                            showAvatars = state.showAvatars,
                                            previewLines = state.previewLines,
                                            confirmArchive = state.confirmArchive,
                                            isDark = isDark,
                                            onClick = {
                                                if (isSelectionActive) {
                                                    viewModel.toggleSelection(
                                                        SelectionKey(msg.id, msg.accountId, msg.folderId, msg.threadId),
                                                    )
                                                } else {
                                                    onOpenMessage(msg.accountId, msg.id, msg.threadId, false)
                                                }
                                            },
                                            onLongClick = {
                                                viewModel.toggleSelection(
                                                    SelectionKey(msg.id, msg.accountId, msg.folderId, msg.threadId),
                                                )
                                            },
                                            onToggleStar = { viewModel.toggleStar(msg.id, msg.isFlagged) },
                                            onArchiveAction = { viewModel.undoArchive(listOf(msg.id)) },
                                        )
                                        HorizontalDivider(
                                            modifier = Modifier.padding(start = if (state.showAvatars) 68.dp else 16.dp),
                                            thickness = 0.5.dp,
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                                        )
                                    }
                                    item(key = "archive_pagination_footer") {
                                        PaginationFooterCard(
                                            currentPageIndex = state.currentPageIndex,
                                            totalPages = state.totalPages,
                                            pageSize = state.pageSize,
                                            totalItems = state.totalItemsInView,
                                            onFirstPage = { viewModel.goToPage(0) },
                                            onPreviousPage = viewModel::goToPreviousPage,
                                            onNextPage = viewModel::goToNextPage,
                                            onLastPage = { viewModel.goToPage(state.totalPages - 1) },
                                        )
                                    }
                                }
                            }
                        }

                        state.isThreadMode -> {
                            if (!state.isPageLoaded && state.threadPageItems.isEmpty()) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            } else if (state.threadPageItems.isEmpty()) {
                                EmptyFolderState(
                                    isFiltered = state.quickFilter != InboxQuickFilter.ALL,
                                    onClearFilter = { viewModel.setQuickFilter(InboxQuickFilter.ALL) },
                                    onSyncNow = viewModel::refreshActiveAccount,
                                )
                            } else {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(bottom = 88.dp),
                                ) {
                                    items(state.threadPageItems, key = { it.threadKey }) { thread ->
                                        val selKey = SelectionKey(
                                            primaryMetaId = thread.latestMetaId,
                                            accountId = thread.accountId,
                                            folderId = thread.folderId,
                                            threadKey = thread.threadKey,
                                        )
                                        ThreadListRow(
                                            thread = thread,
                                            isSelected = thread.latestMetaId in state.selectedItems,
                                            showAvatars = state.showAvatars,
                                            previewLines = state.previewLines,
                                            confirmArchive = state.confirmArchive,
                                            isDark = isDark,
                                            onClick = {
                                                if (isSelectionActive) {
                                                    viewModel.toggleSelection(selKey)
                                                } else {
                                                    onOpenMessage(
                                                        thread.accountId,
                                                        thread.latestMetaId,
                                                        thread.threadKey,
                                                        true,
                                                    )
                                                }
                                            },
                                            onLongClick = { viewModel.toggleSelection(selKey) },
                                            onToggleStar = {
                                                viewModel.toggleStar(thread.latestMetaId, thread.hasFlagged)
                                            },
                                            onSwipeArchive = {
                                                viewModel.requestArchiveThreadInFolder(
                                                    accountId = thread.accountId,
                                                    folderId = thread.folderId,
                                                    threadKey = thread.threadKey,
                                                    fallbackMetaId = thread.latestMetaId,
                                                )
                                            },
                                        )
                                        HorizontalDivider(
                                            modifier = Modifier.padding(start = if (state.showAvatars) 68.dp else 16.dp),
                                            thickness = 0.5.dp,
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                                        )
                                    }
                                    item(key = "thread_pagination_footer") {
                                        PaginationFooterCard(
                                            currentPageIndex = state.currentPageIndex,
                                            totalPages = state.totalPages,
                                            pageSize = state.pageSize,
                                            totalItems = state.totalItemsInView,
                                            onFirstPage = { viewModel.goToPage(0) },
                                            onPreviousPage = viewModel::goToPreviousPage,
                                            onNextPage = viewModel::goToNextPage,
                                            onLastPage = { viewModel.goToPage(state.totalPages - 1) },
                                        )
                                    }
                                }
                            }
                        }

                        else -> {
                            if (!state.isPageLoaded && state.flatPageItems.isEmpty()) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            } else if (state.flatPageItems.isEmpty()) {
                                EmptyFolderState(
                                    isFiltered = state.quickFilter != InboxQuickFilter.ALL,
                                    onClearFilter = { viewModel.setQuickFilter(InboxQuickFilter.ALL) },
                                    onSyncNow = viewModel::refreshActiveAccount,
                                )
                            } else {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(bottom = 88.dp),
                                ) {
                                    items(state.flatPageItems, key = { it.id }) { msg ->
                                        val selKey = SelectionKey(
                                            primaryMetaId = msg.id,
                                            accountId = msg.accountId,
                                            folderId = msg.folderId,
                                            threadKey = null,
                                        )
                                        FlatMessageRow(
                                            msg = msg,
                                            isArchiveView = false,
                                            isSelected = msg.id in state.selectedItems,
                                            showAvatars = state.showAvatars,
                                            previewLines = state.previewLines,
                                            confirmArchive = state.confirmArchive,
                                            isDark = isDark,
                                            onClick = {
                                                if (isSelectionActive) {
                                                    viewModel.toggleSelection(selKey)
                                                } else {
                                                    onOpenMessage(msg.accountId, msg.id, msg.threadId, false)
                                                }
                                            },
                                            onLongClick = { viewModel.toggleSelection(selKey) },
                                            onToggleStar = { viewModel.toggleStar(msg.id, msg.isFlagged) },
                                            onArchiveAction = { viewModel.requestArchiveSingleMessage(msg.id) },
                                        )
                                        HorizontalDivider(
                                            modifier = Modifier.padding(start = if (state.showAvatars) 68.dp else 16.dp),
                                            thickness = 0.5.dp,
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                                        )
                                    }
                                    item(key = "flat_pagination_footer") {
                                        PaginationFooterCard(
                                            currentPageIndex = state.currentPageIndex,
                                            totalPages = state.totalPages,
                                            pageSize = state.pageSize,
                                            totalItems = state.totalItemsInView,
                                            onFirstPage = { viewModel.goToPage(0) },
                                            onPreviousPage = viewModel::goToPreviousPage,
                                            onNextPage = viewModel::goToNextPage,
                                            onLastPage = { viewModel.goToPage(state.totalPages - 1) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchBarSection(
    query: String,
    isSearching: Boolean,
    onQueryChange: (String) -> Unit,
    onInsertOperator: (String) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text("ローカル全文検索 (FTS5 trigram)") },
            placeholder = { Text("例: 請求書 from:tanaka has:attachment") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (isSearching) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                "from:" to "from:",
                "has:attachment" to "添付あり",
                "is:unread" to "未読のみ",
                "after:2025-01-01" to "after:",
                "before:2026-12-31" to "before:",
            ).forEach { (op, label) ->
                FilterChip(
                    selected = query.contains(op, ignoreCase = true),
                    onClick = { onInsertOperator(op) },
                    label = { Text(label) },
                )
            }
        }
    }
}

@Composable
private fun SearchResultsList(
    results: List<MessageMetaEntity>,
    isSearching: Boolean,
    showAvatars: Boolean,
    previewLines: Int,
    isDark: Boolean,
    onMessageClick: (MessageMetaEntity) -> Unit,
    onToggleStar: (MessageMetaEntity) -> Unit,
) {
    if (!isSearching && results.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "一致するメールが見つかりませんでした",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 88.dp),
    ) {
        item(key = "search_header") {
            Text(
                text = "検索結果: ${results.size} 件（上限200件）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        items(results, key = { it.id }) { msg ->
            val decodedSender = msg.senderName
                ?.let { Rfc2047HeaderDecoder.decodeIfNeeded(it) }
                ?.takeIf { it.isNotBlank() }
                ?: msg.senderAddress
            val decodedSubject = msg.subject
                ?.let { Rfc2047HeaderDecoder.decodeIfNeeded(it) }
                ?.takeIf { it.isNotBlank() }
                ?: "(件名なし)"
            MessageRowContent(
                sender = decodedSender,
                senderEmail = msg.senderAddress,
                subject = decodedSubject,
                snippet = msg.snippet ?: "",
                receivedAt = msg.receivedAt,
                isUnread = !msg.isRead,
                isFlagged = msg.isFlagged,
                hasAttachments = msg.hasAttachments,
                threadCount = 1,
                isSelected = false,
                showAvatars = showAvatars,
                previewLines = previewLines,
                isDark = isDark,
                onClick = { onMessageClick(msg) },
                onLongClick = {},
                onToggleStar = { onToggleStar(msg) },
            )
            HorizontalDivider(
                modifier = Modifier.padding(start = if (showAvatars) 68.dp else 16.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
private fun rememberDecodedHeader(raw: String?, fallback: String): String =
    remember(raw, fallback) {
        raw?.let { Rfc2047HeaderDecoder.decodeIfNeeded(it) }
            ?.takeIf { it.isNotBlank() }
            ?: fallback
    }

@Composable
private fun ThreadListRow(
    thread: ThreadSummaryTuple,
    isSelected: Boolean,
    showAvatars: Boolean,
    previewLines: Int,
    confirmArchive: Boolean,
    isDark: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleStar: () -> Unit,
    onSwipeArchive: () -> Unit,
) {
    SwipeToArchiveContainer(
        enabled = !isSelected,
        confirmArchive = confirmArchive,
        onArchive = onSwipeArchive,
    ) {
        val decodedSender = rememberDecodedHeader(thread.senderName, thread.senderAddress)
        val decodedSubject = rememberDecodedHeader(thread.subject, "(件名なし)")
        MessageRowContent(
            sender = decodedSender,
            senderEmail = thread.senderAddress,
            subject = decodedSubject,
            snippet = thread.snippet ?: "",
            receivedAt = thread.latestReceivedAt,
            isUnread = thread.unreadCount > 0,
            isFlagged = thread.hasFlagged,
            hasAttachments = thread.hasAttachments,
            threadCount = thread.messageCount,
            isSelected = isSelected,
            showAvatars = showAvatars,
            previewLines = previewLines,
            isDark = isDark,
            onClick = onClick,
            onLongClick = onLongClick,
            onToggleStar = onToggleStar,
        )
    }
}

@Composable
private fun FlatMessageRow(
    msg: MessageMetaEntity,
    isArchiveView: Boolean,
    isSelected: Boolean,
    showAvatars: Boolean,
    previewLines: Int,
    confirmArchive: Boolean,
    isDark: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleStar: () -> Unit,
    onArchiveAction: () -> Unit,
) {
    SwipeToArchiveContainer(
        enabled = !isArchiveView && !isSelected,
        confirmArchive = confirmArchive,
        onArchive = onArchiveAction,
    ) {
        val decodedSender = rememberDecodedHeader(msg.senderName, msg.senderAddress)
        val decodedSubject = rememberDecodedHeader(msg.subject, "(件名なし)")
        MessageRowContent(
            sender = decodedSender,
            senderEmail = msg.senderAddress,
            subject = decodedSubject,
            snippet = msg.snippet ?: "",
            receivedAt = msg.receivedAt,
            isUnread = !msg.isRead,
            isFlagged = msg.isFlagged,
            hasAttachments = msg.hasAttachments,
            threadCount = 1,
            isSelected = isSelected,
            showAvatars = showAvatars,
            previewLines = previewLines,
            isDark = isDark,
            trailingActionLabel = if (isArchiveView) "受信トレイに戻す" else null,
            onTrailingAction = if (isArchiveView) onArchiveAction else null,
            onClick = onClick,
            onLongClick = onLongClick,
            onToggleStar = onToggleStar,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToArchiveContainer(
    enabled: Boolean,
    confirmArchive: Boolean,
    onArchive: () -> Unit,
    content: @Composable () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = enabled,
        onDismiss = { value ->
            if (enabled && value == SwipeToDismissBoxValue.EndToStart) {
                if (confirmArchive) {
                    scope.launch { dismissState.snapTo(SwipeToDismissBoxValue.Settled) }
                }
                onArchive()
            }
        },
        backgroundContent = {
            val isSwipingLeft = enabled && (
                dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart ||
                    dismissState.targetValue == SwipeToDismissBoxValue.EndToStart
                )
            if (isSwipingLeft) {
                ArchiveSwipeBackground()
            } else {
                Box(modifier = Modifier.fillMaxSize())
            }
        },
        content = { content() },
    )
}

@Composable
private fun ArchiveSwipeBackground() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = KazeIcons.Archive,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "アーカイブ",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRowContent(
    sender: String,
    senderEmail: String,
    subject: String,
    snippet: String,
    receivedAt: Long,
    isUnread: Boolean,
    isFlagged: Boolean,
    hasAttachments: Boolean,
    threadCount: Int,
    isSelected: Boolean,
    showAvatars: Boolean,
    previewLines: Int,
    isDark: Boolean,
    trailingActionLabel: String? = null,
    onTrailingAction: (() -> Unit)? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleStar: () -> Unit,
) {
    val dateText = DateFormatters.formatMonthDayTime(receivedAt)
    val avatarColor = remember(senderEmail) { deterministicAvatarColor(senderEmail.ifBlank { sender }) }
    val initial = sender.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "M"

    val bgColor = when {
        isSelected -> lerp(
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.primaryContainer,
            0.55f,
        )
        isUnread -> MaterialTheme.colorScheme.surfaceContainerLow
        else -> MaterialTheme.colorScheme.background
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (showAvatars || isSelected) {
            Box(
                modifier = Modifier
                    .padding(top = 2.dp, end = 12.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) MaterialTheme.colorScheme.primary else avatarColor)
                    .clickable(onClick = onLongClick),
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "選択中",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    Text(
                        text = initial,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isUnread) {
                    Box(
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
                Text(
                    text = sender,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (isUnread) FontWeight.Bold else FontWeight.Medium,
                    color = if (isUnread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (threadCount > 1) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = CircleShape,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    ) {
                        Text(
                            text = "$threadCount",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
                Text(
                    text = dateText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isUnread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (isUnread) FontWeight.SemiBold else FontWeight.Normal,
                )
            }

            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = subject,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isUnread) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (hasAttachments) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = KazeIcons.AttachFile,
                        contentDescription = "添付あり",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }

            if (snippet.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = previewLines.coerceIn(1, 2),
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (trailingActionLabel != null && onTrailingAction != null) {
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedButton(
                    onClick = onTrailingAction,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Icon(KazeIcons.Unarchive, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(trailingActionLabel, style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        IconButton(onClick = onToggleStar, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = if (isFlagged) Icons.Filled.Star else Icons.Outlined.Star,
                contentDescription = "スター切替",
                tint = if (isFlagged) {
                    if (isDark) StarAmberDark else StarAmberLight
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                },
            )
        }
    }
}

@Composable
private fun EmptyAccountsState(
    onAddAccountClick: () -> Unit,
    onImportK9sClick: () -> Unit,
    onImportMailArchiveClick: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = KazeIcons.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "KazeMail へようこそ",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "既存メールを一切削除・損壊せず、SQLCipher暗号化と FTS5 全文検索で端末ローカルに高速蓄積します。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(20.dp))
                Button(onClick = onAddAccountClick, modifier = Modifier.fillMaxWidth()) {
                    Text("メールアカウントを追加")
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = onImportK9sClick, modifier = Modifier.fillMaxWidth()) {
                    Text("K-9 Mail (.k9s) 設定から移行")
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = onImportMailArchiveClick, modifier = Modifier.fillMaxWidth()) {
                    Text(".eml / .mbox ファイルを取り込む")
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("アプリ設定・テーマを開く")
                }
            }
        }
    }
}

@Composable
private fun EmptyFolderState(
    isFiltered: Boolean,
    onClearFilter: () -> Unit,
    onSyncNow: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = KazeIcons.Inbox,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(48.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (isFiltered) "条件に一致するメールがありません" else "このフォルダにメールがありません",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (isFiltered) {
                OutlinedButton(onClick = onClearFilter) {
                    Text("フィルタを解除")
                }
            } else {
                TextButton(onClick = onSyncNow) {
                    Text("今すぐ非破壊同期を実行")
                }
            }
        }
    }
}

@Composable
private fun EmptyArchiveState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = KazeIcons.Archive,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(48.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "ローカルアーカイブは空です",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "一覧から左スワイプすると端末内アーカイブへ退避できます",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DraftsListSection(
    drafts: List<DraftEntity>,
    onOpenDraft: (DraftEntity) -> Unit,
    onDiscardDraft: (DraftEntity) -> Unit,
) {
    var pendingDiscardDraft by remember { mutableStateOf<DraftEntity?>(null) }

    pendingDiscardDraft?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDiscardDraft = null },
            title = { Text("下書きを破棄") },
            text = {
                Text("「${target.subject.ifBlank { "(件名なし・下書き)" }}」を端末から完全に破棄しますか？")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDiscardDraft(target)
                        pendingDiscardDraft = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("破棄する")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDiscardDraft = null }) {
                    Text("キャンセル")
                }
            },
        )
    }

    if (drafts.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "保存されたローカル下書きはありません",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(drafts, key = { it.draftId }) { draft ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenDraft(draft) },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = draft.subject.ifBlank { "(件名なし・下書き)" },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "更新: ${DateFormatters.formatMonthDayTime(draft.updatedAt)} • ${draft.mode}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (draft.bodyText.isNotBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = draft.bodyText.trim().take(80),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    TextButton(onClick = { pendingDiscardDraft = draft }) {
                        Text("破棄", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun OutboxListSection(
    items: List<OutboxMessageEntity>,
    onFlushNow: () -> Unit,
    onRetryItem: (OutboxMessageEntity) -> Unit,
    onMoveBackToDraft: (OutboxMessageEntity) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "outbox_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "送信キュー / 送信履歴 (${items.size}件)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (items.any { it.status == OutboxMessageEntity.STATUS_QUEUED }) {
                    TextButton(onClick = onFlushNow) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("今すぐ送信試行")
                    }
                }
            }
        }
        if (items.isEmpty()) {
            item(key = "outbox_empty") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "送信トレイは空です",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(items, key = { it.outboxId }) { item ->
                val statusLabel = when (item.status) {
                    OutboxMessageEntity.STATUS_QUEUED -> "送信待ち（オンライン復帰で自動送信）"
                    OutboxMessageEntity.STATUS_SENDING -> "SMTP送信中…"
                    OutboxMessageEntity.STATUS_SENT -> "送信済み (${DateFormatters.formatMonthDayTime(item.sentAt ?: item.updatedAt)})"
                    OutboxMessageEntity.STATUS_FAILED -> "送信エラー（試行 ${item.attemptCount}回）"
                    else -> item.status
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = item.subject.ifBlank { "(件名なし)" },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (item.status == OutboxMessageEntity.STATUS_FAILED) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                        item.lastError?.takeIf { it.isNotBlank() }?.let { err ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (item.status != OutboxMessageEntity.STATUS_SENT && item.status != OutboxMessageEntity.STATUS_SENDING) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { onRetryItem(item) }) {
                                    Text("再送")
                                }
                                TextButton(onClick = { onMoveBackToDraft(item) }) {
                                    Text("下書きに戻す")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactPaginationStrip(
    currentPageIndex: Int,
    totalPages: Int,
    pageSize: Int,
    totalItems: Int,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
) {
    if (totalItems <= 0) return
    val rangeStart = (currentPageIndex * pageSize + 1).coerceAtMost(totalItems)
    val rangeEnd = minOf((currentPageIndex + 1) * pageSize, totalItems)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${rangeStart}–${rangeEnd}件 / 全${totalItems}件 (${currentPageIndex + 1}/${totalPages})",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = onPreviousPage,
                    enabled = currentPageIndex > 0,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                ) {
                    Text("‹ 前へ")
                }
                TextButton(
                    onClick = onNextPage,
                    enabled = currentPageIndex + 1 < totalPages,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                ) {
                    Text("次へ ›")
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

@Composable
private fun PaginationFooterCard(
    currentPageIndex: Int,
    totalPages: Int,
    pageSize: Int,
    totalItems: Int,
    onFirstPage: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onLastPage: () -> Unit,
) {
    if (totalItems <= 0) return
    val rangeStart = (currentPageIndex * pageSize + 1).coerceAtMost(totalItems)
    val rangeEnd = minOf((currentPageIndex + 1) * pageSize, totalItems)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (totalPages > 1) {
                    "${rangeStart}–${rangeEnd} 件を表示 / 全 ${totalItems} 件（${currentPageIndex + 1} / ${totalPages} ページ）"
                } else {
                    "全 ${totalItems} 件（1ページ最大 ${pageSize} 件表示）"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (totalPages > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = onFirstPage,
                        enabled = currentPageIndex > 0,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text("«")
                    }
                    OutlinedButton(
                        onClick = onPreviousPage,
                        enabled = currentPageIndex > 0,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text("‹ 前へ")
                    }
                    OutlinedButton(
                        onClick = onNextPage,
                        enabled = currentPageIndex + 1 < totalPages,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text("次へ ›")
                    }
                    OutlinedButton(
                        onClick = onLastPage,
                        enabled = currentPageIndex + 1 < totalPages,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text("»")
                    }
                }
            }
        }
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
