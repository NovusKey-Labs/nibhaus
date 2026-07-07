package com.nibhaus.di

/**
 * THE entitlement predicate (premium gate unification design, 2026-07-05). Every premium surface
 * (the accurate OCR pass, translation, native Tailscale sync, and future premium features) gates
 * on this one function: the runtime unlock (the `premium.unlocked` DataStore bool) AND the
 * :premium module actually present (the reflective seam resolved PremiumServicesImpl). Module
 * presence is availability, never entitlement; the unlock alone does nothing without the impl.
 */
fun premiumEntitled(unlocked: Boolean, premiumPresent: Boolean): Boolean =
    unlocked && premiumPresent

/**
 * Native (Tailscale) sync resolution gate, shared by StorageProvider resolution
 * ([ServiceLocator.currentStorageProvider]) and the transcript pull source (the
 * TranscriptImporter's source lambda): [create] runs only when the user is entitled AND an
 * endpoint is configured. This fixes the pre-unification split gate where the UI checked the
 * unlock bool but resolution checked module presence.
 */
internal fun <T> resolveNativeSync(entitled: Boolean, endpoint: String, create: (String) -> T?): T? =
    if (!entitled) null else endpoint.takeIf { it.isNotBlank() }?.let(create)
