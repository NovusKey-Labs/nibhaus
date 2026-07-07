package com.nibhaus.ui.scan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nibhaus.pen.PenConnState
import com.nibhaus.ui.theme.G1
import com.nibhaus.ui.theme.monoData
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.nibhaus.ui.InkViewModel
import com.nibhaus.ui.common.InkAppBar
import com.nibhaus.ui.common.NibBadge
import com.nibhaus.ui.common.QuietLine
import kotlinx.coroutines.delay

/** Lists pens found over BLE (strongest signal first); tap one to connect. Scanning stays active
 *  through the connect attempt — a direct LE connect to a non-bonded pen is far more reliable while
 *  the system still sees it advertising (matches NeoStudio). */
@Composable
internal fun ScanScreen(vm: InkViewModel, onBack: () -> Unit) {
    val pens by vm.scannedPens.collectAsStateWithLifecycle()
    val pen by vm.penState.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme
    var scanAttempt by remember { mutableStateOf(0) }
    var scanTimedOut by remember { mutableStateOf(false) }
    fun beginScan() {
        scanTimedOut = false
        scanAttempt += 1
        vm.startScan()
    }
    LaunchedEffect(Unit) { beginScan() }
    DisposableEffect(Unit) { onDispose { vm.stopScan() } }
    LaunchedEffect(pen) {
        if (pen is PenConnState.Connected || pen is PenConnState.PasswordRequired) onBack()
    }
    val waitingForPens = pens.isEmpty() && pen !is PenConnState.Connecting && pen !is PenConnState.Reconnecting
    LaunchedEffect(scanAttempt, waitingForPens) {
        if (waitingForPens) {
            delay(10_000)
            scanTimedOut = true
        } else {
            scanTimedOut = false
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            InkAppBar(title = "Find a pen", sub = "${pens.size} found") {
                Button(onClick = { beginScan() }) { Text("Rescan") }
                Spacer(Modifier.size(8.dp))
                Button(onClick = onBack) { Text("Back") }
            }
        }
        when (pen) {
            is PenConnState.Connecting, is PenConnState.Reconnecting ->
                item { QuietLine("Connecting to the pen… keep it on and nearby.") }
            else ->
                if (pens.isEmpty()) {
                    item {
                        if (scanTimedOut) {
                            Column(Modifier.padding(top = 16.dp, start = 4.dp, end = 4.dp)) {
                                Text(
                                    "No pens found; check the pen is on and in range",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = cs.onSurfaceVariant,
                                )
                                Button(
                                    onClick = { beginScan() },
                                    modifier = Modifier.padding(top = 12.dp),
                                ) {
                                    Text("Try again")
                                }
                            }
                        } else {
                            Row(
                                Modifier.padding(top = 16.dp, start = 4.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = G1)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "Searching… make sure the pen is on and not connected to another device.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = cs.onSurfaceVariant,
                                )
                            }
                        }
                    }
                } else {
                    items(pens, key = { it.mac }) { p ->
                        Card(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { vm.connectPicked(p) },
                            colors = CardDefaults.cardColors(containerColor = cs.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                        ) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                NibBadge(live = false)
                                Spacer(Modifier.size(13.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(p.name, style = MaterialTheme.typography.titleMedium)
                                    Text("Nearby pen · ends in ${p.mac.takeLast(5)}", style = monoData, color = cs.onSurfaceVariant)
                                }
                                Text("${p.rssi} dBm", style = monoData, color = cs.onSurfaceVariant)
                            }
                        }
                    }
                }
        }
    }
}
