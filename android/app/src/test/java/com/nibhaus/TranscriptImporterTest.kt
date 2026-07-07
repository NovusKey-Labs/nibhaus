package com.nibhaus

import com.google.common.truth.Truth.assertThat
import com.nibhaus.data.PageEntity
import com.nibhaus.export.ExportSidecar
import com.nibhaus.ocr.TranscriptFile
import com.nibhaus.ocr.TranscriptImporter
import com.nibhaus.ocr.TranscriptSource
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test

/** A scripted transcript backend — no SAF, no HTTP, no Android. */
private class FakeSource(private val files: List<TranscriptFile>) : TranscriptSource {
    override suspend fun listTranscripts() = files
}

private fun txtFile(path: String, text: String, sidecar: String? = null) = TranscriptFile(
    path = path,
    read = { text.encodeToByteArray() },
    sidecar = { sidecar?.encodeToByteArray() },
)

private fun sidecarJson(pageId: String): String = Json.encodeToString(
    ExportSidecar.serializer(),
    ExportSidecar(pageId, "ncode", "pen", 0L, 0, "hash", emptyList(), 0L),
)

class TranscriptImporterTest {
    private val dao = FakePageDao()
    private val imported = mutableListOf<String>()

    private fun importer(files: List<TranscriptFile>) = TranscriptImporter(
        dao,
        source = { FakeSource(files) },
        onImported = { imported.add(it) },
    )

    private fun page(id: String, transcript: String? = null) = PageEntity(
        id = id, notebookId = "nb", addressKey = "0.0.1.1",
        section = 0, owner = 0, book = 1, page = 1,
        firstSeenAt = 0L, lastInkAt = 0L, transcript = transcript,
    )

    @Test fun flatName_mapsByPageId_andImports() = runTest {
        dao.insert(page("P1"))
        assertThat(importer(listOf(txtFile("P1.txt", "hello world"))).importPending()).isEqualTo(1)
        assertThat(dao.byId("P1")?.transcript).isEqualTo("hello world")
        assertThat(imported).containsExactly("P1")
    }

    @Test fun humanPath_mapsViaSidecar() = runTest {
        dao.insert(page("P2"))
        val n = importer(
            listOf(txtFile("pnb/Work/PNB_Work_Pg038.txt", "from sidecar", sidecarJson("P2")))
        ).importPending()
        assertThat(n).isEqualTo(1)
        assertThat(dao.byId("P2")?.transcript).isEqualTo("from sidecar")
    }

    @Test fun unchangedTranscript_isSkipped() = runTest {
        dao.insert(page("P3", transcript = "same"))
        assertThat(importer(listOf(txtFile("P3.txt", "same"))).importPending()).isEqualTo(0)
        assertThat(imported).isEmpty()
    }

    @Test fun unknownPage_isIgnored() = runTest {
        assertThat(importer(listOf(txtFile("ghost.txt", "no page"))).importPending()).isEqualTo(0)
    }

    @Test fun noSource_returnsZero() = runTest {
        assertThat(TranscriptImporter(dao, source = { null }).importPending()).isEqualTo(0)
    }
}
