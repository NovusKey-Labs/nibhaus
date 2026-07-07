package com.nibhaus.edit

/**
 * On-demand edits to captured strokes (Phase 5). The page-detail edit toolbar drives these; the
 * implementation ([StrokeEditor]) re-queues the affected page for export and keeps an undo stack so
 * [undo] reverts the *last edit* (recolor / resize / delete) — not the last thing you wrote.
 *
 * All edits act on a selection (a list of stroke uuids) so one user action is one undoable step.
 */
interface PageEditor {
    suspend fun delete(uuids: List<String>, pageId: String)
    suspend fun recolor(uuids: List<String>, color: Int, pageId: String)
    suspend fun setThickness(uuids: List<String>, width: Float, pageId: String)
    /** Revert the most recent edit on the page (no-op if there's nothing to undo). */
    suspend fun undo(pageId: String)
}

/** No-op editor for previews/tests and the default ViewModel wiring. */
object NoOpPageEditor : PageEditor {
    override suspend fun delete(uuids: List<String>, pageId: String) {}
    override suspend fun recolor(uuids: List<String>, color: Int, pageId: String) {}
    override suspend fun setThickness(uuids: List<String>, width: Float, pageId: String) {}
    override suspend fun undo(pageId: String) {}
}
