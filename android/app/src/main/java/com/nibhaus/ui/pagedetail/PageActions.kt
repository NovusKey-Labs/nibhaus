package com.nibhaus.ui.pagedetail

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.print.PrintHelper
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nibhaus.data.Point
import com.nibhaus.export.PageGeometry
import com.nibhaus.export.PageStyle
import com.nibhaus.export.Ruling
import com.nibhaus.data.StrokeEntity
import com.nibhaus.share.PageRender
import com.nibhaus.ui.theme.monoData
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.nibhaus.ui.InkViewModel

/**
 * Render a page's ink to a white A4-ratio bitmap and hand it to the system print dialog. We reuse
 * the same auto-fit math as the on-screen canvas and the SVG export, so the print matches what the
 * user sees: ink centered, aspect kept, brand ink (color 0) printed black on white paper.
 *
 * Note: PrintHelper rasterizes one page to a single bitmap — fine for a handwriting page. The
 * upgrade path (multi-page / true vector print) is a PrintDocumentAdapter, only worth it if asked.
 */
internal fun printPage(
    context: android.content.Context,
    jobName: String,
    strokes: List<StrokeEntity>,
    points: (StrokeEntity) -> List<Point>,
    bounds: PageGeometry? = null,
    ruling: Ruling? = null,
    pageNumber: Int? = null,
    pageStyle: PageStyle = PageStyle.LINED,
    blackInk: Boolean = false,
    strokeScale: Float = 1f, // the Fine/Normal/Bold handwriting-size preset (#15b) — see PageRender.renderPage.
) {
    if (strokes.isEmpty()) return
    val bmp = PageRender.renderPage(
        strokes, points,
        bounds = bounds, ruling = ruling, pageNumber = pageNumber, pageStyle = pageStyle, blackInk = blackInk,
        strokeScale = strokeScale,
    ) ?: return
    PrintHelper(context).apply { scaleMode = PrintHelper.SCALE_MODE_FIT }.printBitmap(jobName, bmp)
}

/** A page's tag chips: tap a chip to remove it, "+ Tag" to add one (Phase E). */
@Composable
internal fun TagRow(pageId: String, vm: InkViewModel) {
    val tags by remember(pageId) { vm.tagsForPage(pageId) }.collectAsStateWithLifecycle(emptyList())
    val cs = MaterialTheme.colorScheme
    var adding by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tags.forEach { tag ->
            // 48dp touch-target floor (a11y): minimumInteractiveComponentSize() pads the tappable
            // area up to 48dp without changing the chip's own small visual size.
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = cs.secondaryContainer,
                modifier = Modifier.minimumInteractiveComponentSize().clickable { vm.removeTag(pageId, tag) },
            ) {
                Row(
                    Modifier.padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("#$tag", style = monoData, color = cs.onSecondaryContainer)
                    Spacer(Modifier.size(3.dp))
                    Icon(Icons.Outlined.Close, contentDescription = "Remove tag", tint = cs.onSecondaryContainer, modifier = Modifier.size(14.dp))
                }
            }
        }
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = cs.surfaceVariant,
            modifier = Modifier.minimumInteractiveComponentSize().clickable { adding = true },
        ) {
            Row(Modifier.padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Add, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(14.dp))
                Spacer(Modifier.size(3.dp))
                Text("Tag", style = monoData, color = cs.onSurfaceVariant)
            }
        }
    }
    if (adding) {
        TextInputDialog(
            title = "Add tag",
            label = "Tag",
            initial = "",
            onDismiss = { adding = false },
            onConfirm = { vm.addTag(pageId, it); adding = false },
        )
    }
}

/** Small single-field text dialog (used for adding a tag). */
@Composable
private fun TextInputDialog(
    title: String,
    label: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text(label) }) },
        confirmButton = { Button(onClick = { onConfirm(text.trim()) }) { Text("Save") } },
        dismissButton = { Button(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Decode a persisted content:// image URI into an ImageBitmap (null on failure). */
internal fun loadImageBitmap(context: android.content.Context, uri: String): ImageBitmap? = runCatching {
    context.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use {
        android.graphics.BitmapFactory.decodeStream(it)
    }?.asImageBitmap()
}.getOrNull()
