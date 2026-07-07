package com.nibhaus

import com.google.common.truth.Truth.assertThat
import com.nibhaus.gesture.HotZone
import com.nibhaus.gesture.HotZones
import com.nibhaus.gesture.ZoneAction
import org.junit.Test

class HotZoneTest {
    private val zone = HotZones.TOP_RIGHT

    @Test fun strokeFullyInsideZone_counts() {
        val pts = listOf(0.85f to 0.05f, 0.9f to 0.08f, 0.95f to 0.1f)
        assertThat(HotZones.strokeInside(zone, pts)).isTrue()
    }

    @Test fun strokeStraddlingZone_doesNotCount() {
        // one point outside the top-right corner → not a command
        val pts = listOf(0.85f to 0.05f, 0.5f to 0.5f)
        assertThat(HotZones.strokeInside(zone, pts)).isFalse()
    }

    @Test fun emptyStroke_doesNotCount() {
        assertThat(HotZones.strokeInside(zone, emptyList())).isFalse()
    }

    @Test fun gestureMapsToAction() {
        assertThat(HotZones.actionFor("check")).isEqualTo(ZoneAction.FLAG_IMPORTANT)
        assertThat(HotZones.actionFor("star")).isEqualTo(ZoneAction.FAVORITE)
        assertThat(HotZones.actionFor("box")).isEqualTo(ZoneAction.MAKE_CHECKBOX)
        assertThat(HotZones.actionFor("circle")).isNull()
    }

    @Test fun zoneFor_findsContainingZone() {
        val pts = listOf(0.9f to 0.05f)
        assertThat(HotZones.zoneFor(listOf(zone), pts)).isEqualTo(zone)
        assertThat(HotZones.zoneFor(listOf(zone), listOf(0.1f to 0.1f))).isNull()
    }
}
