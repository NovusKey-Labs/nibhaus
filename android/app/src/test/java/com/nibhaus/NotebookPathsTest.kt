package com.nibhaus

import com.google.common.truth.Truth.assertThat
import com.nibhaus.export.NotebookPaths
import com.nibhaus.export.NotebookType
import com.nibhaus.export.PlannerDate
import com.nibhaus.export.PlannerLayout
import org.junit.Test

class NotebookPathsTest {

    @Test fun `professional notebook files under its code and label`() {
        val base = NotebookPaths.exportBaseName(
            type = NotebookType.PROFESSIONAL, label = "Work", instanceSeq = 0, page = 38,
            fallbackId = "uuid-1",
        )
        assertThat(base).isEqualTo("pnb/Work/PNB_Work_Pg038")
    }

    @Test fun `planner files under date folders when the page's date is known`() {
        val base = NotebookPaths.exportBaseName(
            type = NotebookType.PLANNER_2026, label = "School", instanceSeq = 0, page = 100,
            plannerDate = PlannerDate(2026, 6), fallbackId = "uuid-2",
        )
        assertThat(base).isEqualTo("plnr/2026/06_June/PLNR_School_Pg100")
    }

    @Test fun `a planner page with no resolved date falls back to the label folder`() {
        val base = NotebookPaths.exportBaseName(
            type = NotebookType.PLANNER_2026, label = "School", instanceSeq = 0, page = 100,
            plannerDate = null, fallbackId = "uuid-3",
        )
        assertThat(base).isEqualTo("plnr/School/PLNR_School_Pg100")
    }

    @Test fun `page numbers are zero-padded so files sort`() {
        assertThat(NotebookPaths.pageToken(5)).isEqualTo("Pg005")
        assertThat(NotebookPaths.pageToken(100)).isEqualTo("Pg100")
    }

    @Test fun `labels are sanitised for the filesystem`() {
        assertThat(NotebookPaths.sanitize("Q3 / Notes")).isEqualTo("Q3_Notes")
        assertThat(NotebookPaths.sanitize("  spaced  ")).isEqualTo("spaced")
        assertThat(NotebookPaths.sanitize("a:b*c?")).isEqualTo("a_b_c")
    }

    @Test fun `non-ascii labels become filesystem-safe and over-long labels are capped`() {
        assertThat(NotebookPaths.sanitize("café résumé")).isEqualTo("caf_r_sum") // non-ASCII → underscore
        assertThat(NotebookPaths.sanitize("a".repeat(200)).length).isAtMost(NotebookPaths.MAX_LABEL)
        assertThat(NotebookPaths.sanitize("emoji 🎉 here")).isEqualTo("emoji_here")
    }

    @Test fun `a blank label is disambiguated per physical copy`() {
        val first = NotebookPaths.exportBaseName(
            type = NotebookType.PROFESSIONAL, label = "  ", instanceSeq = 0, page = 1, fallbackId = "x",
        )
        val second = NotebookPaths.exportBaseName(
            type = NotebookType.PROFESSIONAL, label = "", instanceSeq = 1, page = 1, fallbackId = "x",
        )
        assertThat(first).isEqualTo("pnb/Untitled-1/PNB_Untitled-1_Pg001")
        assertThat(second).isEqualTo("pnb/Untitled-2/PNB_Untitled-2_Pg001")
    }

    @Test fun `an unknown notebook type falls back to the stable id`() {
        val base = NotebookPaths.exportBaseName(
            type = null, label = "Work", instanceSeq = 0, page = 38, fallbackId = "uuid-stable",
        )
        assertThat(base).isEqualTo("uuid-stable")
    }

    @Test fun `planner month folders are sortable`() {
        assertThat(PlannerDate(2026, 6).monthFolder).isEqualTo("06_June")
        assertThat(PlannerDate(2026, 12).monthFolder).isEqualTo("12_December")
        assertThat(PlannerDate(2026, 1).monthFolder).isEqualTo("01_January")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an invalid planner month is rejected`() {
        PlannerDate(2026, 13)
    }

    @Test fun `book ids resolve to measured products only`() {
        assertThat(NotebookType.forBook(438)).isEqualTo(NotebookType.PROFESSIONAL)
        assertThat(NotebookType.forBook(999)).isNull()
        assertThat(NotebookType.forBook(null)).isNull()
    }

    @Test fun `professional has measured geometry, planner is still pending`() {
        assertThat(NotebookType.PROFESSIONAL.geometry).isNotNull()
        assertThat(NotebookType.PLANNER_2026.geometry).isNull()
        assertThat(NotebookType.PLANNER_2026.isPlanner).isTrue()
    }

    @Test fun `monthly() builds equal-length sections and rolls over the year`() {
        val twelve = PlannerLayout.monthly(firstPage = 10, pagesPerMonth = 20, startYear = 2026)
        assertThat(twelve.dateFor(10)).isEqualTo(PlannerDate(2026, 1))   // first month
        assertThat(twelve.dateFor(110)).isEqualTo(PlannerDate(2026, 6))  // 10 + 5*20 → June
        assertThat(twelve.dateFor(9)).isNull()                           // before the planner
        // Year rollover: Nov 2026 → Feb 2027.
        val span = PlannerLayout.monthly(firstPage = 1, pagesPerMonth = 10, startYear = 2026, startMonth = 11, months = 4)
        assertThat(span.dateFor(1)).isEqualTo(PlannerDate(2026, 11))
        assertThat(span.dateFor(11)).isEqualTo(PlannerDate(2026, 12))
        assertThat(span.dateFor(21)).isEqualTo(PlannerDate(2027, 1))
        assertThat(span.dateFor(31)).isEqualTo(PlannerDate(2027, 2))
    }

    @Test fun `planner layout maps a page to its month section`() {
        val layout = PlannerLayout(
            listOf(
                PlannerLayout.Section(fromPage = 10, year = 2026, month = 1),
                PlannerLayout.Section(fromPage = 40, year = 2026, month = 2),
                PlannerLayout.Section(fromPage = 100, year = 2026, month = 6),
            ),
        )
        assertThat(layout.dateFor(5)).isNull()                          // before the first section
        assertThat(layout.dateFor(10)).isEqualTo(PlannerDate(2026, 1))
        assertThat(layout.dateFor(39)).isEqualTo(PlannerDate(2026, 1))
        assertThat(layout.dateFor(40)).isEqualTo(PlannerDate(2026, 2))
        assertThat(layout.dateFor(100)).isEqualTo(PlannerDate(2026, 6)) // "page 100 = June"
        assertThat(layout.dateFor(250)).isEqualTo(PlannerDate(2026, 6)) // last section extends onward
    }

    @Test fun `resolve prefers a user assignment over the built-in default`() {
        // No assignment → built-in default (or null for an unknown book).
        assertThat(NotebookType.resolve(assignedId = null, book = 438)).isEqualTo(NotebookType.PROFESSIONAL)
        assertThat(NotebookType.resolve(assignedId = null, book = 999)).isNull()
        // Assignment recognises an unmeasured book, and re-points a known one.
        assertThat(NotebookType.resolve("planner2026", 999)).isEqualTo(NotebookType.PLANNER_2026)
        assertThat(NotebookType.resolve("planner2026", 438)).isEqualTo(NotebookType.PLANNER_2026)
        // A stale/bogus id is ignored, falling back to the built-in.
        assertThat(NotebookType.resolve("bogus", 438)).isEqualTo(NotebookType.PROFESSIONAL)
        assertThat(NotebookType.byId("professional")).isEqualTo(NotebookType.PROFESSIONAL)
        assertThat(NotebookType.byId(null)).isNull()
    }
}
