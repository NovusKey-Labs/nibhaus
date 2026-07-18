package com.nibhaus

import com.google.common.truth.Truth.assertThat
import com.nibhaus.pen.readySpps
import com.nibhaus.pen.recordSightings
import org.junit.Test

/** (requirement 3): the "READY" live dot on saved-pen tiles — pure
 *  sightings+clock → per-spp ready mapping, and the sightings accumulation feeding it. */
class PenPresenceTest {

    // --- readySpps: sighting -> ready, hold-window expiry ---

    @Test fun `a spp sighted just now is ready`() {
        assertThat(readySpps(mapOf("AA:BB" to 1_000L), now = 1_000L)).containsExactly("AA:BB")
    }

    @Test fun `a spp sighted within the hold window is still ready`() {
        val sightings = mapOf("AA:BB" to 1_000L)
        assertThat(readySpps(sightings, now = 1_000L + 9_000L, holdMs = 10_000L)).containsExactly("AA:BB")
    }

    @Test fun `a spp not sighted for longer than the hold window is no longer ready`() {
        val sightings = mapOf("AA:BB" to 1_000L)
        assertThat(readySpps(sightings, now = 1_000L + 10_001L, holdMs = 10_000L)).isEmpty()
    }

    @Test fun `exactly at the hold window boundary is still ready (inclusive)`() {
        val sightings = mapOf("AA:BB" to 1_000L)
        assertThat(readySpps(sightings, now = 1_000L + 10_000L, holdMs = 10_000L)).containsExactly("AA:BB")
    }

    @Test fun `a spp never in the sightings map is never ready`() {
        assertThat(readySpps(emptyMap(), now = 5_000L)).isEmpty()
    }

    // --- per-spp independence: two saved pens, only one sighted -> only that one is ready ---

    @Test fun `two saved pens, only one currently sighted - only that one reads ready`() {
        // "AA:BB" (e.g. a LAMY Safari) is nearby; "CC:DD" (an M1+) was never seen this session.
        val sightings = mapOf("AA:BB" to 5_000L)
        val ready = readySpps(sightings, now = 5_000L)
        assertThat(ready).containsExactly("AA:BB")
        assertThat(ready).doesNotContain("CC:DD")
    }

    @Test fun `two saved pens both recently sighted are both ready`() {
        val sightings = mapOf("AA:BB" to 1_000L, "CC:DD" to 4_000L)
        assertThat(readySpps(sightings, now = 5_000L, holdMs = 10_000L)).containsExactly("AA:BB", "CC:DD")
    }

    @Test fun `one saved pen expires out of the hold window while the other, sighted more recently, stays ready`() {
        val sightings = mapOf("AA:BB" to 0L, "CC:DD" to 9_500L)
        assertThat(readySpps(sightings, now = 15_000L, holdMs = 10_000L)).containsExactly("CC:DD")
    }

    // --- recordSightings: accumulating scan ticks into the running sightings map ---

    @Test fun `recordSightings stamps a newly seen spp with now and keeps prior entries`() {
        val before = mapOf("AA:BB" to 1_000L)
        val after = recordSightings(before, seenNow = listOf("CC:DD"), now = 2_000L)
        assertThat(after).containsExactly("AA:BB", 1_000L, "CC:DD", 2_000L)
    }

    @Test fun `recordSightings refreshes an already-known spp's timestamp`() {
        val before = mapOf("AA:BB" to 1_000L)
        val after = recordSightings(before, seenNow = listOf("AA:BB"), now = 5_000L)
        assertThat(after).containsExactly("AA:BB", 5_000L)
    }

    @Test fun `an empty scan tick leaves prior sightings untouched - no flicker on one missed packet`() {
        val before = mapOf("AA:BB" to 1_000L)
        val after = recordSightings(before, seenNow = emptyList(), now = 2_000L)
        assertThat(after).isEqualTo(before)
    }

    @Test fun `recordSightings then readySpps end to end - a brief gap doesn't drop presence`() {
        var sightings = recordSightings(emptyMap(), listOf("AA:BB"), now = 0L)
        sightings = recordSightings(sightings, emptyList(), now = 3_000L) // one tick misses it
        sightings = recordSightings(sightings, emptyList(), now = 6_000L) // another miss

        assertThat(readySpps(sightings, now = 6_000L, holdMs = 10_000L)).containsExactly("AA:BB")
    }

    // --- clock-advance expiry (bug #3: a saved-pen tile stuck on READY after the pen powers off) ---
    //
    // The wiring bug was NOT in these pure functions — it was that InkViewModel used to call
    // recordSightings again on every 1s ticker tick using the scanner's last-known (and, once the
    // pen goes quiet, permanently frozen) results list, continuously re-stamping "now" and defeating
    // expiry entirely. These tests pin the CORRECT usage contract: sightings are recorded once from a
    // real sighting, and re-evaluating readySpps against an advancing clock — with NO further
    // recordSightings call — must expire it once holdMs elapses.

    @Test fun `re-evaluating readySpps with an advancing clock and no new sighting expires it once holdMs elapses`() {
        val sightings = recordSightings(emptyMap(), listOf("AA:BB"), now = 0L)

        // Simulates the 1s ticker recomputing readySpps repeatedly with NO further recordSightings
        // call (the pen went silent — no more BLE packets, so nothing re-stamps it).
        assertThat(readySpps(sightings, now = 1_000L)).containsExactly("AA:BB")
        assertThat(readySpps(sightings, now = 5_000L)).containsExactly("AA:BB")
        assertThat(readySpps(sightings, now = 10_000L)).containsExactly("AA:BB") // boundary, inclusive
        assertThat(readySpps(sightings, now = 10_001L)).isEmpty() // the pen powered off — no longer ready
        assertThat(readySpps(sightings, now = 60_000L)).isEmpty() // stays expired, doesn't come back on its own
    }

    @Test fun `one saved pen goes silent and expires while another, still being sighted, stays ready across the same clock advance`() {
        var sightings = recordSightings(emptyMap(), listOf("AA:BB", "CC:DD"), now = 0L)
        // Only CC:DD keeps being resighted; AA:BB (powered off) never appears again.
        sightings = recordSightings(sightings, listOf("CC:DD"), now = 5_000L)
        sightings = recordSightings(sightings, listOf("CC:DD"), now = 10_000L)

        val ready = readySpps(sightings, now = 10_500L, holdMs = 10_000L)
        assertThat(ready).containsExactly("CC:DD")
        assertThat(ready).doesNotContain("AA:BB")
    }
}
