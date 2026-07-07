package com.nibhaus

import com.google.common.truth.Truth.assertThat
import com.nibhaus.zones.ActionZone
import com.nibhaus.zones.ZoneAction
import com.nibhaus.zones.boundsOf
import com.nibhaus.zones.tapCentre
import org.junit.Test

class ActionZoneTest {

    @Test
    fun `a near-stationary press is a tap and returns its centre`() {
        val pts = listOf(10f to 10f, 10.5f to 10.2f, 10.3f to 9.9f)
        val c = tapCentre(pts, eps = 2f)
        assertThat(c).isNotNull()
        assertThat(c!!.first).isWithin(0.3f).of(10.25f)
    }

    @Test
    fun `a stroke that travels is not a tap`() {
        val pts = listOf(10f to 10f, 14f to 12f, 20f to 18f)
        assertThat(tapCentre(pts, eps = 2f)).isNull()
    }

    @Test
    fun `a traced outline becomes the zone's box`() {
        // Circle around an icon roughly spanning x 78..86, y 3..9.
        val trace = listOf(78f to 6f, 82f to 3f, 86f to 6f, 82f to 9f, 78f to 6f)
        val box = boundsOf(trace)!!
        assertThat(box).containsExactly(78f, 3f, 86f, 9f).inOrder()

        val zone = ActionZone("z1", ZoneAction.SHARE_PNG, box[0], box[1], box[2], box[3])
        assertThat(zone.contains(82f, 6f)).isTrue()    // a tap in the middle of the icon
        assertThat(zone.contains(70f, 6f)).isFalse()   // a word written to the left is safe
    }
}
