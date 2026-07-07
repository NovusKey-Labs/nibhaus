package com.nibhaus

import com.google.common.truth.Truth.assertThat
import com.nibhaus.export.LibraryView
import org.junit.Test

/** Feature 14: Library gallery/list view preference — key round-trip + default fallback. */
class LibraryViewTest {

    @Test fun `view key round-trips, unknown falls back to default`() {
        LibraryView.entries.forEach { assertThat(LibraryView.fromKey(it.key)).isEqualTo(it) }
        assertThat(LibraryView.fromKey("nope")).isEqualTo(LibraryView.DEFAULT)
        assertThat(LibraryView.fromKey(null)).isEqualTo(LibraryView.DEFAULT)
    }

    @Test fun `default is gallery - the existing look`() {
        assertThat(LibraryView.DEFAULT).isEqualTo(LibraryView.GALLERY)
    }
}
