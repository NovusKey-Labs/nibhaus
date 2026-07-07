package com.nibhaus.export

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Handwriting size preset (#15b): the persisted enum + its multiplier values. */
class StrokeScaleTest {

    @Test fun `fromKey resolves known keys, unknown falls back to Normal`() {
        assertThat(StrokeScale.fromKey("fine")).isEqualTo(StrokeScale.FINE)
        assertThat(StrokeScale.fromKey("normal")).isEqualTo(StrokeScale.NORMAL)
        assertThat(StrokeScale.fromKey("bold")).isEqualTo(StrokeScale.BOLD)
        assertThat(StrokeScale.fromKey(null)).isEqualTo(StrokeScale.DEFAULT)
        assertThat(StrokeScale.fromKey("nonsense")).isEqualTo(StrokeScale.DEFAULT)
    }

    @Test fun `default is Normal at 1x`() {
        assertThat(StrokeScale.DEFAULT).isEqualTo(StrokeScale.NORMAL)
        assertThat(StrokeScale.NORMAL.multiplier).isEqualTo(1f)
    }

    @Test fun `Fine is thinner and Bold is thicker than Normal`() {
        assertThat(StrokeScale.FINE.multiplier).isLessThan(StrokeScale.NORMAL.multiplier)
        assertThat(StrokeScale.BOLD.multiplier).isGreaterThan(StrokeScale.NORMAL.multiplier)
    }
}
