package com.nibhaus.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nibhaus.pen.PenConnState
import com.nibhaus.ui.settings.SettingsScreen
import com.nibhaus.ui.theme.G2
import com.nibhaus.pen.BatteryOptimization
import com.nibhaus.ui.theme.LocalInkExtras
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.nibhaus.ui.common.AppSnackbar
import com.nibhaus.ui.common.LocalAppSnackbar
import com.nibhaus.ui.home.ActivityScreen
import com.nibhaus.ui.home.ConnectionDiagnosticScreen
import com.nibhaus.ui.home.PensHome
import com.nibhaus.ui.library.LibraryScreen
import com.nibhaus.ui.livecapture.LiveCaptureScreen
import com.nibhaus.ui.onboarding.FirstRunCoach
import com.nibhaus.ui.pagedetail.PageDetail
import com.nibhaus.ui.scan.ScanScreen

private enum class Tab { PENS, LIBRARY, ACTIVITY }

// The full-screen takeovers that used to hard-cut in via early `return` (each `if (showX) { ...; return }`
// broke out before the home AnimatedContent ever ran). NONE means "show the bottom-nav home". Order here
// is the same priority the old sequential `if`s used, so if more than one show* flag is ever true at once
// (shouldn't happen) the same one wins.
private enum class Overlay { NONE, SEARCH, CAPTURE_LAB, FIND_MY_PEN, SETTINGS, SCAN, LIVE, DIAG }

/**
 * Root shell for the app (see docs/UI_V3_GAP_AND_PLAN.md — "Nib & Ink"): a bottom-nav scaffold
 * over Pens / Library / Activity, plus full-screen takeovers for a single page, scanning, and
 * settings. The bold lives in one place — the ink gradient on the Ncode dot-grid — everything
 * else stays quiet.
 */
@Composable
fun InkApp(vm: InkViewModel) {
    // Hoist reduced-motion at the very top (before any early returns) so it can be closed over
    // by non-@Composable lambdas such as AnimatedContent's transitionSpec.
    val reduced = rememberReducedMotion()

    // The vault "unlocks" on cold start only — gated on NibSplashState's process-lifetime flag,
    // not rememberSaveable (which is restored from the saved-state bundle a config change/rotation
    // recreates, so it can't distinguish "still the same process" from "still the same Activity").
    // Reduced-motion users skip the ceremony outright and land straight on the real content.
    var splashDone by remember { mutableStateOf(reduced || NibSplashState.shownThisProcess) }
    if (!splashDone) {
        NibSplash(onDone = { NibSplashState.shownThisProcess = true; splashDone = true })
        return
    }

    val notebooks by vm.notebooks.collectAsStateWithLifecycle()
    val pages by vm.pages.collectAsStateWithLifecycle()
    val strokes by vm.strokes.collectAsStateWithLifecycle()
    val pen by vm.penState.collectAsStateWithLifecycle()
    val pageId by vm.selectedPageId.collectAsStateWithLifecycle()
    val notebookId by vm.selectedNotebookId.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(Tab.PENS) }
    var showSettings by remember { mutableStateOf(false) }
    // Which Settings tab to land on when it opens ("Sync & Text" = 1) — the pending card deep-links
    // here when the backlog is stuck for lack of a configured sync target; the gear icon resets it.
    var settingsInitialTab by remember { mutableStateOf(0) }
    var showScan by remember { mutableStateOf(false) }
    var showLive by remember { mutableStateOf(false) }
    var showDiag by remember { mutableStateOf(false) }
    var showCaptureLab by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showFindMyPen by remember { mutableStateOf(false) }
    var showExitPrompt by remember { mutableStateOf(false) }

    // Auto-open the live capture screen the instant the pen starts writing — so ink shows up live
    // without tapping the FAB — from ANY passive viewing context (the Pens/Library/Activity home, a
    // notebook's page grid, or a page you're reading). Suppressed only while a modal/overlay is up or
    // we're already on the live screen, so it never yanks you out of Settings/scan/search/etc. Fires
    // only for ink that just landed (not a stale page on launch) and not twice for the same stroke.
    // (Previously it required the pristine bare Pens home — notebookId==null && pageId==null && the
    // Pens tab — so once you'd opened any notebook, notebookId stayed set and it never fired again.)
    val lastInk by vm.lastInkAt.collectAsStateWithLifecycle()
    var lastAutoOpenInk by remember { mutableStateOf(0L) }
    val canAutoOpen = !showSettings && !showScan && !showLive && !showDiag &&
        !showCaptureLab && !showSearch && !showFindMyPen && !showExitPrompt
    LaunchedEffect(lastInk, canAutoOpen) {
        val t = lastInk ?: return@LaunchedEffect
        if (canAutoOpen && t > lastAutoOpenInk && System.currentTimeMillis() - t < 4_000) {
            lastAutoOpenInk = t
            showLive = true
        }
    }

    // The OS Back button mirrors the in-app Back: pop overlays → page → notebook → back to the Pens
    // tab. At the root it asks before leaving instead of dropping straight to the home screen.
    BackHandler {
        when {
            showExitPrompt -> showExitPrompt = false
            showSearch -> showSearch = false
            showCaptureLab -> showCaptureLab = false
            showFindMyPen -> showFindMyPen = false
            showSettings -> showSettings = false
            showScan -> showScan = false
            showLive -> showLive = false
            showDiag -> showDiag = false
            pageId != null || notebookId != null -> vm.back()
            tab != Tab.PENS -> tab = Tab.PENS
            else -> showExitPrompt = true
        }
    }

    // A locked pen can ask for its password from any screen — surface it globally.
    val passwordPrompt = pen as? PenConnState.PasswordRequired
    if (passwordPrompt != null) {
        PasswordDialog(
            wrongAttempt = passwordPrompt.wrongAttempt,
            attemptsRemaining = passwordPrompt.attemptsRemaining,
            onSubmit = vm::submitPassword,
            onCancel = vm::disconnect,
        )
    }

    // A notebook the app hasn't set up prompts once (from any screen) for its type + a label.
    val notebookSetup by vm.notebookNeedingSetup.collectAsStateWithLifecycle()
    notebookSetup?.let { nb ->
        NotebookSetupDialog(
            notebook = nb,
            defaultTypeId = vm.resolvedTypeId(nb.book),
            onSave = { typeId, label -> vm.setUpNotebook(nb.id, nb.book, typeId, label) },
            onSkip = { vm.skipNotebookSetup(nb.id) },
        )
    }
    // First-connect nudge (FIX #2): when a pen connects while the app isn't battery-exempt, prompt
    // once to allow background capture. Dismissible + persisted, so it asks only once; the Settings
    // "Capture reliability" card is the permanent path.
    val nudgeCtx = LocalContext.current
    val bgNudgeDismissed by vm.bgCaptureNudgeDismissed.collectAsStateWithLifecycle()
    var showBgNudge by remember { mutableStateOf(false) }
    LaunchedEffect(pen, bgNudgeDismissed) {
        if (pen is PenConnState.Connected && !bgNudgeDismissed && !BatteryOptimization.isIgnoring(nudgeCtx)) {
            showBgNudge = true
        }
    }
    if (showBgNudge) {
        BackgroundCaptureNudgeDialog(
            onAllow = { showBgNudge = false; vm.dismissBgCaptureNudge(); BatteryOptimization.openSettings(nudgeCtx) },
            onDismiss = { showBgNudge = false; vm.dismissBgCaptureNudge() },
        )
    }
    // First-run guided coach (#1): shown once, over the Pens screen, after the splash. Gated on the
    // persisted flag having actually loaded (null) rather than a default-false — an existing user must
    // never see it flash on screen while the DataStore read is still in flight.
    val coachDone by vm.onboardingCoachDone.collectAsStateWithLifecycle()
    var showCoach by remember { mutableStateOf(false) }
    LaunchedEffect(coachDone) { if (coachDone == false) showCoach = true }
    if (showCoach) {
        FirstRunCoach(pen = pen, onDone = { showCoach = false; vm.completeOnboardingCoach() })
    }
    if (showExitPrompt) {
        val activity = LocalContext.current.activity()
        ExitConfirmDialog(onDismiss = { showExitPrompt = false }, onConfirm = { activity?.finish() })
    }

    // Same priority order the old sequential `if (showX) { ...; return }` chain used.
    val overlay = when {
        showSearch -> Overlay.SEARCH
        showCaptureLab -> Overlay.CAPTURE_LAB
        showFindMyPen -> Overlay.FIND_MY_PEN
        showSettings -> Overlay.SETTINGS
        showScan -> Overlay.SCAN
        showLive -> Overlay.LIVE
        showDiag -> Overlay.DIAG
        else -> Overlay.NONE
    }

    // One app-wide snackbar surface (#6 UNDO, #7 success confirmation, #8 failure + Retry) — every
    // screen below reaches it via LocalAppSnackbar instead of threading a callback through each of
    // their parameter lists. Lives at this nav-host level so it survives switching tabs/overlays.
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    val appSnackbar = remember(snackbarHostState, snackbarScope) { AppSnackbar(snackbarHostState, snackbarScope) }
    // #7: every export / save-transcript / on-device-transcribe completion already lands in
    // vm.exportStatus (one shared "brief status" channel) — surface it as a snackbar from here so
    // it's consistent no matter which screen (page detail, or a Settings action like "re-export
    // all") set it, instead of each screen growing its own inline status line.
    val exportStatus by vm.exportStatus.collectAsStateWithLifecycle()
    LaunchedEffect(exportStatus) { exportStatus?.let { appSnackbar.show(it) } }
    CompositionLocalProvider(LocalAppSnackbar provides appSnackbar) {
    Box(Modifier.fillMaxSize()) {
    // Full-screen takeovers now animate in/out with the same shared-axis X used for tab switches
    // (opening pushes in from the right, closing reveals from the left) instead of hard-cutting.
    // Each overlay's remembered state still resets on exit — same as the old `if { ...; return }`
    // chain, since leaving NONE→X→NONE tears the composable down either way.
    AnimatedContent(
        targetState = overlay,
        transitionSpec = { sharedAxisX(forward = targetState != Overlay.NONE, reduced = reduced) },
        label = "overlay",
    ) { ov ->
        when (ov) {
            Overlay.SEARCH -> SearchScreen(vm, onBack = { showSearch = false })
            Overlay.CAPTURE_LAB -> CaptureLabScreen(vm, onBack = { showCaptureLab = false })
            Overlay.FIND_MY_PEN -> FindMyPenScreen(vm, onBack = { showFindMyPen = false })
            Overlay.SETTINGS -> SettingsScreen(
                vm,
                onBack = { showSettings = false },
                onOpenCaptureLab = { showSettings = false; showCaptureLab = true },
                initialTab = settingsInitialTab,
            )
            Overlay.SCAN -> ScanScreen(vm, onBack = { showScan = false })
            Overlay.LIVE -> LiveCaptureScreen(vm, onBack = { showLive = false })
            Overlay.DIAG -> ConnectionDiagnosticScreen(vm, onBack = { showDiag = false })
            Overlay.NONE -> {
                // Opening a page from the library reads as a 3D page-turn (design-system §8): the
                // detail rotates in from −18° on open (closing uses fade — see ui-C2-report.md for
                // note on reverse). Tab changes (inner) use shared-axis X.
                AnimatedContent(
                    targetState = pageId != null,
                    transitionSpec = { containerTransform(opening = targetState, reduced = reduced) },
                    label = "page",
                ) { onPage ->
                    if (onPage) {
                        // Nib V3 3D page-turn: rotates in from −18° with a generous camera distance so
                        // the perspective is dramatic but not distorted. Reduced motion snaps to 0°.
                        val pageRotY = remember { Animatable(if (reduced) 0f else -18f) }
                        LaunchedEffect(Unit) {
                            if (!reduced) pageRotY.animateTo(0f, tween(420, easing = NibEasing))
                        }
                        Box(
                            modifier = Modifier.fillMaxSize().graphicsLayer {
                                rotationY = pageRotY.value
                                cameraDistance = 24f * density
                            },
                        ) {
                            PageDetail(strokes, vm)
                        }
                    } else {
                        Scaffold(
                            bottomBar = { InkBottomNav(tab) { tab = it } },
                            floatingActionButton = {
                                if (tab == Tab.PENS) {
                                    // V3 NibFab: squircle ink-gradient FAB with float + grad-pan animations.
                                    val connected = pen is PenConnState.Connected
                                    NibFab(
                                        onClick = { if (connected) showLive = true else showScan = true },
                                    )
                                }
                            },
                        ) { inner ->
                            Box(Modifier.fillMaxSize().padding(inner)) {
                                // V3 phone-frame top radial glow — indigo halo from top-center, behind content.
                                // Spec: radial(120% 80% at 50% -10%, #5B5BF6@0.10 → transparent 55%).
                                // In light theme the gradient is "the one bright thing" — glow is barely-there
                                // (4% alpha) so it doesn't wash out the paper ground.
                                val glowAlpha = if (LocalInkExtras.current.isDark) 0.10f else 0.04f
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(260.dp)
                                        .align(Alignment.TopCenter)
                                        .background(
                                            Brush.radialGradient(
                                                colors = listOf(G2.copy(alpha = glowAlpha), Color.Transparent),
                                            )
                                        )
                                )
                                AnimatedContent(
                                    targetState = tab,
                                    transitionSpec = {
                                        sharedAxisX(forward = targetState.ordinal > initialState.ordinal, reduced = reduced)
                                    },
                                    label = "tab",
                                ) { t ->
                                    when (t) {
                                        Tab.PENS -> PensHome(
                                            vm, pen, notebooks,
                                            onScan = { showScan = true },
                                            onSettings = { settingsInitialTab = 0; showSettings = true },
                                            onSearch = { showSearch = true },
                                            onCheckConnection = { showDiag = true },
                                            onOpenActivity = { tab = Tab.ACTIVITY },
                                            // Open the notebook AND switch to the Library tab so its pages show
                                            // (selecting alone left the user on the Pens screen — looked dead).
                                            onOpenNotebook = { vm.openNotebook(it); tab = Tab.LIBRARY },
                                            // tapping a Recent-row page opens straight to that page.
                                            onOpenPage = { notebookId, pageId ->
                                                vm.openNotebook(notebookId); vm.openPage(pageId); tab = Tab.LIBRARY
                                            },
                                            onFindMyPen = { showFindMyPen = true },
                                            // Pending card, when stuck for lack of a configured sync target: jump
                                            // straight to Settings → Sync & Text instead of the (empty) Activity tab.
                                            onOpenSyncSettings = { settingsInitialTab = 1; showSettings = true },
                                        )
                                        Tab.LIBRARY -> LibraryScreen(vm, notebooks, pages, onScan = { showScan = true })
                                        Tab.ACTIVITY -> ActivityScreen(vm)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    // A pen tap on a printed Share/Email button (a chooser-kind action zone) parks its PNG-vs-PDF
    // pick here — lives inside this CompositionLocalProvider (not as a MainActivity-level sibling
    // of InkApp) so its own share confirmation (#7) can reach the same app-wide snackbar.
    ZoneShareChooser()
    SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
    }
    }
}

/**
 * Nib V3 shared-axis X tab transition: the incoming pane slides in ~36 dp horizontally
 * (≈10 % of screen width), scales from 0.98→1, and fades in; the outgoing pane reverses.
 * Duration 300 ms with [NibEasing]. Under reduced motion, falls back to a plain cross-fade.
 * Direction: positive [forward] means the new tab has a higher ordinal → content enters from
 * the right (feels natural for left-to-right tab progression).
 */
internal fun sharedAxisX(forward: Boolean, reduced: Boolean = false): ContentTransform {
    if (reduced) {
        return ContentTransform(
            targetContentEnter = fadeIn(tween(300, easing = NibEasing)),
            initialContentExit = fadeOut(tween(200)),
            sizeTransform = null,
        )
    }
    val dir = if (forward) 1 else -1
    return ContentTransform(
        targetContentEnter = slideInHorizontally(tween(300, easing = NibEasing)) { it / 10 * dir } +
            scaleIn(tween(300, easing = NibEasing), initialScale = 0.98f) +
            fadeIn(tween(300, easing = NibEasing)),
        initialContentExit = slideOutHorizontally(tween(300, easing = NibEasing)) { -it / 10 * dir } +
            scaleOut(tween(300, easing = NibEasing), targetScale = 0.98f) +
            fadeOut(tween(220)),
        sizeTransform = null,
    )
}

/**
 * Nib V3 container transform for page open/close (design-system §8).
 * The 3D rotationY is applied as a [graphicsLayer] on the PageDetail inside the content block
 * above — this function handles the background-layer fade so they don't fight each other.
 * Opening: detail fades in over 420 ms (rotationY does the drama); library fades out quickly.
 * Closing: library fades back in; detail fades out (reverse rotationY is noted in ui-C2-report.md).
 * Under reduced motion, plain cross-fade with no rotation.
 */
private fun containerTransform(opening: Boolean, reduced: Boolean = false): ContentTransform =
    if (reduced) {
        ContentTransform(
            targetContentEnter = fadeIn(tween(300)),
            initialContentExit = fadeOut(tween(200)),
            sizeTransform = null,
        )
    } else if (opening) {
        // #26a shared-element approximation: PageDetail opens from several different entry points
        // (library grid, favorites, tag filter, Pens-home recent rows, search, the page filmstrip)
        // that don't share one SharedTransitionScope with the detail view, so a literal
        // position-anchored shared element isn't feasible across this overlay architecture — this
        // scale+fade reads as "growing out of the tapped thumbnail" without tracking its actual
        // on-screen position. See ui-C2-report.md for the existing rotationY half of this transition.
        ContentTransform(
            targetContentEnter = fadeIn(tween(420, easing = NibEasing)) +
                scaleIn(tween(420, easing = NibEasing), initialScale = 0.92f),
            initialContentExit = fadeOut(tween(200)),
            sizeTransform = null,
        )
    } else {
        ContentTransform(
            targetContentEnter = fadeIn(tween(220)),
            initialContentExit = fadeOut(tween(420, easing = NibEasing)) +
                scaleOut(tween(420, easing = NibEasing), targetScale = 0.92f),
            sizeTransform = null,
        )
    }

@Composable
private fun InkBottomNav(selected: Tab, onSelect: (Tab) -> Unit) {
    NibBottomBar(
        items = listOf(
            NavItem(Icons.Outlined.Edit, "Pens"),
            NavItem(Icons.Outlined.GridView, "Library"),
            NavItem(Icons.Outlined.History, "Activity"),
        ),
        selected = selected.ordinal,
        onSelect = { onSelect(Tab.entries[it]) },
    )
}

/** First-connect nudge (FIX #2) to allow background capture — once, dismissible. */
@Composable
private fun BackgroundCaptureNudgeDialog(onAllow: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Keep capturing in the background?", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Text(
                "Your device may sleep Nibhaus when it's not on screen, which drops the pen and " +
                    "pauses capture. Allow background activity so your strokes keep saving even when " +
                    "the app is in the background.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = { Button(onClick = onAllow) { Text("Allow") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

/** Confirm before leaving the app (the OS Back at the root), so it isn't an accidental exit. */
@Composable
private fun ExitConfirmDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Exit Nibhaus?", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Text(
                "The pen stays connected in the background while you're away.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Yes") } },
        dismissButton = { Button(onClick = onDismiss) { Text("No") } },
    )
}

/** Unwrap an Activity from a (possibly wrapped) Context — for finishing the app on exit-confirm. */
private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** Shown when a locked pen sends PASSWORD_REQUEST. A wrong entry just re-prompts.
 *  Pens ship with no password set — this only appears once someone (the current owner or a
 *  previous one) has set one on the pen itself, so the copy must never imply a factory default. */
@Composable
private fun PasswordDialog(
    wrongAttempt: Boolean = false,
    attemptsRemaining: Int? = null,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var pw by remember { mutableStateOf("") }
    // The count line shows at every prompt (like Neo Studio's constant counter), not just after a
    // wrong try, so the user always knows how many attempts stand between them and a wiping reset.
    val countLine = attemptsRemaining?.let {
        "$it ${if (it == 1) "try" else "tries"} left before the pen resets and erases its data."
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("This pen has a password", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column {
                if (wrongAttempt) {
                    Text(
                        "Incorrect password. " + (countLine ?: "The pen resets and erases its data after too many wrong tries."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (countLine != null) {
                    Text(countLine, style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "You (or a previous owner) set a password on this pen. Enter it to connect.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = pw,
                    onValueChange = { pw = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
            }
        },
        confirmButton = { Button(onClick = { onSubmit(pw) }) { Text("Unlock") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
