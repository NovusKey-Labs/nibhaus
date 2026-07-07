package com.nibhaus

import com.google.common.truth.Truth.assertThat
import com.nibhaus.data.PageEntity
import com.nibhaus.ui.InkViewModel
import org.junit.Test

/**
 * Feature 15's [InkViewModel.favoritePages] and Feature 9's [InkViewModel.normalizeTranscriptEdit] —
 * both pure, so exercised directly without constructing an [InkViewModel] (see [EagerTranscribeTest]
 * for why VM construction is deliberately avoided in these pure-logic suites).
 */
class FavoritePagesTest {

    private fun page(id: String, lastInkAt: Long = 0L) = PageEntity(
        id = id,
        notebookId = "nb",
        addressKey = "1.2.3.$id",
        section = 1,
        owner = 2,
        book = 3,
        page = 0,
        firstSeenAt = 0L,
        lastInkAt = lastInkAt,
    )

    @Test fun `only bookmarked pages are kept`() {
        val pages = listOf(page("a"), page("b"), page("c"))
        assertThat(InkViewModel.favoritePages(pages, setOf("b"))).containsExactly(page("b"))
    }

    @Test fun `empty bookmark set yields no favorites`() {
        val pages = listOf(page("a"), page("b"))
        assertThat(InkViewModel.favoritePages(pages, emptySet())).isEmpty()
    }

    @Test fun `result is most-recently-inked first`() {
        val a = page("a", lastInkAt = 100L)
        val b = page("b", lastInkAt = 300L)
        val c = page("c", lastInkAt = 200L)
        assertThat(InkViewModel.favoritePages(listOf(a, b, c), setOf("a", "b", "c")))
            .containsExactly(b, c, a).inOrder()
    }

    @Test fun `bookmarked id with no matching page is silently ignored`() {
        assertThat(InkViewModel.favoritePages(listOf(page("a")), setOf("a", "ghost"))).containsExactly(page("a"))
    }
}

class NormalizeTranscriptEditTest {

    @Test fun `leading and trailing whitespace is trimmed`() {
        assertThat(InkViewModel.normalizeTranscriptEdit("  hello world  ")).isEqualTo("hello world")
    }

    @Test fun `interior whitespace is preserved`() {
        assertThat(InkViewModel.normalizeTranscriptEdit("line one\nline two")).isEqualTo("line one\nline two")
    }

    @Test fun `whitespace-only edit normalizes to empty string (clears the transcript)`() {
        assertThat(InkViewModel.normalizeTranscriptEdit("   \n  ")).isEmpty()
    }

    @Test fun `already-clean text is unchanged`() {
        assertThat(InkViewModel.normalizeTranscriptEdit("clean")).isEqualTo("clean")
    }
}
