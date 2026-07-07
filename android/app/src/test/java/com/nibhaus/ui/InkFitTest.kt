package com.nibhaus.ui

import com.google.common.truth.Truth.assertThat
import com.nibhaus.data.StrokeEntity
import com.nibhaus.export.PageGeometry
import com.nibhaus.ui.common.fitBounds
import com.nibhaus.ui.common.inkFit
import com.nibhaus.ui.common.strokeBaseWidthPx
import org.junit.Test

/** The canvas fit math, and full-page-from-start: a page-bounds fit exists before any stroke. */
class InkFitTest {

    @Test fun `fitBounds centres the bounds in the canvas, tighter axis limits scale`() {
        // Professional writable bounds (3.9,3.8)-(62.5,90.0) on a 1000x1000 canvas.
        val fit = fitBounds(3.9f, 3.8f, 62.5f, 90.0f, 1000f, 1000f)!!
        val cw = 62.5f - 3.9f // 58.6
        val ch = 90.0f - 3.8f // 86.2 → height is the tighter axis
        val margin = 1000f * 0.06f
        val expScale = (1000f - 2 * margin) / ch
        assertThat(fit.scale).isWithin(1e-3f).of(expScale)
        assertThat(fit.minX).isEqualTo(3.9f)
        assertThat(fit.minY).isEqualTo(3.8f)
        assertThat(fit.offY).isWithin(1e-2f).of((1000f - ch * expScale) / 2f) // vertically centred (~60)
        assertThat(fit.offX).isWithin(1e-2f).of((1000f - cw * expScale) / 2f) // horizontally centred
    }

    @Test fun `inkFit yields a full-page fit with no strokes when pageBounds is set`() {
        val geom = PageGeometry(3.9f, 3.8f, 62.5f, 90.0f, 137.5f, 210f)
        val fit = inkFit(emptyList<StrokeEntity>(), { emptyList() }, 1000f, 1000f, pageBounds = geom)
        assertThat(fit).isNotNull()
        // Sheet-frame origin: with geometry set, inkFit fits the WHOLE physical sheet (dot area centred
        // in it), not the dot-area corner — so it opens at true edge-to-edge page scale, not the ink.
        assertThat(fit!!.minX).isWithin(0.01f).of(4.069f)
        assertThat(fit.minY).isWithin(0.01f).of(2.408f)
    }

    @Test fun `inkFit is null with no strokes and no pageBounds`() {
        assertThat(inkFit(emptyList<StrokeEntity>(), { emptyList() }, 1000f, 1000f)).isNull()
    }

    // Handwriting size preset (#15b): strokeBaseWidthPx is the single width source drawStrokes reads,
    // so this is the one place the Fine/Normal/Bold multiplier needs to be verified.
    @Test fun `strokeBaseWidthPx scales linearly with the handwriting-size multiplier`() {
        val base = strokeBaseWidthPx(2000f) // default scale=1
        assertThat(strokeBaseWidthPx(2000f, scale = 1f)).isWithin(1e-4f).of(base)
        assertThat(strokeBaseWidthPx(2000f, scale = 0.7f)).isWithin(1e-3f).of(base * 0.7f)
        assertThat(strokeBaseWidthPx(2000f, scale = 1.4f)).isWithin(1e-3f).of(base * 1.4f)
    }

    @Test fun `strokeBaseWidthPx never drops below the visibility floor even at Fine`() {
        // A tiny canvas at the thinnest preset must still floor at 1.2px, not vanish.
        assertThat(strokeBaseWidthPx(10f, scale = 0.7f)).isAtLeast(1.2f)
    }
}
