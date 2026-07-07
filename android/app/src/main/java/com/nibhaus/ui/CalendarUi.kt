package com.nibhaus.ui

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nibhaus.calendar.CalendarTarget
import kotlinx.coroutines.launch
import java.util.Calendar

private val CAL_PERMS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

private fun hasCalendarPerms(context: android.content.Context) =
    CAL_PERMS.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

/** Settings card: pick the device calendar that new events are added to. */
@Composable
fun CalendarSettingsCard(vm: InkViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    val targetId by vm.calendarTargetId.collectAsStateWithLifecycle()
    var calendars by remember { mutableStateOf<List<CalendarTarget>>(emptyList()) }
    var menu by remember { mutableStateOf(false) }

    fun load() = scope.launch { calendars = vm.writableCalendars() }
    val askPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) { load(); menu = true }
    }
    LaunchedEffect(Unit) { if (hasCalendarPerms(context)) load() }

    val current = calendars.firstOrNull { it.id == targetId }
    Column(Modifier.padding(vertical = 14.dp)) {
        Text("Add events to", style = MaterialTheme.typography.titleMedium)
        Text(
            current?.let { "${it.displayName} · ${it.account}" }
                ?: if (targetId >= 0) "Selected calendar (#$targetId)" else "No calendar chosen yet.",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
        )
        Text(
            "Uses a Google or Outlook account already synced on this device. No sign-in needed here.",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Row(Modifier.padding(top = 10.dp)) {
            OutlinedButton(onClick = {
                if (hasCalendarPerms(context)) { load(); menu = true } else askPerms.launch(CAL_PERMS)
            }) { Text("Choose calendar") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (calendars.isEmpty()) {
                    DropdownMenuItem(text = { Text("No writable calendars found") }, onClick = { menu = false })
                }
                calendars.forEach { cal ->
                    DropdownMenuItem(
                        text = { Text("${cal.displayName} · ${cal.account}") },
                        onClick = { vm.setCalendarTarget(cal.id); menu = false },
                    )
                }
            }
        }
    }
}

/** Compose a calendar event (title prefilled from the page) and add it to the chosen calendar. */
@Composable
fun AddEventDialog(vm: InkViewModel, defaultTitle: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val targetId by vm.calendarTargetId.collectAsStateWithLifecycle()

    var title by remember { mutableStateOf(defaultTitle) }
    var notes by remember { mutableStateOf("") }
    var allDay by remember { mutableStateOf(false) }
    var durationMin by remember { mutableStateOf(60) }
    // Start: now rounded up to the next hour.
    val start = remember { Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 1); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) } }
    var startMs by remember { mutableStateOf(start.timeInMillis) }

    val askPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) submitEvent(vm, scope, context, title, startMs, durationMin, allDay, notes, onDismiss)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New calendar event", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth().riseIn(0))
                Row(Modifier.padding(top = 8.dp).riseIn(1), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { pickDateTime(context, startMs) { startMs = it } }) {
                        Text(formatWhen(startMs, allDay))
                    }
                }
                Row(Modifier.padding(top = 8.dp).riseIn(2), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("All-day", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = allDay, onCheckedChange = { allDay = it })
                    if (!allDay) listOf(30, 60, 120).forEach { m ->
                        val sel = durationMin == m
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.clickable { durationMin = m },
                        ) {
                            Text(
                                if (m < 60) "${m}m" else "${m / 60}h",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
                OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notes (optional)") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).riseIn(3))
                if (targetId < 0) Text("Pick a calendar in Settings first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            Button(
                enabled = targetId >= 0,
                onClick = {
                    if (hasCalendarPerms(context)) submitEvent(vm, scope, context, title, startMs, durationMin, allDay, notes, onDismiss)
                    else askPerms.launch(CAL_PERMS)
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun submitEvent(
    vm: InkViewModel,
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
    title: String,
    startMs: Long,
    durationMin: Int,
    allDay: Boolean,
    notes: String,
    onDismiss: () -> Unit,
) {
    val (s, e) = eventBounds(startMs, durationMin, allDay)
    scope.launch {
        val ok = vm.addCalendarEvent(title, s, e, allDay, notes)
        Toast.makeText(context, if (ok) "Added to calendar" else "Couldn't add event", Toast.LENGTH_SHORT).show()
        if (ok) onDismiss()
    }
}

/** Resolve start/end millis; all-day events snap to UTC midnight for [durationMin] ignored (1 day). */
internal fun eventBounds(startMs: Long, durationMin: Int, allDay: Boolean): Pair<Long, Long> {
    if (!allDay) return startMs to (startMs + durationMin * 60_000L)
    val utc = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
        val local = Calendar.getInstance().apply { timeInMillis = startMs }
        clear()
        set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
    }
    return utc.timeInMillis to (utc.timeInMillis + 24 * 60 * 60_000L)
}

private fun pickDateTime(context: android.content.Context, current: Long, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = current }
    DatePickerDialog(
        context,
        { _, y, mo, d ->
            TimePickerDialog(
                context,
                { _, h, mi ->
                    c.set(y, mo, d, h, mi); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
                    onPicked(c.timeInMillis)
                },
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), false,
            ).show()
        },
        c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH),
    ).show()
}

private fun formatWhen(ms: Long, allDay: Boolean): String {
    val fmt = if (allDay) "EEE, MMM d (all-day)" else "EEE, MMM d · h:mm a"
    return android.text.format.DateFormat.format(fmt, ms).toString()
}
