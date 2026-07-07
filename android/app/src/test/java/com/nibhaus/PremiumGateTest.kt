package com.nibhaus

import com.google.common.truth.Truth.assertThat
import com.nibhaus.di.premiumEntitled
import com.nibhaus.di.resolveNativeSync
import org.junit.Test

/**
 * The one entitlement predicate (premium gate unification, 2026-07-05) and the native-sync
 * resolution gate built on it. Pure functions: no Android, no DataStore, no ViewModel.
 */
class PremiumGateTest {

    /** Spec test 2: full (unlocked, present) truth table. Entitled means BOTH, nothing else. */
    @Test fun `entitled only when unlocked AND module present`() {
        assertThat(premiumEntitled(unlocked = true, premiumPresent = true)).isTrue()
        assertThat(premiumEntitled(unlocked = true, premiumPresent = false)).isFalse()
        assertThat(premiumEntitled(unlocked = false, premiumPresent = true)).isFalse()
        assertThat(premiumEntitled(unlocked = false, premiumPresent = false)).isFalse()
    }

    /** Spec test 4: resolution yields null when not entitled, even though a factory (module) is
     *  present and an endpoint is configured. This is the fix for the old split gate. */
    @Test fun `native sync resolves nothing when not entitled, even with the module present`() {
        val resolved = resolveNativeSync(
            entitled = false,
            endpoint = "https://vault.example.ts.net:8090",
        ) { "provider-from-present-module" }
        assertThat(resolved).isNull()
    }

    @Test fun `native sync resolves when entitled and an endpoint is set`() {
        val resolved = resolveNativeSync(
            entitled = true,
            endpoint = "https://vault.example.ts.net:8090",
        ) { it }
        assertThat(resolved).isEqualTo("https://vault.example.ts.net:8090")
    }

    @Test fun `native sync resolves nothing without a usable endpoint`() {
        assertThat(resolveNativeSync(entitled = true, endpoint = "") { it }).isNull()
        assertThat(resolveNativeSync(entitled = true, endpoint = "   ") { it }).isNull()
    }
}
