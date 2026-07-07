package com.nibhaus.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.TipsAndUpdates
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nibhaus.ui.Eyebrow
import com.nibhaus.ui.steelCard

/**
 * One-time "did you know" tip card (Feature 5): a small dismissible steelCard pointing at a feature
 * that just became relevant. Dismiss is permanent (the caller persists it via a SettingsStore flag) —
 * there's no snooze, just gone. Reused for all three tip cards (replay/GIF, printed-button taps,
 * transcribe-to-search) so they share one look.
 */
@Composable
internal fun TipCard(text: String, modifier: Modifier = Modifier, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().steelCard(radius = 14.dp).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.TipsAndUpdates, contentDescription = null, tint = cs.tertiary)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Eyebrow("DID YOU KNOW")
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
        }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Outlined.Close, contentDescription = "Dismiss tip", tint = cs.onSurfaceVariant)
        }
    }
}
