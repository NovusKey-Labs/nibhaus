package com.nibhaus.ui.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One snackbar surface for the whole app: UNDO after a delete (#6), success confirmation after an
 * export/share/save (#7), and failure diagnosis with Retry (#8). Wraps [SnackbarHostState] with an
 * exact-millis auto-dismiss (Compose's own [SnackbarDuration] only offers Short/Long/Indefinite,
 * and #6 needs precisely 5s to line up with the pending-delete timer) so every call site just states
 * a message, an optional action, and how long to show it.
 *
 * Installed once at the nav-host level ([com.nibhaus.ui.InkApp] in Screens.kt) and reached from any
 * nested screen via [LocalAppSnackbar] — no need to thread a callback through every screen's
 * parameter list.
 */
class AppSnackbar(private val hostState: SnackbarHostState, private val scope: CoroutineScope) {

    /**
     * Show [message], optionally with one action button (e.g. "Undo", "Retry"). Replaces whatever
     * snackbar is currently up (Compose's [SnackbarHostState] only ever shows one at a time; a newer
     * call effectively supersedes an older one still queued).
     */
    fun show(
        message: String,
        actionLabel: String? = null,
        durationMs: Long = 3_000L,
        onAction: () -> Unit = {},
    ) {
        scope.launch {
            val autoDismiss = launch {
                delay(durationMs)
                hostState.currentSnackbarData?.dismiss()
            }
            val result = hostState.showSnackbar(message, actionLabel = actionLabel, duration = SnackbarDuration.Indefinite)
            autoDismiss.cancel()
            if (result == SnackbarResult.ActionPerformed) onAction()
        }
    }
}

val LocalAppSnackbar = staticCompositionLocalOf<AppSnackbar> {
    error("LocalAppSnackbar not provided — InkApp installs it around the whole app in Screens.kt")
}
