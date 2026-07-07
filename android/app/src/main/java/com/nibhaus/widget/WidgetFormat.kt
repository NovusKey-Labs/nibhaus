package com.nibhaus.widget

/**
 * Pure display-formatting helpers for the home-screen widget (#13) — kept Compose/Glance-free so
 * they're directly unit-testable.
 */

/** "Notebook name · p. N", falling back to a generic label when the notebook title is blank (e.g.
 *  an unnamed/auto-created notebook). */
internal fun widgetPageLabel(notebookName: String, pageNumber: Int): String {
    val name = notebookName.ifBlank { "Notebook" }
    return "$name · p. $pageNumber"
}

/** A short "Xm/Xh/Xd/Xw ago" relative-time label for the widget's "last written" line. [nowMs] is
 *  injected (not `System.currentTimeMillis()` inline) so this is deterministic to test. */
internal fun relativeTimeLabel(nowMs: Long, thenMs: Long): String {
    val diffMs = (nowMs - thenMs).coerceAtLeast(0L)
    val minutes = diffMs / 60_000L
    val hours = minutes / 60L
    val days = hours / 24L
    val weeks = days / 7L
    return when {
        minutes < 1L -> "just now"
        minutes < 60L -> "${minutes}m ago"
        hours < 24L -> "${hours}h ago"
        days < 7L -> "${days}d ago"
        else -> "${weeks}w ago"
    }
}
