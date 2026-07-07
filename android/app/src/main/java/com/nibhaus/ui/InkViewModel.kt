package com.nibhaus.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nibhaus.audio.RecordingController
import com.nibhaus.data.NotebookEntity
import com.nibhaus.data.PageEntity
import com.nibhaus.data.RecordingEntity
import com.nibhaus.data.StrokeEntity
import com.nibhaus.di.premiumEntitled
import com.nibhaus.edit.NoOpPageEditor
import com.nibhaus.edit.PageEditor
import com.nibhaus.export.ExportOutcome
import com.nibhaus.export.SettingsStore
import com.nibhaus.export.SyncMethod
import com.nibhaus.export.LibraryView
import com.nibhaus.export.NotebookAccent
import com.nibhaus.export.PaperTemplate
import com.nibhaus.export.TranscriptionQuality
import com.nibhaus.health.SyncTargetState
import com.nibhaus.premiumapi.VlmDownloadState
import com.nibhaus.pen.PenConnState
import com.nibhaus.pen.ScannedPen
import com.nibhaus.repo.NoteRepository
import com.nibhaus.ui.common.FailureDiagnosis
import com.nibhaus.ui.common.PendingDeletes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class InkViewModel(
    private val repo: NoteRepository,
    private val settings: SettingsStore,
    pen: PenDeps,
    sync: SyncDeps = SyncDeps(),
    ocr: OcrDeps = OcrDeps(),
    zones: ZoneDeps = ZoneDeps(),
    private val editor: PageEditor = NoOpPageEditor,
    private val inkColor: MutableStateFlow<Int> = MutableStateFlow(0),
    private val inkWidth: MutableStateFlow<Float> = MutableStateFlow(1f),
    private val recorder: RecordingController? = null,
    private val calendar: com.nibhaus.calendar.CalendarGateway? = null,
    private val captureLog: com.nibhaus.capture.CaptureLog? = null,
    private val backgroundStore: com.nibhaus.background.BackgroundStore? = null,
    private val translator: com.nibhaus.translate.InkTranslator? = null,
    private val notebookProfiles: com.nibhaus.capture.NotebookProfileStore? = null,
) : ViewModel() {

    // --- Dependency-group bridge vals (P1-4): keep the pre-refactor private field names working
    // unchanged for every method body below, so grouping the flat ctor params into PenDeps /
    // SyncDeps / OcrDeps / ZoneDeps (see InkViewModelDeps.kt) required zero churn past this point.
    private val penManager = pen.penManager
    private val scanner = pen.scanner
    private val sharedScanner = pen.sharedScanner
    private val hasStoredPassword = pen.hasStoredPassword
    private val signals = pen.signals
    private val connectSavedFn = pen.connectSaved

    private val exportPageNow = sync.exportPageNow
    private val reexportAll = sync.reexportAll
    private val deletePageOp = sync.deletePageOp
    private val deleteNotebookOp = sync.deleteNotebookOp
    private val restoreBackup = sync.restoreBackup
    private val shareSelected = sync.shareSelected

    private val transcripts = ocr.transcripts
    private val transcribeOnDevice = ocr.transcribeOnDevice
    private val saveTranscriptOp = ocr.saveTranscriptOp
    private val vlmState = ocr.vlmState
    private val isMetered = ocr.isMetered
    private val premiumPresent = ocr.premiumPresent

    private val actionZones = zones.actionZones
    private val captureTrace = zones.captureTrace
    private val cancelTrace = zones.cancelTrace

    /** Live-ink probes for the connection diagnostic (Phase A). "Receiving ink" means ink has flowed
     *  at all since connecting (not just in the last few seconds), so it isn't a false ✗ when idle. */
    fun receivingInk(): Boolean =
        (signals?.receivedSinceConnect?.value ?: false) || (signals?.recentlyReceiving() ?: false)
    fun paperRecognized(): Boolean = signals?.lastAddressValid?.value ?: false

    /** The live ink color (0 = brand ink) for new strokes; set from the capture color picker. */
    val inkColorState: StateFlow<Int> = inkColor
    fun setInkColor(color: Int) { inkColor.value = color }

    /** The live writing width multiplier (Fine/Medium/Large) for new strokes. */
    val inkWidthState: StateFlow<Float> = inkWidth
    fun setInkWidth(width: Float) { inkWidth.value = width }

    /** Pens discovered by the current BLE scan, strongest signal first. Shared by every client of
     *  [sharedScanner] — the Find-a-pen screen and the Pens home presence scan both read the SAME
     *  accumulating list, never their own separate one. */
    val scannedPens: StateFlow<List<ScannedPen>> = scanner.results

    /** Find-a-pen screen's client identity on [sharedScanner] (bug #2's ref-counted facade) — distinct
     *  from [PresenceScanClient] so the two never contend over start()/stop(). */
    private object ManualScanClient
    fun startScan() = sharedScanner.start(ManualScanClient)
    fun stopScan() = sharedScanner.stop(ManualScanClient)

    /** Pens home presence scan's client identity — see [ManualScanClient]. */
    private object PresenceScanClient
    fun startPresenceScan() = sharedScanner.start(PresenceScanClient)
    /** Stops this client's hold on the shared scanner AND drops accumulated presence sightings (bug
     *  #3): called whenever presence scanning explicitly stops (the Pens screen is left, or the pen
     *  starts connecting/connects) so a saved tile never shows a stale READY left over from a scan
     *  that's no longer running for this purpose — see [clearPresence]. */
    fun stopPresenceScan() { sharedScanner.stop(PresenceScanClient); clearPresence() }

    /**
     * Connect to a picked pen, passing its full [com.nibhaus.pen.PenTarget] (spp identity + LE
     * advertising address + protocol) — all three are required for the connect to succeed. We KEEP
     * scanning during the attempt: a direct LE connect to this non-bonded, randomly-addressed pen is
     * far more reliable while the system still sees it advertising (that's how NeoStudio connects).
     * ScanScreen stops the scan once the link is up.
     */
    fun connectPicked(pen: ScannedPen) {
        // Stop scanning before connecting: scanning + connecting share the one BLE radio, and the
        // contention causes connection-establishment failures (HCI 0x3e / GATT status 133). Releases
        // only THIS client's hold — if another client (e.g. a lingering presence scan) still needs
        // the radio, the shared facade correctly keeps the real scan running rather than yanking it.
        sharedScanner.stop(ManualScanClient)
        penManager.connect(pen.target)
    }

    val syncMethod: StateFlow<SyncMethod> =
        settings.syncMethod.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncMethod.DEFAULT)
    val localFolderUri: StateFlow<String> =
        settings.localFolderUri.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")
    val backupFolderUri: StateFlow<String> =
        settings.backupFolderUri.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")
    val tailscaleEndpoint: StateFlow<String> =
        settings.tailscaleEndpoint.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")
    val syncToken: StateFlow<String> =
        settings.syncToken.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /** Why the outbox backlog isn't draining (or that it's expected — [SyncTargetState.LOCAL_ONLY]),
     *  derived from the sync method + its required field. Drives the Pens-home pending card's copy
     *  (see [syncTargetState] top-level function for the pure classification). */
    val syncTargetState: StateFlow<SyncTargetState> =
        combine(syncMethod, localFolderUri, tailscaleEndpoint, settings.premiumUnlocked) { method, folder, endpoint, unlocked ->
            com.nibhaus.health.syncTargetState(method, folder, endpoint, premiumEntitled(unlocked, premiumPresent))
        }.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000),
            // Neutral initial value: SyncMethod.DEFAULT + a blank folder would classify as NO_FOLDER,
            // which briefly flashes "No sync folder set" for an already-configured user before
            // DataStore's real value loads. CONFIGURED never shows a false error.
            SyncTargetState.CONFIGURED,
        )
    val translateEndpoint: StateFlow<String> =
        settings.translateEndpoint.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")
    val translateModel: StateFlow<String> =
        settings.translateModel.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")
    fun setTranslateEndpoint(endpoint: String) = viewModelScope.launch { settings.setTranslateEndpoint(endpoint) }
    fun setTranslateModel(model: String) = viewModelScope.launch { settings.setTranslateModel(model) }

    /** BYO OCR server (the accurate-tier ServerInk engine) — the user's own GPU host. */
    val byoOcrEndpoint: StateFlow<String> =
        settings.byoOcrEndpoint.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")
    val byoOcrToken: StateFlow<String> =
        settings.byoOcrToken.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")
    fun setByoOcrEndpoint(endpoint: String) = viewModelScope.launch { settings.setByoOcrEndpoint(endpoint) }
    fun setByoOcrToken(token: String) = viewModelScope.launch { settings.setByoOcrToken(token) }

    /** The selected color palette driving the live theme (selectable color-palette theming). */
    val activePalette: StateFlow<com.nibhaus.ui.theme.Palette> =
        settings.paletteId.map { com.nibhaus.ui.theme.Palettes.byId(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.nibhaus.ui.theme.Palettes.DEFAULT)
    fun setPalette(id: String) = viewModelScope.launch { settings.setPaletteId(id) }

    /** True once the persisted palette has actually been read — the launch splash holds until this
     *  flips, so the first rendered frame is already in the user's palette (no default-theme flash). */
    val paletteLoaded: StateFlow<Boolean> =
        settings.paletteId.map { true }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Welcome-splash milestone 4 ("workspace handoff"): the premium seam (ServiceLocator's `premium`
    // field) resolves synchronously in its constructor, always before this — so in practice this
    // milestone just tracks the DataStore palette read above.
    init {
        viewModelScope.launch {
            paletteLoaded.first { it }
            com.nibhaus.di.StartupProgress.markMilestone(4)
        }
    }

    /** Whether the page canvas renders light even under a dark palette. */
    val lightPaper: StateFlow<Boolean> =
        settings.lightPaper.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    fun setLightPaper(on: Boolean) = viewModelScope.launch { settings.setLightPaper(on) }
    val paperTemplate: StateFlow<PaperTemplate> =
        settings.paperTemplate.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PaperTemplate.DEFAULT)
    fun setPaperTemplate(t: PaperTemplate) = viewModelScope.launch { settings.setPaperTemplate(t) }

    /** Handwriting size preset (#15b) — read by InkSurface at the single stroke-width source. */
    val strokeScale: StateFlow<com.nibhaus.export.StrokeScale> =
        settings.strokeScale.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.nibhaus.export.StrokeScale.DEFAULT)
    fun setStrokeScale(s: com.nibhaus.export.StrokeScale) = viewModelScope.launch { settings.setStrokeScale(s) }

    /** Library tab layout: cover gallery (default) vs. a denser row list. */
    val libraryView: StateFlow<LibraryView> =
        settings.libraryView.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryView.DEFAULT)
    fun setLibraryView(v: LibraryView) = viewModelScope.launch { settings.setLibraryView(v) }

    /** Hide pages with no ink strokes in the open-notebook page navigator. Default off (show all). */
    val hideBlankPages: StateFlow<Boolean> =
        settings.hideBlankPages.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    fun setHideBlankPages(on: Boolean) = viewModelScope.launch { settings.setHideBlankPages(on) }

    /** Opt-in: attach a page's voice note(s) when sharing it via the printed Share icon (not email). */
    val attachAudioOnShare: StateFlow<Boolean> =
        settings.attachAudioOnShare.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    fun setAttachAudioOnShare(on: Boolean) = viewModelScope.launch { settings.setAttachAudioOnShare(on) }

    /** #9: progress ("rendered so far" / total) for an in-flight batch share; null when idle. */
    private val _batchShareProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val batchShareProgress: StateFlow<Pair<Int, Int>?> = _batchShareProgress
    private var batchShareJob: Job? = null

    /** Share a multi-selection of pages (images + opt-in audio) in one share sheet. Tracked as a
     *  cancellable job (#9) — [cancelBatchShare] (the Library progress bar's Cancel) stops it, but
     *  whatever pages already rendered are still shared (see ServiceLocator.shareSelectedPages's
     *  "partial progress kept" `finally`), not silently discarded. */
    fun shareSelectedPages(ids: List<String>) {
        batchShareJob?.cancel()
        batchShareJob = viewModelScope.launch {
            try {
                shareSelected(ids) { done, total -> _batchShareProgress.value = done to total }
            } finally {
                _batchShareProgress.value = null
                batchShareJob = null
            }
        }
    }

    /** #9 cancel affordance for an in-flight batch share (cooperative — see [shareSelectedPages]). */
    fun cancelBatchShare() {
        batchShareJob?.cancel()
    }

    /** Restore editable ink from `.bak.json` backups under [treeUri]; [onResult] gets the stroke count. */
    fun restoreFromFolder(treeUri: android.net.Uri, onResult: (Int) -> Unit) =
        viewModelScope.launch { onResult(restoreBackup(treeUri)) }

    /** Opt-in biometric vault lock (Section C1). Default off. */
    val appLockEnabled: StateFlow<Boolean> =
        settings.appLockEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    fun setAppLockEnabled(on: Boolean) = viewModelScope.launch { settings.setAppLockEnabled(on) }

    fun setSyncMethod(method: SyncMethod) = viewModelScope.launch { settings.setSyncMethod(method) }
    fun setLocalFolderUri(uri: String) = viewModelScope.launch { settings.setLocalFolderUri(uri) }
    fun setBackupFolderUri(uri: String) = viewModelScope.launch { settings.setBackupFolderUri(uri) }
    fun setTailscaleEndpoint(endpoint: String) = viewModelScope.launch { settings.setTailscaleEndpoint(endpoint) }
    fun setSyncToken(token: String) = viewModelScope.launch { settings.setSyncToken(token) }

    /** Premium entitlement (one-time unlock) gating the OCR / handwriting-search / translate /
     *  native-sync layer. Free tier = the full local-first notebook + folder-based sync. */
    val isPremium: StateFlow<Boolean> =
        settings.premiumUnlocked.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    fun setPremiumUnlocked(on: Boolean) = viewModelScope.launch { settings.setPremiumUnlocked(on) }

    /** THE premium gate for every premium surface (gate unification 2026-07-05): the runtime
     *  unlock AND the :premium module actually present. [isPremium] alone is only the unlock bool;
     *  module presence is availability, never entitlement. UI call sites collect this; suspend
     *  paths use [isEntitled] for a fresh read. */
    val entitled: StateFlow<Boolean> =
        settings.premiumUnlocked.map { premiumEntitled(it, premiumPresent) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Fresh entitlement read for gating decisions inside coroutines: reads the unlock bool
     *  straight from DataStore, so a just-flipped unlock is honored even when nothing is
     *  currently collecting [entitled]. */
    private suspend fun isEntitled(): Boolean =
        premiumEntitled(settings.premiumUnlocked.first(), premiumPresent)

    /** First-run coach: null until the persisted flag has actually been read, so the caller can tell
     *  "still loading" apart from "loaded, and it's false" — an existing user must never see the
     *  coach flash on screen just because the read hadn't landed yet (a plain default-false StateFlow
     *  can't tell those two cases apart). */
    val onboardingCoachDone: StateFlow<Boolean?> =
        settings.onboardingCoachDone.map<Boolean, Boolean?> { it }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    fun completeOnboardingCoach() = viewModelScope.launch { settings.setOnboardingCoachDone() }

    /** Bluetooth rationale pre-prompt (#3): same nullable-until-loaded gating as [onboardingCoachDone],
     *  so MainActivity can tell "still reading DataStore" apart from "loaded, never shown". */
    val bleRationaleShown: StateFlow<Boolean?> =
        settings.bleRationaleShown.map<Boolean, Boolean?> { it }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    fun markBleRationaleShown() = viewModelScope.launch { settings.setBleRationaleShown() }

    /** "Reset to Default" for one Settings tab (by tab index) — only that tab's settings revert. */
    fun resetSettingsTab(tab: Int) = viewModelScope.launch {
        when (tab) {
            0 -> settings.resetCaptureAndPen()
            1 -> settings.resetSyncAndOcr()
            2 -> settings.resetAppearance()
            3 -> settings.resetPrivacy()
            4 -> settings.resetIntegrations()
        }
    }

    /** Result of a "Test connection" against the sync endpoint. */
    sealed interface SyncTest {
        data object Idle : SyncTest
        data object Testing : SyncTest
        data class Result(val ok: Boolean, val message: String) : SyncTest
    }

    /**
     * Combined progress state for on-device OCR ("Improve transcription").
     * Derived from [vlmModelState] + an internal transcribing flag so the UI can show
     * download progress (determinate bar) while the model downloads and an indeterminate
     * spinner with a note while inference runs.  Cancel is silent: state returns to [Idle]
     * and the page keeps its prior transcript.
     */
    sealed interface OcrProgress {
        /** No OCR operation in progress. */
        data object Idle : OcrProgress
        /** Downloading the VLM model weights. [pct] is 0–100; -1 = total unknown. */
        data class Downloading(val pct: Int) : OcrProgress
        /** Model is ready; on-device inference is running (30–120 s per page typical). */
        data object Transcribing : OcrProgress
        /** Inference finished with an error (not emitted on cancel — that returns [Idle]). */
        data class Failed(val msg: String) : OcrProgress
    }
    private val _syncTest = MutableStateFlow<SyncTest>(SyncTest.Idle)
    val syncTest: StateFlow<SyncTest> = _syncTest

    /** GET `<endpoint>/_index` with the token, so a bad URL/token surfaces immediately instead of via
     *  a silently-stuck queue. */
    fun testSyncConnection(endpoint: String, token: String) = viewModelScope.launch {
        _syncTest.value = SyncTest.Testing
        _syncTest.value = withContext(Dispatchers.IO) {
            try {
                val conn = (java.net.URL(endpoint.trimEnd('/') + "/_index").openConnection()
                    as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 8000
                    if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
                }
                try {
                    val code = conn.responseCode
                    SyncTest.Result(code in 200..299, "HTTP $code")
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                // #8: sync target unreachable — name the host that was tried and the likely cause
                // (this real exception is a genuine UnknownHostException/ConnectException/
                // SocketTimeoutException, not swallowed anywhere upstream) instead of a raw message.
                SyncTest.Result(false, FailureDiagnosis.hostUnreachable("reach your sync target", endpoint, e).message)
            }
        }
    }

    val notebooks: StateFlow<List<NotebookEntity>> =
        repo.notebooks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val penState: StateFlow<PenConnState> = penManager.state

    /** Pen battery + charge estimate (null when no pen / unknown). */
    val battery: StateFlow<com.nibhaus.pen.BatteryStatus?> = penManager.battery

    /** Live RSSI (dBm) while [startRssiPolling] is active; null otherwise. Find My Pen (#20b). */
    val rssiDbm: StateFlow<Int?> = penManager.rssiDbm
    fun startRssiPolling() = penManager.startRssiPolling()
    fun stopRssiPolling() = penManager.stopRssiPolling()

    /** Feature 2: previously connected pens, most-recently-connected first — the Pens screen's
     *  saved-pen reconnect tiles (shown while not connected). */
    val savedPens: StateFlow<List<com.nibhaus.pen.SavedPen>> = penManager.savedPens

    /** Live "searching…" / "not found" state for an in-flight [connectSaved] scan. */
    val savedPenConnectState: StateFlow<com.nibhaus.pen.SavedPenConnectState> = pen.savedPenConnectState

    /** Tap-to-reconnect a saved pen by its stable spp identity (Feature 2). */
    fun connectSaved(spp: String) = connectSavedFn(spp)

    /** Forget a saved pen (Feature 2's long-press "forget" affordance). */
    fun forgetSavedPen(spp: String) = penManager.forgetPen(spp)

    /**
     * Running spp → last-seen-at (ms) sightings feeding [readyPens]; see [recordSightings]. Updated
     * ONLY by the collector below, on a genuinely new [scanner] emission — never by [readyPens]'s
     * ticker (see its doc for the bug that mattered).
     */
    private val _presenceSightings = MutableStateFlow<Map<String, Long>>(emptyMap())
    init {
        viewModelScope.launch {
            var prev = emptyMap<String, com.nibhaus.pen.ScannedPen>()
            scanner.results.collect { seen ->
                // Record only spp actually (re-)sighted THIS emission, not the whole accumulator:
                // PenScanner.results never shrinks within a session, so stamping every historically-seen
                // spp would keep a powered-off pen READY forever while ANY other pen keeps advertising.
                // A still-advertising pen's ScannedPen changes (fresh rssi) → re-sighted; a gone-silent
                // pen's entry is unchanged → not re-stamped → expires on the hold window.
                val justSighted = seen.filter { prev[it.mac] != it }.map { it.mac }
                if (justSighted.isNotEmpty()) {
                    _presenceSightings.value = com.nibhaus.pen.recordSightings(
                        _presenceSightings.value, justSighted, System.currentTimeMillis(),
                    )
                }
                prev = seen.associateBy { it.mac }
            }
        }
    }

    /** Drop all presence sightings. Called from [stopPresenceScan] whenever presence scanning
     *  explicitly stops (the Pens screen is left, or the pen starts connecting/connects) — bug #3, so
     *  a saved tile never shows a stale READY left over from a scanning session that's no longer
     *  running for this purpose, independent of whether the underlying radio itself kept scanning for
     *  some OTHER client of [sharedScanner]. */
    private fun clearPresence() { _presenceSightings.value = emptyMap() }

    /**
     * Feature 2 refinement — per-spp "ready" (currently advertising nearby) presence for the saved-pen
     * tiles' live dot. Purely reactive to whatever [scanner] is currently reporting; this flow never
     * starts a scan itself — that's the Pens screen's job (visible + Disconnected + saved pens exist,
     * via [startPresenceScan]/[stopPresenceScan] in a DisposableEffect), so a pen never appears "ready"
     * from a scan that shouldn't be running.
     *
     * The 1s ticker re-evaluates [readySpps] against [_presenceSightings] even without a new scan
     * result, so a spp's READY state expires ~3.5s after its last sighting (long enough to bridge a
     * missed advertising packet, short enough that a powered-off pen drops READY promptly) — the ticker
     * must NEVER also re-run [recordSightings] (that used to happen right here): [PenScanner]'s
     * results list only ever grows within one scan session (it has no notion of "no longer visible",
     * by design — the Find-a-pen picker wants a pen it once saw to stay listed), so re-stamping every
     * currently-known spp on every tick would treat "seen at some point this session" as "seen right
     * now", forever — the bug behind a saved-pen tile getting stuck on READY after the real pen
     * powered off. Splitting sighting-recording (event-driven, above) from expiry-recompute
     * (clock-driven, below) fixes it.
     */
    val readyPens: StateFlow<Set<String>> =
        combine(_presenceSightings, tickerFlow(1_000L)) { sightings, now ->
            com.nibhaus.pen.readySpps(sightings, now, holdMs = 3_500L)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private fun tickerFlow(periodMs: Long): Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(periodMs)
        }
    }

    /**
     * Live capture starts FRESH each time the screen is opened: we ignore whatever page was inked
     * in a past session and only bind to a page once ink lands at-or-after [startLiveSession] (a
     * fresh page or one previously written into — the pen touches its page on the first stroke).
     * Until then the screen shows "waiting for ink". Long.MAX_VALUE ⇒ nothing matches before open.
     */
    private val liveSessionGraceMs = 1_500L
    private val liveSince = MutableStateFlow(Long.MAX_VALUE)
    // Backdate the gate by a short grace so the stroke that triggered auto-open (landed a moment
    // before the screen arms) shows immediately, without binding to truly stale pages.
    fun startLiveSession() { liveSince.value = System.currentTimeMillis() - liveSessionGraceMs }

    private val gatedLivePage: Flow<PageEntity?> =
        combine(liveSince, repo.livePage()) { since, page -> page?.takeIf { it.lastInkAt >= since } }

    /** The page currently receiving ink, and its strokes — for the live-capture screen. */
    val livePage: StateFlow<PageEntity?> =
        gatedLivePage.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val liveStrokes: StateFlow<List<StrokeEntity>> =
        gatedLivePage
            .flatMapLatest { p -> if (p == null) flowOf(emptyList()) else repo.strokes(p.id) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Timestamp of the latest ink landing (ungated) — drives auto-opening the live capture screen
     *  when the pen starts writing on the home screen. Distinct so only new ink re-triggers. */
    val lastInkAt: StateFlow<Long?> =
        repo.livePage().map { it?.lastInkAt }.distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The notebook currently being written in that the user hasn't set up yet — drives the one-time
     * new-notebook dialog (pick its product type + label this copy). Reactive: acknowledging it (Save
     * or Skip) makes this go null, so the prompt asks once per physical notebook.
     */
    val notebookNeedingSetup: StateFlow<NotebookEntity?> =
        combine(livePage, notebooks, settings.acknowledgedNotebooks) { page, books, done ->
            books.firstOrNull { it.id == page?.notebookId && it.id !in done }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The built-in type id for a book, to pre-select in the setup dialog (null = unmeasured product). */
    fun resolvedTypeId(book: Int): String? = com.nibhaus.export.NotebookType.forBook(book)?.id

    /** Apply the new-notebook dialog: assign the product type (by book id) + label this copy, once. */
    fun setUpNotebook(notebookId: String, book: Int, typeId: String, label: String) {
        viewModelScope.launch {
            settings.assignNotebookType(book, typeId)
            if (label.isNotBlank()) repo.rename(notebookId, label)
            settings.acknowledgeNotebook(notebookId)
        }
    }

    /** Re-label a notebook that was already set up (Library → notebook → rename). Blank is ignored. */
    fun renameNotebook(id: String, title: String) {
        val t = title.trim()
        if (t.isNotBlank()) viewModelScope.launch { repo.rename(id, t) }
    }

    /** Dismiss the new-notebook dialog without changes (still acknowledged so it won't nag). */
    fun skipNotebookSetup(notebookId: String) =
        viewModelScope.launch { settings.acknowledgeNotebook(notebookId) }

    /** Feature 18: the notebook's chosen accent (NONE = unset) — for the library card tint + picker. */
    fun notebookAccent(notebookId: String): Flow<NotebookAccent> = settings.notebookAccent(notebookId)
    fun setNotebookAccent(notebookId: String, accent: NotebookAccent) =
        viewModelScope.launch { settings.setNotebookAccent(notebookId, accent) }

    // --- Soft delete + UNDO (#6) ---
    // On confirm the item is hidden immediately (its id joins pendingDeleted*Ids, which Library/
    // PageDetail filter out of every grid/list) and the real delete is scheduled for
    // [UNDO_WINDOW_MS] later; UNDO ([undoDeletePage]/[undoDeleteNotebook]) cancels that timer and
    // restores visibility. [PendingDeletes] is the pure schedule/cancel/fire state machine (unit
    // tested on its own); this class only owns the timing (a coroutine per pending id) and the
    // actual deletePageOp/deleteNotebookOp call, which stay untouched — wrapped, not rewritten.
    private val pendingPageDeletes = PendingDeletes<String>()
    private val _pendingDeletedPageIds = MutableStateFlow<Set<String>>(emptySet())
    /** Page ids hidden pending a real delete — Library/PageDetail filter these out of every grid. */
    val pendingDeletedPageIds: StateFlow<Set<String>> = _pendingDeletedPageIds
    private val pageDeleteJobs = HashMap<String, Job>()

    private val pendingNotebookDeletes = PendingDeletes<String>()
    private val _pendingDeletedNotebookIds = MutableStateFlow<Set<String>>(emptySet())
    /** Notebook ids hidden pending a real delete — Library filters these out of the notebook grid. */
    val pendingDeletedNotebookIds: StateFlow<Set<String>> = _pendingDeletedNotebookIds
    private val notebookDeleteJobs = HashMap<String, Job>()

    /** Delete a whole notebook (Feature 18) — mirrors [deleteCurrentPage] but cascades every page in
     *  the notebook, then drops the notebook row itself. Local-only unless [alsoRemote]. Soft: hides
     *  immediately and leaves the notebook if it's open; the real cascade runs after the undo window
     *  (see the "Soft delete + UNDO" block above) unless [undoDeleteNotebook] cancels it first. */
    fun deleteNotebook(id: String, alsoRemote: Boolean, alsoAudio: Boolean) {
        pendingNotebookDeletes.schedule(id)
        _pendingDeletedNotebookIds.value = pendingNotebookDeletes.pending
        notebookDeleteJobs[id]?.cancel()
        notebookDeleteJobs[id] = viewModelScope.launch {
            delay(UNDO_WINDOW_MS)
            if (pendingNotebookDeletes.fire(id)) {
                deleteNotebookOp(id, alsoRemote, alsoAudio)
            }
            _pendingDeletedNotebookIds.value = pendingNotebookDeletes.pending
            notebookDeleteJobs.remove(id)
        }
        if (selectedNotebook.value == id) { selectedPage.value = null; selectedNotebook.value = null }
    }

    /** UNDO a pending notebook delete: cancels the scheduled cascade and restores the notebook to
     *  every grid/list it was hidden from. */
    fun undoDeleteNotebook(id: String) {
        if (pendingNotebookDeletes.cancel(id)) {
            _pendingDeletedNotebookIds.value = pendingNotebookDeletes.pending
            notebookDeleteJobs.remove(id)?.cancel()
        }
    }

    // --- voice notes (tied to a page; phone mic, local-only) ---

    /** Recording state: Idle, or Recording(pageId, startedAt) — startedAt aligns with stroke times. */
    val recordingState: StateFlow<RecordingController.State> =
        recorder?.state ?: MutableStateFlow(RecordingController.State.Idle)

    /** Which stored recording is currently playing back (id), or null. */
    val playingRecordingId: StateFlow<String?> =
        recorder?.playingId ?: MutableStateFlow<String?>(null)

    /** Playback head of the active recording (ms from its start) — drives highlight + scrubber. */
    val playbackPositionMs: StateFlow<Long> =
        recorder?.positionMs ?: MutableStateFlow(0L)

    /** True while a note is actively playing (false when paused/stopped). */
    val isPlaying: StateFlow<Boolean> =
        recorder?.isPlaying ?: MutableStateFlow(false)

    fun recordingsFor(pageId: String?): Flow<List<RecordingEntity>> =
        if (pageId == null) flowOf(emptyList()) else repo.recordings(pageId)

    /** Start a recording bound to [pageId], or stop the one in progress. */
    fun toggleRecording(pageId: String, addressKey: String) {
        val r = recorder ?: return
        if (r.isRecording) r.stop() else r.start(pageId, addressKey)
    }

    fun stopRecording() { recorder?.stop() }
    fun playRecording(recording: RecordingEntity, startMs: Long = 0L) { recorder?.play(recording, startMs) }
    fun pausePlayback() { recorder?.pause() }
    fun resumePlayback() { recorder?.resume() }
    fun seekPlayback(ms: Long) { recorder?.seekTo(ms) }
    fun stopPlayback() { recorder?.stopPlayback() }
    fun deleteRecording(recording: RecordingEntity) { recorder?.delete(recording) }
    fun renameRecording(id: String, title: String) { recorder?.rename(id, title) }

    /** Title of the notebook a page belongs to (for the live/page app bar). */
    fun notebookTitleOf(page: PageEntity?): String =
        notebooks.value.firstOrNull { it.id == page?.notebookId }?.title ?: "Capture"

    val pendingUploads: StateFlow<Int> =
        repo.pendingUploads().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val selectedNotebook = MutableStateFlow<String?>(null)
    private val selectedPage = MutableStateFlow<String?>(null)

    /** Observable drill-down state so the UI can animate transitions (shared-axis / container transform). */
    val selectedNotebookId: StateFlow<String?> = selectedNotebook
    val selectedPageId: StateFlow<String?> = selectedPage

    val pages: StateFlow<List<PageEntity>> =
        selectedNotebook
            .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repo.pages(id) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val strokes: StateFlow<List<StrokeEntity>> =
        selectedPage
            .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repo.strokes(id) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Ids of pages in the open notebook that have at least one stroke — feeds the Library "hide
     *  blank pages" filter (Feature 16). Only subscribed while [hideBlankPages] is on (when it's off,
     *  [visiblePages] ignores this entirely, so there's no reason to run the query) and backed by
     *  ONE query ([com.nibhaus.repo.NoteRepository.nonBlankPageIds]) rather than one stroke-observer
     *  per page of the open notebook. */
    val nonBlankPageIds: StateFlow<Set<String>> =
        combine(selectedNotebook, hideBlankPages, ::Pair)
            .flatMapLatest { (notebookId, hide) ->
                if (!hide || notebookId == null) flowOf(emptySet())
                else repo.nonBlankPageIds(notebookId).map { it.toSet() }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** The open page's stored OCR transcript, reactive — refreshes after on-device/server transcription. */
    val currentTranscript: StateFlow<String?> =
        combine(selectedPage, pages) { id, list -> list.firstOrNull { it.id == id }?.transcript }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The open page entity (book id + page number), reactive — drives the page-detail view's calibrated
     *  ruling and printed page number. */
    val selectedPageEntity: StateFlow<PageEntity?> =
        combine(selectedPage, pages) { id, list -> list.firstOrNull { it.id == id } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Feature 9: persist a manual correction to [pageId]'s transcript. Routes through
     * [saveTranscriptOp] (the same [com.nibhaus.data.PageDao.setTranscriptIndexed] funnel OCR
     * uses), so the edit is both stored AND re-indexed for full-text search. A blank edit clears the
     * transcript — the funnel handles that as an ordinary (empty) write, not an error.
     */
    fun saveTranscript(pageId: String, text: String) {
        val normalized = normalizeTranscriptEdit(text)
        viewModelScope.launch {
            saveTranscriptOp(pageId, normalized)
            _exportStatus.value = if (normalized.isBlank()) "Transcript cleared" else "Transcript saved and searchable"
            delay(3_000)
            _exportStatus.value = null
        }
    }

    // #6 soft delete: a stale composable (one that hasn't recomposed past a hide yet) can still fire a
    // click for an id that's already pending deletion — these two are the only entry points that open
    // a notebook/page, so guarding here closes that re-entry gap everywhere at once rather than in
    // every caller.
    fun openNotebook(id: String) {
        if (pendingNotebookDeletes.isPending(id)) return
        clearOcrDisclosure(); selectedNotebook.value = id; selectedPage.value = null
    }
    fun openPage(id: String) {
        if (pendingPageDeletes.isPending(id)) return
        clearOcrDisclosure(); selectedPage.value = id; clearTranslation()
    }
    fun back() { clearOcrDisclosure(); if (selectedPage.value != null) selectedPage.value = null else selectedNotebook.value = null }

    fun currentNotebook(): String? = selectedNotebook.value
    fun currentPage(): String? = selectedPage.value

    fun strokesFlowOf(stroke: StrokeEntity) = repo.decodePoints(stroke)

    /** Strokes of one page — for drawing real ink into a library page thumbnail. */
    fun pageStrokes(pageId: String): Flow<List<StrokeEntity>> = repo.strokes(pageId)

    /**
     * Library grid batching (perf audit P1-1): each visible NotebookThumb/PageThumb used to open its
     * own `notebookPageCount`/`notebookHasAudio`/`pageHasAudio` Flow — Room invalidates per TABLE, so
     * any stroke write re-ran those queries for every card on screen, and `notebookPageCount` loaded
     * a notebook's whole page list just to read its `.size`. These three are app-scoped, shared, and
     * batched instead: ONE grouped query / ONE distinct-ids query each, re-run once per underlying
     * table change and fanned out in memory to however many cards are visible — the same pattern
     * [nonBlankPageIds] already uses for the "hide blank pages" filter. Cover-ink strokes
     * ([notebookCoverStrokes] / [pageStrokes]) stay per-card: batching full stroke payloads for every
     * notebook/page up front would hold far more decoded ink in memory than the grid ever shows, for
     * a query that isn't the fan-out cost the audit flagged (count + audio were).
     */
    val notebookPageCounts: StateFlow<Map<String, Int>> =
        repo.notebookPageCounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Total pages ever captured, across every notebook — the "N pages captured" gate the tip cards
     *  (Feature 5) and the empty Activity feed (Feature 4) use. Derived from [notebookPageCounts]
     *  rather than a second query, per the perf-audit batching discipline above. */
    val totalPageCount: StateFlow<Int> =
        notebookPageCounts.map { it.values.sum() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val notebooksWithAudio: StateFlow<Set<String>> =
        repo.notebooksWithAudio().map { it.toSet() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val pagesWithAudio: StateFlow<Set<String>> =
        repo.pagesWithAudio().map { it.toSet() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /**
     * Feature 1 (Pens home "Recent" section): one row per notebook (newest-edited first), each up to
     * 3 of its most recently edited pages, newest→oldest. ONE combined flow — [repo.allPages] (already
     * newest-inked-first) and the batched cross-notebook non-blank-id set — grouped once here via the
     * pure [groupRecentByNotebook], so PensHome collects a single StateFlow instead of opening a
     * subscription per notebook row (perf audit P1-1 discipline, same as [notebookPageCounts] et al).
     */
    val recentByNotebook: StateFlow<List<Pair<String, List<PageEntity>>>> =
        combine(repo.allPages(), repo.allNonBlankPageIds().map { it.toSet() }) { pages, nonBlank ->
            groupRecentByNotebook(pages, nonBlank)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Whether any page, anywhere, has a transcript yet — the "transcribe to search" tip card
     *  (Feature 5) hides itself once the user has already discovered transcription on their own. */
    val everTranscribed: StateFlow<Boolean> =
        repo.allPages().map { pages -> pages.any { !it.transcript.isNullOrBlank() } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** "Did you know" tip cards (Feature 5) — dismissed independently and forever. See
     *  [com.nibhaus.export.replayTipEligible] / [com.nibhaus.export.printedButtonTipEligible] /
     *  [com.nibhaus.export.transcribeTipEligible] for the eligibility rule each one uses. */
    val tipReplayDismissed: StateFlow<Boolean> =
        settings.tipReplayDismissed.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    fun dismissReplayTip() = viewModelScope.launch { settings.dismissReplayTip() }

    val tipPrintedButtonsDismissed: StateFlow<Boolean> =
        settings.tipPrintedButtonsDismissed.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    fun dismissPrintedButtonsTip() = viewModelScope.launch { settings.dismissPrintedButtonsTip() }

    val tipTranscribeDismissed: StateFlow<Boolean> =
        settings.tipTranscribeDismissed.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    fun dismissTranscribeTip() = viewModelScope.launch { settings.dismissTranscribeTip() }

    /** Whether the user has ever tapped a printed Share/Email button with the pen (see
     *  [ZoneShareChooser][com.nibhaus.ui.ZoneShareChooser], which marks this the moment such a tap
     *  is recognized — before the format-pick dialog is even resolved). */
    val everZoneTapped: StateFlow<Boolean> =
        settings.everZoneTapped.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    fun markZoneTapped() = viewModelScope.launch { settings.markZoneTapped() }

    /** Crash capture + next-launch prompt (user-initiated feedback, part 2): the acknowledged-crash
     *  timestamp — see [com.nibhaus.feedback.crashPromptEligible] for how the Pens-home card uses it. */
    val feedbackCrashAcked: StateFlow<Long> =
        settings.feedbackCrashAcked.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)
    fun acknowledgeCrash(timestampMillis: Long) = viewModelScope.launch { settings.setFeedbackCrashAcked(timestampMillis) }

    // --- tags (Phase E) ---
    fun tagsForPage(pageId: String?): Flow<List<String>> =
        if (pageId == null) flowOf(emptyList()) else repo.tagsForPage(pageId)
    val allTags: StateFlow<List<String>> =
        repo.allTags().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun addTag(pageId: String, tag: String) {
        if (tag.isBlank()) return
        viewModelScope.launch { repo.addTag(pageId, tag) }
    }
    fun removeTag(pageId: String, tag: String) = viewModelScope.launch { repo.removeTag(pageId, tag) }

    /** Library tag filter: when a tag is selected, the Library shows that tag's pages flat. Picking
     *  a tag drops out of the Favorites view (Feature 15) — the two flat-page filters are exclusive. */
    private val selectedTag = MutableStateFlow<String?>(null)
    val selectedTagState: StateFlow<String?> = selectedTag
    fun selectTag(tag: String?) {
        selectedTag.value = tag
        if (tag != null) showFavorites.value = false
    }
    val taggedPages: StateFlow<List<PageEntity>> =
        selectedTag
            .flatMapLatest { t -> if (t == null) flowOf(emptyList()) else repo.pagesWithTag(t) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // --- Feature 15: bookmarked pages / Favorites ---

    /** Ids of pages the user has starred — persisted in DataStore, not a Room column (no migration). */
    val bookmarkedPageIds: StateFlow<Set<String>> =
        settings.bookmarkedPageIds.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Star/un-star [pageId] to exactly [on]. */
    fun setBookmarked(pageId: String, on: Boolean) = viewModelScope.launch { settings.setBookmarked(pageId, on) }

    /** Flip [pageId]'s current bookmark state (star ↔ unstar). */
    fun toggleBookmark(pageId: String) =
        viewModelScope.launch { settings.setBookmarked(pageId, pageId !in bookmarkedPageIds.value) }

    /** Bookmarked pages across every notebook, most-recently-inked first — the Favorites list. */
    val favorites: StateFlow<List<PageEntity>> =
        combine(repo.allPages(), bookmarkedPageIds) { pages, ids -> favoritePages(pages, ids) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Library "show Favorites" toggle — transient nav state (mirrors [selectedTagState]), NOT
     *  persisted; survives navigating into a favorite page and back since it lives here, not in the
     *  Library composable (which unmounts while a page is open). */
    private val showFavorites = MutableStateFlow(false)
    val showFavoritesState: StateFlow<Boolean> = showFavorites
    fun setShowFavorites(on: Boolean) {
        showFavorites.value = on
        if (on) selectedTag.value = null
    }

    /** A notebook's cover ink = the strokes of its most-recently-inked page (empty if none). */
    fun notebookCoverStrokes(notebookId: String): Flow<List<StrokeEntity>> =
        repo.pages(notebookId).flatMapLatest { pages ->
            val cover = pages.maxByOrNull { it.lastInkAt }
            if (cover == null) flowOf(emptyList()) else repo.strokes(cover.id)
        }

    fun takeOver(mac: String) = penManager.takeOver(mac)
    fun submitPassword(password: String) = penManager.submitPassword(password)

    /** Drop the current pen link (and stop auto-reconnect). Only meaningful once connected. */
    fun disconnect() = penManager.disconnect()

    // --- Pen password management (Settings) ---

    /** Whether an unlock password is stored for auto-unlock (drives the Settings "auto-unlock" line). */
    fun hasStoredPassword(): Boolean = hasStoredPassword.invoke()

    /** "Remember the pen password for 30 days" toggle (off = ask every connect). */
    val rememberPassword: StateFlow<Boolean> =
        settings.rememberPassword.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    fun setRememberPassword(on: Boolean) = viewModelScope.launch { settings.setRememberPassword(on) }

    /** First-connect "allow background capture" nudge — true once shown/dismissed, so it asks once. */
    val bgCaptureNudgeDismissed: StateFlow<Boolean> =
        settings.bgCaptureNudgeDismissed.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    fun dismissBgCaptureNudge() = viewModelScope.launch { settings.dismissBgCaptureNudge() }

    /** Result of an in-flight change/disable-password request (drives the Settings feedback). */
    val passwordOp: StateFlow<com.nibhaus.pen.PasswordOpState> = penManager.passwordOp
    fun changePenPassword(new: String) = penManager.changePassword(new)
    fun disablePenPassword(current: String) = penManager.disablePassword(current)
    fun acknowledgePasswordOp() = penManager.acknowledgePasswordOp()

    // --- Firmware (Settings) ---

    /** The connected pen's firmware version, or null until it reports one. */
    val firmwareVersion: StateFlow<String?> = penManager.firmwareVersion

    /** Transient status line for page-detail actions (export / on-device OCR); shown briefly. */
    private val _exportStatus = MutableStateFlow<String?>(null)
    val exportStatus: StateFlow<String?> = _exportStatus

    /** On-device OCR is wired in EVERY build now (the free ML Kit instant engine lives in :app),
     *  so this capability signal is constant true. Kept as a named val because UI call sites key
     *  off it; accurate-pass affordances additionally key off [entitled]. */
    val onDeviceOcrAvailable: Boolean = true

    /** User master switch for on-device transcription (default on). Off → NAS/OCR host only. */
    val onDeviceOcrEnabled: StateFlow<Boolean> =
        settings.onDeviceOcrEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    fun setOnDeviceOcrEnabled(on: Boolean) = viewModelScope.launch { settings.setOnDeviceOcrEnabled(on) }
    fun resetOnDeviceOcrDisclosure() = viewModelScope.launch { settings.resetOnDeviceOcrDisclosure() }

    val transcriptionQuality: StateFlow<TranscriptionQuality> =
        settings.transcriptionQuality.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TranscriptionQuality.AUTO)
    fun setTranscriptionQuality(q: TranscriptionQuality) = viewModelScope.launch { settings.setTranscriptionQuality(q) }

    val vlmAllowMetered: StateFlow<Boolean> =
        settings.vlmAllowMetered.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    fun setVlmAllowMetered(on: Boolean) = viewModelScope.launch { settings.setVlmAllowMetered(on) }

    val vlmForceOnDevice: StateFlow<Boolean> =
        settings.vlmForceOnDevice.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    fun setVlmForceOnDevice(on: Boolean) = viewModelScope.launch { settings.setVlmForceOnDevice(on) }

    /** Live VLM model download progress; null when VLM is unavailable (freemium build or incapable device). */
    val vlmModelState: StateFlow<VlmDownloadState?> =
        (vlmState?.map { it as VlmDownloadState? } ?: flowOf(null))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** True while [doTranscribe] is executing (between entering the function and leaving it). */
    private val _transcribing = MutableStateFlow(false)

    /** The coroutine running [doTranscribe], held so [cancelOcr] can cancel it. */
    private var doTranscribeJob: Job? = null

    /** #8: the last transcription attempt's failure message (null = no failure, or it was cleared by
     *  a subsequent attempt/success/cancel) — feeds [OcrProgress.Failed], which was previously never
     *  produced. Cleared at the start of every fresh attempt and on success, so it never outlives the
     *  attempt it describes. */
    private val _lastOcrFailure = MutableStateFlow<String?>(null)

    /** Which tier (instant vs. accurate) [retryTranscribe] should re-attempt. */
    private var lastTranscribeAccurate = false

    /**
     * Combined progress exposed to the UI.  Derived from [vlmModelState] + [_transcribing] +
     * [_lastOcrFailure] so the page-detail UI can show a live download bar, an inference spinner, or
     * a failure line with Retry, without polling.
     *
     * Uses [SharingStarted.Eagerly] so the value is always current — no subscriber needed.
     * This also keeps [vlmModelState]'s WhileSubscribed chain active for free via combine.
     */
    val ocrProgress: StateFlow<OcrProgress> =
        combine(vlmModelState, _transcribing, _lastOcrFailure) { vlm, running, failure ->
            when {
                running && vlm is VlmDownloadState.Downloading -> OcrProgress.Downloading(vlm.pct)
                running -> OcrProgress.Transcribing
                failure != null -> OcrProgress.Failed(failure)
                else -> OcrProgress.Idle
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, OcrProgress.Idle)

    /** Cancel any in-flight on-device OCR. Silent: no toast, page transcript is unchanged. */
    fun cancelOcr() {
        doTranscribeJob?.cancel()
        doTranscribeJob = null
        _transcribing.value = false
        _lastOcrFailure.value = null
    }

    /** #8 Retry action: re-run the transcription attempt that just failed, same page + same tier.
     *  Both gates are re-checked fresh at fire time (wave 4 hardening): the accurate tier degrades
     *  to the free instant pass if entitlement was relocked in between. A Settings reset can also
     *  clear the on-device OCR acknowledgement between attempts, so an unacknowledged consent
     *  routes to the disclosure instead of transcribing directly. */
    fun retryTranscribe() {
        val pageId = selectedPage.value ?: return
        viewModelScope.launch {
            startTranscribeGated(pageId, lastTranscribeAccurate && isEntitled())
        }
    }

    // --- Eager Tier-0 transcription (auto-OCR on settle, so search works with zero taps) ---

    /** How long a page's ink must sit quiet before the eager pass transcribes it. Mirrors
     *  [com.nibhaus.export.SafetyBackup]'s per-page debounce so a burst of strokes collapses
     *  into one OCR call instead of one per stroke. */
    private val eagerSettleDebounceMs = 2_500L

    /** "done / total" readout for the eager background pass; null when idle. Kept separate from
     *  [ocrProgress] (the manual "Improve" spinner) so the two never fight over one UI element. */
    data class TranscribeProgress(val done: Int, val total: Int)

    private val _transcribeProgress = MutableStateFlow<TranscribeProgress?>(null)
    val transcribeProgress: StateFlow<TranscribeProgress?> = _transcribeProgress

    /** Pages the eager pass has claimed and not yet released — guards against the backlog catch-up
     *  and the settle watcher both queuing the same page at once. */
    private val eagerClaimed = HashSet<String>()

    /** #9: the coroutine actually running the current eager-transcribe batch (the loop inside
     *  [runEagerTranscribe]), held so [cancelEagerTranscription] (the TRANSCRIBING badge's X) can
     *  cancel just this batch — not the backlog pass / settle watcher that called it, which keep
     *  running so future ink still gets auto-transcribed. */
    private var eagerTranscribeJob: Job? = null

    /** Guards [startEagerTranscription] so a second call (e.g. from a re-entrant caller) doesn't spin
     *  up duplicate long-running watchers. */
    private var eagerStarted = false

    /**
     * Start the eager Tier-0 transcription pass: a one-shot backlog catch-up plus an ongoing
     * settle-watcher. Called once, right after this VM is constructed for real use (see
     * [com.nibhaus.ui.MainActivity]) — kept out of `init` so merely *constructing* an `InkViewModel`
     * (as plain unit tests for unrelated features do) never triggers background OCR work. Inert when
     * no transcription engine is injected (bare test VMs); in production every build has the free
     * instant engine, so the eager pass runs for everyone, instant tier only.
     */
    fun startEagerTranscription() {
        if (eagerStarted || transcribeOnDevice == null) return
        eagerStarted = true
        viewModelScope.launch { runEagerBacklogPass() }
        viewModelScope.launch { watchForSettledPages() }
    }

    /** Catch-up pass, run once when eager transcription starts: transcribe any backlog of pages that
     *  already have ink but no transcript (e.g. captured before this feature existed, or while the
     *  app was closed). */
    private suspend fun runEagerBacklogPass() {
        val backlog = runCatching { repo.pagesNeedingTranscription() }.getOrElse { emptyList() }
        runEagerTranscribe(selectPagesToTranscribe(backlog, eagerClaimed))
    }

    /**
     * Settle trigger: once the actively-inked page goes quiet for [eagerSettleDebounceMs], transcribe
     * it if it still needs one. Keyed on (id, lastInkAt) so a fresh burst of ink on the same page
     * restarts the debounce, matching the "settle" semantics of [com.nibhaus.export.SafetyBackup].
     * Ignores whatever page was already "live" at the moment we started watching — that's the
     * backlog catch-up pass's job; this one only reacts to new ink landing from here on.
     */
    private suspend fun watchForSettledPages() {
        val startingPoint = repo.livePage().first()?.let { it.id to it.lastInkAt }
        repo.livePage()
            .filterNotNull()
            .map { it.id to it.lastInkAt }
            .distinctUntilChanged()
            .filter { it != startingPoint }
            .debounce(eagerSettleDebounceMs)
            .collect { (pageId, _) ->
                val page = repo.pageById(pageId) ?: return@collect
                runEagerTranscribe(selectPagesToTranscribe(listOf(page), eagerClaimed))
            }
    }

    /**
     * Transcribe [pageIds] one at a time via the existing on-device path (Tier-0, accurate=false),
     * which funnels through [com.nibhaus.data.PageDao.setTranscriptIndexed]. Publishes
     * [transcribeProgress] while running and clears it when done. Skips a page rather than racing it
     * if a manual transcription ([doTranscribe]) is currently in flight — the next settle/backlog
     * pass will pick it back up. Never throws out of the coroutine for ordinary failures; a
     * cancellation still propagates (re-thrown, same as [doTranscribe]).
     */
    private suspend fun runEagerTranscribe(pageIds: List<String>) {
        val run = transcribeOnDevice ?: return
        if (pageIds.isEmpty()) return
        // Privacy gate (final-review CRITICAL fix, 2026-07-05): the eager pass must never trigger the
        // ML Kit model download without the SAME first-use acknowledgement the manual "Transcribe on
        // device" flow requires, and never while the on-device OCR switch is off. Read fresh on every
        // call (backlog pass AND settle watcher both funnel through here) so acknowledging later via
        // the manual flow enables eager transcription without an app restart.
        if (!settings.onDeviceOcrAcknowledged.first() || !settings.onDeviceOcrEnabled.first()) return
        eagerClaimed += pageIds
        try {
            // #9: the actual page loop runs as its own tracked child of viewModelScope (a sibling of
            // whichever caller — the backlog pass or the settle watcher — invoked us), so cancelling
            // it via cancelEagerTranscription() stops just this batch. join() returns normally even
            // if the job was cancelled, so the caller carries on afterward (the watcher keeps
            // watching for the NEXT page to settle).
            val job = viewModelScope.launch {
                pageIds.forEachIndexed { index, pageId ->
                    if (_transcribing.value) return@forEachIndexed // manual OCR owns the engine right now
                    // Re-read consent per page (2026-07-05 fix): the entry check at :1044 is read once,
                    // but a disclosure reset or an off toggle DURING a multi-page batch must stop the
                    // remaining pages, not let them through. Stop the whole batch, not just this page.
                    if (!settings.onDeviceOcrAcknowledged.first() || !settings.onDeviceOcrEnabled.first()) return@launch
                    // 1-based readout: the page about to be worked on counts as "in progress", so this
                    // reads 1/N .. N/N (never 0/N, and it does reach N/N before clearing on completion).
                    _transcribeProgress.value = TranscribeProgress(index + 1, pageIds.size)
                    try {
                        run(pageId, false)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Best-effort background pass — one bad page shouldn't stop the rest.
                    }
                }
            }
            eagerTranscribeJob = job
            job.join()
        } finally {
            _transcribeProgress.value = null
            eagerClaimed -= pageIds.toSet()
            eagerTranscribeJob = null
        }
    }

    /**
     * #9 Cancel affordance for the TRANSCRIBING badge (PensHome): stop the in-flight eager-transcribe
     * batch. Cooperative — cancels the tracked [eagerTranscribeJob], so whichever page is mid-request
     * gets its [CancellationException] on the next suspension point. Whatever page already finished
     * keeps its transcript (each page commits to the database via [transcribeOnDevice] before the
     * loop moves to the next one) — only the pages not yet reached are skipped, and they stay
     * eligible for the next backlog/settle pass (released from [eagerClaimed] in
     * [runEagerTranscribe]'s `finally`, exactly like a normal finish). A no-op if nothing is running.
     */
    fun cancelEagerTranscription() {
        eagerTranscribeJob?.cancel()
    }

    /** Drives the one-time disclosure that on-device OCR fetches a model from Google on first use. */
    private val _showOcrDisclosure = MutableStateFlow(false)
    val showOcrDisclosure: StateFlow<Boolean> = _showOcrDisclosure
    /** The page the disclosure was raised for, so confirm transcribes that page even if selection moves. */
    private var pendingOcrPageId: String? = null
    /** Whether the pending transcription should use the accurate (high-quality) tier. */
    private var pendingOcrAccurate: Boolean = false

    /** Drives the one-time accuracy disclaimer dialog (shown before the very first Improve tap). */
    private val _showAccuracyDisclaimer = MutableStateFlow(false)
    val showAccuracyDisclaimer: StateFlow<Boolean> = _showAccuracyDisclaimer
    /** Page and flag saved so the transcription fires after the user taps "Got it.". */
    private var pendingAccuratePageId: String? = null

    fun confirmAccuracyDisclaimer() {
        val pageId = pendingAccuratePageId ?: run { _showAccuracyDisclaimer.value = false; return }
        pendingAccuratePageId = null
        _showAccuracyDisclaimer.value = false
        viewModelScope.launch {
            settings.setOcrDisclaimerShown()
            // Entitlement re-checked at fire time: a relock between the disclaimer being raised
            // and this confirm degrades to the free instant pass, never an accurate request. The
            // instant fallback still goes through startTranscribeGated (wave 4 hardening) so an
            // on-device OCR acknowledgement that was never given (or was reset since) routes to
            // the disclosure instead of transcribing directly.
            if (isEntitled()) startAccurateTranscribe(pageId) else startTranscribeGated(pageId, accurate = false)
        }
    }

    fun dismissAccuracyDisclaimer() {
        _showAccuracyDisclaimer.value = false
        pendingAccuratePageId = null
    }

    /**
     * Transcribe the open page on-device (ML Kit Digital Ink) and index it. The first run downloads
     * the language model from Google (one-time, ~few MB) — outbound to a target the user didn't
     * select — so the very first time we surface a disclosure and only proceed once the user accepts;
     * after that no further download is needed and we never ask again.
     */
    fun transcribeCurrentPageOnDevice() {
        val pageId = selectedPage.value ?: return
        if (transcribeOnDevice == null) return
        viewModelScope.launch { startTranscribeGated(pageId, accurate = false) }
    }

    /** "Improve transcription": requires entitlement (premium unlocked AND module present), then
     *  gates on the accuracy disclaimer (first use) and routes accurate=true. Not entitled means a
     *  silent no-op at the VM level; the UI shows the upsell before ever calling this. */
    fun transcribeCurrentPageAccurate() {
        val pageId = selectedPage.value ?: return
        if (transcribeOnDevice == null) return
        viewModelScope.launch {
            if (!isEntitled()) return@launch
            if (settings.ocrDisclaimerShown.first()) {
                startAccurateTranscribe(pageId)
            } else {
                pendingAccuratePageId = pageId
                _showAccuracyDisclaimer.value = true
            }
        }
    }

    /**
     * Routes an accurate ("Improve transcription") request through the SAME on-device-OCR (ML Kit
     * download) consent gate the instant tier uses, before ever calling [startTranscribe] with
     * accurate=true (final-review CRITICAL fix, 2026-07-05): [RoutedInk]'s accurate chain falls back
     * to the instant ML Kit engine whenever no accurate engine is configured or all of them fail, so
     * the accurate tier must never bypass this disclosure just because the (separate) accuracy
     * disclaimer was already shown. Read fresh, same as [transcribeCurrentPageOnDevice], so
     * acknowledging via either flow unblocks the other with no app restart needed.
     */
    private suspend fun startAccurateTranscribe(pageId: String) = startTranscribeGated(pageId, accurate = true)

    /**
     * Consent-gated transcribe start (wave 4 hardening): the single place every interactive fire
     * path funnels through so none of them can transcribe without a fresh
     * [SettingsStore.onDeviceOcrAcknowledged] read. Raises the on-device OCR (ML Kit download)
     * disclosure when it's not yet true (or was cleared by a Settings reset since), instead of ever
     * calling [startTranscribe] directly. Entitlement for [accurate] is re-checked again, one layer
     * further in, by [doTranscribe] itself (the last checkpoint before any [RoutedInk] accurate-chain
     * call), so a caller here only needs to get consent right; it can never be the reason the premium
     * chain runs for a non-entitled user.
     */
    private suspend fun startTranscribeGated(pageId: String, accurate: Boolean) {
        if (settings.onDeviceOcrAcknowledged.first()) {
            startTranscribe(pageId, accurate)
        } else {
            pendingOcrPageId = pageId
            pendingOcrAccurate = accurate
            _showOcrDisclosure.value = true
        }
    }

    /** User accepted the on-device-OCR disclosure: remember it (never ask again) and transcribe the
     *  page the disclosure was raised for (bound at request time, so a later selection change can't
     *  redirect it to the wrong page). Consent is freshly true by construction here; entitlement for
     *  an accurate request is re-checked fresh by [doTranscribe] at fire time (wave 4 hardening), so
     *  an entitlement relock while this disclosure sat open still degrades to the instant pass. */
    fun confirmOcrDisclosure() {
        val pageId = pendingOcrPageId
        val accurate = pendingOcrAccurate
        clearOcrDisclosure()
        if (pageId == null) return
        viewModelScope.launch {
            settings.acknowledgeOnDeviceOcr()
            startTranscribe(pageId, accurate)
        }
    }

    /** User dismissed the disclosure (or navigated away) — nothing is downloaded or transcribed. */
    fun dismissOcrDisclosure() = clearOcrDisclosure()

    private fun clearOcrDisclosure() {
        _showOcrDisclosure.value = false
        pendingOcrPageId = null
        pendingOcrAccurate = false
    }

    /**
     * Launch [doTranscribe] as a tracked [Job] so [cancelOcr] can cancel it.  Any previously
     * running transcription is cancelled first (only one can be in-flight at a time).
     */
    private fun startTranscribe(pageId: String, accurate: Boolean) {
        lastTranscribeAccurate = accurate
        doTranscribeJob?.cancel()
        doTranscribeJob = viewModelScope.launch { doTranscribe(pageId, accurate) }
    }

    private suspend fun doTranscribe(pageId: String, accurate: Boolean = false) {
        val run = transcribeOnDevice ?: return
        // Fire-time consent (wave 5 hardening): this funnel is the layer that actually invokes the
        // engine, so consent is enforced HERE alongside entitlement. No caller path (interactive,
        // retry, or the auto follow-up below) can reach ML Kit without a fresh acknowledgement.
        // Interactive callers still raise the disclosure themselves before getting here; this is the
        // backstop that guarantees the invariant even if a caller's check went stale.
        if (!settings.onDeviceOcrAcknowledged.first()) return
        // Entitlement's final checkpoint (wave 4 hardening): this is the layer immediately above
        // the RoutedInk call, so coercing here is the one place that closes every caller's gap at
        // once. A dialog-confirm or retry that intended accurate=true is degraded to the free
        // instant pass if entitlement was relocked any time before this actual fire, even though a
        // caller further up (confirmOcrDisclosure in particular) does no entitlement check of its
        // own. Read fresh, never trusted from whenever the caller decided to request accurate.
        val effectiveAccurate = accurate && isEntitled()
        _transcribing.value = true
        _lastOcrFailure.value = null // a fresh attempt starts clean, even if the last one failed
        try {
            val text = run(pageId, effectiveAccurate)
            // Auto accurate pass: when we just ran the instant Tier-0 pass (accurate=false) and
            // the user's quality setting calls for an automatic follow-up, chain it here in the
            // same coroutine — no extra UI, no new dialog — so the user never has to tap Improve.
            // Guards: (a) the accuracy disclaimer must already be shown (non-blocking invariant),
            //         (b) shouldAutoRunAccuratePass decides by quality × vlmCapable × metered.
            if (text != null && !effectiveAccurate) {
                val quality = settings.transcriptionQuality.first()
                val disclaimerShown = settings.ocrDisclaimerShown.first()
                val vlmCapable = vlmState != null   // non-null when premium + VLM-capable device
                // Guards: (a) the accuracy disclaimer must already be shown (non-blocking
                // invariant), (b) shouldAutoRunAccuratePass decides by entitlement x quality x
                // vlmCapable x metered. Not entitled: the instant result stands, no auto chain.
                // The instant pass above can take time; re-read consent so a disclosure reset during
                // it blocks the accurate follow-up too (not just the first run).
                if (disclaimerShown &&
                    shouldAutoRunAccuratePass(quality, vlmCapable, isMetered(), isEntitled()) &&
                    settings.onDeviceOcrAcknowledged.first()
                ) {
                    run(pageId, true)
                }
            }
            if (text == null) {
                // #8: the accurate tier tries the user's BYO OCR server first (see RoutedInk) — if one
                // is configured, name it; that request/fallback chain swallows its own exceptions
                // internally (never surfaces one here), so the reason stays honestly generic rather
                // than guessing "network" vs. "no recognizable handwriting".
                val host = settings.byoOcrEndpoint.first().trim()
                _lastOcrFailure.value = if (effectiveAccurate && host.isNotBlank()) {
                    FailureDiagnosis.hostUnreachable("transcribe", host).message
                } else {
                    FailureDiagnosis.noResult("transcribe").message
                }
            } else {
                _exportStatus.value = "Transcribed ${text.length} characters. Now searchable."
                delay(3_000)
                _exportStatus.value = null
            }
        } catch (e: CancellationException) {
            _exportStatus.value = null  // clear any partial status set before cancel
            _lastOcrFailure.value = null
            throw e                     // re-throw so the coroutine machinery knows it's cancelled
        } catch (e: Exception) {
            val host = settings.byoOcrEndpoint.first().trim()
            _lastOcrFailure.value = if (effectiveAccurate && host.isNotBlank()) {
                FailureDiagnosis.hostUnreachable("transcribe", host, e).message
            } else {
                "Couldn't transcribe. ${e.message?.take(60) ?: "Unknown error"}"
            }
        } finally {
            _transcribing.value = false
        }
    }

    /** Export the page currently open in page detail to the user's selected sync target. */
    fun exportCurrentPage() {
        val pageId = selectedPage.value ?: return
        viewModelScope.launch {
            _exportStatus.value = "Exporting…"
            _exportStatus.value = when (exportPageNow(pageId)) {
                ExportOutcome.DONE -> "Exported to your sync target"
                ExportOutcome.NO_TARGET -> "Pick a sync target in Settings first"
                // #8: storage or renderer are the two things exportPage actually does per page — name
                // both as suspects (the real exception doesn't survive ExportEngine's own runCatching).
                ExportOutcome.FAILED -> FailureDiagnosis.exportFailure().message
            }
            delay(3_000)
            _exportStatus.value = null
        }
    }

    /** Delete the page open in page detail (optionally also its exported copy on the sync target).
     *  Soft: leaves the page and hides it immediately; the real delete runs after the undo window
     *  (see the "Soft delete + UNDO" block above) unless [undoDeletePage] cancels it first. */
    fun deleteCurrentPage(alsoRemote: Boolean, alsoAudio: Boolean) {
        val pageId = selectedPage.value ?: return
        pendingPageDeletes.schedule(pageId)
        _pendingDeletedPageIds.value = pendingPageDeletes.pending
        pageDeleteJobs[pageId]?.cancel()
        pageDeleteJobs[pageId] = viewModelScope.launch {
            delay(UNDO_WINDOW_MS)
            if (pendingPageDeletes.fire(pageId)) {
                deletePageOp(pageId, alsoRemote, alsoAudio)
            }
            _pendingDeletedPageIds.value = pendingPageDeletes.pending
            pageDeleteJobs.remove(pageId)
        }
        back()
    }

    /** UNDO a pending page delete: cancels the scheduled delete and restores the page to every
     *  grid/list it was hidden from. */
    fun undoDeletePage(pageId: String) {
        if (pendingPageDeletes.cancel(pageId)) {
            _pendingDeletedPageIds.value = pendingPageDeletes.pending
            pageDeleteJobs.remove(pageId)?.cancel()
        }
    }

    /** Re-queue every existing page for the current sync target — offered when the user switches
     *  sync method, to backfill history (already-synced pages are skipped by export idempotency). */
    fun reexportAllPages() {
        viewModelScope.launch {
            reexportAll()
            _exportStatus.value = "Moving your pages to the new sync target…"
            delay(3_000)
            _exportStatus.value = null
        }
    }

    // --- Phase 5 editing (page-detail edit toolbar) ---
    private fun edit(op: suspend (pageId: String) -> Unit) {
        val pageId = selectedPage.value ?: return
        viewModelScope.launch { op(pageId) }
    }

    // Selection edits — each is one undoable step (see PageEditor/StrokeEditor).
    fun deleteSelection(uuids: List<String>) = edit { editor.delete(uuids, it) }
    fun recolorSelection(uuids: List<String>, color: Int) = edit { editor.recolor(uuids, color, it) }
    fun resizeSelection(uuids: List<String>, width: Float) = edit { editor.setThickness(uuids, width, it) }
    /** Revert the last edit (recolor / resize / delete) — NOT the last stroke written. */
    fun undoEdit() = edit { editor.undo(it) }

    // --- Calendar (system CalendarContract; no OAuth) ---

    /** The device calendar new events go to (-1 = not chosen). */
    val calendarTargetId: StateFlow<Long> =
        settings.calendarTargetId.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), -1L)
    fun setCalendarTarget(id: Long) = viewModelScope.launch { settings.setCalendarTargetId(id) }

    /** Writable device calendars (needs READ_CALENDAR); off the main thread. */
    suspend fun writableCalendars(): List<com.nibhaus.calendar.CalendarTarget> =
        withContext(Dispatchers.IO) { calendar?.writableCalendars() ?: emptyList() }

    /** Add an event to the chosen calendar (needs WRITE_CALENDAR). Returns true on success. */
    suspend fun addCalendarEvent(
        title: String, startMs: Long, endMs: Long, allDay: Boolean, notes: String?,
    ): Boolean = withContext(Dispatchers.IO) {
        val target = calendarTargetId.value
        if (target < 0 || calendar == null) return@withContext false
        calendar.insertEvent(target, title.ifBlank { "Untitled" }, startMs, endMs, allDay, notes) != null
    }

    // --- Physical action zones (tap-to-teach printed icons) ---

    val zones: StateFlow<List<com.nibhaus.zones.ActionZone>> =
        actionZones?.zones ?: MutableStateFlow(emptyList())

    /** Capture the next traced outline (raw Ncode bbox) while the user circles a printed icon. */
    fun calibrateNextTrace(onTrace: (Int, Float, Float, Float, Float) -> Unit) = captureTrace?.invoke(onTrace)
    /** Abort a pending [calibrateNextTrace] (the user cancelled). */
    fun cancelCalibration() = cancelTrace?.invoke()

    /** Store a zone from a traced box, padded slightly so taps near the edge still register. */
    fun addZone(action: com.nibhaus.zones.ZoneAction, book: Int, left: Float, top: Float, right: Float, bottom: Float) {
        val padX = (right - left) * ZONE_PAD + ZONE_MIN_PAD
        val padY = (bottom - top) * ZONE_PAD + ZONE_MIN_PAD
        actionZones?.add(
            com.nibhaus.zones.ActionZone(
                java.util.UUID.randomUUID().toString(), action,
                left - padX, top - padY, right + padX, bottom + padY, book = book,
            ),
        )
    }

    fun removeZone(id: String) = actionZones?.remove(id)

    // --- Capture Lab (record the pen's own output for measurements) ---

    val captureRecording: StateFlow<Boolean> =
        captureLog?.recording ?: MutableStateFlow(false)

    fun startCapture() = captureLog?.start()
    fun stopCapture(): List<com.nibhaus.capture.CapturedDot> = captureLog?.stop() ?: emptyList()

    // --- Notebook capture profiles (geometry produced on-device by the setup wizard) ---

    /** The page geometry for a book: a captured profile wins over the built-in product geometry. */
    fun pageGeometryFor(book: Int?): com.nibhaus.export.PageGeometry? =
        com.nibhaus.capture.resolveGeometry(
            book?.let { notebookProfiles?.forBook(it) },
            com.nibhaus.export.PageGeometry.forBook(book),
        )

    /** The built-in calibrated ruling for a book's product (mm from sheet edges), or null. */
    fun rulingFor(book: Int?): com.nibhaus.export.Ruling? =
        com.nibhaus.export.NotebookType.forBook(book)?.ruling

    /** The page-layout style for [book]/[page] — cover / lined / footer-only (defaults to lined). */
    fun pageStyleAt(book: Int?, page: Int?): com.nibhaus.export.PageStyle =
        com.nibhaus.export.pageStyleFor(
            com.nibhaus.export.NotebookType.forBook(book)?.pageBands ?: emptyList(),
            page ?: -1,
        )

    /** Persist captured geometry for [book] (writable bounds + physical sheet mm + optional scale). */
    fun saveNotebookGeometry(
        book: Int,
        bounds: com.nibhaus.capture.PageBounds,
        sheetWmm: Float,
        sheetHmm: Float,
        mmPerUnit: Float?,
    ) {
        val geometry = com.nibhaus.capture.assembleGeometry(bounds, sheetWmm, sheetHmm)
        notebookProfiles?.save(com.nibhaus.capture.NotebookProfile(book, geometry, mmPerUnit))
    }

    /** JSON of a notebook's captured setup (to share/back up), or null if it has no profile yet. */
    fun exportNotebookSetup(book: Int): String? =
        notebookProfiles?.forBook(book)?.let { com.nibhaus.capture.encodeProfile(it) }

    /** Apply a shared setup JSON; true if it parsed and was saved. */
    fun importNotebookSetup(json: String): Boolean {
        val profile = com.nibhaus.capture.decodeProfile(json) ?: return false
        notebookProfiles?.save(profile)
        return true
    }

    // --- Background templates (per notebook, rendered behind the ink) ---
    val backgrounds: StateFlow<Map<String, String>> =
        backgroundStore?.map ?: MutableStateFlow(emptyMap())
    fun setBackground(notebookId: String, uri: String) = backgroundStore?.set(notebookId, uri)
    fun clearBackground(notebookId: String) = backgroundStore?.clear(notebookId)

    // --- Search (notebook titles now; OCR transcripts once imported from the sync folder) ---

    /** Pull any `<pageId>.txt` the watcher wrote back into the folder. @return pages updated. */
    suspend fun importTranscripts(): Int = transcripts?.importPending() ?: 0

    /** Pages matching [query] by notebook title or transcript. Local search (title + transcript
     *  FTS) is free for everyone. */
    suspend fun searchPages(query: String): List<PageEntity> =
        repo.searchPages(query)

    /** Most recently inked pages, shown when the search box is empty. */
    suspend fun recentPages(): List<PageEntity> = repo.recentPages()

    /** Recently-run search queries, most-recent-first — shown as tappable chips on the empty search box. */
    val recentSearches: StateFlow<List<String>> =
        settings.recentSearches.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    /** Record a query as "run" (called once a search actually executes, not on every keystroke). */
    fun addRecentSearch(query: String) = viewModelScope.launch { settings.addRecentSearch(query) }
    fun clearRecentSearches() = viewModelScope.launch { settings.clearRecentSearches() }

    // --- Translation (quality LLM on the user's box; ML Kit on-device fallback) ---

    val translatorAvailable: Boolean = translator != null

    /** Default translation target = the device language (the user can pick another in the Text view). */
    val deviceLanguage: String = java.util.Locale.getDefault().language

    /** The current page's translation, with which engine produced it (null = showing the original). */
    data class TranslationUi(val text: String, val onDevice: Boolean)
    private val _translation = MutableStateFlow<TranslationUi?>(null)
    val translation: StateFlow<TranslationUi?> = _translation
    private val _translating = MutableStateFlow(false)
    val translating: StateFlow<Boolean> = _translating
    private val _translateError = MutableStateFlow<String?>(null)
    val translateError: StateFlow<String?> = _translateError

    /** Translate the open page's transcript into [target] (source null = auto-detect on-device). */
    fun translateCurrentPage(target: String, source: String?) {
        val text = currentTranscript.value?.takeIf { it.isNotBlank() } ?: return
        val t = translator ?: return
        viewModelScope.launch {
            if (!isEntitled()) return@launch
            _translating.value = true; _translateError.value = null
            val r = runCatching { t.translate(text, target, source) }.getOrNull()
            if (r == null) {
                // #8: Translator tries the configured LLM box first, falling back tier-to-tier — like
                // the OCR chain, it swallows its own exception before we ever see it, so name the host
                // when one's configured (the request WAS tried) instead of guessing why.
                val host = settings.translateEndpoint.first().trim()
                _translateError.value = if (host.isNotBlank()) {
                    FailureDiagnosis.hostUnreachable("translate", host).message
                } else {
                    "Couldn't translate. Set a translation endpoint in Settings for best quality, or pick languages available offline."
                }
            } else {
                _translation.value = TranslationUi(r.text, r.engine == com.nibhaus.translate.TranslationEngine.ON_DEVICE)
            }
            _translating.value = false
        }
    }

    /** Drop the translation and show the original transcript again. */
    fun clearTranslation() { _translation.value = null; _translateError.value = null }

    /** Open a specific page (from a search hit): select its notebook, then the page. */
    fun openSearchHit(page: PageEntity) { openNotebook(page.notebookId); openPage(page.id) }

    companion object {
        const val ZONE_PAD = 0.05f    // grow the traced box 5% each side …
        const val ZONE_MIN_PAD = 0.3f // … plus a small minimum (Ncode units); kept tight so icons
        //                               a few mm apart (e.g. Share/Email) don't bridge into each other

        /** #6: how long a soft-deleted page/notebook stays hidden-but-recoverable before the real
         *  delete runs — matches the UNDO snackbar's own on-screen time (its callers pass the same
         *  5s as the snackbar's durationMs, so both time out together). */
        const val UNDO_WINDOW_MS = 5_000L

        /**
         * Pure selection logic for the eager-transcription pass: which of [pages] still need a
         * transcript, excluding any already claimed by an in-flight pass ([inFlight])? Idempotent —
         * a page whose transcript is already set (null/blank is the only "needs one" state, even one
         * the user just ran "Improve" on) is never re-selected. Extracted as a pure function so it's
         * unit-testable without a ViewModel or database.
         */
        fun selectPagesToTranscribe(
            pages: List<PageEntity>,
            inFlight: Set<String> = emptySet(),
        ): List<String> =
            pages.filter { it.transcript.isNullOrBlank() && it.id !in inFlight }.map { it.id }

        /** Pure trim applied to a manually edited transcript before it's persisted (Feature 9). A
         *  fully-blank edit normalizes to "" — [saveTranscriptOp]'s funnel treats that as clearing
         *  the transcript, not an error. Extracted so it's unit-testable without a ViewModel. */
        fun normalizeTranscriptEdit(text: String): String = text.trim()

        /** Pure filter for the Favorites list (Feature 15): [pages] narrowed to [bookmarked] ids,
         *  most-recently-inked first. Extracted as a pure function so it's unit-testable without a
         *  ViewModel or database. */
        fun favoritePages(pages: List<PageEntity>, bookmarked: Set<String>): List<PageEntity> =
            pages.filter { it.id in bookmarked }.sortedByDescending { it.lastInkAt }

        /**
         * Feature 1 (Pens home "Recent" section): group [pages] — already newest-inked-first, the
         * order [repo.allPages]/[com.nibhaus.data.PageDao.observeAll] returns — into one row per
         * notebook, up to [perNotebook] pages each, newest→oldest. Blank pages (no strokes) are
         * dropped via [nonBlankIds] first, mirroring the Library "hide blank pages" predicate
         * (a568cd8) — a Recent row only ever shows pages that were actually written on. Row order =
         * each notebook's own most recently edited page, newest notebook first — free from a
         * [LinkedHashMap] preserving first-seen order while walking the newest-first input. Extracted
         * as a pure function so it's unit-testable without a ViewModel or database (and PensHome never
         * opens a per-row DB subscription to compute it — perf audit P1-1 discipline).
         */
        fun groupRecentByNotebook(
            pages: List<PageEntity>,
            nonBlankIds: Set<String>,
            perNotebook: Int = 3,
        ): List<Pair<String, List<PageEntity>>> {
            val grouped = LinkedHashMap<String, MutableList<PageEntity>>()
            for (page in pages) {
                if (page.id !in nonBlankIds) continue
                val bucket = grouped.getOrPut(page.notebookId) { mutableListOf() }
                if (bucket.size < perNotebook) bucket.add(page)
            }
            return grouped.map { (id, list) -> id to list }
        }

        /**
         * Pure decision function: should we automatically chain an accurate pass after the instant
         * Tier-0 transcript? Requires entitlement first (premium unlocked AND module present,
         * gate unification 2026-07-05); then by quality setting:
         *
         * - INSTANT  never (user wants fast only; Improve stays manual)
         * - ACCURATE yes when the device is VLM-capable, regardless of connection
         * - AUTO     yes when VLM-capable AND NOT on a metered (mobile-data) connection
         *
         * Extracted as a pure function so it can be exhaustively unit-tested without a ViewModel.
         */
        fun shouldAutoRunAccuratePass(
            quality: TranscriptionQuality,
            vlmCapable: Boolean,
            isMetered: Boolean,
            entitled: Boolean,
        ): Boolean = entitled && when (quality) {
            TranscriptionQuality.INSTANT  -> false
            TranscriptionQuality.ACCURATE -> vlmCapable
            TranscriptionQuality.AUTO     -> vlmCapable && !isMetered
        }
    }
}
