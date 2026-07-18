package com.nibhaus.premiumapi

/**
 * Typed bundle of every supplier lambda [com.nibhaus.premium.PremiumServicesImpl]'s rich
 * constructor needs (BYO OCR endpoint, VLM metering/gating, telemetry).
 *
 * :app's ServiceLocator.loadPremium() reflectively constructs the impl and previously passed these
 * as 8 POSITIONAL args to newInstance(...) — same-erasure lambdas (mostly `() -> Boolean`), so a
 * reorder compiled cleanly and silently swapped feature flags at runtime (audit P1). Passing ONE
 * [PremiumDeps] instance instead means a field mix-up is a compile error in :premium, not a
 * runtime footgun.
 *
 * Every field defaults to null (⇒ the freemium-safe fallback each supplier already had), so
 * `PremiumDeps()` alone is a valid, fully-degraded bundle.
 */
data class PremiumDeps(
    /** Supplier for the user's BYO OCR endpoint URL. Null/blank → no server tier. */
    val byoEndpoint: (() -> String?)? = null,
    /** Supplier for the BYO endpoint auth token. Null → empty token. */
    val byoToken: (() -> String?)? = null,
    /** Allows HTTP only for explicitly opted-in trusted tailnet endpoints. */
    val allowCleartextEndpoints: Boolean = false,
    /** True when the device is on a metered connection. */
    val isMetered: (() -> Boolean)? = null,
    /** True when the user has force-enabled on-device VLM (overrides the RAM capability gate). */
    val userForcedVlm: (() -> Boolean)? = null,
    /** True when a prior latency probe determined this device is too slow for on-device VLM. */
    val vlmDisabledOnDevice: (() -> Boolean)? = null,
    /** Invoked with the OCR inference latency (ms) when it exceeds the slow-budget threshold. */
    val markVlmTooSlow: ((Long) -> Unit)? = null,
)
