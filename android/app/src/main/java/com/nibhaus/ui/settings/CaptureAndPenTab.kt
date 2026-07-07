package com.nibhaus.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nibhaus.pen.BatteryOptimization
import com.nibhaus.pen.OemBattery
import com.nibhaus.pen.PasswordOpState
import com.nibhaus.pen.PenConnState
import com.nibhaus.ui.ActionZoneSettingsCard
import com.nibhaus.ui.InkViewModel
import com.nibhaus.ui.NibToggle

/** Tab 0 — "Capture & Pen": background-capture health, pen unlock password management, firmware,
 *  the action-zone icon pickers, and an entry into the capture lab diagnostics screen. */
internal fun LazyListScope.captureAndPenTab(
    vm: InkViewModel,
    penState: PenConnState,
    onOpenCaptureLab: () -> Unit,
) {
    item { SectionLabel("Capture reliability") }
    item { CaptureReliabilityCard() }

    item { SectionLabel("Pen security") }
    item { PenSecurityCard(vm, penState) }

    item { SectionLabel("Firmware") }
    item { FirmwareCard(vm, penState) }

    item { SectionLabel("Page action icons") }
    item { SettingsCard { ActionZoneSettingsCard(vm) } }

    // Capture lab is diagnostics tooling (coordinate-scale measurement, raw data recording) that
    // most people never open — collapsed behind Advanced (§18) instead of always taking up space.
    item {
        AdvancedSection {
            SettingsCard {
                Column(Modifier.padding(vertical = 14.dp)) {
                    Text("Capture lab", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Measure the coordinate scale, record raw pen tracking data, or capture planner reference points from the pen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onOpenCaptureLab, modifier = Modifier.padding(top = 10.dp)) { Text("Open capture lab") }
                }
            }
        }
    }
}

/**
 * Pen unlock-password management. The password is entered once at the unlock prompt and stored
 * encrypted for silent auto-unlock; here the user can change it. Changing talks to the pen over
 * BLE, so it's only available while a pen is connected.
 */
@Composable
private fun PenSecurityCard(vm: InkViewModel, penState: PenConnState) {
    val cs = MaterialTheme.colorScheme
    val connected = penState is PenConnState.Connected
    var showChange by remember { mutableStateOf(false) }

    val rememberOn by vm.rememberPassword.collectAsStateWithLifecycle()

    SettingsCard {
        Column(Modifier.padding(vertical = 14.dp)) {
            // The remember toggle: off (default) = ask every connect; on = save encrypted for 30 days.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Remember password 30 days", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (rememberOn) "Skips the password prompt for 30 days. It's saved encrypted on this device."
                        else "Off. You'll be asked for the password each time the pen connects.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
                NibToggle(checked = rememberOn, onCheckedChange = vm::setRememberPassword)
            }

            Spacer(Modifier.height(8.dp))
            Text("Unlock password", style = MaterialTheme.typography.titleMedium)
            Text(
                // The pen has no "remove password" command (only a destructive factory reset clears
                // it), so we offer Set/Change but not a Disable — see PenBleSdk.disablePassword.
                "Set a password on a new pen, or change an existing one.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
            if (connected) {
                Button(
                    onClick = { showChange = true },
                    modifier = Modifier.padding(top = 12.dp),
                ) { Text("Set / change password") }
            } else {
                Text(
                    "Connect your pen to change its password.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }

    if (showChange) {
        PasswordOpDialog(
            title = "Set or change pen password",
            // Like Neo Studio 2, we don't re-ask for the current password — the driver reuses the one
            // that unlocked this session (or the pen's blank-default "0000" on a fresh pen). Pen
            // passwords are exactly 4 digits.
            fields = listOf("New password (4 digits)", "Confirm new password"),
            passwordOp = vm.passwordOp,
            onAcknowledge = vm::acknowledgePasswordOp,
            onDismiss = { showChange = false },
            validate = { f -> when {
                f[0].length != 4 || !f[0].all(Char::isDigit) -> "Password must be exactly 4 digits."
                f[0] != f[1] -> "Passwords don't match."
                else -> null
            } },
            onSubmit = { f -> vm.changePenPassword(f[0]) },
        )
    }
}

/**
 * Firmware: a read-only version readout. There's no in-app update path — the pen makers (NeoLAB,
 * LAMY) ship firmware updates through their own apps and don't expose an update file or API we could
 * drive from here, and we have no trusted source to say whether a version is current. So this only
 * shows what's on the pen and points the user to the maker's app for anything beyond that.
 */
@Composable
private fun FirmwareCard(vm: InkViewModel, penState: PenConnState) {
    val cs = MaterialTheme.colorScheme
    val connected = penState is PenConnState.Connected
    val version by vm.firmwareVersion.collectAsStateWithLifecycle()

    SettingsCard {
        Column(Modifier.padding(vertical = 14.dp)) {
            InlineField("VERSION", if (connected) version ?: "reading…" else "connect pen to read") {}
            Text(
                "To update your pen's firmware, use the pen maker's official app.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/**
 * A small dialog of masked numeric password [fields] that runs one pen [onSubmit] and reflects the
 * [passwordOp] result: closes on success, shows the pen's rejection inline on failure.
 */
@Composable
private fun PasswordOpDialog(
    title: String,
    fields: List<String>,
    passwordOp: kotlinx.coroutines.flow.StateFlow<PasswordOpState>,
    onAcknowledge: () -> Unit,
    onDismiss: () -> Unit,
    validate: (List<String>) -> String?,
    onSubmit: (List<String>) -> Unit,
) {
    val values = remember { mutableStateListOf(*Array(fields.size) { "" }) }
    var error by remember { mutableStateOf<String?>(null) }
    val op by passwordOp.collectAsStateWithLifecycle()
    val working = op is PasswordOpState.Working

    // React to the pen's answer: success closes the dialog, failure shows why and lets them retry.
    LaunchedEffect(op) {
        val done = op as? PasswordOpState.Done ?: return@LaunchedEffect
        if (done.success) onDismiss() else error =
            "The pen didn't accept it. Reconnect the pen and enter its current password when asked, then try again."
        onAcknowledge()
    }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column {
                fields.forEachIndexed { i, label ->
                    OutlinedTextField(
                        value = values[i],
                        // Pen passwords are exactly 4 digits — cap input so it can't overrun.
                        onValueChange = { v -> values[i] = v.filter(Char::isDigit).take(4); error = null },
                        label = { Text(label) },
                        singleLine = true,
                        enabled = !working,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                }
                if (working) Text("Talking to the pen…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            Button(
                enabled = !working,
                onClick = {
                    val v = values.toList()
                    val msg = validate(v)
                    if (msg != null) error = msg else onSubmit(v)
                },
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(enabled = !working, onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Capture reliability (brief FIX #2). The pen foreground service keeps capture alive in the
 * background, but the OS can still throttle or kill it unless the app is battery-exempt — One UI is
 * the most aggressive. Surface the exemption status, route to the OS setting in one tap, and (on
 * Samsung) name the "Never sleeping apps" path. Re-checks on resume so the status refreshes when the
 * user returns from settings. See [BatteryOptimization].
 */
@Composable
private fun CaptureReliabilityCard() {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var ignoring by remember { mutableStateOf(BatteryOptimization.isIgnoring(context)) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) ignoring = BatteryOptimization.isIgnoring(context)
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
    SettingsCard {
        Column(Modifier.padding(vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (ignoring) Icons.Outlined.CheckCircle else Icons.Outlined.WarningAmber,
                    contentDescription = null,
                    tint = if (ignoring) cs.primary else cs.error,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    if (ignoring) "Your pen stays connected in the background" else "Your pen may disconnect in the background",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(
                if (ignoring) {
                    "Pages keep saving even while you're in another app. Nibhaus is exempt from " +
                        "battery optimization."
                } else {
                    "Your device can pause Nibhaus when you switch apps, dropping the pen and pausing " +
                        "capture until you reopen it. Allow background activity to keep it connected."
                },
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (ignoring) {
                OutlinedButton(
                    onClick = { BatteryOptimization.openSettings(context) },
                    modifier = Modifier.padding(top = 12.dp),
                ) { Text("Battery settings") }
            } else {
                Button(
                    onClick = {
                        // Prefer the direct OS "Allow" consent dialog; fall back to the battery
                        // Settings screen on devices that don't offer it.
                        val direct = OemBattery.settingsIntent(context)
                        if (direct == null || runCatching { context.startActivity(direct) }.isFailure) {
                            BatteryOptimization.openSettings(context)
                        }
                    },
                    modifier = Modifier.padding(top = 12.dp),
                ) { Text("Allow background capture") }
            }
            if (samsung) {
                Text(
                    "One UI also has \"Never sleeping apps\": Settings → Battery → Background " +
                        "usage limits → Never sleeping apps → add Nibhaus.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

