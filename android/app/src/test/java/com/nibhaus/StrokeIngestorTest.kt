package com.nibhaus

import com.google.common.truth.Truth.assertThat
import com.nibhaus.data.Point
import com.nibhaus.data.SyncState
import com.nibhaus.ingest.StrokeIngestor
import com.nibhaus.organize.AutoOrganizer
import com.nibhaus.pen.PenDot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class StrokeIngestorTest {

    private val ids = AtomicInteger(0)
    private var clock = 1000L

    private fun dot(phase: PenDot.Phase, x: Float, page: Int = 1) = PenDot(
        section = 3, owner = 27, book = 603, page = page,
        x = x, y = x, pressure = 0.5f, phase = phase, timestamp = clock++, color = 0xFF000000.toInt(),
    )

    private fun fixture(scope: kotlinx.coroutines.CoroutineScope): Fix {
        val stroke = FakeStrokeDao(); val outbox = FakeOutboxDao()
        val pending = FakePendingDotDao(); val page = FakePageDao(); val nb = FakeNotebookDao()
        val ingest = FakeIngestDao(stroke, outbox, pending)
        val organizer = AutoOrganizer(nb, page, now = { clock }, newId = { "id-${ids.incrementAndGet()}" })
        val ingestor = StrokeIngestor(
            ingestDao = ingest, pendingDao = pending, pageDao = page, organizer = organizer,
            scope = scope, flushEveryNDots = 2, now = { clock }, newId = { "s-${ids.incrementAndGet()}" },
        )
        return Fix(ingestor, stroke, outbox, pending, page)
    }

    private class Fix(
        val ingestor: StrokeIngestor,
        val strokeDao: FakeStrokeDao,
        val outboxDao: FakeOutboxDao,
        val pendingDao: FakePendingDotDao,
        val pageDao: FakePageDao,
    )

    @Test
    fun `a completed stroke is persisted AND enqueued for sync in one go`() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(backgroundScope)
        f.ingestor.onDot(dot(PenDot.Phase.DOWN, 0f))
        f.ingestor.onDot(dot(PenDot.Phase.MOVE, 1f))
        f.ingestor.onDot(dot(PenDot.Phase.MOVE, 2f))
        f.ingestor.onDot(dot(PenDot.Phase.UP, 3f))
        advanceUntilIdle()

        // Source-of-truth invariant: 1 stroke stored == 1 stroke queued for upload.
        assertThat(f.strokeDao.byId).hasSize(1)
        assertThat(f.outboxDao.rows).hasSize(1)
        val stroke = f.strokeDao.byId.values.first()
        assertThat(stroke.syncState).isEqualTo(SyncState.PENDING)
        assertThat(f.outboxDao.rows.keys).containsExactly(stroke.uuid)
        // Pending scratch is cleared once committed.
        assertThat(f.pendingDao.rows).isEmpty()
        // The page was auto-created (organization happened with no user action).
        assertThat(f.pageDao.byId).hasSize(1)
    }

    @Test fun `a real stroke with an invalid Ncode address creates no stroke or page`() = runTest {
        val f = fixture(backgroundScope)
        // A genuine stroke (spread out, not a tap) but book -1 = the pen hadn't read a page id yet.
        fun bad(phase: PenDot.Phase, x: Float) = PenDot(
            section = 3, owner = 27, book = -1, page = -1,
            x = x, y = x, pressure = 0.5f, phase = phase, timestamp = clock++, color = 0xFF000000.toInt(),
        )
        f.ingestor.onDot(bad(PenDot.Phase.DOWN, 0f))
        f.ingestor.onDot(bad(PenDot.Phase.MOVE, 10f))
        f.ingestor.onDot(bad(PenDot.Phase.UP, 20f))
        advanceUntilIdle()
        // Must not become a phantom "Notebook -1" page; real (positive-book) writing is unaffected.
        assertThat(f.strokeDao.byId).isEmpty()
        assertThat(f.pageDao.byId).isEmpty()
    }

    @Test
    fun `dots are flushed to pending scratch mid-stroke so a crash loses nothing`() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(backgroundScope)
        f.ingestor.onDot(dot(PenDot.Phase.DOWN, 0f))   // flushes immediately
        f.ingestor.onDot(dot(PenDot.Phase.MOVE, 1f))
        f.ingestor.onDot(dot(PenDot.Phase.MOVE, 2f))   // hits flushEveryNDots=2 → flush
        advanceUntilIdle()

        // No PEN_UP yet, but dots are already durable in pending_dots.
        assertThat(f.pendingDao.rows.size).isAtLeast(2)
        assertThat(f.strokeDao.byId).isEmpty()
    }

    @Test
    fun `the wake-up tap navigates (ensures + touches the page) but leaves no ink`() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(backgroundScope)
        // A bare isolated tap: down + up at essentially one spot, the first mark on the page. This is
        // the user's "tap a page to open it live" gesture — it must surface the page (ensurePage bumps
        // lastInkAt, which the auto-open gate watches) while committing NO stroke.
        f.ingestor.onDot(dot(PenDot.Phase.DOWN, 10f))
        f.ingestor.onDot(dot(PenDot.Phase.UP, 10.4f))
        advanceUntilIdle()

        // No phantom stroke and nothing queued for sync — but the tapped page now exists (navigation).
        assertThat(f.strokeDao.byId).isEmpty()
        assertThat(f.outboxDao.rows).isEmpty()
        assertThat(f.pageDao.byId).hasSize(1)
        // The press's crash-scratch dot was cleared, so a restart can't resurrect it as a stroke.
        assertThat(f.pendingDao.rows).isEmpty()
        f.ingestor.recover()
        advanceUntilIdle()
        assertThat(f.strokeDao.byId).isEmpty()
    }

    @Test
    fun `a period right after writing is kept, not mistaken for a wake tap`() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(backgroundScope)
        // Real writing first (a line — spread exceeds the tap threshold).
        f.ingestor.onDot(dot(PenDot.Phase.DOWN, 0f))
        f.ingestor.onDot(dot(PenDot.Phase.MOVE, 2f))
        f.ingestor.onDot(dot(PenDot.Phase.UP, 4f))
        // Then a tiny tap a few ms later — a real period, NOT isolated, so it must survive.
        f.ingestor.onDot(dot(PenDot.Phase.DOWN, 6f))
        f.ingestor.onDot(dot(PenDot.Phase.UP, 6.3f))
        advanceUntilIdle()

        assertThat(f.strokeDao.byId).hasSize(2)
    }

    @Test
    fun `a small but real isolated mark (wider than the wake threshold) is kept`() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(backgroundScope)
        // First mark on the page, so "isolated", but it travels ~1.5 units — wider than WAKE_EPS,
        // so it's a genuine tiny stroke (a short diagonal), not the pen's near-zero wake tap.
        f.ingestor.onDot(dot(PenDot.Phase.DOWN, 10f))
        f.ingestor.onDot(dot(PenDot.Phase.UP, 11.5f))
        advanceUntilIdle()

        assertThat(f.strokeDao.byId).hasSize(1)
    }

    @Test
    fun `a tap after a long idle (the pen slept) is dropped as a re-wake`() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(backgroundScope)
        f.ingestor.onDot(dot(PenDot.Phase.DOWN, 0f))
        f.ingestor.onDot(dot(PenDot.Phase.UP, 4f))   // real writing → 1 stroke
        advanceUntilIdle()
        assertThat(f.strokeDao.byId).hasSize(1)

        clock += 120_000L                            // pen sleeps for two minutes
        f.ingestor.onDot(dot(PenDot.Phase.DOWN, 20f))
        f.ingestor.onDot(dot(PenDot.Phase.UP, 20.4f)) // wake tap on resume → dropped
        advanceUntilIdle()

        assertThat(f.strokeDao.byId).hasSize(1)       // still just the one real stroke
    }

    @Test
    fun `a tap on an action zone fires the zone, never ink, even as the first mark`() = runTest(UnconfinedTestDispatcher()) {
        val stroke = FakeStrokeDao(); val outbox = FakeOutboxDao(); val pending = FakePendingDotDao()
        val pageDao = FakePageDao(); val nb = FakeNotebookDao(); val ingest = FakeIngestDao(stroke, outbox, pending)
        val organizer = AutoOrganizer(nb, pageDao, now = { clock }, newId = { "id-${ids.incrementAndGet()}" })
        var fired: com.nibhaus.zones.ZoneAction? = null
        val zone = com.nibhaus.zones.ActionZone("z", com.nibhaus.zones.ZoneAction.SHARE_PNG, 9f, 9f, 12f, 12f)
        val ingestor = StrokeIngestor(
            ingestDao = ingest, pendingDao = pending, pageDao = pageDao, organizer = organizer,
            scope = backgroundScope, now = { clock }, newId = { "s-${ids.incrementAndGet()}" },
            actionZones = { listOf(zone) },
            onZoneTap = { z, _, _ -> fired = z.action },
        )
        // First mark on the page is a tap inside the zone: the zone wins over both ink and the wake-drop.
        ingestor.onDot(dot(PenDot.Phase.DOWN, 10f))
        ingestor.onDot(dot(PenDot.Phase.UP, 10.4f))
        advanceUntilIdle()

        assertThat(fired).isEqualTo(com.nibhaus.zones.ZoneAction.SHARE_PNG)
        assertThat(stroke.byId).isEmpty()    // no ink left
        assertThat(pending.rows).isEmpty()   // crash-scratch cleared
    }

    @Test
    fun `recover() promotes an interrupted stroke into a real stroke on next launch`() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(backgroundScope)
        f.ingestor.onDot(dot(PenDot.Phase.DOWN, 0f))
        f.ingestor.onDot(dot(PenDot.Phase.MOVE, 1f))
        f.ingestor.onDot(dot(PenDot.Phase.MOVE, 2f))   // flushed to pending, then "crash" (no UP)
        advanceUntilIdle()
        assertThat(f.strokeDao.byId).isEmpty()

        // Simulate next app launch.
        f.ingestor.recover()
        advanceUntilIdle()

        assertThat(f.strokeDao.byId).hasSize(1)
        assertThat(f.outboxDao.rows).hasSize(1)
        assertThat(f.pendingDao.rows).isEmpty()
    }

    @Test
    fun `a reconnect DOWN on an already-open page clears the orphaned generation so recover() never splices two strokes`() =
        runTest(UnconfinedTestDispatcher()) {
            val f = fixture(backgroundScope)
            // Generation A: pen goes down and writes, then BLE drops mid-stroke — no PEN_UP ever arrives.
            f.ingestor.onDot(dot(PenDot.Phase.DOWN, 0f))  // flushes immediately (seq 0)
            f.ingestor.onDot(dot(PenDot.Phase.MOVE, 1f))
            f.ingestor.onDot(dot(PenDot.Phase.MOVE, 2f))  // flushEveryNDots=2 -> flush (seq 1, 2)
            advanceUntilIdle()
            assertThat(f.pendingDao.rows).hasSize(3)

            // Reconnect: the user resumes writing on the SAME page. Generation B starts with a fresh DOWN.
            f.ingestor.onDot(dot(PenDot.Phase.DOWN, 10f))
            f.ingestor.onDot(dot(PenDot.Phase.MOVE, 11f))
            f.ingestor.onDot(dot(PenDot.Phase.MOVE, 12f))
            advanceUntilIdle()

            // Generation B then also crashes before its own PEN_UP — simulate a restart.
            f.ingestor.recover()
            advanceUntilIdle()

            // Exactly one recovered stroke, holding ONLY generation B's points. Generation A's stale
            // crash-scratch must be cleared the moment generation B's DOWN arrives — otherwise recover()
            // would zig-zag between both generations' coordinates under colliding seq numbers.
            assertThat(f.strokeDao.byId).hasSize(1)
            val points = Json.decodeFromString(ListSerializer(Point.serializer()), f.strokeDao.byId.values.first().pointsJson)
            assertThat(points.map { it.x }).containsExactly(10f, 11f, 12f).inOrder()
        }

    @Test
    fun `onCommitted reports the committed page id`() = runTest(UnconfinedTestDispatcher()) {
        val stroke = FakeStrokeDao(); val outbox = FakeOutboxDao()
        val pending = FakePendingDotDao(); val pageDao = FakePageDao(); val nb = FakeNotebookDao()
        val ingest = FakeIngestDao(stroke, outbox, pending)
        val organizer = AutoOrganizer(nb, pageDao, now = { clock }, newId = { "id-${ids.incrementAndGet()}" })
        var committed: String? = null
        val ingestor = StrokeIngestor(
            ingestDao = ingest, pendingDao = pending, pageDao = pageDao, organizer = organizer,
            scope = backgroundScope, flushEveryNDots = 2, now = { clock }, newId = { "s-${ids.incrementAndGet()}" },
            onCommitted = { committed = it },
        )

        ingestor.onDot(dot(PenDot.Phase.DOWN, 0f))
        ingestor.onDot(dot(PenDot.Phase.MOVE, 2f))
        ingestor.onDot(dot(PenDot.Phase.UP, 4f))
        advanceUntilIdle()

        assertThat(committed).isEqualTo(pageDao.byId.values.first().id)
    }

    @Test fun `overload backpressures producer and eventually persists every dot`() {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val inserts = AtomicInteger()
        val pending = object : com.nibhaus.data.PendingDotDao {
            override suspend fun insertAll(dots: List<com.nibhaus.data.PendingDotEntity>) {
                entered.complete(Unit)
                gate.await()
                inserts.addAndGet(dots.size)
            }
            override suspend fun forPage(pageKey: String) = emptyList<com.nibhaus.data.PendingDotEntity>()
            override suspend fun pageKeysWithPending() = emptyList<String>()
            override suspend fun clearPage(pageKey: String) = Unit
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val stroke = FakeStrokeDao(); val outbox = FakeOutboxDao(); val page = FakePageDao()
        val ingest = object : com.nibhaus.data.IngestDao() {
            override suspend fun insertStroke(stroke: com.nibhaus.data.StrokeEntity) = Unit
            override suspend fun enqueueOutbox(entry: com.nibhaus.data.OutboxEntry) = Unit
            override suspend fun clearPending(pageKey: String) = Unit
        }
        val ingestor = StrokeIngestor(
            ingest, pending, page,
            AutoOrganizer(FakeNotebookDao(), page), scope,
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            val producer = executor.submit {
                repeat(1_100) { ingestor.onDot(dot(PenDot.Phase.DOWN, it.toFloat())) }
            }
            runBlocking { entered.await() }
            Thread.sleep(50)
            assertThat(producer.isDone).isFalse()
            gate.complete(Unit)
            producer.get(5, TimeUnit.SECONDS)
            runBlocking {
                while (inserts.get() < 1_100) kotlinx.coroutines.delay(1)
            }
            assertThat(inserts.get()).isEqualTo(1_100)
        } finally {
            scope.cancel()
            executor.shutdownNow()
        }
    }

    @Test fun `enqueue after ingest cancellation fails explicitly`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val f = fixture(scope)
        scope.cancel()
        runBlocking { kotlinx.coroutines.yield() }

        val failure = runCatching { f.ingestor.onDot(dot(PenDot.Phase.DOWN, 0f)) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
    }
}
