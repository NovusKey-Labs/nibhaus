package com.nibhaus.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nibhaus.capture.Measurement
import com.nibhaus.capture.measurementsCsv
import com.nibhaus.capture.toCsv
import com.nibhaus.pen.PenConnState
import java.io.File

/**
 * Capture Lab — guided recordings of the pen's own output (no app/cloud scraped). While recording,
 * a sticky banner reminds you of the steps for the active capture and holds the Stop button. The
 * reminder is just UI; the pen captures independently, so it never blocks or disturbs the data.
 */
@Composable
fun CaptureLabScreen(vm: InkViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val recording by vm.captureRecording.collectAsStateWithLifecycle()
    val pen by vm.penState.collectAsStateWithLifecycle()
    val connected = pen is PenConnState.Connected
    val cs = MaterialTheme.colorScheme

    var mode by remember { mutableStateOf<String?>(null) } // "scale" | "raw" | "planner"
    var notebookType by remember { mutableStateOf("") }
    var knownCm by remember { mutableStateOf("1") }
    var plannerLabel by remember { mutableStateOf("left page") }
    var measurements by remember { mutableStateOf<List<Measurement>>(emptyList()) }

    fun stepsFor(m: String?): String = when (m) {
        "scale" -> "On \"${notebookType.ifBlank { "this notebook" }}\", draw ONE straight line exactly $knownCm cm long. Then tap Stop."
        "raw" -> "Tap or trace anything you want logged. Then tap Stop."
        "planner" -> "1) Trace a known distance.   2) Trace the known object (e.g. a cell border).   Then Stop. (Also photograph this page.)"
        else -> ""
    }

    fun stopActive() {
        when (mode) {
            "scale" -> {
                measurements = measurements + Measurement(notebookType.ifBlank { "(unnamed)" }, knownCm.toFloatOrNull() ?: 0f, vm.stopCapture())
            }
            "raw" -> exportCsv(context, toCsv(vm.stopCapture()), "capture_${System.currentTimeMillis()}.csv")
            "planner" -> {
                val label = plannerLabel.trim().replace(Regex("[^A-Za-z0-9]+"), "_").ifBlank { "page" }
                exportCsv(context, toCsv(vm.stopCapture()), "planner_${label}_${System.currentTimeMillis()}.csv")
            }
            else -> vm.stopCapture()
        }
        mode = null
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // Fixed header — Back + title.
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).riseIn(0), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Capture lab", style = MaterialTheme.typography.headlineMedium)
            Button(onClick = onBack) { Text("Back") }
        }
        if (!connected) Text("Connect your pen to record.", style = MaterialTheme.typography.bodySmall, color = cs.error, modifier = Modifier.padding(bottom = 8.dp))

        // Sticky, non-blocking step reminder while recording — Stop lives here so it's always visible.
        if (recording) {
            Surface(
                Modifier.fillMaxWidth().padding(bottom = 10.dp),
                shape = RoundedCornerShape(14.dp),
                color = cs.primaryContainer,
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("● Recording", style = MaterialTheme.typography.labelLarge, color = cs.onPrimaryContainer)
                        Text(stepsFor(mode), style = MaterialTheme.typography.bodyMedium, color = cs.onPrimaryContainer)
                    }
                    Button(onClick = { stopActive() }) { Text("Stop") }
                }
            }
        }

        // Scrollable mode cards.
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Box(Modifier.riseIn(1)) {
                LabCard("Notebook measurements", "Name the notebook type, then draw lines of known lengths (for example 1 cm, then 5 cm). Export and send the matching page photos. Nibhaus cross-references notebook type, line, and photo.") {
                    OutlinedTextField(
                        value = notebookType, onValueChange = { notebookType = it },
                        label = { Text("Notebook type (e.g. Planner, Standard)") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    OutlinedTextField(
                        value = knownCm, onValueChange = { knownCm = it },
                        label = { Text("Known length (cm)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    OutlinedButton(
                        enabled = connected && !recording && notebookType.isNotBlank(),
                        onClick = { mode = "scale"; vm.startCapture() },
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text("Record line") }

                    measurements.forEach { m ->
                        Text(
                            "• ${m.notebook}: ${m.knownCm} cm = ${"%.1f".format(m.unitsPerCm)} units/cm",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    if (measurements.isNotEmpty()) {
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { exportCsv(context, measurementsCsv(measurements), "measurements_${System.currentTimeMillis()}.csv") }) { Text("Export measurements") }
                            OutlinedButton(onClick = { measurements = emptyList() }) { Text("Clear") }
                        }
                    }
                }
            }

            Box(Modifier.riseIn(2)) {
                LabCard("Raw capture", "Record every dot (full Ncode address) while you tap/trace anything, then export.") {
                    OutlinedButton(enabled = connected && !recording, onClick = { mode = "raw"; vm.startCapture() }, modifier = Modifier.padding(top = 8.dp)) { Text("Record") }
                }
            }

            Box(Modifier.riseIn(3)) {
                LabCard("Planner reference", "Trace a known distance, then a known object (for example a cell border) on this page. Also send photos of the left and right pages. Nibhaus will cross-reference them and build the template.") {
                    OutlinedTextField(value = plannerLabel, onValueChange = { plannerLabel = it }, label = { Text("Page label") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    OutlinedButton(enabled = connected && !recording, onClick = { mode = "planner"; vm.startCapture() }, modifier = Modifier.padding(top = 8.dp)) { Text("Record") }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LabCard(title: String, desc: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

/** Write [csv] to app cache and open a share sheet so the file can be sent off-device. */
private fun exportCsv(context: Context, csv: String, name: String) {
    runCatching {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, name).apply { writeText(csv) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Export capture"))
    }
}
