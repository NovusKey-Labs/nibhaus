package com.nibhaus.ui.common

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nibhaus.R
import com.nibhaus.ui.theme.monoEyebrow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.nibhaus.ui.WordmarkText
import com.nibhaus.ui.rememberReducedMotion

/** App bar: Newsreader title over an uppercase mono sub-label, with optional trailing actions. */
@Composable
internal fun InkAppBar(title: String, sub: String? = null, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // weight(1f) so a long title shrinks/ellipsizes instead of wrapping and squeezing the
        // trailing actions (bug: on phone widths a crowded action row wrapped its own button text).
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) {
                Text(
                    sub.uppercase(),
                    style = monoEyebrow,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}

/** The Nibhaus lockup (mock §appbar): the vault mark + live "Nib"/gradient-"haus" wordmark. */
@Composable
internal fun BrandWordmark(modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(if (dark) R.drawable.brand_logo_dark else R.drawable.brand_logo_light),
            contentDescription = null,
            modifier = Modifier.height(44.dp),
        )
        Spacer(Modifier.width(11.dp))
        WordmarkText(fontSize = 32)
    }
}

// ---- Pens / Home (mock #1) ----

/** 38dp nib badge: primaryContainer fill, 9dp center dot — Brass when live, Slate when idle (§9). */
@Composable
internal fun NibBadge(live: Boolean) {
    val cs = MaterialTheme.colorScheme
    val reduced = rememberReducedMotion()
    val t = rememberInfiniteTransition(label = "nib")
    // Live → a ring pulses out from the nib dot (mockup .live .pulse, ~1.9s); static otherwise.
    val ringScale by t.animateFloat(
        1f, if (live && !reduced) 2.6f else 1f,
        infiniteRepeatable(tween(1900), RepeatMode.Restart), label = "nibRing",
    )
    val ringAlpha by t.animateFloat(
        if (live && !reduced) 0.5f else 0f, 0f,
        infiniteRepeatable(tween(1900), RepeatMode.Restart), label = "nibRingAlpha",
    )
    val dotColor = if (live) cs.tertiary else cs.secondary
    Box(
        Modifier.size(38.dp).background(cs.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (live) {
            Box(
                Modifier.size(9.dp)
                    .graphicsLayer { scaleX = ringScale; scaleY = ringScale; alpha = ringAlpha }
                    .background(dotColor, CircleShape),
            )
        }
        Box(Modifier.size(9.dp).background(dotColor, CircleShape))
    }
}

// ---- Library (mock #3) ----

/** True on phone-width screens (< 600 dp). Callers tighten chrome on compact screens — smaller
 *  titles, icon-only actions — so tablet-tuned sizing doesn't crowd a phone. Everything is designed
 *  against the tablet; this is the knob that adapts it down to the smaller physical screen. */
@Composable
internal fun rememberCompact(): Boolean = LocalConfiguration.current.screenWidthDp < 600

/** A row of dot-grid thumbnails (design-system §9 library thumbnail).
 *  [cols] is the target column count; partial last rows are padded with invisible spacers so
 *  every card keeps the same width. */
@Composable
internal fun <T> ThumbRow(
    items: List<T>,
    cols: Int,
    thumb: @Composable (item: T, modifier: Modifier) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEach { item -> thumb(item, Modifier.weight(1f)) }
        repeat(cols - items.size) { Spacer(Modifier.weight(1f)) } // keep partial rows left-aligned
    }
}

@Composable
internal fun QuietLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, start = 4.dp, end = 4.dp),
    )
}
