package com.nibhaus.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import com.nibhaus.capture.CapturedDot
import com.nibhaus.capture.PageBounds
import com.nibhaus.capture.cornerBounds
import com.nibhaus.capture.mmPerUnitOf
import com.nibhaus.capture.traceSpan

private enum class WizardStep { INTRO, TOP_LEFT, BOTTOM_RIGHT, SHEET, CONFIRM }

/**
 * Guided "set up this notebook" wizard: the user demonstrates the page on the real paper with the pen
 * (tap the top-left corner, tap the bottom-right) and the app records the writable Ncode bounds, then
 * the physical sheet size. Each capture step shows an animated mock notebook with a pen performing the
 * gesture. On finish it writes the notebook's geometry (so the live canvas opens at true page scale).
 *
 * Captures reuse the Capture Lab dot recorder (ink suppressed), so corner taps never dirty a page and
 * each capture carries the Ncode book id.
 */
@Composable
fun NotebookSetupWizard(vm: InkViewModel, activeBook: Int?, onDone: () -> Unit) {
    val context = LocalContext.current
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val json = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (json != null && vm.importNotebookSetup(json)) onDone()
    }
    var step by remember { mutableStateOf(WizardStep.INTRO) }
    var tl by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var br by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var book by remember { mutableStateOf<Int?>(null) }
    var sheetW by remember { mutableStateOf("145") }
    var sheetH by remember { mutableStateOf("210") }
    var sampleMm by remember { mutableStateOf("100") } // optional scale line length
    var mmPerUnit by remember { mutableStateOf<Float?>(null) }
    val recording by vm.captureRecording.collectAsStateWithLifecycle()

    // Pull dots from a capture; remember the book and return the trace's bounding box.
    fun finishCapture(): List<CapturedDot> {
        val dots = vm.stopCapture()
        dots.firstOrNull()?.let { book = it.book }
        return dots
    }
    fun tapCentre(dots: List<CapturedDot>): Pair<Float, Float>? {
        if (dots.isEmpty()) return null
        return (dots.minOf { it.x } + dots.maxOf { it.x }) / 2f to (dots.minOf { it.y } + dots.maxOf { it.y }) / 2f
    }

    val title: String
    val body: String
    when (step) {
        WizardStep.INTRO -> { title = "Set up this notebook"; body = "I'll learn your page so capture opens at the right size. You'll tap two corners on the paper with your pen. Watch the demo on each step." }
        WizardStep.TOP_LEFT -> { title = "Tap the top-left corner"; body = "Press the pen on the TOP-LEFT corner of the page (inside the dotted area), then tap Done." }
        WizardStep.BOTTOM_RIGHT -> { title = "Tap the bottom-right corner"; body = "Now press the pen on the BOTTOM-RIGHT corner of the page, then tap Done." }
        WizardStep.SHEET -> { title = "Page size"; body = "Enter the physical sheet size (with a ruler). Optionally draw a line of the length below to calibrate scale precisely." }
        WizardStep.CONFIRM -> { title = "Looks good?"; body = bounds(tl, br)?.let { "Captured page ${"%.1f".format(it.x1 - it.x0)} × ${"%.1f".format(it.y1 - it.y0)} Ncode units on a ${sheetW} × ${sheetH} mm sheet." } ?: "Both corners are needed. Go back and capture them." }
    }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column {
                Text(body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.riseIn(0))
                if (step == WizardStep.TOP_LEFT || step == WizardStep.BOTTOM_RIGHT || step == WizardStep.INTRO) {
                    WizardDemo(step, Modifier.fillMaxWidth().height(160.dp).padding(top = 12.dp).riseIn(1))
                }
                if (step == WizardStep.INTRO) {
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp).riseIn(2), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { importer.launch(arrayOf("application/json", "text/*", "*/*")) }) { Text("Import setup") }
                        if (activeBook != null && vm.exportNotebookSetup(activeBook) != null) {
                            TextButton(onClick = { vm.exportNotebookSetup(activeBook)?.let { shareJson(context, it, "notebook-$activeBook.json") } }) { Text("Export setup") }
                        }
                    }
                }
                if (step == WizardStep.SHEET) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp).riseIn(1), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(sheetW, { sheetW = it }, label = { Text("Width mm") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(0.5f))
                        OutlinedTextField(sheetH, { sheetH = it }, label = { Text("Height mm") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    OutlinedTextField(sampleMm, { sampleMm = it }, label = { Text("Calibration line length (mm)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth().padding(top = 6.dp).riseIn(2))
                    if (recording) {
                        Button(onClick = {
                            mmPerUnit = mmPerUnitOf(traceSpan(finishCapture()), sampleMm.toFloatOrNull() ?: 0f)
                        }, modifier = Modifier.padding(top = 8.dp).riseIn(3)) { Text("Done drawing line") }
                    } else {
                        TextButton(onClick = { vm.startCapture() }, modifier = Modifier.padding(top = 4.dp).riseIn(3)) { Text("Draw a calibration line") }
                    }
                    mmPerUnit?.let { Text("Scale: ${"%.3f".format(it)} mm/unit", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
                }
            }
        },
        confirmButton = {
            when (step) {
                WizardStep.INTRO -> Button(onClick = { step = WizardStep.TOP_LEFT }) { Text("Start") }
                WizardStep.TOP_LEFT -> if (recording) {
                    Button(onClick = { tl = tapCentre(finishCapture()); if (tl != null) step = WizardStep.BOTTOM_RIGHT }) { Text("Done") }
                } else Button(onClick = { vm.startCapture() }) { Text("Capture") }
                WizardStep.BOTTOM_RIGHT -> if (recording) {
                    Button(onClick = { br = tapCentre(finishCapture()); if (br != null) step = WizardStep.SHEET }) { Text("Done") }
                } else Button(onClick = { vm.startCapture() }) { Text("Capture") }
                WizardStep.SHEET -> Button(onClick = { step = WizardStep.CONFIRM }) { Text("Next") }
                WizardStep.CONFIRM -> Button(
                    enabled = bounds(tl, br) != null && book != null,
                    onClick = {
                        val b = bounds(tl, br)!!
                        vm.saveNotebookGeometry(book!!, b, sheetW.toFloatOrNull() ?: 0f, sheetH.toFloatOrNull() ?: 0f, mmPerUnit)
                        onDone()
                    },
                ) { Text("Save") }
            }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

/** Normalised writable rectangle from the two captured corners, or null if either is missing/degenerate. */
private fun bounds(tl: Pair<Float, Float>?, br: Pair<Float, Float>?): PageBounds? {
    if (tl == null || br == null) return null
    val b = cornerBounds(tl.first, tl.second, br.first, br.second)
    return if (b.x1 - b.x0 > 1f && b.y1 - b.y0 > 1f) b else null
}

/** A mock notebook with a pen demonstrating the current step's gesture (looping). */
@Composable
private fun WizardDemo(step: WizardStep, modifier: Modifier) {
    val t by rememberInfiniteTransition(label = "demo")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(1300), RepeatMode.Reverse), label = "t")
    val cs = MaterialTheme.colorScheme
    Canvas(modifier) {
        val pad = size.minDimension * 0.14f
        val page = androidx.compose.ui.geometry.Rect(pad, pad, size.width - pad, size.height - pad)
        // Page outline + a faint dot grid.
        drawRoundRect(cs.onSurfaceVariant.copy(alpha = 0.35f), topLeft = page.topLeft, size = page.size, style = Stroke(width = 3f))
        val gx = 6; val gy = 8
        for (i in 1 until gx) for (j in 1 until gy) {
            drawCircle(cs.onSurfaceVariant.copy(alpha = 0.18f), radius = 1.6f,
                center = Offset(page.left + page.width * i / gx, page.top + page.height * j / gy))
        }
        val target = when (step) {
            WizardStep.TOP_LEFT -> page.topLeft
            WizardStep.BOTTOM_RIGHT -> Offset(page.right, page.bottom)
            else -> page.center
        }
        if (step == WizardStep.TOP_LEFT || step == WizardStep.BOTTOM_RIGHT) {
            // Pulsing target ring + a pen tip easing toward the corner.
            drawCircle(cs.primary.copy(alpha = 0.25f + 0.25f * t), radius = 10f + 8f * t, center = target, style = Stroke(width = 3f))
            val approach = 0.18f * (1f - t)
            val penTip = Offset(
                target.x + (page.center.x - target.x) * approach,
                target.y + (page.center.y - target.y) * approach,
            )
            drawPen(penTip, cs.primary)
        }
    }
}

/** Write [json] to app cache and open a share sheet so a notebook setup can be sent off-device. */
private fun shareJson(context: Context, json: String, name: String) {
    runCatching {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, name).apply { writeText(json) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share notebook setup"))
    }
}

/** A small stylised pen nib pointing at [tip]. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPen(tip: Offset, color: androidx.compose.ui.graphics.Color) {
    val len = 34f
    val back = Offset(tip.x + len * 0.55f, tip.y + len)
    val nib = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(tip.x + 7f, tip.y + 12f)
        lineTo(tip.x + 13f, tip.y + 6f)
        close()
    }
    drawPath(nib, color)
    drawLine(color, Offset(tip.x + 10f, tip.y + 9f), back, strokeWidth = 7f)
}
