package com.nibhaus.zones

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Per-notebook zone matching: a traced icon fires only on the notebook it was traced on. */
class ActionZoneMatchTest {

    private fun zone(book: Int) = ActionZone("id$book", ZoneAction.SHARE_PNG, 10f, 10f, 20f, 20f, book = book)

    @Test fun `a notebook-scoped zone matches only its own book`() {
        val zones = listOf(zone(438))
        assertThat(matchZone(zones, book = 438, x = 15f, y = 15f)).isEqualTo(zones[0])
        assertThat(matchZone(zones, book = 554, x = 15f, y = 15f)).isNull()
    }

    @Test fun `a legacy zone (book 0) matches any notebook`() {
        val zones = listOf(zone(0))
        assertThat(matchZone(zones, book = 438, x = 15f, y = 15f)).isEqualTo(zones[0])
        assertThat(matchZone(zones, book = 999, x = 15f, y = 15f)).isEqualTo(zones[0])
    }

    @Test fun `no match outside the rect`() {
        assertThat(matchZone(listOf(zone(438)), book = 438, x = 100f, y = 100f)).isNull()
    }

    @Test fun `an unrecognized-book tap still matches any zone by position`() {
        // A cold tap before the pen locks onto the Ncode pattern has book <= 0; fire the icon anyway.
        val zones = listOf(zone(438))
        assertThat(matchZone(zones, book = 0, x = 15f, y = 15f)).isEqualTo(zones[0])
        assertThat(matchZone(zones, book = -1, x = 15f, y = 15f)).isEqualTo(zones[0])
    }

    @Test fun `overlapping zones use list order including on a shared edge`() {
        val first = zone(438)
        val second = ActionZone("second", ZoneAction.EMAIL_PDF, 20f, 10f, 30f, 20f, book = 438)

        assertThat(matchZone(listOf(first, second), book = 438, x = 20f, y = 15f)).isEqualTo(first)
        assertThat(matchZone(listOf(second, first), book = 438, x = 20f, y = 15f)).isEqualTo(second)
    }

    @Test fun `wrong-book overlap is skipped before choosing a later eligible zone`() {
        val wrongBook = zone(438)
        val anyBook = zone(0).copy(id = "legacy")

        assertThat(matchZone(listOf(wrongBook, anyBook), book = 554, x = 15f, y = 15f))
            .isEqualTo(anyBook)
    }
}
