package com.nibhaus.ink

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Pressure-tapered freehand stroke outline — a compact port of the idea behind perfect-freehand
 * (Steve Ruiz, MIT): low-pass the centre line, vary half-width by pressure, and emit a closed fill
 * polygon with rounded ends. Pure (no Compose/Android), so it's unit-testable and shared by the
 * on-screen renderer and the raster (print / share) renderer.
 *
 * Coordinates are whatever space the caller passes (we use canvas / bitmap px). [pressures] is per
 * input point and is normalised *within the stroke* — the pen's absolute pressure range varies by
 * model, so relative pressure gives a reliable taper regardless of scale.
 *
 * Note (known limitation): no mitre/corner joins or variable end-taper like the full library;
 * a ribbon + 180° caps reads well for the smartpen's dense sampling. Upgrade path: real
 * perfect-freehand `getStroke`, or the Jetpack Ink renderer once its Compose support lands.
 *
 * Returns a flat `[x0,y0,x1,y1,…]` polygon to fill; empty if there's nothing to draw.
 */
fun strokeOutline(
    xs: FloatArray,
    ys: FloatArray,
    pressures: FloatArray,
    width: Float,
    thinning: Float = 0.5f,
    streamline: Float = 0.5f,
    capSteps: Int = 8,
    smoothingSpacing: Float = 3.5f,
): FloatArray {
    val n0 = xs.size
    val half = width / 2f
    if (n0 == 0 || half <= 0f) return FloatArray(0)
    if (n0 == 1) return circlePolygon(xs[0], ys[0], half, capSteps * 2)

    // 1) Low-pass the centre line (streamline) to kill jitter.
    val sx = FloatArray(n0); val sy = FloatArray(n0)
    sx[0] = xs[0]; sy[0] = ys[0]
    val k = (1f - streamline).coerceIn(0.05f, 1f)
    for (i in 1 until n0) {
        sx[i] = sx[i - 1] + (xs[i] - sx[i - 1]) * k
        sy[i] = sy[i - 1] + (ys[i] - sy[i - 1]) * k
    }

    // 2) Per-point radius from pressure, normalised within the stroke.
    var pMin = Float.MAX_VALUE; var pMax = -Float.MAX_VALUE
    for (p in pressures) { if (p < pMin) pMin = p; if (p > pMax) pMax = p }
    val span = pMax - pMin
    val sr = FloatArray(n0) { i ->
        val pn = if (span > 1e-3f) ((pressures.getOrElse(i) { pMax } - pMin) / span) else 1f
        half * ((1f - thinning) + thinning * pn)
    }

    // 2.5) Resample the centre line so a sparsely-sampled stroke (few dots + long chords — e.g. a fast
    // pen, or the M1+'s coarser stream) renders as a smooth curve instead of straight facets. A
    // densely-sampled stroke (LAMY) has every segment already <= smoothingSpacing, so the guard below
    // returns it untouched — its outline is byte-identical to before. Interpolation follows the
    // existing (already-streamlined) centre line via Catmull-Rom, so it never reshapes the curve; it
    // only fills faceted gaps, at whatever resolution the caller's coordinate space implies (px here),
    // which makes the smoothness resolution-appropriate on small thumbnails vs a full-page canvas.
    val cx: FloatArray; val cy: FloatArray; val radii: FloatArray
    if (smoothingSpacing > 0f && needsResample(sx, sy, smoothingSpacing)) {
        val (rx3, ry3, rr3) = resampleCentreLine(sx, sy, sr, smoothingSpacing)
        cx = rx3; cy = ry3; radii = rr3
    } else {
        cx = sx; cy = sy; radii = sr
    }
    val n = cx.size

    // 3) Left/right offsets along the perpendicular; remember the unit tangent for the caps.
    val lx = FloatArray(n); val ly = FloatArray(n); val rx = FloatArray(n); val ry = FloatArray(n)
    val tx = FloatArray(n); val ty = FloatArray(n)
    for (i in 0 until n) {
        val dx = when { i == 0 -> cx[1] - cx[0]; i == n - 1 -> cx[n - 1] - cx[n - 2]; else -> cx[i + 1] - cx[i - 1] }
        val dy = when { i == 0 -> cy[1] - cy[0]; i == n - 1 -> cy[n - 1] - cy[n - 2]; else -> cy[i + 1] - cy[i - 1] }
        val len = hypot(dx, dy).coerceAtLeast(1e-4f)
        val ux = dx / len; val uy = dy / len
        tx[i] = ux; ty[i] = uy
        val nx = -uy; val ny = ux // left-hand normal
        lx[i] = cx[i] + nx * radii[i]; ly[i] = cy[i] + ny * radii[i]
        rx[i] = cx[i] - nx * radii[i]; ry[i] = cy[i] - ny * radii[i]
    }

    // 4) Assemble: left edge → round end cap → right edge (reversed) → round start cap.
    val out = ArrayList<Float>((n * 2 + capSteps * 2 + 4) * 2)
    for (i in 0 until n) { out.add(lx[i]); out.add(ly[i]) }
    appendCap(out, cx[n - 1], cy[n - 1], -ty[n - 1], tx[n - 1], tx[n - 1], ty[n - 1], radii[n - 1], capSteps, 1f)
    for (i in n - 1 downTo 0) { out.add(rx[i]); out.add(ry[i]) }
    appendCap(out, cx[0], cy[0], -ty[0], tx[0], tx[0], ty[0], radii[0], capSteps, -1f)
    return out.toFloatArray()
}

/** True if any segment of the centre line is longer than [spacing] — i.e. resampling would change it.
 *  A stroke already sampled finer than [spacing] (LAMY) returns false → left byte-identical. */
private fun needsResample(cx: FloatArray, cy: FloatArray, spacing: Float): Boolean {
    for (i in 0 until cx.size - 1) if (hypot(cx[i + 1] - cx[i], cy[i + 1] - cy[i]) > spacing) return true
    return false
}

/** Subdivide each long centre-line segment into <=[spacing]-length pieces along the Catmull-Rom curve
 *  through the points, carrying radius by linear interpolation. Adds points ON the existing curve, so
 *  the stroke shape is preserved — only the faceting is removed. Per-segment subdivision is capped so
 *  a single very long chord can't explode the vertex count. */
private fun resampleCentreLine(
    cx: FloatArray, cy: FloatArray, r: FloatArray, spacing: Float,
): Triple<FloatArray, FloatArray, FloatArray> {
    val n = cx.size
    val ox = ArrayList<Float>(n * 3); val oy = ArrayList<Float>(n * 3); val orr = ArrayList<Float>(n * 3)
    for (i in 0 until n - 1) {
        val p0 = if (i - 1 < 0) i else i - 1
        val p3 = if (i + 2 > n - 1) i + 1 else i + 2
        val segLen = hypot(cx[i + 1] - cx[i], cy[i + 1] - cy[i])
        val steps = if (segLen > spacing) ceil(segLen / spacing).toInt().coerceAtMost(64) else 1
        for (s in 0 until steps) {
            val t = s.toFloat() / steps
            val t2 = t * t; val t3 = t2 * t
            ox.add(0.5f * (2f * cx[i] + (-cx[p0] + cx[i + 1]) * t + (2f * cx[p0] - 5f * cx[i] + 4f * cx[i + 1] - cx[p3]) * t2 + (-cx[p0] + 3f * cx[i] - 3f * cx[i + 1] + cx[p3]) * t3))
            oy.add(0.5f * (2f * cy[i] + (-cy[p0] + cy[i + 1]) * t + (2f * cy[p0] - 5f * cy[i] + 4f * cy[i + 1] - cy[p3]) * t2 + (-cy[p0] + 3f * cy[i] - 3f * cy[i + 1] + cy[p3]) * t3))
            orr.add(r[i] + (r[i + 1] - r[i]) * t)
        }
    }
    ox.add(cx[n - 1]); oy.add(cy[n - 1]); orr.add(r[n - 1])
    return Triple(ox.toFloatArray(), oy.toFloatArray(), orr.toFloatArray())
}

/** Half-circle of points (excluding the two edge endpoints) bulging along the tangent. */
private fun appendCap(
    out: ArrayList<Float>, cx: Float, cy: Float,
    nx: Float, ny: Float, tx: Float, ty: Float, r: Float, steps: Int, sign: Float,
) {
    for (j in 1 until steps) {
        val theta = PI.toFloat() * j / steps
        val c = cos(theta); val s = sin(theta)
        out.add(cx + sign * (c * nx + s * tx) * r)
        out.add(cy + sign * (c * ny + s * ty) * r)
    }
}

private fun circlePolygon(cx: Float, cy: Float, r: Float, steps: Int): FloatArray {
    val out = FloatArray(steps * 2)
    for (i in 0 until steps) {
        val a = 2f * PI.toFloat() * i / steps
        out[2 * i] = cx + cos(a) * r
        out[2 * i + 1] = cy + sin(a) * r
    }
    return out
}
