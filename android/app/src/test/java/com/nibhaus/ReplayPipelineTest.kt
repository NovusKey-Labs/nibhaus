package com.nibhaus

import com.google.common.truth.Truth.assertThat
import com.nibhaus.ingest.StrokeIngestor
import com.nibhaus.organize.AutoOrganizer
import com.nibhaus.pen.PenDot
import com.nibhaus.pen.replay.ReplayPenSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Drives the FULL capture pipeline from a recorded session fixture — the replay harness.
 * Once a real `.jsonl` capture from the M1+/LAMY is dropped into resources/replay/, this same
 * test proves the pipeline handles real pen data (no lost strokes, correct auto-filing) with no
 * hardware. Today it runs the synthetic sample; swap the resource name to validate real data.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReplayPipelineTest {

    private val ids = AtomicInteger(0)

    private fun readFixture(name: String): String =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream(name)) { "missing fixture: $name" }
            .bufferedReader().use { it.readText() }

    @Test
    fun `replaying a recorded session reproduces every stroke with correct auto-filing`() = runTest(UnconfinedTestDispatcher()) {
        val jsonl = readFixture("replay/sample_session.jsonl")
        val dots = ReplayPenSource().parse(jsonl)

        // Sanity on the parse itself.
        val expectedStrokes = dots.count { it.phase == PenDot.Phase.UP }
        assertThat(expectedStrokes).isEqualTo(3)

        // Wire the real ingestion + organization against in-memory DAOs.
        val strokeDao = FakeStrokeDao(); val outbox = FakeOutboxDao()
        val pending = FakePendingDotDao(); val pageDao = FakePageDao(); val nb = FakeNotebookDao()
        val ingestDao = FakeIngestDao(strokeDao, outbox, pending)
        val organizer = AutoOrganizer(nb, pageDao, newId = { "id-${ids.incrementAndGet()}" })
        val ingestor = StrokeIngestor(
            ingestDao = ingestDao, pendingDao = pending, pageDao = pageDao, organizer = organizer,
            scope = backgroundScope, newId = { "s-${ids.incrementAndGet()}" },
        )

        // Replay the recorded dots through the exact live path.
        dots.forEach(ingestor::onDot)
        advanceUntilIdle()

        // No stroke lost: one persisted stroke per PEN_UP, each queued for export.
        assertThat(strokeDao.byId).hasSize(expectedStrokes)
        assertThat(outbox.rows).hasSize(expectedStrokes)
        assertThat(pending.rows).isEmpty()

        // Auto-filed by Ncode note id: notebooks 603 and 601, two pages total.
        assertThat(nb.byId.values.map { it.book }.toSet()).containsExactly(603, 601)
        assertThat(pageDao.byId).hasSize(2)
    }
}
