package com.nibhaus.ocr

import com.nibhaus.data.PageDao
import com.nibhaus.export.ExportSidecar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Imports the OCR transcripts the NAS watcher writes back ([source] yields each `<base>.txt`) into the
 * page DB, so they become full-text searchable in-app. **Source-agnostic**: the transcripts may arrive
 * in a SAF folder a sync app fills ([SafTranscriptSource]) or be pulled straight from the NAS sync
 * endpoint over the tailnet ([TailnetTranscriptSource]) — the page-mapping is identical either way:
 *  - flat `<pageId>.txt` → the base *is* the page id (back-compat with the old naming);
 *  - human path (e.g. `pnb/Work/PNB_Work_Pg038.txt`) → read the page id from the sibling `.json` sidecar.
 *
 * Idempotent: a page whose transcript already matches the file is skipped.
 */
class TranscriptImporter(
    private val pageDao: PageDao,
    /** The active transcript backend, or null when sync isn't configured. */
    private val source: suspend () -> TranscriptSource?,
    /** Called after a page's transcript changes, so it can be re-queued for export (refreshes its .md). */
    private val onImported: suspend (String) -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** @return how many pages had their transcript updated. */
    suspend fun importPending(): Int = withContext(Dispatchers.IO) {
        val src = source() ?: return@withContext 0
        var updated = 0
        for (f in src.listTranscripts()) {
            if (!f.path.endsWith(".txt")) continue
            val leafBase = f.path.substringAfterLast('/').removeSuffix(".txt")
            val page = pageDao.byId(leafBase)
                ?: sidecarPageId(f)?.let { pageDao.byId(it) }
                ?: continue
            val text = f.read()?.decodeToString() ?: continue
            if (page.transcript != text) {
                pageDao.setTranscriptIndexed(page.id, text)
                onImported(page.id)
                updated++
            }
        }
        updated
    }

    /** Page id from the sibling `<base>.json` export sidecar, or null if absent/unparseable. */
    private suspend fun sidecarPageId(f: TranscriptFile): String? {
        val bytes = f.sidecar() ?: return null
        return runCatching {
            json.decodeFromString(ExportSidecar.serializer(), bytes.decodeToString()).pageId
        }.getOrNull()
    }
}
