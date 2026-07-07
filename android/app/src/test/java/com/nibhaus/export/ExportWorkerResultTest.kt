package com.nibhaus.export

import androidx.work.ListenableWorker.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Final-review fix (2026-07-05): a null/unresolvable storage provider (no folder chosen yet, or a
 * Tailscale endpoint that entitlement now blocks) must never report success, since that leaves the
 * outbox silently queued while WorkManager thinks the drain is done. [exportWorkResult] is the pure
 * decision [ExportWorker.doWork] delegates to, so this is testable without constructing a real
 * CoroutineWorker (which needs Android's WorkerParameters machinery, not available in a plain JVM
 * unit test).
 */
class ExportWorkerResultTest {

    @Test fun `no provider available is never reported as success`() {
        val result = exportWorkResult(providerAvailable = false, exported = false, deleted = false)
        assertNotEquals(Result.success(), result)
    }

    @Test fun `no provider available retries instead`() {
        assertEquals(Result.retry(), exportWorkResult(providerAvailable = false, exported = false, deleted = false))
    }

    @Test fun `provider available and both drains succeed reports success`() {
        assertEquals(Result.success(), exportWorkResult(providerAvailable = true, exported = true, deleted = true))
    }

    @Test fun `provider available but export failed retries`() {
        assertEquals(Result.retry(), exportWorkResult(providerAvailable = true, exported = false, deleted = true))
    }

    @Test fun `provider available but delete-queue drain failed retries`() {
        assertEquals(Result.retry(), exportWorkResult(providerAvailable = true, exported = true, deleted = false))
    }
}
