package com.nibhaus

import com.google.common.truth.Truth.assertThat
import com.nibhaus.export.toggledSet
import org.junit.Test

/** Feature 15: the pure add/remove step behind [com.nibhaus.export.SettingsStore.setBookmarked]. */
class ToggledSetTest {

    @Test fun `on true adds the id`() {
        assertThat(toggledSet(emptySet(), "p1", on = true)).containsExactly("p1")
    }

    @Test fun `on false removes the id`() {
        assertThat(toggledSet(setOf("p1", "p2"), "p1", on = false)).containsExactly("p2")
    }

    @Test fun `adding an already-present id is a no-op`() {
        assertThat(toggledSet(setOf("p1"), "p1", on = true)).containsExactly("p1")
    }

    @Test fun `removing an absent id is a no-op`() {
        assertThat(toggledSet(setOf("p1"), "p2", on = false)).containsExactly("p1")
    }

    @Test fun `other ids in the set are left untouched`() {
        assertThat(toggledSet(setOf("a", "b", "c"), "b", on = false)).containsExactly("a", "c")
    }
}
