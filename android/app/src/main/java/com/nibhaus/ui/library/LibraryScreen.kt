package com.nibhaus.ui.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nibhaus.export.LibraryView
import com.nibhaus.export.NotebookAccent
import com.nibhaus.export.transcribeTipEligible
import com.nibhaus.ui.theme.monoData
import com.nibhaus.ui.theme.steelBorder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.nibhaus.ui.InkViewModel
import com.nibhaus.ui.common.BrandWordmark
import com.nibhaus.ui.common.EmptyState
import com.nibhaus.ui.common.InkAppBar
import com.nibhaus.ui.common.LocalAppSnackbar
import com.nibhaus.ui.common.QuietLine
import com.nibhaus.ui.common.RenameDialog
import com.nibhaus.ui.common.ThumbRow
import com.nibhaus.ui.common.TipCard
import com.nibhaus.ui.common.rememberCompact
import com.nibhaus.ui.common.rememberCountUp
import com.nibhaus.ui.gradPanBrush
import com.nibhaus.ui.inkGlow
import com.nibhaus.ui.riseIn
import com.nibhaus.ui.sharedAxisX
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun LibraryScreen(
    vm: InkViewModel,
    notebooks: List<com.nibhaus.data.NotebookEntity>,
    pages: List<com.nibhaus.data.PageEntity>,
    onScan: () -> Unit = {},
) {
    val notebookId by vm.selectedNotebookId.collectAsStateWithLifecycle()
    val allTags by vm.allTags.collectAsStateWithLifecycle()
    val selectedTag by vm.selectedTagState.collectAsStateWithLifecycle()
    val taggedPages by vm.taggedPages.collectAsStateWithLifecycle()
    val libraryView by vm.libraryView.collectAsStateWithLifecycle()
    val hideBlank by vm.hideBlankPages.collectAsStateWithLifecycle()
    val nonBlankPageIds by vm.nonBlankPageIds.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    // Perf audit P1-1: collected ONCE here (not per card) and passed down into every
    // NotebookThumb/PageThumb below — shares one batched query across the whole grid instead of one
    // subscription per visible card.
    val notebookPageCounts by vm.notebookPageCounts.collectAsStateWithLifecycle()
    val totalPages by vm.totalPageCount.collectAsStateWithLifecycle()
    val everTranscribed by vm.everTranscribed.collectAsStateWithLifecycle()
    val transcribeTipDismissed by vm.tipTranscribeDismissed.collectAsStateWithLifecycle()
    val notebooksWithAudio by vm.notebooksWithAudio.collectAsStateWithLifecycle()
    val pagesWithAudio by vm.pagesWithAudio.collectAsStateWithLifecycle()
    // #6 soft delete: ids hidden pending a real delete (the undo window) — dropped from every grid/
    // list below so a deleted page/notebook disappears the instant you confirm, not after it's
    // actually gone from the database.
    val pendingDeletedPages by vm.pendingDeletedPageIds.collectAsStateWithLifecycle()
    val pendingDeletedNotebooks by vm.pendingDeletedNotebookIds.collectAsStateWithLifecycle()
    fun List<com.nibhaus.data.PageEntity>.excludingPendingDeletes() =
        filterNot { it.id in pendingDeletedPages || it.notebookId in pendingDeletedNotebooks }
    val visibleNotebooks = remember(notebooks, pendingDeletedNotebooks) {
        notebooks.filterNot { it.id in pendingDeletedNotebooks }
    }
    val snackbar = LocalAppSnackbar.current
    val inNotebook = notebookId != null
    val currentBook = notebooks.firstOrNull { it.id == notebookId }
    var renamingBook by remember { mutableStateOf(false) }
    // Feature 18: notebook overflow menu — accent picker + delete confirmation.
    var showAccentPicker by remember { mutableStateOf(false) }
    var showDeleteNotebook by remember { mutableStateOf(false) }
    // Feature 15: Favorites — bookmarked pages across every notebook, shown flat like a tag filter.
    // Mutually exclusive with the tag filter (picking one clears the other); lives in the ViewModel
    // (not local `remember`) so it survives navigating into a favorite page and back.
    val showFavorites by vm.showFavoritesState.collectAsStateWithLifecycle()
    val filtering = !inNotebook && selectedTag != null
    // Multi-select for batch share (pages only). Long-press a page to start; leaving a notebook clears it.
    var selecting by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    LaunchedEffect(notebookId) { selecting = false; selected.clear() }
    val cols = thumbColumns()
    val cs = MaterialTheme.colorScheme
    val compact = rememberCompact() // phone-width: icon-only Back so the action row doesn't wrap
    // Feature 17: which page in the open notebook was most recently opened, so returning from a page
    // (or jumping via the filmstrip) highlights where you were. Resets when the notebook changes.
    var lastOpenedPageId by rememberSaveable(notebookId) { mutableStateOf<String?>(null) }
    fun openPage(id: String) { lastOpenedPageId = id; vm.openPage(id) }
    // Feature 16: pages hidden from the open-notebook grid when "hide blank pages" is on.
    val shownPages = visiblePages(pages, hideBlank) { it.id in nonBlankPageIds }.excludingPendingDeletes()
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        BrandWordmark(Modifier.padding(start = 16.dp, top = 14.dp, bottom = 2.dp).riseIn(0))
        Box(Modifier.padding(horizontal = 16.dp).riseIn(1)) {
            // Feature 22: the header's stat count counts up on first composition rather than snapping in.
            val subCount = when {
                inNotebook -> pages.excludingPendingDeletes().size
                showFavorites -> favorites.excludingPendingDeletes().size
                filtering -> taggedPages.excludingPendingDeletes().size
                else -> visibleNotebooks.size
            }
            val animatedSubCount = rememberCountUp(subCount)
            InkAppBar(
                title = when {
                    inNotebook -> currentBook?.title?.ifBlank { "Notebook" } ?: "Notebook"
                    showFavorites -> "Favorites"
                    filtering -> "#$selectedTag"
                    else -> "Library"
                },
                sub = when {
                    inNotebook -> "$animatedSubCount pages"
                    showFavorites -> "$animatedSubCount bookmarked"
                    filtering -> "$animatedSubCount tagged pages"
                    else -> "$animatedSubCount notebooks"
                },
            ) {
                if (inNotebook) {
                    // Feature 16: collapse/hide blank pages, next to the page count in the header.
                    IconButton(
                        onClick = { vm.setHideBlankPages(!hideBlank) },
                        modifier = Modifier.semantics { stateDescription = if (hideBlank) "Blank pages hidden" else "Blank pages shown" },
                    ) {
                        Icon(
                            if (hideBlank) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                            contentDescription = if (hideBlank) "Show blank pages" else "Hide blank pages",
                        )
                    }
                    // Feature 18: rename / accent color / delete, consolidated into one overflow menu
                    // (was a standalone rename pencil + a delete action elsewhere).
                    if (currentBook != null) {
                        Box {
                            var moreBook by remember { mutableStateOf(false) }
                            IconButton(onClick = { moreBook = true }) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = "Notebook actions")
                            }
                            DropdownMenu(expanded = moreBook, onDismissRequest = { moreBook = false }) {
                                DropdownMenuItem(
                                    text = { Text("Rename") },
                                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                                    onClick = { moreBook = false; renamingBook = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("Change accent color") },
                                    leadingIcon = { Icon(Icons.Outlined.Palette, contentDescription = null) },
                                    onClick = { moreBook = false; showAccentPicker = true },
                                )
                                // TODO(item18): per-notebook capture toggle (needs pen validation)
                                DropdownMenuItem(
                                    text = { Text("Delete notebook", color = cs.error) },
                                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = cs.error) },
                                    onClick = { moreBook = false; showDeleteNotebook = true },
                                )
                            }
                        }
                    }
                    // Header-wrap fix: icon-only Back on phone widths — a text "Back" button plus the
                    // eye toggle + overflow menu left too little room and wrapped its own label.
                    if (compact) {
                        IconButton(onClick = vm::back) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    } else {
                        Button(onClick = vm::back) { Text("Back") }
                    }
                } else {
                    if (!filtering && !showFavorites) {
                        // Feature 14: gallery ↔ list toggle for the notebook grid (Library root only).
                        val toGallery = libraryView == LibraryView.GALLERY
                        IconButton(
                            onClick = { vm.setLibraryView(if (toGallery) LibraryView.LIST else LibraryView.GALLERY) },
                            modifier = Modifier.semantics { stateDescription = if (toGallery) "Gallery view" else "List view" },
                        ) {
                            Icon(
                                if (toGallery) Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.GridView,
                                contentDescription = if (toGallery) "Switch to list view" else "Switch to gallery view",
                            )
                        }
                    }
                    // Feature 15: Favorites — bookmarked pages across every notebook, available from
                    // the Library root regardless of tag-filter state (picking one clears the other).
                    IconButton(
                        onClick = { vm.setShowFavorites(!showFavorites) },
                        modifier = Modifier.semantics { stateDescription = if (showFavorites) "Showing favorites" else "Not showing favorites" },
                    ) {
                        Icon(
                            if (showFavorites) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                            contentDescription = if (showFavorites) "Close favorites" else "Favorites",
                            tint = if (showFavorites) cs.primary else cs.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        // Tag filter bar (Library root only): tap a tag to show its pages flat; tap again to clear.
        if (!inNotebook && allTags.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                allTags.forEach { tag ->
                    val sel = tag == selectedTag
                    val chipShape = RoundedCornerShape(50)
                    Box(
                        Modifier
                            .clip(chipShape)
                            .then(if (sel) Modifier.background(gradPanBrush()).inkGlow(chipShape) else Modifier.steelBorder(chipShape))
                            .clickable { vm.selectTag(if (sel) null else tag) }
                            // #16 TalkBack: a real selectable-chip state, not just a color change.
                            .semantics { this.selected = sel }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text(
                            "#$tag",
                            style = monoData,
                            color = if (sel) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        // Feature 17: a compact horizontal page-jumper above the grid, for notebooks with enough
        // pages that scanning them one row at a time gets slow. Highlights the page most recently
        // opened from this notebook (a session-local "you are here", since the grid itself has no
        // single "open" page). Reuses PageThumb — no new thumbnail chrome.
        if (inNotebook && showPageFilmstrip(shownPages.size)) {
            PageFilmstrip(shownPages, lastOpenedPageId, vm, pagesWithAudio) { id -> openPage(id) }
        }
        // Drilling into a notebook (and back) uses the same shared-axis X as the tabs.
        AnimatedContent(
            targetState = notebookId,
            transitionSpec = { sharedAxisX(forward = targetState != null) },
            label = "drill",
        ) { nb ->
            // Feature 23: pull-to-refresh. The Room flows behind this grid are already live, so the
            // gesture itself is mostly reassurance — a brief spinner acknowledging the pull, styled
            // with the app's own ink-primary color rather than the Material default.
            var refreshing by remember { mutableStateOf(false) }
            val refreshScope = rememberCoroutineScope()
            val ptrState = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = { refreshing = true; refreshScope.launch { delay(600); refreshing = false } },
                state = ptrState,
                modifier = Modifier.fillMaxSize(),
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        state = ptrState,
                        isRefreshing = refreshing,
                        containerColor = cs.surface,
                        color = cs.primary,
                    )
                },
            ) {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                if (nb == null && showFavorites) {
                    // Feature 15: bookmarked pages across every notebook — same flat-grid treatment
                    // as the tag filter above, reusing PageThumb.
                    val shownFavorites = favorites.excludingPendingDeletes()
                    if (shownFavorites.isEmpty()) {
                        item { EmptyState(Icons.Outlined.BookmarkBorder, "No favorites yet. Star a page to add it here.") }
                    }
                    else itemsIndexed(shownFavorites.chunked(cols), key = { _, row -> row.first().id }) { idx, row ->
                        Box(Modifier.riseIn(2 + idx)) {
                            ThumbRow(row, cols) { page, m ->
                                PageThumb(page.id, "Page ${page.page}", vm, page.id in pagesWithAudio, page.book, m) { vm.openPage(page.id) }
                            }
                        }
                    }
                } else if (nb == null && filtering) {
                    val shownTagged = taggedPages.excludingPendingDeletes()
                    if (shownTagged.isEmpty()) item { QuietLine("No pages with this tag.") }
                    else itemsIndexed(shownTagged.chunked(cols), key = { _, row -> row.first().id }) { idx, row ->
                        Box(Modifier.riseIn(2 + idx)) {
                            ThumbRow(row, cols) { page, m ->
                                PageThumb(page.id, "Page ${page.page}", vm, page.id in pagesWithAudio, page.book, m) { vm.openPage(page.id) }
                            }
                        }
                    }
                } else if (nb == null) {
                    if (visibleNotebooks.isEmpty()) {
                        // First-run home surface (design-system §9): a real empty state, not just a
                        // caption — this is the very first thing a brand-new user sees here.
                        item {
                            EmptyState(
                                icon = Icons.AutoMirrored.Outlined.MenuBook,
                                text = "Every page you write lands here automatically, once a pen is paired and writing on the notebook paper made for this pen.",
                                headline = "Your library is empty",
                                primaryActionLabel = "Pair a pen",
                                onPrimaryAction = onScan,
                            )
                        }
                    }
                    else {
                    // Feature 5 tip card: nudge toward transcription once there's enough captured to be
                    // worth searching, and only if the user hasn't already discovered it on their own.
                    if (transcribeTipEligible(totalPages, everTranscribed, transcribeTipDismissed)) {
                        item {
                            TipCard(
                                "Transcribe a page to make its handwriting searchable.",
                                Modifier.padding(bottom = 8.dp).riseIn(2),
                                onDismiss = vm::dismissTranscribeTip,
                            )
                        }
                    }
                    when (libraryView) {
                        // Feature 14: GALLERY is the existing cover grid (unchanged); LIST is a
                        // denser row-per-notebook layout (Feature 19 metadata lives in both).
                        LibraryView.GALLERY -> itemsIndexed(visibleNotebooks.chunked(cols), key = { _, row -> row.first().id }) { idx, row ->
                            Box(Modifier.riseIn(2 + idx)) {
                                ThumbRow(row, cols) { entry, m ->
                                    NotebookThumb(entry, vm, notebookPageCounts[entry.id] ?: 0, entry.id in notebooksWithAudio, m) { vm.openNotebook(entry.id) }
                                }
                            }
                        }
                        LibraryView.LIST -> itemsIndexed(visibleNotebooks, key = { _, entry -> entry.id }) { idx, entry ->
                            Box(Modifier.riseIn(2 + idx)) {
                                NotebookListRow(entry, vm, notebookPageCounts[entry.id] ?: 0, entry.id in notebooksWithAudio) { vm.openNotebook(entry.id) }
                            }
                        }
                    }
                    }
                } else {
                    if (shownPages.isEmpty()) {
                        item { QuietLine(if (pages.isEmpty()) "No pages in this notebook yet." else "No pages with ink yet.") }
                    } else itemsIndexed(shownPages.chunked(cols), key = { _, row -> row.first().id }) { idx, row ->
                        Box(Modifier.riseIn(2 + idx)) {
                            ThumbRow(row, cols) { page, m ->
                                PageThumb(
                                    page.id, "Page ${page.page}", vm, page.id in pagesWithAudio, page.book, m,
                                    selected = page.id in selected,
                                    onLongPress = { selecting = true; if (page.id !in selected) selected.add(page.id) },
                                ) { if (selecting) { if (!selected.remove(page.id)) selected.add(page.id) } else openPage(page.id) }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
            }
        }
        }
        if (selecting) {
            SelectionShareBar(
                count = selected.size,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                onShare = {
                    // #7: share intents hand off to the OS chooser with no completion callback — the
                    // honest confirmation is "the share sheet was launched", same as every other app.
                    val count = selected.size
                    vm.shareSelectedPages(selected.toList())
                    snackbar.show(if (count == 1) "Shared 1 page" else "Shared $count pages")
                    selecting = false
                    selected.clear()
                },
                onCancel = { selecting = false; selected.clear() },
            )
        }
        // #9: a batch share renders one bitmap per page before handing off to the share sheet — for
        // a large selection that's not instant, so it gets the same cancel affordance as the
        // TRANSCRIBING badge. Independent of `selecting` (already cleared by the time this shows;
        // see onShare above) — driven purely by vm.batchShareProgress.
        val batchShareProgress by vm.batchShareProgress.collectAsStateWithLifecycle()
        batchShareProgress?.let { (done, total) ->
            BatchShareProgressBar(
                done = done,
                total = total,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                onCancel = vm::cancelBatchShare,
            )
        }
    }
    if (renamingBook && currentBook != null) {
        RenameDialog(
            dialogTitle = "Rename notebook",
            fieldLabel = "Label",
            initial = currentBook.title,
            onDismiss = { renamingBook = false },
            onConfirm = { name -> vm.renameNotebook(currentBook.id, name); renamingBook = false },
        )
    }
    if (showAccentPicker && currentBook != null) {
        val accent by remember(currentBook.id) { vm.notebookAccent(currentBook.id) }
            .collectAsStateWithLifecycle(NotebookAccent.NONE)
        AccentColorDialog(
            current = accent,
            onDismiss = { showAccentPicker = false },
            onPick = { vm.setNotebookAccent(currentBook.id, it) },
        )
    }
    if (showDeleteNotebook && currentBook != null) {
        var alsoRemote by remember { mutableStateOf(false) }
        var alsoAudio by remember { mutableStateOf(false) }
        val hasAudio = currentBook.id in notebooksWithAudio
        val snackbar = LocalAppSnackbar.current
        AlertDialog(
            onDismissRequest = { showDeleteNotebook = false },
            title = { Text("Delete this notebook?") },
            text = {
                Column {
                    Text("All ${pages.size} pages will be removed, along with their ink and transcripts on this device. You'll have a few seconds to undo.")
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth().clickable { alsoRemote = !alsoRemote },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = alsoRemote, onCheckedChange = { alsoRemote = it })
                        Spacer(Modifier.width(4.dp))
                        Text("Also delete the exported copies from your sync destination")
                    }
                    if (hasAudio) {
                        Row(
                            Modifier.fillMaxWidth().clickable { alsoAudio = !alsoAudio },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = alsoAudio, onCheckedChange = { alsoAudio = it })
                            Spacer(Modifier.width(4.dp))
                            Text("Also delete this notebook's voice notes")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteNotebook = false
                    val deletedId = currentBook.id
                    vm.deleteNotebook(deletedId, alsoRemote, alsoAudio)
                    snackbar.show("Notebook deleted", actionLabel = "Undo", durationMs = 5_000L) {
                        vm.undoDeleteNotebook(deletedId)
                    }
                }) { Text("Delete", color = cs.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteNotebook = false }) { Text("Cancel") } },
        )
    }
}

/** Feature 17: a small horizontal strip of page thumbs to jump around a notebook quickly — the grid
 *  below already shows every page, so this earns its place only once there are enough to make
 *  scrolling the grid slow (see [showPageFilmstrip]). Reuses [PageThumb]; ~72dp tall per the brief. */
@Composable
private fun PageFilmstrip(
    pages: List<com.nibhaus.data.PageEntity>,
    highlightedPageId: String?,
    vm: InkViewModel,
    pagesWithAudio: Set<String>,
    onOpen: (String) -> Unit,
) {
    LazyRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(pages, key = { it.id }) { page ->
            PageThumb(
                page.id, "Page ${page.page}", vm, page.id in pagesWithAudio, page.book,
                Modifier.height(72.dp),
                selected = page.id == highlightedPageId,
            ) { onOpen(page.id) }
        }
    }
}

/** Feature 17: the filmstrip only earns its keep once the grid needs real scrolling to reach a page —
 *  below that, it would just duplicate the grid. Pure, so it's unit-testable without Compose. */
internal fun showPageFilmstrip(pageCount: Int): Boolean = pageCount > 6

/** Feature 18: pick one of a small fixed accent palette for a notebook's card tint, or clear it by
 *  tapping the currently-selected swatch again. */
@Composable
private fun AccentColorDialog(
    current: NotebookAccent,
    onDismiss: () -> Unit,
    onPick: (NotebookAccent) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Notebook accent color") },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NotebookAccent.entries.filter { it != NotebookAccent.NONE }.forEach { accent ->
                    val sel = accent == current
                    val accentName = accent.key.replaceFirstChar { it.uppercase() }
                    Box(
                        Modifier
                            .size(32.dp)
                            .background(Color(accent.argb), CircleShape)
                            .then(if (sel) Modifier.border(2.dp, cs.onSurface, CircleShape) else Modifier)
                            .clickable { onPick(if (sel) NotebookAccent.NONE else accent) }
                            // #16 TalkBack: a color swatch has no other way to name itself.
                            .semantics {
                                contentDescription = accentName
                                selected = sel
                            },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** Returns the number of thumbnail columns for the current screen width: 2 on phones, 3 on small
 *  tablets (≥600 dp), 4 on large tablets (≥1000 dp). Must be called from a @Composable scope. */
@Composable
private fun thumbColumns(): Int = when {
    LocalConfiguration.current.screenWidthDp >= 1000 -> 4
    LocalConfiguration.current.screenWidthDp >= 600  -> 3
    else -> 2
}

/** Feature 19: notebook metadata line for a library card/row — page count is always shown; the
 *  physical size is appended only when it's actually known (a captured/measured profile). Never
 *  fabricates a size. Pure, so it's unit-testable without Compose. */
internal fun notebookMetaLine(pageCount: Int, widthMm: Int?, heightMm: Int?): String {
    val pages = if (pageCount == 1) "1 page" else "$pageCount pages"
    return if (widthMm != null && heightMm != null) "$pages · $widthMm × $heightMm mm" else pages
}

/** Feature 16: the pages to render in the open-notebook grid. When [hideBlank] is on, pages [hasStrokes]
 *  says have no ink are dropped; otherwise every page is shown. Pure, so it's unit-testable without Compose. */
internal fun visiblePages(
    pages: List<com.nibhaus.data.PageEntity>,
    hideBlank: Boolean,
    hasStrokes: (com.nibhaus.data.PageEntity) -> Boolean,
): List<com.nibhaus.data.PageEntity> =
    if (hideBlank) pages.filter(hasStrokes) else pages

/** Floating bar shown while multi-selecting pages: count + Share (image + opt-in audio) / Cancel. */
@Composable
private fun SelectionShareBar(count: Int, modifier: Modifier = Modifier, onShare: () -> Unit, onCancel: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = cs.surfaceVariant, shadowElevation = 6.dp) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("$count selected", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(onClick = onShare, enabled = count > 0) { Text("Share") }
        }
    }
}

/** #9: progress + Cancel for an in-flight batch share (mirrors [SelectionShareBar]'s chrome) — shown
 *  while [InkViewModel.batchShareProgress] is non-null, i.e. a selection large enough that rendering
 *  every page isn't instant. */
@Composable
private fun BatchShareProgressBar(done: Int, total: Int, modifier: Modifier = Modifier, onCancel: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = cs.surfaceVariant, shadowElevation = 6.dp) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Sharing $done / $total…", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}
