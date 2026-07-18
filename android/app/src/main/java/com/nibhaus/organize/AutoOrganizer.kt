package com.nibhaus.organize

import com.nibhaus.data.NotebookDao
import com.nibhaus.data.NotebookEntity
import com.nibhaus.data.PageDao
import com.nibhaus.data.PageEntity
import com.nibhaus.pen.NcodeAddress
import java.util.UUID

/**
 * Automatically sorts pages into notebooks instead of requiring manual filing.
 *
 * The Ncode address *is* the filing system: a page's `book` id deterministically
 * selects a notebook, and `(book, page)` selects a page within it. Filing is a
 * pure consequence of writing — the user never sorts anything.
 *
 * [ensurePage] is idempotent: it returns the existing page if seen before, else
 * creates the notebook (first time the book id appears) and the page, then
 * returns it. Notebook titles come from [NotebookCatalog]; renaming a notebook
 * does not change the mapping (keyed on `book`, not title).
 */
class AutoOrganizer(
    private val notebookDao: NotebookDao,
    private val pageDao: PageDao,
    private val catalog: NotebookCatalog = NotebookCatalog.DEFAULT,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun ensurePage(address: NcodeAddress, inkAt: Long = now(), touch: Boolean = true): PageEntity {
        val notebook = activeNotebook(address.book)
        pageDao.findInNotebook(notebook.id, address.page)?.let { existing ->
            // [inkAt] is the real ink time; offline ingest passes the stroke's (old) timestamp so
            // re-downloaded pages don't masquerade as "just written" and hijack the live view.
            // [touch]=false for non-ink resolution (an action-zone tap leaves no ink — bumping
            // lastInkAt would fire the auto-open-capture gate underneath the tap's own UI).
            if (touch) pageDao.touch(existing.id, inkAt)
            return existing
        }
        val page = PageEntity(
            id = newId(),
            notebookId = notebook.id,
            addressKey = address.key,
            section = address.section,
            owner = address.owner,
            book = address.book,
            page = address.page,
            firstSeenAt = now(),
            lastInkAt = inkAt,
        )
        pageDao.insert(page)
        // Re-read to tolerate a race where two pages of the same notebook arrive at once.
        return pageDao.findInNotebook(notebook.id, address.page) ?: page
    }

    /** Mark a notebook finished — subsequent ink on the same book model starts a new instance. */
    suspend fun finishNotebook(notebookId: String) = notebookDao.lock(notebookId)

    /**
     * The notebook a page should file into: the current (highest-seq) instance of the book model,
     * unless it's locked — in which case start the next instance so old pages aren't overwritten.
     */
    private suspend fun activeNotebook(book: Int): NotebookEntity {
        val current = notebookDao.activeForBook(book)
        if (current != null && !current.locked) return current
        val seq = (current?.instanceSeq ?: -1) + 1
        val base = catalog.titleFor(book)
        val notebook = NotebookEntity(
            id = newId(),
            book = book,
            instanceSeq = seq,
            locked = false,
            title = if (seq == 0) base else "$base #${seq + 1}",
            createdAt = now(),
        )
        notebookDao.insert(notebook)
        return notebookDao.activeForBook(book) ?: notebook
    }
}
