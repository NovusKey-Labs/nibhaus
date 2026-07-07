package com.nibhaus.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.work.WorkManager

/**
 * Home-screen widget (#13) entry point. [GlanceAppWidgetReceiver] already handles APPWIDGET_UPDATE
 * (dispatches into [NibhausGlanceWidget.provideGlance]) — this only adds the WorkManager periodic
 * refresh's lifecycle: scheduled once a widget instance actually exists, cancelled once the last one
 * is removed, so a user who never adds the widget never pays for the periodic wake-up.
 */
class NibhausWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = NibhausGlanceWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetUpdateWorker.schedulePeriodic(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WorkManager.getInstance(context).cancelUniqueWork(WidgetUpdateWorker.PERIODIC_UNIQUE)
    }
}
