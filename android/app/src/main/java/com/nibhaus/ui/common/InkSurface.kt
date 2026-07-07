package com.nibhaus.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import com.nibhaus.data.Point
import com.nibhaus.export.ExportArtifacts
import com.nibhaus.export.PageGeometry
import com.nibhaus.export.PageStyle
import com.nibhaus.export.PaperTemplate
import com.nibhaus.export.Ruling
import com.nibhaus.export.pageNumberAnchorUnits
import com.nibhaus.export.pageNumberText
import com.nibhaus.export.rulingLinesUnits
import com.nibhaus.export.rulingSideXUnits
import com.nibhaus.export.sheetFrame
import com.nibhaus.data.StrokeEntity
import com.nibhaus.share.PageRender
import com.nibhaus.ui.theme.InkTokens
import com.nibhaus.ui.theme.freehandPath
import com.nibhaus.ui.theme.LocalInkPaper
import com.nibhaus.ui.theme.LocalPaperTemplate
import com.nibhaus.ui.theme.ncodePaper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nibhaus.ui.InkViewModel
import com.nibhaus.ui.StrokeRenderCache
import com.nibhaus.ui.NibEasing
import com.nibhaus.ui.defaultStrokeRenderCache
import com.nibhaus.ui.rememberReducedMotion

/**
 * Stroke full-width as a **fraction of the canvas width**, NOT screen dp — matching the print/export
 * renderer ([com.nibhaus.share.PageRender], `width * 0.0025`). A fixed dp width multiplies by device
 * density, so the same page on a high-density phone drew ink ~2× thicker in px than on the lower-
 * density dev tablet at the same canvas width — fat strokes on same-size letters read as condensed/
 * crowded, and on a tiny thumbnail the dp stroke became a giant blob (unreadable). Keying width to the
 * canvas px makes ink density/resolution-independent: proportional on phone, tablet, and thumbnail
 * alike, and consistent with what print produces. Tune this one number to rebalance ink everywhere.
 */
private const val INK_WIDTH_FRAC = 0.0025f

/**
 * Base stroke width in px for a canvas of [canvasWidth] px; floored so ink never vanishes on tiny
 * thumbnails. [scale] is the user's handwriting-size preset (#15b, [com.nibhaus.export.StrokeScale]
 * — Fine 0.7x / Normal 1x / Bold 1.4x), applied here at the single source so live capture and every
 * in-app page render (both go through [drawStrokes]) pick it up automatically. NOTE: export/print
 * rasterization (`com.nibhaus.share.PageRender`) has its own separate width constant and does not
 * read this — see [drawStrokes]'s doc for why.
 */
internal fun strokeBaseWidthPx(canvasWidth: Float, scale: Float = 1f): Float =
    (canvasWidth * INK_WIDTH_FRAC * scale).coerceAtLeast(1.2f)

/** Printed page-number glyph height in mm — measured ~0.1cm tall x 0.3cm wide on the real page. */
private const val PAGE_NUMBER_MM = 1f

/**
 * Draws the notebook's *calibrated* paper — the real printed ruled lines and page number, in the ink's
 * own coordinate space (via [inkFit]) so ink sits on the lines. Style-aware: [PageStyle.COVER] draws
 * nothing; [PageStyle.LINED] draws every rule; [PageStyle.FOOTER_ONLY] keeps only the bottom rule. Rules
 * appear only when the user's template is Lined; the page number (bottom-left) is printed on every
 * lined/footer page. Uses the same fit as [drawStrokes], so rules and ink can never drift apart.
 */
private fun DrawScope.drawCalibratedPaper(
    strokes: List<StrokeEntity>,
    points: (StrokeEntity) -> List<Point>,
    geometry: PageGeometry,
    ruling: Ruling,
    pageNumber: Int?,
    style: PageStyle,
    lined: Boolean,
    color: Color,
    numberColor: Color = color, // page number legibility: stronger than the faint rule/dot tone
    zones: List<com.nibhaus.zones.ActionZone> = emptyList(),
) {
    if (style == PageStyle.COVER) return
    val fit = inkFit(strokes, points, size.width, size.height, geometry) ?: return
    val rows = rulingLinesUnits(geometry, ruling)
    val pitchU = if (rows.size >= 2) rows[1] - rows[0] else ruling.topMm / ExportArtifacts.MM_PER_UNIT
    if (lined) {
        val (lx, rx) = rulingSideXUnits(geometry, ruling)
        // FOOTER_ONLY pages keep only the bottom line; lined pages draw them all.
        val draw = if (style == PageStyle.FOOTER_ONLY) listOfNotNull(rows.lastOrNull()) else rows
        val sw = 1.dp.toPx()
        for (yu in draw) drawLine(color, fit.map(lx, yu), fit.map(rx, yu), strokeWidth = sw)
    }
    if (pageNumber != null) {
        val (ax, ay) = pageNumberAnchorUnits(geometry, ruling)
        val at = fit.map(ax, ay)
        val paint = android.graphics.Paint().apply {
            this.color = numberColor.toArgb()
            isAntiAlias = true
            typeface = android.graphics.Typeface.MONOSPACE
            // Physical print size mapped through the same fit — small on every screen.
            // Paint.textSize is font size ≈ cap-height/0.7, so scale up a touch for true height.
            textSize = (PAGE_NUMBER_MM / ExportArtifacts.MM_PER_UNIT) * fit.scale / 0.7f
        }
        // [ay] is the print's vertical centre; drop the baseline ~1/3 cap-height so the glyphs centre on it.
        drawContext.canvas.nativeCanvas.drawText(pageNumberText(pageNumber), at.x, at.y + paint.textSize * 0.35f, paint)
    }
    // Printed-button tap targets: a subtle rounded outline where a pen tap fires Share/Email, so the
    // user can see the buttons are live. Same fit as the ink — the box sits where the print sits.
    for (z in zones) {
        val tl = fit.map(z.left, z.top)
        val br = fit.map(z.right, z.bottom)
        val w = br.x - tl.x; val h = br.y - tl.y
        val sw = 1.dp.toPx()
        // Mimic the notebook's PRINTED icons (not bare boxes): an envelope for Email, an
        // out-of-tray arrow for Share — drawn at the zone's spot in the same print tone.
        when (z.action) {
            com.nibhaus.zones.ZoneAction.EMAIL, com.nibhaus.zones.ZoneAction.EMAIL_PNG,
            com.nibhaus.zones.ZoneAction.EMAIL_PDF,
            -> {
                // Envelope: body rect + flap (top corners meeting at centre).
                val bx = tl.x + w * 0.14f; val bw = w * 0.72f
                val by = tl.y + h * 0.22f; val bh = h * 0.56f
                drawRoundRect(
                    color = numberColor,
                    topLeft = androidx.compose.ui.geometry.Offset(bx, by),
                    size = androidx.compose.ui.geometry.Size(bw, bh),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5f * sw),
                    style = Stroke(width = sw),
                )
                drawLine(numberColor, androidx.compose.ui.geometry.Offset(bx, by), androidx.compose.ui.geometry.Offset(bx + bw / 2f, by + bh * 0.55f), strokeWidth = sw)
                drawLine(numberColor, androidx.compose.ui.geometry.Offset(bx + bw, by), androidx.compose.ui.geometry.Offset(bx + bw / 2f, by + bh * 0.55f), strokeWidth = sw)
            }
            else -> {
                // Share: an arrow rising out of a tray (the common export/share print mark).
                val cx = tl.x + w / 2f
                val trayY = tl.y + h * 0.62f
                val trayL = tl.x + w * 0.22f; val trayR = tl.x + w * 0.78f
                // tray: open-top U
                drawLine(numberColor, androidx.compose.ui.geometry.Offset(trayL, trayY), androidx.compose.ui.geometry.Offset(trayL, br.y - h * 0.14f), strokeWidth = sw)
                drawLine(numberColor, androidx.compose.ui.geometry.Offset(trayL, br.y - h * 0.14f), androidx.compose.ui.geometry.Offset(trayR, br.y - h * 0.14f), strokeWidth = sw)
                drawLine(numberColor, androidx.compose.ui.geometry.Offset(trayR, trayY), androidx.compose.ui.geometry.Offset(trayR, br.y - h * 0.14f), strokeWidth = sw)
                // arrow shaft + head
                val topY = tl.y + h * 0.12f
                drawLine(numberColor, androidx.compose.ui.geometry.Offset(cx, topY), androidx.compose.ui.geometry.Offset(cx, trayY + h * 0.1f), strokeWidth = sw)
                drawLine(numberColor, androidx.compose.ui.geometry.Offset(cx, topY), androidx.compose.ui.geometry.Offset(cx - w * 0.16f, topY + h * 0.2f), strokeWidth = sw)
                drawLine(numberColor, androidx.compose.ui.geometry.Offset(cx, topY), androidx.compose.ui.geometry.Offset(cx + w * 0.16f, topY + h * 0.2f), strokeWidth = sw)
            }
        }
    }
}

/**
 * Draws a page's strokes auto-fit to the canvas. Brand ink (color 0) renders in the signature
 * blue→violet gradient; user-picked colors render solid; selected strokes use [highlight] + thicker.
 * [strokeScale] is the handwriting-size preset multiplier (#15b) — see [strokeBaseWidthPx].
 */
internal fun DrawScope.drawStrokes(
    strokes: List<StrokeEntity>,
    points: (StrokeEntity) -> List<Point>,
    base: Color,
    selected: Set<String> = emptySet(),
    highlight: Color = base,
    brandInk: Color = base,
    glowLast: Color? = null,
    pageBounds: PageGeometry? = null,
    revealPhase: Float = 1f, // 0=invisible → 1=fully drawn; staggered per-stroke alpha reveal
    cache: StrokeRenderCache = defaultStrokeRenderCache,
    strokeScale: Float = 1f,
) {
    val fit = inkFit(strokes, points, size.width, size.height, pageBounds) ?: return
    val baseW = strokeBaseWidthPx(size.width, strokeScale)
    // Live capture: a soft halo under the newest stroke so fresh ink reads as "drawing on". Drawn
    // first (under) and bounded to one stroke + two passes, so the crisp ink stays on top and the
    // capture canvas takes no per-frame animation cost.
    if (glowLast != null) {
        strokes.lastOrNull()?.let { s ->
            val raw = cache.points(s, points)
            if (raw.isNotEmpty()) {
                val pts = raw.map { fit.map(it.x, it.y) }
                val pr = raw.map { it.pressure }
                val w = baseW * s.width
                drawPath(freehandPath(pts, pr, w * 3.0f), glowLast.copy(alpha = 0.10f))
                drawPath(freehandPath(pts, pr, w * 2.0f), glowLast.copy(alpha = 0.16f))
            }
        }
    }
    val n = strokes.size.coerceAtLeast(1)
    strokes.forEachIndexed { i, s ->
        // Staggered reveal: stroke i becomes fully opaque when phase > (i+1)/n, and fades in as
        // phase crosses its window. phase=1 → all strokes at alpha=1 (normal rendering).
        val strokeAlpha = ((revealPhase * n) - i).coerceIn(0f, 1f)
        if (strokeAlpha == 0f) return@forEachIndexed
        val raw = cache.points(s, points)
        if (raw.isEmpty()) return@forEachIndexed
        val sel = s.uuid in selected
        val w = baseW * s.width * (if (sel) 1.6f else 1f)
        val color = when {
            sel -> highlight
            s.color != 0 -> Color(s.color)
            else -> brandInk // default ink = theme foreground
        }
        val path = cache.pathFor(s.uuid, s.width, color.toArgb(), sel, baseW, fit) {
            val pts = raw.map { fit.map(it.x, it.y) }
            val pr = raw.map { it.pressure }
            freehandPath(pts, pr, w)
        }
        drawPath(path, color.copy(alpha = strokeAlpha))
    }
}

/**
 * Maps raw Ncode paper coordinates onto the canvas. We don't assume a fixed page size — the pen's
 * coordinate range varies by notebook — so we fit the page's ink to the canvas bounds (aspect kept),
 * exactly like the SVG export. [map] takes a raw point to a canvas pixel.
 */
internal class InkFit(val scale: Float, val offX: Float, val offY: Float, val minX: Float, val minY: Float) {
    fun map(x: Float, y: Float) = Offset((x - minX) * scale + offX, (y - minY) * scale + offY)
}

/** Centre [minX,minY]..[maxX,maxY] in a [width]×[height] canvas with a [marginFrac] margin; tighter axis
 *  limits scale. [marginFrac] 0 = edge-to-edge (used for the calibrated full-sheet fit). */
internal fun fitBounds(minX: Float, minY: Float, maxX: Float, maxY: Float, width: Float, height: Float, marginFrac: Float = 0.06f): InkFit? {
    if (width <= 0f || height <= 0f) return null
    val margin = minOf(width, height) * marginFrac
    val cw = (maxX - minX).coerceAtLeast(1f)
    val ch = (maxY - minY).coerceAtLeast(1f)
    val scale = minOf((width - 2 * margin) / cw, (height - 2 * margin) / ch)
    return InkFit(scale, (width - cw * scale) / 2f, (height - ch * scale) / 2f, minX, minY)
}

internal fun inkFit(
    strokes: List<StrokeEntity>,
    points: (StrokeEntity) -> List<Point>,
    width: Float,
    height: Float,
    pageBounds: PageGeometry? = null,
): InkFit? {
    // Full-page-from-start: when the notebook's geometry is known, fit the WHOLE physical sheet (not just
    // the writable dot area) so the page renders at true edge-to-edge scale — the printed ruling/margins
    // land where they physically are, and (with the canvas constrained to the sheet aspect) there's no
    // letterboxing. Opens at true page scale before the first dot instead of auto-zooming to the strokes.
    if (pageBounds != null) {
        val f = sheetFrame(pageBounds)
        return fitBounds(f.x0, f.y0, f.x0 + f.wUnits, f.y0 + f.hUnits, width, height, marginFrac = 0f)
    }
    var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
    var any = false
    strokes.forEach { s ->
        points(s).forEach { p ->
            any = true
            if (p.x < minX) minX = p.x; if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y; if (p.y > maxY) maxY = p.y
        }
    }
    if (!any) return null
    return fitBounds(minX, minY, maxX, maxY, width, height)
}

/**
 * Read-only dot-grid ink surface — shared by live capture and the non-editing page view.
 *
 * [revealPageId]: when non-null, triggers the "self-drawing" open-reveal animation (staggered
 * alpha per stroke). Only active on page-open (keyed on the page id); live-glow path bypasses it.
 * Performance guard: skips the animation when the stroke count exceeds [REVEAL_STROKE_LIMIT] or
 * reduced motion is on — both snap straight to fully-drawn.
 */
@Composable
internal fun InkSurface(
    strokes: List<StrokeEntity>,
    vm: InkViewModel,
    modifier: Modifier = Modifier,
    background: ImageBitmap? = null,
    liveGlow: Boolean = false,
    pageBounds: PageGeometry? = null,
    ruling: Ruling? = null,
    pageStyle: PageStyle = PageStyle.LINED,
    pageNumber: Int? = null,
    zones: List<com.nibhaus.zones.ActionZone> = emptyList(), // printed-button tap targets to mark
    revealPageId: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    val paper = LocalInkPaper.current // page canvas ("paper") tone — themed independently of the chrome
    val paperTemplate = LocalPaperTemplate.current
    val lined = paperTemplate == PaperTemplate.LINED
    // A calibrated notebook (known geometry + ruling, no custom background image, not a cover) draws the
    // REAL printed page over the ink; when that's on and the user wants lines, suppress the generic ruling.
    val calibrated = background == null && pageBounds != null && ruling != null && pageStyle != PageStyle.COVER
    val bgTemplate = if (calibrated && lined) PaperTemplate.BLANK else paperTemplate
    val paperInk = InkTokens.dotColor(paper.ink) // hoisted: @Composable, so resolve outside the draw lambda
    // Handwriting size preset (#15b) — hoisted here (both live capture and page view render through
    // this one InkSurface) so drawStrokes' single width source picks it up without per-call-site wiring.
    val strokeScale by vm.strokeScale.collectAsStateWithLifecycle()
    val reduced = rememberReducedMotion()
    // Reveal: staggered alpha fade-in of strokes on page open. Only runs for the page-detail view
    // (revealPageId non-null, liveGlow false). Snaps to 1f for live capture, reduced motion, or
    // pages with too many strokes (prevents per-frame path-measure storm on dense pages).
    val revealPhase = remember(revealPageId) {
        Animatable(if (revealPageId == null || liveGlow || reduced) 1f else 0f)
    }
    LaunchedEffect(revealPageId) {
        if (revealPageId != null && !liveGlow && !reduced && strokes.size <= REVEAL_STROKE_LIMIT) {
            revealPhase.snapTo(0f)
            revealPhase.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = (800 + strokes.size * 90).coerceAtMost(2400),
                    easing = NibEasing,
                ),
            )
        } else {
            revealPhase.snapTo(1f)
        }
    }
    // A calibrated page is shaped to the physical sheet (e.g. 13.75:21), so it renders edge-to-edge with
    // no letterbox margins; height-first keeps the whole page inside the available space.
    val pageMod = if (calibrated && pageBounds != null) {
        modifier.aspectRatio(pageBounds.pageWidthMm / pageBounds.pageHeightMm, matchHeightConstraintsFirst = true)
    } else {
        modifier
    }
    Card(
        pageMod,
        colors = CardDefaults.cardColors(containerColor = paper.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        // Read-view magnifier: pinch to zoom, drag to pan, double-tap to zoom to 2.5x centred on the
        // tap (double-tap again to reset to fit — see doubleTapZoomTarget). This is a pure draw-time
        // transform (graphicsLayer scale/translate) LAYERED OVER the existing render — it does NOT
        // touch inkFit/the page fit. It just lets you magnify what's already there, which is what
        // makes a full page readable on a small screen. The Card clips, so zoomed ink stays in its
        // bounds; pan is clamped so the content can't be flung fully off-screen. Live capture
        // (liveGlow) stays locked at 1× so incoming ink is never off-screen mid-write.
        var scale by remember(revealPageId) { mutableStateOf(1f) }
        var offset by remember(revealPageId) { mutableStateOf(Offset.Zero) }
        var boxSize by remember { mutableStateOf(IntSize.Zero) }
        val zoomable = !liveGlow
        val base = Modifier
            .fillMaxSize()
            .onSizeChanged { boxSize = it }
            .then(
                if (zoomable) Modifier
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { tapPoint ->
                            val target = doubleTapZoomTarget(scale, tapPoint, boxSize)
                            scale = target.scale
                            offset = target.offset
                        })
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val next = (scale * zoom).coerceIn(1f, 6f)
                            // Clamp pan to the overflow the scale produces (graphicsLayer scales about
                            // centre), so you can reach every edge but not push the page off-screen.
                            val maxX = (boxSize.width * (next - 1f)) / 2f
                            val maxY = (boxSize.height * (next - 1f)) / 2f
                            offset = if (next > 1f) Offset(
                                (offset.x + pan.x).coerceIn(-maxX, maxX),
                                (offset.y + pan.y).coerceIn(-maxY, maxY),
                            ) else Offset.Zero
                            scale = next
                        }
                    }
                    .graphicsLayer {
                        scaleX = scale; scaleY = scale
                        translationX = offset.x; translationY = offset.y
                    }
                else Modifier,
            )
        Box(Modifier.fillMaxSize()) {
            Canvas(if (background == null) base.ncodePaper(bgTemplate, paperInk) else base) {
                background?.let { drawPageBackground(it) }
                if (calibrated) {
                    drawCalibratedPaper(
                        strokes, vm::strokesFlowOf, pageBounds!!, ruling!!, pageNumber, pageStyle, lined, paperInk,
                        numberColor = paper.ink.copy(alpha = 0.55f), // legible print tone on any paper
                        zones = zones,
                    )
                }
                drawStrokes(
                    strokes, vm::strokesFlowOf, cs.primary, brandInk = paper.ink,
                    glowLast = if (liveGlow) cs.primary else null,
                    pageBounds = pageBounds,
                    revealPhase = revealPhase.value,
                    strokeScale = strokeScale.multiplier,
                )
            }
            // Pinch-to-zoom is disabled during live capture (zoomable = !liveGlow above) so incoming
            // ink never scrolls off-screen mid-write. Without a cue, the disabled pinch just looks
            // broken — this glyph makes it read as intentional.
            if (!zoomable) {
                Icon(
                    Icons.Outlined.Lock,
                    contentDescription = "Zoom locked during live capture",
                    tint = cs.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .size(16.dp),
                )
            }
        }
    }
}

/** The scale/offset [InkSurface]'s read-view magnifier animates to on a double-tap. */
internal data class ZoomTarget(val scale: Float, val offset: Offset)

/**
 * Double-tap zoom (#14): tap once at [tapPoint] to zoom to [zoomScale]x centred on it; tap again
 * anywhere while already zoomed in to reset to fit (1x) — a simple toggle on the *current* scale,
 * not the tap location. [tapPoint]/[boxSize] are in the same pre-transform box coordinate space the
 * pinch/pan `pointerInput` above reports (it runs outside the `graphicsLayer`), matching that block's
 * own pan-clamp math. Pure so the offset-clamp arithmetic is directly unit-testable without Compose.
 */
internal fun doubleTapZoomTarget(
    currentScale: Float,
    tapPoint: Offset,
    boxSize: IntSize,
    zoomScale: Float = 2.5f,
): ZoomTarget {
    if (currentScale > 1f + ZOOM_RESET_EPSILON) return ZoomTarget(1f, Offset.Zero)
    if (boxSize.width <= 0 || boxSize.height <= 0) return ZoomTarget(1f, Offset.Zero)
    val center = Offset(boxSize.width / 2f, boxSize.height / 2f)
    // Same clamp shape as the pinch/pan handler: graphicsLayer scales about the box centre, so the
    // reachable translation range at [zoomScale] is ±(size * (scale-1)) / 2 per axis.
    val maxX = (boxSize.width * (zoomScale - 1f)) / 2f
    val maxY = (boxSize.height * (zoomScale - 1f)) / 2f
    val raw = (center - tapPoint) * zoomScale
    return ZoomTarget(zoomScale, Offset(raw.x.coerceIn(-maxX, maxX), raw.y.coerceIn(-maxY, maxY)))
}

private const val ZOOM_RESET_EPSILON = 0.01f

/** Draws a notebook's template image behind the ink, aspect-fit and centred to the page canvas. */
internal fun DrawScope.drawPageBackground(image: ImageBitmap) {
    val s = minOf(size.width / image.width, size.height / image.height)
    val w = image.width * s; val h = image.height * s
    drawImage(
        image,
        srcOffset = IntOffset.Zero, srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset(((size.width - w) / 2f).toInt(), ((size.height - h) / 2f).toInt()),
        dstSize = IntSize(w.toInt(), h.toInt()),
    )
}

// Self-drawing reveal: skip the per-stroke alpha animation above this count to avoid per-frame
// path-measure overhead on dense pages (they snap straight to fully-drawn).
private const val REVEAL_STROKE_LIMIT = 300
