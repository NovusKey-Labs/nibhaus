package com.nibhaus.feedback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FeedbackBundleTest {

    private fun sampleBundle(lastCrashTail: String? = null) = FeedbackBundle(
        appVersionName = "0.1.0",
        appVersionCode = 1,
        androidVersion = "Android 14 (SDK 34)",
        deviceModel = "Google Pixel 8",
        penDriverId = "penble",
        premiumUnlocked = true,
        paletteId = "D01",
        lastCrashTail = lastCrashTail,
    )

    // --- formatBundleText: exactly what's shown AND exactly what's sent ---

    @Test fun `formats every field without a crash`() {
        val text = formatBundleText(sampleBundle())
        assertThat(text).isEqualTo(
            "Nibhaus version: 0.1.0 (1)\n" +
                "Android: Android 14 (SDK 34)\n" +
                "Device: Google Pixel 8\n" +
                "Pen driver: penble\n" +
                "Premium: true\n" +
                "Palette: D01",
        )
    }

    @Test fun `appends the crash excerpt when present`() {
        val text = formatBundleText(sampleBundle(lastCrashTail = "java.lang.RuntimeException: boom"))
        assertThat(text).contains("Palette: D01\n\nLast crash (excerpt):\njava.lang.RuntimeException: boom")
    }

    @Test fun `omits the crash section entirely when there is none`() {
        val text = formatBundleText(sampleBundle(lastCrashTail = null))
        assertThat(text).doesNotContain("Last crash")
    }

    @Test fun `reflects the premium flag as a literal boolean`() {
        assertThat(formatBundleText(sampleBundle().copy(premiumUnlocked = false))).contains("Premium: false")
        assertThat(formatBundleText(sampleBundle().copy(premiumUnlocked = true))).contains("Premium: true")
    }

    // --- feedbackEmailSubject ---

    @Test fun `subject includes the app version`() {
        assertThat(feedbackEmailSubject(sampleBundle())).isEqualTo("Nibhaus feedback (v0.1.0)")
    }

    // --- feedbackEmailBody: user text (if any) + the bundle, verbatim ---

    @Test fun `body is just the bundle when the user wrote nothing`() {
        val bundleText = formatBundleText(sampleBundle())
        assertThat(feedbackEmailBody("", bundleText)).isEqualTo(bundleText)
        assertThat(feedbackEmailBody("   ", bundleText)).isEqualTo(bundleText)
    }

    @Test fun `body leads with the trimmed user account, then the bundle`() {
        val bundleText = formatBundleText(sampleBundle())
        val body = feedbackEmailBody("  Export silently failed twice.  ", bundleText)
        assertThat(body).isEqualTo("Export silently failed twice.\n\n$bundleText")
    }

    // --- buildFeedbackBundle: wires the live inputs through untouched (Build.* fields aren't
    // meaningful under the plain-JVM unit test stub, so this only asserts what this function itself
    // controls) ---

    @Test fun `wires premium, palette, driver and crash tail through`() {
        val bundle = buildFeedbackBundle(premiumUnlocked = true, paletteId = "L02", crashTail = "trace…")
        assertThat(bundle.premiumUnlocked).isTrue()
        assertThat(bundle.paletteId).isEqualTo("L02")
        assertThat(bundle.lastCrashTail).isEqualTo("trace…")
        assertThat(bundle.penDriverId).isEqualTo(com.nibhaus.BuildConfig.PEN_DRIVER)
        assertThat(bundle.appVersionName).isEqualTo(com.nibhaus.BuildConfig.VERSION_NAME)
    }

    @Test fun `defaults to no crash tail`() {
        assertThat(buildFeedbackBundle(premiumUnlocked = false, paletteId = "D01").lastCrashTail).isNull()
    }
}
