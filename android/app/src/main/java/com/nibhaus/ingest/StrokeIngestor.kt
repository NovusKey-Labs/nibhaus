package com.nibhaus.ingest

import com.nibhaus.data.IngestDao
import com.nibhaus.data.PageDao
import com.nibhaus.data.PendingDotDao
import com.nibhaus.data.PendingDotEntity
import com.nibhaus.data.Point
import com.nibhaus.data.StrokeEntity
import com.nibhaus.data.SyncState
import com.nibhaus.organize.AutoOrganizer
import com.nibhaus.pen.NcodeAddress
import com.nibhaus.pen.PenDot
import com.nibhaus.zones.ActionZone
import com.nibhaus.zones.boundsOf
import com.nibhaus.zones.tapCentre
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * The core of complaint #1 — "the app started missing ~20% of strokes".
 *
 * Design: persist first, render from the database. Every [PenDot] is processed
 * serially through a [Channel] (preserving order, off the BLE callback thread):
 *
 *  - PEN_DOWN  → start a new in-progress buffer for the dot's page.
 *  - PEN_MOVE  → append; periodically flush the buffer to `pending_dots` so a
 *                crash mid-stroke loses at most the last few unflushed dots.
 *  - PEN_UP    → atomically commit the completed [StrokeEntity] AND enqueue it
 *                for sync, then clear the page's pending scratch — all in one
 *                transaction ([IngestDao.commitStroke]).
 *
 * On startup, [recover] promotes any `pending_dots` left by an interrupted
 * session into real strokes, so even an unfinished stroke survives a crash.
 *
 * There is exactly one copy of the ink — in the DB. Nothing waits in RAM for an
 * explicit "Sync", which is the failure mode of the official app.
 */
class StrokeIngestor(
    private val ingestDao: IngestDao,
    private val pendingDao: PendingDotDao,
    private val pageDao: PageDao,
    private val organizer: AutoOrganizer,
    private val scope: CoroutineScope,
    private val flushEveryNDots: Int = 8,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    /** Current ink color for new strokes (the live color picker). 0 = brand ink (theme primary). */
    private val inkColor: () -> Int = { 0 },
    /** Current writing width multiplier for new strokes (the live size picker). 1 = base. */
    private val inkWidth: () -> Float = { 1f },
    /** Invoked after a stroke is durably committed, with the committed page id; the app wires this to
     *  enqueue sync and write the at-ingest safety backup. */
    private val onCommitted: (pageId: String) -> Unit = {},
    /** The user's calibrated physical action zones (printed icons), synchronous. */
    private val actionZones: () -> List<ActionZone> = { emptyList() },
    /** Fired when a tap lands in an action zone (instead of becoming ink); carries the page tapped. */
    private val onZoneTap: (ActionZone, pageId: String, book: Int) -> Unit = { _, _, _ -> },
) {
    /**
     * When set, the NEXT pen stroke is captured as a bounding box [left, top, right, bottom] in raw
     * Ncode coords and suppressed — the calibration "trace the printed icon" flow, which gives the
     * icon's exact extent. Cleared after one capture.
     */
    @Volatile var onCalibrationTrace: ((book: Int, Float, Float, Float, Float) -> Unit)? = null

    private val json = Json
    private val channel = Channel<PenDot>(capacity = INGEST_BUFFER_DOTS)

    /** In-progress strokes keyed by page address. Survives only crash via pending_dots. */
    private val active = HashMap<String, ActiveStroke>()

    /** Pen-clock time of the last committed stroke per page, to spot the isolated wake-up tap. */
    private val lastStrokeEndByKey = HashMap<String, Long>()

    init {
        val consumer = scope.launch {
            for (dot in channel) process(dot)
        }
        consumer.invokeOnCompletion { cause -> channel.close(cause) }
    }

    /** Called synchronously from the pen layer for every dot. Backpressures when persistence stalls. */
    fun onDot(dot: PenDot) {
        val immediate = channel.trySend(dot)
        if (immediate.isSuccess) return
        if (immediate.isClosed) throw IllegalStateException("stroke ingest is closed", immediate.exceptionOrNull())

        // BLE delivery is sequential, so blocking this producer is a bounded, order-preserving spool:
        // no dot is dropped and memory cannot grow with an arbitrarily stalled database.
        val delivered = runBlocking { channel.runCatching { send(dot) } }
        delivered.getOrElse { throw IllegalStateException("stroke ingest is closed", it) }
    }

    private suspend fun process(dot: PenDot) {
        // Drop dots the pen emits before it's read a valid Ncode page id: book/page are the decoder's
        // "unknown" (-1) or garbage. Ingesting them spawns phantom "book -1" / negative-page pages and
        // splits real writing off its actual page. Valid Ncode ids are positive (book) / non-negative (page).
        if (dot.address.book <= 0 || dot.address.page < 0) return
        val key = dot.address.key
        when (dot.phase) {
            PenDot.Phase.DOWN -> {
                // An already-open entry for this key means the PREVIOUS stroke never got its PEN_UP —
                // a BLE disconnect (or any other mid-stroke drop) orphaned it. Its flushed crash-scratch
                // rows are stale: left alone, this new generation's flush would restart seq at 0 and
                // collide with them, and recover() would zig-zag between two unrelated strokes after a
                // later crash. Wipe the orphan's scratch before starting clean so a page key never has
                // more than one generation of pending rows alive at once.
                if (active.containsKey(key)) pendingDao.clearPage(key)
                active[key] = ActiveStroke(dot.address, dot.color, now()).also { it.add(dot) }
                flushPending(key) // persist the first dot immediately
            }
            PenDot.Phase.MOVE -> {
                val s = active.getOrPut(key) { ActiveStroke(dot.address, dot.color, now()) }
                s.add(dot)
                if (s.unflushedSince >= flushEveryNDots) flushPending(key)
            }
            PenDot.Phase.UP -> {
                val s = active.remove(key) ?: ActiveStroke(dot.address, dot.color, now())
                s.add(dot)
                commit(s)
            }
        }
    }

    /** Persist not-yet-committed dots of the active stroke for crash recovery. */
    private suspend fun flushPending(key: String) {
        val s = active[key] ?: return
        val toFlush = s.drainUnflushed()
        if (toFlush.isEmpty()) return
        pendingDao.insertAll(
            toFlush.mapIndexed { i, p ->
                PendingDotEntity(
                    pageKey = key,
                    seq = s.flushedCount + i,
                    color = s.color,
                    x = p.x, y = p.y, pressure = p.pressure, t = p.t,
                )
            },
        )
        s.markFlushed(toFlush.size)
    }

    /** Finalize a completed stroke atomically (stroke + outbox + clear pending). */
    private suspend fun commit(s: ActiveStroke) {
        if (s.points.isEmpty()) return
        val raw = s.points.map { it.x to it.y }
        val key = s.address.key
        // Calibration: the user traced a printed icon — capture its bounding box and suppress the ink.
        // (Clear the crash-scratch dot the press already flushed, so recover() can't resurrect it.)
        onCalibrationTrace?.let { cb ->
            boundsOf(raw)?.let { (l, t, r, b) ->
                onCalibrationTrace = null; pendingDao.clearPage(key); cb(s.address.book, l, t, r, b); return
            }
        }
        // A near-stationary press is a "tap", not writing.
        tapCentre(raw, TAP_EPS)?.let { (cx, cy) ->
            // 1) A tap on a printed icon fires that action instead of leaving ink. touch=false: a
            // button tap is a command, not ink — bumping lastInkAt would auto-open live capture
            // underneath the zone action's own UI (hijacks the page-detail view the user is on).
            com.nibhaus.zones.matchZone(actionZones(), s.address.book, cx, cy)?.let { zone ->
                onZoneTap(zone, organizer.ensurePage(s.address, touch = false).id, s.address.book)
                pendingDao.clearPage(key) // a tap leaves no ink — drop its crash-scratch dot too
                return
            }
            // 2) A *near-zero-spread* press (tighter than the zone tap) that is also isolated — the
            // first mark on the page, or one after a long idle gap (the pen had slept) — is the wake-up
            // tap the pen records with no ink on paper. Drop it. The tighter WAKE_EPS keeps a small but
            // real mark (a short diagonal, a tiny letter) safe; and a tap that closely follows writing
            // is kept regardless — that's real punctuation (a period, an i-dot). Losing real ink is the
            // one thing we won't do. Note: tune WAKE_EPS / WAKE_IDLE_MS once a wake-tap's spread and
            // the pen's sleep timeout are measured on hardware; the "first mark on the page" needs none.
            if (tapCentre(raw, WAKE_EPS) != null) {
                // Only REAL writing arms the punctuation guard (see commit tail: tap-committed dots
                // don't refresh the clock) — otherwise one ink-kept tap poisons every following tap
                // for the whole window, which is exactly the tap-tap-tap navigation case.
                val lastEnd = lastStrokeEndByKey[key]
                if (lastEnd == null || s.points.first().t - lastEnd > WAKE_IDLE_MS) {
                    // The isolated tap is a NAVIGATION gesture, not noise to swallow: the user taps a
                    // page to open it live without leaving a mark. ensurePage (touch=true) bumps the
                    // page's lastInkAt, which is exactly what the auto-open-capture gate watches — so
                    // the app jumps to that page — while committing no stroke. (Previously this branch
                    // dropped the tap entirely; "tap to open" only ever worked by accident, when a
                    // slightly smeared tap became a micro-stroke — leaving a real ink dot behind.)
                    organizer.ensurePage(s.address)
                    pendingDao.clearPage(key) // the wake tap leaves no ink — drop its crash-scratch dot
                    return
                }
            }
        }
        val page = organizer.ensurePage(s.address)
        val stroke = StrokeEntity(
            uuid = newId(),
            pageId = page.id,
            color = inkColor(),
            width = inkWidth(),
            startedAt = s.startedAt,
            endedAt = now(),
            pointsJson = json.encodeToString(ListSerializer(Point.serializer()), s.points),
            syncState = SyncState.PENDING,
        )
        ingestDao.commitStroke(stroke, s.address.key)
        pageDao.touch(page.id, now())
        // Arm the punctuation guard from REAL writing only. A tap kept as ink (a period/i-dot) must
        // not refresh the clock: field data (2026-07-02, pages 30/31) showed each ink-kept tap
        // re-arming the guard, chaining every subsequent quick tap into an ink dot for a full window.
        if (tapCentre(raw, TAP_EPS) == null) lastStrokeEndByKey[key] = s.points.last().t
        onCommitted(page.id)
    }

    /**
     * Rebuild strokes interrupted by a crash. Each page key with leftover
     * pending dots becomes one recovered stroke. Call once on app start.
     */
    suspend fun recover() {
        for (key in pendingDao.pageKeysWithPending()) {
            val pending = pendingDao.forPage(key).sortedBy { it.seq }
            if (pending.isEmpty()) continue
            // A single malformed pageKey must not abort recovery for every other interrupted stroke:
            // parse defensively and skip a bad row rather than throwing out of the whole loop.
            val parts = key.split(".").mapNotNull { it.toIntOrNull() }
            if (parts.size != 4) continue
            val address = NcodeAddress(parts[0], parts[1], parts[2], parts[3])
            val page = organizer.ensurePage(address)
            val points = pending.map { Point(it.x, it.y, it.pressure, it.t) }
            val stroke = StrokeEntity(
                uuid = newId(),
                pageId = page.id,
                color = inkColor(),
                width = inkWidth(),
                startedAt = pending.first().t,
                endedAt = pending.last().t,
                pointsJson = json.encodeToString(ListSerializer(Point.serializer()), points),
                syncState = SyncState.PENDING,
            )
            ingestDao.commitStroke(stroke, key)
        }
    }

    private companion object {
        // Roughly four seconds at a conservative 250 dots/sec; enough for normal DB jitter while
        // still placing a hard ceiling on memory during a prolonged stall.
        const val INGEST_BUFFER_DOTS = 1_024
        // Max Ncode-unit spread for a stroke to count as a "tap" (zone/calibration). May need tuning
        // on hardware once the Ncode coordinate scale is confirmed on the target pens.
        const val TAP_EPS = 2.0f

        // Tighter spread for the *wake-up tap*: that press is essentially a single point, much smaller
        // than a deliberate tap on a printed icon. Kept well under TAP_EPS so a small but real mark
        // (e.g. a 2-unit diagonal) is never mistaken for a wake tap and dropped.
        const val WAKE_EPS = 1.0f

        // A tap within this window after REAL writing on the page is punctuation (period, i-dot) and
        // kept as ink; beyond it, an isolated tap is the tap-to-open navigation gesture. Was 60s —
        // far wider than real punctuation timing — which made taps navigate only once a minute.
        // Field-tuned 2026-07-02: periods land within a few seconds of the word; 10s keeps a
        // comfortable margin while letting tap-navigation feel immediate. Known edge (accepted):
        // adding a period >10s after last touching that page becomes a navigation tap (no ink).
        const val WAKE_IDLE_MS = 10_000L
    }

    private class ActiveStroke(
        val address: NcodeAddress,
        val color: Int,
        val startedAt: Long,
    ) {
        val points = ArrayList<Point>()
        var flushedCount = 0; private set
        var unflushedSince = 0; private set

        fun add(dot: PenDot) {
            points.add(Point(dot.x, dot.y, dot.pressure, dot.timestamp))
            unflushedSince++
        }

        fun drainUnflushed(): List<Point> =
            if (unflushedSince == 0) emptyList()
            else points.subList(points.size - unflushedSince, points.size).toList()

        fun markFlushed(n: Int) {
            flushedCount += n
            unflushedSince -= n
        }
    }
}
