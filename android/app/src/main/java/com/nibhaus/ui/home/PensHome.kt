package com.nibhaus.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Troubleshoot
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nibhaus.pen.PenConnState
import com.nibhaus.ui.theme.monoData
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.nibhaus.export.printedButtonTipEligible
import com.nibhaus.feedback.CrashCapture
import com.nibhaus.feedback.FeedbackScreen
import com.nibhaus.feedback.buildFeedbackBundle
import com.nibhaus.feedback.crashPromptEligible
import com.nibhaus.feedback.crashReportBody
import com.nibhaus.feedback.crashTail
import com.nibhaus.feedback.parseCrashTimestamp
import com.nibhaus.ui.Eyebrow
import com.nibhaus.ui.InkViewModel
import com.nibhaus.ui.MonoBadge
import com.nibhaus.ui.StatusChip
import com.nibhaus.ui.common.BrandWordmark
import com.nibhaus.ui.common.EmptyState
import com.nibhaus.ui.common.InkAppBar
import com.nibhaus.ui.common.NibBadge
import com.nibhaus.ui.common.ThumbRow
import com.nibhaus.ui.common.TipCard
import com.nibhaus.ui.common.rememberHaptics
import com.nibhaus.ui.library.PageThumb
import com.nibhaus.ui.riseIn
import com.nibhaus.ui.steelCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun PensHome(
    vm: InkViewModel,
    pen: PenConnState,
    notebooks: List<com.nibhaus.data.NotebookEntity>,
    onScan: () -> Unit,
    onSettings: () -> Unit,
    onSearch: () -> Unit,
    onCheckConnection: () -> Unit,
    onOpenActivity: () -> Unit,
    onOpenNotebook: (String) -> Unit,
    onOpenPage: (notebookId: String, pageId: String) -> Unit,
    onFindMyPen: () -> Unit,
    onOpenSyncSettings: () -> Unit,
) {
    // Feature 1: pages grouped into one row per notebook (newest-edited first), plus the notebook
    // entities themselves (for each row's title) — both batched/shared, not per-row (perf audit
    // P1-1); see InkViewModel.recentByNotebook / groupRecentByNotebook.
    val recentRowsRaw by vm.recentByNotebook.collectAsStateWithLifecycle()
    // #6 soft delete: a just-deleted page/notebook drops out of Recent instantly, during its undo
    // window too — not just once the real delete finishes.
    val pendingDeletedPages by vm.pendingDeletedPageIds.collectAsStateWithLifecycle()
    val pendingDeletedNotebooks by vm.pendingDeletedNotebookIds.collectAsStateWithLifecycle()
    val recentRows = remember(recentRowsRaw, pendingDeletedPages, pendingDeletedNotebooks) {
        recentRowsRaw.mapNotNull { (notebookId, rowPages) ->
            if (notebookId in pendingDeletedNotebooks) return@mapNotNull null
            val visible = rowPages.filterNot { it.id in pendingDeletedPages }
            if (visible.isEmpty()) null else notebookId to visible
        }
    }
    val notebooksById = remember(notebooks) { notebooks.associateBy { it.id } }
    val pagesWithAudio by vm.pagesWithAudio.collectAsStateWithLifecycle()
    // Feature 2: saved-pen reconnect tiles. A pen with 2+ paired devices gets 2+ tiles (one per saved
    // pen, most-recently-connected first — the order upsertSavedPen already maintains); the
    // CURRENTLY connected pen's own tile is suppressed (it would be a redundant reconnect affordance
    // for the one pen PenStatusCard already shows as connected), but every OTHER saved pen still shows
    // its (dimmed, non-ready — see presenceScanActive below) tile so the user can see/reach them.
    // Tapping one of those while a different pen is connected reuses connectSaved → penManager.connect
    // unchanged from 6847e52: it does NOT disconnect the currently-connected pen first (see
    // PenConnectionManager.connect's doc) — same behavior as tapping "TAP TO CONNECT" while connected
    // would have, not something this refinement changes.
    val savedPens by vm.savedPens.collectAsStateWithLifecycle()
    val savedPenConnectState by vm.savedPenConnectState.collectAsStateWithLifecycle()
    val connectedSpp = (pen as? PenConnState.Connected)?.mac
    val visibleSavedPens = remember(savedPens, connectedSpp) { savedPens.filter { it.spp != connectedSpp } }
    val showSavedPens = visibleSavedPens.isNotEmpty()
    // Feature 2 refinement: a background presence scan lights up each saved tile's "READY" dot when
    // its pen is actually advertising nearby. Only while THIS screen is visible (this composable is
    // torn down — and the DisposableEffect below disposes — on tab switch / any overlay, per the
    // AnimatedContent structure around PensHome's call site) AND the pen is fully Disconnected (not
    // mid connect/reconnect/locked — that BLE work owns the radio) AND there's something to light up.
    // Goes through vm.startPresenceScan()/stopPresenceScan() — a ref-counted facade over the shared
    // BLE scanner (bug #2's SharedPenScanner), NOT a raw start()/stop() — so a saved-tile tap's own
    // scan-and-connect (ServiceLocator.scanForSavedPen, sharing the SAME underlying scanner) can run
    // concurrently without either side's stop() killing the other's still-active scan. That also
    // means this effect no longer needs re-keying on savedPenConnectState (the previous workaround for
    // exactly that contention) — this client's own hold on the scanner is unaffected by scanForSavedPen
    // starting/stopping its own.
    val readyPens by vm.readyPens.collectAsStateWithLifecycle()
    val presenceScanActive = pen is PenConnState.Disconnected && visibleSavedPens.isNotEmpty()
    DisposableEffect(presenceScanActive) {
        if (presenceScanActive) vm.startPresenceScan()
        onDispose { vm.stopPresenceScan() }
    }
    // Feature 23: pull-to-refresh. The Room flows behind this screen are already live, so the
    // gesture itself is mostly reassurance — a brief spinner acknowledging the pull, styled with the
    // app's own ink-primary color rather than the Material default.
    val cs = MaterialTheme.colorScheme
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
        item { BrandWordmark(Modifier.padding(top = 14.dp, bottom = 2.dp).riseIn(0)) }
        item {
            Box(Modifier.riseIn(1)) {
                InkAppBar(title = "Pens", sub = if (pen is PenConnState.Connected) "1 connected" else "no pen") {
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Outlined.Search, contentDescription = "Search handwriting")
                    }
                    IconButton(onClick = onCheckConnection) {
                        Icon(Icons.Outlined.Troubleshoot, contentDescription = "Check connection")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                }
            }
        }
        item { Box(Modifier.riseIn(2)) { PenStatusCard(vm, pen, onScan, onFindMyPen) } }
        if (showSavedPens) {
            itemsIndexed(visibleSavedPens, key = { _, saved -> "saved-" + saved.spp }) { idx, saved ->
                Box(Modifier.riseIn(3 + idx)) {
                    SavedPenTile(
                        pen = saved,
                        connectState = savedPenConnectState,
                        ready = saved.spp in readyPens,
                        onTap = { vm.connectSaved(saved.spp) },
                        onForget = { vm.forgetSavedPen(saved.spp) },
                    )
                }
            }
        }
        val afterSaved = if (showSavedPens) 3 + visibleSavedPens.size else 3
        item { Box(Modifier.riseIn(afterSaved)) { SyncStatusCard(vm, onOpenActivity, onOpenSyncSettings) } }
        item {
            // Feature 5 tip card: nudge toward the printed Share/Email buttons once there's enough
            // captured to plausibly have a page worth sharing, and only if never discovered on their own.
            val totalPages by vm.totalPageCount.collectAsStateWithLifecycle()
            val everZoneTapped by vm.everZoneTapped.collectAsStateWithLifecycle()
            val dismissed by vm.tipPrintedButtonsDismissed.collectAsStateWithLifecycle()
            if (printedButtonTipEligible(totalPages, everZoneTapped, dismissed)) {
                TipCard(
                    "The Share and Email icons printed at the top of your page work. Tap them with the pen.",
                    Modifier.padding(top = 10.dp).riseIn(afterSaved),
                    onDismiss = vm::dismissPrintedButtonsTip,
                )
            }
        }
        item {
            val transcribing by vm.transcribeProgress.collectAsStateWithLifecycle()
            transcribing?.let { p ->
                // #9: cooperative cancel for the eager background pass — whatever page already
                // finished keeps its transcript; only the pages not yet reached are skipped (and stay
                // eligible for the next pass). See InkViewModel.cancelEagerTranscription.
                Row(
                    Modifier.padding(start = 4.dp, top = 10.dp).riseIn(afterSaved + 1),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MonoBadge("TRANSCRIBING ${p.done} / ${p.total}", leadingDot = true)
                    IconButton(onClick = vm::cancelEagerTranscription, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = "Cancel transcribing",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
        item {
            Eyebrow(
                "Recent notebooks",
                Modifier.padding(start = 4.dp, top = 18.dp, bottom = 10.dp).riseIn(afterSaved + 1),
            )
        }
        if (recentRows.isEmpty()) {
            // First-run home surface (design-system §9): a real empty state, not just a caption —
            // this is the very first thing a brand-new user sees.
            item {
                EmptyState(
                    icon = Icons.Outlined.Draw,
                    text = "The pen writes its coordinates as it goes, so it only recognizes the notebook made for this pen. A regular notebook will not work.",
                    modifier = Modifier.riseIn(afterSaved + 2),
                    headline = "Pair a pen to begin",
                    primaryActionLabel = "Pair a pen",
                    onPrimaryAction = onScan,
                )
            }
        } else {
            itemsIndexed(recentRows, key = { _, row -> row.first }) { idx, (notebookId, pages) ->
                Box(Modifier.riseIn(afterSaved + 2 + idx)) {
                    RecentNotebookRow(
                        title = notebooksById[notebookId]?.title ?: "Untitled",
                        pages = pages,
                        vm = vm,
                        pagesWithAudio = pagesWithAudio,
                        onOpenNotebook = { onOpenNotebook(notebookId) },
                        onOpenPage = { pageId -> onOpenPage(notebookId, pageId) },
                    )
                }
            }
        }
        item {
            // Crash capture + next-launch prompt (user-initiated feedback, part 2): shown once per
            // distinct crash (identified by its CRASH_AT timestamp read from the crash file written
            // in NibhausApp.onCreate). Dismiss OR review both acknowledge it, so the same crash
            // never nags again — a later, different crash still will. Placed after Recent notebooks
            // (field report, 2026-07-05), not right under Sync: on a first-run/empty-library screen
            // it was pushing the "Pair a pen to begin" CTA below the fold on smaller phones.
            val context = LocalContext.current
            val crashContent = remember { CrashCapture.readLastCrash(context) }
            val crashTimestamp = remember(crashContent) { parseCrashTimestamp(crashContent) }
            val ackedTimestamp by vm.feedbackCrashAcked.collectAsStateWithLifecycle()
            var showCrashFeedback by remember { mutableStateOf(false) }
            val premium by vm.isPremium.collectAsStateWithLifecycle()
            val palette by vm.activePalette.collectAsStateWithLifecycle()
            if (crashPromptEligible(crashTimestamp, ackedTimestamp)) {
                CrashReportCard(
                    modifier = Modifier.padding(top = 10.dp).riseIn(afterSaved + 3 + recentRows.size),
                    onReview = { vm.acknowledgeCrash(crashTimestamp!!); showCrashFeedback = true },
                    onDismiss = { vm.acknowledgeCrash(crashTimestamp!!) },
                )
            }
            if (showCrashFeedback) {
                val bundle = remember(premium, palette, crashContent) {
                    buildFeedbackBundle(premium, palette.id, crashTail(crashReportBody(crashContent)))
                }
                Dialog(
                    onDismissRequest = { showCrashFeedback = false },
                    properties = DialogProperties(usePlatformDefaultWidth = false),
                ) {
                    FeedbackScreen(bundle = bundle, onClose = { showCrashFeedback = false })
                }
            }
        }
        item { Spacer(Modifier.height(88.dp)) } // clear the FAB
    }
    }
}

/**
 * Feature 1 (Pens home "Recent" section): one row per notebook — a tappable title header (opens the
 * notebook) plus up to 3 of its most recently edited pages, newest→oldest left-to-right. Reuses
 * [ThumbRow]/[PageThumb] — no new visual primitive.
 */
@Composable
private fun RecentNotebookRow(
    title: String,
    pages: List<com.nibhaus.data.PageEntity>,
    vm: InkViewModel,
    pagesWithAudio: Set<String>,
    onOpenNotebook: () -> Unit,
    onOpenPage: (String) -> Unit,
) {
    Column(Modifier.padding(bottom = 6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenNotebook).padding(vertical = 6.dp),
        )
        ThumbRow(pages, 3) { page, m ->
            PageThumb(page.id, "Page ${page.page}", vm, page.id in pagesWithAudio, page.book, m) { onOpenPage(page.id) }
        }
    }
}

/**
 * Feature 2: a saved pen's reconnect tile — pen name + "TAP TO RECONNECT", matching [PenStatusCard]'s
 * idiom (steelCard, monoData tag). Tap re-scans and connects ([InkViewModel.connectSaved]); shows a
 * transient "SEARCHING…" tag while that scan runs, and a "not found" line if it times out. Long-press
 * → confirm dialog to forget the pen.
 *
 * Feature 2 refinement: when [ready] (the pen was actually sighted by the presence scan within its
 * hold window — see [InkViewModel.readyPens]), the tile switches to the same live idiom
 * [PenStatusCard] uses for an actually-connected pen — a pulsing [NibBadge] dot plus a [StatusChip] —
 * instead of the dimmer static "TAP TO RECONNECT" text, so the user can tell at a glance which saved
 * pen (of possibly several) is actually in range right now.
 */
@Composable
private fun SavedPenTile(
    pen: com.nibhaus.pen.SavedPen,
    connectState: com.nibhaus.pen.SavedPenConnectState,
    ready: Boolean,
    onTap: () -> Unit,
    onForget: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val searching = connectState is com.nibhaus.pen.SavedPenConnectState.Searching && connectState.spp == pen.spp
    val notFound = connectState is com.nibhaus.pen.SavedPenConnectState.NotFound && connectState.spp == pen.spp
    val name = pen.name.ifBlank { "Smartpen" }
    var confirmForget by remember { mutableStateOf(false) }
    // Feature 20a: a light tick the moment this tile actually flips to READY (presence-scan sighted
    // it), not on every recomposition while it stays ready.
    val haptics = rememberHaptics()
    var wasReady by remember { mutableStateOf(ready) }
    LaunchedEffect(ready) {
        if (ready && !wasReady) haptics.tick()
        wasReady = ready
    }
    Box(
        Modifier.fillMaxWidth().padding(top = 4.dp)
            .combinedClickable(onClick = { if (!searching) onTap() }, onLongClick = { confirmForget = true })
            .steelCard(radius = 18.dp)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            NibBadge(live = ready && !searching)
            Spacer(Modifier.size(13.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                when {
                    searching -> Text("SEARCHING…", style = monoData, color = cs.tertiary)
                    ready -> StatusChip("READY · TAP TO CONNECT", Modifier.padding(top = 2.dp, bottom = 2.dp))
                    else -> Text("TAP TO RECONNECT", style = monoData, color = cs.onSurfaceVariant)
                }
                if (notFound) {
                    Text("Not found. Is the pen on?", style = MaterialTheme.typography.bodySmall, color = cs.error)
                }
            }
            TextButton(onClick = { confirmForget = true }) {
                Text("Forget", color = cs.error)
            }
        }
    }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget this pen?") },
            text = { Text("\"$name\" won't show a reconnect tile anymore.") },
            confirmButton = {
                TextButton(onClick = { confirmForget = false; onForget() }) { Text("Forget", color = cs.error) }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
        )
    }
}
