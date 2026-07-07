package com.nibhaus.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The **paper** tone — the page canvas color, themed independently of the app chrome (the 3-tone
 * split; see [com.nibhaus.ui.ThemeTones]). This is what lets [com.nibhaus.export.ThemeMode.DARK_LIGHT_PAPER]
 * pair a dark UI with a light writing page.
 *
 * [surface] is the page background; [ink] is the contrast color for brand (color-0) strokes and the
 * Ncode dot grid drawn on that paper. Keeping ink keyed to the paper — not the Material scheme —
 * is what keeps strokes legible when a light page sits under a dark scheme.
 */
data class InkPaper(val surface: Color, val ink: Color)

/** Soft cream writing paper — matches the physical notebook's warm ivory, not a clinical #FFFFFF. */
val PaperWarm = Color(0xFFF3EBD3)
private val PaperWarmInk = DayInk       // #0E1F3D — dark ink/dots on light paper
private val PaperDarkSurface = NavySurface // #142C4D — the signature dark page (matches the card surface)
private val PaperDarkInk = InkText      // #EAF0FB — light ink/dots on the dark page

fun inkPaperFor(lightPaper: Boolean): InkPaper =
    if (lightPaper) InkPaper(PaperWarm, PaperWarmInk) else InkPaper(PaperDarkSurface, PaperDarkInk)

private fun luminance(c: Color) = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue

/**
 * Palette-aware paper: a light [Palette] always uses its own light `paper` token; a dark palette
 * uses its dark `paper` token unless [lightPaper] is on, in which case it falls back to the warm
 * cream page (dark palettes don't ship a light `paper` variant). Ink is picked for contrast
 * against whichever surface results, so strokes stay legible either way.
 */
fun inkPaperFor(p: Palette, lightPaper: Boolean): InkPaper {
    val surface = if (lightPaper && p.isDark) PaperWarm else parseCssColor(p.paper)
    val ink = if (luminance(surface) > 0.5f) DayInk else InkText
    return InkPaper(surface, ink)
}

/** Ambient page-paper tone; provided by [com.nibhaus.ui.NibhausTheme] from the resolved [ThemeTones]. */
val LocalInkPaper = staticCompositionLocalOf { inkPaperFor(lightPaper = true) }
