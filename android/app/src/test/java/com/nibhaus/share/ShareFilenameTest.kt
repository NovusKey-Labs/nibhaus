package com.nibhaus.share

import com.google.common.truth.Truth.assertThat
import java.text.SimpleDateFormat
import java.util.Locale
import org.junit.Test

/** Feature 24: human-readable, filesystem-safe names for shared page/replay artifacts. */
class ShareFilenameTest {

    private fun date(s: String) = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(s)!!

    @Test fun `forPage builds the Nibhaus dash notebook p-page dash date format`() {
        assertThat(ShareFilename.forPage("Field Notes", 12, date("2026-07-02")))
            .isEqualTo("Nibhaus — Field Notes p12 — 2026-07-02")
    }

    @Test fun `forPage falls back to Notebook when the title is blank`() {
        assertThat(ShareFilename.forPage("", 3, date("2026-01-01")))
            .isEqualTo("Nibhaus — Notebook p3 — 2026-01-01")
    }

    @Test fun `forReplay appends replay before the date`() {
        assertThat(ShareFilename.forReplay("Trip Log", 5, date("2026-07-02")))
            .isEqualTo("Nibhaus — Trip Log p5 replay — 2026-07-02")
    }

    @Test fun `sanitize replaces path separators so a title can't escape its directory`() {
        assertThat(ShareFilename.sanitize("Notes/2026\\Q3")).isEqualTo("Notes 2026 Q3")
    }

    @Test fun `sanitize strips other filesystem-hostile characters`() {
        assertThat(ShareFilename.sanitize("Weird: \"Title\" <name> | *?")).isEqualTo("Weird Title name")
    }

    @Test fun `sanitize strips emoji but keeps ordinary punctuation like the em dash`() {
        assertThat(ShareFilename.sanitize("🎉 Trip Log — Summer 🏖️ Notes 🎉")).isEqualTo("Trip Log — Summer Notes")
    }

    @Test fun `sanitize caps the length of a very long title`() {
        val long = "x".repeat(400)
        val result = ShareFilename.sanitize(long)
        assertThat(result.length).isAtMost(120)
        assertThat(result).isEqualTo("x".repeat(120))
    }

    @Test fun `sanitize collapses internal whitespace left behind by stripped characters`() {
        assertThat(ShareFilename.sanitize("A   /   B")).isEqualTo("A B")
    }

    @Test fun `sanitize never returns blank for an all-hostile input`() {
        assertThat(ShareFilename.sanitize("///:::")).isEqualTo("Nibhaus page")
    }

    @Test fun `sanitize on an already-clean name is a no-op`() {
        assertThat(ShareFilename.sanitize("Nibhaus — Field Notes p12 — 2026-07-02"))
            .isEqualTo("Nibhaus — Field Notes p12 — 2026-07-02")
    }
}
