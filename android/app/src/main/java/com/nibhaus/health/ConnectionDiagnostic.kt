package com.nibhaus.health

/**
 * The pen-connection chain as a pure, testable model (Phase A — "Check connection"). Each step is
 * evaluated against a captured [Probe]; the UI gathers the probe from Android + app state and renders
 * the result, leading the user to the FIRST failing step and its fix. This codifies the hard-won BLE
 * bring-up knowledge (BLE permissions, V5 connect, password auth, dot flow, Ncode page resolution)
 * into one button so a stuck connection always has a next action.
 */
object ConnectionDiagnostic {

    enum class Status { PASS, FAIL, SKIPPED }

    data class Step(val name: String, val status: Status, val detail: String)

    /** A snapshot of everything the chain needs, gathered by the caller (no Android types here). */
    data class Probe(
        val bluetoothOn: Boolean,
        val scanPermission: Boolean,
        val connectPermission: Boolean,
        val penPoweredOrConnecting: Boolean, // saw the pen advertising, connecting, or connected
        val gattConnected: Boolean,          // PenConnState.Connected reached
        val authorized: Boolean,             // past PASSWORD_REQUIRED / authorized
        val receivingDots: Boolean,          // a dot arrived within the recent window
        val paperRecognized: Boolean,        // last dot resolved to a valid Ncode (section,owner,note,page)
    )

    /**
     * Walk the chain in order. The first failing step is FAIL with its fix; everything after it is
     * SKIPPED (we can't know downstream state until the blocker clears). Steps before pass.
     */
    fun run(p: Probe): List<Step> {
        val checks = listOf(
            Triple("Bluetooth on", p.bluetoothOn, "Turn on Bluetooth in system settings."),
            Triple("Permissions granted", p.scanPermission && p.connectPermission,
                "Grant Nearby devices (BLUETOOTH_SCAN/CONNECT). On Android 11 and below, grant Location too."),
            Triple("Pen powered & in range", p.penPoweredOrConnecting,
                "Press the pen tip to wake it and hold it within a metre of the device."),
            Triple("Bluetooth link (GATT)", p.gattConnected,
                "Keep the pen close while it connects; if it's paired to another device, use Take over."),
            Triple("Authorized", p.authorized, "Enter the pen password when prompted."),
            Triple("Receiving ink", p.receivingDots, "Write on paper made for this pen. If nothing arrives, reconnect."),
            Triple("Writing on the right paper", p.paperRecognized,
                "Make sure you are writing on paper made for this pen, like your Neo notebook. Plain paper will not register."),
        )
        var blocked = false
        return checks.map { (name, ok, fix) ->
            when {
                blocked -> Step(name, Status.SKIPPED, "Resolve the step above first.")
                ok -> Step(name, Status.PASS, "OK")
                else -> { blocked = true; Step(name, Status.FAIL, fix) }
            }
        }
    }

    /** The first failing step, if any — what the user should fix next. */
    fun firstFailure(steps: List<Step>): Step? = steps.firstOrNull { it.status == Status.FAIL }
}
