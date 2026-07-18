package com.nibhaus.ocr

import com.nibhaus.premiumapi.InkPt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class Fake(val out: String?, val boom: Boolean = false) : InkOcr {
    override suspend fun transcribe(strokes: List<List<InkPt>>, accurate: Boolean): String? {
        if (boom) error("engine down"); return out
    }
}
private val S = listOf(listOf(InkPt(0f, 0f, 0L)))

class RoutedInkTest {
    @Test fun instant_pathUsesTier0() = runBlocking {
        val r = RoutedInk(instant = Fake("t0"), accurateChain = { listOf(Fake("t2"), Fake("t1")) })
        assertEquals("t0", r.transcribe(S, accurate = false))
    }
    @Test fun accurate_prefersFirstInChain() = runBlocking {
        val r = RoutedInk(instant = Fake("t0"), accurateChain = { listOf(Fake("t2"), Fake("t1")) })
        assertEquals("t2", r.transcribe(S, accurate = true))
    }
    @Test fun accurate_fallsThroughOnThrowOrNull() = runBlocking {
        val r = RoutedInk(instant = Fake("t0"), accurateChain = { listOf(Fake(null, boom = true), Fake(null), Fake("t1")) })
        assertEquals("t1", r.transcribe(S, accurate = true))
    }
    @Test fun accurate_fallsBackToInstantWhenChainEmptyOrAllFail() = runBlocking {
        val r = RoutedInk(instant = Fake("t0"), accurateChain = { listOf(Fake(null, boom = true)) })
        assertEquals("t0", r.transcribe(S, accurate = true))
    }

    @Test fun accurate_skipsBlankChainResults() = runBlocking {
        val r = RoutedInk(instant = Fake("t0"), accurateChain = { listOf(Fake(""), Fake("   "), Fake("t1")) })
        assertEquals("t1", r.transcribe(S, accurate = true))
    }

    @Test fun accurate_fallsBackToInstantWhenChainEmpty() = runBlocking {
        val r = RoutedInk(instant = Fake("t0"), accurateChain = { emptyList() })
        assertEquals("t0", r.transcribe(S, accurate = true))
    }

    // -----------------------------------------------------------------------
    // E: CancellationException propagates through RoutedInk — not swallowed
    // -----------------------------------------------------------------------

    @Test fun cancellationException_propagatesFromAccurateChain() = runBlocking {
        val cancelling = object : InkOcr {
            override suspend fun transcribe(strokes: List<List<InkPt>>, accurate: Boolean): String? =
                throw CancellationException("test cancel")
        }
        val r = RoutedInk(instant = Fake("t0"), accurateChain = { listOf(cancelling) })
        var propagated = false
        try {
            r.transcribe(S, accurate = true)
        } catch (e: CancellationException) {
            propagated = true
        }
        assertTrue("CancellationException from accurate chain must propagate, not be swallowed", propagated)
    }

    @Test fun cancellationException_propagatesFromInstantTier() = runBlocking {
        val cancelling = object : InkOcr {
            override suspend fun transcribe(strokes: List<List<InkPt>>, accurate: Boolean): String? =
                throw CancellationException("test cancel")
        }
        val r = RoutedInk(instant = cancelling, accurateChain = { emptyList() })
        var propagated = false
        try {
            r.transcribe(S, accurate = false)
        } catch (e: CancellationException) {
            propagated = true
        }
        assertTrue("CancellationException from instant tier must propagate, not be swallowed", propagated)
    }

    // Ported from :premium's Task7SeamTest when RoutedInk moved to :app: the accurate flag must
    // thread from the call site into the accurate-chain engine.
    @Test fun accurate_flagReachesChainEngine() = runBlocking {
        var sawAccurate = false
        val chainEngine = object : InkOcr {
            override suspend fun transcribe(strokes: List<List<InkPt>>, accurate: Boolean): String? {
                sawAccurate = accurate
                return "x"
            }
        }
        val r = RoutedInk(instant = Fake("t0"), accurateChain = { listOf(chainEngine) })
        r.transcribe(S, accurate = true)
        assertTrue(sawAccurate)
    }

    // -----------------------------------------------------------------------
    // the accurate chain must be a SUPPLIER resolved fresh on every
    // accurate request, not a list frozen at construction. Otherwise an entitled user who configures
    // a BYO endpoint (or forces on-device VLM) after the chain was first built gets nothing until an
    // app restart. This is what makes ServiceLocator's `{ premium?.accurateChain() ?: emptyList() }`
    // wiring actually re-read the premium module's mirrors on every call.
    // -----------------------------------------------------------------------

    @Test fun accurate_resolvesTheChainSupplierFreshOnEveryCall() = runBlocking {
        var chainConfigured = false
        val r = RoutedInk(
            instant = Fake("t0"),
            accurateChain = { if (chainConfigured) listOf(Fake("byo")) else emptyList() },
        )
        // Before the BYO endpoint is configured: chain is empty, falls back to instant.
        assertEquals("t0", r.transcribe(S, accurate = true))

        // Configure the endpoint "at runtime": no new RoutedInk is constructed.
        chainConfigured = true

        // Same RoutedInk instance now sees the new engine, without any rewiring.
        assertEquals("byo", r.transcribe(S, accurate = true))
    }
}
