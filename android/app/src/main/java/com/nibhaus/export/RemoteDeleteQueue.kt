package com.nibhaus.export

import com.nibhaus.data.PendingRemoteDelete
import com.nibhaus.data.PendingRemoteDeleteDao
import kotlinx.coroutines.CancellationException

/**
 * Durable "pending remote delete" queue — the delete-side counterpart of [ExportEngine.exportPending]'s
 * outbox drain. `deletePageNow`'s `alsoRemote` path used to call [ExportEngine.deleteRemote] once,
 * inline, so a momentarily unreachable sync target silently stranded the remote copy of a deleted page
 * forever. [enqueue] instead persists the page's precomputed export base path (Room-backed, survives
 * app restarts); [drain] is called from the same worker cadence as exports and retries with backoff
 * until the target confirms — 2xx or 404 (already gone) both count as done. [StorageProvider.delete]
 * already encodes that contract (see [TailscalePushProvider]): it returns normally on success/404 and
 * throws on anything else (a broken connection, a timeout, or an old server returning 501/405 for an
 * unimplemented DELETE) — so every non-success here is uniformly "retry later", never a permanent
 * failure a row could get stuck as.
 */
class RemoteDeleteQueue(
    private val dao: PendingRemoteDeleteDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Persist [pageId]'s precomputed [basePath] so its remote artifacts are removed even if the
     *  target is unreachable right now. Local deletion never waits on this — it's a plain DB insert,
     *  not a network call. */
    suspend fun enqueue(pageId: String, basePath: String) {
        dao.enqueue(PendingRemoteDelete(pageId, basePath, now()))
    }

    /**
     * Attempt every queued remote delete against [provider]. A page's whole set of artifacts is
     * retried together (deleting an already-gone file is a no-op, so redoing already-succeeded
     * extensions next time is harmless) — if any extension throws, the page stays queued.
     *
     * @return true if the queue fully drained (or was already empty); false → at least one entry
     *   needs a retry (the caller — [ExportWorker] — returns `Result.retry()` so WorkManager backs off
     *   and tries again, mirroring [ExportEngine.exportPending]).
     */
    suspend fun drain(provider: StorageProvider): Boolean {
        val pending = dao.peek(Int.MAX_VALUE)
        if (pending.isEmpty()) return true

        var allOk = true
        for (entry in pending) {
            val ok = runCatching { deleteArtifacts(entry.basePath, provider) }
                .onFailure { if (it is CancellationException) throw it }.isSuccess
            if (ok) {
                dao.remove(entry.pageId)
            } else {
                dao.bumpAttempts(entry.pageId)
                allOk = false
            }
        }
        return allOk
    }

    private suspend fun deleteArtifacts(basePath: String, provider: StorageProvider) {
        ExportEngine.REMOTE_ARTIFACT_EXTENSIONS.forEach { ext -> provider.delete("$basePath.$ext") }
    }
}
