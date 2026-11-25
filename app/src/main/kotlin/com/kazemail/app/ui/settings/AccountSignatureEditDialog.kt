package com.kazemail.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kazemail.app.ui.viewer.HtmlEmailDetector

@Composable
fun AccountSignatureEditDialog(
    title: String,
    initialEnabled: Boolean,
    initialText: String,
    inheritedFromLegacyGlobal: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (enabled: Boolean, text: String) -> Unit,
) {
    var draftEnabled by remember(title, initialEnabled) { mutableStateOf(initialEnabled) }
    var draftText by remember(title, initialText) { mutableStateOf(initialText) }
    val containsHtml = remember(draftText) { HtmlEmailDetector.containsActualHtml(draftText) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "作成・AI生成時にこの署名を自動挿入",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = draftEnabled, onCheckedChange = { draftEnabled = it })
                }
                if (inheritedFromLegacyGlobal) {
                    Text(
                        text = "※ 現在は以前のアプリ共通設定を引き継いでいます。保存するとこのメールアドレス専用の設定になります。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = draftText,
                    onValueChange = { draftText = it },
                    label = { Text("署名（テキスト / HTMLタグ可）") },
                    placeholder = {
                        Text("例:\n株式会社〇〇 山田太郎\nまたは <b>株式会社〇〇</b><br>yamada@example.com")
                    },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (containsHtml) {
                    Text(
                        text = "HTML署名として認識されています（HTML作成時はそのまま装飾付き、テキスト作成時は自動整形して挿入されます）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(draftEnabled, draftText)
                },
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        },
    )
}
