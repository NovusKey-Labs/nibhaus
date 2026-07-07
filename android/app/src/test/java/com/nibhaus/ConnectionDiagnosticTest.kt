package com.nibhaus

import com.google.common.truth.Truth.assertThat
import com.nibhaus.health.ConnectionDiagnostic
import com.nibhaus.health.ConnectionDiagnostic.Status
import org.junit.Test

class ConnectionDiagnosticTest {
    private fun probe(
        bt: Boolean = true, scan: Boolean = true, conn: Boolean = true, powered: Boolean = true,
        gatt: Boolean = true, auth: Boolean = true, dots: Boolean = true, paper: Boolean = true,
    ) = ConnectionDiagnostic.Probe(bt, scan, conn, powered, gatt, auth, dots, paper)

    @Test fun allGood_everyStepPasses() {
        val steps = ConnectionDiagnostic.run(probe())
        assertThat(steps.all { it.status == Status.PASS }).isTrue()
        assertThat(ConnectionDiagnostic.firstFailure(steps)).isNull()
    }

    @Test fun firstFailureBlocksTheRest() {
        // Bluetooth off → step 1 fails, everything after is skipped (not falsely "passing").
        val steps = ConnectionDiagnostic.run(probe(bt = false))
        assertThat(steps[0].status).isEqualTo(Status.FAIL)
        assertThat(steps.drop(1).all { it.status == Status.SKIPPED }).isTrue()
        assertThat(ConnectionDiagnostic.firstFailure(steps)!!.name).isEqualTo("Bluetooth on")
    }

    @Test fun reportsTheFirstFailingStepOnly() {
        // BT/permissions OK, but not connected → "Bluetooth link" is the first failure.
        val steps = ConnectionDiagnostic.run(probe(gatt = false, auth = false, dots = false, paper = false))
        val fail = ConnectionDiagnostic.firstFailure(steps)!!
        assertThat(fail.name).isEqualTo("Bluetooth link (GATT)")
        assertThat(fail.detail).contains("close")
    }

    @Test fun connectedButNoInk_pointsAtReceivingInk() {
        val steps = ConnectionDiagnostic.run(probe(dots = false, paper = false))
        assertThat(ConnectionDiagnostic.firstFailure(steps)!!.name).isEqualTo("Receiving ink")
    }
}
