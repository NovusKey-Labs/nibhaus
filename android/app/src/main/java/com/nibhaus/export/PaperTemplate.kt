package com.nibhaus.export

/**
 * The paper style drawn behind the ink — the notebook's Ncode paper look. A user setting (like
 * [ThemeMode]); default [DOT_GRID] preserves the app's current appearance. Kept in the neutral
 * `export` layer, with no Android/Compose deps, so both the on-screen renderer (InkTokens) and the
 * raster exporter (PageRender) share one definition and the geometry is unit-testable.
 */
enum class PaperTemplate(val key: String, val label: String) {
    DOT_GRID("dot", "Dot grid"),
    LINED("lined", "Lined"),
    BLANK("blank", "Blank");

    companion object {
        val DEFAULT = DOT_GRID
        fun fromKey(key: String?): PaperTemplate = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/**
 * Positions (top-down, or left-to-right for the dot columns) of a paper template's rows along an
 * axis of length [length], starting at [offset] and stepping by [spacing]. Pure geometry so the
 * screen renderer and the exporter agree pixel-for-pixel. Empty when spacing/length is non-positive.
 */
fun paperRowPositions(length: Float, spacing: Float, offset: Float): FloatArray {
    if (spacing <= 0f || length <= 0f) return FloatArray(0)
    val out = ArrayList<Float>((length / spacing).toInt() + 1)
    var v = offset
    while (v < length) { out.add(v); v += spacing }
    return out.toFloatArray()
}
