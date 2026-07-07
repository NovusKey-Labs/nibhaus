package com.nibhaus.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Refreshes [NibhausGlanceWidget]'s content off the UI thread — a WorkManager periodic floor
 * (30min, the platform's own minimum period) plus an on-demand poke wired into the ingest funnel
 * ([com.nibhaus.di.ServiceLocator]'s `onCommitted`) so a freshly-written page shows up without
 * waiting out the period. Mirrors [com.nibhaus.export.ExportWorker]'s coalesced-enqueue shape.
 */
class WidgetUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        NibhausGlanceWidget().updateAll(applicationContext)
        return Result.success()
    }

    companion object {
        internal const val PERIODIC_UNIQUE = "widget_update_periodic"
        private const val POKE_UNIQUE = "widget_update_poke"

        /** Guarantee the periodic refresh is scheduled — called from [NibhausWidgetReceiver.onEnabled]. */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<WidgetUpdateWorker>(30, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_UNIQUE, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** On-demand refresh (a page's ink changed) — coalesced the same way ExportWorker's enqueue is. */
        fun poke(context: Context) {
            val request = OneTimeWorkRequestBuilder<WidgetUpdateWorker>().build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(POKE_UNIQUE, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
