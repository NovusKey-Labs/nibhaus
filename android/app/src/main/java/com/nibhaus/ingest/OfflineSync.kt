package com.nibhaus.ingest

import com.nibhaus.data.IngestDao
import com.nibhaus.data.Point
import com.nibhaus.data.StrokeEntity
import com.nibhaus.data.SyncState
import com.nibhaus.organize.AutoOrganizer
import com.nibhaus.pen.NcodeAddress
import com.nibhaus.pen.NeoPenSdk
import com.nibhaus.pen.OfflineBatch
import com.nibhaus.pen.OfflineStroke
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Phase 2 — pulls the pages a pen stored offline (~1,000 M1+ / ~160 LAMY A4 pages) and ingests
 * them with **no page loss**. Each stroke gets a CONTENT-derived id, which makes the whole thing:
 *  - **idempotent** — re-requesting offline data re-inserts the same ids (INSERT IGNORE → no dupes);
 *  - **resumable** — a download interrupted halfway just continues on retry, no double-counting.
 * Every persisted offline stroke also lands in the export outbox, exactly like live capture.
 *
 * Real flow: `setAllowOfflineData(true)` → `requestOfflineDataList()` → the SDK streams batches to
 * the listener. Fully testable end-to-end against `FakeNeoPenSdk` (no hardware).
 */
class OfflineSync(
    private val sdk: NeoPenSdk,
    private val organizer: AutoOrganizer,
    private val ingestDao: IngestDao,
    private val scope: CoroutineScope,
) {
    private val json = Json

    init { sdk.setOfflineListener { batch -> scope.launch { ingest(batch) } } }

    /** Ask the pen for everything it has stored. Call once the pen is connected/authorized. */
    fun requestAll() {
        sdk.setAllowOfflineData(true)
        sdk.requestOfflineDataList()
    }

    /** Ingest one delivered batch idempotently. Returns the number of strokes received. */
    suspend fun ingest(batch: OfflineBatch): Int {
        for (s in batch.strokes) {
            if (s.points.isEmpty()) continue
            // Use the stroke's real (old) timestamp so re-ingested offline pages don't bump to "now"
            // and hijack the live capture view; the dedupe makes a re-download a true no-op.
            val page = organizer.ensurePage(
                NcodeAddress(s.section, s.owner, s.note, s.page),
                inkAt = s.points.last().t,
            )
            val points = s.points.map { Point(it.x, it.y, it.pressure, it.t) }
            val stroke = StrokeEntity(
                uuid = strokeId(s),
                pageId = page.id,
                color = 0, // brand ink — the canvas renders 0 as the theme primary (visible on any theme)
                startedAt = s.points.first().t,
                endedAt = s.points.last().t,
                pointsJson = json.encodeToString(ListSerializer(Point.serializer()), points),
                syncState = SyncState.PENDING,
            )
            // "offline" pageKey has no pending scratch to clear; INSERT IGNORE dedupes re-downloads.
            ingestDao.commitStroke(stroke, pageKey = "offline")
        }
        return batch.strokes.size
    }

    /**
     * Stable id from stroke content so the same stored stroke always maps to the same row. Keyed on
     * the Ncode location + timing + point count + a hash of all points; a collision would need two
     * genuinely different strokes to match on all of those at once (negligible).
     */
    private fun strokeId(s: OfflineStroke): String {
        val shape = s.points.joinToString(";") { "${it.x},${it.y},${it.pressure},${it.t}" }
        return "off-${s.section}.${s.owner}.${s.note}.${s.page}" +
            "-${s.points.first().t}-${s.points.last().t}-${s.points.size}-${shape.hashCode()}"
    }
}
