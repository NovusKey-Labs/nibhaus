package com.nibhaus.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * "Nib & Ink" v2 palette (supersedes the v1 teal/paper set). Deep-navy ground, brushed-steel
 * structure, and ink that flows in a blue→indigo→violet gradient. Dark-first — the dark scheme is
 * the signature; the light scheme is the same system in daylight.
 *
 * Material You dynamic color stays OFF (see [com.nibhaus.ui.NibhausTheme]) so the brand holds
 * regardless of wallpaper. The gradient is not a single Material role; [InkGradientStops] /
 * [com.nibhaus.ui.theme.InkTokens.inkBrush] carry it for ink + accents. `primary` is the solid
 * accent that stands in where a single color is needed.
 */

// --- core tokens (dark/signature) ---
val NibGround = Color(0xFF070D1C)  // deepest vault ground — app background (mockup); cards pop above it
val Navy = Color(0xFF0B1B3C)         // mid navy — raised fields / inner panels
val NavyDeep = Color(0xFF081530)     // deeper ground — bars, nav, scrims
val NavySurface = Color(0xFF142C4D)  // card / surface
val NavySurface2 = Color(0xFF1B355C) // raised field / surfaceVariant
val InkText = Color(0xFFEAF0FB)      // primary text/ink on dark
val Slate = Color(0xFF8B98B6)        // muted text
val Hairline = Color(0xFF223863)     // dividers/outline (dark)

// brushed steel (logo structure)
val SteelL = Color(0xFFC6CFDC)
val SteelM = Color(0xFF94A0B6)
val SteelD = Color(0xFF5C6980)

// ink gradient stops + solid accent
val G1 = Color(0xFF2E83FC) // blue
val G2 = Color(0xFF5B5BF6) // indigo
val G3 = Color(0xFF8D4EFA) // violet
val Accent = Color(0xFF5566F4)

/** Live-capture signal — a clear green that reads as "recording" and pops against the navy/blue UI. */
val LiveGreen = Color(0xFF22E07A)

/** The blue→indigo→violet ink gradient, as ordered stops (used to build a Brush at draw time). */
val InkGradientStops = listOf(G1, G2, G3)

// --- light (daylight) tokens ---
val DaySurface = Color(0xFFFFFFFF)
val DayBg = Color(0xFFF4F6FA)
val DayInk = Color(0xFF0E1F3D)
val DaySlate = Color(0xFF5B6884)
val DayHairline = Color(0xFFE4E9F2)
