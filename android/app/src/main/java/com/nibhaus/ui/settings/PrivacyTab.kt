package com.nibhaus.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nibhaus.feedback.CrashCapture
import com.nibhaus.feedback.FeedbackScreen
import com.nibhaus.feedback.buildFeedbackBundle
import com.nibhaus.feedback.crashReportBody
import com.nibhaus.feedback.crashTail
import com.nibhaus.security.AppLock
import com.nibhaus.ui.InkViewModel
import com.nibhaus.ui.NibToggle

/** Tab 3 — "Privacy": the app-lock toggle and the user-initiated feedback entry point. */
internal fun LazyListScope.privacyTab(vm: InkViewModel) {
    item { AppLockCard(vm) }
    item { SendFeedbackCard(vm) }
}

/**
 * "Send feedback" (user-initiated feedback mechanism, part 1): a one-line benefit pitch that opens
 * the full diagnostic bundle before anything is sent — the user sees exactly what would go out and
 * only Send actually hands it to a mail app (ACTION_SEND chooser; the app itself transmits nothing).
 */
@Composable
private fun SendFeedbackCard(vm: InkViewModel) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    var showFeedback by remember { mutableStateOf(false) }
    val premium by vm.isPremium.collectAsStateWithLifecycle()
    val palette by vm.activePalette.collectAsStateWithLifecycle()

    SettingsCard {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 14.dp).clickable { showFeedback = true },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Send feedback", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Tell us what's broken or missing. You'll see exactly what's sent.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }
            Icon(Icons.Outlined.Feedback, contentDescription = null, tint = cs.onSurfaceVariant)
        }
    }
    if (showFeedback) {
        Dialog(
            onDismissRequest = { showFeedback = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            val crashBody = remember { crashReportBody(CrashCapture.readLastCrash(context)) }
            val bundle = remember(premium, palette, crashBody) {
                buildFeedbackBundle(premium, palette.id, crashTail(crashBody))
            }
            FeedbackScreen(bundle = bundle, onClose = { showFeedback = false })
        }
    }
}

/**
 * App-lock toggle (Section C1). Only enableable when the device can actually authenticate (a
 * biometric or device credential is set up); otherwise it's disabled with a hint. The lock itself
 * is enforced in MainActivity.
 */
@Composable
private fun AppLockCard(vm: InkViewModel) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val enabled by vm.appLockEnabled.collectAsStateWithLifecycle()
    val available = remember { AppLock.available(context) }
    SettingsCard {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("App lock", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (available) {
                        "Keeps your notes private by requiring your biometric or device PIN to open " +
                            "Nibhaus, and again after it's been in the background."
                    } else {
                        "Set up a screen lock (PIN, pattern, or biometric) on your device to turn this on."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }
            NibToggle(
                checked = enabled && available,
                onCheckedChange = { if (available) vm.setAppLockEnabled(it) },
                modifier = Modifier.alpha(if (available) 1f else 0.38f),
            )
        }
    }
}
