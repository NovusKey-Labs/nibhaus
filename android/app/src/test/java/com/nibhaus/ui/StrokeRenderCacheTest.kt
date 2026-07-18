package com.nibhaus.ui

import androidx.compose.ui.graphics.Path
import com.google.common.truth.Truth.assertThat
import com.nibhaus.data.Point
import com.nibhaus.data.StrokeEntity
import com.nibhaus.data.SyncState
import com.nibhaus.ui.common.InkFit
import org.junit.Test

/** Pure key/cache behavior for the stroke render cache — no Compose runtime or
 *  Android framework needed since [androidx.compose.ui.graphics.Path] and
 *  [androidx.collection.LruCache] are both plain JVM classes. */
class StrokeRenderCacheTest {

    private fun stroke(uuid: String = "s1", width: Float = 1f, color: Int = 0) = StrokeEntity(
        uuid = uuid,
        pageId = "p1",
        color = color,
        startedAt = 0L,
        endedAt = 0L,
        pointsJson = "[]",
        syncState = SyncState.PENDING,
        width = width,
    )

    private fun fit(scale: Float = 2f, offX: Float = 0f, offY: Float = 0f, minX: Float = 0f, minY: Float = 0f) =
        InkFit(scale, offX, offY, minX, minY)

    @Test fun `same inputs hit the cache — build not called again`() {
        val cache = StrokeRenderCache()
        var builds = 0
        val f = fit()
        repeat(3) {
            cache.pathFor("s1", 1f, 0xFF000000.toInt(), selected = false, baseWidthPx = 4f, fit = f) {
                builds++; Path()
            }
        }
        assertThat(builds).isEqualTo(1)
    }

    @Test fun `changed stroke width misses`() {
        val cache = StrokeRenderCache()
        var builds = 0
        val f = fit()
        cache.pathFor("s1", 1f, 0, selected = false, baseWidthPx = 4f, fit = f) { builds++; Path() }
        cache.pathFor("s1", 1.5f, 0, selected = false, baseWidthPx = 4f, fit = f) { builds++; Path() }
        assertThat(builds).isEqualTo(2)
    }

    @Test fun `changed color misses`() {
        val cache = StrokeRenderCache()
        var builds = 0
        val f = fit()
        cache.pathFor("s1", 1f, 0xFF0000, selected = false, baseWidthPx = 4f, fit = f) { builds++; Path() }
        cache.pathFor("s1", 1f, 0x00FF00, selected = false, baseWidthPx = 4f, fit = f) { builds++; Path() }
        assertThat(builds).isEqualTo(2)
    }

    @Test fun `changed selected state misses`() {
        val cache = StrokeRenderCache()
        var builds = 0
        val f = fit()
        cache.pathFor("s1", 1f, 0, selected = false, baseWidthPx = 4f, fit = f) { builds++; Path() }
        cache.pathFor("s1", 1f, 0, selected = true, baseWidthPx = 4f, fit = f) { builds++; Path() }
        assertThat(builds).isEqualTo(2)
    }

    @Test fun `changed fit (canvas resize) misses`() {
        val cache = StrokeRenderCache()
        var builds = 0
        cache.pathFor("s1", 1f, 0, selected = false, baseWidthPx = 4f, fit = fit(scale = 2f)) { builds++; Path() }
        cache.pathFor("s1", 1f, 0, selected = false, baseWidthPx = 4f, fit = fit(scale = 3f)) { builds++; Path() }
        assertThat(builds).isEqualTo(2)
    }

    @Test fun `sub-pixel jitter within quantization does not thrash the cache`() {
        val cache = StrokeRenderCache()
        var builds = 0
        cache.pathFor("s1", 1f, 0, selected = false, baseWidthPx = 4.001f, fit = fit(scale = 2.002f)) { builds++; Path() }
        cache.pathFor("s1", 1f, 0, selected = false, baseWidthPx = 4.004f, fit = fit(scale = 2.004f)) { builds++; Path() }
        assertThat(builds).isEqualTo(1)
    }

    @Test fun `different stroke uuid never collides`() {
        val cache = StrokeRenderCache()
        var builds = 0
        val f = fit()
        cache.pathFor("s1", 1f, 0, selected = false, baseWidthPx = 4f, fit = f) { builds++; Path() }
        cache.pathFor("s2", 1f, 0, selected = false, baseWidthPx = 4f, fit = f) { builds++; Path() }
        assertThat(builds).isEqualTo(2)
    }

    @Test fun `eviction — oldest entry falls out once maxEntries is exceeded`() {
        val cache = StrokeRenderCache(maxEntries = 2)
        var builds = 0
        val f = fit()
        cache.pathFor("s1", 1f, 0, selected = false, baseWidthPx = 4f, fit = f) { builds++; Path() }
        cache.pathFor("s2", 1f, 0, selected = false, baseWidthPx = 4f, fit = f) { builds++; Path() }
        cache.pathFor("s3", 1f, 0, selected = false, baseWidthPx = 4f, fit = f) { builds++; Path() } // evicts s1
        assertThat(builds).isEqualTo(3)
        // s1 was evicted, so asking for it again rebuilds.
        cache.pathFor("s1", 1f, 0, selected = false, baseWidthPx = 4f, fit = f) { builds++; Path() }
        assertThat(builds).isEqualTo(4)
        // s3 is still warm.
        cache.pathFor("s3", 1f, 0, selected = false, baseWidthPx = 4f, fit = f) { builds++; Path() }
        assertThat(builds).isEqualTo(4)
    }

    @Test fun `points decoded once per uuid, reused on second call`() {
        val cache = StrokeRenderCache()
        var decodes = 0
        val pts = listOf(Point(1f, 2f, 1f, 0L))
        val s = stroke()
        val decode: (StrokeEntity) -> List<Point> = { decodes++; pts }
        val first = cache.points(s, decode)
        val second = cache.points(s, decode)
        assertThat(decodes).isEqualTo(1)
        assertThat(first).isEqualTo(pts)
        assertThat(second).isEqualTo(pts)
    }

    @Test fun `points cache keyed by uuid — a different stroke decodes independently`() {
        val cache = StrokeRenderCache()
        var decodes = 0
        val decode: (StrokeEntity) -> List<Point> = { s -> decodes++; listOf(Point(0f, 0f, 1f, s.uuid.hashCode().toLong())) }
        cache.points(stroke("a"), decode)
        cache.points(stroke("b"), decode)
        assertThat(decodes).isEqualTo(2)
    }

    @Test fun `evictAll clears both caches`() {
        val cache = StrokeRenderCache()
        var builds = 0
        var decodes = 0
        val f = fit()
        cache.pathFor("s1", 1f, 0, selected = false, baseWidthPx = 4f, fit = f) { builds++; Path() }
        cache.points(stroke("s1")) { decodes++; emptyList() }
        cache.evictAll()
        cache.pathFor("s1", 1f, 0, selected = false, baseWidthPx = 4f, fit = f) { builds++; Path() }
        cache.points(stroke("s1")) { decodes++; emptyList() }
        assertThat(builds).isEqualTo(2)
        assertThat(decodes).isEqualTo(2)
    }
}
