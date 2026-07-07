package com.nibhaus.edit

import com.nibhaus.data.OutboxDao
import com.nibhaus.data.OutboxEntry
import com.nibhaus.data.StrokeDao
import com.nibhaus.data.StrokeEntity
import com.nibhaus.data.SyncState

/**
 * Phase 5 editing. Strokes are append-only ink; an edit recolors, resizes, or deletes a *selection*
 * of strokes and then re-queues the page for export, so the change reaches the user's sync target.
 * The export engine is idempotent by content hash, so the edited page is re-written (new hash)
 * rather than duplicated.
 *
 * Undo reverts the last *edit* (not the last stroke you wrote): each action records the inverse of
 * what it changed (old colors / old widths / the deleted strokes), pushed as one batch onto a
 * per-page, in-memory stack. [undo] pops and applies the inverse.
 *
 * Note (known limitation): the undo stack is in-memory (a within-session convenience), and
 * deleting every stroke on a page leaves the previously-exported file on the target — we only
 * re-write pages that still have ink.
 */
class StrokeEditor(
    private val strokeDao: StrokeDao,
    private val outboxDao: OutboxDao,
    private val now: () -> Long = System::currentTimeMillis,
    /** Wired with the edited page id to enqueue the export drain and write the at-ingest safety backup. */
    private val onChanged: (pageId: String) -> Unit = {},
) : PageEditor {

    private sealed interface UndoOp
    private data class ReColor(val uuid: String, val color: Int) : UndoOp
    private data class ReWidth(val uuid: String, val width: Float) : UndoOp
    private data class ReInsert(val stroke: StrokeEntity) : UndoOp

    /** Per-page stack of reversible edit batches (newest last). */
    private val undoStacks = mutableMapOf<String, ArrayDeque<List<UndoOp>>>()

    override suspend fun recolor(uuids: List<String>, color: Int, pageId: String) {
        val before = strokeDao.byUuids(uuids)
        if (before.isEmpty()) return
        before.forEach { strokeDao.setColor(it.uuid, color) }
        push(pageId, before.map { ReColor(it.uuid, it.color) })
        requeue(pageId)
    }

    override suspend fun setThickness(uuids: List<String>, width: Float, pageId: String) {
        val before = strokeDao.byUuids(uuids)
        if (before.isEmpty()) return
        before.forEach { strokeDao.setWidth(it.uuid, width) }
        push(pageId, before.map { ReWidth(it.uuid, it.width) })
        requeue(pageId)
    }

    override suspend fun delete(uuids: List<String>, pageId: String) {
        val before = strokeDao.byUuids(uuids)
        if (before.isEmpty()) return
        before.forEach { strokeDao.delete(it.uuid) }
        outboxDao.remove(before.map { it.uuid })
        push(pageId, before.map { ReInsert(it) })
        requeue(pageId)
    }

    override suspend fun undo(pageId: String) {
        val batch = undoStacks[pageId]?.removeLastOrNull() ?: return
        batch.forEach { op ->
            when (op) {
                is ReColor -> strokeDao.setColor(op.uuid, op.color)
                is ReWidth -> strokeDao.setWidth(op.uuid, op.width)
                is ReInsert -> strokeDao.insert(op.stroke)
            }
        }
        requeue(pageId)
    }

    private fun push(pageId: String, batch: List<UndoOp>) {
        if (batch.isEmpty()) return
        val stack = undoStacks.getOrPut(pageId) { ArrayDeque() }
        stack.addLast(batch)
        while (stack.size > MAX_UNDO) stack.removeFirst()
    }

    /** The page's exported artifact is now stale — re-queue its remaining strokes for a fresh write. */
    private suspend fun requeue(pageId: String) {
        val remaining = strokeDao.strokesForPage(pageId)
        if (remaining.isNotEmpty()) {
            strokeDao.markSync(remaining.map { it.uuid }, SyncState.PENDING)
            remaining.forEach { outboxDao.enqueue(OutboxEntry(it.uuid, now())) }
        }
        onChanged(pageId)
    }

    private companion object {
        const val MAX_UNDO = 50 // cap the in-memory history per page
    }
}
