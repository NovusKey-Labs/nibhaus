package com.nibhaus.ui.pagedetail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nibhaus.data.Point
import com.nibhaus.data.StrokeEntity
import com.nibhaus.share.ReplayGif
import com.nibhaus.share.ShareFilename
import com.nibhaus.ui.theme.InkText
import com.nibhaus.ui.theme.NavyDeep
import com.nibhaus.ui.theme.InkTokens
import com.nibhaus.ui.theme.freehandPath
import com.nibhaus.ui.theme.monoData
import com.nibhaus.ui.theme.monoEyebrow
import com.nibhaus.ui.theme.LocalInkPaper
import com.nibhaus.ui.theme.LocalPaperTemplate
import com.nibhaus.ui.theme.ncodePaper
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.nibhaus.ui.InkViewModel
import com.nibhaus.ui.ReplayFrame
import com.nibhaus.ui.StrokeRenderCache
import com.nibhaus.ui.buildReplayTimeline
import com.nibhaus.ui.common.fitBounds
import com.nibhaus.ui.common.formatClock
import com.nibhaus.ui.common.inkFit
import com.nibhaus.ui.common.strokeBaseWidthPx
import com.nibhaus.ui.defaultStrokeRenderCache
import com.nibhaus.ui.pointsUpTo
import com.nibhaus.ui.replayFrameAt

/**
 * Handwriting Replay: plays a page's ink back in the order/timing it was written, driven by the
 * strokes' own timestamps — NOT audio-coupled (contrast [drawStrokesPencast]/[ListenCanvas], which
 * follow a voice recording's playback position). The timeline is built once from [strokes]; a
 * simple [withFrameNanos] loop advances the position by elapsed×speed while playing.
 */
@Composable
internal fun ReplayScreen(strokes: List<StrokeEntity>, vm: InkViewModel, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val paper = LocalInkPaper.current
    val paperTemplate = LocalPaperTemplate.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportingGif by remember { mutableStateOf(false) }
    // a human name for the exported GIF — "Nibhaus — {notebook} p{page} replay — {date}".
    val pageEntity by vm.selectedPageEntity.collectAsStateWithLifecycle()
    val notebooks by vm.notebooks.collectAsStateWithLifecycle()
    val gifBaseName = ShareFilename.forReplay(
        notebooks.firstOrNull { it.id == pageEntity?.notebookId }?.title.orEmpty(),
        pageEntity?.page ?: 0,
    )
    // #15b follow-up: the exported GIF honors the Fine/Normal/Bold handwriting-size preset too.
    val strokeScale by vm.strokeScale.collectAsStateWithLifecycle()
    val timeline = remember(strokes) { buildReplayTimeline(strokes, vm::strokesFlowOf) }
    var positionMs by remember(timeline) { mutableStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var speedIndex by remember { mutableStateOf(0) }
    // rememberUpdatedState so the frame loop below (launched once per playing/timeline, NOT per
    // speedIndex) reads the CURRENT speed each iteration — otherwise it closes over the speed value
    // from whenever it was launched and a mid-play speed change does nothing until pause/resume.
    val speed by rememberUpdatedState(REPLAY_SPEEDS[speedIndex])

    BackHandler { onClose() }

    // Advance position by real elapsed time × speed each frame; stop at the end. Reading `speed`/
    // `positionMs` fresh each loop iteration means live speed changes and scrubs both take effect
    // immediately without restarting the effect.
    LaunchedEffect(playing, timeline) {
        if (!playing || timeline.totalMs <= 0L) return@LaunchedEffect
        var lastFrameNanos = withFrameNanos { it }
        while (true) {
            val nowNanos = withFrameNanos { it }
            val deltaMs = (nowNanos - lastFrameNanos) / 1_000_000L
            lastFrameNanos = nowNanos
            val next = positionMs + (deltaMs * speed).toLong()
            if (next >= timeline.totalMs) {
                positionMs = timeline.totalMs
                playing = false
                break
            }
            positionMs = next
        }
    }

    // Short pages get a proportional skip so ±10s isn't a no-op (jumps straight to start/end).
    val skipMs = if (timeline.totalMs in 1 until 20_000L) (timeline.totalMs / 10).coerceAtLeast(200L) else 10_000L

    Column(Modifier.fillMaxSize().background(paper.surface)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onClose) { Text("Back") }
            Text("HANDWRITING REPLAY", style = monoEyebrow, color = cs.onSurfaceVariant)
            // Export the replay as a shareable animated GIF clip (Feature: Replay → GIF export).
            if (exportingGif) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(horizontal = 12.dp).size(24.dp),
                    strokeWidth = 2.dp,
                    color = cs.onSurfaceVariant,
                )
            } else {
                IconButton(
                    enabled = strokes.isNotEmpty(),
                    onClick = {
                        exportingGif = true
                        scope.launch {
                            val file = ReplayGif.renderToCache(
                                context, strokes, vm::strokesFlowOf,
                                baseName = gifBaseName, strokeScale = strokeScale.multiplier,
                            )
                            exportingGif = false
                            if (file != null) ReplayGif.share(context, file)
                        }
                    },
                ) {
                    Icon(Icons.Outlined.Share, contentDescription = "Share replay as GIF", tint = cs.onSurfaceVariant)
                }
            }
        }
        Card(
            Modifier.weight(1f).fillMaxWidth().padding(8.dp),
            colors = CardDefaults.cardColors(containerColor = paper.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            Canvas(Modifier.fillMaxSize().ncodePaper(paperTemplate, InkTokens.dotColor(paper.ink))) {
                val frame = replayFrameAt(timeline, positionMs)
                drawReplayFrame(strokes, vm::strokesFlowOf, frame, brandInk = paper.ink)
            }
        }
        ReplayBar(
            totalMs = timeline.totalMs,
            positionMs = positionMs,
            playing = playing,
            speedIndex = speedIndex,
            skipMs = skipMs,
            onPlayPause = {
                if (!playing && positionMs >= timeline.totalMs) positionMs = 0L
                playing = !playing
            },
            onSeek = { ms -> positionMs = ms.coerceIn(0L, timeline.totalMs) },
            onSkip = { deltaMs -> positionMs = (positionMs + deltaMs).coerceIn(0L, timeline.totalMs) },
            onCycleSpeed = { speedIndex = (speedIndex + 1) % REPLAY_SPEEDS.size },
        )
    }
}

/** Cycles 1×→2×→4×→1× on tap. */
private val REPLAY_SPEEDS = listOf(1f, 2f, 4f)

/**
 * Renders a [ReplayFrame]: strokes already drawn at full ink, the in-progress stroke traced up to
 * its fraction, everything after left unrendered. The fit is computed from [allStrokes] — the
 * FULL page, not just what's currently visible — so the page doesn't rescale/re-zoom as more ink
 * is revealed; this layers on top of [inkFit]/[fitBounds] exactly like [drawStrokesPencast] does.
 */
private fun DrawScope.drawReplayFrame(
    allStrokes: List<StrokeEntity>,
    points: (StrokeEntity) -> List<Point>,
    frame: ReplayFrame,
    brandInk: Color,
    cache: StrokeRenderCache = defaultStrokeRenderCache,
) {
    val fit = inkFit(allStrokes, points, size.width, size.height) ?: return
    val baseW = strokeBaseWidthPx(size.width)
    // Finished strokes are the same every frame at a given fit, so their tessellation is cacheable —
    // this is the hot path at up to 60fps while replay plays. The in-progress stroke's geometry
    // changes every frame (revealed up to activeFraction) so it's tessellated fresh, uncached.
    frame.doneStrokes.forEach { rs ->
        val s = rs.stroke
        if (rs.points.isEmpty()) return@forEach
        val color = if (s.color != 0) Color(s.color) else brandInk
        val path = cache.pathFor(s.uuid, s.width, color.toArgb(), selected = false, baseWidthPx = baseW, fit = fit) {
            val mapped = rs.points.map { fit.map(it.x, it.y) }
            val pr = rs.points.map { it.pressure }
            freehandPath(mapped, pr, baseW * s.width)
        }
        drawPath(path, color)
    }
    frame.activeStroke?.let { rs ->
        val pts = pointsUpTo(rs, frame.activeFraction)
        if (pts.isEmpty()) return@let
        val mapped = pts.map { fit.map(it.x, it.y) }
        val pr = pts.map { it.pressure }
        val color = if (rs.stroke.color != 0) Color(rs.stroke.color) else brandInk
        drawPath(freehandPath(mapped, pr, baseW * rs.stroke.width), color)
    }
}

/** Bottom transport for Replay: scrubber + timecode, ±10s, play/pause, speed cycle. */
@Composable
private fun ReplayBar(
    totalMs: Long,
    positionMs: Long,
    playing: Boolean,
    speedIndex: Int,
    skipMs: Long,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkip: (Long) -> Unit,
    onCycleSpeed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val onBar = InkText
    Surface(
        modifier.padding(14.dp).fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = NavyDeep,
        shadowElevation = 12.dp,
    ) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatClock(positionMs), style = monoData, color = onBar)
                Slider(
                    value = if (totalMs > 0) (positionMs.toFloat() / totalMs).coerceIn(0f, 1f) else 0f,
                    onValueChange = { frac -> onSeek((frac * totalMs).toLong()) },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text(formatClock(totalMs), style = monoData, color = onBar)
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { onSkip(-skipMs) }) {
                    Icon(Icons.Outlined.Replay10, contentDescription = "Back 10 seconds", tint = onBar)
                }
                IconButton(onClick = onPlayPause) {
                    Icon(
                        if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play",
                        tint = onBar,
                    )
                }
                IconButton(onClick = { onSkip(skipMs) }) {
                    Icon(Icons.Outlined.Forward10, contentDescription = "Forward 10 seconds", tint = onBar)
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onCycleSpeed) {
                    Text("${REPLAY_SPEEDS[speedIndex].toInt()}×", color = onBar)
                }
            }
        }
    }
}
