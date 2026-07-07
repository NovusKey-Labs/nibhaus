package com.nibhaus

import com.google.common.truth.Truth.assertThat
import com.nibhaus.export.printedButtonTipEligible
import com.nibhaus.export.replayTipEligible
import com.nibhaus.export.transcribeTipEligible
import org.junit.Test

class TipEligibilityTest {

    // --- replayTipEligible: shown in page detail once a page has more than 20 strokes ---

    @Test fun `replay tip is not eligible at or below the stroke threshold`() {
        assertThat(replayTipEligible(strokeCount = 20, dismissed = false)).isFalse()
        assertThat(replayTipEligible(strokeCount = 0, dismissed = false)).isFalse()
    }

    @Test fun `replay tip is eligible once strokes exceed the threshold`() {
        assertThat(replayTipEligible(strokeCount = 21, dismissed = false)).isTrue()
    }

    @Test fun `replay tip stays hidden forever once dismissed`() {
        assertThat(replayTipEligible(strokeCount = 500, dismissed = true)).isFalse()
    }

    // --- printedButtonTipEligible: Pens home, after 3 pages captured, never zone-tapped ---

    @Test fun `printed-button tip needs at least 3 pages captured`() {
        assertThat(printedButtonTipEligible(totalPages = 2, everZoneTapped = false, dismissed = false)).isFalse()
        assertThat(printedButtonTipEligible(totalPages = 3, everZoneTapped = false, dismissed = false)).isTrue()
    }

    @Test fun `printed-button tip hides once the user has ever tapped a zone`() {
        assertThat(printedButtonTipEligible(totalPages = 10, everZoneTapped = true, dismissed = false)).isFalse()
    }

    @Test fun `printed-button tip hides once dismissed`() {
        assertThat(printedButtonTipEligible(totalPages = 10, everZoneTapped = false, dismissed = true)).isFalse()
    }

    // --- transcribeTipEligible: Library, after 5 pages, never transcribed ---

    @Test fun `transcribe tip needs at least 5 pages captured`() {
        assertThat(transcribeTipEligible(totalPages = 4, everTranscribed = false, dismissed = false)).isFalse()
        assertThat(transcribeTipEligible(totalPages = 5, everTranscribed = false, dismissed = false)).isTrue()
    }

    @Test fun `transcribe tip hides once any page has been transcribed`() {
        assertThat(transcribeTipEligible(totalPages = 50, everTranscribed = true, dismissed = false)).isFalse()
    }

    @Test fun `transcribe tip hides once dismissed`() {
        assertThat(transcribeTipEligible(totalPages = 50, everTranscribed = false, dismissed = true)).isFalse()
    }
}
