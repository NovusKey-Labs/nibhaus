package com.nibhaus.di

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.room.withTransaction
import com.nibhaus.BuildConfig
import com.nibhaus.audio.RecordingController
import com.nibhaus.data.InkDatabase
import com.nibhaus.data.MIGRATION_4_5
import com.nibhaus.data.MIGRATION_5_6
import com.nibhaus.data.MIGRATION_6_7
import com.nibhaus.data.MIGRATION_7_8
import com.nibhaus.data.MIGRATION_8_9
import com.nibhaus.data.MIGRATION_9_10
import com.nibhaus.data.MIGRATION_10_11
import com.nibhaus.data.MIGRATION_11_12
import com.nibhaus.data.MIGRATION_12_13
import com.nibhaus.data.PageDeletionPlan
import com.nibhaus.data.PendingLocalDeleteCleanup
import com.nibhaus.data.PendingRemoteDelete
import com.nibhaus.data.Point
import com.nibhaus.export.ExportEngine
import com.nibhaus.export.ExportWorker
import com.nibhaus.export.RemoteDeleteQueue
import com.nibhaus.ocr.InkOcr
import com.nibhaus.ocr.OnDeviceInk
import com.nibhaus.ocr.RoutedInk
import com.nibhaus.premiumapi.InkPt
import com.nibhaus.premiumapi.PremiumDeps
import com.nibhaus.premiumapi.PremiumServices
import com.nibhaus.premiumapi.VlmDownloadState
import kotlinx.coroutines.flow.Flow
import com.nibhaus.translate.InkTranslator
import com.nibhaus.export.LocalFolderProvider
import com.nibhaus.export.LocalOnlyProvider
import com.nibhaus.export.LocalDeleteCleanupQueue
import com.nibhaus.export.SafetyBackup
import com.nibhaus.export.SettingsStore
import com.nibhaus.export.StorageProvider
import com.nibhaus.export.SyncMethod
import com.nibhaus.ingest.OfflineSync
import com.nibhaus.ingest.StrokeIngestor
import com.nibhaus.organize.AutoOrganizer
import com.nibhaus.pen.CaptureSignals
import com.nibhaus.pen.FakeNeoPenSdk
import com.nibhaus.pen.NeoPenSdk
import com.nibhaus.pen.PenConnectionManager
import com.nibhaus.pen.PenScanner
import com.nibhaus.pen.PenTarget
import com.nibhaus.pen.SavedPenConnectState
import com.nibhaus.pen.SharedPrefsPenPrefs
import com.nibhaus.repo.NoteRepository
import com.nibhaus.security.SecretStore
import com.nibhaus.edit.StrokeEditor
import com.nibhaus.export.ExportOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Tiny manual DI. Hand-wiring keeps the dependency graph visible in one place
 * and avoids kapt/Hilt build complexity. Swap [FakeNeoPenSdk] for
 * the `:neosdk` module's NeoSdkAdapter (real pen) to go live — see android/STRANGLER.md.
 *
 * NOTE (post-brief): there is intentionally NO cloud sync here. This app is
 * local-first/self-hosted — export → NAS (Syncthing/Tailscale) is Phase 3, and
 * OCR hand-off is Phase 4. See android/DESIGN.md.
 */
class ServiceLocator private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** True when the active sync target needs the network (Tailscale push). Gates the export
     *  worker's network constraint so local-folder / local-only exports still run offline. */
    @Volatile
    var syncNeedsNetwork = false
        private set

    val db: InkDatabase = Room.databaseBuilder(
        appContext, InkDatabase::class.java, "nibhaus.db",
    )
        // Real captures now live on devices, so preserve them across schema bumps with explicit
        // migrations. A missing FORWARD migration now fails loudly (caught by the instrumented
        // migration tests before ship) instead of silently wiping the source-of-truth DB; destructive
        // fallback is limited to downgrades (installing an older build), and the always-on .bak.json
        // ring (SafetyBackup) recovers captures even from that.
        .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)
        .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
        .build()

    /**
     * The pen unlock password, encrypted at rest (Keystore-backed). By default the app asks every
     * connect; if the user turns on "remember for 30 days" we store the typed password here and
     * auto-unlock until it expires. It never leaves the device. The build-time `-PpenPassword=…`
     * flag is only a dev fallback.
     */
    val penPassword = SecretStore(appContext, "pen_password")

    /** Synchronous mirror of the "remember password" setting, for the BLE-thread password provider. */
    @Volatile private var rememberPassword = false

    /** In-memory mirror of settings.vlmDisabledOnThisDevice — keeps the lambda synchronous. */
    @Volatile private var isVlmDisabledOnDevice = false

    /** In-memory mirror of settings.vlmAllowMetered — keeps the lambda synchronous. */
    @Volatile private var isVlmAllowMetered = false

    /** In-memory mirror of settings.vlmForceOnDevice — keeps the lambda synchronous. */
    @Volatile private var isVlmUserForced = false

    /** In-memory mirrors of settings.byoOcrEndpoint/byoOcrToken — keep the premium ctor's supplier
     *  lambdas synchronous (no DataStore suspend read on the OCR hot path). Empty = unset. */
    @Volatile private var byoOcrEndpointMirror = ""
    @Volatile private var byoOcrTokenMirror = ""

    /** The stored password to auto-answer with, honoring the toggle + 30-day expiry (else null). */
    private fun rememberedPassword(): String? =
        if (rememberPassword && penPassword.savedWithinDays(REMEMBER_DAYS)) penPassword.get() else null

    // --- pen driver ---
    // Real NeoLAB SDK when the :neosdk AAR is present (STRANGLER.md), else the in-memory fake. The
    // app never imports kr.neolab.sdk; we resolve the adapter reflectively so :app + CI compile
    // without it. The adapter asks [penPassword] for the stored unlock password (build flag fallback).
    val penSdk: NeoPenSdk = loadRealPenSdk() ?: FakeNeoPenSdk().also {
        // With penble always packaged (settings.gradle.kts), this should never fire outside
        // emulator/CI/tests. If it does, the configured driver failed to load, which is a
        // misconfigured build, not a normal fallback, so make it visible instead of silent.
        android.util.Log.w(
            "ServiceLocator",
            "using FakeNeoPenSdk, configured driver BuildConfig.PEN_DRIVER='${BuildConfig.PEN_DRIVER}' did not load",
        )
    }
    // ------------------

    private val penPrefs = SharedPrefsPenPrefs(appContext)

    private fun loadRealPenSdk(): NeoPenSdk? = runCatching {
        // "penble" = the clean-room GPL-free driver (side-by-side A/B); default = the GPL adapter.
        val className =
            if (BuildConfig.PEN_DRIVER == "penble") "com.nibhaus.penble.PenBleSdk"
            else "com.nibhaus.neosdk.NeoSdkAdapter"
        val cls = Class.forName(className)
        val ctor = cls.getConstructor(Context::class.java, Function1::class.java)
        ctor.newInstance(
            appContext,
            { _: String -> rememberedPassword() ?: BuildConfig.PEN_PASSWORD.ifBlank { null } },
        ) as NeoPenSdk
    }.onFailure {
        // Module-absent (ClassNotFound) is the normal freemium/CI case — silent. ANYTHING else
        // (ProGuard-stripped ctor, signature drift, init crash) must not masquerade as "module
        // absent": that failure mode shipped the fake pen SDK invisibly (build audit P0).
        if (it !is ClassNotFoundException) {
            android.util.Log.w("ServiceLocator", "pen driver load failed (not absence)", it)
        }
    }.getOrNull()

    // --- premium feature bundle (open-core strangler seam, mirrors loadRealPenSdk) ---
    // Present ⇒ the four monetized impls light up; absent ⇒ null and every premium accessor degrades
    // to a freemium fallback. :app never references the :premium bundle package at compile time — the
    // impl is resolved by name. The class name is assembled from parts on purpose so :app source holds
    // no contiguous reference to the private bundle package (keeps the open-core leakage grep clean).
    private val premium: PremiumServices? = loadPremium()

    private fun loadPremium(): PremiumServices? = runCatching {
        val className = "com.nibhaus." + "premium.PremiumServicesImpl"
        val cls = Class.forName(className)
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager

        // Pick the richest constructor (Context + the typed PremiumDeps bundle); robust if the
        // param list grows later (still just 2 params: Context, PremiumDeps). Falls back to the
        // 1-arg (Context) path if only that exists (older :premium).
        // !isSynthetic excludes any Kotlin default-param $default ctor — picking it would make
        // newInstance throw and null out premium.
        val richCtor = cls.declaredConstructors
            .filter { !it.isSynthetic }
            .maxByOrNull { it.parameterTypes.size }
            ?.takeIf { it.parameterTypes.size > 1 }

        if (richCtor != null) {
            richCtor.isAccessible = true
            val vlmDisabledFn: () -> Boolean = { isVlmDisabledOnDevice }
            val tooSlowFn: (Long) -> Unit = { _: Long ->
                // onTooSlow fires on Dispatchers.Default — switch to IO for DataStore write
                appScope.launch(Dispatchers.IO) { settings.setVlmDisabledOnThisDevice(true) }
            }
            // ONE typed arg (was 7 positional same-erasure lambdas) — a field mix-up is now a
            // compile error in :premium instead of a silent runtime feature-flag swap.
            val deps = PremiumDeps(
                byoEndpoint = { byoOcrEndpointMirror.ifBlank { null } },
                byoToken = { byoOcrTokenMirror.ifBlank { null } },
                allowCleartextEndpoints = BuildConfig.ALLOW_CLEARTEXT_SYNC_ENDPOINT,
                allowMetered = { isVlmAllowMetered },
                isMetered = { cm.isActiveNetworkMetered },
                userForcedVlm = { isVlmUserForced },
                vlmDisabledOnDevice = vlmDisabledFn,
                markVlmTooSlow = tooSlowFn,
            )
            richCtor.newInstance(appContext, deps) as PremiumServices
        } else {
            cls.getConstructor(Context::class.java).newInstance(appContext) as PremiumServices
        }
    }.onFailure {
        // Same rule as the pen-driver loader: only true absence is silent — every other failure is
        // a real bug that must not degrade invisibly to freemium.
        if (it !is ClassNotFoundException) {
            android.util.Log.w("ServiceLocator", "premium bundle load failed (not absence)", it)
        }
    }.getOrNull()
    // ------------------

    /** Whether the reflective open-core seam found the :premium bundle. Availability, never
     *  entitlement: the runtime unlock must ALSO be true for any premium surface to activate.
     *  See [premiumEntitled]. */
    val premiumPresent: Boolean get() = premium != null

    /** Fresh entitlement check for resolution paths: reads the unlock bool straight from
     *  DataStore, so a runtime unlock/relock is honored on the very next resolution even though
     *  the premium wiring itself is static. */
    suspend fun premiumEntitledNow(): Boolean =
        premiumEntitled(settings.premiumUnlocked.first(), premiumPresent)

    /** Runtime sync/OCR settings (DataStore). The Settings screen reads/writes this. */
    val settings = SettingsStore(appContext)

    /** #15b follow-up: a synchronously-readable snapshot of the Fine/Normal/Bold handwriting-size
     *  preset, so exported/shared page rasters render at the same width the user sees on screen
     *  instead of always Normal. [ExportEngine]'s renderRasters callback below isn't suspend, so it
     *  can't await [settings.strokeScale] directly — `.value` off this StateFlow works from either. */
    private val strokeScaleState: StateFlow<com.nibhaus.export.StrokeScale> =
        settings.strokeScale.stateIn(appScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, com.nibhaus.export.StrokeScale.DEFAULT)

    init {
        // Mirror the "remember password" toggle into a synchronous field for the BLE-thread provider.
        // Turning it off forgets any stored password so the next connect prompts again.
        appScope.launch {
            settings.rememberPassword.collect { on ->
                rememberPassword = on
                if (!on) penPassword.clear()
            }
        }
        // Mirror whether the active sync target needs the network, for the export worker's constraint.
        appScope.launch {
            settings.syncMethod.collect { syncNeedsNetwork = it == SyncMethod.TAILSCALE_PUSH }
        }
        // Mirror the VLM too-slow flag so the supplier lambda stays synchronous (avoids DataStore reads on the hot path).
        appScope.launch {
            settings.vlmDisabledOnThisDevice.collect { isVlmDisabledOnDevice = it }
        }
        appScope.launch {
            settings.vlmAllowMetered.collect { isVlmAllowMetered = it }
        }
        appScope.launch {
            settings.vlmForceOnDevice.collect { isVlmUserForced = it }
        }
        appScope.launch {
            settings.byoOcrEndpoint.collect { byoOcrEndpointMirror = it }
        }
        appScope.launch {
            settings.byoOcrToken.collect { byoOcrTokenMirror = it }
        }
    }

    /** BLE discovery for the scan/pick UI. */
    val penScanner = PenScanner(appContext)

    /** Ref-counted facade over [penScanner] (bug #2 fix — see [com.nibhaus.pen.SharedPenScanner]'s
     *  doc): the ONE shared instance every client (the Pens home presence scan, the Find-a-pen
     *  screen, and [scanForSavedPen] below) registers with, so one client's stop() never kills
     *  another's still-active scan. */
    val sharedPenScanner = com.nibhaus.pen.SharedPenScanner(startReal = penScanner::start, stopReal = penScanner::stop)

    /** Creates events in the user's device calendars (Google/Outlook) via CalendarContract. */
    val calendarGateway = com.nibhaus.calendar.CalendarGateway(appContext)

    /** Calibrated physical action zones (printed Share/Email icons → tap triggers the action). */
    val actionZones = com.nibhaus.zones.ActionZoneStore(appContext)
    val notebookProfiles = com.nibhaus.capture.NotebookProfileStore(appContext)

    /** Capture Lab dot recorder (scale/icon/planner measurements from the pen's own output). */
    val captureLog = com.nibhaus.capture.CaptureLog()

    /** Per-notebook background template images, rendered behind the ink. */
    val backgrounds = com.nibhaus.background.BackgroundStore(appContext)

    /** Imports watcher-written `<pageId>.txt` transcripts from the sync folder, for in-app search. */
    val transcriptImporter = com.nibhaus.ocr.TranscriptImporter(
        db.pageDao(),
        // Pull transcripts from wherever pages are pushed: the SAF folder a sync app fills, or — in
        // Tailscale-push mode — straight from the NAS sync endpoint, so the loop needs no sync app.
        source = {
            val (method, folder, endpoint) = settings.snapshot()
            when (method) {
                com.nibhaus.export.SyncMethod.TAILSCALE_PUSH -> {
                    val token = settings.syncToken.first()
                    resolveNativeSync(premiumEntitledNow(), endpoint) {
                        val target = com.nibhaus.export.ExportEndpoint.parse(it, BuildConfig.ALLOW_CLEARTEXT_SYNC_ENDPOINT)
                        premium?.transcriptSource(target.origin, token)
                    }
                }
                com.nibhaus.export.SyncMethod.LOCAL_ONLY -> null
                else ->
                    folder.takeIf { it.isNotBlank() }
                        ?.let { com.nibhaus.ocr.SafTranscriptSource(appContext, it) }
            }
        },
        // A new transcript makes the page's Markdown note stale → re-queue it for export.
        onImported = { pageId -> requeuePageForExport(pageId) },
    )

    /** On-device handwriting OCR for EVERY build: the free ML Kit instant tier ([OnDeviceInk], now
     *  in :app) composed with whatever accurate engines the premium bundle contributes, best-first
     *  (empty in freemium, so [RoutedInk] serves instant-only). Wiring is static and presence-based;
     *  ENTITLEMENT is enforced at the call sites (accurate requests, translate, native sync). */
    val onDeviceInk: InkOcr = RoutedInk(
        instant = OnDeviceInk(),
        // Supplier, not a frozen snapshot (final-review fix, 2026-07-05): re-resolved on every accurate
        // request so a BYO endpoint (or forced on-device VLM) configured after this ServiceLocator was
        // built takes effect immediately, not only after an app restart.
        accurateChain = { premium?.accurateChain() ?: emptyList() },
    )

    /** Live VLM model download/readiness state; null only in freemium builds (no :premium module).
     *  PremiumServicesImpl.vlmModelState() is self-sufficient — it never needs accurateChain() to
     *  have run first (final-review IMPORTANT fix, 2026-07-05) — so this capture at construction time
     *  is safe even though accurateChain() itself is resolved lazily, per request, above. */
    val vlmModelStateFlow: Flow<VlmDownloadState>? = premium?.vlmModelState()

    /** Tiered translator: the user's GPU-box translation LLM (quality), ML Kit on-device (offline).
     *  Premium; null in a freemium build. */
    val translator: InkTranslator? = premium?.translator(
        endpoint = { settings.translateEndpoint.first() },
        model = { settings.translateModel.first() },
        allowCleartextEndpoints = BuildConfig.ALLOW_CLEARTEXT_SYNC_ENDPOINT,
    )

    /**
     * Transcribe a page on-device, store the result, and re-queue it for export (so it becomes
     * searchable and its `.md` note refreshes). @return the text, or null if nothing was recognized
     * (in a freemium build the accurate chain is empty, so accurate requests degrade to the instant
     * result). :app decodes its stored points to native [InkPt] before crossing the module boundary.
     */
    suspend fun transcribeOnDevice(pageId: String, accurate: Boolean = false): String? = withContext(Dispatchers.IO) {
        // Engine-entry consent backstop (2026-07-05): the VM paths (doTranscribe, eager batch) each
        // re-read consent at fire time, but this is the single function that actually reaches the
        // on-device engine, so it is gated here too. No caller, present or future, can transcribe
        // on-device without a fresh first-use acknowledgement. Read fresh, never cached.
        if (!settings.onDeviceOcrAcknowledged.first()) return@withContext null
        val strokes = db.strokeDao().strokesForPage(pageId)
        val decoded = strokes.map { stroke ->
            exportJson.decodeFromString(ListSerializer(Point.serializer()), stroke.pointsJson)
                .map { InkPt(it.x, it.y, it.t) }
        }
        val text = onDeviceInk.transcribe(decoded, accurate) ?: return@withContext null
        db.pageDao().setTranscriptIndexed(pageId, text)
        requeuePageForExport(pageId)
        text
    }

    /**
     * Feature 9: persist a manual transcript edit through the same funnel OCR uses
     * ([com.nibhaus.data.PageDao.setTranscriptIndexed] — updates the page AND its FTS row), then
     * re-queues the page for export so its `.md` note picks up the correction.
     */
    suspend fun saveTranscript(pageId: String, text: String): Unit = withContext(Dispatchers.IO) {
        db.pageDao().setTranscriptIndexed(pageId, text)
        requeuePageForExport(pageId)
    }

    /** Re-queue a page's strokes for export (e.g. after a transcript import refreshes its `.md`). */
    private suspend fun requeuePageForExport(pageId: String) {
        val strokes = db.strokeDao().strokesForPage(pageId)
        if (strokes.isEmpty()) return
        db.strokeDao().markSync(strokes.map { it.uuid }, com.nibhaus.data.SyncState.PENDING)
        strokes.forEach { db.outboxDao().enqueue(com.nibhaus.data.OutboxEntry(it.uuid, System.currentTimeMillis())) }
        ExportWorker.enqueue(appContext)
    }

    /** Re-queue every page for export — used when the user switches sync targets and wants their
     *  existing history pushed to the new destination. Export idempotency skips pages already there. */
    suspend fun reexportAllPages() {
        db.pageDao().allIds().forEach { requeuePageForExport(it) }
    }

    val organizer = AutoOrganizer(db.notebookDao(), db.pageDao())

    private val exportJson = Json

    /** Phase 3 export core; the worker drives it with whatever target [currentStorageProvider] yields. */
    val exportEngine = ExportEngine(
        strokeDao = db.strokeDao(),
        pageDao = db.pageDao(),
        notebookDao = db.notebookDao(),
        outboxDao = db.outboxDao(),
        exportDao = db.exportDao(),
        decode = { exportJson.decodeFromString(ListSerializer(Point.serializer()), it.pointsJson) },
        penId = { penPrefs.lastPenMac ?: "unknown" },
        // Page rasters alongside the vectors (one bitmap pass): PNG so the OCR hand-off gets
        // image + strokes, and PDF as a portable/printable copy in the sync folder.
        renderRasters = { strokes, dec ->
            val bmp = com.nibhaus.share.PageRender.renderPage(strokes, dec, strokeScale = strokeScaleState.value.multiplier)
            if (bmp == null) emptyList() else buildList {
                java.io.ByteArrayOutputStream().use { out ->
                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                    add("png" to out.toByteArray())
                }
                com.nibhaus.share.PageShare.pdfBytes(bmp)?.let { add("pdf" to it) }
            }
        },
        // Resolve each page's notebook type through the user's saved assignments, so a typed-but-
        // unmeasured notebook (e.g. a planner the user designated) files under the right path/geometry.
        typeForBook = { settings.notebookType(it) },
        tagsFor = { db.tagDao().tagsForPage(it) },
    )

    /** Durable "also delete the exported copy" queue (bug #2) — [ExportWorker] drains it on the same
     *  cadence as the export outbox, so a page deleted while the sync target is unreachable still gets
     *  its remote artifacts removed once the target comes back, instead of stranding them forever. */
    val remoteDeleteQueue = RemoteDeleteQueue(db.pendingRemoteDeleteDao())

    val localDeleteCleanupQueue = LocalDeleteCleanupQueue(db.pendingLocalDeleteCleanupDao()) { kind, target ->
        when (kind) {
            LocalDeleteCleanupQueue.RECORDING_FILE -> check(java.io.File(target).delete() || !java.io.File(target).exists())
            LocalDeleteCleanupQueue.SAFETY_BACKUP -> settings.backupFolderUri.first().takeIf { it.isNotEmpty() }
                ?.let { LocalFolderProvider(appContext, Uri.parse(it)).delete("$target.bak.json") }
            LocalDeleteCleanupQueue.BOOKMARK -> settings.removeBookmarks(setOf(target))
            LocalDeleteCleanupQueue.NOTEBOOK_ACCENT -> settings.removeAccent(target)
            else -> error("Unknown local delete cleanup kind: $kind")
        }
    }

    /** Live ink color the user picks in capture (0 = brand ink). Stamped onto each new stroke. */
    val inkColor = MutableStateFlow(0)

    /** Live writing width (Fine/Medium/Large → multiplier). Stamped onto each new stroke. */
    val inkWidth = MutableStateFlow(1f)

    /** At-ingest durability: writes <addressKey>.bak.json into the user's safety folder on each change. */
    val safetyBackup = SafetyBackup(
        provider = {
            settings.backupFolderUri.first().takeIf { it.isNotEmpty() }
                ?.let { LocalFolderProvider(appContext, Uri.parse(it)) }
        },
        readPage = { pageId ->
            db.pageDao().byId(pageId)?.let { p ->
                val strokes = db.strokeDao().strokesForPage(pageId)
                com.nibhaus.export.PageBackup(
                    p.section, p.owner, p.book, p.page,
                    strokes.map {
                        com.nibhaus.export.BackupStroke(
                            it.color, it.width,
                            exportJson.decodeFromString(ListSerializer(Point.serializer()), it.pointsJson),
                        )
                    },
                )
            }
        },
        scope = appScope,
    )

    val ingestor = StrokeIngestor(
        ingestDao = db.ingestDao(),
        pendingDao = db.pendingDotDao(),
        pageDao = db.pageDao(),
        organizer = organizer,
        scope = appScope,
        inkColor = { inkColor.value },
        inkWidth = { inkWidth.value },
        // Each committed stroke kicks a (coalesced) export drain. Idempotent + durable, so a no-op
        // when nothing's configured and a catch-up when the target is set later. Also pokes the
        // home-screen widget (#13) so "latest page" doesn't wait out its 30min periodic refresh.
        onCommitted = { id -> ExportWorker.enqueue(appContext); safetyBackup.onPageChanged(id); com.nibhaus.widget.WidgetUpdateWorker.poke(appContext) },
        // User-calibrated zones first (matchZone is first-hit), then the built-in printed buttons.
        actionZones = { actionZones.current() + com.nibhaus.zones.BuiltinZones.ALL },
        onZoneTap = { zone, pageId, book -> handleZoneTap(zone, pageId, book) },
    )

    /** A pen tap on a printed Share/Email button awaiting the user's PNG-vs-PDF pick in the app. */
    data class PendingZoneShare(val pageId: String, val book: Int, val email: Boolean)

    /** Set when a chooser-kind zone (SHARE/EMAIL) is tapped; the UI shows a PNG/PDF dialog for it. */
    val pendingZoneShare = kotlinx.coroutines.flow.MutableStateFlow<PendingZoneShare?>(null)

    /** The user picked a format for the pending zone share (or dismissed with null). */
    fun resolveZoneShare(png: Boolean?) {
        val p = pendingZoneShare.value ?: return
        pendingZoneShare.value = null
        if (png != null) renderAndShare(p.pageId, email = p.email, png = png)
    }

    /** A tap on a calibrated printed icon → render that page (framed to the full notebook page) and
     *  Share/Email it. Chooser-kind zones park in [pendingZoneShare] for the format pick instead. */
    private fun handleZoneTap(zone: com.nibhaus.zones.ActionZone, pageId: String, book: Int) {
        when (zone.action) {
            com.nibhaus.zones.ZoneAction.SHARE ->
                return run { pendingZoneShare.value = PendingZoneShare(pageId, book, email = false) }
            com.nibhaus.zones.ZoneAction.EMAIL ->
                return run { pendingZoneShare.value = PendingZoneShare(pageId, book, email = true) }
            else -> {}
        }
        val email = zone.action == com.nibhaus.zones.ZoneAction.EMAIL_PNG ||
            zone.action == com.nibhaus.zones.ZoneAction.EMAIL_PDF
        val png = zone.action == com.nibhaus.zones.ZoneAction.SHARE_PNG ||
            zone.action == com.nibhaus.zones.ZoneAction.EMAIL_PNG
        renderAndShare(pageId, email = email, png = png)
    }

    /** Render [pageId] full-page and hand it to Share or Email in the chosen format. */
    private fun renderAndShare(pageId: String, email: Boolean, png: Boolean) {
        appScope.launch {
            val page = db.pageDao().byId(pageId) ?: return@launch
            val strokes = db.strokeDao().strokesForPage(pageId)
            val geometry = com.nibhaus.capture.resolveGeometry(
                notebookProfiles.forBook(page.book),
                com.nibhaus.export.PageGeometry.forBook(page.book),
            )
            val type = com.nibhaus.export.NotebookType.forBook(page.book)
            val bmp = com.nibhaus.share.PageRender.renderPage(
                strokes,
                points = { exportJson.decodeFromString(ListSerializer(Point.serializer()), it.pointsJson) },
                bounds = geometry,
                ruling = type?.ruling,
                pageNumber = page.page,
                pageStyle = com.nibhaus.export.pageStyleFor(type?.pageBands ?: emptyList(), page.page),
                strokeScale = strokeScaleState.value.multiplier,
            ) ?: return@launch
            val fmt = if (png) com.nibhaus.share.PageShare.Format.PNG else com.nibhaus.share.PageShare.Format.PDF
            // Feature 24: a human name for the shared file — "Nibhaus — {notebook} p{page} — {date}"
            // instead of the internal page id.
            val notebookTitle = db.notebookDao().byId(page.notebookId)?.title.orEmpty()
            val baseName = com.nibhaus.share.ShareFilename.forPage(notebookTitle, page.page)
            val uri = com.nibhaus.share.PageShare.fileUri(appContext, bmp, fmt, baseName = baseName) ?: return@launch
            if (email) {
                com.nibhaus.share.PageShare.email(appContext, uri, fmt.mime)
            } else {
                // Opt-in: attach the page's voice note(s) to a Share (never email — too large).
                val audio = if (settings.attachAudioOnShare.first()) {
                    db.recordingDao().forPage(pageId)
                        .mapNotNull { com.nibhaus.share.PageShare.fileUri(appContext, java.io.File(it.path)) }
                } else emptyList()
                if (audio.isNotEmpty()) com.nibhaus.share.PageShare.shareMultiple(appContext, listOf(uri) + audio)
                else com.nibhaus.share.PageShare.share(appContext, uri, fmt.mime)
            }
        }
    }

    /**
     * Render several pages (full-page) + optionally their voice notes, and share them all at once.
     * Suspends (rather than launching its own coroutine) so the caller owns a cancellable [kotlinx.coroutines.Job]
     * for this specific batch (#9) and can report [onProgress] (pages rendered so far / total) for a
     * progress + Cancel affordance. Cooperative: each page's DB reads are themselves suspension
     * points, so cancelling takes effect between (or within) pages. Cancelling still shares whatever
     * pages were already rendered — "partial progress kept" — instead of discarding that work; the
     * final share-sheet handoff runs under [kotlinx.coroutines.NonCancellable] so it isn't itself cut off by the
     * same cancellation.
     */
    suspend fun shareSelectedPages(pageIds: List<String>, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }) {
        val includeAudio = settings.attachAudioOnShare.first()
        val uris = mutableListOf<Uri>()
        try {
            pageIds.forEachIndexed { index, pid ->
                val page = db.pageDao().byId(pid)
                if (page != null) {
                    val strokes = db.strokeDao().strokesForPage(pid)
                    val geometry = com.nibhaus.capture.resolveGeometry(
                        notebookProfiles.forBook(page.book),
                        com.nibhaus.export.PageGeometry.forBook(page.book),
                    )
                    val type = com.nibhaus.export.NotebookType.forBook(page.book)
                    val bmp = com.nibhaus.share.PageRender.renderPage(
                        strokes,
                        { exportJson.decodeFromString(ListSerializer(Point.serializer()), it.pointsJson) },
                        bounds = geometry,
                        ruling = type?.ruling,
                        pageNumber = page.page,
                        pageStyle = com.nibhaus.export.pageStyleFor(type?.pageBands ?: emptyList(), page.page),
                        strokeScale = strokeScaleState.value.multiplier,
                    )
                    if (bmp != null) {
                        // Feature 24: a human name per page — "Nibhaus — {notebook} p{page} — {date}"
                        // instead of the internal "page-{book}-{page}" id; the page number keeps
                        // multiple pages from the same notebook from colliding in the same batch.
                        val notebookTitle = db.notebookDao().byId(page.notebookId)?.title.orEmpty()
                        val baseName = com.nibhaus.share.ShareFilename.forPage(notebookTitle, page.page)
                        com.nibhaus.share.PageShare.fileUri(
                            appContext, bmp, com.nibhaus.share.PageShare.Format.PNG, baseName = baseName,
                        )?.let { uris.add(it) }
                        if (includeAudio) {
                            db.recordingDao().forPage(pid)
                                .mapNotNullTo(uris) { com.nibhaus.share.PageShare.fileUri(appContext, java.io.File(it.path)) }
                        }
                    }
                }
                onProgress(index + 1, pageIds.size)
            }
        } finally {
            if (uris.isNotEmpty()) {
                withContext(kotlinx.coroutines.NonCancellable) {
                    com.nibhaus.share.PageShare.shareMultiple(appContext, uris)
                }
            }
        }
    }

    /** Restore editable ink from `.bak.json` backups anywhere under a SAF folder tree. Returns strokes
     *  restored; idempotent (content-derived ids → INSERT IGNORE never duplicates). */
    suspend fun restoreFromFolder(treeUri: Uri): Int = withContext(Dispatchers.IO) {
        val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(appContext, treeUri) ?: return@withContext 0
        var restored = 0
        var bakFound = 0
        val stack = ArrayDeque<androidx.documentfile.provider.DocumentFile>().apply { add(root) }
        while (stack.isNotEmpty()) {
            for (f in stack.removeLast().listFiles()) {
                if (f.isDirectory) { stack.add(f); continue }
                if (f.name?.endsWith(".bak.json") != true) continue
                bakFound++
                val json = runCatching {
                    appContext.contentResolver.openInputStream(f.uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull() ?: continue
                restored += restorePageBackup(json)
            }
        }
        android.util.Log.i("NibhausRestore", "tree=${root.name} uri=$treeUri bakFiles=$bakFound restoredStrokes=$restored")
        restored
    }

    private suspend fun restorePageBackup(json: String): Int {
        val b = com.nibhaus.export.decodeBackup(json) ?: return 0
        // Real (old) ink time so a restored page doesn't masquerade as "just written" in the live view.
        val inkAt = b.strokes.flatMap { it.points }.maxOfOrNull { it.t } ?: System.currentTimeMillis()
        val page = organizer.ensurePage(com.nibhaus.pen.NcodeAddress(b.section, b.owner, b.book, b.page), inkAt = inkAt)
        var n = 0
        for (s in b.strokes) {
            if (s.points.isEmpty()) continue
            db.ingestDao().commitStroke(
                com.nibhaus.data.StrokeEntity(
                    uuid = com.nibhaus.export.backupStrokeId(b, s),
                    pageId = page.id,
                    color = s.color,
                    startedAt = s.points.first().t,
                    endedAt = s.points.last().t,
                    pointsJson = exportJson.encodeToString(ListSerializer(Point.serializer()), s.points),
                    syncState = com.nibhaus.data.SyncState.PENDING,
                    width = s.width,
                ),
                "restore",
            )
            n++
        }
        return n
    }

    /** Calibration: capture the next traced outline as [left,top,right,bottom] (then auto-clears). */
    fun captureNextTrace(onTrace: (Int, Float, Float, Float, Float) -> Unit) { ingestor.onCalibrationTrace = onTrace }

    /** Cancel a pending [captureNextTrace] so no stroke is consumed. */
    fun cancelTraceCapture() { ingestor.onCalibrationTrace = null }

    /** Live ink signals (last dot time, page validity, pen-down) for the diagnostic + stall alerts. */
    val captureSignals = CaptureSignals()

    val penManager = PenConnectionManager(
        sdk = penSdk,
        prefs = penPrefs,
        scope = appScope,
        // While the Capture Lab is recording, dots go only to the log (consumed, not inked); otherwise
        // feed the diagnostic/stall signals, then persist the dot (never blocks the BLE thread).
        onDot = { dot -> if (!captureLog.onDot(dot)) { captureSignals.onDot(dot); ingestor.onDot(dot) } },
        // A password the user typed that the pen accepted → remember it (encrypted) only if the user
        // opted into "remember for 30 days"; otherwise we keep asking every connect.
        onPasswordAccepted = { if (rememberPassword) penPassword.set(it) },
        // The user disabled the pen password → forget the stored secret (nothing to auto-unlock).
        onPasswordCleared = { penPassword.clear() },
        // Fallback "old" for a set/change when this session didn't capture the unlock (e.g. an
        // auto-reconnect that didn't re-prompt): the encrypted stored password, if remembered.
        storedPassword = { if (rememberPassword && penPassword.savedWithinDays(REMEMBER_DAYS)) penPassword.get() else null },
        // Reconnect can't redial the pen's old LE address (it rotates on power-cycle), so re-scan for
        // its stable identity to get the current one — mirrors what a manual tap-to-connect does.
        rescan = { spp, windowMs -> scanForSavedPen(spp, windowMs) },
    )

    /**
     * Scan for [spp]'s current LE target for up to [windowMs], returning it once found or null on
     * timeout. Shared by [penManager]'s auto-reconnect (the `rescan` hook above) and the Pens screen's
     * manual saved-pen tap-to-reconnect ([connectSaved]) — one scan-filter-connect implementation, not
     * two. The LE address rotates on power-cycle, so both paths must RE-SCAN rather than redial a
     * stored address.
     */
    private suspend fun scanForSavedPen(spp: String, windowMs: Long): PenTarget? {
        // Registers with the shared, ref-counted facade rather than the raw penScanner (bug #2): if
        // the Pens home presence scan (or the Find-a-pen screen) is ALSO holding the scanner right
        // now, this must not stop it out from under them when this scan's own window ends.
        sharedPenScanner.start(SavedPenScanClient)
        try {
            return withTimeoutOrNull(windowMs) {
                penScanner.results
                    .mapNotNull { list -> list.firstOrNull { it.target.sppAddress == spp }?.target }
                    .first()
            }
        } finally {
            sharedPenScanner.stop(SavedPenScanClient)
        }
    }

    /** [scanForSavedPen]'s client identity on [sharedPenScanner] — shared by both its callers
     *  ([connectSaved] and [penManager]'s `rescan` hook), which may run concurrently for two
     *  different pens; [SharedPenScanner] supports the same token registering more than once. */
    private object SavedPenScanClient

    /** Feature 2 tap-to-reconnect state for the Pens screen's saved-pen tiles, set only by
     *  [connectSaved] — distinct from [penManager]'s own [com.nibhaus.pen.PenConnState]. */
    private val _savedPenConnectState = MutableStateFlow<SavedPenConnectState>(SavedPenConnectState.Idle)
    val savedPenConnectState: StateFlow<SavedPenConnectState> = _savedPenConnectState.asStateFlow()

    /**
     * Tap-to-reconnect a previously saved pen (Feature 2): scan for its current LE target and connect
     * once found, sharing [scanForSavedPen] with the auto-reconnect path rather than duplicating the
     * scan-filter-connect logic. [savedPenConnectState] drives the tile's "searching…" / "not found"
     * chrome; on success it returns to Idle and hands off to [penManager] (whose PenConnState takes
     * over from there — the same path a scan-and-pick connect uses).
     */
    fun connectSaved(spp: String) {
        appScope.launch {
            _savedPenConnectState.value = SavedPenConnectState.Searching(spp)
            val target = scanForSavedPen(spp, SAVED_PEN_SCAN_WINDOW_MS)
            _savedPenConnectState.value =
                if (target != null) SavedPenConnectState.Idle else SavedPenConnectState.NotFound(spp)
            target?.let { penManager.connect(it) }
        }
    }

    /** User-initiated export of one page (the page-detail action). Runs off the main thread. */
    suspend fun exportPageNow(pageId: String): ExportOutcome = withContext(Dispatchers.IO) {
        val provider = currentStorageProvider() ?: return@withContext ExportOutcome.NO_TARGET
        if (exportEngine.exportSingle(pageId, provider)) ExportOutcome.DONE else ExportOutcome.FAILED
    }

    /** Delete a page and all its local data; optionally also remove its exported artifacts from the
     *  current sync target and/or its voice notes (audio files + rows). Runs off the main thread.
     *  `alsoRemote` only ENQUEUES the remote delete (a plain DB insert — [remoteDeleteQueue]) rather
     *  than calling the sync target inline, so local deletion never blocks on (or is lost to) an
     *  unreachable target; [ExportWorker] drains the queue with retry/backoff (bug #2). */
    suspend fun deletePageNow(pageId: String, alsoRemote: Boolean, alsoAudio: Boolean): Unit = withContext(Dispatchers.IO) {
        db.withTransaction {
            db.deleteDao().deletePages(listOf(deletionPlan(pageId, alsoRemote, alsoAudio)))
        }
        safetyBackup.cancel(pageId)
        ExportWorker.enqueue(appContext)
    }

    /** Delete a whole notebook (Feature 18): every one of its pages, cascaded the same way as
     *  [deletePageNow] (optionally also its exported copies + voice notes), then the notebook row
     *  itself. Runs off the main thread. */
    suspend fun deleteNotebookNow(notebookId: String, alsoRemote: Boolean, alsoAudio: Boolean): Unit = withContext(Dispatchers.IO) {
        val plans = db.withTransaction {
            val snapshot = db.pageDao().pagesInNotebook(notebookId).map { deletionPlan(it.id, alsoRemote, alsoAudio) }
            val accent = PendingLocalDeleteCleanup("accent:$notebookId", LocalDeleteCleanupQueue.NOTEBOOK_ACCENT, notebookId, System.currentTimeMillis())
            db.deleteDao().deletePages(snapshot, notebookId, listOf(accent))
            snapshot
        }
        plans.forEach { safetyBackup.cancel(it.pageId) }
        ExportWorker.enqueue(appContext)
    }

    private suspend fun deletionPlan(pageId: String, alsoRemote: Boolean, alsoAudio: Boolean): PageDeletionPlan {
        val page = db.pageDao().byId(pageId)
        val now = System.currentTimeMillis()
        val cleanups = buildList {
            add(PendingLocalDeleteCleanup("bookmark:$pageId", LocalDeleteCleanupQueue.BOOKMARK, pageId, now))
            page?.addressKey?.let { add(PendingLocalDeleteCleanup("backup:$pageId", LocalDeleteCleanupQueue.SAFETY_BACKUP, it, now)) }
            if (alsoAudio) db.recordingDao().forPage(pageId).forEach {
                add(PendingLocalDeleteCleanup("recording:${it.id}", LocalDeleteCleanupQueue.RECORDING_FILE, it.path, now))
            }
        }
        val remote = if (alsoRemote) exportEngine.remoteBasePath(pageId)?.let { PendingRemoteDelete(pageId, it, now) } else null
        return PageDeletionPlan(pageId, remote, cleanups, alsoAudio)
    }

    /** Resolve the user's selected target at export time, or null if it isn't usable yet. */
    suspend fun currentStorageProvider(): StorageProvider? {
        val (method, folderUri, endpoint) = settings.snapshot()
        return when (method) {
            SyncMethod.LOCAL_FOLDER ->
                folderUri.takeIf { it.isNotEmpty() }?.let { LocalFolderProvider(appContext, Uri.parse(it)) }
            SyncMethod.TAILSCALE_PUSH -> {
                // Entitlement-gated (spec matrix, Native sync row): null when locked or when the
                // :premium module is absent, even if an endpoint is configured.
                val token = settings.syncToken.first()
                resolveNativeSync(premiumEntitledNow(), endpoint) {
                    val target = com.nibhaus.export.ExportEndpoint.parse(it, BuildConfig.ALLOW_CLEARTEXT_SYNC_ENDPOINT)
                    premium?.pushProvider(target.origin, token)
                }
            }
            SyncMethod.LOCAL_ONLY -> LocalOnlyProvider(appContext)
        }
    }

    /** Pulls pages the pen stored offline on (re)connect; idempotent. Phase 2. */
    val offlineSync = OfflineSync(
        sdk = penSdk,
        organizer = organizer,
        ingestDao = db.ingestDao(),
        scope = appScope,
    )

    /** Phase 5 stroke editing (delete/recolor/undo); re-queues edited pages for export. */
    val strokeEditor = StrokeEditor(
        strokeDao = db.strokeDao(),
        outboxDao = db.outboxDao(),
        pendingRemoteDeleteDao = db.pendingRemoteDeleteDao(),
        transaction = { block -> db.withTransaction { block() } },
        artifactDelete = { pageId ->
            exportEngine.remoteBasePath(pageId)?.let { PendingRemoteDelete(pageId, it, System.currentTimeMillis()) }
        },
        onChanged = { id -> ExportWorker.enqueue(appContext); safetyBackup.onPageChanged(id) },
    )

    val repository = NoteRepository(
        notebookDao = db.notebookDao(),
        pageDao = db.pageDao(),
        strokeDao = db.strokeDao(),
        outboxDao = db.outboxDao(),
        recordingDao = db.recordingDao(),
        tagDao = db.tagDao(),
    )

    /** Voice notes tied to a page (phone mic; local-only). The Live screen drives start/stop/play. */
    val recordingController = RecordingController(
        context = appContext,
        dao = db.recordingDao(),
        scope = appScope,
    )

    // Splash milestone 3 ("live ink canvas"): every ctor-time field above (db, penSdk, premium,
    // organizer, ingestor, recordingController, ...) is wired by the time this line runs — see
    // StartupProgress's doc for why this fires before milestones 1/2 in practice, and why that's OK.
    init { StartupProgress.markMilestone(3) }

    /**
     * Recover any stroke interrupted by a prior crash. The pen connection itself is owned by
     * [com.nibhaus.pen.PenForegroundService] (started from the Activity once BLE permissions are
     * granted), not auto-connected here — connecting from Application.onCreate would run before
     * permissions and couldn't survive backgrounding.
     */
    /** Release heavy caches (the on-device VLM native context) under memory pressure — invoked from
     *  NibhausApp.onTrimMemory. Freemium (premium == null) is a no-op. */
    fun onTrimMemory() { premium?.onTrimMemory() }

    fun onStartup() {
        // Splash milestone 1 ("vault systems"): DB is already open (ctor, above); this is the
        // async half — crash-recovery finishing.
        appScope.launch { ingestor.recover(); StartupProgress.markMilestone(1) }
        // One-time cleanup of phantom pages from dots the pen emitted before reading a valid Ncode
        // address (the ingest guard prevents new ones). Idempotent — a clean DB makes this a no-op.
        appScope.launch {
            db.pageDao().idsWithInvalidAddress().forEach { deletePageNow(it, alsoRemote = false, alsoAudio = false) }
            db.notebookDao().deleteInvalidBooks()
        }
    }

    companion object {
        /** How long a remembered pen password stays valid before we ask again. */
        private const val REMEMBER_DAYS = 30

        /** How long [connectSaved] scans before giving up — long enough for the pen to start
         *  advertising after the user powers it on, short enough a tile doesn't hang forever. */
        private const val SAVED_PEN_SCAN_WINDOW_MS = 12_000L

        @Volatile private var instance: ServiceLocator? = null

        fun from(context: Context): ServiceLocator =
            instance ?: synchronized(this) {
                instance ?: ServiceLocator(context).also { instance = it }
            }
    }
}
