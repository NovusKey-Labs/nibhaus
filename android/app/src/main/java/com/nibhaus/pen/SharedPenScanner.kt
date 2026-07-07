package com.nibhaus.pen

/**
 * Ref-counted facade over a single shared BLE scanner so multiple logical clients — the Pens home
 * presence scan ([com.nibhaus.ui.InkViewModel.startPresenceScan]), the Find-a-pen screen
 * ([com.nibhaus.ui.InkViewModel.startScan]), and [com.nibhaus.di.ServiceLocator.scanForSavedPen]'s
 * saved-pen reconnect scan — can all want "the scanner running" at once without one client's [stop]
 * killing another's still-active scan.
 *
 * THE BUG this fixes (live-test bug #2, "first manual scan comes up empty; Rescan finds the pen"):
 * [PenScanner] is a bare start()/stop() pair with no concept of ownership — whoever calls stop()
 * kills whatever scan is currently running, and start() unconditionally wipes accumulated results
 * and restarts. Opening the Find-a-pen screen while the Pens home presence scan was active (or
 * mid-teardown — e.g. during the screen-transition animation, since `AnimatedContent` keeps the
 * outgoing screen composed, and its `DisposableEffect`, until the animation finishes) raced their
 * two start()/stop() calls: the presence effect's delayed `onDispose` could `stop()` the scan
 * Find-a-pen had JUST started, killing it before it accumulated any results. "Rescan" worked only
 * because by then the presence effect had fully disposed and stopped contending.
 *
 * [start] is a no-op on the underlying scanner once ANY client already holds it — the real scan
 * keeps running uninterrupted and results keep flowing to everyone; [stop] only tears the real scan
 * down once the LAST client releases it. A client identity is any stable token (equality-comparable
 * — an object reference or a string constant both work); the same token can be started more than
 * once (e.g. two overlapping invocations of [com.nibhaus.di.ServiceLocator.scanForSavedPen]) — each
 * [start] must be balanced by exactly one matching [stop] to fully release that registration.
 */
class SharedPenScanner(
    private val startReal: () -> Unit,
    private val stopReal: () -> Unit,
) {
    // A list (not a Set) so the SAME token can register more than once — e.g. two overlapping
    // scanForSavedPen calls sharing one client identity — and each needs its own matching stop().
    private val clients = mutableListOf<Any>()

    @Synchronized
    fun start(client: Any) {
        val wasIdle = clients.isEmpty()
        clients += client
        if (wasIdle) startReal()
    }

    @Synchronized
    fun stop(client: Any) {
        // remove() only removes (at most) ONE registration, balancing one start() — and only tears
        // down the real scan if it actually released a registration AND that was the last one; an
        // unbalanced/unknown-token stop() (e.g. a duplicate onDispose) must never call stopReal() on
        // an already-idle facade.
        if (clients.remove(client) && clients.isEmpty()) stopReal()
    }
}
