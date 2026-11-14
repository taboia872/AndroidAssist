package com.taboia872.androidassist.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.taboia872.androidassist.i18n.Strings

/**
 * Generic number picker used by the numeric settings (tool calls per turn,
 * context turns, silence timeout). Options are rendered as a radio list;
 * an option may carry its own label (for "0 = everything").
 */
@Composable
private fun NumberPickerDialog(
    title: String,
    options: List<Pair<Int, String>>,
    current: Int,
    onSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelected(value) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = current == value, onClick = { onSelected(value) })
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(Strings.t(Strings.cancel)) }
        }
    )
}

/**
 * Tool calls per turn: 1..8 (the hard ceiling the app enforces).
 */
@Composable
private fun MaxToolCallsDialog(
    current: Int,
    onSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    NumberPickerDialog(
        title = Strings.t(Strings.maxToolCallsLabel),
        options = (1..8).map { it to it.toString() },
        current = current,
        onSelected = onSelected,
        onDismiss = onDismiss
    )
}

/**
 * Context turns kept in the request: 0 (everything) or 2..20 turns.
 */
@Composable
private fun MaxContextTurnsDialog(
    current: Int,
    onSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val options = mutableListOf(0 to Strings.t(Strings.allTurns))
    (2..20 step 2).forEach { n -> options.add(n to n.toString()) }
    NumberPickerDialog(
        title = Strings.t(Strings.maxContextTurnsLabel),
        options = options,
        current = current,
        onSelected = onSelected,
        onDismiss = onDismiss
    )
}

/**
 * Silence window that ends the voice capture: 0.5s..5s.
 * Lower = the recording stops sooner (noisy rooms where the pause is
 * hard to detect); higher = waits longer for the user to finish.
 */
@Composable
private fun VoiceSilenceDialog(
    current: Int,
    onSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(500, 750, 1000, 1500, 2000, 2500, 3000, 4000, 5000)
        .map { ms -> ms to (ms / 1000.0).toString().replace(".0", "") + " " + Strings.t(Strings.secondsShort) }
    NumberPickerDialog(
        title = Strings.t(Strings.voiceSilenceLabel),
        options = options,
        current = current,
        onSelected = onSelected,
        onDismiss = onDismiss
    )
}
