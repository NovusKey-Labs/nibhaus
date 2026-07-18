package com.nibhaus.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * One physical notebook = one *instance* of an Ncode `book` model. A book id can be reused (you
 * finish a notebook and buy another of the same model), so the identity is `(book, instanceSeq)`,
 * not `book` alone. Marking a notebook `locked` (finished) makes the next ink on that book id
 * start a new instance instead of overlapping the old pages — the bug the brief calls out.
 */
@Entity(tableName = "notebooks", indices = [Index(value = ["book", "instanceSeq"], unique = true)])
data class NotebookEntity(
    @PrimaryKey val id: String,       // uuid
    val book: Int,                    // Ncode book id (model) — not unique on its own
    val instanceSeq: Int,             // 0,1,2… — which physical notebook of this model
    val locked: Boolean,              // finished → next ink starts a new instance
    val title: String,
    val createdAt: Long,
)

/** One physical Ncode page, unique within its notebook instance by Ncode page number. */
@Entity(
    tableName = "pages",
    indices = [Index(value = ["notebookId", "page"], unique = true)],
)
data class PageEntity(
    @PrimaryKey val id: String,       // uuid
    val notebookId: String,
    val addressKey: String,           // NcodeAddress.key — "section.owner.book.page"
    val section: Int,
    val owner: Int,
    val book: Int,
    val page: Int,
    val firstSeenAt: Long,
    val lastInkAt: Long,
    val transcript: String? = null,  // verbatim OCR text imported from the sync folder (search)
)

enum class SyncState { PENDING, SYNCED }

/**
 * A completed, immutable stroke. Append-only — this is what makes sync
 * conflict-free and the source of truth for rendering (complaint #1 & #2).
 */
@Entity(
    tableName = "strokes",
    indices = [Index(value = ["pageId"]), Index(value = ["syncState"])],
)
data class StrokeEntity(
    @PrimaryKey val uuid: String,
    val pageId: String,
    val color: Int,
    val startedAt: Long,
    val endedAt: Long,
    val pointsJson: String,           // serialized List<Point>
    val syncState: SyncState,
    val width: Float = 1f,            // render thickness multiplier on the base ink width (editing)
)

/**
 * Crash-recovery scratch for an in-progress stroke. Dots are flushed here on a
 * debounced cadence and on lifecycle pause, then promoted to a [StrokeEntity]
 * on PEN_UP and cleared. On launch, [com.nibhaus.ingest.StrokeIngestor.recover]
 * rebuilds any stroke that was interrupted mid-air.
 */
@Entity(tableName = "pending_dots", indices = [Index(value = ["pageKey"])])
data class PendingDotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pageKey: String,
    val seq: Int,
    val color: Int,
    val x: Float,
    val y: Float,
    val pressure: Float,
    val t: Long,
)

/**
 * Durable "pending export" queue. Inserted in the SAME transaction as the stroke, so a crash
 * never loses track of what still needs writing out. Drained by the local→NAS export in Phase 3
 * (Syncthing/Tailscale) — NOT a cloud upload.
 */
@Entity(tableName = "outbox", indices = [Index(value = ["enqueuedAt"])])
data class OutboxEntry(
    @PrimaryKey val strokeUuid: String,
    val enqueuedAt: Long,
    val attempts: Int = 0,
)

/**
 * A voice recording tied to one page. [startedAt] is wall-clock ms on the SAME clock as
 * [StrokeEntity.startedAt], so a stroke's audio offset = stroke.startedAt − recording.startedAt.
 * That single shared timestamp is what makes the audio "interactive" with the ink (tap a stroke →
 * seek the audio to when it was written, and vice-versa). Audio lives in app-private storage.
 */
@Entity(tableName = "recordings", indices = [Index(value = ["pageId"])])
data class RecordingEntity(
    @PrimaryKey val id: String,       // uuid
    val pageId: String,
    val path: String,                 // absolute path under filesDir/recordings
    val startedAt: Long,              // wall-clock ms at record start (aligns with stroke timestamps)
    val durationMs: Long,             // filled in on stop (0 while in progress)
    // SQL default must match the v5→v6 ALTER's DEFAULT '' or Room's schema check fails.
    @ColumnInfo(defaultValue = "''") val title: String = "", // user label; blank → shown as "Note N"
    // The page's stable Ncode address ("section.owner.book.page"); lets audio re-attach to a page even
    // if the page row is recreated (e.g. after a recovery). Default '' for rows from before v10.
    @ColumnInfo(defaultValue = "''") val addressKey: String = "",
)

/** A free-text tag on a page (many per page). Filterable in the Library (Phase E). */
@Entity(tableName = "page_tags", primaryKeys = ["pageId", "tag"], indices = [Index(value = ["tag"])])
data class PageTag(
    val pageId: String,
    val tag: String,
)

/**
 * Full-text search index over page transcripts (SQLite FTS4, porter tokenizer so "running" matches
 * "run"). It is a derived index, NOT a source of truth — `pages.transcript` owns the text. We keep
 * it in sync explicitly on every transcript write ([PageDao.setTranscriptIndexed]) rather than via
 * external-content triggers, because Room doesn't validate trigger correctness and a silently-stale
 * trigger would be worse than an index we can always rebuild from `pages`. The MIGRATION_8_9
 * backfill repopulates it from existing transcripts on upgrade. `pageId` ties a hit back to its page.
 */
@Fts4(tokenizer = FtsOptions.TOKENIZER_PORTER)
@Entity(tableName = "page_fts")
data class PageFts(
    val pageId: String,
    val transcript: String,
)

@Serializable
data class Point(val x: Float, val y: Float, val pressure: Float, val t: Long)

/**
 * Idempotency ledger for Phase 3 export. One row per page that has been exported, recording the
 * content hash and which target it went to. Re-export is skipped only when both still match —
 * so editing a page (new strokes → new hash) or switching sync target (new providerId) forces a
 * fresh write, but an unchanged page to the same target is never re-written or duplicated.
 */
@Entity(tableName = "export_records")
data class ExportRecord(
    @PrimaryKey val pageId: String,
    val contentHash: String,
    val providerId: String,
    val exportedAt: Long,
)

/**
 * Durable "pending remote delete" queue, mirroring [OutboxEntry]. `deletePageNow`'s `alsoRemote` path
 * used to call the sync target's delete inline, once — an unreachable target silently stranded the
 * remote copy forever. This row is enqueued instead (BEFORE the local page row is dropped, since
 * [basePath] is only computable while the page/notebook data still exists) and drained by the export
 * worker's own cadence, retried with backoff until the target confirms.
 */
@Entity(tableName = "pending_remote_deletes", indices = [Index(value = ["enqueuedAt"])])
data class PendingRemoteDelete(
    @PrimaryKey val pageId: String,
    val basePath: String,   // precomputed export base path (no extension), e.g. "pnb/Work/PNB_Work_Pg038"
    val enqueuedAt: Long,
    val attempts: Int = 0,
)

/** A durable post-commit cleanup operation for state outside Room (files, SAF, DataStore). */
@Entity(tableName = "pending_local_delete_cleanup", indices = [Index(value = ["enqueuedAt"])])
data class PendingLocalDeleteCleanup(
    @PrimaryKey val id: String,
    val kind: String,
    val target: String,
    val enqueuedAt: Long,
)
