package com.nibhaus.ui

import com.google.common.truth.Truth.assertThat
import com.nibhaus.data.PageEntity
import com.nibhaus.ui.library.notebookMetaLine
import com.nibhaus.ui.library.showPageFilmstrip
import com.nibhaus.ui.library.visiblePages
import org.junit.Test

/** Feature 19: notebook metadata line — page count always; physical size only when known. */
class NotebookMetaLineTest {

    @Test fun `page count only when size is unknown`() {
        assertThat(notebookMetaLine(12, null, null)).isEqualTo("12 pages")
    }

    @Test fun `singular page count`() {
        assertThat(notebookMetaLine(1, null, null)).isEqualTo("1 page")
    }

    @Test fun `page count and size when both dimensions are known`() {
        assertThat(notebookMetaLine(12, 140, 205)).isEqualTo("12 pages · 140 × 205 mm")
    }

    @Test fun `never fabricates a size when only one dimension is known`() {
        assertThat(notebookMetaLine(12, 140, null)).isEqualTo("12 pages")
        assertThat(notebookMetaLine(12, null, 205)).isEqualTo("12 pages")
    }

    @Test fun `zero pages still reports a count`() {
        assertThat(notebookMetaLine(0, null, null)).isEqualTo("0 pages")
    }
}

/** Feature 16: collapse/hide blank pages in the open-notebook page navigator. */
class VisiblePagesTest {

    private fun page(id: String, page: Int) =
        PageEntity(
            id = id,
            notebookId = "nb",
            addressKey = "1.1.1.$page",
            section = 1,
            owner = 1,
            book = 1,
            page = page,
            firstSeenAt = 0L,
            lastInkAt = 0L,
        )

    @Test fun `hideBlank off returns every page unchanged`() {
        val pages = listOf(page("a", 1), page("b", 2))
        assertThat(visiblePages(pages, hideBlank = false) { false }).isEqualTo(pages)
    }

    @Test fun `hideBlank on drops pages with no strokes`() {
        val a = page("a", 1)
        val b = page("b", 2)
        val pages = listOf(a, b)
        val hasStrokes: (PageEntity) -> Boolean = { it.id == "a" }
        assertThat(visiblePages(pages, hideBlank = true, hasStrokes = hasStrokes)).containsExactly(a)
    }

    @Test fun `hideBlank on with all pages blank yields empty list`() {
        val pages = listOf(page("a", 1), page("b", 2))
        assertThat(visiblePages(pages, hideBlank = true) { false }).isEmpty()
    }

    @Test fun `hideBlank on with all pages inked keeps everything`() {
        val pages = listOf(page("a", 1), page("b", 2))
        assertThat(visiblePages(pages, hideBlank = true) { true }).isEqualTo(pages)
    }

    @Test fun `empty page list stays empty either way`() {
        assertThat(visiblePages(emptyList(), hideBlank = false) { true }).isEmpty()
        assertThat(visiblePages(emptyList(), hideBlank = true) { true }).isEmpty()
    }
}

/** Feature 17: the page filmstrip only earns its keep once the grid needs real scrolling. */
class ShowPageFilmstripTest {

    @Test fun `few pages - grid already shows them all, no filmstrip`() {
        assertThat(showPageFilmstrip(0)).isFalse()
        assertThat(showPageFilmstrip(1)).isFalse()
        assertThat(showPageFilmstrip(6)).isFalse()
    }

    @Test fun `enough pages that scrolling the grid gets slow - show it`() {
        assertThat(showPageFilmstrip(7)).isTrue()
        assertThat(showPageFilmstrip(40)).isTrue()
    }
}
