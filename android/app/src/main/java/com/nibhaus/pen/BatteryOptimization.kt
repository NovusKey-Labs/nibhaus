package com.nibhaus.pen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Helpers for the battery-optimization exemption the user must grant so OneUI (an Android 14+ phone /
 * an Android tablet) doesn't sleep the pen foreground service. We deliberately open the app's own
 * App-info page (no special permission, Play-safe) rather than firing ACTION_REQUEST_IGNORE_BATTERY_
 * OPTIMIZATIONS, a Play-restricted permission. Surfaced in Settings + the connect-time nudge.
 */
object BatteryOptimization {

    fun isIgnoring(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Opens Nibhaus's own App-info page (where the user sets Battery → Unrestricted / Allow
     * background) so they land on the app, not the full battery-optimization list. Falls back to the
     * general list on the rare device without an app-details screen.
     */
    fun openSettings(context: Context) {
        val appInfo = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(appInfo) }.onFailure {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
