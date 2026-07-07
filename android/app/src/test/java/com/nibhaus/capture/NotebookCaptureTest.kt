package com.nibhaus.capture

import com.google.common.truth.Truth.assertThat
import com.nibhaus.export.PageGeometry
import kotlinx.serialization.json.Json
import org.junit.Test

/** Pure capture math + the notebook profile (geometry produced on-device by the wizard). */
class NotebookCaptureTest {

    @Test fun `cornerBounds normalises two taps regardless of order`() {
        // Bottom-right tapped first, top-left second → still min..max.
        assertThat(cornerBounds(62.5f, 90.0f, 3.9f, 3.8f))
            .isEqualTo(PageBounds(3.9f, 3.8f, 62.5f, 90.0f))
    }

    @Test fun `mmPerUnitOf divides known mm by the span in units`() {
        assertThat(mmPerUnitOf(spanUnits = 43.1f, knownMm = 100f)).isWithin(1e-4f).of(100f / 43.1f)
    }

    @Test fun `mmPerUnitOf is null for a non-positive span`() {
        assertThat(mmPerUnitOf(0f, 100f)).isNull()
    }

    @Test fun `assembleGeometry builds PageGeometry from bounds plus sheet mm`() {
        val g = assembleGeometry(PageBounds(3.9f, 3.8f, 62.5f, 90.0f), sheetWmm = 145f, sheetHmm = 210f)
        assertThat(g.writableX0).isEqualTo(3.9f)
        assertThat(g.writableY1).isEqualTo(90.0f)
        assertThat(g.pageWidthMm).isEqualTo(145f)
        assertThat(g.pageHeightMm).isEqualTo(210f)
    }

    @Test fun `resolveGeometry prefers a captured profile over the built-in`() {
        val captured = PageGeometry(1f, 1f, 2f, 2f, 10f, 10f)
        val builtin = PageGeometry(3.9f, 3.8f, 62.5f, 90.0f, 137.5f, 210f)
        assertThat(resolveGeometry(NotebookProfile(438, captured), builtin)).isEqualTo(captured)
        assertThat(resolveGeometry(null, builtin)).isEqualTo(builtin)
        assertThat(resolveGeometry(NotebookProfile(438, geometry = null), builtin)).isEqualTo(builtin)
    }

    @Test fun `profile round-trips through JSON`() {
        val p = NotebookProfile(438, PageGeometry(3.9f, 3.8f, 62.5f, 90f, 137.5f, 210f), mmPerUnit = 2.32f)
        val json = Json.encodeToString(NotebookProfile.serializer(), p)
        assertThat(Json.decodeFromString(NotebookProfile.serializer(), json)).isEqualTo(p)
    }

    @Test fun `encodeProfile then decodeProfile round-trips`() {
        val p = NotebookProfile(438, PageGeometry(3.9f, 3.8f, 62.5f, 90f, 137.5f, 210f), mmPerUnit = 2.32f)
        assertThat(decodeProfile(encodeProfile(p))).isEqualTo(p)
    }

    @Test fun `decodeProfile returns null on garbage (a bad share import)`() {
        assertThat(decodeProfile("not a profile")).isNull()
    }
}
