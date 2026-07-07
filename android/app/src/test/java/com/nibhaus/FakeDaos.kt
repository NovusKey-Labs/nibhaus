package com.nibhaus

import com.nibhaus.data.ExportDao
import com.nibhaus.data.ExportRecord
import com.nibhaus.data.IngestDao
import com.nibhaus.data.NotebookDao
import com.nibhaus.data.NotebookEntity
import com.nibhaus.data.OutboxDao
import com.nibhaus.data.OutboxEntry
import com.nibhaus.data.PageDao
import com.nibhaus.data.PageEntity
import com.nibhaus.data.PageTag
import com.nibhaus.data.PendingRemoteDelete
import com.nibhaus.data.PendingRemoteDeleteDao
import com.nibhaus.data.RecordingDao
import com.nibhaus.data.RecordingEntity
import com.nibhaus.data.TagDao
import com.nibhaus.data.PendingDotDao
import com.nibhaus.data.PendingDotEntity
import com.nibhaus.data.StrokeDao
import com.nibhaus.data.StrokeEntity
import com.nibhaus.data.SyncState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory DAO fakes so the whole pipeline runs as plain JVM unit tests, with
 * no Android, no Room compiler, and no device. Behavior matches the @Query
 * semantics of the real DAOs (IGNORE-on-conflict, ordering, etc.).
 */
class FakeNotebookDao : NotebookDao {
    val byId = LinkedHashMap<String, NotebookEntity>()
    private val flow = MutableStateFlow<List<NotebookEntity>>(emptyList())
    override suspend fun insert(notebook: NotebookEntity) {
        // unique (book, instanceSeq)
        if (byId.values.none { it.book == notebook.book && it.instanceSeq == notebook.instanceSeq }) {
            byId[notebook.id] = notebook; emit()
        }
    }
    override suspend fun activeForBook(book: Int) =
        byId.values.filter { it.book == book }.maxByOrNull { it.instanceSeq }
    override suspend fun deleteInvalidBooks() { byId.values.filter { it.book <= 0 }.forEach { byId.remove(it.id) } }
    override suspend fun byId(id: String): NotebookEntity? = byId[id]
    override suspend fun lock(id: String) {
        byId[id]?.let { byId[id] = it.copy(locked = true); emit() }
    }
    override suspend fun rename(id: String, title: String) {
        byId[id]?.let { byId[id] = it.copy(title = title); emit() }
    }
    override suspend fun delete(id: String) { byId.remove(id); emit() }
    override fun observeAll(): Flow<List<NotebookEntity>> = flow
    override suspend fun searchByTitle(q: String): List<NotebookEntity> =
        byId.values.filter { it.title.contains(q, ignoreCase = true) }.sortedByDescending { it.createdAt }
    private fun emit() { flow.value = byId.values.sortedByDescending { it.createdAt } }
}

class FakePageDao : PageDao {
    val byId = LinkedHashMap<String, PageEntity>()
    override suspend fun insert(page: PageEntity) {
        // unique (notebookId, page)
        if (byId.values.none { it.notebookId == page.notebookId && it.page == page.page }) {
            byId[page.id] = page
        }
    }
    override suspend fun findInNotebook(notebookId: String, page: Int) =
        byId.values.firstOrNull { it.notebookId == notebookId && it.page == page }
    override suspend fun touch(id: String, ts: Long) { byId[id]?.let { byId[id] = it.copy(lastInkAt = ts) } }
    override fun observeByNotebook(notebookId: String): Flow<List<PageEntity>> =
        MutableStateFlow(byId.values.filter { it.notebookId == notebookId }.sortedBy { it.page })
    override fun observeAll(): Flow<List<PageEntity>> =
        MutableStateFlow(byId.values.sortedByDescending { it.lastInkAt })
    override fun observeLatest(): Flow<PageEntity?> =
        MutableStateFlow(byId.values.maxByOrNull { it.lastInkAt })
    override fun observeCount(): Flow<Int> = MutableStateFlow(byId.size)
    override suspend fun byId(id: String): PageEntity? = byId[id]
    override suspend fun setTranscript(id: String, text: String) { byId[id]?.let { byId[id] = it.copy(transcript = text) } }
    override suspend fun searchByTranscript(q: String): List<PageEntity> =
        byId.values.filter { it.transcript?.contains(q, ignoreCase = true) == true }.sortedByDescending { it.lastInkAt }

    /** In-memory stand-in for the real FTS MATCH (e.g. "milk* eggs*"): every de-starred word must
     *  appear in the transcript. Good enough to exercise the repo's orchestration; the genuine
     *  porter/word-boundary behaviour is covered on a device by FtsMigrationTest. */
    override suspend fun searchByTranscriptFts(match: String): List<PageEntity> {
        val terms = match.split(" ").map { it.removeSuffix("*").lowercase() }.filter { it.isNotBlank() }
        return byId.values
            .filter { p -> p.transcript?.lowercase()?.let { t -> terms.all { t.contains(it) } } == true }
            .sortedByDescending { it.lastInkAt }
    }
    override suspend fun deleteFtsForPage(id: String) {}
    override suspend fun insertFts(id: String, text: String) {}
    override suspend fun pagesInNotebook(notebookId: String): List<PageEntity> =
        byId.values.filter { it.notebookId == notebookId }.sortedByDescending { it.lastInkAt }
    override suspend fun recentPages(limit: Int): List<PageEntity> =
        byId.values.sortedByDescending { it.lastInkAt }.take(limit)
    override suspend fun allIds(): List<String> = byId.keys.toList()
    override suspend fun delete(id: String) { byId.remove(id) }
    override suspend fun idsWithInvalidAddress(): List<String> =
        byId.values.filter { it.book <= 0 || it.page < 0 }.map { it.id }

    /** Simplified vs. the real @Query (no stroke-existence join — this fake has no StrokeDao
     *  reference); good enough since callers re-check idempotency before acting on the result. */
    override suspend fun pagesNeedingTranscription(): List<PageEntity> =
        byId.values.filter { it.transcript.isNullOrBlank() }.sortedByDescending { it.lastInkAt }

    /** Simplified vs. the real @Query (no stroke-existence join — same limitation as
     *  [pagesNeedingTranscription], this fake has no StrokeDao reference): returns every page in the
     *  notebook rather than only the ones with ink. */
    override fun observeNonBlankPageIds(notebookId: String): Flow<List<String>> =
        MutableStateFlow(byId.values.filter { it.notebookId == notebookId }.map { it.id })

    /** Simplified vs. the real @Query (no stroke-existence join — same limitation as
     *  [observeNonBlankPageIds] above): returns every page id rather than only the ones with ink. */
    override fun observeAllNonBlankPageIds(): Flow<List<String>> = MutableStateFlow(byId.keys.toList())

    /** Mirrors the real `GROUP BY notebookId` @Query (Feature 19 / P1-1 library-grid batching). */
    override fun observePageCounts(): Flow<Map<String, Int>> =
        MutableStateFlow(byId.values.groupingBy { it.notebookId }.eachCount())
}

class FakeStrokeDao : StrokeDao {
    val byId = LinkedHashMap<String, StrokeEntity>()
    override suspend fun insert(stroke: StrokeEntity) { byId.putIfAbsent(stroke.uuid, stroke) }
    override fun observeByPage(pageId: String): Flow<List<StrokeEntity>> =
        MutableStateFlow(byId.values.filter { it.pageId == pageId }.sortedBy { it.startedAt })
    override suspend fun strokesForPage(pageId: String) =
        byId.values.filter { it.pageId == pageId }.sortedBy { it.startedAt }
    override suspend fun deleteForPage(pageId: String) {
        byId.keys.filter { byId[it]?.pageId == pageId }.forEach { byId.remove(it) }
    }
    override suspend fun byUuids(uuids: List<String>) = uuids.mapNotNull { byId[it] }
    override suspend fun markSync(uuids: List<String>, state: SyncState) {
        uuids.forEach { u -> byId[u]?.let { byId[u] = it.copy(syncState = state) } }
    }
    override suspend fun countForPage(pageId: String) = byId.values.count { it.pageId == pageId }
    override suspend fun delete(uuid: String) { byId.remove(uuid) }
    override suspend fun setColor(uuid: String, color: Int) {
        byId[uuid]?.let { byId[uuid] = it.copy(color = color) }
    }
    override suspend fun setWidth(uuid: String, width: Float) {
        byId[uuid]?.let { byId[uuid] = it.copy(width = width) }
    }
    override suspend fun latestOnPage(pageId: String) =
        byId.values.filter { it.pageId == pageId }.maxByOrNull { it.startedAt }
}

class FakePendingDotDao : PendingDotDao {
    val rows = ArrayList<PendingDotEntity>()
    private var nextId = 1L
    override suspend fun insertAll(dots: List<PendingDotEntity>) {
        dots.forEach { rows.add(it.copy(id = nextId++)) }
    }
    override suspend fun forPage(pageKey: String) = rows.filter { it.pageKey == pageKey }.sortedBy { it.seq }
    override suspend fun pageKeysWithPending() = rows.map { it.pageKey }.distinct()
    override suspend fun clearPage(pageKey: String) { rows.removeAll { it.pageKey == pageKey } }
}

class FakeOutboxDao : OutboxDao {
    val rows = LinkedHashMap<String, OutboxEntry>()
    private val backlog = MutableStateFlow(0)
    override suspend fun enqueue(entry: OutboxEntry) { rows.putIfAbsent(entry.strokeUuid, entry); sync() }
    override suspend fun peek(limit: Int) = rows.values.sortedBy { it.enqueuedAt }.take(limit)
    override suspend fun remove(uuids: List<String>) { uuids.forEach { rows.remove(it) }; sync() }
    override suspend fun bumpAttempts(uuids: List<String>) {
        uuids.forEach { u -> rows[u]?.let { rows[u] = it.copy(attempts = it.attempts + 1) } }
    }
    override fun observeBacklog(): Flow<Int> = backlog
    private fun sync() { backlog.value = rows.size }
}

class FakePendingRemoteDeleteDao : PendingRemoteDeleteDao {
    val rows = LinkedHashMap<String, PendingRemoteDelete>()
    private val backlog = MutableStateFlow(0)
    override suspend fun enqueue(entry: PendingRemoteDelete) { rows[entry.pageId] = entry; sync() }
    override suspend fun peek(limit: Int) = rows.values.sortedBy { it.enqueuedAt }.take(limit)
    override suspend fun remove(pageId: String) { rows.remove(pageId); sync() }
    override suspend fun bumpAttempts(pageId: String) {
        rows[pageId]?.let { rows[pageId] = it.copy(attempts = it.attempts + 1) }
    }
    override fun observeBacklog(): Flow<Int> = backlog
    private fun sync() { backlog.value = rows.size }
}

class FakeExportDao : ExportDao {
    val byPage = LinkedHashMap<String, ExportRecord>()
    override suspend fun find(pageId: String) = byPage[pageId]
    override suspend fun upsert(record: ExportRecord) { byPage[record.pageId] = record }
    override suspend fun deleteForPage(pageId: String) { byPage.remove(pageId) }
}

/**
 * [pageDao], when supplied, resolves each recording's pageId to a notebookId for
 * [observeNotebooksWithAudio] — the real @Query does this with a SQL join; this fake needs the page
 * table to do the same lookup. Optional (defaults to null, matching every pre-existing call site)
 * since most tests only exercise the page-level queries below.
 */
class FakeRecordingDao(private val pageDao: FakePageDao? = null) : RecordingDao {
    val rows = LinkedHashMap<String, RecordingEntity>()
    override suspend fun insert(recording: RecordingEntity) { rows.putIfAbsent(recording.id, recording) }
    override suspend fun setDuration(id: String, durationMs: Long) {
        rows[id]?.let { rows[id] = it.copy(durationMs = durationMs) }
    }
    override suspend fun setTitle(id: String, title: String) {
        rows[id]?.let { rows[id] = it.copy(title = title) }
    }
    override fun observeByPage(pageId: String): kotlinx.coroutines.flow.Flow<List<RecordingEntity>> =
        kotlinx.coroutines.flow.MutableStateFlow(emptyList())
    override suspend fun forPage(pageId: String): List<RecordingEntity> = emptyList()
    override suspend fun deleteForPage(pageId: String) { rows.entries.removeIf { it.value.pageId == pageId } }

    /** Simplified vs. the real @Query (joins on the raw `pageId` column, not `addressKey` — this
     *  fake doesn't model page recreation) — good enough to exercise the batching wiring (P1-1). */
    override fun observePagesWithAudio(): kotlinx.coroutines.flow.Flow<List<String>> =
        kotlinx.coroutines.flow.MutableStateFlow(rows.values.map { it.pageId }.distinct())

    /** Resolves pageId -> notebookId via [pageDao] (null → empty, same as having no recordings). */
    override fun observeNotebooksWithAudio(): kotlinx.coroutines.flow.Flow<List<String>> {
        val pd = pageDao ?: return kotlinx.coroutines.flow.MutableStateFlow(emptyList())
        val notebookIds = rows.values.mapNotNull { pd.byId[it.pageId]?.notebookId }.distinct()
        return kotlinx.coroutines.flow.MutableStateFlow(notebookIds)
    }

    override suspend fun delete(id: String) { rows.remove(id) }
}

class FakeTagDao : TagDao {
    val tags = LinkedHashMap<String, MutableSet<String>>() // pageId -> tags
    override suspend fun add(tag: PageTag) { tags.getOrPut(tag.pageId) { mutableSetOf() }.add(tag.tag) }
    override suspend fun remove(pageId: String, tag: String) { tags[pageId]?.remove(tag) }
    override suspend fun deleteForPage(pageId: String) { tags.remove(pageId) }
    override fun observeForPage(pageId: String): kotlinx.coroutines.flow.Flow<List<String>> =
        kotlinx.coroutines.flow.MutableStateFlow(tags[pageId]?.sorted() ?: emptyList())
    override suspend fun tagsForPage(pageId: String): List<String> =
        tags[pageId]?.sorted() ?: emptyList()
    override fun observeAllTags(): kotlinx.coroutines.flow.Flow<List<String>> =
        kotlinx.coroutines.flow.MutableStateFlow(tags.values.flatten().distinct().sorted())
    override fun observePagesWithTag(tag: String): kotlinx.coroutines.flow.Flow<List<PageEntity>> =
        kotlinx.coroutines.flow.MutableStateFlow(emptyList())
}

/** Concrete IngestDao whose abstract members hit the fakes; commitStroke uses the real default body. */
class FakeIngestDao(
    private val strokeDao: FakeStrokeDao,
    private val outboxDao: FakeOutboxDao,
    private val pendingDao: FakePendingDotDao,
) : IngestDao() {
    override suspend fun insertStroke(stroke: StrokeEntity) = strokeDao.insert(stroke)
    override suspend fun enqueueOutbox(entry: OutboxEntry) = outboxDao.enqueue(entry)
    override suspend fun clearPending(pageKey: String) = pendingDao.clearPage(pageKey)
}
