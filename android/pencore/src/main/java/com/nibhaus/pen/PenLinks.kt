package com.nibhaus.pen

/**
 * Maps a connected pen (by its reported name/model) to its manufacturer's official page — for a
 * user-initiated "Official page" link. Only well-known, stable manufacturer URLs (no fabricated or
 * guessed deep links). LAMY ncode pens are NeoLAB hardware sold under the LAMY brand, so they point
 * to LAMY; Neo pens point to NeoLAB; anything else falls back to the NeoLAB SDK org.
 *
 * [LAMY] is pinned to the `/en/` path, not the bare domain (field report, 2026-07-05): lamy.com's
 * root redirects by geo/locale, which sent users straight to the German (de-de) storefront. The
 * `/en/` language path avoids that without hardcoding any country locale.
 */
object PenLinks {
    const val NEOLAB = "https://www.neosmartpen.com"
    const val LAMY = "https://www.lamy.com/en/digital-writing/"
    const val SDK = "https://github.com/NeoSmartpen"

    /** Official page for [penName] (e.g. "LAMY_safari", "NWP-F80", "Neosmartpen_M1+", "NWP-F55"). */
    fun officialUrl(penName: String?): String {
        val n = penName.orEmpty().uppercase()
        return when {
            "LAMY" in n || "NWP-F80" in n -> LAMY
            "NEO" in n || "M1" in n || "NWP-F5" in n || "NWP-F1" in n || "DIMO" in n -> NEOLAB
            else -> SDK
        }
    }
}
