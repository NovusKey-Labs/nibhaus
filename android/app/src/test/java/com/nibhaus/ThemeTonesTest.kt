package com.nibhaus

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Locks the 3-tone split: chrome (bars/surfaces) and paper (page canvas) theme independently. */
class ThemeTonesTest {

    @Test fun `tonesFor — dark palette honours the light-paper toggle, light palette ignores it`() {
        val dark = com.nibhaus.ui.theme.Palettes.byId("D01")
        val light = com.nibhaus.ui.theme.Palettes.byId("L01")
        assertThat(com.nibhaus.ui.tonesFor(dark, lightPaperToggle = false))
            .isEqualTo(com.nibhaus.ui.ThemeTones(darkChrome = true, lightPaper = false))
        assertThat(com.nibhaus.ui.tonesFor(dark, lightPaperToggle = true))
            .isEqualTo(com.nibhaus.ui.ThemeTones(darkChrome = true, lightPaper = true))
        assertThat(com.nibhaus.ui.tonesFor(light, lightPaperToggle = false))
            .isEqualTo(com.nibhaus.ui.ThemeTones(darkChrome = false, lightPaper = true))
    }
}
