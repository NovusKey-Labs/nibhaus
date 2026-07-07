package com.nibhaus

import com.google.common.truth.Truth.assertThat
import com.nibhaus.export.paletteIdFromLegacyTheme
import org.junit.Test

class ThemePaletteMigrationTest {
    @Test fun `legacy ThemeMode maps to a palette id + lightPaper`() {
        assertThat(paletteIdFromLegacyTheme("dark")).isEqualTo("D01" to false)
        assertThat(paletteIdFromLegacyTheme("light")).isEqualTo("L01" to false)
        assertThat(paletteIdFromLegacyTheme("dark_light_paper")).isEqualTo("D01" to true)
        assertThat(paletteIdFromLegacyTheme("system")).isEqualTo("D01" to false)
        assertThat(paletteIdFromLegacyTheme(null)).isEqualTo("D01" to false)
    }
}
