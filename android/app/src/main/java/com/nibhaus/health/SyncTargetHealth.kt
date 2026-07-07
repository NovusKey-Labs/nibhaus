package com.nibhaus.health

import com.nibhaus.export.SyncMethod

/**
 * Why a queued export backlog isn't draining (or, for [LOCAL_ONLY], why that's expected). The outbox
 * is persist-first (DESIGN.md: every stored stroke enqueues an export) so a backlog by itself is
 * normal — but when the *sync target* isn't set up, the backlog piles up silently forever with no
 * signal to the user. This is a pure classification of the configured [SyncMethod] against its
 * required field, so the Pens-home pending card (and anything else) can explain *why* instead of just
 * showing a growing number.
 */
enum class SyncTargetState {
    /** A sync target is fully configured; the outbox should drain normally. */
    CONFIGURED,
    /** [SyncMethod.LOCAL_FOLDER] chosen but no folder picked yet. */
    NO_FOLDER,
    /** [SyncMethod.TAILSCALE_PUSH] chosen but no endpoint entered yet. */
    NO_ENDPOINT,
    /** [SyncMethod.TAILSCALE_PUSH] chosen (endpoint may already be set) but entitlement is the
     *  blocker — native sync is a premium feature the user doesn't have unlocked. Distinct from
     *  [NO_ENDPOINT] so the home card's copy stays honest about WHY the outbox isn't draining. */
    NOT_ENTITLED,
    /** [SyncMethod.LOCAL_ONLY] — there is nothing to push; a backlog here is expected and benign. */
    LOCAL_ONLY,
}

/**
 * Classify [method] + its required field into a [SyncTargetState]. Framework-free/pure.
 *
 * [entitled] gates [SyncMethod.TAILSCALE_PUSH] (final-review fix, 2026-07-05): native sync is a
 * premium surface, and an endpoint persisted from before a relock (or from an app version that
 * predates this entitlement check) must never classify as CONFIGURED. That would show the home
 * card's ordinary pending-copy while [com.nibhaus.di.ServiceLocator.currentStorageProvider] actually
 * resolves no provider and the outbox silently never drains. A missing entitlement takes priority
 * over a missing endpoint (final-review MINOR fix, 2026-07-05): the reason the outbox isn't draining
 * is the entitlement, not a blank field the user may well have already filled in.
 */
fun syncTargetState(method: SyncMethod, folderUri: String, endpoint: String, entitled: Boolean): SyncTargetState = when (method) {
    SyncMethod.LOCAL_FOLDER -> if (folderUri.isBlank()) SyncTargetState.NO_FOLDER else SyncTargetState.CONFIGURED
    SyncMethod.TAILSCALE_PUSH -> when {
        !entitled -> SyncTargetState.NOT_ENTITLED
        endpoint.isBlank() -> SyncTargetState.NO_ENDPOINT
        else -> SyncTargetState.CONFIGURED
    }
    SyncMethod.LOCAL_ONLY -> SyncTargetState.LOCAL_ONLY
}
