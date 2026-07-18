package com.nibhaus

import com.google.common.truth.Truth.assertThat
import com.nibhaus.data.PageEntity
import com.nibhaus.ui.InkViewModel
import org.junit.Test

/**
 * (Pens home "Recent" section): [InkViewModel.groupRecentByNotebook] groups the global
 * newest-first page list into one row per notebook (newest-edited notebook first, newest page
 * left-to-right, capped at 3, blank pages excluded). Pure, so exercised directly without constructing
 * an [InkViewModel] (see [EagerTranscribeTest] for why VM construction is deliberately avoided in
 * these pure-logic suites).
 */
class GroupRecentByNotebookTest {

    private fun page(id: String, notebookId: String, lastInkAt: Long) = PageEntity(
        id = id,
        notebookId = notebookId,
        addressKey = "1.2.3.$id",
        section = 1,
        owner = 2,
        book = 3,
        page = 0,
        firstSeenAt = 0L,
        lastInkAt = lastInkAt,
    )

    @Test fun `one row per notebook, ordered by that notebook's own newest page`() {
        // Global newest-first order (as repo.allPages() returns): nb2's page is newest overall, so
        // nb2's row must lead even though nb1 has more total pages.
        val pages = listOf(
            page("nb2-a", "nb2", 300L),
            page("nb1-a", "nb1", 200L),
            page("nb1-b", "nb1", 100L),
        )
        val rows = InkViewModel.groupRecentByNotebook(pages, nonBlankIds = pages.map { it.id }.toSet())

        assertThat(rows.map { it.first }).containsExactly("nb2", "nb1").inOrder()
    }

    @Test fun `each row is capped at 3 pages, newest to oldest`() {
        val pages = (1..5).map { page("p$it", "nb", lastInkAt = (100 - it).toLong()) }
        val rows = InkViewModel.groupRecentByNotebook(pages, nonBlankIds = pages.map { it.id }.toSet())

        assertThat(rows).hasSize(1)
        assertThat(rows.single().second.map { it.id }).containsExactly("p1", "p2", "p3").inOrder()
    }

    @Test fun `blank pages (not in nonBlankIds) are excluded from rows and from notebook ordering`() {
        val blank = page("blank", "nb1", 500L) // newest overall, but has no ink
        val inked = page("inked", "nb1", 100L)
        val rows = InkViewModel.groupRecentByNotebook(
            listOf(blank, inked),
            nonBlankIds = setOf("inked"),
        )

        assertThat(rows).hasSize(1)
        assertThat(rows.single().second).containsExactly(inked)
    }

    @Test fun `a notebook with only blank pages produces no row`() {
        val pages = listOf(page("a", "nb", 100L), page("b", "nb", 50L))
        val rows = InkViewModel.groupRecentByNotebook(pages, nonBlankIds = emptySet())
        assertThat(rows).isEmpty()
    }

    @Test fun `empty page list yields no rows`() {
        assertThat(InkViewModel.groupRecentByNotebook(emptyList(), emptySet())).isEmpty()
    }

    @Test fun `perNotebook cap is configurable`() {
        val pages = (1..5).map { page("p$it", "nb", lastInkAt = (100 - it).toLong()) }
        val rows = InkViewModel.groupRecentByNotebook(pages, nonBlankIds = pages.map { it.id }.toSet(), perNotebook = 2)
        assertThat(rows.single().second).hasSize(2)
    }
}
