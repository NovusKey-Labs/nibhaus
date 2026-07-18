package com.nibhaus.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.nibhaus.di.ServiceLocator
import com.nibhaus.di.StartupProgress
import com.nibhaus.pen.PenForegroundService
import com.nibhaus.security.AppLock
import com.nibhaus.ui.theme.LocalPaperTemplate
import com.nibhaus.widget.WIDGET_PAGE_ID_EXTRA
import kotlinx.coroutines.launch
import java.util.UUID

internal fun validatedWidgetPageId(raw: String?): String? {
    if (raw == null || raw.length != 36) return null
    return raw.takeIf { runCatching { UUID.fromString(it).toString() }.getOrNull() == it }
}

// FragmentActivity (not bare ComponentActivity) so the AndroidX BiometricPrompt can attach — see AppLock.
class MainActivity : FragmentActivity() {

    // Nib unlocked for this foreground session (Section C1); re-locked in onStop when backgrounded.
    private var unlocked by mutableStateOf(false)
    private var appLockEnabled = false

    private val viewModel: InkViewModel by viewModels {
        val sl = ServiceLocator.from(applicationContext)
        val cm = applicationContext.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                InkViewModel(
                    repo = sl.repository,
                    settings = sl.settings,
                    pen = PenDeps(
                        penManager = sl.penManager,
                        scanner = sl.penScanner,
                        sharedScanner = sl.sharedPenScanner,
                        hasStoredPassword = sl.penPassword::has,
                        signals = sl.captureSignals,
                        connectSaved = sl::connectSaved,
                        savedPenConnectState = sl.savedPenConnectState,
                    ),
                    sync = SyncDeps(
                        exportPageNow = sl::exportPageNow,
                        reexportAll = sl::reexportAllPages,
                        deletePageOp = sl::deletePageNow,
                        deleteNotebookOp = sl::deleteNotebookNow,
                        restoreBackup = sl::restoreFromFolder,
                        shareSelected = sl::shareSelectedPages,
                    ),
                    ocr = OcrDeps(
                        transcripts = sl.transcriptImporter,
                        transcribeOnDevice = sl::transcribeOnDevice,
                        saveTranscriptOp = sl::saveTranscript,
                        vlmState = sl.vlmModelStateFlow,
                        isMetered = { cm.isActiveNetworkMetered },
                        premiumPresent = sl.premiumPresent,
                    ),
                    zones = ZoneDeps(
                        actionZones = sl.actionZones,
                        captureTrace = sl::captureNextTrace,
                        cancelTrace = sl::cancelTraceCapture,
                    ),
                    editor = sl.strokeEditor,
                    inkColor = sl.inkColor,
                    inkWidth = sl.inkWidth,
                    recorder = sl.recordingController,
                    calendar = sl.calendarGateway,
                    captureLog = sl.captureLog,
                    backgroundStore = sl.backgrounds,
                    translator = sl.translator,
                    notebookProfiles = sl.notebookProfiles,
                )
                    // Eager Tier-0 transcription: auto-OCR pages in the background so search works
                    // with zero taps. Started here (once, for the app's one long-lived VM) rather
                    // than in InkViewModel's init, so it doesn't fire for every VM built in tests.
                    .also { it.startEagerTranscription() } as T
        }
    }

    // Auto-connect to the build-time pen MAC if one was injected (-PpenMac); otherwise the service
    // just auto-reconnects to the last-remembered pen. A scan/pair screen replaces this in Phase 1.
    private fun startPenService() {
        PenForegroundService.start(this, com.nibhaus.BuildConfig.PEN_MAC.ifBlank { null })
        // Splash milestone 2 ("connected pens"): the driver itself resolved back in ServiceLocator's
        // constructor; this is "the service actually got told to start."
        StartupProgress.markMilestone(2)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Branded launch: the vault logo on brand navy (Theme.Nibhaus.Splash), held until the saved
        // palette has loaded so the first Compose frame renders in the user's own theme — no
        // default-palette flash — then a quick fade-through handoff into the app.
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { !viewModel.paletteLoaded.value }
        splash.setOnExitAnimationListener { provider ->
            provider.view.animate()
                .alpha(0f)
                .setDuration(220L)
                .withEndAction { provider.remove() }
                .start()
        }
        super.onCreate(savedInstanceState)
        // Android 15 (targetSdk 35) draws edge-to-edge by default; opt in explicitly (transparent
        // system bars) and inset our content for them below, so the OS bars never overlap the UI.
        enableEdgeToEdge()
        // Keep the screen awake while the app is foreground so the pen connection / capture isn't
        // interrupted during use (and you don't have to keep tapping the screen while testing).
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Bluetooth rationale pre-prompt (#3): only relevant the first time BLE permission actually
        // needs asking — already-granted (or not-yet-relevant-SDK) skips straight to the old
        // eager-start behavior, same as before this feature existed.
        val needsBleRationale = blePermissions().any { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (!needsBleRationale) ensurePermissionsThenStartService()
        handleWidgetDeepLink(intent)
        setContent {
            val palette by viewModel.activePalette.collectAsStateWithLifecycle()
            val lightPaper by viewModel.lightPaper.collectAsStateWithLifecycle()
            val paperTemplate by viewModel.paperTemplate.collectAsStateWithLifecycle()
            val lockEnabled by viewModel.appLockEnabled.collectAsStateWithLifecycle()
            SideEffect {
                appLockEnabled = lockEnabled
                if (lockEnabled) {
                    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
            NibhausTheme(palette, lightPaper) {
                CompositionLocalProvider(LocalPaperTemplate provides paperTemplate) {
                    Surface(Modifier.fillMaxSize()) {
                        // systemBars ∪ displayCutout: keep chrome out from under punch-hole cameras
                        // in landscape (the status bar doesn't always cover the cutout there).
                        Box(
                            Modifier.fillMaxSize()
                                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)),
                        ) {
                            if (lockEnabled && !unlocked) {
                                LockScreen { AppLock.prompt(this@MainActivity) { ok -> if (ok) unlocked = true } }
                            } else {
                                // ZoneShareChooser() renders from inside InkApp (Screens.kt) now, so its
                                // "shared as PNG/PDF" confirmation (#7) can reach the app-wide snackbar.
                                InkApp(viewModel)
                            }
                        }
                    }
                    // Shown once, ahead of the system BLE dialogs the Continue tap triggers. Gated on
                    // the persisted flag having actually loaded (null) so it can't flash for a user who
                    // already saw it on a prior launch but hasn't granted permission yet.
                    if (needsBleRationale) {
                        when (viewModel.bleRationaleShown.collectAsStateWithLifecycle().value) {
                            null -> {}
                            true -> LaunchedEffect(Unit) { ensurePermissionsThenStartService() }
                            false -> BleRationaleDialog(
                                onContinue = { viewModel.markBleRationaleShown(); ensurePermissionsThenStartService() },
                            )
                        }
                    }
                }
            }
        }
    }

    // Hide note content before Android captures the task snapshot; FLAG_SECURE remains set while
    // app lock is enabled, blocking both screenshots and recents imagery.
    override fun onPause() {
        if (appLockEnabled && !isChangingConfigurations) unlocked = false
        super.onPause()
    }

    // Re-lock when the app is genuinely backgrounded — but not on a rotation/config change.
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) unlocked = false
    }

    // A tap on the home-screen widget (#13) while MainActivity is already running (standard launch
    // mode still delivers new intents to onNewIntent instead of a fresh instance in the common
    // single-task-on-top case) — handle the deep link again and keep it as the activity's intent.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWidgetDeepLink(intent)
    }

    /** Opens straight to the page the widget was showing when tapped, if any. Reads the page (and
     *  its notebook, via [InkViewModel.openSearchHit]) off the main thread through the existing
     *  repository — no navigation code duplicated here, just the intent → id → page lookup. */
    private fun handleWidgetDeepLink(intent: Intent) {
        val rawPageId = runCatching { intent.getStringExtra(WIDGET_PAGE_ID_EXTRA) }.getOrNull()
        val pageId = validatedWidgetPageId(rawPageId) ?: return
        lifecycleScope.launch {
            ServiceLocator.from(applicationContext).repository.pageById(pageId)?.let { viewModel.openSearchHit(it) }
        }
    }

    // ── FragmentActivity ActivityResult 16-bit shim ──────────────────────────────────────────────
    // This is a FragmentActivity (for the biometric AppLock). FragmentActivity reserves the high 16
    // bits of an activity-result requestCode to route results to Fragments, and rejects any code that
    // uses them (checkForValidRequestCode). But the androidx ActivityResult registry behind every
    // Compose rememberLauncherForActivityResult picker generates full 32-bit codes → every document/
    // folder picker (Sync folder, Crash-log folder, Backup restore, page background) crashed on launch.
    // Fix: swap the registry's oversized code for a fresh 16-bit code the framework accepts, then on
    // the way back dispatch the result to the registry ourselves under the original code (bypassing
    // FragmentActivity's high-bit fragment routing). Covers startActivityForResult-based contracts —
    // all our pickers; permissions use the legacy path below; IntentSender contracts aren't used.
    // ponytail: only 0–1 pickers are ever in flight, so the wrapping 16-bit counter never realistically
    // collides; if that ever changes, the do/while already skips codes still in flight.
    private val pendingResultCodes = HashMap<Int, Int>()   // framework 16-bit code -> registry code
    private var nextResultCode = 1

    private fun allocResultCode(): Int {
        do {
            nextResultCode = (nextResultCode + 1) and 0xFFFF
        } while (nextResultCode == 0 || pendingResultCodes.containsKey(nextResultCode))
        return nextResultCode
    }

    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        // -1 = plain startActivity (no result); codes already within 16 bits need no remap.
        if (requestCode == -1 || (requestCode and 0xFFFF.inv()) == 0) {
            super.startActivityForResult(intent, requestCode, options)
            return
        }
        val code = allocResultCode()
        pendingResultCodes[code] = requestCode
        super.startActivityForResult(intent, code, options)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val original = pendingResultCodes.remove(requestCode)
        if (original != null) {
            // Dispatch to the androidx registry directly (as ComponentActivity would) under the
            // original code — do NOT call super, whose FragmentActivity impl keys on the high bits.
            activityResultRegistry.dispatchResult(original, resultCode, data)
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun ensurePermissionsThenStartService() {
        val needed = (blePermissions() + notificationPermission())
            .filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) startPenService()
        // Legacy requestPermissions (16-bit requestCode) — NOT the androidx registry launcher: this is
        // a FragmentActivity (for BiometricPrompt), and the registry generates >16-bit codes that
        // FragmentActivity.checkForValidRequestCode rejects → first-launch crash. ponytail: don't
        // "modernize" this back to registerForActivityResult without dropping FragmentActivity.
        else ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSIONS) return
        // Need at least the BLE permissions to run the connection service.
        val bleGranted = permissions.zip(grantResults.toList())
            .filter { it.first in blePermissions() }
            .all { it.second == android.content.pm.PackageManager.PERMISSION_GRANTED }
        // OEM race (observed on Galaxy S24): starting the foreground service immediately in this
        // callback can crash with SecurityException("Starting FGS with type connectedDevice
        // requires ... BLUETOOTH_CONNECT") because the runtime grant just handed to us here isn't
        // always committed to ActivityManager yet. Defer to the next main-loop tick so the grant
        // lands first. The already-granted path (ensurePermissionsThenStartService) doesn't race.
        if (bleGranted) android.os.Handler(android.os.Looper.getMainLooper()).post { startPenService() }
    }

    private fun blePermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun notificationPermission(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            listOf(Manifest.permission.POST_NOTIFICATIONS)
        else emptyList()

    private companion object {
        // Must fit in the lower 16 bits (FragmentActivity validates this); any small constant works.
        const val REQ_PERMISSIONS = 0x1A
    }
}

/** Pen tapped a printed Share/Email button (a chooser-kind action zone): ask PNG vs PDF, then fire
 *  the existing share/email path. Reads ServiceLocator directly — one flow, no ViewModel ceremony.
 *  Called from inside [InkApp] (Screens.kt) so it shares that composition's [LocalAppSnackbar]. */
@Composable
internal fun ZoneShareChooser() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val sl = androidx.compose.runtime.remember(context) { ServiceLocator.from(context.applicationContext) }
    val pending by sl.pendingZoneShare.collectAsStateWithLifecycle()
    val p = pending ?: return
    // Feature 5: this IS "the user tapped a printed button" — recorded the moment the pen's tap is
    // recognized, not once the PNG/PDF pick resolves, so it still counts even if they dismiss the
    // dialog without picking a format. Drives the printed-buttons tip card's eligibility.
    LaunchedEffect(p) { sl.settings.markZoneTapped() }
    // Feature 20a: a confirm tick the moment this chooser appears — the pen tapping a printed
    // button is otherwise a silent event until the dialog itself renders.
    val haptics = com.nibhaus.ui.common.rememberHaptics()
    LaunchedEffect(p) { haptics.confirm() }
    val snackbar = com.nibhaus.ui.common.LocalAppSnackbar.current
    // #7: like the batch-share sweep in LibraryScreen, share/email intents hand off to the OS with
    // no completion callback — confirm the moment the chooser was actually launched.
    fun resolve(png: Boolean) {
        sl.resolveZoneShare(png)
        val format = if (png) "PNG" else "PDF"
        snackbar.show(if (p.email) "Emailed page as $format" else "Shared page as $format")
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { sl.resolveZoneShare(null) },
        title = { Text(if (p.email) "Email this page" else "Share this page") },
        text = { Text("Send the page as an image or a PDF?") },
        confirmButton = { Button(onClick = { resolve(png = false) }) { Text("PDF") } },
        dismissButton = { Button(onClick = { resolve(png = true) }) { Text("Image") } },
    )
}

/** Bluetooth rationale pre-prompt (#3): one friendly line explaining WHY the system BLE dialogs are
 *  about to fire, shown once ever before they do. Dismissing (tap outside / Back) counts the same as
 *  Continue — this is an explanation the user needs to see once, not a gate that can strand them. */
@Composable
private fun BleRationaleDialog(onContinue: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onContinue,
        title = { Text("Using your pen needs Bluetooth", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Text(
                "Nibhaus uses Bluetooth to hear your pen. Android will ask for permission next.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = { Button(onClick = onContinue) { Text("Continue") } },
    )
}

/** Minimal placeholder shown while the vault is locked; auto-prompts on appear, with a manual retry. */
@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    LaunchedEffect(Unit) { onUnlock() }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Nibhaus is locked", style = MaterialTheme.typography.titleMedium)
        Button(onClick = onUnlock, modifier = Modifier.padding(top = 16.dp)) { Text("Unlock") }
    }
}
