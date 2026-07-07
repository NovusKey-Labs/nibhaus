package com.nibhaus.ui.common

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Pure exception/context → user-facing failure message mapping (#8). This does not invent a new
 * error channel: every call site already catches an exception (or already knows a boolean outcome
 * and a configured host) — this just turns what's already caught into one plain-language line of
 * what failed, the most likely cause, and (where the message says so) that a retry will help.
 * Framework-free and unit-tested on its own; wired at each existing catch site in [com.nibhaus.ui.InkViewModel].
 */
object FailureDiagnosis {

    /** [message] is the single plain-language line to show (snackbar or inline card); [canRetry]
     *  says whether a Retry action makes sense (false for cases nothing action will fix). */
    data class Diagnosis(val message: String, val canRetry: Boolean = true)

    /**
     * Classifies a caught network failure into a short, honest reason. Reads the exception TYPE when
     * one is available (a real [UnknownHostException]/[ConnectException]/[SocketTimeoutException] —
     * the case where a call site's own `catch (e: Exception)` handed us the real throwable), and
     * falls back to reading its message text for the `error("... returned $code")` shape several
     * call sites in :premium already throw on a non-2xx HTTP response. [exception] is null when the
     * failure happened behind a swallow-to-null boundary the caller can't see past (e.g. the OCR/
     * translate engine chains fall back tier-to-tier internally) — the reason then stays generic
     * rather than guessing.
     */
    internal fun networkReason(exception: Throwable?): String = when {
        exception is UnknownHostException -> "That address couldn't be found"
        exception is ConnectException -> "It refused the connection"
        exception is SocketTimeoutException -> "It took too long to respond"
        exception?.message?.contains("time", ignoreCase = true) == true -> "It took too long to respond"
        exception?.message?.let { HTTP_ERROR_CODE.containsMatchIn(it) } == true -> "It responded with an error"
        else -> "It didn't respond"
    }

    /**
     * A configured host — OCR/translate server, or a sync target — couldn't be reached. Names the
     * host so the user isn't left guessing which of several possible endpoints failed, and suggests
     * the most likely fix for a homelab/tailnet setup (same network or VPN as that host).
     */
    fun hostUnreachable(operation: String, host: String, exception: Throwable? = null): Diagnosis =
        Diagnosis(
            "Couldn't $operation. Tried $host. ${networkReason(exception)}. " +
                "Check that you're on the same network or VPN as that host, then retry.",
        )

    /**
     * No custom server is configured, so there's no host to name — either the on-device engine
     * itself returned nothing (e.g. no recognizable handwriting) or it's simply not set up. Kept
     * distinct from [hostUnreachable] so the copy never implies a network problem that isn't one.
     */
    fun noResult(operation: String): Diagnosis =
        Diagnosis("Couldn't $operation. No recognizable result. Try again, or add a server in Settings for better accuracy.")

    /**
     * Export/share: rendering the page or writing it to the sync destination failed. [exception] is
     * whatever the existing catch already has for that attempt — most export call sites today only
     * keep a boolean outcome (no exception survives past [com.nibhaus.export.ExportEngine]'s own
     * `runCatching`), so this is usually called with null and names both likely suspects instead of
     * guessing one. Always says the automatic retry already covers "try again" — a manual Retry
     * button would just duplicate the queued retry-with-backoff that's already going to run.
     */
    fun exportFailure(exception: Throwable? = null): Diagnosis =
        if (exception == null) {
            Diagnosis(
                "Export failed. The sync folder may not be reachable, or the page couldn't be rendered. " +
                    "It stays queued and will retry.",
                canRetry = false,
            )
        } else {
            val reason = networkReason(exception).replaceFirstChar(Char::lowercaseChar)
            Diagnosis("Export failed ($reason). It stays queued and will retry.", canRetry = false)
        }

    // Matches the two "non-2xx" message shapes existing catch sites already throw:
    // TailscalePushProvider's "... -> HTTP 500" and Translator/ServerInk's "... returned 500".
    private val HTTP_ERROR_CODE = Regex("(HTTP|returned)\\s*\\d{3}")
}
