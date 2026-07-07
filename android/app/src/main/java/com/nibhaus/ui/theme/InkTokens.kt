package com.nibhaus.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import com.nibhaus.export.PaperTemplate
import com.nibhaus.export.paperRowPositions
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor

/**
 * The non-Material tokens that make the "Ink & Ncode" identity (design-system §6–7): the dot-grid
 * lattice the pen reads, and the ink stroke geometry. Centralized here so capture, page detail, and
 * thumbnails share one implementation.
 */
object InkTokens {
    // Ncode dot-grid (§6).
    val dotSpacing: Dp = 15.dp
    val dotRadius: Dp = 1.1.dp
    val dotOffset: Dp = 9.dp

    // Ink stroke geometry (§7).
    val inkWidthMin: Dp = 1.4.dp
    val inkWidthMax: Dp = 3.6.dp
    val inkWidthBase: Dp = 2.6.dp
    val highlighterWidth: Dp = 7.dp
    const val highlighterAlpha = 0.32f

    /** Dot tint: onBackground @13% (light) / onSurface @10% (dark) — read inside composition. */
    @Composable
    @ReadOnlyComposable
    fun dotColor(base: Color): Color =
        base.copy(alpha = if (LocalInkExtras.current.isDark) 0.14f else 0.13f)

    /** Pressure-modulated stroke width (0..1 pressure → [inkWidthMin]..[inkWidthMax]). */
    fun inkWidthFor(pressure: Float): Float {
        val p = pressure.coerceIn(0f, 1f)
        return inkWidthMin.value + (inkWidthMax.value - inkWidthMin.value) * p
    }

    /** The signature ink gradient (blue→indigo→violet) across [size], for brand-ink strokes (§v2). */
    fun inkBrush(size: Size): Brush =
        Brush.linearGradient(InkGradientStops, start = Offset(0f, 0f), end = Offset(size.width, size.height))
}

/**
 * Tiles the Ncode dot lattice behind content (§6). Scales naturally with the layout; for the
 * page-detail zoom, multiply [spacing] by the zoom factor at the call site.
 */
fun Modifier.ncodeDotGrid(
    color: Color,
    spacing: Dp = InkTokens.dotSpacing,
    radius: Dp = InkTokens.dotRadius,
    offset: Dp = InkTokens.dotOffset,
): Modifier = drawBehind {
    val step = spacing.toPx()
    if (step <= 0f) return@drawBehind
    val r = radius.toPx()
    val o = offset.toPx()
    var y = o
    while (y < size.height) {
        var x = o
        while (x < size.width) {
            drawCircle(color = color, radius = r, center = Offset(x, y))
            x += step
        }
        y += step
    }
}

/** Horizontal rules behind content (the "lined" paper template), rows via [paperRowPositions]. */
fun Modifier.ncodeLined(
    color: Color,
    spacing: Dp = InkTokens.dotSpacing,
    offset: Dp = InkTokens.dotOffset,
): Modifier = drawBehind {
    val step = spacing.toPx()
    if (step <= 0f) return@drawBehind
    val sw = 1.dp.toPx()
    for (y in paperRowPositions(size.height, step, offset.toPx())) {
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = sw)
    }
}

/** Draws the notebook's [PaperTemplate] behind content: dots, rules, or nothing (blank). */
fun Modifier.ncodePaper(
    template: PaperTemplate,
    color: Color,
    spacing: Dp = InkTokens.dotSpacing,
    radius: Dp = InkTokens.dotRadius,
    offset: Dp = InkTokens.dotOffset,
): Modifier = when (template) {
    PaperTemplate.BLANK -> this
    PaperTemplate.DOT_GRID -> ncodeDotGrid(color, spacing, radius, offset)
    PaperTemplate.LINED -> ncodeLined(color, spacing, offset)
}

/** The active page paper template; provided from the user setting at the app root (MainActivity). */
val LocalPaperTemplate = staticCompositionLocalOf { PaperTemplate.DEFAULT }

/**
 * Builds a smooth ink [Path] through captured points with Catmull-Rom → cubic-Bézier conversion
 * (§7) — no jagged polylines. Points are in the caller's coordinate space (apply scaling first).
 */
fun inkPath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points[0].x, points[0].y)
    if (points.size == 1) return path
    for (i in 0 until points.size - 1) {
        val p0 = points[if (i - 1 < 0) i else i - 1]
        val p1 = points[i]
        val p2 = points[i + 1]
        val p3 = points[if (i + 2 > points.size - 1) i + 1 else i + 2]
        // Catmull-Rom (tension 0) control points for the segment p1→p2.
        val c1 = Offset(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f)
        val c2 = Offset(p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f)
        path.cubicTo(c1.x, c1.y, c2.x, c2.y, p2.x, p2.y)
    }
    return path
}

/**
 * Filled, pressure-tapered ink outline (perfect-freehand style) — the natural-pen look. [points]
 * are in the caller's coordinate space; [pressures] is per point. Fill this path (no stroke style).
 */
fun freehandPath(points: List<Offset>, pressures: List<Float>, width: Float): Path {
    val n = points.size
    val xs = FloatArray(n) { points[it].x }
    val ys = FloatArray(n) { points[it].y }
    val pr = FloatArray(n) { pressures.getOrElse(it) { 1f } }
    val o = com.nibhaus.ink.strokeOutline(xs, ys, pr, width)
    val path = Path()
    if (o.size < 4) return path
    path.moveTo(o[0], o[1])
    var i = 2
    while (i < o.size) { path.lineTo(o[i], o[i + 1]); i += 2 }
    path.close()
    return path
}

/**
 * The "vault frame" steel-hairline border — v3 "rim" (§5): a brushed-steel bezel that catches light
 * at the corners — a faint white highlight, into steel, into steel-deep, back to a faint highlight,
 * ~140°. On light theme steel reads heavy on white, so it softens to a flat `outlineVariant` hairline.
 */
@Composable
fun Modifier.steelBorder(shape: Shape, width: Dp = 1.dp): Modifier {
    val extras = LocalInkExtras.current
    val brush = if (extras.isDark) {
        Brush.linearGradient(
            0.00f to extras.bezel1.copy(alpha = 0.70f),
            0.30f to extras.bezel2.copy(alpha = 0.26f),
            0.70f to extras.bezel3.copy(alpha = 0.40f),
            1.00f to extras.bezel1.copy(alpha = 0.22f),
        )
    } else {
        SolidColor(MaterialTheme.colorScheme.outlineVariant)
    }
    return border(width, brush, shape)
}

/**
 * Soft gradient glow (§4/§6): a tinted indigo halo behind FABs / badges / active controls. Dark
 * only — the light scheme's glow is transparent (§1) — and `clip = false` so it bleeds past the
 * shape. (A tinted shadow; a true 0-offset blurred halo would need a RenderEffect layer.)
 */
@Composable
fun Modifier.glow(shape: Shape, elevation: Dp = 16.dp): Modifier {
    val extras = LocalInkExtras.current
    return if (extras.isDark) {
        val glowColor = extras.glow
        shadow(elevation, shape, clip = false, ambientColor = glowColor.copy(alpha = 0.45f), spotColor = glowColor.copy(alpha = 0.55f))
    } else {
        this
    }
}
