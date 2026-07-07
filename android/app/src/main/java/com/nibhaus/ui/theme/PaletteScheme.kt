package com.nibhaus.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Parses a raw CSS color string (as stored on [Palette]) into a Compose [Color].
 * Handles `#RRGGBB` hex and `rgba(r,g,b,a)` forms — the only two shapes present
 * in the palette token JSON.
 */
fun parseCssColor(s: String): Color {
    val trimmed = s.trim()
    return if (trimmed.startsWith("#")) {
        Color(("FF" + trimmed.removePrefix("#")).toLong(16))
    } else {
        val args = trimmed.removePrefix("rgba(").removeSuffix(")").split(",").map { it.trim() }
        val r = args[0].toInt()
        val g = args[1].toInt()
        val b = args[2].toInt()
        val a = args[3].toFloat()
        Color(red = r / 255f, green = g / 255f, blue = b / 255f, alpha = a)
    }
}

private fun luminance(c: Color) = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue

private fun contrastOf(c: Color): Color = if (luminance(c) > 0.5f) Color.Black else Color.White

/** Maps a [Palette]'s design tokens onto a Material 3 [ColorScheme]. */
fun schemeFrom(p: Palette): ColorScheme {
    val base = parseCssColor(p.base)
    val support = parseCssColor(p.support)
    val accent = parseCssColor(p.accent)
    val text = parseCssColor(p.text)
    val muted = parseCssColor(p.muted)
    val surface = parseCssColor(p.surface)
    val surface2 = parseCssColor(p.surface2)
    val surface3 = parseCssColor(p.surface3)
    val hairline = parseCssColor(p.hairline)

    val baseScheme = if (p.isDark) darkColorScheme() else lightColorScheme()

    return baseScheme.copy(
        background = base,
        onBackground = text,
        surface = surface,
        onSurface = text,
        surfaceVariant = surface2,
        onSurfaceVariant = muted,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surface2,
        surfaceContainerHighest = surface3,
        primary = accent,
        onPrimary = contrastOf(accent),
        primaryContainer = surface3,
        onPrimaryContainer = text,
        secondary = support,
        onSecondary = contrastOf(support),
        secondaryContainer = surface2,
        onSecondaryContainer = text,
        tertiary = accent,
        tertiaryContainer = surface3,
        outline = hairline,
        outlineVariant = hairline,
        scrim = base,
        error = Color(0xFFFF7A75),
        onError = Color(0xFF3A0A08),
    )
}

/** App-specific colors not covered by the Material [ColorScheme] slots. */
data class InkExtras(
    val glow: Color,
    val bezel1: Color,
    val bezel2: Color,
    val bezel3: Color,
    val panelBg: Color,
    val shellBg: Color,
    val navBg: Color,
    val inkLine: Color,
    val paper: Color,
    /** Whether the *palette* (not the OS theme) reads dark — drives dark-only chrome like [steelBorder]/[glow]. */
    val isDark: Boolean,
)

fun Palette.extras(): InkExtras = InkExtras(
    glow = parseCssColor(glow),
    bezel1 = parseCssColor(bezel1),
    bezel2 = parseCssColor(bezel2),
    bezel3 = parseCssColor(bezel3),
    panelBg = parseCssColor(panelBg),
    shellBg = parseCssColor(shellBg),
    navBg = parseCssColor(navBg),
    inkLine = parseCssColor(inkLine),
    paper = parseCssColor(paper),
    isDark = this.isDark,
)

val LocalInkExtras = staticCompositionLocalOf { Palettes.DEFAULT.extras() }
