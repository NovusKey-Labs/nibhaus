package com.nibhaus.pen

/**
 * Pure crossing/re-arm decision behind the low-battery notification (UX item #10). Stateful but
 * side-effect-free — [PenConnectionManager] feeds it every battery reading and turns a `true` result
 * into a one-shot [PenConnectionManager.lowBatteryAlert] emission; the host (PenForegroundService)
 * just posts the notification through the existing plumbing.
 *
 * Fires once per connection session the first time battery drops to/below [lowPct] while NOT
 * charging. Re-arms — so the next drop below [lowPct] fires again — when the battery recovers above
 * [clearPct], starts charging, or a new connection session begins ([reset]).
 */
class LowBatteryGate(
    private val lowPct: Int = 15,
    private val clearPct: Int = 20,
) {
    private var warned = false

    /** Feed a fresh battery reading. Returns true exactly once per arm-cycle, on the crossing. */
    fun onBattery(percent: Int, isCharging: Boolean): Boolean {
        if (isCharging || percent > clearPct) {
            warned = false
            return false
        }
        if (percent <= lowPct && !warned) {
            warned = true
            return true
        }
        return false
    }

    /** Re-arm for a new connection session (call on reconnect / a fresh connect). */
    fun reset() { warned = false }
}
