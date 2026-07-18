package com.nibhaus.export

import com.nibhaus.data.ExportDao
import com.nibhaus.data.ExportRecord
import com.nibhaus.data.NotebookDao
import com.nibhaus.data.OutboxDao
import com.nibhaus.data.PageDao
import com.nibhaus.data.Point
import com.nibhaus.data.StrokeDao
import com.nibhaus.data.StrokeEntity
import com.nibhaus.data.SyncState
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Phase 3 export core. Drains the durable outbox (strokes captured but not yet exported) by writing
 * one SVG + sidecar per touched page to the selected [StorageProvider]. Framework-free so the
 * integrity-critical behavior is unit-tested without Android:
 *
 *  - **idempotent** — a page already exported with the same content to the same target is skipped;
 *    re-export overwrites by name, so it can never duplicate or corrupt (DESIGN.md).
 *  - **never loses** — outbox entries are only removed after their page's write succeeds; a failed
 *    page is left queued (attempts bumped) and the caller retries with backoff.
 *  - **resumable** — the outbox is the source of work, so a crash mid-export just re-drains.
 */
class ExportEngine(
    private val strokeDao: StrokeDao,
    private val pageDao: PageDao,
    private val notebookDao: NotebookDao,
    private val outboxDao: OutboxDao,
    private val exportDao: ExportDao,
    private val decode: (StrokeEntity) -> List<Point>,
    private val penId: () -> String,
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * Optional page rasters, injected by the app (uses Android Bitmap). Returns (extension → bytes)
     * for each format rendered from one bitmap pass: a `.png` (so the OCR payload is **image +
     * strokes** — the SOTA HTR input) and a `.pdf` (a portable, printable copy in the sync folder).
     * Left null in tests so the engine stays framework-free.
     */
    private val renderRasters: ((List<StrokeEntity>, (StrokeEntity) -> List<Point>) -> List<Pair<String, ByteArray>>)? = null,
    /**
     * Resolve a book id to its notebook type (geometry + path code). Default = built-ins only; the app
     * injects one backed by the user's DataStore assignments so a typed-but-unmeasured notebook files
     * correctly. Suspending because the assignment read is async.
     */
    private val typeForBook: suspend (Int?) -> NotebookType? = { NotebookType.forBook(it) },
    /** A page's tags, for the Markdown frontmatter. Default empty so the engine stays framework-free. */
    private val tagsFor: suspend (String) -> List<String> = { emptyList() },
) {
    private val pageExportLocks = ConcurrentHashMap<String, Mutex>()

    /** @return true if everything pending was exported (or already up to date); false → retry. */
    suspend fun exportPending(provider: StorageProvider): Boolean {
        val pending = outboxDao.peek(Int.MAX_VALUE)
        if (pending.isEmpty()) return true

        val pendingByPage = strokeDao.byUuids(pending.map { it.strokeUuid })
            .groupBy { it.pageId }

        var allOk = true
        for ((pageId, pendingStrokes) in pendingByPage) {
            val uuids = pendingStrokes.map { it.uuid }
            val ok = runCatching { exportPageLocked(pageId, provider) }.isSuccess
            if (ok) {
                strokeDao.markSync(uuids, SyncState.SYNCED)
                outboxDao.remove(uuids)
            } else {
                outboxDao.bumpAttempts(uuids)
                allOk = false
            }
        }
        return allOk
    }

    /**
     * Export one page on demand (the page-detail "Export" action). Idempotent like the drain —
     * unchanged content already on the target is a no-op. On success the page's strokes are marked
     * SYNCED and dropped from the outbox so the background drain won't redo them.
     *
     * @return true if the page is on the target (written now or already up to date); false → failed,
     *   and the strokes stay queued for the automatic retry.
     */
    suspend fun exportSingle(pageId: String, provider: StorageProvider): Boolean {
        val ok = runCatching { exportPageLocked(pageId, provider) }.isSuccess
        if (ok) {
            val uuids = strokeDao.strokesForPage(pageId).map { it.uuid }
            if (uuids.isNotEmpty()) {
                strokeDao.markSync(uuids, SyncState.SYNCED)
                outboxDao.remove(uuids)
            }
        }
        return ok
    }

    private suspend fun exportPageLocked(pageId: String, provider: StorageProvider) {
        pageExportLocks.computeIfAbsent(pageId) { Mutex() }.withLock {
            exportPage(pageId, provider)
        }
    }

    private suspend fun exportPage(pageId: String, provider: StorageProvider) {
        val strokes = strokeDao.strokesForPage(pageId)
        if (strokes.isEmpty()) return

        val page = pageDao.byId(pageId)
        val hash = ExportArtifacts.contentHash(strokes, page?.transcript)
        val prior = exportDao.find(pageId)
        if (prior != null && prior.contentHash == hash && prior.providerId == provider.id) {
            return // already on this target with this content — nothing to do
        }

        // Human, type-driven path: pnb/Work/PNB_Work_Pg038 etc. Unknown notebook → the page UUID
        // (flat, today's behaviour). The path is cosmetic — identity/idempotency stay keyed by pageId
        // (the ledger row + the sidecar), so renaming a notebook never dupes or drops.
        val type = typeForBook(page?.book)
        val notebook = page?.notebookId?.let { notebookDao.byId(it) }
        val base = NotebookPaths.exportBaseName(
            type = type,
            label = notebook?.title.orEmpty(),
            instanceSeq = notebook?.instanceSeq ?: 0,
            page = page?.page ?: 0,
            // Planner pages file under date folders when the type carries a page→date layout; until a
            // planner's layout is measured this is null and NotebookPaths files it under its label.
            plannerDate = type?.plannerLayout?.dateFor(page?.page ?: 0),
            fallbackId = pageId,
        )
        val imageName = "${base.substringAfterLast('/')}.png" // co-located; Obsidian resolves by name

        val ts = now()
        // Decode each stroke's pointsJson exactly once and reuse it below — svg/inkml/rasters/backup
        // all need the same decoded points, and re-parsing the same JSON per format is wasted work.
        val decodedPoints = strokes.associateBy({ it.uuid }, decode)
        val cachedDecode: (StrokeEntity) -> List<Point> = { decodedPoints.getValue(it.uuid) }
        val rasters = renderRasters?.invoke(strokes, cachedDecode).orEmpty()
        val formats = listOf("svg", "inkml") + rasters.map { it.first } + listOf("md", "json")
        // Archival export → full-page true-size when we know this notebook's geometry (ink placed where
        // it physically sits on the sheet), with the REAL printed ruling + page number underneath;
        // unknown notebooks fall back to ink-cropped. Share/Print use the auto-fit raster (PageRender)
        // instead, so the mode follows the function the user invoked.
        val svg = ExportArtifacts.renderSvg(
            strokes, cachedDecode, type?.geometry,
            ruling = type?.ruling,
            pageNumber = page?.page,
            pageStyle = pageStyleFor(type?.pageBands ?: emptyList(), page?.page ?: -1),
        )
        val sidecar = ExportArtifacts.sidecarJson(
            pageId, page, penId(), strokes.size, hash, ts, formats,
            notebookType = type?.id, notebookLabel = notebook?.title,
        )

        provider.write("$base.svg", svg.toByteArray())
        provider.write(
            "$base.inkml",
            ExportArtifacts.renderInkML(strokes, cachedDecode, notebookType = type?.displayName, label = notebook?.title)
                .toByteArray(),
        )
        rasters.forEach { (ext, bytes) -> provider.write("$base.$ext", bytes) }
        provider.write(
            "$base.md",
            ExportArtifacts.renderMarkdown(
                pageId, page, page?.transcript, imageName,
                notebookType = type?.displayName, label = notebook?.title, tags = tagsFor(pageId),
            ).toByteArray(),
        )
        provider.write("$base.json", sidecar.toByteArray())
        // Full, self-contained editable-stroke backup (Ncode address + every point). This is the
        // restorable copy: point the destination at a LOCAL folder and it's an on-device backup that
        // survives an app-data wipe, retrievable without any remote sync. (See restoreFromFolder.)
        val backup = PageBackup(
            page?.section ?: 0, page?.owner ?: 0, page?.book ?: 0, page?.page ?: 0,
            strokes.map { BackupStroke(it.color, it.width, cachedDecode(it)) },
        )
        provider.write("$base.bak.json", encodeBackup(backup).toByteArray())

        exportDao.upsert(ExportRecord(pageId, hash, provider.id, ts))
    }

    /**
     * The export base path for [pageId] (without extension) — the same [NotebookPaths.exportBaseName]
     * computation [exportPage] used when it wrote this page's artifacts. Callers that need to delete a
     * page's remote artifacts LATER (after the local page row is gone — see [RemoteDeleteQueue]) must
     * capture this NOW, while the page/notebook data this depends on still exists.
     *
     * @return null if the page doesn't exist.
     */
    suspend fun remoteBasePath(pageId: String): String? {
        val page = pageDao.byId(pageId) ?: return null
        return basePathFor(page)
    }

    private suspend fun basePathFor(page: com.nibhaus.data.PageEntity): String {
        val type = typeForBook(page.book)
        val notebook = page.notebookId.let { notebookDao.byId(it) }
        return NotebookPaths.exportBaseName(
            type = type,
            label = notebook?.title.orEmpty(),
            instanceSeq = notebook?.instanceSeq ?: 0,
            page = page.page,
            plannerDate = type?.plannerLayout?.dateFor(page.page),
            fallbackId = page.id,
        )
    }

    /** Best-effort removal of a page's exported artifacts from [provider] — for "also delete from
     *  the sync destination". Recomputes the same base path the export wrote; missing files (or a
     *  target that can't delete) are ignored. A notebook renamed since export may strand its old-path
     *  files, since the path is label-derived — acceptable; identity stays keyed by pageId elsewhere.
     *  Immediate and swallows failures — prefer enqueuing through [RemoteDeleteQueue] instead so an
     *  unreachable target is retried rather than silently giving up. */
    suspend fun deleteRemote(pageId: String, provider: StorageProvider) {
        val page = pageDao.byId(pageId) ?: return
        val base = basePathFor(page)
        REMOTE_ARTIFACT_EXTENSIONS.forEach { ext -> runCatching { provider.delete("$base.$ext") } }
    }

    companion object {
        /** Every artifact extension one page's export can produce — shared by [deleteRemote]'s
         *  immediate best-effort delete and [RemoteDeleteQueue]'s durable retry drain. .txt is written
         *  by the NAS OCR watcher, not the app, but it's this page's artifact too; .bak.json must go
         *  too or restoreFromFolder would resurrect the page the user just deleted. */
        val REMOTE_ARTIFACT_EXTENSIONS = listOf("svg", "inkml", "png", "pdf", "md", "json", "bak.json", "txt")
    }
}

/** Result of a user-initiated single-page export, for UI feedback. */
enum class ExportOutcome { DONE, NO_TARGET, FAILED }
