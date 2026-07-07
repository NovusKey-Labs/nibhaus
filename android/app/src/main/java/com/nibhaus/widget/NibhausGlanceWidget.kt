package com.nibhaus.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.nibhaus.di.ServiceLocator
import com.nibhaus.pen.PenConnState
import com.nibhaus.ui.MainActivity
import kotlinx.coroutines.flow.first

/** Intent-extra key [NibhausGlanceWidget]'s tap action stamps the target page id under — read back
 *  by [MainActivity]'s widget deep-link handling. */
const val WIDGET_PAGE_ID_EXTRA = "widget_page_id"

/**
 * Home-screen widget (#13): the latest page's notebook + page number + "last written" time, and a
 * pen-status dot. Tapping it opens the app straight to that page.
 *
 * Deliberately reads [ServiceLocator.penManager]'s in-memory [PenConnState] rather than binding
 * [com.nibhaus.pen.PenForegroundService] — that's a real bindable/startable Service (owns the live
 * BLE connection); the widget only ever wants to know "was the pen connected last time this process
 * observed it", which the already-running app singleton's `StateFlow.value` gives for free. If the
 * process is dead (rare — the foreground service normally keeps it alive while a pen is in use),
 * [ServiceLocator.from] spins up a fresh instance whose pen state defaults to disconnected, which is
 * exactly the right "last known state" in that case too.
 */
class NibhausGlanceWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val sl = ServiceLocator.from(context)
        val page = sl.db.pageDao().observeLatest().first()
        val notebookName = page?.let { sl.db.notebookDao().byId(it.notebookId)?.title } ?: ""
        val connected = sl.penManager.state.value is PenConnState.Connected
        // Built here (a real Context, not a Composable one) and handed down — glance-appwidget 1.1.1's
        // actionStartActivity only takes an Intent, not a Class<Activity>/reified type.
        val tapIntent = page?.let {
            Intent(context, MainActivity::class.java).putExtra(WIDGET_PAGE_ID_EXTRA, it.id)
        }

        provideContent {
            WidgetContent(
                notebookName = notebookName,
                pageNumber = page?.page,
                tapIntent = tapIntent,
                lastInkAt = page?.lastInkAt,
                penConnected = connected,
            )
        }
    }
}

@Composable
private fun WidgetContent(
    notebookName: String,
    pageNumber: Int?,
    tapIntent: Intent?,
    lastInkAt: Long?,
    penConnected: Boolean,
) {
    val tapAction = tapIntent?.let {
        GlanceModifier.clickable(actionStartActivity(it))
    } ?: GlanceModifier
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(16.dp)
            .padding(14.dp)
            .then(tapAction),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PenStatusDot(connected = penConnected)
            Spacer(GlanceModifier.width(6.dp))
            Text(
                "Nibhaus",
                style = TextStyle(
                    fontWeight = FontWeight.Medium,
                    color = GlanceTheme.colors.onSurfaceVariant,
                ),
            )
        }
        Spacer(GlanceModifier.size(8.dp))
        if (pageNumber != null) {
            Text(
                widgetPageLabel(notebookName, pageNumber),
                maxLines = 1,
                style = TextStyle(fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface),
            )
            if (lastInkAt != null) {
                Text(
                    relativeTimeLabel(System.currentTimeMillis(), lastInkAt),
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
                )
            }
        } else {
            Text(
                "No pages yet. Write your first one.",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
            )
        }
    }
}

@Composable
private fun PenStatusDot(connected: Boolean) {
    val color = if (connected) ColorProvider(androidx.compose.ui.graphics.Color(0xFF22E07A)) else GlanceTheme.colors.outline
    Box(
        modifier = GlanceModifier.size(8.dp).cornerRadius(4.dp).background(color),
        content = {},
    )
}
