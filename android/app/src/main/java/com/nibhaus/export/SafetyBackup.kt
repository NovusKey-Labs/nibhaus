package com.nibhaus.export

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * At-ingest durability. Writes one self-contained `<addressKey>.bak.json` per page into a dedicated,
 * always-on safety folder promptly after each change (new ink OR an edit), so a page survives an
 * app-data wipe even before the deferred export runs and regardless of the sync method.
 *
 * Debounced per page: a burst of quick changes coalesces into one write ~[debounceMs] after the last.
 * The flush re-reads the page's CURRENT state via [readPage], so it mirrors edits faithfully — writes
 * when strokes remain, deletes the file when the page was fully erased (so a restore can't resurrect
 * erased ink). Best-effort: a failed write is retried by the next change, never blocks or crashes the
 * caller. Android-light (only `android.util.Log` for a best-effort diagnostic) (folder resolution +
 * the DB read are injected) so it is unit-tested on the JVM.
 */
class SafetyBackup(
    /** The safety-folder target, or null when no folder is chosen (feature off). Read at flush time. */
    private val provider: suspend () -> StorageProvider?,
    /** The page's current address + strokes as a [PageBackup], or null if the page is gone. */
    private val readPage: suspend (pageId: String) -> PageBackup?,
    private val scope: CoroutineScope,
    private val debounceMs: Long = 2500L,
) {
    private val jobs = HashMap<String, Job>()

    /** Mark a page changed; (re)starts its debounce so rapid changes collapse to one flush. */
    fun onPageChanged(pageId: String) {
        synchronized(jobs) {
            jobs.remove(pageId)?.cancel()
            jobs[pageId] = scope.launch {
                delay(debounceMs)
                flush(pageId)
            }
        }
    }

    /** Cancel any pending backup flush for [pageId] — call before deleting the page's file so a
     *  debounce in flight can't rewrite (resurrect) it. */
    fun cancel(pageId: String) {
        synchronized(jobs) { jobs.remove(pageId)?.cancel() }
    }

    /** Mirror the page's current state into the safety folder. Best-effort; never throws. */
    suspend fun flush(pageId: String) {
        val target = provider() ?: return
        runCatching {
            val backup = readPage(pageId)
            if (backup == null || backup.strokes.isEmpty()) {
                if (backup != null) target.delete(fileName(backup))
            } else {
                target.write(fileName(backup), encodeBackup(backup).toByteArray())
            }
        }.onFailure { android.util.Log.w("NibhausBackup", "safety flush failed for $pageId", it) }
    }

    private fun fileName(b: PageBackup) = "${b.section}.${b.owner}.${b.book}.${b.page}.bak.json"
}
