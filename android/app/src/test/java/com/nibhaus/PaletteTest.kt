package com.nibhaus

import com.google.common.truth.Truth.assertThat
import com.nibhaus.ui.theme.Palettes
import org.junit.Test

class PaletteTest {
    @Test fun `registry has 5 light and 5 dark, default is D01`() {
        assertThat(Palettes.ALL).hasSize(10)
        assertThat(Palettes.light.map { it.id }).containsExactly("L01","L02","L03","L04","L05").inOrder()
        assertThat(Palettes.dark.map { it.id }).containsExactly("D01","D02","D03","D04","D05").inOrder()
        assertThat(Palettes.DEFAULT.id).isEqualTo("D01")
        assertThat(Palettes.byId("D02").name).isEqualTo("Obsidian Orchid")
        assertThat(Palettes.byId("nope").id).isEqualTo("D01") // unknown -> default
    }
    @Test fun `D01 tokens match the source of truth`() {
        val d01 = Palettes.byId("D01")
        assertThat(d01.base).isEqualTo("#0B1630")
        assertThat(d01.accent).isEqualTo("#6C7CFF")
        assertThat(d01.paper).isEqualTo("#11203B")
        assertThat(d01.isDark).isTrue()
    }

    @Test fun `the Match my wallpaper id is not a registry entry, and resolves to a placeholder`() {
        assertThat(Palettes.ALL.map { it.id }).doesNotContain(Palettes.DYNAMIC_ID)
        assertThat(Palettes.light.map { it.id }).doesNotContain(Palettes.DYNAMIC_ID)
        assertThat(Palettes.dark.map { it.id }).doesNotContain(Palettes.DYNAMIC_ID)
        val placeholder = Palettes.byId(Palettes.DYNAMIC_ID)
        assertThat(placeholder.id).isEqualTo(Palettes.DYNAMIC_ID)
        assertThat(placeholder.name).isEqualTo("Match my wallpaper")
        // Never blank/broken if ever rendered un-resolved (pre-S, or no Context yet).
        assertThat(placeholder.base).isEqualTo(Palettes.DEFAULT.base)
    }
}
