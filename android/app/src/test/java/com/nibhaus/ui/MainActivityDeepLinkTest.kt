package com.nibhaus.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MainActivityDeepLinkTest {
    @Test fun `accepts a canonical page UUID`() {
        assertThat(validatedWidgetPageId("123e4567-e89b-12d3-a456-426614174000"))
            .isEqualTo("123e4567-e89b-12d3-a456-426614174000")
    }

    @Test fun `rejects malicious malformed and oversized page identifiers`() {
        assertThat(validatedWidgetPageId("../../../../data/data/com.nibhaus/files/secret")).isNull()
        assertThat(validatedWidgetPageId("a".repeat(10_000))).isNull()
        assertThat(validatedWidgetPageId("1-1-1-1-1")).isNull()
    }

    @Test fun `missing page identifier falls back to normal launch`() {
        assertThat(validatedWidgetPageId(null)).isNull()
    }
}
