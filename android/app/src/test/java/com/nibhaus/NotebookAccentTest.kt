package com.nibhaus

import com.google.common.truth.Truth.assertThat
import com.nibhaus.export.NotebookAccent
import org.junit.Test

/** per-notebook accent color palette — key round-trip + default fallback. */
class NotebookAccentTest {

    @Test fun `accent key round-trips, unknown falls back to default`() {
        NotebookAccent.entries.forEach { assertThat(NotebookAccent.fromKey(it.key)).isEqualTo(it) }
        assertThat(NotebookAccent.fromKey("nope")).isEqualTo(NotebookAccent.DEFAULT)
        assertThat(NotebookAccent.fromKey(null)).isEqualTo(NotebookAccent.DEFAULT)
    }

    @Test fun `default is NONE - no tint`() {
        assertThat(NotebookAccent.DEFAULT).isEqualTo(NotebookAccent.NONE)
    }

    @Test fun `palette offers six pickable colors, distinct from the unset default`() {
        val pickable = NotebookAccent.entries.filter { it != NotebookAccent.NONE }
        assertThat(pickable).hasSize(6)
        assertThat(pickable.map { it.argb }.toSet()).hasSize(6) // no two colors collide
    }
}
