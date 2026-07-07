package com.nibhaus.pen

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live signals about the ink stream itself, fed from the dot pipeline. Used by the connection
 * diagnostic ("am I receiving ink?", "is the paper recognized?") and to detect a dot-stall while
 * the pen is down. Pure (no Android), so it's unit-testable and lives at the [com.nibhaus.pen]
 * boundary alongside the rest of the pen model.
 */
class CaptureSignals(private val now: () -> Long = System::currentTimeMillis) {
    private val _lastDotAt = MutableStateFlow(0L)
    val lastDotAt: StateFlow<Long> = _lastDotAt.asStateFlow()

    private val _lastAddressValid = MutableStateFlow(false)
    val lastAddressValid: StateFlow<Boolean> = _lastAddressValid.asStateFlow()

    /** True while the pen tip is down (between DOWN and UP) — a stall here is suspicious. */
    private val _penDown = MutableStateFlow(false)
    val penDown: StateFlow<Boolean> = _penDown.asStateFlow()

    /** Any dot received since the current connection began — "is ink working?" for the diagnostic. */
    private val _receivedSinceConnect = MutableStateFlow(false)
    val receivedSinceConnect: StateFlow<Boolean> = _receivedSinceConnect.asStateFlow()

    fun onDot(dot: PenDot) {
        _lastDotAt.value = now()
        _lastAddressValid.value = dot.address.isValid
        _receivedSinceConnect.value = true
        when (dot.phase) {
            PenDot.Phase.DOWN -> _penDown.value = true
            PenDot.Phase.UP -> _penDown.value = false
            PenDot.Phase.MOVE -> Unit
        }
    }

    /** Call on each fresh connection so "received ink" reflects this session, not a stale one. */
    fun onConnected() {
        _receivedSinceConnect.value = false
        _lastAddressValid.value = false
    }

    /** A dot arrived within [windowMs] — i.e. ink is actively flowing. */
    fun recentlyReceiving(windowMs: Long = 3_000): Boolean {
        val t = _lastDotAt.value
        return t > 0 && now() - t < windowMs
    }

    /**
     * Pen tip is down (being written with) but no dot has arrived within [thresholdMs] — the ink
     * stream has silently stalled mid-stroke. Distinct from idle, which is pen-up ([penDown] false).
     * The pure decision behind the foreground service's silent-capture watchdog.
     */
    fun isStalled(thresholdMs: Long = 4_000): Boolean {
        val t = _lastDotAt.value
        return _penDown.value && t > 0 && now() - t >= thresholdMs
    }
}
