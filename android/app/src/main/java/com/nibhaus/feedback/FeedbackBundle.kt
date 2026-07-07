package com.nibhaus.feedback

import android.os.Build
import com.nibhaus.BuildConfig

/**
 * The exact diagnostic snapshot shown to the user before they send feedback — user-initiated,
 * nothing leaves the device until they tap Send. See [formatBundleText] for how it's rendered and
 * [FeedbackScreen] for where it's shown/sent. Every field is either a compile-time constant or a
 * value the caller already has on hand — building this collects nothing new.
 */
data class FeedbackBundle(
    val appVersionName: String,
    val appVersionCode: Int,
    val androidVersion: String,
    val deviceModel: String,
    val penDriverId: String,
    val premiumUnlocked: Boolean,
    val paletteId: String,
    /** The tail of the last crash report (see [crashTail]), or null when there isn't one. */
    val lastCrashTail: String? = null,
)

/**
 * Assemble the bundle from build-time constants (app version, [BuildConfig.PEN_DRIVER]) plus the
 * few live values the caller already holds (premium entitlement, active palette id). No [android
 * .content.Context] needed — [Build] fields are static — so this is directly unit testable.
 * [crashTail] is precomputed by the caller; the file read itself lives in [CrashCapture].
 */
fun buildFeedbackBundle(
    premiumUnlocked: Boolean,
    paletteId: String,
    crashTail: String? = null,
): FeedbackBundle = FeedbackBundle(
    appVersionName = BuildConfig.VERSION_NAME,
    appVersionCode = BuildConfig.VERSION_CODE,
    androidVersion = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
    deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
    penDriverId = BuildConfig.PEN_DRIVER,
    premiumUnlocked = premiumUnlocked,
    paletteId = paletteId,
    lastCrashTail = crashTail,
)

/**
 * Plain-text rendering of [bundle] — verbatim what the user sees as selectable text on the
 * feedback screen AND verbatim what's appended to the email body, so "you'll see exactly what's
 * sent" is literally true. Pure/deterministic — no locale-sensitive formatting.
 */
fun formatBundleText(bundle: FeedbackBundle): String = buildString {
    appendLine("Nibhaus version: ${bundle.appVersionName} (${bundle.appVersionCode})")
    appendLine("Android: ${bundle.androidVersion}")
    appendLine("Device: ${bundle.deviceModel}")
    appendLine("Pen driver: ${bundle.penDriverId}")
    appendLine("Premium: ${bundle.premiumUnlocked}")
    append("Palette: ${bundle.paletteId}")
    bundle.lastCrashTail?.let { tail ->
        appendLine()
        appendLine()
        appendLine("Last crash (excerpt):")
        append(tail)
    }
}

/** The exact subject line for the ACTION_SEND intent — "Nibhaus feedback (vX.Y)" per the brief. */
fun feedbackEmailSubject(bundle: FeedbackBundle): String = "Nibhaus feedback (v${bundle.appVersionName})"

/**
 * The exact email body: the user's free-text account first (if any), then the diagnostic bundle
 * verbatim as shown on screen.
 */
fun feedbackEmailBody(userText: String, bundleText: String): String {
    val trimmed = userText.trim()
    return if (trimmed.isEmpty()) bundleText else "$trimmed\n\n$bundleText"
}
