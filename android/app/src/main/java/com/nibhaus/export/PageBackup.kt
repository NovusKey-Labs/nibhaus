package com.nibhaus.export

import com.nibhaus.data.Point
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A self-contained, restorable backup of one page — its Ncode address plus every stroke's full data
 * (color, width, and X/Y/pressure/time points). Unlike the metadata sidecar, this is enough to rebuild
 * the page's editable ink after a wipe. Exported as `<base>.bak.json` and read back by the restore flow.
 */
@Serializable
data class PageBackup(
    val section: Int,
    val owner: Int,
    val book: Int,
    val page: Int,
    val strokes: List<BackupStroke>,
)

@Serializable
data class BackupStroke(val color: Int, val width: Float, val points: List<Point>)

private val backupJson = Json { ignoreUnknownKeys = true }

fun encodeBackup(b: PageBackup): String = backupJson.encodeToString(PageBackup.serializer(), b)

fun decodeBackup(json: String): PageBackup? =
    runCatching { backupJson.decodeFromString(PageBackup.serializer(), json) }.getOrNull()

/**
 * Stable, content-derived id for a restored stroke (Ncode address + timing + point shape), so
 * re-restoring the same backup re-inserts the same row — INSERT IGNORE dedupes, never duplicates.
 */
fun backupStrokeId(b: PageBackup, s: BackupStroke): String {
    val shape = s.points.joinToString(";") { "${it.x},${it.y},${it.pressure},${it.t}" }
    val t0 = s.points.firstOrNull()?.t ?: 0L
    val t1 = s.points.lastOrNull()?.t ?: 0L
    return "bak-${b.section}.${b.owner}.${b.book}.${b.page}-$t0-$t1-${s.points.size}-${shape.hashCode()}"
}
