package com.nibhaus.ui.theme

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.roundToInt

/**
 * "Match my wallpaper" (design-system §12 add-on, Android 12+): builds a real [Palette] from the
 * platform's Material You dynamic `ColorScheme` (seeded from the user's wallpaper). Every field is
 * converted back to the same CSS-string token shape every hand-tuned [Palette] uses, so the result
 * flows through the exact same [schemeFrom]/[extras]/[inkPaperFor] pipeline as the other 10 — no
 * special-casing anywhere else in the theme system. Re-derive on demand (callers typically wrap
 * this in `remember`); it isn't cached here, so a wallpaper change is picked up on the next call.
 */
@RequiresApi(Build.VERSION_CODES.S)
fun dynamicPalette(context: Context, dark: Boolean): Palette {
    val scheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    return Palette(
        id = Palettes.DYNAMIC_ID,
        mode = if (dark) "dark" else "light",
        name = "Match my wallpaper",
        mood = "Adaptive · personal · from your wallpaper",
        base = scheme.background.toHex(),
        support = scheme.secondary.toHex(),
        accent = scheme.primary.toHex(),
        text = scheme.onBackground.toHex(),
        muted = scheme.onSurfaceVariant.toHex(),
        surface = scheme.surface.toHex(),
        surface2 = scheme.surfaceContainerHigh.toHex(),
        surface3 = scheme.surfaceContainerHighest.toHex(),
        panelBg = scheme.surface.toRgba(0.86f),
        shellBg = scheme.surface.toRgba(0.90f),
        navBg = scheme.surface.toRgba(0.78f),
        paper = scheme.surface.toHex(),
        inkLine = scheme.primary.toRgba(0.70f),
        pattern = "dynamic",
        sceneA = scheme.primary.toRgba(0.18f),
        sceneB = scheme.surfaceVariant.toRgba(0.46f),
        sceneBase = scheme.background.toHex(),
        // steelBorder's bezel gradient wants a light/mid/dark trio; the dynamic scheme has no
        // literal equivalent, so derive one from onPrimary/outline the same way the hand-tuned
        // palettes read visually (near-white highlight → mid steel → near-black).
        bezel1 = scheme.onPrimary.mix(Color.White, 0.6f).toHex(),
        bezel2 = scheme.outline.toHex(),
        bezel3 = scheme.outline.mix(Color.Black, 0.55f).toHex(),
        glow = scheme.primary.toRgba(0.44f),
        hairline = scheme.onBackground.toRgba(0.10f),
    )
}

/** Linear-interpolates towards [other] by [fraction] (0=this, 1=other), alpha fixed opaque — used
 *  only to derive the couple of bezel tones the dynamic scheme doesn't provide directly. */
private fun Color.mix(other: Color, fraction: Float): Color = Color(
    red = red + (other.red - red) * fraction,
    green = green + (other.green - green) * fraction,
    blue = blue + (other.blue - blue) * fraction,
    alpha = 1f,
)

private fun Color.toHex(): String {
    val argb = this.copy(alpha = 1f).toArgb()
    return "#%06X".format(argb and 0xFFFFFF)
}

private fun Color.toRgba(alpha: Float): String {
    val r = (red.coerceIn(0f, 1f) * 255).roundToInt()
    val g = (green.coerceIn(0f, 1f) * 255).roundToInt()
    val b = (blue.coerceIn(0f, 1f) * 255).roundToInt()
    return "rgba($r,$g,$b,$alpha)"
}
