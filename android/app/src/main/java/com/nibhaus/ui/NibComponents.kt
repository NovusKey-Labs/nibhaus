package com.nibhaus.ui

import android.content.res.Configuration
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nibhaus.export.ThemeMode
import com.nibhaus.ui.theme.G2
import com.nibhaus.ui.theme.LocalInkExtras
import com.nibhaus.ui.theme.monoEyebrow

// ─────────────────────────────────────────────────────────────────────────────
// NavItem — data carrier for NibBottomBar
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A single bottom-nav destination. [icon] is any [ImageVector] from material-icons (or custom);
 * [label] is the short string shown below the icon (e.g. "Pens", "Library", "Activity").
 */
data class NavItem(val icon: ImageVector, val label: String)

// ─────────────────────────────────────────────────────────────────────────────
// 1. StatusChip
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Pill-shaped status indicator — animated ink gradient with glow, Inter Medium 12.5 sp white text.
 * Usage: `StatusChip("CONNECTED")`, `StatusChip("LIVE")`.
 *
 * Shape: `RoundedCornerShape(50)` (stadium). Padding: 7 dp vertical × 15 dp horizontal.
 * Background: [gradPanBrush] (animated; falls back to static on reduced motion).
 * Glow: [inkGlow] dark-only 22 dp indigo halo.
 */
@Composable
fun StatusChip(
    text: String,
    modifier: Modifier = Modifier,
) {
    val chipShape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .inkGlow(chipShape)
            .clip(chipShape)
            .background(gradPanBrush())
            .padding(vertical = 7.dp, horizontal = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.Medium,
                fontSize = 12.5.sp,
                letterSpacing = 0.sp,
                color = Color.White,
            ),
        )
    }
}

@Preview(
    name = "StatusChip · dark",
    showBackground = true,
    backgroundColor = 0xFF070D1C,
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
)
@Composable
private fun PreviewStatusChip() {
    NibhausTheme(ThemeMode.DARK) {
        Row(
            modifier = Modifier
                .background(Color(0xFF070D1C))
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatusChip("CONNECTED")
            StatusChip("LIVE")
        }
    }
}

/** Light variant: gradient stays vivid; glow absent (light-only behavior of [inkGlow]). */
@Preview(
    name = "StatusChip · light",
    showBackground = true,
    backgroundColor = 0xFFF4F6FA,
    uiMode = Configuration.UI_MODE_NIGHT_NO or Configuration.UI_MODE_TYPE_NORMAL,
)
@Composable
private fun PreviewStatusChipLight() {
    NibhausTheme(ThemeMode.LIGHT) {
        Row(
            modifier = Modifier
                .background(Color(0xFFF4F6FA))
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatusChip("CONNECTED")
            StatusChip("LIVE")
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 2. MonoBadge
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Monospace telemetry badge — IBM Plex Mono 10 sp, letter-spacing +1.2 sp, UPPERCASE, white on an
 * animated ink gradient pill with a 7 dp corner radius.
 *
 * [leadingDot] renders a small filled circle (4 dp) before the text — useful as a live indicator.
 * Examples: "VERBATIM · VAULT-LOCAL", "AES-LOCKED", "NEO1-V1 · LOCAL · 0.3S · 14 WORDS".
 *
 * Shape: `RoundedCornerShape(7dp)`. Padding: 4 dp vertical × 10 dp horizontal. Gap: 6 dp.
 */
@Composable
fun MonoBadge(
    text: String,
    modifier: Modifier = Modifier,
    leadingDot: Boolean = false,
) {
    val badgeShape = RoundedCornerShape(7.dp)
    Row(
        modifier = modifier
            .inkGlow(badgeShape)
            .clip(badgeShape)
            .background(gradPanBrush())
            .padding(vertical = 4.dp, horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (leadingDot) {
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }
        Text(
            text = text.uppercase(),
            style = monoEyebrow.copy(
                letterSpacing = 1.2.sp,
                color = Color.White,
            ),
        )
    }
}

@Preview(
    name = "MonoBadge · dark",
    showBackground = true,
    backgroundColor = 0xFF070D1C,
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
)
@Composable
private fun PreviewMonoBadge() {
    NibhausTheme(ThemeMode.DARK) {
        Column(
            modifier = Modifier
                .background(Color(0xFF070D1C))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MonoBadge("VERBATIM · VAULT-LOCAL")
            MonoBadge("AES-LOCKED", leadingDot = true)
            MonoBadge("NEO1-V1 · LOCAL · 0.3S · 14 WORDS")
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 3. Eyebrow
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Section label — IBM Plex Mono 10 sp, letter-spacing +2.0 sp, UPPERCASE.
 * Color is `colorScheme.onSurfaceVariant` — steel-gray in both dark (`#8B98B6` / Slate)
 * and light (`#5B6884` / DaySlate), so it reads correctly on either ground.
 * No background — purely typographic. Usage: `Eyebrow("RECENT PAGES")`, `Eyebrow("SYNC")`.
 */
@Composable
fun Eyebrow(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        style = monoEyebrow.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
    )
}

@Preview(
    name = "Eyebrow · dark",
    showBackground = true,
    backgroundColor = 0xFF070D1C,
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
)
@Composable
private fun PreviewEyebrow() {
    NibhausTheme(ThemeMode.DARK) {
        Column(
            modifier = Modifier
                .background(Color(0xFF070D1C))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Eyebrow("RECENT PAGES")
            Eyebrow("SYNC")
            Eyebrow("CONNECTED PENS")
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 4. NibToggle
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Drop-in `Switch` replacement that matches the V3 design language.
 *
 * Track: 44 × 25 dp, `RoundedCornerShape(14 dp)`.
 *  - ON  → animated ink [gradPanBrush] + indigo glow (dark only).
 *  - OFF → steel hairline `#223863`.
 * Thumb: white 19 dp circle that slides with [NibEasing] over ~220 ms.
 *
 * Use anywhere [Switch] is used in Settings — same checked/onCheckedChange contract.
 */
@Composable
fun NibToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val trackShape = RoundedCornerShape(14.dp)
    val trackW = 44.dp
    val trackH = 25.dp
    val thumbDiameter = 19.dp
    val thumbPad = 3.dp

    val thumbOffset by animateDpAsState(
        targetValue = if (checked) trackW - thumbDiameter - thumbPad else thumbPad,
        animationSpec = tween(durationMillis = 220, easing = NibEasing),
        label = "toggleThumb",
    )

    // Compute brushes unconditionally so no composable call-order changes when checked flips.
    // OFF track: outlineVariant — dark-navy hairline (#223863) in dark, soft steel (#E4E9F2) in light.
    val panBrush = gradPanBrush()
    val offColor = MaterialTheme.colorScheme.outlineVariant
    val offBrush = Brush.linearGradient(colors = listOf(offColor, offColor))
    val trackBrush = if (checked) panBrush else offBrush

    // Glow: inline the shadow logic to conditionally apply only when ON (dark-only, keyed off the
    // active palette, not the OS; previews render with the default LocalInkExtras — D01, isDark =
    // true — so dark previews still show the glow).
    val extras = LocalInkExtras.current
    val glowMod: Modifier = if (checked && extras.isDark) {
        Modifier.shadow(
            elevation = 22.dp,
            shape = trackShape,
            clip = false,
            ambientColor = extras.glow.copy(alpha = 0.4f),
            spotColor = extras.glow.copy(alpha = 0.4f),
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .size(trackW, trackH)
            .then(glowMod)
            .clip(trackShape)
            .background(trackBrush)
            .semantics {
                stateDescription = if (checked) "On" else "Off"
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Switch,
                onClick = { onCheckedChange(!checked) },
            ),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = thumbOffset)
                .size(thumbDiameter)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

@Preview(
    name = "NibToggle · dark",
    showBackground = true,
    backgroundColor = 0xFF070D1C,
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
)
@Composable
private fun PreviewNibToggle() {
    NibhausTheme(ThemeMode.DARK) {
        Row(
            modifier = Modifier
                .background(Color(0xFF070D1C))
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NibToggle(checked = true, onCheckedChange = {})
            NibToggle(checked = false, onCheckedChange = {})
        }
    }
}

/**
 * Light variant: ON track stays vivid gradient; OFF track shows day-paper outlineVariant (#E4E9F2)
 * — a subtle steel hairline on white, not the dark-navy block.
 */
@Preview(
    name = "NibToggle · light",
    showBackground = true,
    backgroundColor = 0xFFF4F6FA,
    uiMode = Configuration.UI_MODE_NIGHT_NO or Configuration.UI_MODE_TYPE_NORMAL,
)
@Composable
private fun PreviewNibToggleLight() {
    NibhausTheme(ThemeMode.LIGHT) {
        Row(
            modifier = Modifier
                .background(Color(0xFFF4F6FA))
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NibToggle(checked = true, onCheckedChange = {})
            NibToggle(checked = false, onCheckedChange = {})
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 5. NibFab
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Nib floating action button — 58 dp squircle (`RoundedCornerShape(20 dp)`, NOT circular),
 * animated ink gradient, a two-layer lifted indigo glow, and a ±4 dp float loop gated on
 * [rememberReducedMotion].
 *
 * Default [content] is a white "+" [Icons.Filled.Add] icon. Swap for custom icons or labels.
 *
 * Glow layers (dark only):
 *  - Outer lifted: `#5B5BF6 @0.6`, elevation 30 dp (maps CSS `0 14 30 -8` halo).
 *  - Inner ambient: `#5B5BF6 @0.4`, elevation 22 dp ([inkGlow] equivalent).
 */
@Composable
fun NibFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = "Add",
            tint = Color.White,
        )
    },
) {
    val fabShape = RoundedCornerShape(20.dp)

    // Float animation — always set up, gated by gating the target value so it's free of
    // composable-call-order changes when reduced-motion setting flips.
    val reduced = rememberReducedMotion()
    val floatTrans = rememberInfiniteTransition(label = "fabFloat")
    val floatYDp by floatTrans.animateFloat(
        initialValue = 0f,
        targetValue = if (reduced) 0f else -4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3_800, easing = NibEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "float",
    )

    val panBrush = gradPanBrush()

    Box(
        modifier = modifier
            .graphicsLayer { translationY = floatYDp.dp.toPx() }
            // Outer lifted glow: CSS `box-shadow: 0 14px 30px -8px #5B5BF6@0.6`
            .shadow(
                elevation = 30.dp,
                shape = fabShape,
                clip = false,
                ambientColor = G2.copy(alpha = 0.6f),
                spotColor = G2.copy(alpha = 0.6f),
            )
            // Inner ambient inkGlow (consistent with chips/badges)
            .inkGlow(fabShape)
            .clip(fabShape)
            .size(58.dp)
            .background(panBrush)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Preview(
    name = "NibFab · dark",
    showBackground = true,
    backgroundColor = 0xFF070D1C,
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
)
@Composable
private fun PreviewNibFab() {
    NibhausTheme(ThemeMode.DARK) {
        Box(
            modifier = Modifier
                .background(Color(0xFF070D1C))
                .padding(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            NibFab(onClick = {})
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 6. NibBottomBar
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Custom bottom navigation bar replacing Material3 `NavigationBar`. Height 66 dp.
 *
 * Structure:
 *  - 1 dp steel hairline (`#223863`) at the top edge.
 *  - Background: vertical gradient `transparent → #081026 @0.3` (frosted-glass bottom-fade).
 *  - Each item: icon (24 dp) stacked over label, 4 dp gap, Inter SemiBold 10 sp.
 *
 * Selected item:
 *  - Icon tinted by the canonical ink gradient via `drawWithContent` + `BlendMode.SrcIn`.
 *  - Label color: `colorScheme.onSurface` (near-white in dark, navy in light).
 * Unselected item:
 *  - Icon tint: `colorScheme.onSurfaceVariant` (steel-gray in both themes).
 *  - Label color: `colorScheme.onSurfaceVariant`.
 *
 * [items] order matches visual left→right order. [selected] is a zero-based index.
 */
@Composable
fun NibBottomBar(
    items: List<NavItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Bottom-fade brush: transparent at top, 30% background tint at bottom — fades into whatever
    // ground is behind (deep navy in dark; day-paper in light).
    val bgFade = MaterialTheme.colorScheme.background.copy(alpha = 0.3f)
    val bgBrush = Brush.verticalGradient(
        colors = listOf(Color.Transparent, bgFade),
    )
    // Steel hairline: dark-navy divider in dark (#223863), soft outline in light (#E4E9F2).
    val hairlineColor = MaterialTheme.colorScheme.outlineVariant
    val unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant
    val selectedLabelColor = MaterialTheme.colorScheme.onSurface

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(66.dp)
            .background(bgBrush),
    ) {
        // Top hairline — drawn first (bottom of z-stack) so it's behind the content row.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(hairlineColor)
                .align(Alignment.TopStart),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(66.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                val isSelected = index == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .semantics(mergeDescendants = true) {
                            role = Role.Tab
                            this.selected = isSelected
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Tab,
                            onClick = { onSelect(index) },
                        )
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Gradient-tinted icon when selected: draw content then overlay gradient SrcIn.
                    // Gradient stays vivid in both themes ("the one bright thing").
                    Icon(
                        imageVector = item.icon,
                        contentDescription = null,
                        tint = if (isSelected) Color.White else unselectedColor,
                        modifier = Modifier
                            .size(24.dp)
                            .then(
                                if (isSelected) {
                                    Modifier
                                        .graphicsLayer {
                                            compositingStrategy = CompositingStrategy.Offscreen
                                        }
                                        .drawWithContent {
                                            drawContent()
                                            drawRect(
                                                brush = inkGradient(),
                                                blendMode = BlendMode.SrcIn,
                                            )
                                        }
                                } else {
                                    Modifier
                                }
                            ),
                    )
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 10.sp,
                            letterSpacing = 0.sp,
                            color = if (isSelected) selectedLabelColor else unselectedColor,
                        ),
                    )
                }
            }
        }
    }
}

@Preview(
    name = "NibBottomBar · dark",
    showBackground = true,
    backgroundColor = 0xFF070D1C,
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
    widthDp = 360,
)
@Composable
private fun PreviewNibBottomBar() {
    val items = listOf(
        NavItem(Icons.Filled.Edit, "Pens"),
        NavItem(Icons.AutoMirrored.Filled.LibraryBooks, "Library"),
        NavItem(Icons.Filled.Notifications, "Activity"),
    )
    NibhausTheme(ThemeMode.DARK) {
        Box(modifier = Modifier.background(Color(0xFF070D1C))) {
            NibBottomBar(
                items = items,
                selected = 1,
                onSelect = {},
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 7. BandDivider
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Section-band divider — 1 dp horizontal line with a left-anchored fade:
 * `linearGradient(90°, outlineVariant → transparent)`.
 * Dark: `#223863` (steel-dark); light: `#E4E9F2` (day hairline) — reads on either ground.
 *
 * Fills its horizontal extent ([Modifier.fillMaxWidth] by default). Use between section groups in
 * lists, settings pages, or detail screens.
 */
@Composable
fun BandDivider(modifier: Modifier = Modifier) {
    val dividerColor = MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    colors = listOf(dividerColor, Color.Transparent),
                ),
            ),
    )
}

@Preview(
    name = "BandDivider · dark",
    showBackground = true,
    backgroundColor = 0xFF070D1C,
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
    widthDp = 360,
)
@Composable
private fun PreviewBandDivider() {
    NibhausTheme(ThemeMode.DARK) {
        Column(
            modifier = Modifier
                .background(Color(0xFF070D1C))
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Eyebrow("RECENT PAGES", modifier = Modifier.padding(horizontal = 20.dp))
            BandDivider()
            Eyebrow("SYNC", modifier = Modifier.padding(horizontal = 20.dp))
            BandDivider()
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Combined showcase preview
// ─────────────────────────────────────────────────────────────────────────────

@Preview(
    name = "Nib V3 component kit · dark",
    showBackground = true,
    backgroundColor = 0xFF070D1C,
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
    widthDp = 360,
    heightDp = 680,
)
@Composable
private fun PreviewNibComponentKit() {
    val navItems = listOf(
        NavItem(Icons.Filled.Edit, "Pens"),
        NavItem(Icons.AutoMirrored.Filled.LibraryBooks, "Library"),
        NavItem(Icons.Filled.Notifications, "Activity"),
    )
    NibhausTheme(ThemeMode.DARK) {
        Box(
            modifier = Modifier
                .background(Color(0xFF070D1C))
                .fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Eyebrow("STATUS")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusChip("CONNECTED")
                    StatusChip("LIVE")
                }

                BandDivider()

                Eyebrow("TELEMETRY")
                MonoBadge("VERBATIM · VAULT-LOCAL")
                MonoBadge("AES-LOCKED", leadingDot = true)

                BandDivider()

                Eyebrow("SETTINGS")
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NibToggle(checked = true, onCheckedChange = {})
                    NibToggle(checked = false, onCheckedChange = {})
                }

                BandDivider()

                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    NibFab(onClick = {})
                }
            }

            NibBottomBar(
                items = navItems,
                selected = 0,
                onSelect = {},
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
    }
}

/**
 * Light-theme showcase — the full kit on cool paper:
 *  - Paper-white cards, navy text, steel hairlines, vivid gradient accents.
 *  - Eyebrow: DaySlate (#5B6884).  Toggle OFF: day outlineVariant (#E4E9F2).
 *  - Gradient chips, badges, FAB, active nav: still vivid blue→violet (the one bright thing).
 */
@Preview(
    name = "Nib V3 component kit · light",
    showBackground = true,
    backgroundColor = 0xFFF4F6FA,
    uiMode = Configuration.UI_MODE_NIGHT_NO or Configuration.UI_MODE_TYPE_NORMAL,
    widthDp = 360,
    heightDp = 680,
)
@Composable
private fun PreviewNibComponentKitLight() {
    val navItems = listOf(
        NavItem(Icons.Filled.Edit, "Pens"),
        NavItem(Icons.AutoMirrored.Filled.LibraryBooks, "Library"),
        NavItem(Icons.Filled.Notifications, "Activity"),
    )
    NibhausTheme(ThemeMode.LIGHT) {
        Box(
            modifier = Modifier
                .background(Color(0xFFF4F6FA))
                .fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Eyebrow("STATUS")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusChip("CONNECTED")
                    StatusChip("LIVE")
                }

                BandDivider()

                Eyebrow("TELEMETRY")
                MonoBadge("VERBATIM · VAULT-LOCAL")
                MonoBadge("AES-LOCKED", leadingDot = true)

                BandDivider()

                Eyebrow("SETTINGS")
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NibToggle(checked = true, onCheckedChange = {})
                    NibToggle(checked = false, onCheckedChange = {})
                }

                BandDivider()

                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    NibFab(onClick = {})
                }
            }

            NibBottomBar(
                items = navItems,
                selected = 0,
                onSelect = {},
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
    }
}
