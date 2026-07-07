package com.nibhaus.premiumapi

import com.nibhaus.export.StorageProvider
import com.nibhaus.ocr.InkOcr
import com.nibhaus.ocr.TranscriptSource
import com.nibhaus.translate.InkTranslator
import kotlinx.coroutines.flow.Flow

/** A single pre-decoded ink sample (x, y in capture coords; t = epoch millis). Native type so the
 *  OCR contract carries no :app entity across the module boundary. */
data class InkPt(val x: Float, val y: Float, val t: Long)

/**
 * Download/readiness state of the on-device VLM model weights. Mirrors VlmModelManager.DownloadState
 * (in :premium) but lives here so :app can observe without a compile-time :premium dependency.
 * pct == -1 in Downloading means total size is unknown (show indeterminate indicator).
 */
sealed class VlmDownloadState {
    object Idle : VlmDownloadState()
    data class Downloading(val pct: Int) : VlmDownloadState()
    object Ready : VlmDownloadState()
    data class Failed(val reason: String) : VlmDownloadState()
}

/**
 * The premium feature bundle. :app's ServiceLocator resolves the impl
 * (`com.nibhaus.premium.PremiumServicesImpl`) reflectively — present ⇒ full suite, absent ⇒ freemium
 * (every accessor unused). Mirrors the pen-driver strangler seam (loadRealPenSdk → :neosdk).
 */
interface PremiumServices {
    /** The ACCURATE OCR engines this bundle contributes, ordered best-first: ServerInk when a BYO
     *  endpoint is configured, then VlmInk when the device is capable or the user forced it. The
     *  instant tier (ML Kit OnDeviceInk) lives in :app and is free; :app composes
     *  RoutedInk(instant, accurateChain()) itself. Entitlement is enforced at :app call sites. */
    fun accurateChain(): List<InkOcr>
    fun translator(endpoint: suspend () -> String, model: suspend () -> String): InkTranslator
    fun pushProvider(endpoint: String, token: String): StorageProvider
    fun transcriptSource(endpoint: String, token: String): TranscriptSource
    /** Download/readiness state of the on-device VLM model. Nullable so a freemium-safe caller can
     *  hold this behind `premium?.` without a compile-time :premium dependency; a real premium
     *  implementation is expected to return a flow independent of whether [accurateChain] has run. */
    fun vlmModelState(): Flow<VlmDownloadState>?

    /** Release heavy caches (the on-device VLM native context's non-evictable KV/compute buffers)
     *  under memory pressure. Called from NibhausApp.onTrimMemory. No-op by default. */
    fun onTrimMemory() {}
}
