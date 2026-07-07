package com.nibhaus.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import com.nibhaus.export.ThemeMode
import com.nibhaus.ui.theme.G1
import com.nibhaus.ui.theme.G2
import com.nibhaus.ui.theme.G3
import com.nibhaus.ui.theme.LocalInkExtras
import com.nibhaus.ui.theme.steelBorder

// ---------------------------------------------------------------------------
// Static gradient helpers
// ---------------------------------------------------------------------------

/**
 * The canonical, static ink gradient: `linearGradient(135°, #2E83FC → #5B5BF6 @0.52 → #8D4EFA)`.
 * Uses existing colour tokens [G1]/[G2]/[G3]. For a living, animated version see [gradPanBrush].
 *
 * The `Offset.Infinite` end means the gradient always fills the composable's full diagonal — safe
 * to use on any size surface (screen, card, or chip).
 */
fun inkGradient(): Brush = Brush.linearGradient(
    colorStops = arrayOf(0f to G1, 0.52f to G2, 1f to G3),
    start = Offset.Zero,
    end = Offset.Infinite,
)

// ---------------------------------------------------------------------------
// Glow modifier
// ---------------------------------------------------------------------------

/**
 * Ink glow — `#5B5BF6 @0.4, blur 22` — the ambient + spot halo used on chips, badges, FABs, and
 * active toggles. Dark-only: a no-op on light theme (glow reads as muddiness on white ground).
 *
 * [shape] should match the composable's clip shape (default: stadium/pill for chips).
 *
 * Note: Compose `shadow()` approximates CSS `box-shadow` blur; the elevation (22 dp) maps to
 * the CSS blur radius. The exact soft-radius behaviour differs from web but reads identically at
 * arm's length on device.
 */
@Composable
fun Modifier.inkGlow(shape: Shape = RoundedCornerShape(50)): Modifier {
    val extras = LocalInkExtras.current
    return if (extras.isDark) {
        shadow(
            elevation = 22.dp,
            shape = shape,
            clip = false,
            ambientColor = extras.glow.copy(alpha = 0.4f),
            spotColor = extras.glow.copy(alpha = 0.4f),
        )
    } else {
        this
    }
}

// ---------------------------------------------------------------------------
// Steel card modifier
// ---------------------------------------------------------------------------

/**
 * Brushed-steel card surface (V3 "steel card" spec):
 *  - fill `colorScheme.surface` — `NavySurface` (#142C4D) in dark; paper-white (#FFFFFF) in light.
 *  - 1 px brushed-steel gradient border via [steelBorder]: full gradient in dark, flat
 *    `outlineVariant` hairline in light (already handled inside [steelBorder]).
 *  - clip + round to [radius] (default 18 dp)
 *  - drop shadow: `#000 @0.7` at 18 dp elevation in dark; softened to @0.18 at 8 dp in light.
 *
 * Padding is the caller's responsibility.
 */
@Composable
fun Modifier.steelCard(radius: Dp = 18.dp): Modifier {
    val shape = RoundedCornerShape(radius)
    val isDark = LocalInkExtras.current.isDark
    val surface = MaterialTheme.colorScheme.surface
    // Top sheen: card surface is subtly lit at the top (lighter) fading to the base, simulating the
    // mockup's brushed-steel "lit from above" face. Flat (no sheen) in light theme — paper is paper.
    val topSheen = if (isDark) androidx.compose.ui.graphics.lerp(surface, Color.White, 0.08f) else surface
    return this
        .shadow(
            elevation = if (isDark) 18.dp else 8.dp,
            shape = shape,
            clip = false,
            spotColor = Color.Black.copy(alpha = if (isDark) 0.7f else 0.18f),
            ambientColor = Color.Black.copy(alpha = if (isDark) 0.18f else 0.06f),
        )
        .clip(shape)
        .background(Brush.verticalGradient(listOf(topSheen, surface)))
        .steelBorder(shape)
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

/**
 * Preview: 4 staggered steel cards + an animated gradient chip — dark theme.
 * Background is the Nib ground colour (#070D1C). Wrapped in [NibhausTheme] so
 * `colorScheme.surface` resolves to NavySurface (#142C4D) as in production.
 */
@Preview(
    name = "Nib V3 foundation · dark",
    showBackground = true,
    backgroundColor = 0xFF070D1C,
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
    widthDp = 360,
)
@Composable
private fun PreviewNibFxDark() {
    NibhausTheme(ThemeMode.DARK) {
        Column(
            modifier = Modifier
                .background(Color(0xFF070D1C))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 4 steel cards, each rising in with staggered delay
            repeat(4) { i ->
                Box(
                    modifier = Modifier
                        .riseIn(index = i)
                        .steelCard()
                        .fillMaxWidth()
                        .height(52.dp),
                )
            }

            // Chip-sized box showing the animated ink gradient
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(gradPanBrush())
                    .inkGlow(RoundedCornerShape(20.dp)),
            )
        }
    }
}

/**
 * Preview: 4 steel cards + gradient chip — light theme ("cool paper").
 * Cards show paper-white surface (#FFFFFF), steel border softens to flat `outlineVariant`
 * hairline, shadow is gentler. Gradient chip stays the vivid blue→violet.
 */
@Preview(
    name = "Nib V3 foundation · light",
    showBackground = true,
    backgroundColor = 0xFFF4F6FA,
    uiMode = Configuration.UI_MODE_NIGHT_NO or Configuration.UI_MODE_TYPE_NORMAL,
    widthDp = 360,
)
@Composable
private fun PreviewNibFxLight() {
    NibhausTheme(ThemeMode.LIGHT) {
        Column(
            modifier = Modifier
                .background(Color(0xFFF4F6FA))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            repeat(4) { i ->
                Box(
                    modifier = Modifier
                        .riseIn(index = i)
                        .steelCard()
                        .fillMaxWidth()
                        .height(52.dp),
                )
            }

            // Chip: gradient stays vivid in both themes — "the one bright thing".
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(inkGradient()),
            )
        }
    }
}

/**
 * Preview: static/reduced-motion variant — all cards visible at rest, gradient static.
 * Useful for inspecting layout without animation running.
 */
@Preview(
    name = "Nib V3 foundation · static (no motion)",
    showBackground = true,
    backgroundColor = 0xFF070D1C,
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
    widthDp = 360,
)
@Composable
private fun PreviewNibFxStatic() {
    NibhausTheme(ThemeMode.DARK) {
        Column(
            modifier = Modifier
                .background(Color(0xFF070D1C))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Identical layout; no animation in preview renders because animateTo/infiniteTransition
            // don't run in @Preview (Compose tooling renders one frame). Result shows resting state.
            repeat(4) { i ->
                Box(
                    modifier = Modifier
                        .riseIn(index = i)
                        .steelCard()
                        .fillMaxWidth()
                        .height(52.dp),
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(inkGradient()),
            )
        }
    }
}
