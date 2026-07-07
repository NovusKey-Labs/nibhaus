package com.nibhaus

import com.google.common.truth.Truth.assertThat
import com.nibhaus.zones.ActionZone
import com.nibhaus.zones.BuiltinZones
import com.nibhaus.zones.ZoneAction
import com.nibhaus.zones.matchZone
import org.junit.Test

/** Built-in printed-button zones for book 438, positioned from the pen-traced ruling anchors. */
class ActionZoneBuiltinTest {

    @Test fun `share and email buttons hit at their centres, miss between them`() {
        assertThat(matchZone(BuiltinZones.ALL, 438, 52.0f, 7.44f)?.action).isEqualTo(ZoneAction.SHARE)
        assertThat(matchZone(BuiltinZones.ALL, 438, 56.2f, 7.44f)?.action).isEqualTo(ZoneAction.EMAIL)
        assertThat(matchZone(BuiltinZones.ALL, 438, 54.2f, 7.44f)).isNull() // the gap between buttons
        assertThat(matchZone(BuiltinZones.ALL, 438, 30f, 50f)).isNull()     // mid-page writing area
    }

    @Test fun `builtin zones are book-scoped and user zones win by list order`() {
        assertThat(matchZone(BuiltinZones.ALL, 554, 52.0f, 7.44f)).isNull() // other notebook
        val user = ActionZone("user", ZoneAction.SHARE_PDF, 50f, 6f, 54f, 9f, book = 438)
        val hit = matchZone(listOf(user) + BuiltinZones.ALL, 438, 52.0f, 7.44f)
        assertThat(hit?.action).isEqualTo(ZoneAction.SHARE_PDF) // calibrated zone shadows the builtin
    }
}
