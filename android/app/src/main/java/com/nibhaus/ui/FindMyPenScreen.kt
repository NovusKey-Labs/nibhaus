package com.nibhaus.ui

import android.content.res.Configuration
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nibhaus.export.ThemeMode
import com.nibhaus.pen.PenConnState
import com.nibhaus.ui.theme.G1
import com.nibhaus.ui.theme.G2
import com.nibhaus.ui.theme.SteelD
import com.nibhaus.ui.theme.monoEyebrow
import kotlin.math.exp
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// RSSI smoothing + proximity bucketing (pure — unit-tested in FindMyPenProximityTest)
// ---------------------------------------------------------------------------

/**
 * Exponential moving average over raw RSSI (dBm), robust to the ~1 Hz-but-uneven spacing of
 * [com.nibhaus.pen.PenConnectionManager.rssiDbm] polls. BLE RSSI swings ±10 dB sample-to-sample from
 * body position, walls, and antenna orientation alone — a naive distance estimate straight off raw
 * samples (d = 10^((txPower−rssi)/20)) is too jittery to drive a stable radar. Smoothing over ~3 s
 * trades a little lag for a proximity meter that doesn't flicker between buckets on every read.
 *
 * [previous] null primes the filter with the raw reading directly (no warm-up lag on the very first
 * sample after polling starts).
 */
internal fun emaRssi(previous: Float?, raw: Int, dtMillis: Long, timeConstantMillis: Long = 3_000L): Float {
    if (previous == null) return raw.toFloat()
    val dt = dtMillis.coerceAtLeast(0L)
    val alpha = 1f - exp(-dt.toFloat() / timeConstantMillis.toFloat())
    return previous + alpha * (raw - previous)
}

/** Qualitative hot/cold proximity, shown instead of a noisy metres estimate. */
internal enum class ProximityLevel(val label: String) {
    VERY_CLOSE("VERY CLOSE"),
    NEARBY("NEARBY"),
    IN_THE_ROOM("IN THE ROOM"),
    FAR("FAR"),
}

/** Bucket a smoothed RSSI (dBm) into a [ProximityLevel]. Thresholds are deliberately wide — BLE RSSI
 *  varies by 10+ dB with orientation and obstruction alone, so tight bands would flicker constantly.
 *  The IN_THE_ROOM/FAR boundary is -80: across-the-house-through-walls readings sit below that, so a
 *  pen in another part of the house no longer reads as "in the room" (field report, 2026-07-05). */
internal fun proximityLevel(smoothedRssiDbm: Float): ProximityLevel = when {
    smoothedRssiDbm >= -55f -> ProximityLevel.VERY_CLOSE
    smoothedRssiDbm >= -70f -> ProximityLevel.NEARBY
    smoothedRssiDbm >= -80f -> ProximityLevel.IN_THE_ROOM
    else -> ProximityLevel.FAR
}

/**
 * Map a smoothed RSSI (dBm) to a 0..1 "closeness" for positioning the pen pin on the radar: 1 = at
 * (or above) [nearCeilDbm], pin sits at center; 0 = at (or below) [farFloorDbm], pin sits at the
 * outer ring. Linear in between. This is what makes the pin actually MOVE toward/away from center as
 * you walk toward/away from the pen — before, the pin was pinned to dead center regardless of signal,
 * so the radar looked frozen (field report, 2026-07-05). BLE gives no bearing, so only the radial
 * distance from center is meaningful; the angle is left fixed (straight up = farther out).
 */
internal fun proximityCloseness(
    smoothedRssiDbm: Float,
    nearCeilDbm: Float = -50f,
    farFloorDbm: Float = -95f,
): Float = ((smoothedRssiDbm - farFloorDbm) / (nearCeilDbm - farFloorDbm)).coerceIn(0f, 1f)

/** How far (dp) the pen pin travels from radar center (closeness 1) to the outer ring (closeness 0).
 *  Kept inside the 284 dp radar's outer ring (~125 dp radius) with room for the 36 dp pin. */
private const val PIN_TRAVEL_DP = 96f

/**
 * Find-My-Pen radar/route/pin screen (V3 design-system §10 motion spec):
 *   - **sweep** — gradient sweep line rotates 360° over concentric steel rings.
 *   - **ping** — ring expands scale 0.5→2.35 + fades, looping.
 *   - **route dots** — 4 dots pulse scale 0.7↔1.28 with staggered delays.
 *   - **pin-bob** — the pen pin bobs ±6 dp at center.
 *
 * All decorative loops gate on [rememberReducedMotion].
 *
 * **RSSI (#20b):** polls [InkViewModel.rssiDbm] at ~1 Hz ONLY while this screen is visible (see
 * [InkViewModel.startRssiPolling]/[InkViewModel.stopRssiPolling] — the underlying
 * [com.nibhaus.pen.PenConnectionManager]/[com.nibhaus.penble.PenBleSdk] driver only reads RSSI while
 * something asked). Raw readings are smoothed with [emaRssi] and bucketed with [proximityLevel] into
 * a hot/cold readout — a metres estimate off raw RSSI is too noisy to be meaningful indoors.
 */
@Composable
fun FindMyPenScreen(vm: InkViewModel, onBack: () -> Unit) {
    val pen by vm.penState.collectAsStateWithLifecycle()
    val rawRssi by vm.rssiDbm.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        vm.startRssiPolling()
        onDispose { vm.stopRssiPolling() }
    }

    var smoothedRssi by remember { mutableStateOf<Float?>(null) }
    var lastSampleAt by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(rawRssi) {
        val raw = rawRssi
        if (raw == null) {
            smoothedRssi = null
            lastSampleAt = null
        } else {
            val now = System.currentTimeMillis()
            val dt = lastSampleAt?.let { now - it } ?: Long.MAX_VALUE
            smoothedRssi = emaRssi(smoothedRssi, raw, dt)
            lastSampleAt = now
        }
    }

    FindMyPenContent(
        isConnected = pen is PenConnState.Connected,
        smoothedRssiDbm = smoothedRssi,
        onBack = onBack,
    )
}

// ---------------------------------------------------------------------------
// Internal pure composable — separate so previews call it directly
// ---------------------------------------------------------------------------

@Composable
private fun FindMyPenContent(
    isConnected: Boolean,
    smoothedRssiDbm: Float? = null,
    onBack: () -> Unit,
) {
    val reduced = rememberReducedMotion()
    val cs = MaterialTheme.colorScheme

    // Pen-pin radial position: glide the pin between center (close) and the outer ring (far) as the
    // smoothed signal changes, so the radar actually reacts when you walk around. No reading yet →
    // rest at center. Animated so it drifts smoothly between the ~1 Hz polls instead of jumping.
    val targetCloseness = smoothedRssiDbm?.let { proximityCloseness(it) } ?: 1f
    val animatedCloseness by animateFloatAsState(
        targetValue = targetCloseness,
        animationSpec = tween(durationMillis = 900, easing = LinearEasing),
        label = "closeness",
    )

    // ── Animation declarations ────────────────────────────────────────────────
    // All loops use a single InfiniteTransition; gated by setting targetValue = initialValue when
    // reduced motion is on (so the lambda still runs but produces no visual change).

    val inf = rememberInfiniteTransition(label = "findMyPen")

    // radar sweep: 0 → 360° over 4 s, linear so the 360°→0° reset is invisible
    val sweepAngle by inf.animateFloat(
        initialValue = 0f,
        targetValue = if (reduced) 0f else 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweep",
    )

    // ping scale: 0.5 → 2.35 then resets (ring explodes outward)
    val pingScale by inf.animateFloat(
        initialValue = 0.5f,
        targetValue = if (reduced) 0.5f else 2.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_000, easing = NibEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pingScale",
    )
    // ping alpha: fades from 0.65 → 0 in sync with scale expansion
    val pingAlpha by inf.animateFloat(
        initialValue = if (reduced) 0f else 0.65f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_000, easing = NibEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pingAlpha",
    )

    // pin-bob: translates 0 ↔ -6 dp (bobs upward and back), 2.2 s reverse
    val pinBobDp by inf.animateFloat(
        initialValue = 0f,
        targetValue = if (reduced) 0f else -6f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_200, easing = NibEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pinBob",
    )

    // route dots: scale 0.7 ↔ 1.28 with staggered initial delays (persists across Reverse cycles)
    // Spec delays: 0, 0.1, 0.32, 0.54 s → 0, 100, 320, 540 ms
    val dot0 by inf.animateFloat(
        initialValue = if (reduced) 1f else 0.7f,
        targetValue = if (reduced) 1f else 1.28f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_400, delayMillis = 0, easing = NibEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot0",
    )
    val dot1 by inf.animateFloat(
        initialValue = if (reduced) 1f else 0.7f,
        targetValue = if (reduced) 1f else 1.28f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_400, delayMillis = 100, easing = NibEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot1",
    )
    val dot2 by inf.animateFloat(
        initialValue = if (reduced) 1f else 0.7f,
        targetValue = if (reduced) 1f else 1.28f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_400, delayMillis = 320, easing = NibEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot2",
    )
    val dot3 by inf.animateFloat(
        initialValue = if (reduced) 1f else 0.7f,
        targetValue = if (reduced) 1f else 1.28f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_400, delayMillis = 540, easing = NibEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot3",
    )

    val dotScales = listOf(dot0, dot1, dot2, dot3)

    // ── Layout ───────────────────────────────────────────────────────────────

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.background),
    ) {
        // ── Top app bar ───────────────────────────────────────────────────────

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 10.dp)
                .riseIn(index = 0),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    tint = cs.onSurface,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Find my pen",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Eyebrow("BLUETOOTH · LOCATING")
            }
            StatusChip(if (isConnected) "CONNECTED" else "FINDING")
        }

        BandDivider()

        // ── Radar Canvas + bobbing pen pin ────────────────────────────────────

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .riseIn(index = 1),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier.size(284.dp),
                contentAlignment = Alignment.Center,
            ) {
                // Radar Canvas: concentric rings + sweep + ping
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val maxR = minOf(size.width, size.height) / 2f * 0.88f

                    // 4 concentric steel rings — faintest at center, progressively brighter outward
                    for (i in 1..4) {
                        drawCircle(
                            color = SteelD.copy(alpha = 0.10f + 0.06f * i),
                            radius = maxR * i / 4f,
                            center = c,
                            style = Stroke(width = 1.dp.toPx()),
                        )
                    }

                    // Cross-hair guides (very faint steel lines)
                    val hair = SteelD.copy(alpha = 0.18f)
                    drawLine(hair, Offset(c.x - maxR, c.y), Offset(c.x + maxR, c.y), 0.5.dp.toPx())
                    drawLine(hair, Offset(c.x, c.y - maxR), Offset(c.x, c.y + maxR), 0.5.dp.toPx())

                    if (!reduced) {
                        // Radar sweep: rotate canvas, then draw gradient line + ghost trail
                        rotate(sweepAngle, pivot = c) {
                            // Ghost trail: 3 lines at -15°, -30°, -45° with diminishing alpha + width
                            for (t in 1..3) {
                                rotate(degrees = -t * 15f, pivot = c) {
                                    drawLine(
                                        color = G2.copy(alpha = 0.09f / t),
                                        start = c,
                                        end = Offset(c.x + maxR, c.y),
                                        strokeWidth = maxOf((3f - t) * 1.dp.toPx(), 0.5.dp.toPx()),
                                    )
                                }
                            }
                            // Main sweep line: transparent at center → G1 mid → G2 vivid at tip
                            drawLine(
                                brush = Brush.linearGradient(
                                    colorStops = arrayOf(
                                        0f to Color.Transparent,
                                        0.25f to G1.copy(alpha = 0.35f),
                                        1f to G2,
                                    ),
                                    start = c,
                                    end = Offset(c.x + maxR, c.y),
                                ),
                                start = c,
                                end = Offset(c.x + maxR, c.y),
                                strokeWidth = 2.dp.toPx(),
                            )
                        }

                        // Ping ring: expands outward from ~15% to ~74% of radar radius, fades as it grows
                        if (pingAlpha > 0.01f) {
                            drawCircle(
                                color = G1.copy(alpha = pingAlpha),
                                radius = maxR * 0.315f * pingScale,
                                center = c,
                                style = Stroke(width = 1.5.dp.toPx()),
                            )
                        }
                    }
                }

                // Bobbing pen pin: ink-gradient circle with nib icon, centred on the radar
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .graphicsLayer {
                            // GraphicsLayerScope : Density — dp.toPx() is valid here (see NibMotion.kt).
                            // translationY = the bob, MINUS the radial travel (up = farther from center);
                            // travel is 0 at closeness 1 (center) and PIN_TRAVEL_DP at closeness 0 (ring).
                            val travel = (1f - animatedCloseness) * PIN_TRAVEL_DP.dp.toPx()
                            translationY = pinBobDp.dp.toPx() - travel
                            transformOrigin = TransformOrigin.Center
                        }
                        .inkGlow(CircleShape)
                        .clip(CircleShape)
                        .background(inkGradient()),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = "Pen location",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        // ── Route dots + distance / signal readout ────────────────────────────

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp)
                .riseIn(index = 2),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            // Route dots — staggered-pulse row; acts as a "distance" signal indicator.
            // Dots pulse 0.7↔1.28 with increasing phase offsets (wave travels left→right).
            Row(
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                dotScales.forEach { scale ->
                    Box(
                        modifier = Modifier
                            .size(11.dp)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                transformOrigin = TransformOrigin.Center
                            }
                            .clip(CircleShape)
                            .background(inkGradient()),
                    )
                }
            }

            // Distance / signal readout — hot/cold proximity from the smoothed RSSI (#20b). No
            // signal yet (still smoothing / pen not connected) falls back to the original
            // "searching" copy rather than flashing a bucket on the very first noisy reading.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val level = smoothedRssiDbm?.let { proximityLevel(it) }
                Text(
                    text = level?.label ?: "LOCATING…",
                    style = monoEyebrow.copy(fontSize = 18.sp, letterSpacing = 3.sp),
                    color = cs.onSurface,
                )
                Text(
                    text = when {
                        !isConnected -> "SIGNAL · DISCONNECTED"
                        smoothedRssiDbm != null -> "SIGNAL · ${smoothedRssiDbm.roundToInt()} dBm"
                        else -> "SIGNAL · SEARCHING"
                    },
                    style = monoEyebrow,
                    color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Previews (dark + light) — show static resting state (animations are single-frame in Preview)
// ---------------------------------------------------------------------------

@Preview(
    name = "Find-My-Pen · dark (connected)",
    showBackground = true,
    backgroundColor = 0xFF070D1C,
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
    widthDp = 360,
    heightDp = 720,
)
@Composable
private fun PreviewFindMyPenDark() {
    NibhausTheme(ThemeMode.DARK) {
        FindMyPenContent(isConnected = true, smoothedRssiDbm = -52f, onBack = {})
    }
}

@Preview(
    name = "Find-My-Pen · light (searching)",
    showBackground = true,
    backgroundColor = 0xFFF4F6FA,
    uiMode = Configuration.UI_MODE_NIGHT_NO or Configuration.UI_MODE_TYPE_NORMAL,
    widthDp = 360,
    heightDp = 720,
)
@Composable
private fun PreviewFindMyPenLight() {
    NibhausTheme(ThemeMode.LIGHT) {
        FindMyPenContent(isConnected = false, onBack = {})
    }
}
