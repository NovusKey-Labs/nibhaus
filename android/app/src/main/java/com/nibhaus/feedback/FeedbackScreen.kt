package com.nibhaus.feedback

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nibhaus.ui.Eyebrow
import com.nibhaus.ui.steelCard
import com.nibhaus.ui.theme.monoData

/**
 * Full diagnostic bundle + free-text field + Send — the one screen both entry points (Settings →
 * Privacy → "Send feedback" and the post-crash Pens-home card) open. Nothing is transmitted by the
 * app itself: Send hands an ACTION_SEND text/plain intent to a chooser and the user's own mail app
 * does the sending. [bundle] is assembled by the caller ([buildFeedbackBundle]) — this screen only
 * renders and sends it, so what's shown is exactly what's sent.
 */
@Composable
fun FeedbackScreen(bundle: FeedbackBundle, onClose: () -> Unit) {
    val context = LocalContext.current
    var userText by rememberSaveable { mutableStateOf("") }
    val bundleText = remember(bundle) { formatBundleText(bundle) }
    val cs = MaterialTheme.colorScheme

    // The screen is shown inside a borderless full-width Dialog; without its own surface it floats
    // transparently over whatever's behind it. A solid background makes it read as a proper sheet.
    Surface(Modifier.fillMaxSize(), color = cs.background) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Send feedback", style = MaterialTheme.typography.headlineSmall)
            Button(onClick = onClose) { Text("Close") }
        }
        LazyColumn(Modifier.weight(1f)) {
            item {
                Text(
                    "Tell us what's broken or missing. You'll see exactly what's sent below.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            item {
                OutlinedTextField(
                    value = userText,
                    onValueChange = { userText = it },
                    label = { Text("What happened?") },
                    placeholder = { Text("The more detail, the better.") },
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                )
            }
            item { Eyebrow("WHAT WILL BE SENT", Modifier.padding(top = 18.dp, bottom = 8.dp)) }
            item {
                Column(Modifier.fillMaxWidth().steelCard(radius = 14.dp).padding(14.dp)) {
                    SelectionContainer {
                        Text(bundleText, style = monoData, color = cs.onSurface)
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
        // Two send paths get equal weight (half-width each, so "GitHub issue" fits on one line);
        // Copy is the fallback, so it sits below as a full-width outlined action.
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = { sendFeedbackEmail(context, userText, bundleText, bundle) },
                modifier = Modifier.weight(1f),
            ) { Text("Email", maxLines = 1) }
            Button(
                onClick = { openGithubIssue(context, userText, bundleText, bundle) },
                modifier = Modifier.weight(1f),
            ) { Text("GitHub issue", maxLines = 1) }
        }
        OutlinedButton(
            onClick = { copyReport(context, userText, bundleText) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Copy report", maxLines = 1) }
        Text(
            "Email sends from your own mail account, so it shows your address, like any email. " +
                "GitHub shows only your GitHub username. The report itself contains no identity.",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp),
        )
    }
    }
}

/** ACTION_SENDTO on a mailto: URI — restricts the picker to actual email apps (the plain
 *  ACTION_SEND chooser buried mail among every share target; user feedback 2026-07-03). The app
 *  still transmits nothing itself. */
private fun sendFeedbackEmail(context: Context, userText: String, bundleText: String, bundle: FeedbackBundle) {
    val intent = Intent(Intent.ACTION_SENDTO).apply {
        data = android.net.Uri.parse("mailto:${FeedbackConfig.EMAIL}")
        putExtra(Intent.EXTRA_SUBJECT, feedbackEmailSubject(bundle))
        putExtra(Intent.EXTRA_TEXT, feedbackEmailBody(userText, bundleText))
    }
    runCatching { context.startActivity(intent) }
        .onFailure { // no email app at all — fall back to the generic share sheet
            val fallback = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(FeedbackConfig.EMAIL))
                putExtra(Intent.EXTRA_SUBJECT, feedbackEmailSubject(bundle))
                putExtra(Intent.EXTRA_TEXT, feedbackEmailBody(userText, bundleText))
            }
            context.startActivity(Intent.createChooser(fallback, "Send feedback"))
        }
}

/** Opens the repo's new-issue page prefilled with the report — filed issues flow straight into
 *  the feedback ledger via the repo's sweep workflow. */
private fun openGithubIssue(context: Context, userText: String, bundleText: String, bundle: FeedbackBundle) {
    val title = android.net.Uri.encode(feedbackEmailSubject(bundle))
    val body = android.net.Uri.encode(feedbackEmailBody(userText, bundleText))
    val url = "${FeedbackConfig.REPO_ISSUES_URL}/new?title=$title&body=$body"
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    }
}

/** Clipboard fallback — paste it anywhere. */
private fun copyReport(context: Context, userText: String, bundleText: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("Nibhaus feedback", feedbackEmailBody(userText, bundleText)))
}
