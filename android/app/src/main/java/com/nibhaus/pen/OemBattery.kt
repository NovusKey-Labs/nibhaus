package com.nibhaus.pen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * OEM autostart / battery-optimization deep link. Aggressive OEM battery managers (Samsung
 * "Sleeping apps", Xiaomi autostart, and similar) kill the pen's background BLE link even past a
 * plain Doze exemption; the most reliable ask is the direct system consent dialog
 * (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS — needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS in the
 * manifest, the Play-safe user-consent variant) rather than routing the user through Settings.
 * Falls back to the general ignore-list screen on the rare device that doesn't support the direct
 * dialog. Each candidate is resolveActivity-guarded so we never fire an Intent nothing can handle.
 */
object OemBattery {

    fun isIgnoring(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Best-effort settings/consent Intent for this device, or null if neither action resolves. */
    fun settingsIntent(context: Context): Intent? {
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        val candidates = mapOf(request.action to request, fallback.action to fallback)
        val chosen = chooseAction { action -> candidates.getValue(action).resolveActivity(context.packageManager) != null }
        return chosen?.let { candidates.getValue(it) }
    }

    /**
     * Pure intent-choice logic: prefers the direct "Allow" consent dialog, falls back to the
     * general ignore-list settings screen, and returns null if the device resolves neither.
     * [resolves] is injected (a fake in tests, `Intent#resolveActivity` in production) so this is
     * unit-testable without a real PackageManager.
     */
    internal fun chooseAction(resolves: (String) -> Boolean): String? = when {
        resolves(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) -> Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
        resolves(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) -> Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
        else -> null
    }
}
