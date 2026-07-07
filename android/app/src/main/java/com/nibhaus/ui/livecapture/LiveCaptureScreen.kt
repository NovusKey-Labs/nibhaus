package com.nibhaus.ui.livecapture

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nibhaus.audio.RecordingController
import com.nibhaus.data.Point
import com.nibhaus.data.RecordingEntity
import com.nibhaus.pen.PenConnState
import com.nibhaus.ui.theme.LiveGreen
import com.nibhaus.ui.theme.monoData
import com.nibhaus.ui.theme.monoEyebrow
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.nibhaus.ui.InkViewModel
import com.nibhaus.ui.NotebookSetupWizard
import com.nibhaus.ui.common.BatteryBadge
import com.nibhaus.ui.common.ExpandingColorPicker
import com.nibhaus.ui.common.ExpandingSizePicker
import com.nibhaus.ui.common.InkSurface
import com.nibhaus.ui.common.formatClock
import com.nibhaus.ui.common.recordingName
import com.nibhaus.ui.common.rememberCompact
import com.nibhaus.ui.rememberReducedMotion
import com.nibhaus.ui.riseIn

/**
 * Live capture (mock #2, the hero): the page being written right now, on a full Ncode dot-grid, with
 * teal ink landing as you write. Renders from the database (the source of truth) — the [InkViewModel]
 * observes the most-recently-inked page, which updates as the ingestor commits each stroke. The
 * coordinate + sample-rate readout below are computed from real captured points, not faked.
 */
@Composable
internal fun LiveCaptureScreen(vm: InkViewModel, onBack: () -> Unit) {
    // Start fresh on every entry: don't show the last session's page — wait for new ink.
    LaunchedEffect(Unit) { vm.startLiveSession() }
    // Never leave the mic (or a playback) running after leaving capture.
    // Note: stops the note on navigate-away; background recording would move this to the service.
    DisposableEffect(Unit) { onDispose { vm.stopRecording(); vm.stopPlayback() } }
    val pen by vm.penState.collectAsStateWithLifecycle()
    val page by vm.livePage.collectAsStateWithLifecycle()
    val strokes by vm.liveStrokes.collectAsStateWithLifecycle()
    val inkColor by vm.inkColorState.collectAsStateWithLifecycle()
    val inkWidth by vm.inkWidthState.collectAsStateWithLifecycle()
    val battery by vm.battery.collectAsStateWithLifecycle()
    val recState by vm.recordingState.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme
    val live = pen is PenConnState.Connected
    val compact = rememberCompact() // phone-width: tighten the header so the notebook title isn't crowded
    val pageId = page?.id
    var showWizard by remember { mutableStateOf(false) }
    if (showWizard) NotebookSetupWizard(vm, activeBook = page?.book, onDone = { showWizard = false })

    // Voice notes need the mic permission; ask on the first tap, then start once granted.
    val context = LocalContext.current
    val micGranted = remember {
        mutableStateOf(
            context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted.value = granted
        if (granted) pageId?.let { vm.toggleRecording(it, page?.addressKey ?: "") }
    }
    fun onRecordTap() {
        val id = pageId ?: return
        if (micGranted.value) vm.toggleRecording(id, page?.addressKey ?: "")
        else askMic.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    Column(Modifier.fillMaxSize()) {
        // V3 chrome: riseIn stagger on header + status chip.
        // Header: a light back arrow, the notebook title with ONE clean state line beneath it (given the
        // flexible width so they ellipsize gracefully), then a fixed action cluster that never competes
        // for width. This reads correctly in every state — waiting, live, paused — and can't crush.
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 8.dp).riseIn(0),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f).padding(start = 4.dp, end = 8.dp)) {
                Text(
                    vm.notebookTitleOf(page),
                    style = if (compact) MaterialTheme.typography.titleMedium
                            else MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                CaptureStatusLine(live = live, pageNumber = page?.page)
            }
            IconButton(onClick = { showWizard = true }) {
                Icon(Icons.Outlined.AspectRatio, contentDescription = "Page size")
            }
            VoiceRecordButton(
                enabled = pageId != null,
                recording = recState is RecordingController.State.Recording,
                startedAt = (recState as? RecordingController.State.Recording)?.startedAt,
                onTap = ::onRecordTap,
            )
            battery?.let {
                Spacer(Modifier.width(4.dp))
                BatteryBadge(it, showEta = false)
            }
        }

        // Writing controls: ink color + width, each a single chip that expands to the full options.
        val cs0 = MaterialTheme.colorScheme
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ExpandingColorPicker(selected = inkColor, onColor = cs0.onSurface, onSelect = vm::setInkColor)
            ExpandingSizePicker(selected = inkWidth, onColor = cs0.onSurface, onSelect = vm::setInkWidth)
        }
        pageId?.let { RecordingsStrip(it, vm) }
        // Full-page-from-start: open the canvas at the notebook's writable page bounds (when known)
        // so the first stroke renders at true scale/position instead of auto-zooming to itself.
        // Live canvas: no revealPageId (liveGlow=true keeps the live rendering path untouched).
        InkSurface(
            strokes, vm, Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp), liveGlow = true,
            pageBounds = vm.pageGeometryFor(page?.book),
            ruling = vm.rulingFor(page?.book),
            pageStyle = vm.pageStyleAt(page?.book, page?.page),
            pageNumber = page?.page,
            zones = com.nibhaus.zones.BuiltinZones.ALL.filter { it.book == page?.book },
        )

        // V3 mono readout: combined coordinate × coordinate · fps · status, IBM Plex Mono.
        val lastPoints = strokes.lastOrNull()?.let { vm.strokesFlowOf(it) }.orEmpty()
        val lastPoint = lastPoints.lastOrNull()
        val coordStr = lastPoint?.let { "%.1f × %.1f".format(it.x, it.y) } ?: "— × —"
        val statusStr = if (live) "${sampleRate(lastPoints)} fps · streaming" else "paused"
        Text(
            "$coordStr · $statusStr",
            style = monoData,
            color = if (live) cs.primary else cs.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 13.dp)
                .riseIn(1),
        )
    }
}

/** One clean, single-line capture-state indicator: a colored state dot plus a plain-language label.
 *  Robust in every state (live + page, waiting, not connected) — always one line, never a crushed
 *  per-character vertical stack. The single source of the LIVE/state readout in the header. */
@Composable
private fun CaptureStatusLine(live: Boolean, pageNumber: Int?) {
    val cs = MaterialTheme.colorScheme
    val (dot, label) = when {
        live && pageNumber != null -> LiveGreen to "Live · Page $pageNumber"
        live -> LiveGreen to "Waiting for ink"
        else -> cs.onSurfaceVariant to "Not connected"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(dot, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = monoEyebrow,
            color = cs.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Voice-note control for live capture: a mic that turns into a red Stop with a running timer while
 * recording. Disabled until a page is selected (a recording is always bound to a specific page).
 */
@Composable
private fun VoiceRecordButton(enabled: Boolean, recording: Boolean, startedAt: Long?, onTap: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (recording && startedAt != null) {
            val elapsed by produceState(0L, startedAt) {
                while (true) { value = System.currentTimeMillis() - startedAt; kotlinx.coroutines.delay(500) }
            }
            Text(formatClock(elapsed), style = monoData, color = cs.error)
            Spacer(Modifier.width(4.dp))
        }
        val tint = when {
            recording -> cs.error
            enabled -> cs.onSurface
            else -> cs.onSurfaceVariant.copy(alpha = 0.4f)
        }
        IconButton(onClick = onTap, enabled = enabled) {
            Icon(
                if (recording) Icons.Filled.Stop else Icons.Outlined.Mic,
                contentDescription = if (recording) "Stop recording" else "Record a voice note for this page",
                tint = tint,
            )
        }
    }
}

/** Horizontally-scrolling pills of the page's voice notes; tap one to play/stop it. */
@Composable
private fun RecordingsStrip(pageId: String, vm: InkViewModel) {
    val recordings by remember(pageId) { vm.recordingsFor(pageId) }.collectAsStateWithLifecycle(emptyList())
    if (recordings.isEmpty()) return
    val playingId by vm.playingRecordingId.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        recordings.forEachIndexed { i, r: RecordingEntity ->
            val playing = r.id == playingId
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (playing) cs.primaryContainer else cs.surfaceVariant,
                modifier = Modifier.clickable { if (playing) vm.stopPlayback() else vm.playRecording(r) },
            ) {
                Row(
                    Modifier.padding(start = 8.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        contentDescription = if (playing) "Stop" else "Play",
                        tint = cs.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        recordingName(r, i) + r.durationMs.takeIf { it > 0 }?.let { " · ${formatClock(it)}" }.orEmpty(),
                        style = monoData,
                        color = cs.onSurface,
                    )
                }
            }
        }
    }
}

/** Brass live dot with an expanding-ring pulse (design-system §8, 1.8s loop). */
@Composable
private fun LiveIndicator() {
    val reduced = rememberReducedMotion()
    val t = rememberInfiniteTransition(label = "live")
    // Reduced motion → ring stays at scale 1 / alpha 0 (invisible); the solid dot + LIVE pill remain.
    val scale by t.animateFloat(
        initialValue = 1f, targetValue = if (reduced) 1f else 2.4f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Restart), label = "ring",
    )
    val alpha by t.animateFloat(
        initialValue = if (reduced) 0f else 0.5f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Restart), label = "ringAlpha",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(9.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
                    .background(LiveGreen, CircleShape),
            )
            Box(Modifier.size(9.dp).background(LiveGreen, CircleShape))
        }
        Spacer(Modifier.width(7.dp))
        Text("LIVE", style = monoData, color = LiveGreen)
    }
}

/** Real sample rate (Hz) from a stroke's captured point timestamps; "—" if indeterminable. */
private fun sampleRate(points: List<Point>): String {
    if (points.size < 2) return "—"
    val ms = points.last().t - points.first().t
    if (ms <= 0L) return "—"
    return (((points.size - 1) * 1000.0) / ms).toInt().toString()
}

// ---- Scan / pair ----
