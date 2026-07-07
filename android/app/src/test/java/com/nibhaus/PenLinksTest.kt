package com.nibhaus

import com.google.common.truth.Truth.assertThat
import com.nibhaus.pen.PenLinks
import org.junit.Test

class PenLinksTest {
    @Test fun lamy_pointsToLamy() {
        assertThat(PenLinks.officialUrl("LAMY_safari")).isEqualTo(PenLinks.LAMY)
        assertThat(PenLinks.officialUrl("NWP-F80")).isEqualTo(PenLinks.LAMY)
    }

    @Test fun neo_pointsToNeolab() {
        assertThat(PenLinks.officialUrl("Neosmartpen_M1+")).isEqualTo(PenLinks.NEOLAB)
        assertThat(PenLinks.officialUrl("NWP-F55")).isEqualTo(PenLinks.NEOLAB)
    }

    @Test fun unknownOrBlank_fallsBackToSdk() {
        assertThat(PenLinks.officialUrl(null)).isEqualTo(PenLinks.SDK)
        assertThat(PenLinks.officialUrl("")).isEqualTo(PenLinks.SDK)
        assertThat(PenLinks.officialUrl("Mystery")).isEqualTo(PenLinks.SDK)
    }

    // Field report, 2026-07-05: the connect tile's LAMY link opened the German (de-de) storefront —
    // lamy.com's bare root redirects by geo/locale. Pin to a language path, never a country locale.
    @Test fun lamy_hasNoHardcodedCountryLocale() {
        assertThat(PenLinks.LAMY).doesNotContain("de-de")
        assertThat(PenLinks.LAMY).doesNotContain("/de/")
    }
}
