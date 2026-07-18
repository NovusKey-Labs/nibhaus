package com.nibhaus.ui.pagedetail

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditCalendar
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nibhaus.audio.recordingForStroke
import com.nibhaus.data.RecordingEntity
import com.nibhaus.data.StrokeEntity
import com.nibhaus.export.replayTipEligible
import com.nibhaus.ui.theme.G1
import com.nibhaus.ui.theme.InkTokens
import com.nibhaus.ui.theme.monoData
import com.nibhaus.ui.theme.monoEyebrow
import com.nibhaus.ui.theme.ncodeDotGrid
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.nibhaus.ui.AddEventDialog
import com.nibhaus.ui.BandDivider
import com.nibhaus.ui.Eyebrow
import com.nibhaus.ui.InkViewModel
import com.nibhaus.ui.MonoBadge
import com.nibhaus.ui.PageTextView
import com.nibhaus.ui.common.InkSurface
import com.nibhaus.ui.common.RenameDialog
import com.nibhaus.ui.common.TipCard
import com.nibhaus.ui.common.drawStrokes
import com.nibhaus.ui.common.rememberCountUp
import com.nibhaus.ui.common.rememberHaptics
import com.nibhaus.ui.rememberReducedMotion
import com.nibhaus.ui.riseIn
import com.nibhaus.ui.sharedAxisX
import com.nibhaus.ui.steelCard

/**
 * Page detail (mock #4). Read-only by default; the Edit toggle reveals the floating toolbar and an
 * editable canvas. Editing is selection-based: tap strokes to toggle them, or drag a lasso to select
 * a region, then act on the whole selection (recolor / thicken / delete). Undo drops the last stroke.
 */
@Composable
internal fun PageDetail(strokes: List<StrokeEntity>, vm: InkViewModel) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val snackbar = com.nibhaus.ui.common.LocalAppSnackbar.current
    val haptics = rememberHaptics()
    val reducedMotion = rememberReducedMotion()
    val ocrProgress by vm.ocrProgress.collectAsStateWithLifecycle()
    val pageId by vm.selectedPageId.collectAsStateWithLifecycle()
    val pageEntity by vm.selectedPageEntity.collectAsStateWithLifecycle()
    val recordings by remember(pageId) { vm.recordingsFor(pageId) }.collectAsStateWithLifecycle(emptyList())
    val playingId by vm.playingRecordingId.collectAsStateWithLifecycle()
    val positionMs by vm.playbackPositionMs.collectAsStateWithLifecycle()
    val isPlaying by vm.isPlaying.collectAsStateWithLifecycle()
    val notebookId by vm.selectedNotebookId.collectAsStateWithLifecycle()
    // #16 TalkBack: the page canvas below announces "Handwritten page {n} of {notebook}".
    val notebooksForA11y by vm.notebooks.collectAsStateWithLifecycle()
    val notebookTitle = notebooksForA11y.firstOrNull { it.id == notebookId }?.title?.ifBlank { "Notebook" } ?: "Notebook"
    // #26b: prev/next page navigation within the viewer — siblings in the same notebook, ordered by
    // page number, so Prev/Next (and the direction-aware slide below) has something to compare.
    val notebookPages by vm.pages.collectAsStateWithLifecycle()
    val siblingPages = remember(notebookPages, notebookId) {
        notebookPages.filter { it.notebookId == notebookId }.sortedBy { it.page }
    }
    val siblingIndex = siblingPages.indexOfFirst { it.id == pageId }
    val prevPageId = siblingPages.getOrNull(siblingIndex - 1)?.id
    val nextPageId = siblingPages.getOrNull(siblingIndex + 1)?.id
    fun pageNumberOf(id: String?): Int = siblingPages.firstOrNull { it.id == id }?.page ?: 0
    val backgrounds by vm.backgrounds.collectAsStateWithLifecycle()
    val bgUri = notebookId?.let { backgrounds[it] }
    val background = remember(bgUri) { bgUri?.let { loadImageBitmap(context, it) } }
    val pickBackground = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val nb = notebookId
        if (uri != null && nb != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            vm.setBackground(nb, uri.toString())
        }
    }
    var editing by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var textView by remember { mutableStateOf(false) }
    val transcript by vm.currentTranscript.collectAsStateWithLifecycle()
    val ocrEnabled by vm.onDeviceOcrEnabled.collectAsStateWithLifecycle()
    val ocrAllowed = vm.onDeviceOcrAvailable && ocrEnabled
    var lassoMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedRecId by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<RecordingEntity?>(null) }
    var showAddEvent by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showUpsell by remember { mutableStateOf(false) }
    var showReplay by remember { mutableStateOf(false) }
    var showPrintChoice by remember { mutableStateOf(false) }
    val entitled by vm.entitled.collectAsStateWithLifecycle()
    // #15b follow-up: exports/prints honor the Fine/Normal/Bold handwriting-size preset too, so a
    // printed or shared page matches what's on screen instead of always rendering at Normal width.
    val strokeScale by vm.strokeScale.collectAsStateWithLifecycle()
    val bookmarkedIds by vm.bookmarkedPageIds.collectAsStateWithLifecycle()

    // Stop audio whenever we leave the page or drop out of listen mode.
    DisposableEffect(Unit) { onDispose { vm.stopPlayback() } }

    // The recording the scrubber/highlight follow: the loaded one, else the picked one, else newest.
    val active = recordings.firstOrNull { it.id == playingId }
        ?: recordings.firstOrNull { it.id == selectedRecId }
        ?: recordings.lastOrNull()
    val loaded = active != null && active.id == playingId        // player is on this note (playing OR paused)
    val playingActive = loaded && isPlaying                       // actively playing this note

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = vm::back) { Text("Back") }
                // the stroke total counts up on first composition rather than snapping in —
                // the live selection tally (which changes on every tap) is left un-animated on purpose.
                val strokeCount = rememberCountUp(strokes.size)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // #26b: prev/next page navigation within the viewer — hidden mid-edit so paging
                    // away can't strand an in-progress lasso/selection.
                    if (!editing && prevPageId != null) {
                        IconButton(onClick = { vm.openPage(prevPageId) }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Outlined.ChevronLeft, contentDescription = "Previous page", tint = cs.onSurfaceVariant)
                        }
                    }
                    Text(
                        if (editing && selected.isNotEmpty()) "${selected.size} SELECTED" else "$strokeCount STROKES",
                        style = monoEyebrow,
                        color = cs.onSurfaceVariant,
                    )
                    if (!editing && nextPageId != null) {
                        IconButton(onClick = { vm.openPage(nextPageId) }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Outlined.ChevronRight, contentDescription = "Next page", tint = cs.onSurfaceVariant)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Listen: replay the page's voice notes synced to the ink (only if any exist).
                    if (recordings.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                listening = !listening
                                if (listening) { editing = false; textView = false } else vm.stopPlayback()
                            },
                            modifier = Modifier.semantics { stateDescription = if (listening) "Listening" else "Not listening" },
                        ) {
                            Icon(
                                Icons.Outlined.Headphones,
                                contentDescription = if (listening) "Close listen" else "Listen with the ink",
                                tint = if (listening) cs.tertiary else cs.onSurfaceVariant,
                            )
                        }
                    }
                    // Typed-text view: read the page's OCR transcript in a printed font (the ink stays
                    // the source of truth — this is a parallel, non-destructive view).
                    IconButton(
                        onClick = {
                            textView = !textView
                            if (textView) { editing = false; selected = emptySet(); listening = false; vm.stopPlayback() }
                        },
                        modifier = Modifier.semantics { stateDescription = if (textView) "Showing typed text" else "Showing ink" },
                    ) {
                        Icon(
                            Icons.Outlined.Description,
                            contentDescription = if (textView) "Show ink" else "Show typed text",
                            tint = if (textView) cs.tertiary else cs.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = {
                            editing = !editing; if (!editing) selected = emptySet() else { listening = false; textView = false; vm.stopPlayback() }
                        },
                        modifier = Modifier.semantics { stateDescription = if (editing) "Editing" else "Not editing" },
                    ) {
                        Icon(
                            Icons.Outlined.Edit,
                            contentDescription = if (editing) "Done editing" else "Edit",
                            tint = if (editing) cs.tertiary else cs.onSurfaceVariant,
                        )
                    }
                    // star/un-star this page — bookmarked pages surface in Library → Favorites.
                    pageId?.let { id ->
                        val bookmarked = id in bookmarkedIds
                        IconButton(
                            onClick = {
                                haptics.tick()
                                vm.toggleBookmark(id)
                                // #7: confirm the toggle — snappy enough that the icon flip alone could be
                                // missed, and "Favorites" is a named destination worth naming here.
                                snackbar.show(if (bookmarked) "Removed from Favorites" else "Added to Favorites")
                            },
                            modifier = Modifier.semantics { stateDescription = if (bookmarked) "Bookmarked" else "Not bookmarked" },
                        ) {
                            Icon(
                                if (bookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                                contentDescription = if (bookmarked) "Remove from Favorites" else "Add to Favorites",
                                tint = if (bookmarked) cs.tertiary else cs.onSurfaceVariant,
                            )
                        }
                    }
                    // Secondary actions collapsed into one overflow (⋯) to keep the bar uncluttered.
                    Box {
                        var more by remember { mutableStateOf(false) }
                        IconButton(onClick = { more = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "More actions", tint = cs.onSurfaceVariant)
                        }
                        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                            DropdownMenuItem(
                                text = { Text("Add to calendar") },
                                leadingIcon = { Icon(Icons.Outlined.EditCalendar, contentDescription = null) },
                                onClick = { more = false; showAddEvent = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Print") },
                                leadingIcon = { Icon(Icons.Outlined.Print, contentDescription = null) },
                                enabled = strokes.isNotEmpty(),
                                onClick = { more = false; showPrintChoice = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Handwriting Replay") },
                                leadingIcon = { Icon(Icons.Outlined.History, contentDescription = null) },
                                enabled = strokes.isNotEmpty(),
                                onClick = { more = false; showReplay = true },
                            )
                            if (ocrAllowed) {
                                DropdownMenuItem(
                                    text = { Text("Transcribe on device") },
                                    leadingIcon = { Icon(Icons.Outlined.Description, contentDescription = null) },
                                    enabled = strokes.isNotEmpty(),
                                    onClick = { more = false; vm.transcribeCurrentPageOnDevice() },
                                )
                            }
                            if (ocrAllowed) {
                                DropdownMenuItem(
                                    text = { Text("✨ Improve transcription") },
                                    leadingIcon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null) },
                                    enabled = strokes.isNotEmpty(),
                                    onClick = {
                                        more = false
                                        if (entitled) vm.transcribeCurrentPageAccurate() else showUpsell = true
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Export now") },
                                leadingIcon = { Icon(Icons.Outlined.FileUpload, contentDescription = null) },
                                onClick = { more = false; vm.exportCurrentPage() },
                            )
                            DropdownMenuItem(
                                text = { Text(if (bgUri == null) "Set notebook background…" else "Replace notebook background…") },
                                leadingIcon = { Icon(Icons.Outlined.Image, contentDescription = null) },
                                enabled = notebookId != null,
                                onClick = { more = false; pickBackground.launch(arrayOf("image/*")) },
                            )
                            if (bgUri != null) {
                                DropdownMenuItem(
                                    text = { Text("Remove background") },
                                    onClick = { more = false; notebookId?.let { vm.clearBackground(it) } },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Delete page", color = cs.error) },
                                leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = cs.error) },
                                onClick = { more = false; showDelete = true },
                            )
                        }
                    }
                }
            }
            // OCR progress (download → inference); the completion/error status line for export
            // / transcribe / save-transcript (#7) now surfaces as the app-wide snackbar (see InkApp
            // in Screens.kt, which is the one place that observes vm.exportStatus) instead of here.
            when (val ocr = ocrProgress) {
                is InkViewModel.OcrProgress.Idle -> {}
                is InkViewModel.OcrProgress.Downloading -> {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val pct = ocr.pct
                        // Percent known → determinate bar. Percent unknown → the spinner reads
                        // as "working" without implying a fake, uncalibrated fill.
                        if (pct < 0) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = G1)
                            Spacer(Modifier.width(10.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (pct < 0) "Downloading model…" else "Downloading model ($pct%)…",
                                style = monoData,
                                color = cs.primary,
                            )
                            if (pct >= 0) {
                                LinearProgressIndicator(
                                    progress = { pct / 100f },
                                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = vm::cancelOcr) { Text("Cancel") }
                    }
                }
                is InkViewModel.OcrProgress.Transcribing -> {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = G1)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Transcribing on device. This can take a couple of minutes…",
                            style = monoData,
                            color = cs.primary,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = vm::cancelOcr) { Text("Cancel") }
                    }
                }
                is InkViewModel.OcrProgress.Failed -> {
                    // #8: one plain-language line of what failed + the likely cause (from
                    // FailureDiagnosis), plus Retry — stays up as an inline card rather than a
                    // timed-out snackbar, since this is a state worth the user actually noticing.
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(ocr.msg, style = monoData, color = cs.error, modifier = Modifier.weight(1f))
                        TextButton(onClick = vm::retryTranscribe) { Text("Retry") }
                    }
                }
            }
            pageId?.let { TagRow(it, vm) }
            // Show the tip only once the page is substantial enough that replay/GIF export is
            // actually worth knowing about, and only in the plain reading view (not mid-edit/listen/text).
            val replayTipDismissed by vm.tipReplayDismissed.collectAsStateWithLifecycle()
            if (!editing && !listening && !textView && replayTipEligible(strokes.size, replayTipDismissed)) {
                TipCard(
                    "You can replay this page's handwriting stroke by stroke, then export it as an animated GIF to share.",
                    Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    onDismiss = vm::dismissReplayTip,
                )
            }
            val surfaceMod = Modifier.weight(1f).fillMaxWidth().padding(8.dp)
            when {
                textView -> {
                    val tr by vm.translation.collectAsStateWithLifecycle()
                    val translating by vm.translating.collectAsStateWithLifecycle()
                    val translateErr by vm.translateError.collectAsStateWithLifecycle()
                    // V3 Transcript: ink preview steelCard → MonoBadge → verbatim text → mono footer.
                    Column(surfaceMod) {
                        // ── Header row: section eyebrow + vault-local badge ─────────────────────
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp).riseIn(0),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Eyebrow("TRANSCRIPT")
                            MonoBadge("VERBATIM · VAULT-LOCAL", leadingDot = true)
                        }
                        // ── Ink preview: steelCard (tcard spec: radius 16, padding 15) ──────────
                        val dotColor = InkTokens.dotColor(cs.onBackground)
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(176.dp)
                                .padding(horizontal = 4.dp)
                                .riseIn(1)
                                .steelCard(radius = 16.dp)
                                .padding(15.dp),
                        ) {
                            Canvas(Modifier.fillMaxSize().ncodeDotGrid(dotColor, spacing = 13.dp)) {
                                drawStrokes(strokes, vm::strokesFlowOf, cs.onSurface, brandInk = cs.onSurface)
                            }
                        }
                        BandDivider(Modifier.padding(vertical = 6.dp))
                        // ── Verbatim text (translation-aware) ────────────────────────────────────
                        PageTextView(
                            transcript = transcript,
                            onTranscribe = { vm.transcribeCurrentPageOnDevice() },
                            canTranscribe = ocrAllowed && strokes.isNotEmpty(),
                            defaultTarget = vm.deviceLanguage,
                            translationText = tr?.text,
                            translationOnDevice = tr?.onDevice == true,
                            translating = translating,
                            translateError = translateErr,
                            onTranslate = { source, target ->
                                if (entitled && vm.translatorAvailable) vm.translateCurrentPage(target, source)
                                else showUpsell = true
                            },
                            onShowOriginal = { vm.clearTranslation() },
                            onSaveTranscript = { text -> pageId?.let { vm.saveTranscript(it, text) } },
                            modifier = Modifier.weight(1f).riseIn(2),
                        )
                        BandDivider()
                        // ── Metadata footer: model · word count in IBM Plex Mono ────────────────
                        val wordCount = transcript?.trim()
                            ?.split("\\s+".toRegex())?.count { it.isNotEmpty() } ?: 0
                        val modelLabel = if (ocrAllowed) "on-device" else "vault-server"
                        Text(
                            if (wordCount > 0) "$modelLabel · $wordCount words" else modelLabel,
                            style = monoData,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).riseIn(3),
                        )
                    }
                }
                editing -> EditableInkCanvas(
                    strokes, vm, lassoMode, selected,
                    onToggleStroke = { s -> selected = if (s.uuid in selected) selected - s.uuid else selected + s.uuid },
                    onLasso = { hit -> selected = hit.map { it.uuid }.toSet() },
                    modifier = surfaceMod,
                    background = background,
                )
                listening -> ListenCanvas(
                    strokes, vm, recordings, active, playingActive, positionMs,
                    onTapStroke = { s ->
                        // Tap-to-play (pencast): jump the audio to what was said while writing this stroke.
                        recordingForStroke(recordings, s)?.let { (rec, offset) ->
                            selectedRecId = rec.id; vm.playRecording(rec, offset)
                        }
                    },
                    // A marker carries its own offset → play that bookmark, not the recording's start.
                    onTapMarker = { rec, offsetMs -> selectedRecId = rec.id; vm.playRecording(rec, offsetMs) },
                    modifier = surfaceMod,
                    background = background,
                )
                else -> {
                    // #26b: page-to-page change — direction-aware horizontal slide+fade, keyed on
                    // pageId, comparing page numbers (forward = higher number = slide in from the
                    // right). Scoped to the plain reading view only — editing/listening/textView
                    // aren't navigated away from mid-mode, so they stay un-animated. Each branch
                    // fetches its OWN page's strokes (vm.pageStrokes(pid), same per-page flow the
                    // library thumbnails use) instead of the outer reactive `strokes`, so the
                    // outgoing pane doesn't flash the new page's ink mid-transition.
                    AnimatedContent(
                        targetState = pageId,
                        transitionSpec = {
                            sharedAxisX(forward = pageNumberOf(targetState) > pageNumberOf(initialState), reduced = reducedMotion)
                        },
                        label = "pageCanvas",
                        modifier = surfaceMod,
                    ) { pid ->
                        val branchStrokes by remember(pid) { vm.pageStrokes(pid.orEmpty()) }.collectAsStateWithLifecycle(emptyList())
                        val branchPage = siblingPages.firstOrNull { it.id == pid } ?: pageEntity?.takeIf { it.id == pid }
                        InkSurface(
                            branchStrokes, vm,
                            // #16 TalkBack: the canvas itself announces which page/notebook it is.
                            Modifier.fillMaxSize().semantics {
                                contentDescription = "Handwritten page ${branchPage?.page ?: 0} of $notebookTitle"
                            },
                            background = background, revealPageId = pid,
                            pageBounds = vm.pageGeometryFor(branchPage?.book),
                            ruling = vm.rulingFor(branchPage?.book),
                            pageStyle = vm.pageStyleAt(branchPage?.book, branchPage?.page),
                            pageNumber = branchPage?.page,
                            zones = com.nibhaus.zones.BuiltinZones.ALL.filter { it.book == branchPage?.book },
                        )
                    }
                }
            }
        }
        if (editing) {
            // The collapsed pickers rest on the first selected stroke's color/size (or defaults).
            val firstSel = strokes.firstOrNull { it.uuid in selected }
            EditToolbar(
                lassoMode = lassoMode,
                hasSelection = selected.isNotEmpty(),
                currentColor = firstSel?.color ?: 0,
                currentWidth = firstSel?.width ?: 1f,
                onToggleLasso = { lassoMode = !lassoMode },
                onRecolor = { c -> vm.recolorSelection(selected.toList(), c); selected = emptySet() },
                onResize = { w -> vm.resizeSelection(selected.toList(), w); selected = emptySet() },
                onDelete = { vm.deleteSelection(selected.toList()); selected = emptySet() },
                onUndo = { vm.undoEdit() },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        if (listening && active != null) {
            ListenBar(
                recordings = recordings,
                active = active,
                loaded = loaded,
                playing = playingActive,
                positionMs = positionMs,
                onSelect = { rec -> selectedRecId = rec.id; vm.stopPlayback() },
                onPlayPause = {
                    when {
                        playingActive -> vm.pausePlayback()          // playing → pause in place
                        loaded -> vm.resumePlayback()                // paused → resume where we left off
                        else -> vm.playRecording(active, 0)          // stopped → play from start
                    }
                },
                onSeek = { ms -> if (loaded) vm.seekPlayback(ms) else vm.playRecording(active, ms) },
                onRename = { renaming = active },
                onDelete = { vm.deleteRecording(active) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        if (showReplay) {
            ReplayScreen(strokes, vm, onClose = { showReplay = false })
        }
    }
    // Leaving no notes? drop out of listen mode.
    LaunchedEffect(recordings.isEmpty()) { if (recordings.isEmpty()) listening = false }
    renaming?.let { rec ->
        val idx = recordings.indexOfFirst { it.id == rec.id }
        RenameDialog(
            dialogTitle = "Rename note",
            fieldLabel = "Name",
            initial = rec.title.ifBlank { "Note ${idx + 1}" },
            onDismiss = { renaming = null },
            onConfirm = { name -> vm.renameRecording(rec.id, name); renaming = null },
        )
    }
    if (showAddEvent) AddEventDialog(vm, defaultTitle = "", onDismiss = { showAddEvent = false })
    if (showDelete) {
        var alsoRemote by remember { mutableStateOf(false) }
        var alsoAudio by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete this page?") },
            text = {
                Column {
                    Text("Its ink and transcript on this device are removed. You'll have a few seconds to undo.")
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth().clickable { alsoRemote = !alsoRemote },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = alsoRemote, onCheckedChange = { alsoRemote = it })
                        Spacer(Modifier.width(4.dp))
                        Text("Also delete the exported copy from your sync destination")
                    }
                    if (recordings.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().clickable { alsoAudio = !alsoAudio },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = alsoAudio, onCheckedChange = { alsoAudio = it })
                            Spacer(Modifier.width(4.dp))
                            Text("Also delete this page's voice notes")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    val deletedId = pageId
                    vm.deleteCurrentPage(alsoRemote, alsoAudio)
                    if (deletedId != null) {
                        snackbar.show("Page deleted", actionLabel = "Undo", durationMs = 5_000L) {
                            vm.undoDeletePage(deletedId)
                        }
                    }
                }) {
                    Text("Delete", color = cs.error)
                }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } },
        )
    }
    if (showPrintChoice) {
        // Print exactly like the page (true ink colors), or force everything black for a laser/mono print.
        val printView = androidx.compose.ui.platform.LocalView.current
        val doPrint: (Boolean) -> Unit = { black ->
            showPrintChoice = false
            // Wake the system bars before handing off: some print UIs have no in-surface back
            // affordance, and with the bars swiped away there's no way out of the print flow.
            (printView.context as? android.app.Activity)?.window?.let { w ->
                androidx.core.view.WindowCompat.getInsetsController(w, printView)
                    .show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
            printPage(
                context, "Nibhaus page", strokes, vm::strokesFlowOf,
                bounds = vm.pageGeometryFor(pageEntity?.book),
                ruling = vm.rulingFor(pageEntity?.book),
                pageNumber = pageEntity?.page,
                pageStyle = vm.pageStyleAt(pageEntity?.book, pageEntity?.page),
                blackInk = black,
                strokeScale = strokeScale.multiplier,
            )
        }
        AlertDialog(
            onDismissRequest = { showPrintChoice = false },
            title = { Text("Print ink as") },
            text = { Text("Print the ink in its true colors, or all in black?") },
            confirmButton = { TextButton(onClick = { doPrint(false) }) { Text("True colors") } },
            dismissButton = { TextButton(onClick = { doPrint(true) }) { Text("Black ink") } },
        )
    }
    if (showUpsell) {
        AlertDialog(
            onDismissRequest = { showUpsell = false },
            title = { Text("A premium feature") },
            text = {
                Text(
                    "The accurate transcription pass, translation, and syncing straight to your " +
                        "own server are planned Premium features and are not available yet. " +
                        "Everything else stays free: capture, instant transcription, full-text " +
                        "search, editing, export, and folder sync.",
                )
            },
            confirmButton = { TextButton(onClick = { showUpsell = false }) { Text("Got it") } },
        )
    }
    val showOcrDisclosure by vm.showOcrDisclosure.collectAsStateWithLifecycle()
    if (showOcrDisclosure) {
        AlertDialog(
            onDismissRequest = { vm.dismissOcrDisclosure() },
            title = { Text("Transcribe on this device?") },
            text = {
                Text(
                    "On-device transcription uses Google's ML Kit handwriting recognition. Your " +
                        "handwriting is recognized on your device and is never uploaded. The first " +
                        "time, a one-time language model is downloaded from Google; after that no " +
                        "further download is needed. Instant on-device transcription is the " +
                        "default; you can add your own transcription server in Settings.",
                )
            },
            confirmButton = { Button(onClick = { vm.confirmOcrDisclosure() }) { Text("Download & transcribe") } },
            dismissButton = { TextButton(onClick = { vm.dismissOcrDisclosure() }) { Text("Cancel") } },
        )
    }
    val showAccuracyDisclaimer by vm.showAccuracyDisclaimer.collectAsStateWithLifecycle()
    if (showAccuracyDisclaimer) {
        AlertDialog(
            onDismissRequest = { vm.dismissAccuracyDisclaimer() },
            title = { Text("About transcription quality", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    "Transcription reads your handwriting for search and editing. Your original ink is " +
                        "always kept and never changed. Accuracy depends on your handwriting; messy or " +
                        "stylized writing may contain errors. For the highest accuracy, connect your own server.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = { Button(onClick = { vm.confirmAccuracyDisclaimer() }) { Text("Got it") } },
        )
    }
    val showVlmDisclosure by vm.showVlmDownloadDisclosure.collectAsStateWithLifecycle()
    if (showVlmDisclosure && vm.vlmDownloadDisclosure != null) {
        ModelDownloadDisclosure(
            metered = vm.isConnectionMetered(),
            onWifiOnly = { vm.confirmVlmDownload(com.nibhaus.premiumapi.DownloadConsentChoice.WifiOnly) },
            onAllowMetered = { vm.confirmVlmDownload(com.nibhaus.premiumapi.DownloadConsentChoice.AllowMetered) },
            onDecline = vm::dismissVlmDownloadDisclosure,
        )
    }
}
