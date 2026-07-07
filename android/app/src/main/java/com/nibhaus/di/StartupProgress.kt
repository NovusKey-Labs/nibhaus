package com.nibhaus.di

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Real cold-start milestones for the welcome splash's progress bar — ticked from actual init
 * points, not a timer. A process-lifetime singleton (mirrors [com.nibhaus.ui.NibSplashState]):
 * every startup gets one shared counter running 0..4.
 *
 * The four milestones and where each is actually marked:
 *  1. "vault systems"     — Room DB opened (synchronous, [ServiceLocator]'s constructor) +
 *                            [com.nibhaus.ingest.StrokeIngestor.recover] completed. Marked from
 *                            [ServiceLocator.onStartup] (called by `NibhausApp.onCreate`).
 *  2. "connected pens"    — pen driver resolved (synchronous, [ServiceLocator]'s constructor) +
 *                            `PenForegroundService` started. Marked from `MainActivity`'s
 *                            `startPenService()` (reached via `ensurePermissionsThenStartService`,
 *                            either immediately or after the permission-result callback).
 *  3. "live ink canvas"   — approximated: there's no single "capture pipeline is live" signal, so
 *                            this is marked at [ServiceLocator] construction complete, which is
 *                            when the ingestor/organizer/capture wiring all exist. In practice this
 *                            fires *before* 1 and 2 (it happens synchronously in
 *                            `Application.onCreate`, before the Activity/permissions/DB-recovery
 *                            work) — see [markMilestone]'s doc for why that's fine.
 *  4. "workspace handoff" — `InkViewModel.paletteLoaded` flips true (DataStore's first read). The
 *                            premium seam ([ServiceLocator]'s `premium` field) resolves
 *                            synchronously in the constructor, i.e. always before this — so it
 *                            never gates anything on its own; this milestone is effectively just
 *                            "the palette is known."
 */
object StartupProgress {

    /** Status-line text, in reading order — index `i` is what's shown while `i` of the 4
     *  milestones are complete (see [milestoneCount]'s doc for why this is by *count*, not by
     *  "which milestone number," since 3 can legitimately land before 1 and 2). */
    val phaseLabels: List<String> = listOf(
        "Initializing vault systems",
        "Syncing connected pens",
        "Preparing live ink canvas",
        "Securing workspace handoff",
    )

    private val completed = HashSet<Int>()

    private val _milestoneCount = MutableStateFlow(0)

    /** 0..4 — how many of the 4 milestones have reported complete so far. Monotonic: this counts
     *  *distinct* milestone numbers seen, not the highest one seen, so it never decreases and is
     *  safe to drive an `animateFloatAsState` target directly. That distinction matters because the
     *  milestones don't necessarily land in numeric order (see milestone 3's doc above) — counting
     *  the highest-seen number would let an early "3" overstate progress by implying 1 and 2 were
     *  already done. Counting distinct arrivals stays honest regardless of order. */
    val milestoneCount: StateFlow<Int> = _milestoneCount

    /** Mark milestone [n] (1..4) complete; out-of-range or repeat calls are harmless no-ops. */
    @Synchronized
    fun markMilestone(n: Int) {
        if (n !in 1..4) return
        if (completed.add(n)) _milestoneCount.value = completed.size
    }

    /** Test-only: reset the process-lifetime state so each test starts clean. */
    @Synchronized
    fun resetForTest() {
        completed.clear()
        _milestoneCount.value = 0
    }
}
