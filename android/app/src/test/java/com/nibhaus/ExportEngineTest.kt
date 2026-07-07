package com.nibhaus

import com.google.common.truth.Truth.assertThat
import com.nibhaus.data.NotebookEntity
import com.nibhaus.data.OutboxEntry
import com.nibhaus.data.PageEntity
import com.nibhaus.data.Point
import com.nibhaus.data.StrokeEntity
import com.nibhaus.data.SyncState
import com.nibhaus.export.ExportEngine
import com.nibhaus.export.NotebookType
import com.nibhaus.export.StorageProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.IOException

/** A target that records writes (and can be told to fail), so the export engine is tested for real. */
private class FakeStorageProvider(override val id: String = "fake") : StorageProvider {
    val writes = LinkedHashMap<String, ByteArray>()
    val deletes = mutableListOf<String>()
    var writeCount = 0
    var failNextWrites = 0
    override suspend fun write(name: String, bytes: ByteArray) {
        if (failNextWrites > 0) { failNextWrites--; throw IOException("unreachable") }
        writes[name] = bytes; writeCount++
    }
    override suspend fun delete(name: String) { deletes += name }
}

class ExportEngineTest {

    private val json = Json
    private val strokeDao = FakeStrokeDao()
    private val pageDao = FakePageDao()
    private val notebookDao = FakeNotebookDao()
    private val outbox = FakeOutboxDao()
    private val exportDao = FakeExportDao()

    private val engine = ExportEngine(
        strokeDao = strokeDao,
        pageDao = pageDao,
        notebookDao = notebookDao,
        outboxDao = outbox,
        exportDao = exportDao,
        decode = { json.decodeFromString(ListSerializer(Point.serializer()), it.pointsJson) },
        penId = { "AA:BB:CC" },
        now = { 1_000L },
    )

    private suspend fun seedStroke(uuid: String, pageId: String, points: List<Point>) {
        strokeDao.insert(
            StrokeEntity(
                uuid = uuid, pageId = pageId, color = 0xFF112233.toInt(),
                startedAt = 1, endedAt = 2,
                pointsJson = json.encodeToString(ListSerializer(Point.serializer()), points),
                syncState = SyncState.PENDING,
            ),
        )
        outbox.enqueue(OutboxEntry(uuid, enqueuedAt = 1))
    }

    private val pts = listOf(Point(0f, 0f, 1f, 0), Point(10f, 10f, 1f, 1))

    @Test fun `exports a pending page then drains the outbox`() = runTest {
        pageDao.insert(page("P1"))
        seedStroke("s1", "P1", pts)
        val provider = FakeStorageProvider()

        val ok = engine.exportPending(provider)

        assertThat(ok).isTrue()
        assertThat(provider.writes.keys).containsExactly("P1.svg", "P1.inkml", "P1.md", "P1.json", "P1.bak.json")
        assertThat(outbox.peek(10)).isEmpty()                       // drained
        assertThat(strokeDao.byId["s1"]!!.syncState).isEqualTo(SyncState.SYNCED)
    }

    @Test fun `decodes each stroke's points once and reuses them across svg, inkml and backup`() = runTest {
        pageDao.insert(page("P1"))
        seedStroke("s1", "P1", pts)
        var decodeCalls = 0
        val countingEngine = ExportEngine(
            strokeDao = strokeDao, pageDao = pageDao, notebookDao = notebookDao,
            outboxDao = outbox, exportDao = exportDao,
            decode = { decodeCalls++; json.decodeFromString(ListSerializer(Point.serializer()), it.pointsJson) },
            penId = { "AA:BB:CC" }, now = { 1_000L },
        )
        val provider = FakeStorageProvider()

        countingEngine.exportPending(provider)

        // One stroke → decode called once, not once per format (svg + inkml + backup would be 3).
        assertThat(decodeCalls).isEqualTo(1)
        // Round-trip: the hoisted/reused decode still produces a correct, restorable backup.
        val restored = com.nibhaus.export.decodeBackup(provider.writes["P1.bak.json"]!!.decodeToString())!!
        assertThat(restored.strokes.single().points).isEqualTo(pts)
    }

    @Test fun `re-draining an unchanged page does not write again`() = runTest {
        seedStroke("s1", "P1", pts)
        val provider = FakeStorageProvider()
        engine.exportPending(provider)
        val after1 = provider.writeCount

        // Same stroke re-enqueued (e.g. a redundant trigger): content + target unchanged → skip.
        outbox.enqueue(OutboxEntry("s1", enqueuedAt = 2))
        val ok = engine.exportPending(provider)

        assertThat(ok).isTrue()
        assertThat(provider.writeCount).isEqualTo(after1)           // no duplicate write
        assertThat(outbox.peek(10)).isEmpty()
    }

    @Test fun `new ink on a page re-exports it`() = runTest {
        seedStroke("s1", "P1", pts)
        val provider = FakeStorageProvider()
        engine.exportPending(provider)
        val after1 = provider.writeCount

        seedStroke("s2", "P1", listOf(Point(5f, 5f, 1f, 0), Point(6f, 7f, 1f, 1)))
        engine.exportPending(provider)

        assertThat(provider.writeCount).isEqualTo(after1 + 5)       // overwrote svg + inkml + md + json + bak.json
    }

    @Test fun `an imported transcript re-exports the page with the markdown note`() = runTest {
        pageDao.insert(page("P1"))
        seedStroke("s1", "P1", pts)
        val provider = FakeStorageProvider()
        engine.exportPending(provider)
        val after1 = provider.writeCount

        // OCR landed: same strokes, new transcript → content hash changes → re-export.
        pageDao.setTranscript("P1", "Buy milk and eggs")
        outbox.enqueue(OutboxEntry("s1", enqueuedAt = 2))
        engine.exportPending(provider)

        assertThat(provider.writeCount).isEqualTo(after1 + 5)        // svg + inkml + md + json + bak.json rewritten
        assertThat(provider.writes["P1.md"]!!.decodeToString()).contains("Buy milk and eggs")
    }

    @Test fun `switching target re-exports so files are not stranded`() = runTest {
        seedStroke("s1", "P1", pts)
        engine.exportPending(FakeStorageProvider(id = "folderA"))

        outbox.enqueue(OutboxEntry("s1", enqueuedAt = 2))
        val folderB = FakeStorageProvider(id = "folderB")
        engine.exportPending(folderB)

        assertThat(folderB.writes.keys).containsExactly("P1.svg", "P1.inkml", "P1.md", "P1.json", "P1.bak.json")
    }

    @Test fun `a failed write keeps the stroke queued for retry`() = runTest {
        seedStroke("s1", "P1", pts)
        val provider = FakeStorageProvider().apply { failNextWrites = 1 }

        val ok = engine.exportPending(provider)

        assertThat(ok).isFalse()                                    // → WorkManager retries
        assertThat(outbox.peek(10).map { it.strokeUuid }).containsExactly("s1") // not lost
        assertThat(strokeDao.byId["s1"]!!.syncState).isEqualTo(SyncState.PENDING)

        // Retry with a healthy provider completes it.
        assertThat(engine.exportPending(provider)).isTrue()
        assertThat(outbox.peek(10)).isEmpty()
    }

    @Test fun `a registered notebook exports under its type-and-label path`() = runTest {
        notebookDao.insert(
            NotebookEntity(id = "nbW", book = 438, instanceSeq = 0, locked = false, title = "Work", createdAt = 0),
        )
        pageDao.insert(
            PageEntity(
                id = "P9", notebookId = "nbW", addressKey = "3.27.438.38",
                section = 3, owner = 27, book = 438, page = 38, firstSeenAt = 0, lastInkAt = 5,
            ),
        )
        seedStroke("s9", "P9", pts)
        val provider = FakeStorageProvider()

        engine.exportPending(provider)

        assertThat(provider.writes.keys).containsExactly(
            "pnb/Work/PNB_Work_Pg038.svg", "pnb/Work/PNB_Work_Pg038.inkml",
            "pnb/Work/PNB_Work_Pg038.md", "pnb/Work/PNB_Work_Pg038.json",
            "pnb/Work/PNB_Work_Pg038.bak.json",
        )
        // The note embeds the co-located image by filename (no folder), which Obsidian resolves.
        assertThat(provider.writes["pnb/Work/PNB_Work_Pg038.md"]!!.decodeToString())
            .contains("![[PNB_Work_Pg038.png]]")
    }

    @Test fun `an injected type resolver drives the export path`() = runTest {
        // Mirrors a DataStore assignment: book 700 -> the 2026 Planner. Its layout is empty, so the
        // planner files by label (plnr/School/…) rather than under date folders — exactly the
        // until-measured behaviour.
        val planEngine = ExportEngine(
            strokeDao = strokeDao, pageDao = pageDao, notebookDao = notebookDao,
            outboxDao = outbox, exportDao = exportDao,
            decode = { json.decodeFromString(ListSerializer(Point.serializer()), it.pointsJson) },
            penId = { "AA:BB:CC" }, now = { 1_000L },
            typeForBook = { book -> if (book == 700) NotebookType.PLANNER_2026 else NotebookType.forBook(book) },
        )
        notebookDao.insert(
            NotebookEntity(id = "nbS", book = 700, instanceSeq = 0, locked = false, title = "School", createdAt = 0),
        )
        pageDao.insert(
            PageEntity(
                id = "P7", notebookId = "nbS", addressKey = "3.27.700.12",
                section = 3, owner = 27, book = 700, page = 12, firstSeenAt = 0, lastInkAt = 5,
            ),
        )
        seedStroke("s7", "P7", pts)
        val provider = FakeStorageProvider()

        planEngine.exportPending(provider)

        assertThat(provider.writes.keys).containsExactly(
            "plnr/School/PLNR_School_Pg012.svg", "plnr/School/PLNR_School_Pg012.inkml",
            "plnr/School/PLNR_School_Pg012.md", "plnr/School/PLNR_School_Pg012.json",
            "plnr/School/PLNR_School_Pg012.bak.json",
        )
    }

    @Test fun `deleteRemote removes every artifact for the page at its export path`() = runTest {
        notebookDao.insert(
            NotebookEntity(id = "nbW", book = 438, instanceSeq = 0, locked = false, title = "Work", createdAt = 0),
        )
        pageDao.insert(
            PageEntity(
                id = "P9", notebookId = "nbW", addressKey = "3.27.438.38",
                section = 3, owner = 27, book = 438, page = 38, firstSeenAt = 0, lastInkAt = 5,
            ),
        )
        seedStroke("s9", "P9", pts)
        val provider = FakeStorageProvider()
        engine.exportPending(provider) // establishes the pnb/Work/PNB_Work_Pg038.* path

        engine.deleteRemote("P9", provider)

        // Best-effort removal of the same base path the export wrote — every artifact extension,
        // including the raster (png/pdf) and the watcher-written .txt transcript.
        assertThat(provider.deletes).containsExactly(
            "pnb/Work/PNB_Work_Pg038.svg", "pnb/Work/PNB_Work_Pg038.inkml",
            "pnb/Work/PNB_Work_Pg038.png", "pnb/Work/PNB_Work_Pg038.pdf",
            "pnb/Work/PNB_Work_Pg038.md", "pnb/Work/PNB_Work_Pg038.json",
            "pnb/Work/PNB_Work_Pg038.bak.json", "pnb/Work/PNB_Work_Pg038.txt",
        ).inOrder()
    }

    @Test fun `remoteBasePath returns the same base path deleteRemote computes`() = runTest {
        notebookDao.insert(
            NotebookEntity(id = "nbW", book = 438, instanceSeq = 0, locked = false, title = "Work", createdAt = 0),
        )
        pageDao.insert(
            PageEntity(
                id = "P9", notebookId = "nbW", addressKey = "3.27.438.38",
                section = 3, owner = 27, book = 438, page = 38, firstSeenAt = 0, lastInkAt = 5,
            ),
        )

        // Captured BEFORE any local delete — this is what RemoteDeleteQueue.enqueue persists, since by
        // drain time the page row (and this computation's inputs) may already be gone.
        assertThat(engine.remoteBasePath("P9")).isEqualTo("pnb/Work/PNB_Work_Pg038")
    }

    @Test fun `remoteBasePath is null for a page that does not exist`() = runTest {
        assertThat(engine.remoteBasePath("ghost")).isNull()
    }

    private fun page(id: String) = PageEntity(
        id = id, notebookId = "nb", addressKey = "3.27.603.1",
        section = 3, owner = 27, book = 603, page = 1, firstSeenAt = 0, lastInkAt = 5,
    )
}
