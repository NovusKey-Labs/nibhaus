package com.nibhaus
import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.nibhaus.ui.theme.Palettes
import com.nibhaus.ui.theme.parseCssColor
import com.nibhaus.ui.theme.schemeFrom
import org.junit.Test

class PaletteSchemeTest {
    @Test fun `parseCssColor handles hex and rgba`() {
        assertThat(parseCssColor("#0B1630")).isEqualTo(Color(0xFF0B1630))
        val c = parseCssColor("rgba(108,124,255,.44)")
        assertThat(c.red).isWithin(0.01f).of(108/255f)
        assertThat(c.alpha).isWithin(0.01f).of(0.44f)
    }
    @Test fun `schemeFrom maps tokens onto the scheme slots`() {
        val s = schemeFrom(Palettes.byId("D01"))
        assertThat(s.background).isEqualTo(Color(0xFF0B1630)) // base
        assertThat(s.surface).isEqualTo(Color(0xFF132340))    // surface
        assertThat(s.primary).isEqualTo(Color(0xFF6C7CFF))    // accent
        assertThat(s.onSurface).isEqualTo(Color(0xFFEAF0FB))  // text
        assertThat(s.onSurfaceVariant).isEqualTo(Color(0xFF9AA8C7)) // muted
    }
    @Test fun `every palette keeps text readable on its surface`() {
        // luminance gap between onSurface (text) and surface must be clear
        Palettes.ALL.forEach { p ->
            val s = schemeFrom(p)
            fun lum(c: Color) = 0.2126f*c.red + 0.7152f*c.green + 0.0722f*c.blue
            assertThat(kotlin.math.abs(lum(s.onSurface) - lum(s.surface))).isGreaterThan(0.35f)
        }
    }
}
