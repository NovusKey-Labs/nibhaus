package com.nibhaus.pen

/**
 * Feature 2 refinement — the "READY" live dot on the Pens home saved-pen tiles: pure sightings+clock
 * mapping, unit-testable without a real BLE scanner or wall clock (see [InkViewModel.readyPens] for
 * the reactive wiring that maintains [sightings] from [PenScanner.results]).
 *
 * [sightings] is spp → the wall-clock ms it was last seen in a scan tick. A spp reads READY as long
 * as it was sighted within [holdMs] of [now]; BLE advertising has gaps between packets, so treating a
 * pen as gone the instant a single scan tick misses it would flicker a tile's dot on and off. Per-spp,
 * so two saved pens with only one currently advertising correctly show only that one as ready.
 *
 * Callers MUST invoke this on a clock tick independent of new sightings arriving (see
 * `com.nibhaus.ui.InkViewModel.readyPens`'s 1s ticker) — otherwise a spp that stops being sighted
 * never re-evaluates past its hold window and reads READY forever. The clock-advance tests below
 * exercise exactly that contract.
 */
fun readySpps(sightings: Map<String, Long>, now: Long, holdMs: Long = 10_000L): Set<String> =
    sightings.filterValues { seenAt -> now - seenAt <= holdMs }.keys

/**
 * Folds one scan tick's sighted spp addresses into the running [sightings] map, stamping each with
 * [now]; spp not in [seenNow] keep their prior timestamp (an empty tick — e.g. a missed advertising
 * packet — must not erase a very recent sighting). Pure — the caller re-invokes this on every
 * [PenScanner.results] emission and keeps the returned map as its running state.
 */
fun recordSightings(sightings: Map<String, Long>, seenNow: Collection<String>, now: Long): Map<String, Long> =
    sightings + seenNow.associateWith { now }
