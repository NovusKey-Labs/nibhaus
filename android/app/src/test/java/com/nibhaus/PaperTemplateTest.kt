package com.nibhaus

import com.google.common.truth.Truth.assertThat
import com.nibhaus.export.PaperTemplate
import com.nibhaus.export.paperRowPositions
import org.junit.Test

class PaperTemplateTest {

    @Test fun `row positions start at offset and step by spacing, staying under length`() {
        val ys = paperRowPositions(length = 100f, spacing = 30f, offset = 10f)
        assertThat(ys.toList()).containsExactly(10f, 40f, 70f).inOrder() // 100f excluded (not < length)
    }

    @Test fun `non-positive spacing or length yields no rows`() {
        assertThat(paperRowPositions(100f, 0f, 5f)).isEmpty()
        assertThat(paperRowPositions(0f, 10f, 0f)).isEmpty()
    }

    @Test fun `template key round-trips, unknown falls back to default`() {
        PaperTemplate.entries.forEach { assertThat(PaperTemplate.fromKey(it.key)).isEqualTo(it) }
        assertThat(PaperTemplate.fromKey("nope")).isEqualTo(PaperTemplate.DEFAULT)
        assertThat(PaperTemplate.DEFAULT).isEqualTo(PaperTemplate.DOT_GRID)
    }
}
