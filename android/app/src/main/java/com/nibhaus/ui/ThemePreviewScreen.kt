package com.nibhaus.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nibhaus.ui.theme.LocalInkPaper
import com.nibhaus.ui.theme.Palette
import com.nibhaus.ui.theme.Palettes
import com.nibhaus.ui.theme.inkPath
import com.nibhaus.ui.theme.monoData

/**
 * Read-only preview of a color [Palette] (design-system §12): a nested [NibhausTheme] "island" that
 * renders a representative, static slice of the app in the PENDING palette — independent of whatever
 * palette is currently active app-wide. Lets the user see a real theme before committing to it.
 *
 * All content here is baked sample data (no [InkViewModel], no repo reads) — this screen never
 * touches real notebooks/pens/strokes, so browsing it can't have side effects. The Settings palette
 * picker (a later task) is responsible for launching this and wiring [onClose].
 */
@Composable
fun ThemePreviewScreen(palette: Palette, lightPaper: Boolean, onClose: () -> Unit) {
    NibhausTheme(palette, lightPaper) {
        ThemePreviewContent(onClose = onClose)
    }
}

@Composable
private fun ThemePreviewContent(onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(cs.background)) {
        // Faux capture header — echoes InkAppBar's title/back layout without touching Screens.kt.
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 20.dp, top = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Close preview", tint = cs.onSurface)
            }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(
                    "Field Notes, Vol. 3",
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Eyebrow("THEME PREVIEW")
            }
        }
        BandDivider()

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.riseIn(0)) { MockPenStatusCard() }
            Box(Modifier.riseIn(1)) { MockInkIllustration() }
            Column(Modifier.riseIn(2), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Eyebrow("RECENT PAGES")
                MockNoteRow("Kickoff meeting notes", "3 pages · synced")
                MockNoteRow("Sketch: garden layout", "1 page · not synced")
                MockNoteRow("Reading list", "2 pages · synced")
            }
        }
    }
}

/** Pen-status card mock (echoes the real PenStatusCard's shape, static "Connected" sample). */
@Composable
private fun MockPenStatusCard() {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().steelCard(radius = 18.dp).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).background(cs.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(9.dp).background(cs.primary, CircleShape))
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text("Neo Smartpen", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
            Text("CONNECTED", style = monoData, color = cs.primary)
            Text("Receiving ink", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.BatteryFull, contentDescription = null, tint = cs.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("82%", style = monoData, color = cs.onSurface)
        }
    }
}

/**
 * A small ink illustration: baked sample strokes over the page ["paper"][LocalInkPaper]. Every stroke
 * uses the paper's own ink color — real color-0 ink renders from `paper.ink`/`onSurface`, never from
 * `InkExtras.inkLine` (that token isn't wired into any live render path; see design-spec §2's
 * deferral note), so this preview must show only ink the applied theme actually delivers. The
 * "brand" line is drawn at full alpha, the other two at reduced alpha, to keep the illustration's
 * visual hierarchy without implying a second, unused ink color.
 */
@Composable
private fun MockInkIllustration() {
    val paper = LocalInkPaper.current
    Canvas(
        Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(14.dp)).background(paper.surface),
    ) {
        fun pt(xFrac: Float, yFrac: Float) = Offset(size.width * xFrac, size.height * yFrac)

        // Baked "handwriting" sample lines — static design-time points, not real strokes.
        val line1 = listOf(pt(0.08f, 0.30f), pt(0.20f, 0.22f), pt(0.34f, 0.34f), pt(0.46f, 0.24f), pt(0.60f, 0.32f))
        val line2 = listOf(pt(0.10f, 0.52f), pt(0.28f, 0.46f), pt(0.42f, 0.58f), pt(0.58f, 0.48f), pt(0.72f, 0.56f), pt(0.84f, 0.50f))
        val line3 = listOf(pt(0.10f, 0.74f), pt(0.24f, 0.70f), pt(0.36f, 0.80f), pt(0.50f, 0.72f))

        // Color-0 / "brand" stroke — full-alpha paper ink, for hierarchy against the other two lines.
        drawPath(inkPath(line2), color = paper.ink, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
        // Plain-ink strokes, at reduced alpha, for contrast against the brand line.
        drawPath(inkPath(line1), color = paper.ink.copy(alpha = 0.55f), style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round))
        drawPath(inkPath(line3), color = paper.ink.copy(alpha = 0.40f), style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))

        // A short underline accent, drawn with drawLine rather than a smoothed path.
        drawLine(
            color = paper.ink.copy(alpha = 0.5f),
            start = pt(0.08f, 0.88f),
            end = pt(0.30f, 0.88f),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

/** One mock "note" row — title + subtitle, themed colors only (no click target, purely illustrative). */
@Composable
private fun MockNoteRow(title: String, subtitle: String) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(cs.primary, CircleShape))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------------------
// Design-time preview
// ---------------------------------------------------------------------------

@Preview(name = "Theme preview · Midnight Electric (dark)", showBackground = true)
@Composable
private fun ThemePreviewScreenPreview() {
    ThemePreviewScreen(Palettes.byId("D01"), lightPaper = false, onClose = {})
}

@Preview(name = "Theme preview · Porcelain Sapphire (light)", showBackground = true)
@Composable
private fun ThemePreviewScreenPreviewLight() {
    ThemePreviewScreen(Palettes.byId("L01"), lightPaper = false, onClose = {})
}

/** Dark chrome + light ("cream") paper — exercises the dark-palette lightPaper=true branch
 *  ([com.nibhaus.ui.theme.inkPaperFor]'s fallback to the warm cream page). */
@Preview(name = "Theme preview · Midnight Electric (dark, light paper)", showBackground = true)
@Composable
private fun ThemePreviewScreenPreviewLightPaper() {
    ThemePreviewScreen(Palettes.byId("D01"), lightPaper = true, onClose = {})
}
