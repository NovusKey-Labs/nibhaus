package com.nibhaus.export

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** Delimiter for the persisted recent-search list — a control char a user can't type on the search
 *  field, so no escaping is needed for the simple join/split roundtrip. */
private const val RECENT_SEARCH_DELIMITER = ""

/** Search hints (Feature 25): how many recent searches to keep as tappable chips. */
private const val RECENT_SEARCHES_CAP = 5

/**
 * Tip-card eligibility rules for the one-time "Did you know" cards (Feature 5): pure predicates so
 * they're directly unit-testable without Compose/DataStore. Each card earns its place only once the
 * feature it points at is actually relevant — showing it any earlier would be noise.
 */
fun replayTipEligible(strokeCount: Int, dismissed: Boolean): Boolean =
    strokeCount > 20 && !dismissed

fun printedButtonTipEligible(totalPages: Int, everZoneTapped: Boolean, dismissed: Boolean): Boolean =
    totalPages >= 3 && !everZoneTapped && !dismissed

fun transcribeTipEligible(totalPages: Int, everTranscribed: Boolean, dismissed: Boolean): Boolean =
    totalPages >= 5 && !everTranscribed && !dismissed

/**
 * Pure fold step for recent-search history: add [query] to [current] (most-recent-first), moving an
 * existing case-insensitive match to the front instead of duplicating it, and capping at [cap]. Blank
 * queries are ignored. Kept top-level (not buried in a coroutine) so it's directly unit-testable.
 */
fun updatedRecentSearches(current: List<String>, query: String, cap: Int = 10): List<String> {
    val trimmed = query.trim()
    if (trimmed.isBlank()) return current
    val deduped = current.filterNot { it.equals(trimmed, ignoreCase = true) }
    return (listOf(trimmed) + deduped).take(cap)
}

/**
 * Pure add/remove step for the bookmarked-pages set (Feature 15): add [id] when [on] is true, else
 * remove it. Idempotent — adding an already-present id or removing an absent one leaves the set
 * unchanged. Kept top-level (not buried in a coroutine) so it's directly unit-testable.
 */
fun toggledSet(set: Set<String>, id: String, on: Boolean): Set<String> =
    if (on) set + id else set - id

/**
 * Migrates a legacy [ThemeMode] key (from before the selectable color-palette feature) to a
 * palette id + light-paper toggle. Pure so it's directly unit-testable without a DataStore
 * instance; used as the lazy fallback when `ui.palette`/`ui.light_paper` haven't been written yet.
 */
fun paletteIdFromLegacyTheme(themeKey: String?): Pair<String, Boolean> = when (themeKey) {
    "light" -> "L01" to false
    "dark_light_paper" -> "D01" to true
    else -> "D01" to false
}

private fun decodeRecentSearches(raw: String?): List<String> =
    raw?.split(RECENT_SEARCH_DELIMITER)?.filter { it.isNotBlank() } ?: emptyList()

private fun encodeRecentSearches(list: List<String>): String =
    list.joinToString(RECENT_SEARCH_DELIMITER)

/** App theme preference: follow the system, or force one. */
enum class ThemeMode(val key: String, val label: String) {
    SYSTEM("system", "System default"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark"),
    DARK_LIGHT_PAPER("dark_light_paper", "Dark · light paper");

    companion object {
        val DEFAULT = SYSTEM
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** Library rendering mode: the cover gallery grid (current look, default) or a denser list of rows. */
enum class LibraryView(val key: String, val label: String) {
    GALLERY("gallery", "Gallery"),
    LIST("list", "List");

    companion object {
        val DEFAULT = GALLERY
        fun fromKey(key: String?): LibraryView = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** Feature 18: a small fixed accent-color palette for tinting one notebook's library card/row —
 *  a subtle personalization, not a data field. NONE = no tint (default, unset). */
enum class NotebookAccent(val key: String, val argb: Long) {
    NONE("none", 0x00000000),
    CORAL("coral", 0xFFEF6461),
    AMBER("amber", 0xFFDCA54C),
    SAGE("sage", 0xFF6FA287),
    SKY("sky", 0xFF5B8DBE),
    VIOLET("violet", 0xFF8B6FB0),
    SLATE("slate", 0xFF6B7280);

    companion object {
        val DEFAULT = NONE
        fun fromKey(key: String?): NotebookAccent = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** The sync targets the export pipeline knows how to write to today (cloud pending — DESIGN.md). */
enum class SyncMethod(val key: String, val label: String) {
    LOCAL_FOLDER("local_folder", "Folder on this device"),
    TAILSCALE_PUSH("tailscale_push", "Direct push (Tailscale)"),
    LOCAL_ONLY("local_only", "Local only");

    companion object {
        val DEFAULT = LOCAL_FOLDER
        fun fromKey(key: String?): SyncMethod = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** User-selected transcription quality tier: which OCR engine is preferred. */
enum class TranscriptionQuality(val key: String, val label: String) {
    AUTO("auto", "Auto"),
    INSTANT("instant", "Instant"),
    ACCURATE("accurate", "Accurate");

    companion object {
        val DEFAULT = AUTO
        fun fromKey(key: String?): TranscriptionQuality =
            entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/**
 * Live-handwriting size preset (#15b): a stroke-width multiplier applied at the single width
 * source ([com.nibhaus.ui.common.strokeBaseWidthPx]) so it covers both live capture and in-app
 * page rendering — see that function's callers for the one hook point. Display-time, not stored
 * per-stroke (unlike [com.nibhaus.di.ServiceLocator.inkWidth]'s Fine/Medium/Large picker, which
 * stamps a permanent width onto each new stroke at ingest).
 */
enum class StrokeScale(val key: String, val label: String, val multiplier: Float) {
    FINE("fine", "Fine", 0.7f),
    NORMAL("normal", "Normal", 1f),
    BOLD("bold", "Bold", 1.4f);

    companion object {
        val DEFAULT = NORMAL
        fun fromKey(key: String?): StrokeScale = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/**
 * Runtime settings persisted with Jetpack DataStore (Preferences) per the brief — NOT
 * SharedPreferences. Changing a value takes effect on the next export (the worker reads it fresh),
 * no rebuild/restart. Only the keys Phase 3 needs live here; Phase 4 adds the OCR-trigger keys.
 */
class SettingsStore internal constructor(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.settingsDataStore)
    private val syncMethodKey = stringPreferencesKey("sync.method")
    private val localFolderUriKey = stringPreferencesKey("sync.local_folder.uri")
    private val backupFolderUriKey = stringPreferencesKey("backup.folder.uri")
    private val tailscaleEndpointKey = stringPreferencesKey("sync.tailscale.endpoint")
    private val syncTokenKey = stringPreferencesKey("sync.token")
    private val themeModeKey = stringPreferencesKey("ui.theme")
    private val rememberPasswordKey = booleanPreferencesKey("pen.remember_password")
    private val onDeviceOcrAckKey = booleanPreferencesKey("ocr.on_device.acknowledged")
    private val onDeviceOcrEnabledKey = booleanPreferencesKey("ocr.on_device.enabled")
    private val bgCaptureNudgeDismissedKey = booleanPreferencesKey("capture.bg_nudge_dismissed")
    private val appLockEnabledKey = booleanPreferencesKey("security.app_lock")
    private val premiumUnlockedKey = booleanPreferencesKey("premium.unlocked")
    private val attachAudioOnShareKey = booleanPreferencesKey("share.attach_audio")
    private val calendarTargetIdKey = longPreferencesKey("calendar.target_id")
    private val translateEndpointKey = stringPreferencesKey("translate.endpoint")
    private val translateModelKey = stringPreferencesKey("translate.model")
    private val byoOcrEndpointKey = stringPreferencesKey("ocr.byo_endpoint")
    private val byoOcrTokenKey = stringPreferencesKey("ocr.byo_token")
    private val vlmDisabledOnThisDeviceKey = booleanPreferencesKey("vlm.disabled_on_device")
    private val vlmAllowMeteredKey   = booleanPreferencesKey("vlm.allow_metered")
    private val vlmForceOnDeviceKey  = booleanPreferencesKey("vlm.force_on_device")
    private val ocrDisclaimerShownKey = booleanPreferencesKey("ocr.disclaimer_shown")
    private val transcriptionQualityKey = stringPreferencesKey("ocr.transcription_quality")
    private val recentSearchesKey = stringPreferencesKey("search.recent")
    private val libraryViewKey = stringPreferencesKey("ui.library_view")
    private val hideBlankPagesKey = booleanPreferencesKey("library.hide_blank_pages")
    private val bookmarkedPageIdsKey = stringSetPreferencesKey("pages.bookmarked")
    private val onboardingCoachDoneKey = booleanPreferencesKey("onboarding.coach_done")
    private val bleRationaleShownKey = booleanPreferencesKey("permissions.ble_rationale_shown")
    private val tipReplayDismissedKey = booleanPreferencesKey("tip.replay_dismissed")
    private val tipPrintedButtonsDismissedKey = booleanPreferencesKey("tip.printed_buttons_dismissed")
    private val tipTranscribeDismissedKey = booleanPreferencesKey("tip.transcribe_dismissed")
    private val everZoneTappedKey = booleanPreferencesKey("zones.ever_tapped")

    private val paletteKey = stringPreferencesKey("ui.palette")
    private val lightPaperKey = booleanPreferencesKey("ui.light_paper")

    /** Selected color palette id (e.g. "D01"/"L01"). Falls back to a migration of the legacy
     *  `ui.theme` value, lazily — no destructive rewrite of that key. See [paletteIdFromLegacyTheme]. */
    val paletteId: Flow<String> =
        store.data.map { it[paletteKey] ?: paletteIdFromLegacyTheme(it[themeModeKey]).first }
    suspend fun setPaletteId(id: String) = edit { it[paletteKey] = id }

    /** Whether the paper canvas renders light even under a dark palette. Falls back to the legacy
     *  `ui.theme` migration, lazily. See [paletteIdFromLegacyTheme]. */
    val lightPaper: Flow<Boolean> =
        store.data.map { it[lightPaperKey] ?: paletteIdFromLegacyTheme(it[themeModeKey]).second }
    suspend fun setLightPaper(on: Boolean) = edit { it[lightPaperKey] = on }

    private val paperTemplateKey = stringPreferencesKey("ui.paper")
    val paperTemplate: Flow<PaperTemplate> =
        store.data.map { PaperTemplate.fromKey(it[paperTemplateKey]) }
    suspend fun setPaperTemplate(t: PaperTemplate) = edit { it[paperTemplateKey] = t.key }

    /** Handwriting size preset (#15b) — Appearance tab. See [StrokeScale]. */
    private val strokeScaleKey = stringPreferencesKey("ink.stroke_scale")
    val strokeScale: Flow<StrokeScale> =
        store.data.map { StrokeScale.fromKey(it[strokeScaleKey]) }
    suspend fun setStrokeScale(s: StrokeScale) = edit { it[strokeScaleKey] = s.key }

    /** Library tab layout: cover gallery (default) or a denser list of notebook rows. */
    val libraryView: Flow<LibraryView> =
        store.data.map { LibraryView.fromKey(it[libraryViewKey]) }
    suspend fun setLibraryView(v: LibraryView) = edit { it[libraryViewKey] = v.key }

    /** Hide pages with no ink strokes in the open-notebook page navigator. Default off (show all). */
    val hideBlankPages: Flow<Boolean> =
        store.data.map { it[hideBlankPagesKey] ?: false }
    suspend fun setHideBlankPages(on: Boolean) = edit { it[hideBlankPagesKey] = on }

    /** Feature 15: page ids the user has starred, surfaced in the Favorites list. A DataStore string
     *  set — deliberately NOT a Room column, so bookmarking needs no schema/migration. */
    val bookmarkedPageIds: Flow<Set<String>> =
        store.data.map { it[bookmarkedPageIdsKey] ?: emptySet() }

    /** Star/un-star [pageId]. See [toggledSet] for the pure add/remove step. */
    suspend fun setBookmarked(pageId: String, on: Boolean) = edit {
        it[bookmarkedPageIdsKey] = toggledSet(it[bookmarkedPageIdsKey] ?: emptySet(), pageId, on)
    }

    /** Drop [ids] from the bookmarked set — called when their pages are deleted, so a deleted page's
     *  id doesn't linger forever in DataStore (favorites already filters ghost ids defensively, but
     *  this actually cleans up). No-op for any id that wasn't bookmarked. */
    suspend fun removeBookmarks(ids: Set<String>) = edit {
        val current = it[bookmarkedPageIdsKey] ?: emptySet()
        if (current.any { id -> id in ids }) it[bookmarkedPageIdsKey] = current - ids
    }

    /** First-run guided coach (3-step: connect pen / just write / tap the printed buttons). False
     *  until the user finishes it, taps Skip, or checks "Don't show this again" — any of the three
     *  writes true, and it's never shown again. */
    val onboardingCoachDone: Flow<Boolean> =
        store.data.map { it[onboardingCoachDoneKey] ?: false }
    suspend fun setOnboardingCoachDone() = edit { it[onboardingCoachDoneKey] = true }

    /** Whether the friendly "Nibhaus uses Bluetooth…" pre-prompt has already been shown, ahead of the
     *  system BLE permission dialogs. Shown once ever, regardless of whether permission is later
     *  granted or denied — this is an explanation, not a re-askable permission gate. */
    val bleRationaleShown: Flow<Boolean> =
        store.data.map { it[bleRationaleShownKey] ?: false }
    suspend fun setBleRationaleShown() = edit { it[bleRationaleShownKey] = true }

    /** "Did you know" tip cards (Feature 5): each dismissed independently and forever — see
     *  [replayTipEligible] / [printedButtonTipEligible] / [transcribeTipEligible] for when they show. */
    val tipReplayDismissed: Flow<Boolean> = store.data.map { it[tipReplayDismissedKey] ?: false }
    suspend fun dismissReplayTip() = edit { it[tipReplayDismissedKey] = true }

    val tipPrintedButtonsDismissed: Flow<Boolean> = store.data.map { it[tipPrintedButtonsDismissedKey] ?: false }
    suspend fun dismissPrintedButtonsTip() = edit { it[tipPrintedButtonsDismissedKey] = true }

    val tipTranscribeDismissed: Flow<Boolean> = store.data.map { it[tipTranscribeDismissedKey] ?: false }
    suspend fun dismissTranscribeTip() = edit { it[tipTranscribeDismissedKey] = true }

    /** Whether the user has ever tapped a printed Share/Email button with the pen — the signal
     *  [printedButtonTipEligible] uses to stop suggesting a feature they've already discovered. */
    val everZoneTapped: Flow<Boolean> = store.data.map { it[everZoneTappedKey] ?: false }
    suspend fun markZoneTapped() = edit { it[everZoneTappedKey] = true }

    /** Timestamp (epoch millis) of the last crash report the user has acknowledged — dismissed or
     *  opened for review — via the Pens-home crash card. 0L = never acknowledged. See
     *  [com.nibhaus.feedback.crashPromptEligible] for how the card decides whether to show. */
    private val feedbackCrashAckedKey = longPreferencesKey("feedback.crash_acked")
    val feedbackCrashAcked: Flow<Long> = store.data.map { it[feedbackCrashAckedKey] ?: 0L }
    suspend fun setFeedbackCrashAcked(timestampMillis: Long) = edit { it[feedbackCrashAckedKey] = timestampMillis }

    /** Whether to remember the pen unlock password (for ~30 days) vs. ask every connect. Default off. */
    val rememberPassword: Flow<Boolean> =
        store.data.map { it[rememberPasswordKey] ?: false }
    suspend fun setRememberPassword(on: Boolean) = edit { it[rememberPasswordKey] = on }

    /** Opt-in biometric/device-credential lock on app open + return-from-background (Section C1). Default off. */
    val appLockEnabled: Flow<Boolean> =
        store.data.map { it[appLockEnabledKey] ?: false }
    suspend fun setAppLockEnabled(on: Boolean) = edit { it[appLockEnabledKey] = on }

    /** Premium entitlement — the one-time unlock gating the "intelligence + native-sync" layer (OCR,
     *  handwriting search, translation, native tailnet sync). Default LOCKED; Play Billing / StoreKit
     *  flips it (a debug-only dev toggle stands in until billing lands). NOT touched by any tab reset. */
    val premiumUnlocked: Flow<Boolean> =
        store.data.map { it[premiumUnlockedKey] ?: false }
    suspend fun setPremiumUnlocked(on: Boolean) = edit { it[premiumUnlockedKey] = on }

    /** Opt-in: when sharing a page via its printed Share icon, also attach the page's voice note(s).
     *  Share only (never email — audio routinely exceeds email size limits). Default off. */
    val attachAudioOnShare: Flow<Boolean> =
        store.data.map { it[attachAudioOnShareKey] ?: false }
    suspend fun setAttachAudioOnShare(on: Boolean) = edit { it[attachAudioOnShareKey] = on }

    /**
     * Whether the user has acknowledged that on-device OCR (ML Kit Digital Ink) downloads a one-time
     * model from Google on first use — outbound to a target the user didn't select. We ask once via a
     * disclosure, then never again. Default off, so the first "Transcribe on device" prompts.
     */
    val onDeviceOcrAcknowledged: Flow<Boolean> =
        store.data.map { it[onDeviceOcrAckKey] ?: false }
    suspend fun acknowledgeOnDeviceOcr() = edit { it[onDeviceOcrAckKey] = true }

    /**
     * Master switch for on-device (ML Kit) transcription. Default ON — the per-use disclosure still
     * gates first use; turn OFF to keep transcription on the NAS/OCR host only.
     */
    val onDeviceOcrEnabled: Flow<Boolean> =
        store.data.map { it[onDeviceOcrEnabledKey] ?: true }
    suspend fun setOnDeviceOcrEnabled(on: Boolean) = edit { it[onDeviceOcrEnabledKey] = on }

    /** Re-arm the first-use on-device OCR disclosure so it prompts again. */
    suspend fun resetOnDeviceOcrDisclosure() = edit { it[onDeviceOcrAckKey] = false }

    /** Whether the first-connect "allow background capture" nudge has already been shown/dismissed. */
    val bgCaptureNudgeDismissed: Flow<Boolean> =
        store.data.map { it[bgCaptureNudgeDismissedKey] ?: false }
    suspend fun dismissBgCaptureNudge() = edit { it[bgCaptureNudgeDismissedKey] = true }

    /** The device calendar new events are added to (-1 = none chosen yet). */
    val calendarTargetId: Flow<Long> =
        store.data.map { it[calendarTargetIdKey] ?: -1L }
    suspend fun setCalendarTargetId(id: Long) = edit { it[calendarTargetIdKey] = id }

    val syncMethod: Flow<SyncMethod> =
        store.data.map { SyncMethod.fromKey(it[syncMethodKey]) }
    val localFolderUri: Flow<String> =
        store.data.map { it[localFolderUriKey] ?: "" }
    /** Dedicated, always-on "crash backup" folder for the at-ingest [SafetyBackup]. Empty = off. */
    val backupFolderUri: Flow<String> =
        store.data.map { it[backupFolderUriKey] ?: "" }
    val tailscaleEndpoint: Flow<String> =
        store.data.map { it[tailscaleEndpointKey] ?: "" }

    /** Bearer token for the NAS sync endpoint (PUT pages / GET transcripts). Blank = no auth header. */
    val syncToken: Flow<String> =
        store.data.map { it[syncTokenKey] ?: "" }
    suspend fun setSyncToken(token: String) = edit { it[syncTokenKey] = token }

    /**
     * High-quality translation engine: an OpenAI-compatible endpoint (Ollama/vLLM on the user's GPU
     * box) running a translation-grade LLM — one model covers every language, no per-language
     * downloads, and traffic stays on the user's own tailnet. Blank → fall back to on-device ML Kit.
     */
    val translateEndpoint: Flow<String> =
        store.data.map { it[translateEndpointKey] ?: "" }
    val translateModel: Flow<String> =
        store.data.map { it[translateModelKey] ?: "" }
    suspend fun setTranslateEndpoint(endpoint: String) = edit { it[translateEndpointKey] = endpoint }
    suspend fun setTranslateModel(model: String) = edit { it[translateModelKey] = model }

    /**
     * BYO OCR server: an OpenAI-compatible / VLM-serving endpoint on the user's own GPU box, wired
     * as the accurate-tier ServerInk engine (highest-quality transcription, stays on the user's own
     * tailnet). Blank → that tier is skipped (falls through to on-device VLM/ML Kit).
     */
    val byoOcrEndpoint: Flow<String> =
        store.data.map { it[byoOcrEndpointKey] ?: "" }
    val byoOcrToken: Flow<String> =
        store.data.map { it[byoOcrTokenKey] ?: "" }
    suspend fun setByoOcrEndpoint(endpoint: String) = edit { it[byoOcrEndpointKey] = endpoint }
    suspend fun setByoOcrToken(token: String) = edit { it[byoOcrTokenKey] = token }

    /** True once a first-run latency probe determined this device is too slow for on-device VLM.
     *  Set by [markVlmTooSlow] (wired from VlmInk.onTooSlow); cleared only by user override (Task 9.5). */
    val vlmDisabledOnThisDevice: Flow<Boolean> =
        store.data.map { it[vlmDisabledOnThisDeviceKey] ?: false }

    suspend fun setVlmDisabledOnThisDevice(disabled: Boolean) =
        edit { it[vlmDisabledOnThisDeviceKey] = disabled }

    /** Allow model weight downloads over metered (mobile data) connections. Default off. */
    val vlmAllowMetered: Flow<Boolean> =
        store.data.map { it[vlmAllowMeteredKey] ?: false }
    suspend fun setVlmAllowMetered(on: Boolean) = edit { it[vlmAllowMeteredKey] = on }

    /** User override: force-enable on-device VLM even if the RAM capability probe failed. Default off. */
    val vlmForceOnDevice: Flow<Boolean> =
        store.data.map { it[vlmForceOnDeviceKey] ?: false }
    suspend fun setVlmForceOnDevice(on: Boolean) = edit { it[vlmForceOnDeviceKey] = on }

    /** True once the accuracy disclaimer dialog has been shown and dismissed. Default false (show on first OCR). */
    val ocrDisclaimerShown: Flow<Boolean> =
        store.data.map { it[ocrDisclaimerShownKey] ?: false }
    suspend fun setOcrDisclaimerShown() = edit { it[ocrDisclaimerShownKey] = true }

    /** User's preferred OCR tier. Default AUTO. */
    val transcriptionQuality: Flow<TranscriptionQuality> =
        store.data.map { TranscriptionQuality.fromKey(it[transcriptionQualityKey]) }
    suspend fun setTranscriptionQuality(q: TranscriptionQuality) =
        edit { it[transcriptionQualityKey] = q.key }

    /** Recently-run search queries, most-recent-first, capped at [RECENT_SEARCHES_CAP]. Empty until
     *  the user searches. */
    val recentSearches: Flow<List<String>> =
        store.data.map { decodeRecentSearches(it[recentSearchesKey]) }

    /** Record a submitted search query — see [updatedRecentSearches] for the fold logic. */
    suspend fun addRecentSearch(query: String) = edit {
        val current = decodeRecentSearches(it[recentSearchesKey])
        it[recentSearchesKey] = encodeRecentSearches(updatedRecentSearches(current, query, cap = RECENT_SEARCHES_CAP))
    }

    suspend fun clearRecentSearches() = edit { it.remove(recentSearchesKey) }

    // Per-notebook-product type assignment (the new-notebook dialog). Keyed by Ncode book id, so the
    // user designates a product once and every physical copy of it auto-resolves afterward.
    private fun notebookTypeKey(book: Int) = stringPreferencesKey("notebook.type.$book")

    /** Persist the user's type choice for a book id. */
    suspend fun assignNotebookType(book: Int, typeId: String) =
        edit { it[notebookTypeKey(book)] = typeId }

    /** The user's assigned type id for a book id (null = not assigned), observed for the dialog. */
    fun assignedTypeId(book: Int): Flow<String?> =
        store.data.map { it[notebookTypeKey(book)] }

    /** Resolve a book id to its type at export time: user assignment wins, else the built-in default. */
    suspend fun notebookType(book: Int?): NotebookType? {
        book ?: return null
        val assignedId = store.data.first()[notebookTypeKey(book)]
        return NotebookType.resolve(assignedId, book)
    }

    // Feature 18: per-notebook accent color, keyed by the notebook's own uuid (its filing key) — each
    // physical copy gets its own tint, distinct from NotebookProfileStore's per-book-model geometry.
    private fun notebookAccentKey(notebookId: String) = stringPreferencesKey("notebook.accent.$notebookId")

    /** The user's chosen accent for a notebook (NONE = default/no tint), observed for the library cards. */
    fun notebookAccent(notebookId: String): Flow<NotebookAccent> =
        store.data.map { NotebookAccent.fromKey(it[notebookAccentKey(notebookId)]) }

    /** Persist the accent color for a notebook; NONE clears it back to the unset default. */
    suspend fun setNotebookAccent(notebookId: String, accent: NotebookAccent) = edit {
        if (accent == NotebookAccent.NONE) it.remove(notebookAccentKey(notebookId)) else it[notebookAccentKey(notebookId)] = accent.key
    }

    /** Drop a deleted notebook's accent key entirely — otherwise `notebook.accent.<id>` lingers in
     *  DataStore forever (a new notebook can never reuse that uuid, so it's pure orphan cleanup). */
    suspend fun removeAccent(notebookId: String) = edit { it.remove(notebookAccentKey(notebookId)) }

    // Notebooks the user has already been prompted to set up (type + label), so the dialog asks once.
    private val acknowledgedNotebooksKey = stringSetPreferencesKey("notebook.setup.done")

    val acknowledgedNotebooks: Flow<Set<String>> =
        store.data.map { it[acknowledgedNotebooksKey] ?: emptySet() }

    /** Mark a notebook as set up / dismissed, so the new-notebook dialog won't ask again. */
    suspend fun acknowledgeNotebook(id: String) = edit { prefs ->
        prefs[acknowledgedNotebooksKey] = (prefs[acknowledgedNotebooksKey] ?: emptySet()) + id
    }

    suspend fun setSyncMethod(method: SyncMethod) =
        edit { it[syncMethodKey] = method.key }
    suspend fun setLocalFolderUri(uri: String) =
        edit { it[localFolderUriKey] = uri }
    suspend fun setBackupFolderUri(uri: String) =
        edit { it[backupFolderUriKey] = uri }
    suspend fun setTailscaleEndpoint(endpoint: String) =
        edit { it[tailscaleEndpointKey] = endpoint }

    /** One-shot read for the WorkManager job, which needs the current target at export time. */
    suspend fun snapshot(): Triple<SyncMethod, String, String> {
        val prefs = store.data.first()
        return Triple(
            SyncMethod.fromKey(prefs[syncMethodKey]),
            prefs[localFolderUriKey] ?: "",
            prefs[tailscaleEndpointKey] ?: "",
        )
    }

    // --- "Reset to Default", per Settings tab. Removing a key makes its read fall back to the
    //     default, so each reset reverts exactly that tab's own settings and nothing else. ---
    suspend fun resetCaptureAndPen() = edit {
        it.remove(rememberPasswordKey); it.remove(bgCaptureNudgeDismissedKey)
    }
    suspend fun resetSyncAndOcr() = edit {
        it.remove(syncMethodKey); it.remove(localFolderUriKey); it.remove(tailscaleEndpointKey)
        it.remove(syncTokenKey); it.remove(onDeviceOcrEnabledKey); it.remove(onDeviceOcrAckKey)
        it.remove(translateEndpointKey); it.remove(translateModelKey)
        it.remove(byoOcrEndpointKey); it.remove(byoOcrTokenKey)
        it.remove(vlmDisabledOnThisDeviceKey)
        it.remove(vlmAllowMeteredKey); it.remove(vlmForceOnDeviceKey)
        it.remove(ocrDisclaimerShownKey); it.remove(transcriptionQualityKey)
    }
    suspend fun resetAppearance() = edit {
        it.remove(themeModeKey); it.remove(paletteKey); it.remove(lightPaperKey); it.remove(strokeScaleKey)
    }
    suspend fun resetPrivacy() = edit { it.remove(appLockEnabledKey) }
    suspend fun resetIntegrations() = edit { it.remove(calendarTargetIdKey) }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        store.edit(block)
    }
}
