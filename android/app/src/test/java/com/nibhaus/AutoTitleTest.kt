package com.nibhaus

import com.google.common.truth.Truth.assertThat
import com.nibhaus.text.AutoTitle
import org.junit.Test

class AutoTitleTest {
    @Test fun firstNonEmptyLine_becomesTitle() {
        assertThat(AutoTitle.fromTranscript("\n  Meeting notes \nrest of page")).isEqualTo("Meeting notes")
    }

    @Test fun longLine_isCappedWithEllipsis() {
        val t = AutoTitle.fromTranscript("x".repeat(100), maxLen = 10)!!
        assertThat(t).hasLength(11) // 10 chars + ellipsis
        assertThat(t.endsWith("…")).isTrue()
    }

    @Test fun nullOrBlank_returnsNull() {
        assertThat(AutoTitle.fromTranscript(null)).isNull()
        assertThat(AutoTitle.fromTranscript("   \n  ")).isNull()
    }
}
