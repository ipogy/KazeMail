package com.kazemail.app.ui.dashboard

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kazemail.app.data.db.MessageMetaEntity
import com.kazemail.app.sync.ImapFolderNameFormatter
import com.kazemail.app.ui.common.DateFormatters

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onAddAccountClick: () -> Unit,
    onNavigateBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var hasNotifPermission by remember { mutableStateOf(viewModel.hasNotificationPermission()) }
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasNotifPermission = granted
        if (granted) {
            viewModel.setNotificationsEnabled(true)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("同期状態・詳細診断")
                        Text(
                            text = "ローカル蓄積: ${state.totalStoredMessages} 件 | 非破壊・暗号化DB",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    if (onNavigateBack != null) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddAccountClick,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("アカウント追加") },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            if (state.isSyncRunning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
            }

            state.statusBanner?.let { banner ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = banner,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (state.accounts.isEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "メールアカウントが未登録です",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "KazeMail は IMAP EXAMINE + BODY.PEEK による非破壊同期、SQLCipher + Keystore 暗号化、FTS5 trigram 全文検索でローカル完結動作します。",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = onAddAccountClick) {
                            Text("アカウントを追加（パスワード / OAuth2）")
                        }
                    }
                }
                return@Column
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.accounts.forEach { account ->
                    FilterChip(
                        selected = account.accountId == state.selectedAccountId,
                        onClick = { viewModel.selectAccount(account.accountId) },
                        label = { Text("${account.emailAddress} (${account.authType})") },
                    )
                }
            }

            val activeAccId = state.selectedAccountId
            if (activeAccId != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { viewModel.triggerPhase1AndBackgroundBackfill(activeAccId) },
                        enabled = !state.isSyncRunning,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("初期同期", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { viewModel.triggerIncrementalSync(activeAccId) },
                        enabled = !state.isSyncRunning,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("差分同期", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { viewModel.enqueueBackgroundBackfill(activeAccId) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("全件取得", maxLines = 1)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "新着メール通知",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = if (!hasNotifPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    "通知権限が未許可です"
                                } else {
                                    "バックグラウンド差分同期時にINBOX新着を通知"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!hasNotifPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            TextButton(
                                onClick = {
                                    notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                },
                            ) {
                                Text("権限を許可")
                            }
                        } else {
                            Switch(
                                checked = state.notificationsEnabled,
                                onCheckedChange = viewModel::setNotificationsEnabled,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "定期バックグラウンド同期",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(
                            15L to "15分",
                            30L to "30分",
                            60L to "1時間",
                            0L to "手動のみ",
                        ).forEach { (minutes, label) ->
                            FilterChip(
                                selected = state.periodicSyncIntervalMinutes == minutes,
                                onClick = { viewModel.setPeriodicSyncInterval(minutes) },
                                label = { Text(label) },
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Outbox 未送信・待機中: ${state.outboxPendingCount} 件",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        TextButton(onClick = viewModel::flushOutboxNow) {
                            Text("Outbox送信実行")
                        }
                    }
                }
            }

            if (state.folders.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.folders.forEach { item ->
                        val f = item.folder
                        val sync = item.syncState
                        val tag = when {
                            f.fullPath.startsWith("__archive__/") -> " [退避]"
                            sync?.backfillCompleted == true -> " [完了]"
                            sync?.phase1Completed == true -> " [P1済]"
                            else -> ""
                        }
                        FilterChip(
                            selected = f.folderId == state.selectedFolderId,
                            onClick = { viewModel.selectFolder(f.folderId) },
                            label = {
                                val display = ImapFolderNameFormatter.formatDisplayName(f.fullPath, f.folderName)
                                Text("$display (${f.totalCount})$tag")
                            },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = viewModel::updateSearchQuery,
                label = { Text("FTS5 ローカル検索 (例: 田中 from:example has:attachment is:unread)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))

            val displayedMessages = if (state.searchQuery.isNotBlank()) {
                state.searchResults
            } else {
                state.recentMessages
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(displayedMessages, key = { it.id }) { msg ->
                    MessageMetaRow(msg)
                }
            }
        }
    }
}

@Composable
private fun MessageMetaRow(msg: MessageMetaEntity) {
    val dateLabel = DateFormatters.formatDateTime(msg.receivedAt)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = msg.senderName?.takeIf { it.isNotBlank() } ?: msg.senderAddress,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (msg.isRead) FontWeight.Normal else FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = dateLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = msg.subject ?: "(件名なし)",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (msg.isRead) FontWeight.Normal else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!msg.snippet.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = msg.snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (msg.hasAttachments) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "添付あり (UID ${msg.uid})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
