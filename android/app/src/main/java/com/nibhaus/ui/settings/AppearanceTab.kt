package com.nibhaus.ui.settings

import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nibhaus.export.PaperTemplate
import com.nibhaus.export.StrokeScale
import com.nibhaus.ui.InkViewModel
import com.nibhaus.ui.theme.Palette
import com.nibhaus.ui.theme.Palettes
import com.nibhaus.ui.theme.dynamicPalette
import com.nibhaus.ui.theme.parseCssColor

/** Tab 2 — "Appearance": the light/dark palette pickers, the light-paper-under-dark-theme override,
 *  and the page paper template. A tapped-but-not-yet-applied palette is held in [pendingPalette]
 *  (owned by the shell so the reset-confirm dialog can clear it) until Apply or Preview. */
internal fun LazyListScope.appearanceTab(
    vm: InkViewModel,
    activePalette: Palette,
    lightPaper: Boolean,
    paper: PaperTemplate,
    pendingPalette: String?,
    onPendingPaletteChange: (String?) -> Unit,
    onShowThemePreview: () -> Unit,
) {
    // Material You (#11): a distinct standalone card, not a third row — its swatch is resolved live
    // from the device wallpaper rather than baked into the [Palettes] registry, so it doesn't fit
    // the fixed light/dark rows below. Android 12+ only (dynamicDarkColorScheme/dynamicLightColorScheme).
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        item {
            DynamicPaletteCard(
                active = activePalette.id == Palettes.DYNAMIC_ID,
                pending = pendingPalette == Palettes.DYNAMIC_ID,
                onClick = { onPendingPaletteChange(Palettes.DYNAMIC_ID) },
            )
        }
    }
    item {
        PaletteRow(
            title = "Light Themes",
            palettes = Palettes.light,
            activeId = activePalette.id,
            pendingId = pendingPalette,
            onTap = onPendingPaletteChange,
        )
    }
    item {
        PaletteRow(
            title = "Dark Themes",
            palettes = Palettes.dark,
            activeId = activePalette.id,
            pendingId = pendingPalette,
            onTap = onPendingPaletteChange,
        )
    }
    item {
        val cs = MaterialTheme.colorScheme
        SettingsCard {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 15.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Light Paper", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Keeps your pages easy to read with a light background, even when the rest of the app is dark.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
                // The dynamic palette has no static mode — its actual light/dark follows the live
                // system setting, not a token on the (placeholder) Palette object — so resolve it
                // the same way NibhausTheme does instead of trusting Palettes.byId(...).isDark.
                val systemDark = isSystemInDarkTheme()
                fun isDarkFor(id: String) = if (id == Palettes.DYNAMIC_ID) systemDark else Palettes.byId(id).isDark
                Switch(
                    checked = lightPaper,
                    onCheckedChange = vm::setLightPaper,
                    enabled = pendingPalette?.let(::isDarkFor) ?: isDarkFor(activePalette.id),
                )
            }
        }
    }
    if (pendingPalette != null && pendingPalette != activePalette.id) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onShowThemePreview,
                    modifier = Modifier.weight(1f),
                ) { Text("Preview") }
                Button(
                    onClick = { vm.setPalette(pendingPalette); onPendingPaletteChange(null) },
                    modifier = Modifier.weight(1f),
                ) { Text("Apply") }
            }
        }
    }
    item {
        SettingsCard {
            DropdownRow(
                title = "Paper",
                desc = "The background every page shows behind your ink",
                current = paper.label,
                options = PaperTemplate.entries,
                optionLabel = { it.label },
                onPick = vm::setPaperTemplate,
            )
        }
    }
    item {
        val strokeScale by vm.strokeScale.collectAsStateWithLifecycle()
        SettingsCard {
            StrokeScaleRow(current = strokeScale, onPick = vm::setStrokeScale)
        }
    }
}

/** Handwriting size preset (#15b): a stroke-width multiplier for live capture + every in-app page
 *  render (applied at InkSurface's single width source, [com.nibhaus.ui.common.strokeBaseWidthPx]).
 *  A row of three choices, per the design brief, rather than a dropdown — the option set is small
 *  and fixed, so all three read at a glance. */
@Composable
private fun StrokeScaleRow(current: StrokeScale, onPick: (StrokeScale) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 15.dp)) {
        Text("Handwriting size", style = MaterialTheme.typography.titleMedium)
        Text(
            "How thick your ink looks while writing and reading pages",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StrokeScale.entries.forEach { option ->
                FilterChip(
                    selected = option == current,
                    onClick = { onPick(option) },
                    label = { Text(option.label) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** A titled, horizontally-scrollable row of [PaletteCard]s — one of the two rows (light/dark) in the
 *  Appearance tab's palette picker. */
@Composable
private fun PaletteRow(
    title: String,
    palettes: List<Palette>,
    activeId: String,
    pendingId: String?,
    onTap: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(palettes, key = { it.id }) { palette ->
                PaletteCard(
                    palette = palette,
                    active = palette.id == activeId,
                    pending = palette.id == pendingId,
                    onClick = { onTap(palette.id) },
                )
            }
        }
    }
}

/** Standalone "Match my wallpaper" card (#11, Android 12+): a full-width row (not a swatch tile in
 *  the light/dark rows, since its colors aren't in the [Palettes] registry) with a live-resolved
 *  swatch pulled from [dynamicPalette] so the preview genuinely reflects the current wallpaper, not
 *  a static stand-in. Tapping it feeds [Palettes.DYNAMIC_ID] into the same pending/Preview/Apply
 *  flow every other palette card uses — no separate wiring. */
@Composable
@androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
private fun DynamicPaletteCard(active: Boolean, pending: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val swatch = remember(systemDark) { dynamicPalette(context, dark = systemDark) }
    val swatchColors = remember(swatch) {
        listOf(parseCssColor(swatch.base), parseCssColor(swatch.support), parseCssColor(swatch.accent))
    }
    val ringColor = if (pending) cs.primary else if (active) cs.outline else Color.Transparent
    val ringWidth = if (pending) 3.dp else 1.5.dp
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(cs.surfaceVariant)
            .border(ringWidth, ringColor, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            swatchColors.forEach { color ->
                Box(
                    Modifier.size(16.dp).clip(CircleShape).background(color)
                        .border(1.dp, cs.outlineVariant, CircleShape),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Match my wallpaper", style = MaterialTheme.typography.titleMedium)
            Text(
                "Colors pulled from your device wallpaper",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
        }
        if (active) {
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = "Active theme",
                tint = cs.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** One palette tile: a tri-swatch of base/support/accent, the palette name, and a ring that marks
 *  the currently-active palette (thin) or the tapped-but-not-yet-applied one (thick, primary).
 *  Presses scale the card down slightly and spring back on release (Material Expressive). */
@Composable
private fun PaletteCard(palette: Palette, active: Boolean, pending: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ringColor = if (pending) cs.primary else if (active) cs.outline else Color.Transparent
    val ringWidth = if (pending) 3.dp else 1.5.dp
    // Parsing the CSS-string swatch tokens is pure work per palette; remember it keyed on the
    // palette id so it isn't re-parsed on every recomposition (e.g. ring color changes).
    val swatchColors = remember(palette.id) {
        listOf(parseCssColor(palette.base), parseCssColor(palette.support), parseCssColor(palette.accent))
    }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "paletteCardScale",
    )
    Box(modifier = Modifier.width(92.dp).scale(scale)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(cs.surfaceVariant)
                .border(ringWidth, ringColor, RoundedCornerShape(14.dp))
                .clickable(interactionSource = interactionSource, indication = LocalIndication.current, onClick = onClick)
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                swatchColors.forEach { color ->
                    Box(
                        Modifier.size(16.dp).clip(CircleShape).background(color)
                            .border(1.dp, cs.outlineVariant, CircleShape),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                palette.name,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (active) {
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = "Active theme",
                tint = cs.primary,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(14.dp),
            )
        }
    }
}
