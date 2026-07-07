package com.nibhaus.export

import com.google.common.truth.Truth.assertThat
import com.nibhaus.data.Point
import org.junit.Test

/** Self-contained page backup (Ncode address + every stroke's points) → restorable from a folder. */
class PageBackupTest {

    private val sample = PageBackup(
        section = 3, owner = 27, book = 438, page = 8,
        strokes = listOf(
            BackupStroke(color = 0, width = 1f, points = listOf(Point(1f, 2f, 0.5f, 100L), Point(3f, 4f, 0.6f, 110L))),
            BackupStroke(color = -16776961, width = 1.4f, points = listOf(Point(5f, 6f, 0.7f, 200L))),
        ),
    )

    @Test fun `page backup round-trips through JSON (full stroke data preserved)`() {
        assertThat(decodeBackup(encodeBackup(sample))).isEqualTo(sample)
    }

    @Test fun `decodeBackup returns null on garbage (a bad restore file)`() {
        assertThat(decodeBackup("not a backup")).isNull()
    }

    @Test fun `restore stroke ids are content-derived and stable (re-restore won't duplicate)`() {
        val s = sample.strokes[0]
        assertThat(backupStrokeId(sample, s)).isEqualTo(backupStrokeId(sample, s))
        assertThat(backupStrokeId(sample, s)).isNotEqualTo(backupStrokeId(sample, sample.strokes[1]))
    }
}
