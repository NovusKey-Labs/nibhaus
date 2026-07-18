package com.nibhaus.ui.pagedetail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun ModelDownloadDisclosure(
    metered: Boolean,
    onWifiOnly: () -> Unit,
    onAllowMetered: () -> Unit,
    onDecline: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text("Download the accurate recognition model") },
        text = {
            Column {
                Text("Accurate recognition uses a vision-language model that runs entirely on your device. To enable it, Nibhaus downloads the model one time:")
                Text("• Source: Hugging Face (huggingface.co)", modifier = Modifier.padding(top = 8.dp))
                Text("• Download size: approximately 3.1 GB (one-time)")
                Text("• Where it lives: stored on this device only. Your notes and pen data are never uploaded — the download is one-way, from Hugging Face to your device.")
                Text("• Integrity: the file is verified against a pinned checksum after download; a mismatch is rejected.")
                Text("We recommend downloading over Wi-Fi. Nibhaus will not use mobile data for this download unless you choose to. You can pause or cancel at any time; a partial download resumes where it left off.", modifier = Modifier.padding(top = 8.dp))
                Text("This download is the only network contact accurate recognition makes. It exposes your device's IP address to Hugging Face's CDN, like any file download. No account, telemetry, or note content is sent.", modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = onWifiOnly) { Text("Download over Wi-Fi") } },
        dismissButton = {
            Column {
                TextButton(onClick = onAllowMetered, enabled = metered) { Text("Use mobile data") }
                TextButton(onClick = onDecline) { Text("Not now") }
            }
        },
    )
}
