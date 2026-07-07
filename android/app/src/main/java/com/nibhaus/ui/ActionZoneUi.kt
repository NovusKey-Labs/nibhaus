package com.nibhaus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nibhaus.pen.PenConnState
import com.nibhaus.zones.ZoneAction
import kotlinx.coroutines.launch

/**
 * "Tap-to-teach" the physical action icons printed on the page (e.g. the top-right Share/Email).
 * Tap an icon with the pen to capture its Ncode spot, bind it to an action, and from then on tapping
 * that printed icon triggers the action on the current page.
 */
@Composable
fun ActionZoneSettingsCard(vm: InkViewModel) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val zones by vm.zones.collectAsStateWithLifecycle()
    val pen by vm.penState.collectAsStateWithLifecycle()
    val connected = pen is PenConnState.Connected
    val attachAudio by vm.attachAudioOnShare.collectAsStateWithLifecycle()

    var waiting by remember { mutableStateOf(false) }
    // The traced box [left, top, right, bottom] awaiting an action binding.
    var captured by remember { mutableStateOf<List<Float>?>(null) }
    var capturedBook by remember { mutableStateOf(0) }

    Column(Modifier.padding(vertical = 14.dp)) {
        Column(Modifier.riseIn(0)) {
            Text("Page action icons", style = MaterialTheme.typography.titleMedium)
            Text(
                "Circle a printed icon (e.g. the Share/Email at the top-right) with the pen to bind it.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp).riseIn(1),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text("Attach voice note when sharing", style = MaterialTheme.typography.bodyMedium)
                Text("Adds the page's audio to a Share (not Email).", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Switch(checked = attachAudio, onCheckedChange = vm::setAttachAudioOnShare)
        }

        zones.forEachIndexed { idx, z ->
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp).riseIn(minOf(2 + idx, 4)),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(z.action.label, style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { vm.removeZone(z.id) }) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Remove zone", tint = cs.onSurfaceVariant)
                }
            }
        }

        if (connected) {
            OutlinedButton(
                onClick = {
                    waiting = true
                    vm.calibrateNextTrace { book, l, t, r, b -> scope.launch { capturedBook = book; captured = listOf(l, t, r, b); waiting = false } }
                },
                modifier = Modifier.padding(top = 10.dp).riseIn(4),
            ) { Text("Add zone (circle an icon)") }
        } else {
            Text(
                "Connect your pen to calibrate a zone.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp).riseIn(4),
            )
        }
    }

    if (waiting) {
        AlertDialog(
            onDismissRequest = { waiting = false; vm.cancelCalibration() },
            title = { Text("Circle the printed icon", style = MaterialTheme.typography.headlineSmall) },
            text = { Text("With the pen connected, draw a circle around the printed action icon on your page.") },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { waiting = false; vm.cancelCalibration() }) { Text("Cancel") } },
        )
    }

    captured?.let { box ->
        AlertDialog(
            onDismissRequest = { captured = null },
            title = { Text("Bind this icon to…", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Column {
                    ZoneAction.entries.forEach { action ->
                        Button(
                            onClick = { vm.addZone(action, capturedBook, box[0], box[1], box[2], box[3]); captured = null },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        ) { Text(action.label) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { captured = null }) { Text("Cancel") } },
        )
    }
}
