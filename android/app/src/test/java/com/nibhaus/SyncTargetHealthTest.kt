package com.nibhaus

import com.google.common.truth.Truth.assertThat
import com.nibhaus.export.SyncMethod
import com.nibhaus.health.SyncTargetState
import com.nibhaus.health.syncTargetState
import org.junit.Test

/**
 * Unit tests for [syncTargetState] — the pure classifier behind the Pens-home pending card's copy.
 * Covers every (method, required-field) branch so a queued-but-not-draining backlog always maps to
 * a state the UI can explain (see [SyncTargetState] kdoc for why this exists).
 */
class SyncTargetHealthTest {

    @Test fun `local folder with a chosen folder is configured`() {
        assertThat(syncTargetState(SyncMethod.LOCAL_FOLDER, "content://tree/abc", "", entitled = true))
            .isEqualTo(SyncTargetState.CONFIGURED)
    }

    @Test fun `local folder with a blank folder uri is NO_FOLDER`() {
        assertThat(syncTargetState(SyncMethod.LOCAL_FOLDER, "", "", entitled = true))
            .isEqualTo(SyncTargetState.NO_FOLDER)
    }

    @Test fun `tailscale push with an endpoint is configured when entitled`() {
        assertThat(syncTargetState(SyncMethod.TAILSCALE_PUSH, "", "https://nas.tailnet.ts.net:8090", entitled = true))
            .isEqualTo(SyncTargetState.CONFIGURED)
    }

    @Test fun `tailscale push with a blank endpoint is NO_ENDPOINT`() {
        assertThat(syncTargetState(SyncMethod.TAILSCALE_PUSH, "", "", entitled = true))
            .isEqualTo(SyncTargetState.NO_ENDPOINT)
    }

    @Test fun `local only is always LOCAL_ONLY regardless of the other fields`() {
        assertThat(syncTargetState(SyncMethod.LOCAL_ONLY, "", "", entitled = true)).isEqualTo(SyncTargetState.LOCAL_ONLY)
        assertThat(syncTargetState(SyncMethod.LOCAL_ONLY, "content://tree/abc", "https://nas:8090", entitled = true))
            .isEqualTo(SyncTargetState.LOCAL_ONLY)
        assertThat(syncTargetState(SyncMethod.LOCAL_ONLY, "", "", entitled = false)).isEqualTo(SyncTargetState.LOCAL_ONLY)
    }

    @Test fun `a folder uri of only whitespace still counts as NO_FOLDER`() {
        assertThat(syncTargetState(SyncMethod.LOCAL_FOLDER, "   ", "", entitled = true))
            .isEqualTo(SyncTargetState.NO_FOLDER)
    }

    @Test fun `an endpoint of only whitespace still counts as NO_ENDPOINT`() {
        assertThat(syncTargetState(SyncMethod.TAILSCALE_PUSH, "", "   ", entitled = true))
            .isEqualTo(SyncTargetState.NO_ENDPOINT)
    }

    // ---- Entitlement gating ----
    // A Tailscale endpoint that was configured before a relock (or by an app version predating this
    // gate) must classify as NOT configured, never CONFIGURED, so the home card's copy stays honest
    // about why the outbox isn't draining, instead of promising a sync that entitlement blocks.

    @Test fun `tailscale push with an endpoint but NOT entitled is NOT_ENTITLED, not NO_ENDPOINT`() {
        assertThat(syncTargetState(SyncMethod.TAILSCALE_PUSH, "", "https://nas.tailnet.ts.net:8090", entitled = false))
            .isEqualTo(SyncTargetState.NOT_ENTITLED)
    }

    @Test fun `tailscale push with a blank endpoint and NOT entitled is still NOT_ENTITLED`() {
        assertThat(syncTargetState(SyncMethod.TAILSCALE_PUSH, "", "", entitled = false))
            .isEqualTo(SyncTargetState.NOT_ENTITLED)
    }

    @Test fun `local folder configured stays CONFIGURED regardless of entitlement`() {
        assertThat(syncTargetState(SyncMethod.LOCAL_FOLDER, "content://tree/abc", "", entitled = false))
            .isEqualTo(SyncTargetState.CONFIGURED)
    }
}
