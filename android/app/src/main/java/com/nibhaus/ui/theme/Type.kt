package com.nibhaus.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.nibhaus.R

/**
 * Typography for the "Nib & Ink" v2 direction (supersedes the v1 Newsreader/Plex-Sans set):
 *   - **Sora** for display / headline / large titles (the structural, slightly geometric voice),
 *   - **Inter** for UI titles, body and labels,
 *   - **IBM Plex Mono** for data readouts and eyebrow labels.
 *
 * All three are **bundled** in res/font/ and ship inside the APK — NOT pulled via downloadable
 * Google Fonts, which would fetch from fonts.gstatic.com at runtime and break the one hard privacy
 * rule (no outbound network except user-selected sync/OCR). See app/THIRD_PARTY_FONTS.md (SIL OFL 1.1).
 *
 * Sora and Inter ship as variable fonts; each Material weight pins the `wght` axis via
 * [FontVariation] (API 26+; both target devices are Android 15). IBM Plex Mono ships as static
 * Regular/Medium instances.
 */
// FontVariation (variable-font axis pinning) is still @ExperimentalTextApi → opt in explicitly.
@OptIn(ExperimentalTextApi::class)
private fun variable(resId: Int, weight: Int) =
    Font(resId, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

private val Sora = FontFamily( // display + large titles
    variable(R.font.sora_variable, 600),
    variable(R.font.sora_variable, 700),
    variable(R.font.sora_variable, 800),
)
private val Inter = FontFamily( // UI titles, body, labels
    variable(R.font.inter_variable, 400),
    variable(R.font.inter_variable, 500),
    variable(R.font.inter_variable, 600),
    variable(R.font.inter_variable, 700),
)
private val Mono = FontFamily( // IBM Plex Mono
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
)

val InkTypography = Typography(
    displayLarge = TextStyle(fontFamily = Sora, fontWeight = FontWeight.ExtraBold, fontSize = 48.sp, lineHeight = 52.sp, letterSpacing = (-1.0).sp),
    displaySmall = TextStyle(fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontFamily = Sora, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 26.sp, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontFamily = Sora, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
    titleMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 19.sp, letterSpacing = 0.sp),
    bodyLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodySmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 12.5.sp, lineHeight = 18.sp, letterSpacing = 0.2.sp),
    labelLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
)

/** Mono data readouts — coordinates, battery, fps, page counts. */
val monoData = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.4.sp)

/** Uppercase section eyebrow ("SYNC", "RECENT PAGES"). Tint at the call site. */
val monoEyebrow = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 2.0.sp)
