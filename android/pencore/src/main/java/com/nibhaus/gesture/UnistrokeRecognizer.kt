package com.nibhaus.gesture

import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A deterministic, on-device **point-cloud** gesture recognizer (the $P/$Q family — Vatavu,
 * Anthony & Wobbrock, ICMI 2012 / 2018) for the small Ncode hot-zone vocabulary — check, star,
 * box, circle. No ML, no network. It replaces the earlier $1 unistroke recognizer: $1 is sensitive
 * to a stroke's *start point and drawing direction* (it normalizes by an "indicative angle" and
 * matches point-for-point in order), so the same box drawn from a different corner, or a circle
 * drawn counter-clockwise, scores worse. The point-cloud approach treats a stroke as an unordered
 * cloud and matches via greedy nearest-point assignment, making it naturally invariant to start
 * point, direction, and (after scaling/centering) position and size — the right fit for marks a
 * user dashes off in a printed action zone without thinking about stroke order.
 *
 * Pipeline: resample to [N] points, scale to a unit bounding box, translate the centroid to the
 * origin, then score each template with [greedyCloudMatch] (the lower the cloud distance, the
 * better). Pure (pencore) → unit-tested. Thresholds were calibrated against the test fixtures
 * (clean shapes land at cloud-distance ≤ ~0.52; a plain straight stroke lands at ~3.5), so a strict
 * [maxDistance] plus a best-vs-second [minMargin] keeps ordinary writing from being read as a
 * command. Note: uses exact greedy cloud-matching rather than $Q's integer LUT / lower-bound
 * speedups — those only matter for large template sets, and ours is four marks recognized on the
 * rare hot-zone tap. Upgrade path: add the $Q LUT if the vocabulary ever grows large.
 */
object UnistrokeRecognizer {

    data class Pt(val x: Float, val y: Float)
    data class Match(val name: String, val score: Float)

    private const val N = 32

    private val templates: Map<String, List<Pt>> by lazy {
        rawTemplates().mapValues { normalize(it.value) }
    }

    /**
     * Recognize a stroke, or null if nothing clears the thresholds. [maxDistance] is the worst
     * cloud distance still accepted; [minMargin] is the required gap between the best and
     * second-best template so an ambiguous mark isn't force-fit to a command.
     */
    fun recognize(points: List<Pt>, maxDistance: Double = 1.2, minMargin: Double = 0.3): Match? {
        if (points.size < 8) return null
        if (pathLength(points) < 1e-3) return null
        val candidate = normalize(points)
        val scored = templates
            .map { (name, t) -> name to greedyCloudMatch(candidate, t) }
            .sortedBy { it.second }
        val (name, best) = scored.first()
        val second = scored.getOrNull(1)?.second ?: Double.MAX_VALUE
        return if (best <= maxDistance && second - best >= minMargin) {
            Match(name, (1.0 - best).coerceAtLeast(0.0).toFloat())
        } else {
            null
        }
    }

    // --- point-cloud pipeline ---

    private fun normalize(pts: List<Pt>): List<Pt> =
        translateToOrigin(scaleToUnit(resample(pts, N)))

    private fun resample(points: List<Pt>, n: Int): List<Pt> {
        val interval = pathLength(points) / (n - 1)
        var accumulated = 0.0
        val out = ArrayList<Pt>(n)
        out.add(points.first())
        val pts = points.toMutableList()
        var i = 1
        while (i < pts.size) {
            val prev = pts[i - 1]; val cur = pts[i]
            val d = dist(prev, cur)
            if (accumulated + d >= interval) {
                val t = if (d == 0.0) 0.0 else (interval - accumulated) / d
                val np = Pt((prev.x + t * (cur.x - prev.x)).toFloat(), (prev.y + t * (cur.y - prev.y)).toFloat())
                out.add(np)
                pts.add(i, np)
                accumulated = 0.0
            } else {
                accumulated += d
            }
            i++
        }
        while (out.size < n) out.add(points.last())
        return out.subList(0, n)
    }

    /** Uniform scale (aspect-preserving) so the larger bounding-box dimension spans 1.0. */
    private fun scaleToUnit(pts: List<Pt>): List<Pt> {
        val minX = pts.minOf { it.x }; val maxX = pts.maxOf { it.x }
        val minY = pts.minOf { it.y }; val maxY = pts.maxOf { it.y }
        val size = max(max((maxX - minX).toDouble(), (maxY - minY).toDouble()), 1e-9)
        return pts.map { Pt(((it.x - minX) / size).toFloat(), ((it.y - minY) / size).toFloat()) }
    }

    private fun translateToOrigin(pts: List<Pt>): List<Pt> {
        val c = centroid(pts)
        return pts.map { Pt(it.x - c.x, it.y - c.y) }
    }

    /**
     * Minimum cloud distance between two equally-sized point clouds, probing several start indices
     * (step ≈ √n) in both directions — the $P "greedy cloud match". Direction/start invariant.
     */
    private fun greedyCloudMatch(a: List<Pt>, b: List<Pt>): Double {
        val n = a.size
        val step = floor(sqrt(n.toDouble())).toInt().coerceAtLeast(1)
        var best = Double.MAX_VALUE
        var i = 0
        while (i < n) {
            best = minOf(best, cloudDistance(a, b, i), cloudDistance(b, a, i))
            i += step
        }
        return best
    }

    /**
     * Weighted sum of greedy nearest-neighbour distances, walking [pts] from [start] and matching
     * each to its closest still-unmatched point in [tmpl]. Earlier matches (when more of the cloud
     * is still free) weigh more, which penalizes shape mismatch over alignment noise.
     */
    private fun cloudDistance(pts: List<Pt>, tmpl: List<Pt>, start: Int): Double {
        val n = pts.size
        val matched = BooleanArray(n)
        var sum = 0.0
        var i = start
        do {
            var best = Double.MAX_VALUE
            var index = -1
            for (j in 0 until n) {
                if (!matched[j]) {
                    val d = dist(pts[i], tmpl[j])
                    if (d < best) { best = d; index = j }
                }
            }
            matched[index] = true
            val weight = 1.0 - ((i - start + n) % n) / n.toDouble()
            sum += weight * best
            i = (i + 1) % n
        } while (i != start)
        return sum
    }

    private fun pathLength(pts: List<Pt>): Double {
        var d = 0.0
        for (i in 1 until pts.size) d += dist(pts[i - 1], pts[i])
        return d
    }

    private fun centroid(pts: List<Pt>): Pt {
        var x = 0.0; var y = 0.0
        for (p in pts) { x += p.x; y += p.y }
        return Pt((x / pts.size).toFloat(), (y / pts.size).toFloat())
    }

    private fun dist(a: Pt, b: Pt): Double = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())

    // --- canonical templates (any scale; the pipeline normalizes them) ---

    private fun rawTemplates(): Map<String, List<Pt>> = mapOf(
        "circle" to (0..360 step 12).map { Pt((50 + 50 * Math.cos(Math.toRadians(it.toDouble()))).toFloat(), (50 + 50 * Math.sin(Math.toRadians(it.toDouble()))).toFloat()) },
        "box" to listOf(Pt(0f, 0f), Pt(100f, 0f), Pt(100f, 100f), Pt(0f, 100f), Pt(0f, 0f)),
        // checkmark: short dip down-right, long rise up-right.
        "check" to listOf(Pt(0f, 50f), Pt(33f, 100f), Pt(100f, 0f)),
        "star" to pentagram(),
    )

    /** A one-stroke 5-point star (pentagram) traced point→point→… and closed. */
    private fun pentagram(): List<Pt> {
        val outer = (0 until 5).map {
            val a = Math.toRadians(-90.0 + it * 72.0)
            Pt((50 + 50 * Math.cos(a)).toFloat(), (50 + 50 * Math.sin(a)).toFloat())
        }
        val order = intArrayOf(0, 2, 4, 1, 3, 0)
        return order.map { outer[it] }
    }
}
