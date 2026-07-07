package com.nibhaus.ui.home

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.nibhaus.ui.common.rememberHaptics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nibhaus.health.ConnectionDiagnostic
import com.nibhaus.health.SyncTargetState
import com.nibhaus.pen.PenConnState
import com.nibhaus.ui.theme.G1
import com.nibhaus.ui.theme.monoData
import com.nibhaus.ui.theme.monoEyebrow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.nibhaus.ui.Eyebrow
import com.nibhaus.ui.InkViewModel
import com.nibhaus.ui.PenLinks
import com.nibhaus.ui.StatusChip
import com.nibhaus.ui.NibEasing
import com.nibhaus.ui.common.BatteryBadge
import com.nibhaus.ui.common.BrandWordmark
import com.nibhaus.ui.common.EmptyState
import com.nibhaus.ui.common.InkAppBar
import com.nibhaus.ui.common.NibBadge
import com.nibhaus.ui.riseIn
import com.nibhaus.ui.steelCard
import kotlinx.coroutines.delay

/** Pen-status card (design-system §9): nib badge + name + mono status tag + battery. */
@Composable
internal fun PenStatusCard(
    vm: InkViewModel,
    pen: PenConnState,
    onScan: () -> Unit,
    onFindMyPen: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val battery by vm.battery.collectAsStateWithLifecycle()
    // Feature 20a/21: the pen's false→true connect edge drives three things off the same detection —
    // a confirm tick, a brief NibBadge glow-in, and a transient "Welcome back" status line.
    val haptics = rememberHaptics()
    val isConnected = pen is PenConnState.Connected
    var wasConnected by remember { mutableStateOf(isConnected) }
    var justConnected by remember { mutableStateOf(false) }
    LaunchedEffect(isConnected) {
        if (isConnected && !wasConnected) {
            haptics.confirm()
            justConnected = true
            delay(3_000)
            justConnected = false
        }
        wasConnected = isConnected
    }
    // Feature 21: the badge's live glow eases in over ~600ms on the connect moment; NibBadge's own
    // always-on pulse takes over once this settles at full alpha.
    val connectGlow by animateFloatAsState(
        targetValue = if (isConnected) 1f else 0f,
        animationSpec = tween(600, easing = NibEasing),
        label = "connectGlow",
    )
    data class Look(val live: Boolean, val name: String, val tag: String, val tagColor: Color, val sub: String, val onTap: (() -> Unit)?)
    val l = when (pen) {
        is PenConnState.Connected -> Look(true, pen.penName.ifBlank { "Smartpen" }, "CONNECTED", cs.primary, "Receiving ink", null)
        is PenConnState.Connecting -> Look(false, "Smartpen", "CONNECTING…", cs.onSurfaceVariant, "Hold the pen on and nearby", null)
        is PenConnState.Reconnecting -> Look(false, "Smartpen", "RECONNECTING (${pen.attempt})", cs.tertiary, "Connection lost, retrying", null)
        is PenConnState.PasswordRequired -> Look(false, "Smartpen", "LOCKED", cs.tertiary, "Enter the pen password", null)
        is PenConnState.BondedElsewhere -> Look(false, "Smartpen", "PAIRED ELSEWHERE", cs.error, "Release it to take over", { vm.takeOver(pen.mac) })
        is PenConnState.Disconnected -> Look(false, "No pen", "TAP TO CONNECT", cs.onSurfaceVariant, "Find a pen over Bluetooth", onScan)
    }
    Box(
        Modifier.fillMaxWidth().padding(top = 4.dp)
            .let { if (l.onTap != null) it.clickable { l.onTap!!() } else it }
            .steelCard(radius = 18.dp)
    ) {
        Column {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(contentAlignment = Alignment.Center) {
                    // Feature 21: a soft radial glow behind the badge, fading in on connect.
                    if (connectGlow > 0f) {
                        Box(
                            Modifier.size(52.dp)
                                .graphicsLayer { alpha = connectGlow }
                                .background(
                                    Brush.radialGradient(listOf(cs.tertiary.copy(alpha = 0.55f), Color.Transparent)),
                                    CircleShape,
                                ),
                        )
                    }
                    NibBadge(live = l.live)
                }
                Spacer(Modifier.size(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(l.name, style = MaterialTheme.typography.titleMedium)
                    if (l.live && justConnected) {
                        // Feature 21: a warm greeting stands in for the chip for ~3s, then settles.
                        Text("Welcome back, ${l.name}", style = monoData, color = cs.primary)
                    } else if (l.live) {
                        StatusChip(l.tag, Modifier.padding(top = 2.dp, bottom = 2.dp))
                    } else {
                        Text(l.tag, style = monoData, color = l.tagColor)
                    }
                    Text(l.sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                val bat = battery
                if (l.live && bat != null) BatteryBadge(bat)
                else Text(if (l.live) "•" else "—", style = monoData, color = cs.onSurfaceVariant)
            }
            val context = LocalContext.current
            val penName = (pen as? PenConnState.Connected)?.penName
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Opens the manufacturer's official page for the detected pen (user-initiated link).
                if (penName != null) {
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(com.nibhaus.pen.PenLinks.officialUrl(penName))),
                            )
                        }
                    }) { Text("Official page ↗") }
                } else if (pen is PenConnState.Disconnected) {
                    // Feature 24: subtle "buy a pen" nudge — only in the no-pen empty state.
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(PenLinks.LAMY_ANDROID)),
                            )
                        }
                    }) {
                        Text(
                            "Don't have a pen? Get the LAMY Neo →",
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                } else {
                    Spacer(Modifier.width(1.dp))
                }
                // Find my pen — radar/route/pin screen (connected only; unobtrusive icon)
                if (pen is PenConnState.Connected) {
                    IconButton(onClick = onFindMyPen) {
                        Icon(
                            imageVector = Icons.Outlined.MyLocation,
                            contentDescription = "Find my pen",
                            tint = cs.onSurfaceVariant,
                        )
                    }
                }
                // Disconnect drops the link + stops auto-reconnect — only rendered when there's a
                // link to sever (Connected) or an attempt in flight (Connecting/Reconnecting). The
                // Disconnected card has nothing to disconnect, so the action is omitted, not disabled.
                if (pen is PenConnState.Connected || pen is PenConnState.Connecting || pen is PenConnState.Reconnecting) {
                    TextButton(onClick = { vm.disconnect() }) {
                        Text("Disconnect")
                    }
                }
            }
        }
    }
}

/**
 * One glanceable backup state from the outbox. "All backed up" once drained; while pending, the copy
 * depends on WHY it's stuck ([SyncTargetState]) instead of just growing a silent counter — a missing
 * sync folder/endpoint says so and routes to Settings → Sync; [SyncTargetState.LOCAL_ONLY] says the
 * backlog is expected (sync is intentionally off) rather than reading as an error. The normal
 * draining case ([SyncTargetState.CONFIGURED]) keeps the original copy and taps through to Activity.
 */
private data class SyncCardLook(
    val title: String,
    val sub: String,
    val icon: ImageVector,
    val tint: Color,
    val onTap: () -> Unit,
)

@Composable
internal fun SyncStatusCard(vm: InkViewModel, onOpenActivity: () -> Unit, onOpenSyncSettings: () -> Unit) {
    val pending by vm.pendingUploads.collectAsStateWithLifecycle()
    val targetState by vm.syncTargetState.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme
    val backed = pending == 0
    // Feature 22: the displayed count ticks up on first composition; the branching above stays on
    // the real (unanimated) `pending` value so state transitions aren't delayed by the animation.
    val animatedPending = com.nibhaus.ui.common.rememberCountUp(pending)

    val look = when {
        backed -> SyncCardLook(
            "All backed up", "Every stroke saved on device & synced",
            Icons.Outlined.CloudDone, cs.primary, onOpenActivity,
        )
        targetState == SyncTargetState.CONFIGURED -> SyncCardLook(
            "$animatedPending pending", "Tap to see what's still queued",
            Icons.Outlined.CloudUpload, cs.tertiary, onOpenActivity,
        )
        targetState == SyncTargetState.NO_FOLDER -> SyncCardLook(
            "$animatedPending queued", "No sync folder set. Choose one in Settings.",
            Icons.Outlined.CloudOff, cs.error, onOpenSyncSettings,
        )
        targetState == SyncTargetState.NO_ENDPOINT -> SyncCardLook(
            "$animatedPending queued", "No sync server set. Add one in Settings.",
            Icons.Outlined.CloudOff, cs.error, onOpenSyncSettings,
        )
        targetState == SyncTargetState.NOT_ENTITLED -> SyncCardLook(
            "$animatedPending queued",
            "Syncing straight to your own server is a planned Premium feature and is not available yet.",
            Icons.Outlined.CloudOff, cs.onSurfaceVariant, onOpenSyncSettings,
        )
        else -> SyncCardLook( // LOCAL_ONLY: benign by design — styled calm, not alarming.
            "$animatedPending stored locally", "Sync is off",
            Icons.Outlined.CloudOff, cs.onSurfaceVariant, onOpenSyncSettings,
        )
    }
    Box(
        Modifier.fillMaxWidth().padding(top = 8.dp).clickable { look.onTap() }.steelCard(radius = 18.dp)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(look.icon, contentDescription = null, tint = look.tint)
            Spacer(Modifier.size(13.dp))
            Column(Modifier.weight(1f)) {
                Text(look.title, style = MaterialTheme.typography.titleMedium)
                Text(look.sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Text(if (backed) "✓" else "$animatedPending", style = monoData, color = look.tint)
        }
    }
}

/**
 * Crash capture next-launch prompt (user-initiated feedback, part 2): the [com.nibhaus.ui.common
 * .TipCard] idiom, repurposed for a crash instead of a discovered feature — the whole row opens the
 * feedback screen for review, the trailing X just acknowledges (dismiss = acknowledge, no snooze).
 */
@Composable
internal fun CrashReportCard(modifier: Modifier = Modifier, onReview: () -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .steelCard(radius = 14.dp)
            .clickable { onReview() }
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = cs.error)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Eyebrow("LAST LAUNCH")
            Text(
                "Nibhaus crashed last time. Review the report?",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Outlined.Close, contentDescription = "Dismiss crash report", tint = cs.onSurfaceVariant)
        }
    }
}

/**
 * "Check connection" (Phase A): walks the BLE chain and tells you the first thing to fix. Gathers
 * a [ConnectionDiagnostic.Probe] from Android (Bluetooth + permissions) and app state (pen state,
 * live ink signals), then renders each step pass/fail/skipped with the fix for the first failure.
 */
@Composable
internal fun ConnectionDiagnosticScreen(vm: InkViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val pen by vm.penState.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme
    var steps by remember { mutableStateOf(ConnectionDiagnostic.run(buildConnectionProbe(context, pen, vm))) }
    fun recheck() { steps = ConnectionDiagnostic.run(buildConnectionProbe(context, pen, vm)) }
    // Re-evaluate live so writing a stroke (or fixing Bluetooth) flips its step while the screen is open.
    LaunchedEffect(pen) { while (true) { recheck(); kotlinx.coroutines.delay(1_200) } }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onBack) { Text("Back") }
            Text("CHECK CONNECTION", style = monoEyebrow, color = cs.onSurfaceVariant)
            Button(onClick = { recheck() }) { Text("Re-check") }
        }
        val firstFail = ConnectionDiagnostic.firstFailure(steps)
        LazyColumn(Modifier.fillMaxSize()) {
            items(steps, key = { it.name }) { step ->
                DiagnosticStepCard(step, isFirstFail = step == firstFail)
            }
        }
    }
}

/**
 * One row of the "Check connection" chain: a pass/fail/skipped glyph, the step name, and — for
 * anything short of PASS — the fix. Pulled out of [ConnectionDiagnosticScreen] as a small, fully
 * stateless card so it (unlike the screen, which needs a live [InkViewModel] + [android.content
 * .Context]) is directly previewable with sample [ConnectionDiagnostic.Step] data.
 */
@Composable
internal fun DiagnosticStepCard(step: ConnectionDiagnostic.Step, isFirstFail: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier.fillMaxWidth().padding(vertical = 5.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isFirstFail) 3.dp else 1.dp),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            val (glyph, tint) = when (step.status) {
                ConnectionDiagnostic.Status.PASS -> "✓" to cs.primary
                ConnectionDiagnostic.Status.FAIL -> "✕" to cs.error
                ConnectionDiagnostic.Status.SKIPPED -> "·" to cs.onSurfaceVariant
            }
            Text(glyph, style = MaterialTheme.typography.titleMedium, color = tint)
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(step.name, style = MaterialTheme.typography.titleSmall)
                if (step.status != ConnectionDiagnostic.Status.PASS) {
                    Text(step.detail, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

/** Gather the diagnostic probe from Android + app state. */
private fun buildConnectionProbe(
    context: android.content.Context,
    pen: PenConnState,
    vm: InkViewModel,
): ConnectionDiagnostic.Probe {
    fun granted(p: String) = context.checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED
    val s = Build.VERSION.SDK_INT
    val bluetoothOn =
        (context.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)
            ?.adapter?.isEnabled == true
    val scanPerm = if (s >= Build.VERSION_CODES.S) granted(android.Manifest.permission.BLUETOOTH_SCAN)
        else granted(android.Manifest.permission.ACCESS_FINE_LOCATION)
    val connectPerm = if (s >= Build.VERSION_CODES.S) granted(android.Manifest.permission.BLUETOOTH_CONNECT) else true
    val connected = pen is PenConnState.Connected
    val receiving = vm.receivingInk()
    return ConnectionDiagnostic.Probe(
        bluetoothOn = bluetoothOn,
        scanPermission = scanPerm,
        connectPermission = connectPerm,
        penPoweredOrConnecting = pen !is PenConnState.Disconnected,
        gattConnected = connected,
        authorized = connected, // our flow only emits Connected after the password is accepted
        receivingDots = receiving,
        paperRecognized = receiving && vm.paperRecognized(),
    )
}

@Composable
internal fun ActivityScreen(vm: InkViewModel) {
    val pending by vm.pendingUploads.collectAsStateWithLifecycle()
    val notebookPageCounts by vm.notebookPageCounts.collectAsStateWithLifecycle()
    val everCaptured = notebookPageCounts.values.any { it > 0 }
    val cs = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item { BrandWordmark(Modifier.padding(top = 14.dp, bottom = 2.dp).riseIn(0)) }
        item { Box(Modifier.riseIn(1)) { InkAppBar(title = "Activity") } }
        if (!everCaptured) {
            // Feature 4: nothing has ever synced because nothing's ever been written — the sync
            // status card below would otherwise misleadingly read "all strokes saved" for zero strokes.
            item { EmptyState(Icons.Outlined.Draw, "Write a page and it lands here.", Modifier.riseIn(2)) }
        } else {
            item {
                Eyebrow(
                    "Sync",
                    Modifier.padding(start = 4.dp, top = 18.dp, bottom = 10.dp).riseIn(2),
                )
            }
            item {
                // Feature 22: the pending count ticks up on first composition; branching above stays
                // on the real (unanimated) `pending` value so it isn't delayed by the animation.
                val animatedPending = com.nibhaus.ui.common.rememberCountUp(pending)
                Box(Modifier.fillMaxWidth().riseIn(3).steelCard(radius = 18.dp)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        // While pages are still queued, the spinner shows sync is in progress.
                        if (pending > 0) {
                            CircularProgressIndicator(modifier = Modifier.size(30.dp), strokeWidth = 2.dp, color = G1)
                            Spacer(Modifier.width(13.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (pending == 0) "All strokes saved on device" else "Securing pages",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            if (pending > 0) {
                                Text("$animatedPending pending · encrypting locally", style = monoData, color = cs.onSurfaceVariant)
                            }
                        }
                        Text(
                            if (pending == 0) "✓ synced" else "$animatedPending",
                            style = monoData,
                            color = if (pending == 0) cs.primary else cs.tertiary,
                        )
                    }
                }
            }
        }
    }
}

// ---- Page detail + ink (mock #4) ----
