package com.nibhaus

import com.google.common.truth.Truth.assertThat
import com.nibhaus.pen.SharedPenScanner
import org.junit.Test

/**
 * Pure ref-counting logic behind [SharedPenScanner] — the fix for live-test bug #2 ("first manual
 * scan comes up empty; Rescan finds the pen"). Root cause: [com.nibhaus.pen.PenScanner] is a bare
 * start()/stop() pair with no notion of ownership, shared by three independent callers (the Pens
 * home presence scan, the Find-a-pen screen, and ServiceLocator.scanForSavedPen's reconnect scan) —
 * whoever calls stop() kills whichever scan happens to be running, and start() unconditionally wipes
 * accumulated results. Opening Find-a-pen while the presence scan was active (or mid-teardown during
 * the screen-transition animation) raced their start()/stop() calls: the presence effect's delayed
 * onDispose could stop() the scan Find-a-pen had JUST started, so the first window saw nothing —
 * "Rescan" worked only because by then the presence effect had fully disposed and stopped contending.
 */
class SharedPenScannerTest {

    private fun facade(onStart: () -> Unit = {}, onStop: () -> Unit = {}) =
        SharedPenScanner(startReal = onStart, stopReal = onStop)

    @Test fun `the first client's start invokes the real start`() {
        var starts = 0
        val s = facade(onStart = { starts++ })
        s.start("presence")
        assertThat(starts).isEqualTo(1)
    }

    @Test fun `a second client's start while the first is still active does not restart the real scanner`() {
        var starts = 0
        val s = facade(onStart = { starts++ })
        s.start("presence")
        s.start("scanScreen")
        assertThat(starts).isEqualTo(1) // not restarted — results keep flowing to both, uninterrupted
    }

    @Test fun `one client stopping while another is still active does not stop the real scanner`() {
        var stops = 0
        val s = facade(onStop = { stops++ })
        s.start("presence")
        s.start("scanScreen")

        s.stop("presence") // this is the exact race: presence's teardown must not kill scanScreen's scan

        assertThat(stops).isEqualTo(0)
    }

    @Test fun `the last client's stop invokes the real stop`() {
        var stops = 0
        val s = facade(onStop = { stops++ })
        s.start("presence")
        s.start("scanScreen")

        s.stop("presence")
        s.stop("scanScreen")

        assertThat(stops).isEqualTo(1)
    }

    @Test fun `stopping a client that was never started is a no-op - never calls real stop with zero clients`() {
        var stops = 0
        val s = facade(onStop = { stops++ })

        s.stop("nobody")

        assertThat(stops).isEqualTo(0)
    }

    @Test fun `the same token starting twice needs two matching stops to release it (reentrant safety)`() {
        var starts = 0; var stops = 0
        val s = facade(onStart = { starts++ }, onStop = { stops++ })
        val token = "connectSaved"
        s.start(token) // e.g. connectSaved()'s own scan
        s.start(token) // a concurrent rescan invocation reusing the same client identity

        s.stop(token) // the first invocation finishing must not stop the second's still-active scan
        assertThat(stops).isEqualTo(0)

        s.stop(token)
        assertThat(stops).isEqualTo(1)
        assertThat(starts).isEqualTo(1) // only ever one real start across the whole overlap
    }

    @Test fun `after every client releases, a fresh start begins a new real session`() {
        var starts = 0
        val s = facade(onStart = { starts++ })
        s.start("presence")
        s.stop("presence")

        s.start("presence")

        assertThat(starts).isEqualTo(2)
    }
}
