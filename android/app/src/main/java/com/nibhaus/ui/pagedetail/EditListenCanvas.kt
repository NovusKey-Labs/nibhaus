package com.nibhaus.ui.pagedetail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nibhaus.audio.isStrokeSpoken
import com.nibhaus.audio.markerStrokes
import com.nibhaus.data.Point
import com.nibhaus.data.RecordingEntity
import com.nibhaus.data.StrokeEntity
import com.nibhaus.ui.theme.InkText
import com.nibhaus.ui.theme.NavyDeep
import com.nibhaus.ui.theme.InkTokens
import com.nibhaus.ui.theme.freehandPath
import com.nibhaus.ui.theme.monoData
import com.nibhaus.ui.theme.ncodeDotGrid
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.nibhaus.ui.InkViewModel
import com.nibhaus.ui.StrokeRenderCache
import com.nibhaus.ui.common.ExpandingColorPicker
import com.nibhaus.ui.common.ExpandingSizePicker
import com.nibhaus.ui.common.drawPageBackground
import com.nibhaus.ui.common.drawStrokes
import com.nibhaus.ui.common.formatClock
import com.nibhaus.ui.common.inkFit
import com.nibhaus.ui.common.recordingName
import com.nibhaus.ui.common.strokeBaseWidthPx
import com.nibhaus.ui.defaultStrokeRenderCache

/**
 * Editable canvas: renders ink (selected strokes highlighted brass + thicker) and captures gestures.
 * Lasso mode → drag draws a brass dashed loop and selects the strokes inside it; otherwise a tap
 * toggles the nearest stroke (within a small radius) in the selection.
 */
@Composable
internal fun EditableInkCanvas(
    strokes: List<StrokeEntity>,
    vm: InkViewModel,
    lassoMode: Boolean,
    selected: Set<String>,
    onToggleStroke: (StrokeEntity) -> Unit,
    onLasso: (List<StrokeEntity>) -> Unit,
    modifier: Modifier = Modifier,
    background: ImageBitmap? = null,
) {
    val cs = MaterialTheme.colorScheme
    val lassoPts = remember { mutableStateListOf<Offset>() }
    Card(
        modifier,
        colors = CardDefaults.cardColors(containerColor = cs.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        val gesture = if (lassoMode) {
            Modifier.pointerInput(strokes) {
                detectDragGestures(
                    onDragStart = { lassoPts.clear(); lassoPts.add(it) },
                    onDragEnd = {
                        val fit = inkFit(strokes, vm::strokesFlowOf, size.width.toFloat(), size.height.toFloat())
                        val poly = lassoPts.toList()
                        // Precision: select a stroke only when MOST of it is inside the loop, so
                        // circling one letter doesn't grab neighbours that merely dip a point in.
                        val hit = if (fit == null) emptyList() else strokes.filter { s ->
                            val pts = vm.strokesFlowOf(s)
                            if (pts.isEmpty()) return@filter false
                            val inside = pts.count { p ->
                                val sp = fit.map(p.x, p.y)
                                pointInPolygon(poly, sp.x, sp.y)
                            }
                            inside >= pts.size * LASSO_INSIDE_FRACTION
                        }
                        onLasso(hit)
                        lassoPts.clear()
                    },
                    onDragCancel = { lassoPts.clear() },
                ) { change, _ -> lassoPts.add(change.position) }
            }
        } else {
            Modifier.pointerInput(strokes) {
                detectTapGestures { tap ->
                    val fit = inkFit(strokes, vm::strokesFlowOf, size.width.toFloat(), size.height.toFloat())
                        ?: return@detectTapGestures
                    var best: StrokeEntity? = null
                    var bestSq = Float.MAX_VALUE
                    strokes.forEach { s ->
                        vm.strokesFlowOf(s).forEach { p ->
                            val sp = fit.map(p.x, p.y)
                            val dx = sp.x - tap.x
                            val dy = sp.y - tap.y
                            val sq = dx * dx + dy * dy
                            if (sq < bestSq) { bestSq = sq; best = s }
                        }
                    }
                    val hit = best
                    if (hit != null && bestSq <= TAP_RADIUS_PX * TAP_RADIUS_PX) onToggleStroke(hit)
                }
            }
        }
        val grid = if (background == null) Modifier.ncodeDotGrid(InkTokens.dotColor(cs.onBackground)) else Modifier
        Canvas(Modifier.fillMaxSize().then(grid).then(gesture)) {
            background?.let { drawPageBackground(it) }
            drawStrokes(strokes, vm::strokesFlowOf, cs.primary, selected, cs.tertiary, brandInk = cs.onSurface)
            if (lassoPts.size >= 2) {
                val path = Path().apply {
                    moveTo(lassoPts[0].x, lassoPts[0].y)
                    for (i in 1 until lassoPts.size) lineTo(lassoPts[i].x, lassoPts[i].y)
                }
                drawPath(
                    path, cs.tertiary,
                    style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))),
                )
            }
        }
    }
}

/**
 * Floating edit toolbar (design-system §9): an Ink-dark bar inset over the canvas. Left — the lasso
 * toggle, an expanding color picker (full palette), and an expanding size picker (Fine/Medium/Large).
 * Right — delete and undo. The color/size/delete actions apply to the current selection (dimmed
 * until something's selected); undo reverts the last *edit*, not the last stroke written.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun EditToolbar(
    lassoMode: Boolean,
    hasSelection: Boolean,
    currentColor: Int,
    currentWidth: Float,
    onToggleLasso: () -> Unit,
    onRecolor: (Int) -> Unit,
    onResize: (Float) -> Unit,
    onDelete: () -> Unit,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    // Material 3 Expressive floating tool palette (HorizontalFloatingToolbar graduated in alpha22),
    // with the lasso as a real ToggleButton. Centered over the page; always expanded.
    Box(modifier.fillMaxWidth().padding(14.dp), contentAlignment = Alignment.Center) {
        HorizontalFloatingToolbar(
            expanded = true,
            colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
        ) {
            ToggleButton(checked = lassoMode, onCheckedChange = { onToggleLasso() }) {
                Icon(Icons.Outlined.Gesture, contentDescription = "Lasso select")
            }
            ExpandingColorPicker(selected = currentColor, onColor = cs.onSurface, onSelect = onRecolor, enabled = hasSelection)
            ExpandingSizePicker(selected = currentWidth, onColor = cs.onSurface, onSelect = onResize, enabled = hasSelection)
            IconButton(onClick = onDelete, enabled = hasSelection) {
                Icon(Icons.Outlined.Delete, contentDescription = "Delete selected")
            }
            IconButton(onClick = onUndo) {
                Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = "Undo last edit")
            }
        }
    }
}

/**
 * Listen mode (the pencast): replay a page's voice notes synced to the ink. Tapping any stroke jumps
 * the audio to what was being said while it was written; tapping a voice marker plays that note from
 * its start; while a note plays the ink "re-writes" itself (already-spoken strokes inked, the rest
 * dimmed); the scrubber (in [ListenBar]) seeks. All four behaviours come off one shared timeline.
 */
@Composable
internal fun ListenCanvas(
    strokes: List<StrokeEntity>,
    vm: InkViewModel,
    recordings: List<RecordingEntity>,
    active: RecordingEntity?,
    playing: Boolean,
    positionMs: Long,
    onTapStroke: (StrokeEntity) -> Unit,
    onTapMarker: (RecordingEntity, Long) -> Unit,
    modifier: Modifier = Modifier,
    background: ImageBitmap? = null,
) {
    val cs = MaterialTheme.colorScheme
    // All bookmark points: (recording, marker-stroke) for the start + each post-pause resume.
    val markers = remember(recordings, strokes) {
        recordings.flatMap { r -> markerStrokes(r, strokes).map { r to it } }
    }
    Card(
        modifier,
        colors = CardDefaults.cardColors(containerColor = cs.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        val gesture = Modifier.pointerInput(strokes, recordings) {
            detectTapGestures { tap ->
                val fit = inkFit(strokes, vm::strokesFlowOf, size.width.toFloat(), size.height.toFloat())
                    ?: return@detectTapGestures
                // Markers sit on top of the ink, so hit-test them first.
                val markerHit = markers.firstOrNull { (_, s) ->
                    val p = vm.strokesFlowOf(s).firstOrNull() ?: return@firstOrNull false
                    val sp = fit.map(p.x, p.y)
                    val dx = sp.x - tap.x; val dy = sp.y - tap.y
                    dx * dx + dy * dy <= MARKER_RADIUS_PX * MARKER_RADIUS_PX
                }
                if (markerHit != null) {
                    val (rec, s) = markerHit
                    onTapMarker(rec, (s.startedAt - rec.startedAt).coerceAtLeast(0L))
                    return@detectTapGestures
                }
                // Otherwise the nearest stroke (same hit-test as the editor).
                var best: StrokeEntity? = null; var bestSq = Float.MAX_VALUE
                strokes.forEach { s ->
                    vm.strokesFlowOf(s).forEach { p ->
                        val sp = fit.map(p.x, p.y)
                        val dx = sp.x - tap.x; val dy = sp.y - tap.y
                        val sq = dx * dx + dy * dy
                        if (sq < bestSq) { bestSq = sq; best = s }
                    }
                }
                val hit = best
                if (hit != null && bestSq <= TAP_RADIUS_PX * TAP_RADIUS_PX) onTapStroke(hit)
            }
        }
        val grid = if (background == null) Modifier.ncodeDotGrid(InkTokens.dotColor(cs.onBackground)) else Modifier
        Canvas(Modifier.fillMaxSize().then(grid).then(gesture)) {
            background?.let { drawPageBackground(it) }
            if (active != null && playing) {
                drawStrokesPencast(strokes, vm::strokesFlowOf, active, positionMs, cs.onSurfaceVariant.copy(alpha = 0.22f), brandInk = cs.onSurface)
            } else {
                drawStrokes(strokes, vm::strokesFlowOf, cs.primary, brandInk = cs.onSurface)
            }
            // Voice markers: a dot at the start + every resume-after-pause point.
            val fit = inkFit(strokes, vm::strokesFlowOf, size.width, size.height)
            if (fit != null) markers.forEach { (_, s) ->
                vm.strokesFlowOf(s).firstOrNull()?.let { p ->
                    val sp = fit.map(p.x, p.y)
                    drawCircle(cs.tertiary, radius = 7.dp.toPx(), center = sp)
                    drawCircle(cs.surface, radius = 2.5.dp.toPx(), center = sp)
                }
            }
        }
    }
}

/** Like [drawStrokes] but colours by playback: spoken strokes inked, not-yet-spoken ones dimmed. */
private fun DrawScope.drawStrokesPencast(
    strokes: List<StrokeEntity>,
    points: (StrokeEntity) -> List<Point>,
    recording: RecordingEntity,
    positionMs: Long,
    future: Color,
    brandInk: Color,
    cache: StrokeRenderCache = defaultStrokeRenderCache,
) {
    val fit = inkFit(strokes, points, size.width, size.height) ?: return
    val baseW = strokeBaseWidthPx(size.width)
    strokes.forEach { s ->
        val raw = cache.points(s, points)
        if (raw.isEmpty()) return@forEach
        val spoken = isStrokeSpoken(recording, s, positionMs)
        val w = baseW * s.width
        val color = when {
            !spoken -> future                       // not yet reached → dim
            s.color != 0 -> Color(s.color)
            else -> brandInk                        // spoken default ink
        }
        val path = cache.pathFor(s.uuid, s.width, color.toArgb(), selected = false, baseWidthPx = baseW, fit = fit) {
            val pts = raw.map { fit.map(it.x, it.y) }
            val pr = raw.map { it.pressure }
            freehandPath(pts, pr, w)
        }
        drawPath(path, color)
    }
}

/** Bottom transport for Listen mode: active-note name + rename/delete, note picker, play/pause, scrub. */
@Composable
internal fun ListenBar(
    recordings: List<RecordingEntity>,
    active: RecordingEntity,
    loaded: Boolean,
    playing: Boolean,
    positionMs: Long,
    onSelect: (RecordingEntity) -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    // Theme-stable dark bar (Ink) with light content — NOT cs.onSurface, which inverts to white in dark mode.
    val onBar = InkText
    val activeIndex = recordings.indexOfFirst { it.id == active.id }
    Surface(
        modifier.padding(14.dp).fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = NavyDeep,
        shadowElevation = 12.dp,
    ) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    recordingName(active, activeIndex),
                    style = MaterialTheme.typography.titleSmall,
                    color = onBar,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRename) {
                    Icon(Icons.Outlined.Edit, contentDescription = "Rename note", tint = onBar)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Delete note", tint = onBar)
                }
            }
            if (recordings.size > 1) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    recordings.forEachIndexed { i, r ->
                        val sel = r.id == active.id
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (sel) cs.tertiary else onBar.copy(alpha = 0.15f),
                            // #16 TalkBack: which note is the active one, not just its color.
                            modifier = Modifier.clickable { onSelect(r) }.semantics { selected = sel },
                        ) {
                            Text(
                                recordingName(r, i),
                                style = monoData,
                                color = if (sel) cs.onPrimary else onBar,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPlayPause) {
                    Icon(
                        if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play",
                        tint = onBar,
                    )
                }
                Text(formatClock(if (loaded) positionMs else 0L), style = monoData, color = onBar)
                val dur = active.durationMs.coerceAtLeast(1L)
                Slider(
                    value = if (loaded) (positionMs.toFloat() / dur).coerceIn(0f, 1f) else 0f,
                    onValueChange = { frac -> onSeek((frac * dur).toLong()) },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text(formatClock(active.durationMs), style = monoData, color = onBar)
            }
        }
    }
}

/** Ray-casting point-in-polygon, for lasso selection (coordinates in canvas pixels). */
private fun pointInPolygon(poly: List<Offset>, x: Float, y: Float): Boolean {
    if (poly.size < 3) return false
    var inside = false
    var j = poly.lastIndex
    for (i in poly.indices) {
        val xi = poly[i].x; val yi = poly[i].y
        val xj = poly[j].x; val yj = poly[j].y
        if (((yi > y) != (yj > y)) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
        j = i
    }
    return inside
}

private const val TAP_RADIUS_PX = 48f

private const val MARKER_RADIUS_PX = 30f // tap target around a voice-note marker dot

private const val LASSO_INSIDE_FRACTION = 0.6f // a stroke is selected when ≥60% of its points are inside the loop
