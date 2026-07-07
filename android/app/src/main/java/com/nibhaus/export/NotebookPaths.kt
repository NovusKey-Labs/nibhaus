package com.nibhaus.export

import java.util.Locale

/**
 * Builds the human-meaningful export base path for a page from its notebook *type* + *label* + page
 * number. Pure (no Android / no I/O) so it's unit-tested directly.
 *
 *   Professional "Work", page 38            -> pnb/Work/PNB_Work_Pg038
 *   2026 Planner "School", page 100 (June)  -> plnr/2026/06_June/PLNR_School_Pg100
 *
 * Returns a base name WITHOUT extension; the engine appends .svg/.png/.txt/etc so every artifact for
 * a page shares one base. The path is purely for the user's folder — identity/idempotency stay keyed
 * by the page UUID in the sidecar + ledger, so renaming a notebook never corrupts dedupe. An unknown
 * notebook falls back to the stable UUID (today's flat behaviour), so export never blocks on a type.
 *
 * Decisions baked in (per the design discussion): page numbers zero-padded so files sort; labels
 * filesystem-sanitised; month folders sortable ("06_June"); blank labels disambiguated per physical
 * copy so two un-named notebooks of one product never collide.
 */
object NotebookPaths {

    /**
     * @param type        resolved product, or null if this notebook's type is unknown.
     * @param label       the per-notebook name (NotebookEntity.title); blank → a per-instance default.
     * @param instanceSeq which physical copy of this product (0-based) — disambiguates blank labels.
     * @param page        Ncode page number.
     * @param plannerDate for a planner page, the (year, month) it falls on; drives the date folders.
     * @param fallbackId  stable id (the pageId) used when [type] is null, so export still works.
     */
    fun exportBaseName(
        type: NotebookType?,
        label: String,
        instanceSeq: Int,
        page: Int,
        plannerDate: PlannerDate? = null,
        fallbackId: String,
    ): String {
        if (type == null) return fallbackId
        val name = labelOrDefault(label, instanceSeq)
        val file = "${type.code.uppercase(Locale.US)}_${name}_${pageToken(page)}"
        return if (type.isPlanner && plannerDate != null) {
            "${type.code}/${plannerDate.year}/${plannerDate.monthFolder}/$file"
        } else {
            "${type.code}/$name/$file"
        }
    }

    /** "Pg038" — zero-padded so a flat file listing sorts correctly (Pg010 after Pg009, not Pg1). */
    fun pageToken(page: Int): String = String.format(Locale.US, "Pg%03d", page)

    /** Longest label kept in a path segment, so a pathological title can't blow a filesystem's
     *  per-component limit (commonly 255 bytes) once combined with the prefix + page token. */
    const val MAX_LABEL = 48

    /** Filesystem-safe label: any run of chars outside [A-Za-z0-9-] collapses to a single underscore,
     *  then it's length-capped. */
    fun sanitize(raw: String): String =
        raw.trim().replace(Regex("[^A-Za-z0-9-]+"), "_").trim('_').take(MAX_LABEL).trim('_')

    /** Sanitised label, or a per-instance default ("Untitled-1", "Untitled-2", …) when blank. */
    private fun labelOrDefault(label: String, instanceSeq: Int): String =
        sanitize(label).ifBlank { "Untitled-${instanceSeq + 1}" }
}

/** A planner page's date, for the date-driven folders. [monthFolder] is sortable, e.g. "06_June". */
data class PlannerDate(val year: Int, val month: Int) {
    init { require(month in 1..12) { "month out of range: $month" } }

    val monthFolder: String
        get() = String.format(Locale.US, "%02d_%s", month, MONTHS[month - 1])

    private companion object {
        val MONTHS = arrayOf(
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December",
        )
    }
}

/**
 * Maps a planner's page number to the (year, month) it falls on, so planner pages file under date
 * folders. Data-driven: [sections] is a table of "from this page number onward, it's this month",
 * and [dateFor] returns the latest section starting at-or-before the page. Filling a real planner is
 * just editing the table — no code change — so the mechanism ships now and the numbers come from the
 * planner's printed page→date layout once measured (we don't fabricate them).
 */
class PlannerLayout(sections: List<Section>) {
    data class Section(val fromPage: Int, val year: Int, val month: Int)

    private val sorted = sections.sortedBy { it.fromPage }

    fun dateFor(page: Int): PlannerDate? =
        sorted.lastOrNull { page >= it.fromPage }?.let { PlannerDate(it.year, it.month) }

    companion object {
        /**
         * Layout for a planner with [months] equal-length monthly sections of [pagesPerMonth] pages,
         * the first month beginning at [firstPage]. Months roll over the year past December — so a
         * planner that runs Nov 2026 → Feb 2027 is one call. Fill the real numbers from the planner's
         * printed page→date pages once measured.
         */
        fun monthly(
            firstPage: Int,
            pagesPerMonth: Int,
            startYear: Int,
            startMonth: Int = 1,
            months: Int = 12,
        ): PlannerLayout = PlannerLayout(
            (0 until months).map { i ->
                val m0 = (startMonth - 1) + i // 0-based month index from January of startYear
                Section(
                    fromPage = firstPage + i * pagesPerMonth,
                    year = startYear + m0 / 12,
                    month = (m0 % 12) + 1,
                )
            },
        )
    }
}
