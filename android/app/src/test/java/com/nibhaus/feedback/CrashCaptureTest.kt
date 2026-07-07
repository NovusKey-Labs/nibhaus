package com.nibhaus.feedback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CrashCaptureTest {

    // --- boundedStackTrace / formatCrashReport: what gets written to last_crash.txt ---

    @Test fun `short stack traces pass through unbounded`() {
        assertThat(boundedStackTrace("java.lang.NullPointerException", maxChars = 100))
            .isEqualTo("java.lang.NullPointerException")
    }

    @Test fun `long stack traces are truncated with a marker`() {
        val huge = "x".repeat(50)
        val bounded = boundedStackTrace(huge, maxChars = 10)
        assertThat(bounded).isEqualTo("x".repeat(10) + "\n… [truncated]")
    }

    @Test fun `formatCrashReport writes a parseable header then the bounded trace`() {
        val report = formatCrashReport(1_700_000_000_000L, "boom", maxChars = 100)
        assertThat(report).isEqualTo("CRASH_AT=1700000000000\nboom")
    }

    @Test fun `formatCrashReport bounds a pathological trace`() {
        val report = formatCrashReport(1L, "y".repeat(50), maxChars = 5)
        assertThat(report).isEqualTo("CRASH_AT=1\n" + "y".repeat(5) + "\n… [truncated]")
    }

    // --- parseCrashTimestamp: reading the header back ---

    @Test fun `parses the header timestamp`() {
        assertThat(parseCrashTimestamp("CRASH_AT=123456\nsome trace")).isEqualTo(123456L)
    }

    @Test fun `null content has no timestamp`() {
        assertThat(parseCrashTimestamp(null)).isNull()
    }

    @Test fun `malformed header has no timestamp`() {
        assertThat(parseCrashTimestamp("not a header\ntrace")).isNull()
        assertThat(parseCrashTimestamp("")).isNull()
    }

    // --- crashReportBody: everything after the header line ---

    @Test fun `body is everything after the first line`() {
        assertThat(crashReportBody("CRASH_AT=1\nline1\nline2")).isEqualTo("line1\nline2")
    }

    @Test fun `body is null when there is no content, no newline, or a blank remainder`() {
        assertThat(crashReportBody(null)).isNull()
        assertThat(crashReportBody("CRASH_AT=1")).isNull() // no trailing newline at all
        assertThat(crashReportBody("CRASH_AT=1\n   ")).isNull() // blank remainder
    }

    // --- crashTail: the excerpt embedded in the feedback bundle ---

    @Test fun `crashTail returns the trimmed body when short`() {
        assertThat(crashTail("  boom  ", maxChars = 100)).isEqualTo("boom")
    }

    @Test fun `crashTail keeps only the last maxChars, prefixed with an ellipsis`() {
        val body = "0123456789"
        assertThat(crashTail(body, maxChars = 4)).isEqualTo("…6789")
    }

    @Test fun `crashTail is null for null or blank input`() {
        assertThat(crashTail(null)).isNull()
        assertThat(crashTail("   ")).isNull()
    }

    // --- crashPromptEligible: the next-launch prompt's show/hide rule ---

    @Test fun `not eligible without a crash timestamp`() {
        assertThat(crashPromptEligible(crashTimestamp = null, acknowledgedTimestamp = 0L)).isFalse()
    }

    @Test fun `eligible when a crash exists and nothing has been acknowledged yet`() {
        assertThat(crashPromptEligible(crashTimestamp = 500L, acknowledgedTimestamp = 0L)).isTrue()
    }

    @Test fun `not eligible once that exact crash has been acknowledged`() {
        assertThat(crashPromptEligible(crashTimestamp = 500L, acknowledgedTimestamp = 500L)).isFalse()
    }

    @Test fun `a new crash after an old acknowledged one is eligible again`() {
        assertThat(crashPromptEligible(crashTimestamp = 900L, acknowledgedTimestamp = 500L)).isTrue()
    }
}
