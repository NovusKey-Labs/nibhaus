package com.nibhaus

import com.google.common.truth.Truth.assertThat
import com.nibhaus.pen.SavedPen
import com.nibhaus.pen.forgetSavedPen
import com.nibhaus.pen.upsertSavedPen
import org.junit.Test

/** (Pens screen saved-pen tiles): the pure list-editing logic behind [PenPrefs.savedPens]. */
class PenPrefsTest {

    @Test fun `upsert adds a new pen to the front`() {
        val pens = upsertSavedPen(emptyList(), SavedPen("Safari", "AA:BB", 100L))
        assertThat(pens).containsExactly(SavedPen("Safari", "AA:BB", 100L))
    }

    @Test fun `upsert of an already-saved spp replaces it in place, not a duplicate`() {
        val existing = listOf(SavedPen("Safari", "AA:BB", 100L), SavedPen("M1+", "CC:DD", 50L))
        val pens = upsertSavedPen(existing, SavedPen("Safari (renamed)", "AA:BB", 200L))

        assertThat(pens).hasSize(2)
        assertThat(pens).contains(SavedPen("Safari (renamed)", "AA:BB", 200L))
        assertThat(pens.none { it.spp == "AA:BB" && it.name == "Safari" }).isTrue()
    }

    @Test fun `upsert moves the refreshed pen to the front - most recently connected first`() {
        val existing = listOf(SavedPen("M1+", "CC:DD", 50L), SavedPen("Safari", "AA:BB", 100L))
        val pens = upsertSavedPen(existing, SavedPen("Safari", "AA:BB", 300L))

        assertThat(pens.first().spp).isEqualTo("AA:BB")
        assertThat(pens.first().lastConnectedAt).isEqualTo(300L)
    }

    @Test fun `dedupe by spp - never two entries for the same pen`() {
        var pens = emptyList<SavedPen>()
        pens = upsertSavedPen(pens, SavedPen("Safari", "AA:BB", 1L))
        pens = upsertSavedPen(pens, SavedPen("Safari", "AA:BB", 2L))
        pens = upsertSavedPen(pens, SavedPen("Safari", "AA:BB", 3L))

        assertThat(pens).hasSize(1)
        assertThat(pens.single().lastConnectedAt).isEqualTo(3L)
    }

    @Test fun `forget removes the pen with that spp`() {
        val existing = listOf(SavedPen("Safari", "AA:BB", 100L), SavedPen("M1+", "CC:DD", 50L))
        val pens = forgetSavedPen(existing, "AA:BB")

        assertThat(pens).containsExactly(SavedPen("M1+", "CC:DD", 50L))
    }

    @Test fun `forgetting an unknown spp is a no-op`() {
        val existing = listOf(SavedPen("Safari", "AA:BB", 100L))
        assertThat(forgetSavedPen(existing, "ghost")).isEqualTo(existing)
    }

    @Test fun `forgetting from an empty list stays empty`() {
        assertThat(forgetSavedPen(emptyList(), "AA:BB")).isEmpty()
    }
}
