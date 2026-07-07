package com.nibhaus.widget

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure display formatting for the home-screen widget (#13) — no Glance/Compose involved. */
class WidgetFormatTest {

    @Test fun `widgetPageLabel combines notebook name and page number`() {
        assertThat(widgetPageLabel("Field Notes", 12)).isEqualTo("Field Notes · p. 12")
    }

    @Test fun `widgetPageLabel falls back to a generic name when blank`() {
        assertThat(widgetPageLabel("", 3)).isEqualTo("Notebook · p. 3")
        assertThat(widgetPageLabel("   ", 3)).isEqualTo("Notebook · p. 3")
    }

    @Test fun `relativeTimeLabel buckets by minute, hour, day, week`() {
        val now = 1_000_000_000L
        assertThat(relativeTimeLabel(now, now)).isEqualTo("just now")
        assertThat(relativeTimeLabel(now, now - 30_000L)).isEqualTo("just now") // 30s
        assertThat(relativeTimeLabel(now, now - 5 * 60_000L)).isEqualTo("5m ago")
        assertThat(relativeTimeLabel(now, now - 3 * 3_600_000L)).isEqualTo("3h ago")
        assertThat(relativeTimeLabel(now, now - 2 * 86_400_000L)).isEqualTo("2d ago")
        assertThat(relativeTimeLabel(now, now - 15 * 86_400_000L)).isEqualTo("2w ago")
    }

    @Test fun `relativeTimeLabel never goes negative for a future timestamp`() {
        val now = 1_000_000_000L
        assertThat(relativeTimeLabel(now, now + 60_000L)).isEqualTo("just now")
    }
}
