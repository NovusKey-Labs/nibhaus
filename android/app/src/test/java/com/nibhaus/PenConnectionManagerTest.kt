package com.nibhaus

import com.google.common.truth.Truth.assertThat
import com.nibhaus.pen.FakeNeoPenSdk
import com.nibhaus.pen.InMemoryPenPrefs
import com.nibhaus.pen.FirmwareUpdateState
import com.nibhaus.pen.PasswordOpState
import com.nibhaus.pen.PenConnState
import com.nibhaus.pen.PenConnectionManager
import com.nibhaus.pen.PenMessage
import com.nibhaus.pen.PenProtocol
import com.nibhaus.pen.PenTarget
import com.nibhaus.pen.SavedPen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Tests for the reconnect state machine. */
@OptIn(ExperimentalCoroutinesApi::class)
class PenConnectionManagerTest {

    private fun manager(
        sdk: FakeNeoPenSdk,
        prefs: InMemoryPenPrefs,
        scope: CoroutineScope,
    ) = PenConnectionManager(
        sdk = sdk, prefs = prefs, scope = scope, onDot = {},
        backoffSchedule = listOf(1_000, 2_000, 4_000),
    )

    @Test
    fun `connect succeeds and remembers the pen`() = runTest(UnconfinedTestDispatcher()) {
        val sdk = FakeNeoPenSdk(); val prefs = InMemoryPenPrefs()
        val mgr = manager(sdk, prefs, backgroundScope)

        mgr.connect("AA:BB")

        assertThat(mgr.state.value).isInstanceOf(PenConnState.Connected::class.java)
        assertThat(prefs.lastPenMac).isEqualTo("AA:BB")
    }

    @Test
    fun `a pen bonded to another device surfaces an actionable takeover state`() = runTest(UnconfinedTestDispatcher()) {
        val sdk = FakeNeoPenSdk().apply { connectShouldFailWith = PenMessage.FailureReason.BONDED_ELSEWHERE }
        val prefs = InMemoryPenPrefs("AA:BB")
        val mgr = manager(sdk, prefs, backgroundScope)

        mgr.connect("AA:BB")
        assertThat(mgr.state.value).isInstanceOf(PenConnState.BondedElsewhere::class.java)

        // Take over releases the bond and reconnects.
        mgr.takeOver("AA:BB")
        advanceUntilIdle()
        assertThat(mgr.state.value).isInstanceOf(PenConnState.Connected::class.java)
    }

    @Test
    fun `an unexpected drop auto-reconnects on its own`() = runTest(UnconfinedTestDispatcher()) {
        val sdk = FakeNeoPenSdk(); val prefs = InMemoryPenPrefs()
        val mgr = manager(sdk, prefs, backgroundScope)
        mgr.connect("AA:BB")

        sdk.emitDisconnected()                     // link lost while we believed we were connected
        assertThat(mgr.state.value).isInstanceOf(PenConnState.Reconnecting::class.java)

        // The reconnect loop runs in backgroundScope; advanceUntilIdle() does NOT service
        // background coroutines, so move the virtual clock to let the backoff delay elapse.
        advanceTimeBy(10_000)                       // backoff elapses → reconnect succeeds
        assertThat(mgr.state.value).isInstanceOf(PenConnState.Connected::class.java)
    }

    @Test
    fun `reconnect keeps retrying with backoff while the pen is unreachable`() = runTest(UnconfinedTestDispatcher()) {
        val sdk = FakeNeoPenSdk(); val prefs = InMemoryPenPrefs()
        val mgr = manager(sdk, prefs, backgroundScope)
        mgr.connect("AA:BB")                        // connectAttempts = 1
        val afterFirstConnect = sdk.connectAttempts

        sdk.connectShouldFailWith = PenMessage.FailureReason.TIMEOUT
        sdk.emitDisconnected()
        assertThat((mgr.state.value as PenConnState.Reconnecting).attempt).isEqualTo(1)

        advanceTimeBy(10_000)                        // several backoff cycles, all failing
        assertThat(sdk.connectAttempts).isGreaterThan(afterFirstConnect + 1) // retried multiple times

        mgr.disconnect()                             // stops the loop
        assertThat(mgr.state.value).isInstanceOf(PenConnState.Disconnected::class.java)
    }

    @Test
    fun `reconnect re-scans for the pen's current LE address when the cached one is stale`() =
        runTest(UnconfinedTestDispatcher()) {
            val sdk = FakeNeoPenSdk(); val prefs = InMemoryPenPrefs()
            // The pen power-cycles → its LE advertising address rotates, so the cached target is dead;
            // only a fresh scan finds the new address. The rescan hook stands in for that scan and is
            // the ONLY way to reach the pen on its new address.
            val fresh = PenTarget("9C:7B:D2:01", "RANDOM:NEW", PenProtocol.V2)
            val mgr = PenConnectionManager(
                sdk = sdk, prefs = prefs, scope = backgroundScope, onDot = {},
                backoffSchedule = listOf(1_000),
                rescan = { spp, window -> delay(window); if (spp == "9C:7B:D2:01") fresh else null },
            )
            mgr.connect(PenTarget("9C:7B:D2:01", "RANDOM:OLD", PenProtocol.V2)) // initial: old LE addr
            assertThat(sdk.lastConnectedTarget?.leAddress).isEqualTo("RANDOM:OLD")

            sdk.emitDisconnected()                       // pen powered off → drop → reconnect loop
            assertThat(mgr.state.value).isInstanceOf(PenConnState.Reconnecting::class.java)

            advanceTimeBy(5_000)                          // scan window elapses → reacquires fresh addr
            assertThat(mgr.state.value).isInstanceOf(PenConnState.Connected::class.java)
            // The reconnect dialed the NEW address from the rescan, not the dead cached one.
            assertThat(sdk.lastConnectedTarget?.leAddress).isEqualTo("RANDOM:NEW")
        }

    @Test
    fun `reconnect gives up after the attempt cap instead of looping forever`() =
        runTest(UnconfinedTestDispatcher()) {
            val sdk = FakeNeoPenSdk(); val prefs = InMemoryPenPrefs()
            val mgr = PenConnectionManager(
                sdk = sdk, prefs = prefs, scope = backgroundScope, onDot = {},
                backoffSchedule = listOf(1_000), maxReconnectAttempts = 3,
            )
            mgr.connect("AA:BB")                       // initial connect succeeds
            sdk.connectShouldFailWith = PenMessage.FailureReason.TIMEOUT
            sdk.emitDisconnected()                     // drop → reconnect loop, every attempt fails

            advanceTimeBy(60_000)

            assertThat(mgr.state.value).isInstanceOf(PenConnState.Disconnected::class.java)
            assertThat(sdk.connectAttempts).isEqualTo(1 + 3) // initial + exactly 3 capped retries
        }

    @Test
    fun `a locked pen surfaces a password prompt and unlocks when the password is submitted`() =
        runTest(UnconfinedTestDispatcher()) {
            val sdk = FakeNeoPenSdk(); val prefs = InMemoryPenPrefs()
            val mgr = manager(sdk, prefs, backgroundScope)
            mgr.connect("AA:BB")

            sdk.emitPasswordRequired()
            assertThat(mgr.state.value).isInstanceOf(PenConnState.PasswordRequired::class.java)

            mgr.submitPassword("1234")               // fake authorizes on any non-empty password
            assertThat(mgr.state.value).isInstanceOf(PenConnState.Connected::class.java)
        }

    @Test
    fun `an accepted typed password is reported once so the host can store it`() =
        runTest(UnconfinedTestDispatcher()) {
            val sdk = FakeNeoPenSdk(); val prefs = InMemoryPenPrefs()
            val accepted = mutableListOf<String>()
            val mgr = PenConnectionManager(
                sdk = sdk, prefs = prefs, scope = backgroundScope, onDot = {},
                onPasswordAccepted = { accepted += it },
            )
            mgr.connect("AA:BB")                       // initial connect (no password) must not store
            sdk.emitPasswordRequired()
            mgr.submitPassword("1234")                 // fake authorizes → Connected

            assertThat(accepted).containsExactly("1234")
        }

    @Test
    fun `a wrong password that gets re-prompted is never stored`() =
        runTest(UnconfinedTestDispatcher()) {
            val sdk = FakeNeoPenSdk(); val prefs = InMemoryPenPrefs()
            val accepted = mutableListOf<String>()
            val mgr = PenConnectionManager(
                sdk = sdk, prefs = prefs, scope = backgroundScope, onDot = {},
                onPasswordAccepted = { accepted += it },
            )
            mgr.connect("AA:BB")
            sdk.emitPasswordRequired()
            mgr.submitPassword("")                     // fake rejects empty → no Connected
            sdk.emitPasswordRequired()                 // pen re-asks: the attempt was wrong

            assertThat(accepted).isEmpty()
        }

    @Test
    fun `a wrong-password reply carries the remaining-attempts count into the UI state`() =
        runTest(UnconfinedTestDispatcher()) {
            val sdk = FakeNeoPenSdk(); val prefs = InMemoryPenPrefs()
            val mgr = manager(sdk, prefs, backgroundScope)
            mgr.connect("AA:BB")

            sdk.emitPasswordRequired() // initial lock prompt: not a failed attempt
            var state = mgr.state.value as PenConnState.PasswordRequired
            assertThat(state.wrongAttempt).isFalse()
            assertThat(state.attemptsRemaining).isNull()

            sdk.emitPasswordRequired(wrongAttempt = true, attemptsRemaining = 4)
            state = mgr.state.value as PenConnState.PasswordRequired
            assertThat(state.wrongAttempt).isTrue()
            assertThat(state.attemptsRemaining).isEqualTo(4)
        }

    @Test
    fun `changing the password reuses the session password and persists the new one`() =
        runTest(UnconfinedTestDispatcher()) {
            val sdk = FakeNeoPenSdk().apply { fakePassword = "1234" }
            val accepted = mutableListOf<String>()
            val mgr = PenConnectionManager(
                sdk = sdk, prefs = InMemoryPenPrefs(), scope = backgroundScope, onDot = {},
                onPasswordAccepted = { accepted += it }, onPasswordCleared = {},
            )
            mgr.connect(PenTarget("9C:7B:D2:01", "RANDOM:LE", PenProtocol.V2))
            mgr.submitPassword("1234") // unlock → the session password is now "1234"

            mgr.changePassword("5678") // reuses "1234" as the old field, no re-prompt

            assertThat(sdk.fakePassword).isEqualTo("5678")
            assertThat(accepted).contains("5678")
            assertThat(mgr.passwordOp.value)
                .isEqualTo(PasswordOpState.Done(success = true, disabled = false))
        }

    @Test
    fun `disabling the password clears the stored secret and reports success`() =
        runTest(UnconfinedTestDispatcher()) {
            val sdk = FakeNeoPenSdk().apply { fakePassword = "1234" }
            var cleared = false
            val mgr = PenConnectionManager(
                sdk = sdk, prefs = InMemoryPenPrefs(), scope = backgroundScope, onDot = {},
                onPasswordCleared = { cleared = true },
            )

            mgr.disablePassword("1234")

            assertThat(sdk.fakePassword).isNull()
            assertThat(cleared).isTrue()
            assertThat(mgr.passwordOp.value)
                .isEqualTo(PasswordOpState.Done(success = true, disabled = true))
        }

    @Test
    fun `a change without unlocking is rejected and stores nothing`() =
        runTest(UnconfinedTestDispatcher()) {
            val sdk = FakeNeoPenSdk().apply { fakePassword = "1234" }
            val accepted = mutableListOf<String>()
            val mgr = PenConnectionManager(
                sdk = sdk, prefs = InMemoryPenPrefs(), scope = backgroundScope, onDot = {},
                onPasswordAccepted = { accepted += it },
            )

            mgr.changePassword("5678") // no session → old defaults to "0000", which ≠ "1234"

            assertThat(sdk.fakePassword).isEqualTo("1234") // unchanged
            assertThat(accepted).isEmpty()
            assertThat(mgr.passwordOp.value)
                .isEqualTo(PasswordOpState.Done(success = false, disabled = false))
        }

    @Test
    fun `a successful connect saves the pen for one-tap reconnect later`() =
        runTest(UnconfinedTestDispatcher()) {
            val sdk = FakeNeoPenSdk(); val prefs = InMemoryPenPrefs()
            val mgr = PenConnectionManager(
                sdk = sdk, prefs = prefs, scope = backgroundScope, onDot = {}, now = { 42L },
            )

            mgr.connect(PenTarget("9C:7B:D2:01", "RANDOM:LE", PenProtocol.V2))

            assertThat(prefs.savedPens).containsExactly(
                SavedPen("Neo smartpen (fake)", "9C:7B:D2:01", 42L),
            )
            assertThat(mgr.savedPens.value).isEqualTo(prefs.savedPens)
        }

    @Test
    fun `reconnecting the same pen updates its saved entry instead of duplicating it`() =
        runTest(UnconfinedTestDispatcher()) {
            val sdk = FakeNeoPenSdk(); val prefs = InMemoryPenPrefs()
            val mgr = manager(sdk, prefs, backgroundScope)
            val target = PenTarget("9C:7B:D2:01", "RANDOM:LE", PenProtocol.V2)

            mgr.connect(target)
            mgr.disconnect()
            mgr.connect(target)

            assertThat(prefs.savedPens).hasSize(1)
            assertThat(mgr.savedPens.value).hasSize(1)
        }

    @Test
    fun `forgetPen drops the pen from savedPens and its live mirror`() =
        runTest(UnconfinedTestDispatcher()) {
            val sdk = FakeNeoPenSdk(); val prefs = InMemoryPenPrefs()
            val mgr = manager(sdk, prefs, backgroundScope)
            mgr.connect(PenTarget("9C:7B:D2:01", "RANDOM:LE", PenProtocol.V2))
            assertThat(mgr.savedPens.value).isNotEmpty()

            mgr.forgetPen("9C:7B:D2:01")

            assertThat(prefs.savedPens).isEmpty()
            assertThat(mgr.savedPens.value).isEmpty()
        }

    @Test
    fun `forgetPen currently leaves the persisted password and Keystore alias intact`() =
        runTest(UnconfinedTestDispatcher()) {
            val sdk = FakeNeoPenSdk(); val prefs = InMemoryPenPrefs()
            var secretClearCalls = 0
            val mgr = PenConnectionManager(
                sdk = sdk,
                prefs = prefs,
                scope = backgroundScope,
                onDot = {},
                onPasswordCleared = { secretClearCalls++ },
            )
            mgr.connect(PenTarget("9C:7B:D2:01", "RANDOM:LE", PenProtocol.V2))

            mgr.forgetPen("9C:7B:D2:01")

            // H2 audit gap characterization: Forget removes the saved-pen tile but does not invoke
            // SecretStore.clear(), so the stored password and its AndroidKeyStore alias survive.
            // This intentionally passes today and should fail when H2 is fixed later.
            assertThat(secretClearCalls).isEqualTo(0)
        }

    @Test
    fun `a failed connect on a never-paired pen never writes lastPenMac (no false saved-pen tile)`() =
        runTest(UnconfinedTestDispatcher()) {
            // Brand-new device: no prior lastPenMac, no savedPens — mirrors a fresh install that has
            // never successfully paired anything.
            val sdk = FakeNeoPenSdk().apply { connectShouldFailWith = PenMessage.FailureReason.TIMEOUT }
            val prefs = InMemoryPenPrefs()
            val mgr = manager(sdk, prefs, backgroundScope)

            mgr.connect("AA:BB") // the attempt fails immediately — never reaches Connected
            mgr.disconnect()     // stop the auto-reconnect loop this schedules

            assertThat(prefs.lastPenMac).isNull()
            assertThat(prefs.savedPens).isEmpty()
        }

    @Test
    fun `a bonded-elsewhere failure shows the attempted target's mac, not a stale persisted one`() =
        runTest(UnconfinedTestDispatcher()) {
            // prefs remembers a DIFFERENT, previously-paired pen; the user now attempts a NEW pen that
            // turns out to be bonded elsewhere — it never reaches Connected, so the surfaced mac must
            // come from the in-flight attempt (lastTarget), not a stale persisted value, and the
            // unconfirmed attempt must not overwrite the persisted proof-of-pairing either.
            val sdk = FakeNeoPenSdk().apply { connectShouldFailWith = PenMessage.FailureReason.BONDED_ELSEWHERE }
            val prefs = InMemoryPenPrefs(initial = "OLD:PEN")
            val mgr = manager(sdk, prefs, backgroundScope)

            mgr.connect("NEW:PEN")

            assertThat((mgr.state.value as PenConnState.BondedElsewhere).mac).isEqualTo("NEW:PEN")
            assertThat(prefs.lastPenMac).isEqualTo("OLD:PEN")
        }

    @Test
    fun `firmware version is exposed and a flash reports a result`() =
        runTest(UnconfinedTestDispatcher()) {
            val sdk = FakeNeoPenSdk()
            val mgr = manager(sdk, InMemoryPenPrefs(), backgroundScope)

            sdk.emitFirmwareVersion("2.18")
            assertThat(mgr.firmwareVersion.value).isEqualTo("2.18")

            val img = java.io.File.createTempFile("fwimg", ".bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            mgr.updateFirmware(img)
            assertThat(mgr.firmwareUpdate.value)
                .isEqualTo(FirmwareUpdateState.Done(success = true))
            img.delete()
        }

    // --- RSSI (#20b) ---

    @Test
    fun `rssi polling starts and stops on request, updates while active, and clears on stop`() =
        runTest(UnconfinedTestDispatcher()) {
            val sdk = FakeNeoPenSdk()
            val mgr = manager(sdk, InMemoryPenPrefs(), backgroundScope)
            assertThat(mgr.rssiDbm.value).isNull()

            mgr.startRssiPolling()
            assertThat(sdk.rssiPollingActive).isTrue()

            sdk.emitRssi(-58)
            assertThat(mgr.rssiDbm.value).isEqualTo(-58)

            mgr.stopRssiPolling()
            assertThat(sdk.rssiPollingActive).isFalse()
            assertThat(mgr.rssiDbm.value).isNull()
        }

    @Test
    fun `rssi clears when the pen disconnects`() = runTest(UnconfinedTestDispatcher()) {
        val sdk = FakeNeoPenSdk(); val prefs = InMemoryPenPrefs()
        val mgr = manager(sdk, prefs, backgroundScope)
        mgr.connect("AA:BB")
        mgr.startRssiPolling()
        sdk.emitRssi(-70)
        assertThat(mgr.rssiDbm.value).isEqualTo(-70)

        sdk.emitDisconnected()

        assertThat(mgr.rssiDbm.value).isNull()
    }

    // --- explicit disconnect suppresses ALL auto-reconnect (live-test bug #1: tap Disconnect
    // immediately reconnects) — link LOSS (unexpected drop) must keep auto-reconnecting unchanged;
    // only a USER-INITIATED disconnect must suppress it, until the user explicitly connects again. ---

    @Test
    fun `an explicit disconnect suppresses the internal reconnect loop even if the driver reports a stray failure behind it`() =
        runTest(UnconfinedTestDispatcher()) {
            val sdk = FakeNeoPenSdk(); val prefs = InMemoryPenPrefs()
            val mgr = manager(sdk, prefs, backgroundScope)
            mgr.connect("AA:BB")

            mgr.disconnect()
            sdk.connectShouldFailWith = PenMessage.FailureReason.TIMEOUT
            val attemptsAtDisconnect = sdk.connectAttempts
            // A straggling connect attempt from before the disconnect (or the driver's own internal
            // retry) resolves AFTER the user already asked to disconnect — must not restart the loop.
            sdk.connect(PenTarget.legacy("AA:BB"))

            advanceTimeBy(10_000)

            assertThat(mgr.state.value).isInstanceOf(PenConnState.Disconnected::class.java)
            // Only the stray attempt itself happened — no redial chained after it.
            assertThat(sdk.connectAttempts).isEqualTo(attemptsAtDisconnect + 1)
        }

    @Test
    fun `an explicit disconnect makes the service-level redial (autoConnect) a no-op until a new connect`() =
        runTest(UnconfinedTestDispatcher()) {
            val sdk = FakeNeoPenSdk(); val prefs = InMemoryPenPrefs()
            val mgr = manager(sdk, prefs, backgroundScope)
            mgr.connect("AA:BB")
            val attemptsAtConnect = sdk.connectAttempts

            mgr.disconnect()
            mgr.autoConnect() // mirrors PenForegroundService.onStartCommand's redial on (re)start

            assertThat(mgr.state.value).isInstanceOf(PenConnState.Disconnected::class.java)
            assertThat(sdk.connectAttempts).isEqualTo(attemptsAtConnect) // autoConnect did nothing
        }

    @Test
    fun `a fresh explicit connect after a disconnect clears the suppression - link loss auto-reconnects again`() =
        runTest(UnconfinedTestDispatcher()) {
            val sdk = FakeNeoPenSdk(); val prefs = InMemoryPenPrefs()
            val mgr = manager(sdk, prefs, backgroundScope)
            mgr.connect("AA:BB")
            mgr.disconnect()

            mgr.connect("AA:BB") // the user explicitly reconnects

            sdk.emitDisconnected() // a real link loss (pen powers off) ...
            assertThat(mgr.state.value).isInstanceOf(PenConnState.Reconnecting::class.java) // ...still auto-reconnects
        }

    @Test
    fun `takeOver is a user-facing connect too - it also clears the disconnect suppression`() =
        runTest(UnconfinedTestDispatcher()) {
            val sdk = FakeNeoPenSdk(); val prefs = InMemoryPenPrefs()
            val mgr = manager(sdk, prefs, backgroundScope)
            mgr.connect("AA:BB")
            mgr.disconnect()

            mgr.takeOver("AA:BB")
            assertThat(mgr.state.value).isInstanceOf(PenConnState.Connected::class.java)

            sdk.emitDisconnected()
            assertThat(mgr.state.value).isInstanceOf(PenConnState.Reconnecting::class.java)
        }

    // --- Low-battery notification (#10) ---

    @Test
    fun `battery crossing below 15 percent while connected fires the low-battery alert once`() =
        runTest(UnconfinedTestDispatcher()) {
            val sdk = FakeNeoPenSdk(); val prefs = InMemoryPenPrefs()
            val mgr = manager(sdk, prefs, backgroundScope)
            val alerts = mutableListOf<Int>()
            backgroundScope.launch { mgr.lowBatteryAlert.collect { alerts += it } }

            mgr.connect("AA:BB")
            sdk.emitBattery(20)
            sdk.emitBattery(15) // crosses -> fires once
            sdk.emitBattery(10) // still low -> no refire

            assertThat(alerts).containsExactly(15)
        }

    @Test
    fun `reconnecting re-arms the low-battery alert`() = runTest(UnconfinedTestDispatcher()) {
        val sdk = FakeNeoPenSdk(); val prefs = InMemoryPenPrefs()
        val mgr = manager(sdk, prefs, backgroundScope)
        val alerts = mutableListOf<Int>()
        backgroundScope.launch { mgr.lowBatteryAlert.collect { alerts += it } }

        mgr.connect("AA:BB")
        sdk.emitBattery(10) // fires

        mgr.disconnect()
        mgr.connect("AA:BB") // fresh session -> re-armed, even with no recovery above 20%
        sdk.emitBattery(10) // fires again

        assertThat(alerts).containsExactly(10, 10)
    }
}
