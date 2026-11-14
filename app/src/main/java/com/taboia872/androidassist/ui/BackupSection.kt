package com.taboia872.androidassist.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.taboia872.androidassist.backup.BackupStore
import com.taboia872.androidassist.i18n.Strings

/**
 * BACKUP section — the LAST section of the System tab (user request).
 *
 * Fractional backup: each kind is its own file via SAF. The two icons sit
 * side by side and their ORDER matters (user report — they were swapped):
 *
 *   LEFT  (Upload, arrow UP)    = IMPORT — load a backup file INTO the app
 *   RIGHT (Download, arrow DOWN) = EXPORT — write the app data OUT to a file
 *
 * The export file name carries the kind + a timestamp, so every backup is a
 * separate, recognizable JSON.
 */
@Composable
fun BackupSection(
    onExport: (kind: String, fileName: String) -> Unit,
    onImport: () -> Unit
) {
    SettingsSection(title = Strings.t(Strings.backupSection)) {
        listOf(
            BackupStore.KIND_PERSONAS to Strings.t(Strings.backupPersonas),
            BackupStore.KIND_MEMORIES to Strings.t(Strings.backupMemories),
            BackupStore.KIND_KEYS to Strings.t(Strings.backupKeys),
            BackupStore.KIND_CHATS to Strings.t(Strings.backupChats)
        ).forEach { (kind, label) ->
            // date stamp computed once (remember in a loop is fragile)
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm")
                .format(java.util.Date())
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                // LEFT: import (arrow UP) — restore a backup into the app
                IconButton(onClick = onImport) {
                    Icon(
                        Icons.Default.Upload,
                        contentDescription = Strings.t(Strings.backupImport),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // RIGHT: export (arrow DOWN) — make the backup file
                IconButton(onClick = {
                    onExport(kind, "androidassist-backup-$kind-$stamp.json")
                }) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = Strings.t(Strings.backupExport),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
