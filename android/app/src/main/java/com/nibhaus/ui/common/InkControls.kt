package com.nibhaus.ui.common

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.nibhaus.data.RecordingEntity
import com.nibhaus.pen.BatteryStatus
import com.nibhaus.ui.theme.monoData
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** A battery glyph with a proportional fill + percent; optionally the charge time to full. */
@Composable
internal fun BatteryBadge(status: BatteryStatus, showEta: Boolean = false, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val pct = status.percent.coerceIn(0, 100)
    val fill = when {
        pct <= 15 -> cs.error
        pct <= 35 -> cs.tertiary
        else -> cs.primary
    }
    val outline = cs.onSurfaceVariant
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = 26.dp, height = 13.dp)) {
            val nub = 2.dp.toPx()
            val bodyW = size.width - nub
            val sw = 1.5.dp.toPx()
            val r = CornerRadius(2.dp.toPx(), 2.dp.toPx())
            val rIn = CornerRadius(1.dp.toPx(), 1.dp.toPx())
            drawRoundRect(outline, Offset(sw / 2, sw / 2), Size(bodyW - sw, size.height - sw), r, style = Stroke(sw))
            drawRoundRect(outline, Offset(bodyW, size.height * 0.3f), Size(nub, size.height * 0.4f), rIn)
            val pad = sw + 1.dp.toPx()
            val innerW = (bodyW - 2 * pad).coerceAtLeast(0f)
            drawRoundRect(fill, Offset(pad, pad), Size(innerW * (pct / 100f), size.height - 2 * pad), rIn)
        }
        Spacer(Modifier.width(6.dp))
        Text("$pct%", style = monoData, color = cs.onSurface)
        val eta = status.chargeEtaMinutes
        if (showEta && eta != null) {
            Spacer(Modifier.width(6.dp))
            Text("· ${formatEta(eta)} to full", style = monoData, color = cs.primary)
        }
    }
}

private fun formatEta(min: Int): String = if (min < 60) "${min}m" else "${min / 60}h ${min % 60}m"

/** Ink colors for live capture (0 = brand ink, rendered in the theme primary). Mid-tones so they
 *  read on both light and dark. Picked at will to organize notes by color. */
private val INK_PALETTE = listOf(
    0,                    // default ink — theme foreground (white in dark, black in light)
    0xFF3B82F6.toInt(),   // blue
    0xFF22A06B.toInt(),   // green
    0xFFD6453D.toInt(),   // red
    0xFF8B5CF6.toInt(),   // purple
    0xFFB5872E.toInt(),   // amber
)

/** Writing/stroke widths offered by the size picker (multiplier on the base ink width). */
private val INK_SIZES = listOf("Fine" to 0.7f, "Medium" to 1.0f, "Large" to 1.6f)

private fun nearly(a: Float, b: Float) = kotlin.math.abs(a - b) < 0.05f

/**
 * Collapsed-by-default color picker: shows the current color as one dot; tapping animates open to
 * the full palette; picking a color collapses back to the single dot in the new color. [onColor] is
 * the foreground used for the default (color 0) swatch + selection ring (so it reads on dark bars).
 */
@Composable
internal fun ExpandingColorPicker(
    selected: Int,
    onColor: Color,
    onSelect: (Int) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier.animateContentSize(animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow)),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (open) {
            INK_PALETTE.forEach { c -> ColorDot(c, onColor, sel = c == selected, enabled = enabled) { onSelect(c); open = false } }
        } else {
            ColorDot(selected, onColor, sel = false, enabled = enabled) { if (enabled) open = true }
        }
    }
}

@Composable
private fun ColorDot(color: Int, onColor: Color, sel: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val display = if (color == 0) onColor else Color(color) // 0 = default/theme ink
    // The clickable sits on a full 48dp touch target (a11y minimum); the painted dot inside stays
    // the original 22/26dp so the picker doesn't visually balloon.
    Box(
        Modifier.size(48.dp).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(if (sel) 26.dp else 22.dp)
                .alpha(if (enabled) 1f else 0.5f)
                .background(display, CircleShape)
                .then(if (sel) Modifier.border(2.dp, onColor, CircleShape) else Modifier),
        )
    }
}

/** Collapsed-by-default size picker (Fine/Medium/Large), same expand/collapse behaviour as colors. */
@Composable
internal fun ExpandingSizePicker(
    selected: Float,
    onColor: Color,
    onSelect: (Float) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier.animateContentSize(animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow)),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (open) {
            INK_SIZES.forEach { (label, w) ->
                SizeChip(label, w, onColor, sel = nearly(w, selected), enabled = enabled) { onSelect(w); open = false }
            }
        } else {
            val label = INK_SIZES.firstOrNull { nearly(it.second, selected) }?.first ?: "Medium"
            SizeChip(label, selected, onColor, sel = false, enabled = enabled) { if (enabled) open = true }
        }
    }
}

@Composable
private fun SizeChip(label: String, width: Float, onColor: Color, sel: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    // Clickable lives on an outer 48dp-tall (a11y minimum) touch target; the pill itself — clip,
    // background, padding — is unchanged, just centered inside the taller invisible target.
    Box(
        Modifier.defaultMinSize(minHeight = 48.dp).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(14.dp))
                .background(if (sel) cs.primary.copy(alpha = 0.22f) else Color.Transparent)
                .alpha(if (enabled) 1f else 0.5f)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size((5 + width * 6).dp).background(onColor, CircleShape)) // dot hints the weight
            Text(label, style = MaterialTheme.typography.labelLarge, color = onColor)
        }
    }
}

/** A note's display name: its user label, or "Note N" by position. */
internal fun recordingName(r: RecordingEntity, index: Int): String =
    r.title.ifBlank { "Note ${index + 1}" }

/** Rename a voice note. */
@Composable
internal fun RenameDialog(
    dialogTitle: String,
    fieldLabel: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dialogTitle) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(fieldLabel) },
            )
        },
        confirmButton = { Button(onClick = { onConfirm(text.trim()) }) { Text("Save") } },
        dismissButton = { Button(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** m:ss for a duration in ms. */
internal fun formatClock(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
