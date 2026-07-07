package com.nibhaus

import com.google.common.truth.Truth.assertThat
import com.nibhaus.ingest.OfflineSync
import com.nibhaus.organize.AutoOrganizer
import com.nibhaus.pen.FakeNeoPenSdk
import com.nibhaus.pen.OfflineBatch
import com.nibhaus.pen.OfflinePoint
import com.nibhaus.pen.OfflineStroke
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineSyncTest {

    private val ids = AtomicInteger(0)

    private class Fix(
        val sdk: FakeNeoPenSdk,
        val sync: OfflineSync,
        val strokeDao: FakeStrokeDao,
        val outboxDao: FakeOutboxDao,
        val pageDao: FakePageDao,
    )

    private fun fixture(scope: kotlinx.coroutines.CoroutineScope): Fix {
        val stroke = FakeStrokeDao(); val outbox = FakeOutboxDao(); val pending = FakePendingDotDao()
        val page = FakePageDao(); val nb = FakeNotebookDao()
        val ingest = FakeIngestDao(stroke, outbox, pending)
        val organizer = AutoOrganizer(nb, page, newId = { "id-${ids.incrementAndGet()}" })
        val sdk = FakeNeoPenSdk()
        return Fix(sdk, OfflineSync(sdk, organizer, ingest, scope), stroke, outbox, page)
    }

    private fun stroke(note: Int, page: Int, startT: Long) = OfflineStroke(
        section = 3, owner = 27, note = note, page = page, color = 0xFF000000.toInt(),
        points = listOf(
            OfflinePoint(1f, 1f, 0.5f, startT),
            OfflinePoint(2f, 2f, 0.6f, startT + 10),
            OfflinePoint(3f, 3f, 0.4f, startT + 20),
        ),
    )

    @Test
    fun `ingesting stored strokes persists every one and queues each for export`() = runTest {
        val f = fixture(backgroundScope)
        val batch = OfflineBatch("pen", listOf(stroke(603, 1, 1000), stroke(603, 2, 2000), stroke(601, 1, 3000)))

        val received = f.sync.ingest(batch)

        assertThat(received).isEqualTo(3)
        assertThat(f.strokeDao.byId).hasSize(3)   // no page loss
        assertThat(f.outboxDao.rows).hasSize(3)   // each queued for export
        assertThat(f.pageDao.byId).hasSize(3)     // auto-filed into 2 notebooks, 3 pages
    }

    @Test
    fun `re-requesting offline data is idempotent - no duplicates`() = runTest {
        val f = fixture(backgroundScope)
        val batch = OfflineBatch("pen", listOf(stroke(603, 1, 1000), stroke(603, 1, 2000)))

        f.sync.ingest(batch)
        f.sync.ingest(batch) // pen re-sends the same data (e.g. after a dropped connection)

        assertThat(f.strokeDao.byId).hasSize(2) // still 2, not 4
        assertThat(f.outboxDao.rows).hasSize(2)
    }

    @Test
    fun `an interrupted download resumes without double-counting`() = runTest {
        val f = fixture(backgroundScope)
        val s1 = stroke(603, 1, 1000); val s2 = stroke(603, 2, 2000); val s3 = stroke(603, 3, 3000)

        f.sync.ingest(OfflineBatch("pen", listOf(s1, s2)))        // first attempt drops after 2
        f.sync.ingest(OfflineBatch("pen", listOf(s1, s2, s3)))    // retry re-sends all 3

        assertThat(f.strokeDao.byId).hasSize(3) // s1/s2 not duplicated; s3 added
    }

    @Test
    fun `requestAll drives the full pipeline from the pen's offline store`() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(backgroundScope)
        f.sdk.offlineStore += listOf(stroke(603, 1, 1000), stroke(603, 2, 2000))

        f.sdk.emitConnected()
        f.sync.requestAll()
        advanceUntilIdle()

        assertThat(f.sdk.allowOfflineData).isTrue()
        assertThat(f.strokeDao.byId).hasSize(2)
    }
}
