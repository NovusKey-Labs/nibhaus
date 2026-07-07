package com.nibhaus.ui.common

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Double-tap zoom (#14): the pure tap-point → scale/offset-clamp math, without Compose. */
class InkSurfaceZoomTest {

    private val box = IntSize(1000, 800)

    @Test fun `double-tap at the centre while at fit zooms in with no offset needed`() {
        val target = doubleTapZoomTarget(currentScale = 1f, tapPoint = Offset(500f, 400f), boxSize = box)
        assertThat(target.scale).isEqualTo(2.5f)
        assertThat(target.offset.x).isWithin(1e-3f).of(0f)
        assertThat(target.offset.y).isWithin(1e-3f).of(0f)
    }

    @Test fun `double-tap off-centre while at fit offsets toward the tap point, clamped to the reachable range`() {
        // Tap near the top-left corner — the raw offset would overshoot what graphicsLayer can reach
        // at 2.5x, so it must clamp to maxX/maxY = size*(scale-1)/2 per axis, same shape as pinch/pan.
        val target = doubleTapZoomTarget(currentScale = 1f, tapPoint = Offset(0f, 0f), boxSize = box)
        val maxX = (box.width * (2.5f - 1f)) / 2f
        val maxY = (box.height * (2.5f - 1f)) / 2f
        assertThat(target.scale).isEqualTo(2.5f)
        assertThat(target.offset.x).isWithin(1e-3f).of(maxX)
        assertThat(target.offset.y).isWithin(1e-3f).of(maxY)
    }

    @Test fun `double-tap while already zoomed resets to fit regardless of tap point`() {
        val target = doubleTapZoomTarget(currentScale = 2.5f, tapPoint = Offset(123f, 456f), boxSize = box)
        assertThat(target.scale).isEqualTo(1f)
        assertThat(target.offset).isEqualTo(Offset.Zero)
    }

    @Test fun `a pinch-zoomed scale also counts as zoomed for the reset branch`() {
        val target = doubleTapZoomTarget(currentScale = 4f, tapPoint = Offset(10f, 10f), boxSize = box)
        assertThat(target.scale).isEqualTo(1f)
        assertThat(target.offset).isEqualTo(Offset.Zero)
    }

    @Test fun `an unmeasured box (zero size) never produces a broken transform`() {
        val target = doubleTapZoomTarget(currentScale = 1f, tapPoint = Offset.Zero, boxSize = IntSize.Zero)
        assertThat(target.scale).isEqualTo(1f)
        assertThat(target.offset).isEqualTo(Offset.Zero)
    }

    @Test fun `custom zoomScale is honored`() {
        val target = doubleTapZoomTarget(currentScale = 1f, tapPoint = Offset(500f, 400f), boxSize = box, zoomScale = 3f)
        assertThat(target.scale).isEqualTo(3f)
    }
}
