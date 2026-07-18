package com.nibhaus.ocr

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nibhaus.di.ServiceLocator
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * Periodically pulls the watcher-written transcripts back into the page DB — the inbound half of
 * native sync, so handwriting becomes searchable with no third-party sync app. [TranscriptImporter.importPending]
 * is idempotent + source-agnostic: in Tailscale-push mode it pulls from the NAS sync endpoint; in
 * local-folder mode it reads the synced folder; in local-only it no-ops.
 */
class SyncPullWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val sl = ServiceLocator.from(applicationContext)
        return runCatching { sl.transcriptImporter.importPending() }
            .onFailure { if (it is CancellationException) throw it }
            .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }

    companion object {
        private const val UNIQUE = "sync_pull"

        /** Schedule the recurring transcript pull (idempotent — safe to call on every app start). */
        fun schedule(context: Context) {
            // Transcripts only arrive over the network, so require connectivity: no wasted runs
            // offline, and it fires the moment connectivity returns.
            val request = PeriodicWorkRequestBuilder<SyncPullWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
