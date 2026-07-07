package com.nibhaus.ui

import com.nibhaus.ui.theme.Palette

/**
 * The 3-tone split: app **chrome** (bars, nav, cards) and the writing **paper** (page canvas) are
 * themed independently, so we can offer a dark UI with a light page.
 *
 * Pure (no Compose) so it's unit-testable. [darkChrome] drives the Material color scheme and the
 * system bars; [lightPaper] drives the `paper` token the page canvas reads (see InkPaper.kt).
 */
data class ThemeTones(val darkChrome: Boolean, val lightPaper: Boolean)

/**
 * Resolves [ThemeTones] for a selectable [Palette] plus the user's light-paper toggle. Light
 * palettes always use light paper (there's no dark-paper variant of a light palette); dark
 * palettes honour the toggle, pairing a dark UI with either a dark or a light page.
 */
fun tonesFor(p: Palette, lightPaperToggle: Boolean): ThemeTones =
    ThemeTones(darkChrome = p.isDark, lightPaper = if (p.isDark) lightPaperToggle else true)
