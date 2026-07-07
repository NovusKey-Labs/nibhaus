package com.nibhaus

import com.google.common.truth.Truth.assertThat
import com.nibhaus.export.NotebookType
import com.nibhaus.export.PageStyle
import com.nibhaus.export.Ruling
import com.nibhaus.export.pageNumberAnchorUnits
import com.nibhaus.export.pageNumberText
import com.nibhaus.export.pageStyleFor
import com.nibhaus.export.rulingLinesUnits
import com.nibhaus.export.rulingSideXUnits
import com.nibhaus.export.sheetFrame
import org.junit.Test

/**
 * Pure page-layout math for the Neo N Professional (book 438): the sheet frame, ruled-line positions,
 * and page-number anchor — all in raw Ncode units, derived from the measured physical spec so the
 * on-screen/exported page matches the physical notebook. Values cross-checked in the design spec.
 */
class PaperLayoutTest {

    private val anchored = NotebookType.PROFESSIONAL.geometry!! // 138x205mm + pen-traced RulingAnchors
    private val geom = anchored.copy(anchors = null)            // derivation fallback (no anchors)
    private val ruling = Ruling(topMm = 14f, bottomMm = 8.5f, lineCount = 27, sideMm = 9f)

    @Test fun `sheet frame centres the writable dot area in the sheet`() {
        val f = sheetFrame(geom)
        assertThat(f.wUnits).isWithin(0.01f).of(58.475f) // 138 / 2.36
        assertThat(f.hUnits).isWithin(0.01f).of(86.864f) // 205 / 2.36
        assertThat(f.x0).isWithin(0.01f).of(3.963f)
        assertThat(f.y0).isWithin(0.01f).of(3.468f)
    }

    @Test fun `ruled lines span top margin to bottom margin with the exact line count`() {
        val ys = rulingLinesUnits(geom, ruling)
        assertThat(ys).hasSize(27)
        assertThat(ys.first()).isWithin(0.01f).of(9.4f)    // page top 3.468 + 14mm
        assertThat(ys.last()).isWithin(0.01f).of(86.731f)  // 8.5mm above the page bottom (90.332)
        // pitch from the count: 182.5mm / 26 gaps ≈ 7.02mm / 2.36 ≈ 2.974 units — matches the measured 0.7cm
        assertThat(ys[1] - ys[0]).isWithin(0.01f).of(2.974f)
    }

    @Test fun `ruled lines are inset by the side margin`() {
        val (left, right) = rulingSideXUnits(geom, ruling)
        assertThat(left).isWithin(0.01f).of(7.776f)   // page left 3.963 + 9mm
        assertThat(right).isWithin(0.01f).of(58.624f) // page right 62.437 - 9mm
    }

    @Test fun `page number is 3-digit zero-padded`() {
        assertThat(pageNumberText(36)).isEqualTo("036")
        assertThat(pageNumberText(5)).isEqualTo("005")
        assertThat(pageNumberText(100)).isEqualTo("100")
    }

    @Test fun `page number anchors bottom-left, flush with the ruling`() {
        val (x, y) = pageNumberAnchorUnits(geom, ruling)
        assertThat(x).isWithin(0.01f).of(7.776f)   // flush with the ruling's left inset
        assertThat(y).isWithin(0.01f).of(87.578f)  // centre 6.5mm above the page bottom
    }

    @Test fun `traced anchors override derivation - rules run exactly first to last traced Y`() {
        val ys = rulingLinesUnits(anchored, ruling)
        assertThat(ys).hasSize(27)
        assertThat(ys.first()).isWithin(0.001f).of(9.56f)   // traced rule 1
        assertThat(ys.last()).isWithin(0.001f).of(86.71f)   // traced rule 27
        assertThat(ys[1] - ys[0]).isWithin(0.001f).of(2.9673f) // (86.71-9.56)/26
        val (l, r) = rulingSideXUnits(anchored, ruling)
        assertThat(l).isWithin(0.001f).of(6.92f); assertThat(r).isWithin(0.001f).of(57.92f)
        val (x, y) = pageNumberAnchorUnits(anchored, ruling)
        assertThat(x).isWithin(0.001f).of(6.92f)
        // lastY + (8.5-6.5)mm * upm, upm = 77.15/182.5
        assertThat(y).isWithin(0.01f).of(87.556f)
    }

    @Test fun `page style resolves by band, defaulting to lined outside measured ranges`() {
        val bands = NotebookType.PROFESSIONAL.pageBands
        assertThat(pageStyleFor(bands, 1)).isEqualTo(PageStyle.COVER)         // 001 cover
        assertThat(pageStyleFor(bands, 50)).isEqualTo(PageStyle.LINED)        // 002–129 lined
        assertThat(pageStyleFor(bands, 200)).isEqualTo(PageStyle.FOOTER_ONLY) // 130–256 footer-only
        assertThat(pageStyleFor(bands, 999)).isEqualTo(PageStyle.LINED)       // past measured → default
        assertThat(pageStyleFor(emptyList(), 5)).isEqualTo(PageStyle.LINED)   // unmeasured notebook
    }
}
