package com.kazemail.app.ui.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kazemail.app.ai.AiReferenceEmailSummary
import com.kazemail.app.ui.common.DateFormatters
import com.kazemail.app.ui.theme.KazeIcons

data class AiAttachableAttachmentCandidate(
    val id: String,
    val label: String,
    val isDownloading: Boolean = false,
    val isAlreadyAdded: Boolean = false,
)

@Composable
fun AiReferenceEmailPickerDialog(
    candidates: List<AiReferenceEmailSummary>,
    selectedMetaIds: Set<Long>,
    searchQuery: String,
    isLoading: Boolean,
    maxSelections: Int,
    onSearchQueryChange: (String) -> Unit,
    onToggleSelect: (AiReferenceEmailSummary) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("AIに見せる別のメールを選択")
                Text(
                    text = "選択中 ${selectedMetaIds.size}/${maxSelections}通（他メールの見積・仕様などを参照させられます）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    label = { Text("件名・差出人・本文で検索") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isLoading) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("メールを検索中…", style = MaterialTheme.typography.bodySmall)
                    }
                } else if (candidates.isEmpty()) {
                    Text(
                        text = if (searchQuery.isBlank()) {
                            "参照できる同期済みメールがありません"
                        } else {
                            "「$searchQuery」に一致するメールが見つかりませんでした"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp, max = 340.dp),
                    ) {
                        items(candidates, key = { it.metaId }) { email ->
                            val isSelected = email.metaId in selectedMetaIds
                            val canToggle = isSelected || selectedMetaIds.size < maxSelections
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = canToggle) { onToggleSelect(email) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Checkbox(
                                    checked = isSelected,
                                    enabled = canToggle,
                                    onCheckedChange = { if (canToggle) onToggleSelect(email) },
                                )
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(top = 4.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = email.senderLabel,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (email.hasAttachments) {
                                            Icon(
                                                imageVector = KazeIcons.AttachFile,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(14.dp),
                                            )
                                        }
                                        if (email.receivedAt > 0L) {
                                            Text(
                                                text = DateFormatters.formatMonthDayTime(email.receivedAt),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(start = 4.dp),
                                            )
                                        }
                                    }
                                    Text(
                                        text = email.subject,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (email.snippet.isNotBlank()) {
                                        Text(
                                            text = email.snippet,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("完了")
            }
        },
    )
}
