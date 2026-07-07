package com.nibhaus.export

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nibhaus.di.ServiceLocator
import java.util.concurrent.TimeUnit

/**
 * Runs the export off the UI/capture path and survives process death (WorkManager). On failure it
 * returns retry → WorkManager re-runs it with exponential backoff, which is exactly the
 * "unreachable endpoint → queue + retry, never drop" behavior the brief asks for.
 */
class ExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sl = ServiceLocator.from(applicationContext)
        // Missing/!configured target (including a sync method entitlement now blocks, such as a
        // relock, or a Tailscale endpoint predating this check): nothing was drained, so this must
        // NOT report success; that would leave the outbox silently queued forever with WorkManager
        // believing the job is done. Retrying (with the class's normal backoff) keeps it honest and
        // self-heals the moment the target becomes resolvable again, same as a real drain failure below.
        val provider = sl.currentStorageProvider()
            ?: return exportWorkResult(providerAvailable = false, exported = false, deleted = false)
        // Both queues share this one worker's cadence/backoff (a durable delete queue mirroring the
        // durable export outbox — see RemoteDeleteQueue's kdoc). Run both even if the first needs a
        // retry, so one backlog stalling the other never happens.
        // Drain remote deletes BEFORE exporting: a page deleted-with-remote then re-inked on the SAME
        // Ncode paper produces a fresh PageEntity sharing the old export basePath. Exporting first would
        // write the new artifacts and then delete them at that shared basePath — silent remote data
        // loss. Delete-then-export makes the collision self-heal in one cycle (stale gone, fresh rewritten).
        val deleted = sl.remoteDeleteQueue.drain(provider)
        val exported = sl.exportEngine.exportPending(provider)
        return exportWorkResult(providerAvailable = true, exported = exported, deleted = deleted)
    }

    companion object {
        private const val UNIQUE = "export"

        /**
         * Enqueue a drain. Coalesced so a burst of strokes (perf audit P1-2: a 200-stroke burst
         * under the old `APPEND_OR_REPLACE` chained up to 200 runs — each commit's request queued
         * up *behind* the currently-running one instead of merging with it) doesn't spawn redundant
         * runs.
         *
         * Uses `REPLACE`, not `KEEP`: [com.nibhaus.export.ExportEngine.exportPending] peeks and
         * drains the WHOLE outbox in one call, so in the common case the currently
         * enqueued-or-running work already covers a fresh commit and a `KEEP` would be enough. But
         * a commit that lands *after* a running drain's peek — mid-run — would be invisible to that
         * run; under `KEEP` its enqueue() call is then silently dropped (work already "exists"), so
         * that stroke would sit in the outbox until some unrelated future commit happens to trigger
         * another drain. `REPLACE` closes that gap: it cancels the in-flight run (if any) and
         * schedules a fresh one, which re-peeks the outbox from scratch and always picks up the
         * latest commit. A cancelled mid-run drain isn't lossy — the outbox row for any page it
         * hadn't gotten to yet is never removed (it's only removed after that page's write
         * succeeds), and `exportPage` is idempotent (content-hash short-circuit), so the replacement
         * run redoing an already-written page is a cheap no-op, not a correctness risk.
         *
         * Durability comes from the outbox table (rows survive process death, removed only after a
         * successful write), not from this scheduling policy — the policy only controls how many
         * WorkManager executions a burst produces, not whether any stroke's export is guaranteed.
         */
        fun enqueue(context: Context) {
            val builder = OneTimeWorkRequestBuilder<ExportWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            // Network-target sync (Tailscale push): only run when connected, so a long offline backlog
            // drains the moment connectivity returns instead of waiting out the backoff — and we don't
            // burn retries/battery offline. Local-folder/-only exports stay unconstrained.
            if (ServiceLocator.from(context).syncNeedsNetwork) {
                builder.setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
            }
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.REPLACE, builder.build())
        }
    }
}

/**
 * Pure decision behind [ExportWorker.doWork]'s return value (final-review fix, 2026-07-05): a run
 * that never got a usable provider drained nothing, so it must never report success. Only a real
 * success (provider resolved AND both queues drained clean) does. Framework-free so it's directly
 * unit-testable without constructing a real CoroutineWorker.
 */
internal fun exportWorkResult(providerAvailable: Boolean, exported: Boolean, deleted: Boolean): androidx.work.ListenableWorker.Result =
    if (providerAvailable && exported && deleted) androidx.work.ListenableWorker.Result.success() else androidx.work.ListenableWorker.Result.retry()
