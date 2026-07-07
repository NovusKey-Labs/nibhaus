package com.nibhaus

import com.google.common.truth.Truth.assertThat
import com.nibhaus.export.updatedRecentSearches
import org.junit.Test

class RecentSearchesTest {

    @Test fun `new query is added to the front`() {
        assertThat(updatedRecentSearches(listOf("dentist", "milk"), "eggs"))
            .containsExactly("eggs", "dentist", "milk").inOrder()
    }

    @Test fun `re-running an existing query moves it to front instead of duplicating it`() {
        assertThat(updatedRecentSearches(listOf("dentist", "milk", "eggs"), "milk"))
            .containsExactly("milk", "dentist", "eggs").inOrder()
    }

    @Test fun `dedupe is case-insensitive and keeps the newly-typed casing`() {
        assertThat(updatedRecentSearches(listOf("Dentist", "milk"), "DENTIST"))
            .containsExactly("DENTIST", "milk").inOrder()
    }

    @Test fun `blank or whitespace-only query is ignored, list unchanged`() {
        val current = listOf("dentist", "milk")
        assertThat(updatedRecentSearches(current, "")).isEqualTo(current)
        assertThat(updatedRecentSearches(current, "   ")).isEqualTo(current)
    }

    @Test fun `query is trimmed before being stored`() {
        assertThat(updatedRecentSearches(emptyList(), "  eggs  ")).containsExactly("eggs")
    }

    @Test fun `list is capped, dropping the oldest entries`() {
        val current = listOf("a", "b", "c")
        assertThat(updatedRecentSearches(current, "d", cap = 3)).containsExactly("d", "a", "b").inOrder()
    }

    @Test fun `cap applies even on first insert into a short list`() {
        assertThat(updatedRecentSearches(emptyList(), "only", cap = 0)).isEmpty()
    }

    @Test fun `default cap is 10`() {
        val current = (1..10).map { "q$it" }
        val updated = updatedRecentSearches(current, "q11")
        assertThat(updated).hasSize(10)
        assertThat(updated.first()).isEqualTo("q11")
        assertThat(updated).doesNotContain("q10")
    }
}
