package com.nibhaus.export

/**
 * A known Ncode notebook product: its physical layout ([geometry], for true-size full-page export)
 * plus how its pages file on the backend ([code], [isPlanner]). Resolved from the Ncode book id —
 * the same id identifies the product across every physical copy, so the user designates a *type*
 * once per product (then labels each physical notebook separately; see NotebookEntity.title).
 *
 * Adding a product is one row in [BY_BOOK] once it's been measured: geometry from the corner/edge
 * traces (android/tools/calibrate_ncode.py), and — for a planner — its page→date map.
 */
data class NotebookType(
    val id: String,             // stable key, e.g. "professional"
    val displayName: String,    // shown in the new-notebook type picker
    val code: String,           // short path segment + filename prefix, e.g. "pnb"
    val geometry: PageGeometry?, // full-page SVG geometry; null until the product is measured
    val ruling: Ruling? = null, // printed line ruling (mm from sheet edges); null = no calibrated rules
    val pageBands: List<PageBand> = emptyList(), // per-page-range layout (cover/lined/footer-only)
    val isPlanner: Boolean = false, // planner pages file under date folders (year/month)
    val plannerLayout: PlannerLayout? = null, // planner page→date table; null until measured
) {
    companion object {
        val PROFESSIONAL = NotebookType(
            id = "professional",
            displayName = "Professional Notebook",
            code = "pnb",
            // Page face 138 x 205 mm — user ruler-measured (M) 2026-07-02 (binding eats width between
            // facing pages, so this is the visible/writable face). The old derived 210 mm height skewed
            // the ruling pitch to 7.21 mm vs the measured 7.0 — the one-row drift. Dot area (D) from traces.
            // Ruling anchors pen-traced 2026-07-02 (pages 33/34): rule 1 Y=9.56, rule 27 Y=86.71,
            // rules span X 6.92..57.92 (=120mm, matching the 9mm side insets). Measured, not derived.
            geometry = PageGeometry(
                3.9f, 3.8f, 62.5f, 90.0f, 138f, 205f,
                anchors = RulingAnchors(firstY = 9.56f, lastY = 86.71f, leftX = 6.92f, rightX = 57.92f),
            ),
            // Printed ruling: first line 14 mm from top, last 8.5 mm from bottom, 27 lines, 9 mm sides.
            ruling = Ruling(topMm = 14f, bottomMm = 8.5f, lineCount = 27, sideMm = 9f),
            // 001 cover; 002–129 fully lined; 130–256 keep only the bottom line + page number + buttons.
            pageBands = listOf(
                PageBand(1, 1, PageStyle.COVER),
                PageBand(2, 129, PageStyle.LINED),
                PageBand(130, 256, PageStyle.FOOTER_ONLY),
            ),
        )

        // 2026 Planner — book id, geometry, and the page→date layout are all pending its measurement.
        // Note: once measured, (1) register its book id in BY_BOOK, (2) set geometry from the
        // corner/edge traces, and (3) set plannerLayout from the planner's printed page→date pages —
        // e.g. PlannerLayout(listOf(Section(fromPage = 100, year = 2026, month = 6), ...)). The path
        // engine already consumes all three; until then a planner files by label (no date folders).
        val PLANNER_2026 = NotebookType(
            id = "planner2026",
            displayName = "2026 Planner",
            code = "plnr",
            geometry = null,
            isPlanner = true,
            plannerLayout = null,
        )

        /** Measured products keyed by Ncode book id. */
        private val BY_BOOK: Map<Int, NotebookType> = mapOf(438 to PROFESSIONAL)

        /** The built-in product for an Ncode book id, or null if we haven't measured/registered it. */
        fun forBook(book: Int?): NotebookType? = book?.let { BY_BOOK[it] }

        /** Every type the user can pick in the new-notebook dialog. */
        val ALL: List<NotebookType> = listOf(PROFESSIONAL, PLANNER_2026)

        /** A type by its stable id (the user's persisted choice), or null. */
        fun byId(id: String?): NotebookType? = id?.let { i -> ALL.firstOrNull { it.id == i } }

        /**
         * Resolve a book id to its type: a user assignment (the new-notebook dialog, persisted by id)
         * wins over the built-in default, so an unmeasured notebook the user has typed is recognised,
         * and a built-in can be re-pointed. Pure so the precedence is unit-tested without DataStore.
         */
        fun resolve(assignedId: String?, book: Int?): NotebookType? = byId(assignedId) ?: forBook(book)
    }
}
