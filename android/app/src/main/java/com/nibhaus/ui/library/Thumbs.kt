package com.nibhaus.ui.library

import android.app.Activity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nibhaus.export.NotebookAccent
import com.nibhaus.export.PageGeometry
import com.nibhaus.export.sheetFrame
import com.nibhaus.data.StrokeEntity
import com.nibhaus.ui.theme.InkTokens
import com.nibhaus.ui.theme.monoData
import com.nibhaus.ui.theme.ncodeDotGrid
import kotlin.math.roundToInt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.nibhaus.ui.InkViewModel
import com.nibhaus.ui.common.InkSurface
import com.nibhaus.ui.common.drawStrokes
import com.nibhaus.ui.common.inkFit
import com.nibhaus.ui.common.rememberCountUp
import com.nibhaus.ui.steelCard

/** Page thumbnail showing the page's real ink (so the library isn't a wall of blank cards). */
@Composable
internal fun PageThumb(
    pageId: String,
    label: String,
    vm: InkViewModel,
    hasAudio: Boolean,
    book: Int?,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    onOpen: () -> Unit,
) {
    val strokes by remember(pageId) { vm.pageStrokes(pageId) }.collectAsStateWithLifecycle(emptyList())
    // Full page frame, not an ink-bbox zoom: same sheetFrame fit InkSurface uses once a notebook's
    // geometry is known (see inkFit's pageBounds branch) — otherwise thumbs auto-zoom to the ink.
    val geometry = remember(book) { vm.pageGeometryFor(book) }
    ThumbBody(label, strokes, vm, hasAudio, geometry, modifier, selected, onLongPress, onOpen = onOpen)
}

/** Notebook thumbnail: a cover drawn from the notebook's most-recently-inked page, plus its
 * page-count / physical-size metadata. [compact] suppresses that text overlay — used
 *  for the small cover thumb in the list-view row, where the text sits beside it instead. */
@Composable
internal fun NotebookThumb(
    notebook: com.nibhaus.data.NotebookEntity,
    vm: InkViewModel,
    pageCount: Int,
    hasAudio: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onOpen: () -> Unit,
) {
    val strokes by remember(notebook.id) { vm.notebookCoverStrokes(notebook.id) }.collectAsStateWithLifecycle(emptyList())
    val geometry = remember(notebook.book) { vm.pageGeometryFor(notebook.book) }
    // the page count counts up on first composition rather than snapping in.
    val animatedPageCount = rememberCountUp(pageCount)
    val meta = notebookMetaLine(animatedPageCount, geometry?.pageWidthMm?.roundToInt(), geometry?.pageHeightMm?.roundToInt())
    // the notebook's chosen accent, if any — a small corner swatch on its cover.
    val accent by remember(notebook.id) { vm.notebookAccent(notebook.id) }.collectAsStateWithLifecycle(NotebookAccent.NONE)
    val accentColor = if (accent == NotebookAccent.NONE) null else Color(accent.argb)
    // Full page frame, not an ink-bbox zoom — same geometry already computed above for the meta line.
    ThumbBody(notebook.title, strokes, vm, hasAudio, geometry, modifier, meta = meta, showLabel = !compact, accentColor = accentColor, onOpen = onOpen)
}

/** List row with a small cover thumb, title, and metadata; denser than the gallery card.
 *  Reuses [NotebookThumb]/[steelCard] styling rather than inventing new chrome. */
@Composable
internal fun NotebookListRow(
    notebook: com.nibhaus.data.NotebookEntity,
    vm: InkViewModel,
    pageCount: Int,
    hasAudio: Boolean,
    onOpen: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val geometry = remember(notebook.book) { vm.pageGeometryFor(notebook.book) }
    // the page count counts up on first composition rather than snapping in.
    val animatedPageCount = rememberCountUp(pageCount)
    val meta = notebookMetaLine(animatedPageCount, geometry?.pageWidthMm?.roundToInt(), geometry?.pageHeightMm?.roundToInt())
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .steelCard(radius = 14.dp)
            .clickable(onClick = onOpen)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NotebookThumb(notebook, vm, pageCount, hasAudio, Modifier.size(56.dp), compact = true) { onOpen() }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(notebook.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(meta, style = monoData, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun ThumbBody(
    label: String,
    strokes: List<StrokeEntity>,
    vm: InkViewModel,
    hasAudio: Boolean,
    pageBounds: PageGeometry? = null,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    meta: String? = null,
    showLabel: Boolean = true,
    accentColor: Color? = null,
    onOpen: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    // V3: steel card (navy fill + brushed-steel border + drop shadow); thumb radius = 15 dp.
    // Selected state adds a 2 dp primary-color inner border on top of the steel border.
    val baseMod = modifier.aspectRatio(1.05f).steelCard(radius = 15.dp)
    Box(
        (if (selected) baseMod.border(2.dp, cs.primary, RoundedCornerShape(15.dp)) else baseMod)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            // #16 TalkBack: always name the thumb, even when showLabel suppresses the visible
            // caption (e.g. the compact cover in a list row, where the title sits beside it instead).
            .semantics { contentDescription = label }
    ) {
        Box(Modifier.fillMaxSize().ncodeDotGrid(InkTokens.dotColor(cs.onBackground), spacing = 13.dp)) {
            if (strokes.isNotEmpty()) {
                // pageBounds set → the full physical sheet frames the canvas (true proportions, no
                // ink-bbox auto-zoom), matching InkSurface's calibrated render.
                Canvas(Modifier.fillMaxSize()) {
                    drawStrokes(strokes, vm::strokesFlowOf, base = cs.onSurface, brandInk = cs.onSurface, pageBounds = pageBounds)
                }
            }
            // a small corner swatch for the notebook's chosen accent color, if any.
            if (accentColor != null) {
                Box(
                    Modifier.align(Alignment.TopStart).padding(6.dp).size(10.dp)
                        .background(accentColor, CircleShape),
                )
            }
            // Soundwave badge → this page/notebook has voice notes tied to it. Bottom-end so it
            // doesn't compete with the page label, which now takes the top-end corner (below).
            if (hasAudio) {
                Surface(
                    shape = CircleShape,
                    color = cs.primaryContainer,
                    shadowElevation = 1.dp,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                ) {
                    Icon(
                        Icons.Outlined.GraphicEq,
                        contentDescription = "Has voice notes",
                        tint = cs.primary,
                        modifier = Modifier.padding(4.dp).size(15.dp),
                    )
                }
            }
            if (showLabel) {
                // Legibility: this label (page number, or the notebook title on a cover) sits directly
                // over the page's real ink, which can be any color in any palette — plain text with no
                // backing becomes illegible wherever the ink behind it is close to the text color,
                // worst on the smaller thumbs. A small opaque-ish chip behind it guarantees contrast in
                // every palette; colors come from the theme, never hardcoded, so it stays correct
                // across the darkest and lightest palettes alike. Top-end (a page-corner number), per
                // the v3 spec, instead of sitting low over the ink.
                Column(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .background(cs.surfaceVariant.copy(alpha = 0.85f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        label,
                        style = monoData,
                        color = cs.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (meta != null) {
                        Text(
                            meta,
                            style = monoData,
                            color = cs.onSurface.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

// ---- Activity (sync/export status) ----
