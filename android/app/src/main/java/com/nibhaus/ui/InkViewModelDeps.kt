package com.nibhaus.ui

import com.nibhaus.export.ExportOutcome
import com.nibhaus.pen.CaptureSignals
import com.nibhaus.pen.PenConnectionManager
import com.nibhaus.pen.PenScanner
import com.nibhaus.pen.SavedPenConnectState
import com.nibhaus.pen.SharedPenScanner
import com.nibhaus.premiumapi.VlmDownloadState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Parameter-object groups for [InkViewModel]'s constructor (architecture audit P1-4): pen/BLE,
 * sync/export, OCR, and physical action-zone dependencies. Every field keeps the exact type and
 * default value the flat constructor parameter had — this is a pure grouping, zero behavior change.
 */

/** Pen connection + BLE scan dependencies. */
class PenDeps(
    val penManager: PenConnectionManager,
    val scanner: PenScanner,
    /** Ref-counted facade over [scanner] (bug #2 fix) so the Find-a-pen screen's manual scan and the
     *  Pens home presence scan can both hold it at once without one's stop() killing the other's
     *  still-active scan — see [SharedPenScanner]'s doc. Defaults to a fresh single-owner facade
     *  around [scanner] so existing tests/callers that don't care about ref-counting are unaffected;
     *  production wiring ([com.nibhaus.di.ServiceLocator]) passes the one real shared instance. */
    val sharedScanner: SharedPenScanner = SharedPenScanner(startReal = scanner::start, stopReal = scanner::stop),
    /** Whether the pen unlock password is currently stored (encrypted) for auto-unlock. */
    val hasStoredPassword: () -> Boolean = { false },
    val signals: CaptureSignals? = null,
    /** tap-to-reconnect a saved pen by its stable spp identity (scan-filter-connect,
     *  shared with [com.nibhaus.di.ServiceLocator]'s auto-reconnect rescan). Default no-op keeps
     *  existing tests/callers valid. */
    val connectSaved: (spp: String) -> Unit = {},
    /** Live "searching…" / "not found" state for [connectSaved]'s in-flight scan. */
    val savedPenConnectState: StateFlow<SavedPenConnectState> = MutableStateFlow(SavedPenConnectState.Idle),
)

/** Export / sync / page-and-notebook lifecycle dependencies. */
class SyncDeps(
    val exportPageNow: suspend (pageId: String) -> ExportOutcome = { ExportOutcome.NO_TARGET },
    val reexportAll: suspend () -> Unit = {},
    val deletePageOp: suspend (pageId: String, alsoRemote: Boolean, alsoAudio: Boolean) -> Unit = { _, _, _ -> },
    /** cascade-delete a whole notebook (every page's local data, optionally its exported
     *  copies + voice notes), then the notebook row. Default no-op keeps existing tests/callers valid. */
    val deleteNotebookOp: suspend (notebookId: String, alsoRemote: Boolean, alsoAudio: Boolean) -> Unit = { _, _, _ -> },
    val restoreBackup: suspend (android.net.Uri) -> Int = { 0 },
    /** #9: suspend so InkViewModel can own a cancellable Job for one batch share; the progress
     *  callback reports (pages rendered so far, total) for a Cancel-affordance UI. */
    val shareSelected: suspend (pageIds: List<String>, onProgress: (Int, Int) -> Unit) -> Unit = { _, _ -> },
)

/** On-device / server OCR + transcript dependencies. */
class OcrDeps(
    val transcripts: com.nibhaus.ocr.TranscriptImporter? = null,
    /** On-device handwriting OCR of a page → stored transcript (or null). ML Kit Digital Ink.
     *  accurate=true → best-available quality tier (may be slower); false → fast/instant tier. */
    val transcribeOnDevice: (suspend (pageId: String, accurate: Boolean) -> String?)? = null,
    /** persist a manually edited transcript through the same DB funnel OCR uses (updates
     *  the page's transcript AND its FTS row). Default no-op keeps existing tests/callers valid. */
    val saveTranscriptOp: suspend (pageId: String, text: String) -> Unit = { _, _ -> },
    /** Live VLM model download/readiness state from ServiceLocator; null in freemium builds. */
    val vlmState: kotlinx.coroutines.flow.Flow<VlmDownloadState>? = null,
    val vlmDisclosure: com.nibhaus.premiumapi.DownloadDisclosure? = null,
    val downloadVlmModel: (suspend (com.nibhaus.premiumapi.DownloadConsent) -> Boolean)? = null,
    /**
     * Synchronous check: is the active network connection metered (mobile data)?
     * Used by the Auto quality tier to skip the accurate pass when on mobile data.
     * Default false so tests and freemium builds never accidentally block.
     */
    val isMetered: () -> Boolean = { false },
    /** Whether the reflective open-core seam found the :premium bundle
     *  ([com.nibhaus.di.ServiceLocator.premiumPresent]). Availability, never entitlement: the
     *  entitled gate is the premium unlock AND this. The free ML Kit instant engine now lives in
     *  :app, so "on-device OCR available" is constant true and no longer injected. Default false
     *  keeps existing tests/callers freemium-safe. */
    val premiumPresent: Boolean = false,
)

/** Physical action-zone (tap-to-teach printed icons) dependencies. */
class ZoneDeps(
    val actionZones: com.nibhaus.zones.ActionZoneStore? = null,
    val captureTrace: ((onTrace: (Int, Float, Float, Float, Float) -> Unit) -> Unit)? = null,
    val cancelTrace: (() -> Unit)? = null,
)
