package com.nibhaus.ui

import androidx.collection.LruCache
import androidx.compose.ui.graphics.Path
import com.nibhaus.data.Point
import com.nibhaus.data.StrokeEntity
import com.nibhaus.ui.common.InkFit

/**
 * Memoizes the two expensive per-stroke steps [drawStrokes] used to redo on EVERY redraw
 * (perf audit P0-1): decoding a completed stroke's `pointsJson` and tessellating it into a filled
 * outline [Path] ([com.nibhaus.ui.theme.freehandPath] -> [com.nibhaus.ink.strokeOutline]). A
 * dense live-capture page redraws the whole canvas on every new dot; without this, every stroke
 * that was already finished got re-decoded and re-tessellated anyway.
 *
 * This is safe because completed strokes are IMMUTABLE — a [StrokeEntity.uuid] never gets
 * re-pointed to different points once written (append-only per `data/Entities.kt`), so `uuid` alone
 * is a stable key for the decoded points.
 *
 * The tessellated [Path] is cached in CANVAS pixel coordinates (post [InkFit.map]), not page units,
 * because that's simpler to get right: the key folds in everything that can change the rendered
 * shape — the stroke identity, its own width multiplier, the canvas-derived base width, selection
 * (which also scales width), and the [InkFit] the path was built under (scale/offsets/origin). A
 * canvas resize, page switch, or fit recompute just produces a key miss; the stale entries age out
 * of the LRU rather than needing explicit invalidation. Color is folded into the key too even though
 * it doesn't affect the tessellated geometry — cheap insurance, and alpha (the reveal animation) is
 * still applied at draw time via the returned `Color`, never baked into the cached Path.
 */
class StrokeRenderCache(maxEntries: Int = 512) {
    private val pathCache = LruCache<String, Path>(maxEntries)
    private val pointsCache = LruCache<String, List<Point>>(maxEntries)

    /** Decoded points for [stroke], decoding (via [decode]) only on a cache miss. */
    fun points(stroke: StrokeEntity, decode: (StrokeEntity) -> List<Point>): List<Point> =
        pointsCache.get(stroke.uuid) ?: decode(stroke).also { pointsCache.put(stroke.uuid, it) }

    /**
     * The tessellated [Path] for a stroke drawn with the given look, building it (via [build]) only
     * on a cache miss.
     */
    internal fun pathFor(
        uuid: String,
        strokeWidth: Float,
        colorArgb: Int,
        selected: Boolean,
        baseWidthPx: Float,
        fit: InkFit,
        build: () -> Path,
    ): Path {
        val cacheKey = key(uuid, strokeWidth, colorArgb, selected, baseWidthPx, fit)
        return pathCache.get(cacheKey) ?: build().also { pathCache.put(cacheKey, it) }
    }

    fun evictAll() {
        pathCache.evictAll()
        pointsCache.evictAll()
    }

    companion object {
        /** Round to the nearest 0.1 so sub-pixel float jitter (canvas size, fit math) doesn't thrash
         *  the cache with near-duplicate keys. */
        internal fun quantize(v: Float): Float = kotlin.math.round(v * 10f) / 10f

        internal fun key(
            uuid: String,
            strokeWidth: Float,
            colorArgb: Int,
            selected: Boolean,
            baseWidthPx: Float,
            fit: InkFit,
        ): String {
            val qBase = quantize(baseWidthPx)
            val qScale = quantize(fit.scale)
            val qOffX = quantize(fit.offX)
            val qOffY = quantize(fit.offY)
            val qMinX = quantize(fit.minX)
            val qMinY = quantize(fit.minY)
            return "$uuid:$strokeWidth:$colorArgb:$selected:$qBase:$qScale:$qOffX:$qOffY:$qMinX:$qMinY"
        }
    }
}

/**
 * One process-wide cache, shared by every [drawStrokes] call site (live capture, thumbnails,
 * editable canvas, page view) — strokes are keyed by uuid, which is globally unique and immutable,
 * so sharing is safe and lets the cache survive navigating between screens (e.g. back into a page
 * you already rendered). Plain top-level singleton, matching this codebase's hand-wired-DI style
 * (no framework to scope it through) — see [com.nibhaus.ui.PenLinks] for the same pattern.
 */
internal val defaultStrokeRenderCache = StrokeRenderCache()
