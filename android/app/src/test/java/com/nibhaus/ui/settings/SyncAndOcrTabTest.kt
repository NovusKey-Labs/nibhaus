package com.nibhaus.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Final-review fix (2026-07-05): the Sync & Text tab's Advanced section (VLM tuning, the BYO
 * transcription server, and the translation server) must not offer editable, server-promising
 * fields to a not-entitled (freemium) user, since those are planned Premium features, not working
 * config. [plannedPremiumFeatureLine] is the exact copy each gated field is replaced with; pure and
 * directly testable so the three call sites in [syncAndOcrTab] can't drift from one another or from
 * the register's established "is a planned Premium feature and is not available yet" phrasing (see
 * SettingsScreen.kt / PageDetail.kt's existing upsell copy).
 */
class SyncAndOcrTabTest {

    @Test fun `planned premium line names the capability and uses the cleared sentence pattern`() {
        assertThat(plannedPremiumFeatureLine("Translation"))
            .isEqualTo("Translation is a planned Premium feature and is not available yet.")
    }

    @Test fun `planned premium line works for a multi-word capability`() {
        assertThat(plannedPremiumFeatureLine("Adding your own transcription server"))
            .isEqualTo("Adding your own transcription server is a planned Premium feature and is not available yet.")
    }

    @Test fun `planned premium line for handwriting recognition tuning`() {
        assertThat(plannedPremiumFeatureLine("Handwriting recognition tuning"))
            .isEqualTo("Handwriting recognition tuning is a planned Premium feature and is not available yet.")
    }
}
