package com.nibhaus.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.MapInfo
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface NotebookDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(notebook: NotebookEntity)

    /** The current physical notebook for a book model = the highest-numbered instance. */
    @Query("SELECT * FROM notebooks WHERE book = :book ORDER BY instanceSeq DESC LIMIT 1")
    suspend fun activeForBook(book: Int): NotebookEntity?

    /** Remove phantom "book -1"/0 notebooks left by pre-Ncode-lock dots (one-time cleanup). */
    @Query("DELETE FROM notebooks WHERE book <= 0")
    suspend fun deleteInvalidBooks()

    /** A specific notebook by id — the export path needs its label (title) + instanceSeq. */
    @Query("SELECT * FROM notebooks WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): NotebookEntity?

    /** Mark a notebook finished; the next ink on this book id starts a fresh instance. */
    @Query("UPDATE notebooks SET locked = 1 WHERE id = :id")
    suspend fun lock(id: String)

    @Query("UPDATE notebooks SET title = :title WHERE id = :id")
    suspend fun rename(id: String, title: String)

    /** Drop the notebook row itself; callers must first clear its pages, strokes, and related data. */
    @Query("DELETE FROM notebooks WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM notebooks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<NotebookEntity>>

    /** Notebooks whose title contains [q] (case-insensitive) — so search works before any OCR. */
    @Query("SELECT * FROM notebooks WHERE title LIKE '%' || :q || '%' ORDER BY createdAt DESC")
    suspend fun searchByTitle(q: String): List<NotebookEntity>
}

@Dao
interface PageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(page: PageEntity)

    /** A page is identified within its notebook instance by Ncode page number. */
    @Query("SELECT * FROM pages WHERE notebookId = :notebookId AND page = :page LIMIT 1")
    suspend fun findInNotebook(notebookId: String, page: Int): PageEntity?

    @Query("UPDATE pages SET lastInkAt = MAX(lastInkAt, :ts) WHERE id = :id")
    suspend fun touch(id: String, ts: Long)

    @Query("SELECT * FROM pages WHERE notebookId = :notebookId ORDER BY page ASC")
    fun observeByNotebook(notebookId: String): Flow<List<PageEntity>>

    /** Every captured page across all notebooks, newest-inked first — the base for cross-notebook
     *  views like Favorites. */
    @Query("SELECT * FROM pages ORDER BY lastInkAt DESC")
    fun observeAll(): Flow<List<PageEntity>>

    /** The page being written right now = the most recently inked one (drives the live-capture view). */
    @Query("SELECT * FROM pages ORDER BY lastInkAt DESC LIMIT 1")
    fun observeLatest(): Flow<PageEntity?>

    /** Total pages captured (for "N pages safe" in the disconnect alert). */
    @Query("SELECT COUNT(*) FROM pages")
    fun observeCount(): Flow<Int>

    /** Page metadata for an export sidecar. */
    @Query("SELECT * FROM pages WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): PageEntity?

    /** Store the OCR transcript imported from the sync folder. */
    @Query("UPDATE pages SET transcript = :text WHERE id = :id")
    suspend fun setTranscript(id: String, text: String)

    /** Pages whose transcript contains [q] (case-insensitive), newest first — handwriting search. */
    @Query("SELECT * FROM pages WHERE transcript LIKE '%' || :q || '%' ORDER BY lastInkAt DESC")
    suspend fun searchByTranscript(q: String): List<PageEntity>

    // --- full-text transcript search (FTS4) ---

    /**
     * Pages whose transcript matches the FTS [match] expression (e.g. "milk* eggs*" = both words,
     * any order, prefix). Joined back to `pages` and ordered newest-inked first. The porter
     * tokenizer means a query stem matches inflections ("run" ↔ "running").
     */
    @Query(
        "SELECT p.* FROM pages p JOIN page_fts f ON p.id = f.pageId " +
            "WHERE f.transcript MATCH :match ORDER BY p.lastInkAt DESC",
    )
    suspend fun searchByTranscriptFts(match: String): List<PageEntity>

    @Query("DELETE FROM page_fts WHERE pageId = :id")
    suspend fun deleteFtsForPage(id: String)

    @Query("DELETE FROM pages WHERE id = :id")
    suspend fun delete(id: String)

    /** Phantom pages from dots the pen emitted before reading a valid Ncode address (one-time cleanup). */
    @Query("SELECT id FROM pages WHERE book <= 0 OR page < 0")
    suspend fun idsWithInvalidAddress(): List<String>

    @Query("INSERT INTO page_fts(pageId, transcript) VALUES (:id, :text)")
    suspend fun insertFts(id: String, text: String)

    /**
     * The single funnel for transcript writes: persist the text on the page (source of truth) and
     * refresh its FTS row. Delete-then-insert keeps the index exact across re-imports. Sequenced
     * (not a transaction) deliberately — the FTS index is rebuildable from `pages`, so a crash
     * between the two only risks a momentarily stale search hit, never lost transcript text.
     */
    suspend fun setTranscriptIndexed(id: String, text: String) {
        setTranscript(id, text)
        deleteFtsForPage(id)
        insertFts(id, text)
    }

    /** All pages in a notebook, newest-inked first — to surface a notebook-title search match. */
    @Query("SELECT * FROM pages WHERE notebookId = :notebookId ORDER BY lastInkAt DESC")
    suspend fun pagesInNotebook(notebookId: String): List<PageEntity>

    /** Most recently inked pages — the empty-query "recents" list in Search. */
    @Query("SELECT * FROM pages ORDER BY lastInkAt DESC LIMIT :limit")
    suspend fun recentPages(limit: Int): List<PageEntity>

    @Query("SELECT id FROM pages")
    suspend fun allIds(): List<String>

    /**
     * Pages with ink but no transcript yet — the eager-transcription backlog (Tier-0 auto-OCR on
     * settle). "Needs one" = has at least one stroke AND transcript is null/blank; newest-inked
     * first so a catch-up pass clears the most relevant pages first.
     */
    @Query(
        "SELECT * FROM pages WHERE (transcript IS NULL OR TRIM(transcript) = '') " +
            "AND EXISTS (SELECT 1 FROM strokes WHERE strokes.pageId = pages.id) " +
            "ORDER BY lastInkAt DESC",
    )
    suspend fun pagesNeedingTranscription(): List<PageEntity>

    /**
     * Ids of pages in [notebookId] that have at least one stroke — feeds the Library "hide blank
     * pages" filter with ONE query instead of one stroke-observer per page. Reactive:
     * Room re-runs this whenever `pages` or `strokes` changes.
     */
    @Query(
        "SELECT p.id FROM pages p WHERE p.notebookId = :notebookId " +
            "AND EXISTS (SELECT 1 FROM strokes s WHERE s.pageId = p.id)",
    )
    fun observeNonBlankPageIds(notebookId: String): Flow<List<String>>

    /**
     * Ids of every page (any notebook) with at least one stroke — the same predicate as
     * [observeNonBlankPageIds] but unscoped, for cross-notebook views like the Pens home "Recent"
     * section (one row per notebook, its 3 most recently EDITED pages). ONE query shared
     * across every notebook's row instead of a per-notebook subscription.
     */
    @Query("SELECT p.id FROM pages p WHERE EXISTS (SELECT 1 FROM strokes s WHERE s.pageId = p.id)")
    fun observeAllNonBlankPageIds(): Flow<List<String>>

    /**
     * Page counts for every notebook, one grouped query — the library grid's per-card "N pages"
     * metadata used to `SELECT * FROM pages WHERE notebookId = :id` (the whole page
     * list) just to read its `.size`, once per visible NotebookThumb/NotebookListRow. Same batching
     * idea as [observeNonBlankPageIds]: ONE query shared across every visible card instead of one
     * per card.
     */
    @MapInfo(keyColumn = "notebookId", valueColumn = "pageCount")
    @Query("SELECT notebookId, COUNT(*) AS pageCount FROM pages GROUP BY notebookId")
    fun observePageCounts(): Flow<Map<String, Int>>
}

@Dao
interface StrokeDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(stroke: StrokeEntity)

    @Query("SELECT * FROM strokes WHERE pageId = :pageId ORDER BY startedAt ASC")
    fun observeByPage(pageId: String): Flow<List<StrokeEntity>>

    /** All strokes on a page, for rendering an export artifact (one-shot, not observed). */
    @Query("SELECT * FROM strokes WHERE pageId = :pageId ORDER BY startedAt ASC")
    suspend fun strokesForPage(pageId: String): List<StrokeEntity>

    @Query("SELECT * FROM strokes WHERE uuid IN (:uuids)")
    suspend fun byUuids(uuids: List<String>): List<StrokeEntity>

    @Query("UPDATE strokes SET syncState = :state WHERE uuid IN (:uuids)")
    suspend fun markSync(uuids: List<String>, state: SyncState)

    @Query("SELECT COUNT(*) FROM strokes WHERE pageId = :pageId")
    suspend fun countForPage(pageId: String): Int

    // --- editing (Phase 5) ---
    @Query("DELETE FROM strokes WHERE uuid = :uuid")
    suspend fun delete(uuid: String)

    @Query("DELETE FROM strokes WHERE pageId = :pageId")
    suspend fun deleteForPage(pageId: String)

    @Query("UPDATE strokes SET color = :color WHERE uuid = :uuid")
    suspend fun setColor(uuid: String, color: Int)

    @Query("UPDATE strokes SET width = :width WHERE uuid = :uuid")
    suspend fun setWidth(uuid: String, width: Float)

    /** Most recently started stroke on a page — the target of "undo". */
    @Query("SELECT * FROM strokes WHERE pageId = :pageId ORDER BY startedAt DESC LIMIT 1")
    suspend fun latestOnPage(pageId: String): StrokeEntity?
}

@Dao
interface PendingDotDao {
    @Insert suspend fun insertAll(dots: List<PendingDotEntity>)

    @Query("SELECT * FROM pending_dots WHERE pageKey = :pageKey ORDER BY seq ASC")
    suspend fun forPage(pageKey: String): List<PendingDotEntity>

    @Query("SELECT DISTINCT pageKey FROM pending_dots")
    suspend fun pageKeysWithPending(): List<String>

    @Query("DELETE FROM pending_dots WHERE pageKey = :pageKey")
    suspend fun clearPage(pageKey: String)
}

@Dao
interface RecordingDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(recording: RecordingEntity)

    @Query("UPDATE recordings SET durationMs = :durationMs WHERE id = :id")
    suspend fun setDuration(id: String, durationMs: Long)

    @Query("UPDATE recordings SET title = :title WHERE id = :id")
    suspend fun setTitle(id: String, title: String)

    // Recordings are matched by the page's stable Ncode address (joined via pages), so audio still
    // attaches after a page is recreated. Callers still pass a pageId.
    @Query("SELECT r.* FROM recordings r JOIN pages p ON r.addressKey = p.addressKey WHERE p.id = :pageId ORDER BY r.startedAt ASC")
    fun observeByPage(pageId: String): Flow<List<RecordingEntity>>

    @Query("SELECT r.* FROM recordings r JOIN pages p ON r.addressKey = p.addressKey WHERE p.id = :pageId")
    suspend fun forPage(pageId: String): List<RecordingEntity>

    @Query("DELETE FROM recordings WHERE addressKey IN (SELECT addressKey FROM pages WHERE id = :pageId)")
    suspend fun deleteForPage(pageId: String)

    /**
     * Ids of every page carrying at least one voice note, one query — the library grid used to run
     * this as one EXISTS observer per visible PageThumb, so any recording write re-ran it for every
     * card on screen. Joined via addressKey (not the raw `recordings.pageId` column), matching how
     * [observeByPage] resolves recordings, so a voice note still counts after its page row is
     * recreated (e.g. after a recovery).
     */
    @Query("SELECT DISTINCT p.id FROM recordings r INNER JOIN pages p ON r.addressKey = p.addressKey")
    fun observePagesWithAudio(): Flow<List<String>>

    /** Ids of every notebook with at least one page carrying a voice note, one query — same batching
     *  as [observePagesWithAudio] but for the notebook-level badge. */
    @Query("SELECT DISTINCT p.notebookId FROM recordings r INNER JOIN pages p ON r.addressKey = p.addressKey")
    fun observeNotebooksWithAudio(): Flow<List<String>>

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface TagDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(tag: PageTag)

    @Query("DELETE FROM page_tags WHERE pageId = :pageId AND tag = :tag")
    suspend fun remove(pageId: String, tag: String)

    @Query("DELETE FROM page_tags WHERE pageId = :pageId")
    suspend fun deleteForPage(pageId: String)

    @Query("SELECT tag FROM page_tags WHERE pageId = :pageId ORDER BY tag ASC")
    fun observeForPage(pageId: String): Flow<List<String>>

    /** One-shot tags for a page — used by export to stamp the Markdown frontmatter. */
    @Query("SELECT tag FROM page_tags WHERE pageId = :pageId ORDER BY tag ASC")
    suspend fun tagsForPage(pageId: String): List<String>

    /** All distinct tags in use, for the Library filter bar. */
    @Query("SELECT DISTINCT tag FROM page_tags ORDER BY tag ASC")
    fun observeAllTags(): Flow<List<String>>

    /** Page ids carrying a tag (newest-inked first), for the filtered Library view. */
    @Query(
        "SELECT p.* FROM pages p INNER JOIN page_tags t ON t.pageId = p.id " +
            "WHERE t.tag = :tag ORDER BY p.lastInkAt DESC",
    )
    fun observePagesWithTag(tag: String): Flow<List<PageEntity>>
}

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(entry: OutboxEntry)

    @Query("SELECT * FROM outbox ORDER BY enqueuedAt ASC LIMIT :limit")
    suspend fun peek(limit: Int): List<OutboxEntry>

    @Query("DELETE FROM outbox WHERE strokeUuid IN (:uuids)")
    suspend fun remove(uuids: List<String>)

    @Query("UPDATE outbox SET attempts = attempts + 1 WHERE strokeUuid IN (:uuids)")
    suspend fun bumpAttempts(uuids: List<String>)

    @Query("SELECT COUNT(*) FROM outbox")
    fun observeBacklog(): Flow<Int>
}

@Dao
interface PendingRemoteDeleteDao {
    /** REPLACE (not IGNORE): a page deleted again before its first remote delete drains should keep
     *  only its latest basePath — dead code path today (a deleted page can't be deleted again), but
     *  correct either way rather than silently keeping a stale one. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(entry: PendingRemoteDelete)

    @Query("SELECT * FROM pending_remote_deletes ORDER BY enqueuedAt ASC LIMIT :limit")
    suspend fun peek(limit: Int): List<PendingRemoteDelete>

    @Query("DELETE FROM pending_remote_deletes WHERE pageId = :pageId")
    suspend fun remove(pageId: String)

    @Query("UPDATE pending_remote_deletes SET attempts = attempts + 1 WHERE pageId = :pageId")
    suspend fun bumpAttempts(pageId: String)

    @Query("SELECT COUNT(*) FROM pending_remote_deletes")
    fun observeBacklog(): Flow<Int>
}

data class PageDeletionPlan(
    val pageId: String,
    val remoteDelete: PendingRemoteDelete?,
    val cleanups: List<PendingLocalDeleteCleanup>,
    val deleteAudio: Boolean,
)

/** Owns the complete Room boundary for destructive page/notebook deletion. */
@Dao
@Suppress("TooManyFunctions") // The transaction deliberately owns every delete and queue statement.
abstract class DeleteDao {
    @Query("DELETE FROM outbox WHERE strokeUuid IN (SELECT uuid FROM strokes WHERE pageId = :pageId)")
    protected abstract suspend fun deleteOutbox(pageId: String)
    @Query("DELETE FROM strokes WHERE pageId = :pageId")
    protected abstract suspend fun deleteStrokes(pageId: String)
    @Query("DELETE FROM page_fts WHERE pageId = :pageId")
    protected abstract suspend fun deleteFts(pageId: String)
    @Query("DELETE FROM export_records WHERE pageId = :pageId")
    protected abstract suspend fun deleteExport(pageId: String)
    @Query("DELETE FROM page_tags WHERE pageId = :pageId")
    protected abstract suspend fun deleteTags(pageId: String)
    @Query("DELETE FROM recordings WHERE addressKey IN (SELECT addressKey FROM pages WHERE id = :pageId)")
    protected abstract suspend fun deleteRecordings(pageId: String)
    @Query("DELETE FROM pages WHERE id = :pageId")
    protected abstract suspend fun deletePage(pageId: String)
    @Query("DELETE FROM notebooks WHERE id = :notebookId")
    protected abstract suspend fun deleteNotebook(notebookId: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun enqueueRemote(entry: PendingRemoteDelete)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun enqueueCleanups(entries: List<PendingLocalDeleteCleanup>)

    @Transaction
    open suspend fun deletePages(
        plans: List<PageDeletionPlan>,
        notebookId: String? = null,
        extraCleanups: List<PendingLocalDeleteCleanup> = emptyList(),
    ) {
        if (extraCleanups.isNotEmpty()) enqueueCleanups(extraCleanups)
        plans.forEach { plan ->
            plan.remoteDelete?.let { enqueueRemote(it) }
            if (plan.cleanups.isNotEmpty()) enqueueCleanups(plan.cleanups)
            deleteOutbox(plan.pageId)
            deleteStrokes(plan.pageId)
            deleteFts(plan.pageId)
            deleteExport(plan.pageId)
            deleteTags(plan.pageId)
            if (plan.deleteAudio) deleteRecordings(plan.pageId)
            deletePage(plan.pageId)
        }
        notebookId?.let { deleteNotebook(it) }
    }
}

@Dao
interface PendingLocalDeleteCleanupDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(entry: PendingLocalDeleteCleanup)
    @Query("SELECT * FROM pending_local_delete_cleanup ORDER BY enqueuedAt ASC LIMIT :limit")
    suspend fun peek(limit: Int): List<PendingLocalDeleteCleanup>
    @Query("DELETE FROM pending_local_delete_cleanup WHERE id = :id")
    suspend fun remove(id: String)
}

@Dao
interface ExportDao {
    @Query("SELECT * FROM export_records WHERE pageId = :pageId LIMIT 1")
    suspend fun find(pageId: String): ExportRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: ExportRecord)

    @Query("DELETE FROM export_records WHERE pageId = :pageId")
    suspend fun deleteForPage(pageId: String)
}

/**
 * The atomic write that guarantees an ingested stroke is never lost AND is
 * always queued for upload — both in one transaction.
 */
@Dao
abstract class IngestDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertStroke(stroke: StrokeEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun enqueueOutbox(entry: OutboxEntry)

    @Query("DELETE FROM pending_dots WHERE pageKey = :pageKey")
    abstract suspend fun clearPending(pageKey: String)

    @Transaction
    open suspend fun commitStroke(stroke: StrokeEntity, pageKey: String) {
        insertStroke(stroke)
        enqueueOutbox(OutboxEntry(stroke.uuid, stroke.endedAt))
        clearPending(pageKey)
    }
}
