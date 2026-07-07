package com.nibhaus.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.nibhaus.export.ThemeMode
import com.nibhaus.export.paletteIdFromLegacyTheme
import com.nibhaus.ui.theme.InkShapes
import com.nibhaus.ui.theme.InkTypography
import com.nibhaus.ui.theme.LocalInkExtras
import com.nibhaus.ui.theme.LocalInkPaper
import com.nibhaus.ui.theme.Palette
import com.nibhaus.ui.theme.Palettes
import com.nibhaus.ui.theme.dynamicPalette
import com.nibhaus.ui.theme.extras
import com.nibhaus.ui.theme.inkPaperFor
import com.nibhaus.ui.theme.schemeFrom

/**
 * The "Ink & Ncode" theme — Material 3 *Expressive*, themed from the user's selected color [Palette]
 * (design-system §12: selectable color-palette theming).
 *
 * [MaterialExpressiveTheme] brings the Expressive spring/physics motion scheme app-wide (livelier
 * component transitions) while [schemeFrom] derives the Material color scheme from the palette's own
 * tokens. Material You dynamic color is intentionally NOT used: the brand identity must hold
 * regardless of device wallpaper.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NibhausTheme(palette: Palette, lightPaper: Boolean, content: @Composable () -> Unit) {
    // "Match my wallpaper" (Android 12+): [palette] may be [Palettes.DYNAMIC_PLACEHOLDER] — resolve
    // it against a live Context + the current system dark/light mode right here, so every caller
    // (the live app theme in MainActivity AND the Settings preview island) gets real Material You
    // colors with zero special-casing anywhere else. Pre-S (or if Context resolution ever fails to
    // apply), the placeholder's own baked tokens render instead — never blank/broken.
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val resolved = if (palette.id == Palettes.DYNAMIC_ID && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        remember(systemDark) { dynamicPalette(context, dark = systemDark) }
    } else {
        palette
    }
    // 3-tone split: chrome (this `dark`) and paper theme independently, so a dark palette can pair a
    // dark UI with a light page. `dark` drives the Material scheme + system bars; the paper token below
    // drives the page canvas.
    val tones = tonesFor(resolved, lightPaper)
    val dark = tones.darkChrome
    // These parse CSS-string tokens off the palette on every call; remember them keyed on the
    // palette (and the resolved light-paper tone) so recompositions that don't change the theme
    // don't re-derive the scheme/extras/paper from scratch.
    val scheme = remember(resolved) { schemeFrom(resolved) }
    val extras = remember(resolved) { resolved.extras() }
    val inkPaper = remember(resolved, tones.lightPaper) { inkPaperFor(resolved, tones.lightPaper) }
    // Keep the OS status/nav-bar glyphs (clock, gesture pill) legible against OUR background:
    // dark theme → light icons, light theme → dark icons. enableEdgeToEdge() keys off the *system*
    // dark-mode setting, which can disagree with the in-app theme override — so set it explicitly.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // At Activity level view.context IS the Activity; inside a Dialog it's a ContextThemeWrapper —
            // skip bar styling there (the preview island must not mutate the app window's bars).
            val activity = view.context as? Activity ?: return@SideEffect
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialExpressiveTheme(
        colorScheme = scheme,
        typography = InkTypography,
        shapes = InkShapes,
    ) {
        CompositionLocalProvider(
            LocalInkPaper provides inkPaper,
            LocalInkExtras provides extras,
        ) {
            content()
        }
    }
}

/**
 * Legacy [ThemeMode] entry point — kept so the existing `@Preview` composables (which pick an
 * explicit light/dark mode, not a palette) keep compiling unchanged. Migrates the mode to its
 * equivalent palette id + light-paper toggle via [paletteIdFromLegacyTheme] and delegates.
 */
@Composable
fun NibhausTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val (id, lp) = paletteIdFromLegacyTheme(mode.key)
    NibhausTheme(Palettes.byId(id), lp, content)
}
