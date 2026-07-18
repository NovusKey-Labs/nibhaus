package com.nibhaus.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Empty state for first-run surfaces: a friendly icon over one line of
 * plain-language next-action copy — the same quiet, unhurried voice as [QuietLine], just with a
 * little more visual presence for the screens where "nothing here yet" is the very first thing a
 * new user sees. Not a replacement for [QuietLine] everywhere — plain sub-lists (a notebook with
 * no pages, a tag with no matches) stay as they were.
 *
 * Two looks, chosen by whether [headline] is given:
 *  - **Plain** (headline omitted): icon + [text] only — used for the lighter-touch spots (Search,
 *    Favorites, a tag filter with no matches, the Activity feed).
 *  - **Rich** (headline given): a `primaryContainer` ring around the icon, a Sora [headline], the
 *    muted [text] as a supporting line, and — when both [primaryActionLabel] and [onPrimaryAction]
 *    are given — a filled primary-action button. Reserved for the true first-run home surfaces
 *    (empty Pens, empty Library) where a plain caption isn't enough guidance.
 */
@Composable
internal fun EmptyState(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    headline: String? = null,
    primaryActionLabel: String? = null,
    onPrimaryAction: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    if (headline == null) {
        Column(
            modifier.fillMaxWidth().padding(top = 40.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = cs.onSurfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
        return
    }
    Column(
        modifier.fillMaxWidth().padding(top = 48.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(72.dp).background(cs.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = cs.onPrimaryContainer, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text(
            headline,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
        if (primaryActionLabel != null && onPrimaryAction != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onPrimaryAction) { Text(primaryActionLabel) }
        }
    }
}
