package com.nibhaus

import android.app.Application
import android.content.ComponentCallbacks2
import com.nibhaus.di.ServiceLocator
import com.nibhaus.feedback.CrashCapture

class NibhausApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // User-initiated feedback mechanism, part 2: capture a crash report file. Installed first,
        // before anything else, so a crash during startup itself is still captured. Always
        // delegates to whatever handler was previously installed (system crash handling unchanged).
        CrashCapture.install(this)
        // Crash recovery + auto-reconnect to the last pen.
        ServiceLocator.from(this).onStartup()
        // Recurring pull of watcher-written transcripts back into the searchable DB.
        com.nibhaus.ocr.SyncPullWorker.schedule(this)
    }

    /**
     * Free the on-device VLM native context under memory pressure or when backgrounded. The GGUF
     * weights are mmap'd (evictable), but the KV cache and compute buffers are not — releasing them
     * is the biggest reclaim we can offer the system. VlmInk self-heals (re-inits on the next OCR).
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (shouldReleaseVlmMemory(level)) ServiceLocator.from(this).onTrimMemory()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        ServiceLocator.from(this).onTrimMemory()
    }
}

/**
 * Release the heavy VLM native buffers at [ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL] and
 * above — which also covers every background-trim level (UI_HIDDEN/BACKGROUND/MODERATE/COMPLETE, all
 * ≥ 15). We deliberately do NOT release on the milder RUNNING_MODERATE/RUNNING_LOW, so a brief dip in
 * available memory while the user is actively working doesn't throw away a warm context mid-session.
 * Pure so the threshold is unit-testable without an Android context.
 */
fun shouldReleaseVlmMemory(level: Int): Boolean =
    level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
