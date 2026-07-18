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
    fun `empty point sets have neither bounds nor a tap centre`() {
        assertThat(boundsOf(emptyList())).isNull()
        assertThat(tapCentre(emptyList(), eps = 2f)).isNull()
    }

    @Test
    fun `a single point is a tap whose centre and bounds are that point`() {
        val point = 4.5f to -2f
        assertThat(tapCentre(listOf(point), eps = 0f)).isEqualTo(point)
        assertThat(boundsOf(listOf(point))).containsExactly(4.5f, -2f, 4.5f, -2f).inOrder()
    }

    @Test
    fun `tap extent exactly equal to epsilon is accepted on both axes`() {
        assertThat(tapCentre(listOf(1f to 3f, 3f to 1f), eps = 2f)).isEqualTo(2f to 2f)
    }

    @Test
    fun `tap extent over epsilon on either axis is rejected`() {
        assertThat(tapCentre(listOf(0f to 0f, 2.01f to 1f), eps = 2f)).isNull()
        assertThat(tapCentre(listOf(0f to 0f, 1f to 2.01f), eps = 2f)).isNull()
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

    @Test
    fun `zone rectangle includes every boundary and excludes just-outside points`() {
        val zone = ActionZone("z", ZoneAction.EMAIL, 10f, 20f, 30f, 40f)

        assertThat(zone.contains(10f, 20f)).isTrue()
        assertThat(zone.contains(30f, 40f)).isTrue()
        assertThat(zone.contains(10f, 40f)).isTrue()
        assertThat(zone.contains(30f, 20f)).isTrue()
        assertThat(zone.contains(Math.nextDown(10f), 30f)).isFalse()
        assertThat(zone.contains(Math.nextUp(30f), 30f)).isFalse()
        assertThat(zone.contains(20f, Math.nextDown(20f))).isFalse()
        assertThat(zone.contains(20f, Math.nextUp(40f))).isFalse()
    }
}
