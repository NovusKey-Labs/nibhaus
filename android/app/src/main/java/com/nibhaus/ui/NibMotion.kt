package com.nibhaus.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.nibhaus.R
import com.nibhaus.di.StartupProgress
import com.nibhaus.ui.theme.G1
import com.nibhaus.ui.theme.G2
import com.nibhaus.ui.theme.G3
import com.nibhaus.ui.theme.InkGradientStops
import com.nibhaus.ui.theme.InkText
import com.nibhaus.ui.theme.monoEyebrow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/**
 * Nib & Ink V3 signature easing — `cubic-bezier(0.2, 0.8, 0.2, 1)`. Decelerates fast, settles
 * softly; the single easing used everywhere (entrance tweens, grad-pan, NibSplash).
 */
val NibEasing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

/**
 * "Nib" in ink color + "haus" in the gradient, Sora — the live wordmark (matches the mockup).
 * [inkColor] defaults to the theme's onSurface so "Nib" reads dark-navy on light / near-white on
 * dark (mockup `--ink` flips by theme); pass a fixed light color over an always-dark ground (splash).
 * [vaultBrush] defaults to the static ink gradient; [NibSplash] passes an animated one-shot pan
 * across "haus" instead.
 */
@Composable
fun WordmarkText(
    fontSize: Int = 30,
    inkColor: Color = MaterialTheme.colorScheme.onSurface,
    vaultBrush: Brush = Brush.linearGradient(InkGradientStops),
) {
    val display = MaterialTheme.typography.displaySmall.copy(fontSize = fontSize.sp, fontWeight = FontWeight.ExtraBold)
    Row {
        Text("Nib", style = display.copy(color = inkColor))
        Text("haus", style = display.copy(brush = vaultBrush))
    }
}

/**
 * True when the system "Remove animations" (accessibility / developer) setting is on — the platform
 * zeroes [Settings.Global.ANIMATOR_DURATION_SCALE]. Compose infinite transitions don't honor it
 * automatically, so gate decorative loops (FAB float, gradient pan) on this. The mockup honors
 * prefers-reduced-motion the same way (line 243: animations off).
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/**
 * Animated ink gradient whose start/end offsets pan over 7 s (infiniteRepeatable Reverse), making
 * the gradient feel alive on chips, badges, FABs, and toggles. Falls back to the static
 * [inkGradient] when reduced motion is on.
 *
 * The gradient tile is 200 dp — right-sized for small components (chips ≤ 150 dp, FAB ≤ 60 dp).
 * For screen-sized surfaces use [inkGradient] directly.
 */
@Composable
fun gradPanBrush(): Brush {
    val reduced = rememberReducedMotion()
    if (reduced) return inkGradient()
    val density = LocalDensity.current
    val cellPx = with(density) { 200.dp.toPx() }
    val t = rememberInfiniteTransition(label = "gradPan")
    val panX by t.animateFloat(
        initialValue = 0f,
        targetValue = cellPx,
        animationSpec = infiniteRepeatable(
            animation = tween(7_000, easing = NibEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pan",
    )
    // Diagonal gradient pans along X so the full G1→G2→G3 sweep moves across the composable
    return Brush.linearGradient(
        colorStops = arrayOf(0f to G1, 0.52f to G2, 1f to G3),
        start = Offset(panX, 0f),
        end = Offset(panX + cellPx, cellPx),
    )
}

/**
 * Staggered entrance: on first appearance, translates from 14 dp below and fades in over 600 ms
 * with [NibEasing], delayed by [index] × [stagger] ms. Snaps to resting state (no animation)
 * when reduced motion is on. Apply per child in a screen to make it "populate in."
 *
 * Usage: `Box(modifier = Modifier.riseIn(index = 0).steelCard())`
 */
@Composable
fun Modifier.riseIn(index: Int = 0, stagger: Int = 60): Modifier {
    val reduced = rememberReducedMotion()
    val alpha = remember { Animatable(if (reduced) 1f else 0f) }
    val ty = remember { Animatable(if (reduced) 0f else 14f) }
    LaunchedEffect(Unit) {
        if (!reduced) {
            delay((index * stagger).toLong())
            launch { alpha.animateTo(1f, tween(600, easing = NibEasing)) }
            ty.animateTo(0f, tween(600, easing = NibEasing))
        }
    }
    // Read state values here (composable scope) so changes trigger recomposition
    val a = alpha.value
    val t = ty.value
    return this.graphicsLayer {
        this.alpha = a
        translationY = t.dp.toPx()   // GraphicsLayerScope : Density — dp.toPx() works directly
    }
}

/**
 * Whether [NibSplash] has already played once during this process's lifetime. A plain object
 * field, not `rememberSaveable` — `rememberSaveable` is restored from the composable's saved-state
 * bundle, which is exactly what a config change (rotation) recreates, so it can't tell "still the
 * same process" apart from "still the same Activity instance." This can: it starts `false` when
 * the process starts and is only ever flipped to `true`, so the intro is a genuine cold-start
 * moment — once per process, never on a later recomposition or rotation.
 */
object NibSplashState {
    var shownThisProcess: Boolean = false
}

/** Bit-for-bit the `windowSplashScreenBackground` of `Theme.Nibhaus.Splash` (themes.xml) — using
 *  the same flat color (not a Navy gradient, which differs subtly by tone) guarantees the
 *  native→Compose handoff never flashes a different shade, let alone white. */
private val SplashNavy = Color(0xFF0B1630)

/** One diagonal step of the ink-gradient sweep [gradPanBrush] loops forever — same G1→G2→G3 tile,
 *  but driven by an explicit 0f..1f [progress] so [NibSplash] can play it exactly once across
 *  the wordmark instead of looping. */
private fun panningInkBrush(progress: Float, cellPx: Float): Brush {
    val panX = progress * cellPx
    return Brush.linearGradient(
        colorStops = arrayOf(0f to G1, 0.52f to G2, 1f to G3),
        start = Offset(panX, 0f),
        end = Offset(panX + cellPx, cellPx),
    )
}

// ── Welcome-splash scene palette (the design prototype's ambient colors) ─────────────────────────
private val SplashBgTop = Color(0xFF040812)
private val SplashBgMid = Color(0xFF081224)
private val SplashBgBot = Color(0xFF03060C)
private val AccentBlue = Color(0xFF6C7CFF)    // halo / glow accent
private val AccentViolet = Color(0xFFA05CFF)
private val SplashMuted = Color(0xFFA3B1CF)   // secondary text on the dark scene
private val SplashLine = Color(0x29C6CFDC)    // hairline borders (16% steel)
private val SplashGlass = Color(0xFF0A1428)   // glassy card ground (used translucent)

/** Choreography bounds: hold at least [MIN_SPLASH_MS] so the entrance sequence reads; never hold
 *  past [SPLASH_CAP_MS] (the design's own 10 s bound) even if a milestone never reports. */
private const val MIN_SPLASH_MS = 3_000L
private const val SPLASH_CAP_MS = 10_000L

/**
 * Welcome splash — the user's design prototype rebuilt in Compose: a deep-navy ambient scene
 * (slow-drifting brand-color glow), the logo cradle entering with breathing halos and a very slow
 * orbit ring, wordmark + tagline fading up, an ink swoosh drawing itself, three status chips
 * staggering in, and a progress bar whose fill tracks REAL startup milestones
 * ([StartupProgress], 0..4) — not a timer — while the status line names the phase in flight.
 *
 * Ends when all four milestones are in AND [MIN_SPLASH_MS] has elapsed; [SPLASH_CAP_MS] force-ends
 * (bar to 100%) regardless. Tap anywhere skips instantly. Reduced-motion is decided by the caller
 * (see [NibSplashState] / `InkApp`) but honored here too as a safety net — no ceremony at all.
 */
@Composable
fun NibSplash(onDone: () -> Unit) {
    if (rememberReducedMotion()) {
        LaunchedEffect(Unit) { onDone() }
        return
    }

    // Real startup milestones — the bar fills toward what has actually finished (see StartupProgress).
    val milestones by StartupProgress.milestoneCount.collectAsState()
    var minElapsed by remember { mutableStateOf(false) }
    var capped by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    fun finish() { if (!finished) { finished = true; onDone() } }

    val sceneAlpha = remember { Animatable(1f) }
    val sceneScale = remember { Animatable(1f) }

    LaunchedEffect(Unit) { delay(MIN_SPLASH_MS); minElapsed = true }
    LaunchedEffect(Unit) { delay(SPLASH_CAP_MS); capped = true }

    // The run is over when everything real reported in (or the cap hit) and the minimum has
    // elapsed; then the ready badge lands, and the whole scene lifts away into the app.
    val complete = milestones >= 4 || capped
    LaunchedEffect(complete, minElapsed) {
        if (complete && minElapsed && !finished) {
            delay(700)   // let the full bar + "handoff complete" badge land before lifting
            launch { sceneScale.animateTo(1.03f, tween(550, easing = NibEasing)) }
            sceneAlpha.animateTo(0f, tween(550, easing = NibEasing))
            finish()
        }
    }

    // Fill animates TOWARD the fraction of milestones actually complete; the cap forces 100%.
    val fill by animateFloatAsState(
        targetValue = if (capped) 1f else milestones / 4f,
        animationSpec = tween(900, easing = NibEasing),
        label = "splashFill",
    )
    // One pan of the ink gradient across the gradient-"haus" wordmark as it enters (kept from the V1 splash).
    val gradProgress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(450); gradProgress.animateTo(1f, tween(560, easing = NibEasing)) }

    val density = LocalDensity.current
    val cellPx = with(density) { 200.dp.toPx() }
    val vaultBrush = panningInkBrush(gradProgress.value, cellPx)

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = sceneAlpha.value
                scaleX = sceneScale.value
                scaleY = sceneScale.value
            }
            // Exact native-splash navy underneath the scene → no color flash on handoff.
            .background(SplashNavy)
            // Tap anywhere to skip straight to the app — no exit ceremony.
            .pointerInput(Unit) { detectTapGestures { finish() } },
        contentAlignment = Alignment.Center,
    ) {
        SplashAmbient(Modifier.matchParentSize())

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
        ) {
            // Logo cradle wrapped by the breathing halos + the slow orbit (drawn past bounds).
            Box(contentAlignment = Alignment.Center) {
                HaloOrbit(Modifier.matchParentSize())
                LogoCradle()
            }
            Spacer(Modifier.height(26.dp))
            Box(Modifier.fadeUp(delayMs = 450)) {
                // Splash ground is always deep-navy regardless of theme → force the light ink color.
                WordmarkText(fontSize = 40, inkColor = InkText, vaultBrush = vaultBrush)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Capture ideas with live ink, kept private on your device.",
                style = MaterialTheme.typography.bodyMedium.copy(color = SplashMuted),
                textAlign = TextAlign.Center,
                modifier = Modifier.fadeUp(delayMs = 680),
            )
            InkSwoosh(
                modifier = Modifier
                    .fillMaxWidth(0.86f)
                    .height(72.dp)
                    .padding(top = 14.dp)
                    .fadeUp(delayMs = 950),
                drawDelayMs = 1_100,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                StartupProgress.phaseLabels[milestones.coerceAtMost(3)].uppercase(),
                style = monoEyebrow.copy(color = SplashMuted, letterSpacing = 2.0.sp),
                modifier = Modifier.fadeUp(delayMs = 1_200),
            )
            Spacer(Modifier.height(12.dp))
            SplashProgressBar(
                fraction = fill,
                modifier = Modifier.fillMaxWidth(0.62f).fadeUp(delayMs = 1_250),
            )
            Spacer(Modifier.height(24.dp))
            // weight(1f) each so the three chips share the row width evenly. Without it, the row
            // measured the third chip with almost no width left, wrapping its text into a ~440dp-tall
            // sliver (off-screen on a phone) — which inflated the whole splash to full height and left
            // contentAlignment=Center nothing to center. Sharing the width keeps all three visible and
            // short, so the column wraps to its real content height and the scene centers (field
            // report, 2026-07-06). Top-align so uneven wrap counts don't misalign the chip tops.
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                SplashChip("Live Ink", "Pressure-aware capture", Modifier.weight(1f).fadeUp(delayMs = 950))
                SplashChip("Find Pen", "Proximity controls online", Modifier.weight(1f).fadeUp(delayMs = 1_200))
                SplashChip("Private and secure", "Encrypted workspace", Modifier.weight(1f).fadeUp(delayMs = 1_450))
            }
        }

        // The ready badge: appears once the run is complete, just before the scene lifts away.
        val badgeAlpha by animateFloatAsState(
            targetValue = if (complete && minElapsed) 1f else 0f,
            animationSpec = tween(500, easing = NibEasing),
            label = "splashBadge",
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 44.dp)
                .graphicsLayer { alpha = badgeAlpha }
                .clip(RoundedCornerShape(50))
                .background(SplashGlass.copy(alpha = 0.68f))
                .border(1.dp, SplashLine, RoundedCornerShape(50))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                "WORKSPACE HANDOFF COMPLETE",
                style = monoEyebrow.copy(color = InkText, letterSpacing = 1.6.sp),
            )
        }
    }
}

/** The design's `fadeUp` entrance (0.8 s, ease, `forwards`): alpha 0→1 + rise from 14 dp below,
 *  starting after [delayMs]. Splash-local cousin of [riseIn] with an absolute delay. */
@Composable
private fun Modifier.fadeUp(delayMs: Int, durMs: Int = 800): Modifier {
    val alpha = remember { Animatable(0f) }
    val ty = remember { Animatable(14f) }
    LaunchedEffect(Unit) {
        delay(delayMs.toLong())
        launch { alpha.animateTo(1f, tween(durMs, easing = NibEasing)) }
        ty.animateTo(0f, tween(durMs, easing = NibEasing))
    }
    val a = alpha.value
    val t = ty.value
    return this.graphicsLayer {
        this.alpha = a
        translationY = t.dp.toPx()
    }
}

/** Deep-navy vertical wash + three big blurred-looking brand-color glows drifting very slowly
 *  (the design's 12 s ambientShift, ping-ponged with Reverse). Radial-gradient circles read as
 *  blurred light without an actual blur pass. */
@Composable
private fun SplashAmbient(modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "splashAmbient")
    val drift by t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(12_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "drift",
    )
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(listOf(SplashBgTop, SplashBgMid, SplashBgBot)))
        val dx = (drift - 0.5f) * size.width * 0.08f
        val dy = (drift - 0.5f) * size.height * 0.05f
        fun glow(cx: Float, cy: Float, r: Float, c: Color, a: Float) {
            drawCircle(
                Brush.radialGradient(
                    listOf(c.copy(alpha = a), Color.Transparent),
                    center = Offset(cx, cy), radius = r,
                ),
                radius = r, center = Offset(cx, cy),
            )
        }
        glow(size.width * 0.20f + dx, size.height * 0.20f + dy, size.minDimension * 0.55f, G1, 0.20f)
        glow(size.width * 0.80f - dx, size.height * 0.22f + dy, size.minDimension * 0.48f, G3, 0.16f)
        glow(size.width * 0.52f + dx, size.height * 0.62f - dy, size.minDimension * 0.60f, G2, 0.12f)
    }
}

/** Three breathing halo rings (the 4 s haloPulse, phase-staggered via [StartOffset]) plus the very
 *  slow 18 s orbit ring carrying two glowing dots. Draws well past the logo box's bounds on
 *  purpose — Compose doesn't clip draw calls unless asked to, so the rings wrap the scene. */
@Composable
private fun HaloOrbit(modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "haloOrbit")
    // 0→1→0 triangle over 4 s = the design's scale/opacity breathe; three phases like h1/h2/h3.
    val spec = { offset: Int ->
        infiniteRepeatable<Float>(
            tween(2_000, easing = LinearEasing), RepeatMode.Reverse,
            initialStartOffset = StartOffset(offset),
        )
    }
    val b1 by t.animateFloat(0f, 1f, spec(0), label = "b1")
    val b2 by t.animateFloat(0f, 1f, spec(200), label = "b2")
    val b3 by t.animateFloat(0f, 1f, spec(450), label = "b3")
    val angle by t.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(18_000, easing = LinearEasing)),
        label = "orbit",
    )
    Canvas(modifier) {
        val c = Offset(size.width / 2f, size.height / 2f)
        fun ring(radius: Float, base: Float, b: Float) {
            val r = radius * (0.96f + 0.07f * b)
            val a = base * (0.16f + 0.78f * b)
            drawCircle(AccentBlue.copy(alpha = a), radius = r, center = c, style = Stroke(1.dp.toPx()))
            // a soft wide under-stroke doubles as the glow
            drawCircle(AccentBlue.copy(alpha = a * 0.25f), radius = r, center = c, style = Stroke(6.dp.toPx()))
        }
        ring(92.dp.toPx(), 0.84f, b1)
        ring(136.dp.toPx(), 0.42f, b2)
        ring(184.dp.toPx(), 0.18f, b3)
        // Orbit ring + two gradient-glow dots, one revolution every 18 s.
        val or = 204.dp.toPx()
        drawCircle(Color.White.copy(alpha = 0.08f), radius = or, center = c, style = Stroke(1.dp.toPx()))
        for ((phase, col) in listOf(-64f to AccentBlue, 128f to AccentViolet)) {
            val rad = Math.toRadians((angle + phase).toDouble())
            val p = Offset(c.x + (or * cos(rad)).toFloat(), c.y + (or * sin(rad)).toFloat())
            drawCircle(
                Brush.radialGradient(listOf(col, Color.Transparent), center = p, radius = 12.dp.toPx()),
                radius = 12.dp.toPx(), center = p,
            )
            drawCircle(col, radius = 4.dp.toPx(), center = p)
        }
    }
}

/** The glassy logo cradle: rises 28 dp and settles from 92% with a soft spring while fading in
 *  (the design's 1.05 s logoIn at a 0.15 s delay). Layout size is its final size — the halos in
 *  [HaloOrbit] center on where it lands, and it rises into them. */
@Composable
private fun LogoCradle() {
    val alpha = remember { Animatable(0f) }
    val ty = remember { Animatable(28f) }
    val scale = remember { Animatable(0.92f) }
    LaunchedEffect(Unit) {
        delay(150)
        launch { alpha.animateTo(1f, tween(600, easing = NibEasing)) }
        launch { ty.animateTo(0f, tween(1_050, easing = NibEasing)) }
        scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow))
    }
    Box(
        Modifier
            .graphicsLayer {
                this.alpha = alpha.value
                translationY = ty.value.dp.toPx()
                scaleX = scale.value
                scaleY = scale.value
            }
            .size(140.dp)
            .clip(RoundedCornerShape(34.dp))
            .background(SplashGlass.copy(alpha = 0.72f))
            .border(1.dp, SplashLine, RoundedCornerShape(34.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.brand_logo_dark),
            contentDescription = null,
            modifier = Modifier.size(100.dp),
        )
    }
}

/** The ink swoosh that draws itself (the design's 2.5 s stroke-dashoffset draw) — done the Compose
 *  way: [PathMeasure] carves a growing partial segment of the full curve each frame. Geometry is
 *  the design's swoosh, normalized from its 760×90 viewBox. */
@Composable
private fun InkSwoosh(modifier: Modifier, drawDelayMs: Int) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(drawDelayMs.toLong())
        progress.animateTo(1f, tween(2_500, easing = NibEasing))
    }
    Canvas(modifier) {
        if (progress.value <= 0f) return@Canvas
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(0.016f * w, 0.53f * h)
            cubicTo(0.100f * w, 0.11f * h, 0.184f * w, 0.80f * h, 0.263f * w, 0.44f * h)
            cubicTo(0.342f * w, 0.09f * h, 0.418f * w, 0.22f * h, 0.503f * w, 0.50f * h)
            cubicTo(0.576f * w, 0.73f * h, 0.639f * w, 0.71f * h, 0.721f * w, 0.40f * h)
            cubicTo(0.805f * w, 0.09f * h, 0.889f * w, 0.27f * h, 0.979f * w, 0.53f * h)
        }
        val pm = PathMeasure().apply { setPath(path, false) }
        val partial = Path()
        pm.getSegment(0f, pm.length * progress.value, partial, true)
        drawPath(
            partial,
            brush = Brush.linearGradient(
                listOf(G1, AccentBlue, AccentViolet),
                start = Offset(0f, h / 2f), end = Offset(w, h / 2f),
            ),
            style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/** Track + gradient fill + the looping 2.1 s shine sweep. [fraction] 0..1 is the smoothed REAL
 *  milestone fraction from the caller — this composable just renders it. */
@Composable
private fun SplashProgressBar(fraction: Float, modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "splashShine")
    val shineX by t.animateFloat(
        initialValue = -0.4f, targetValue = 1.4f,
        animationSpec = infiniteRepeatable(tween(2_100, easing = LinearEasing)),
        label = "sweep",
    )
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, SplashLine, RoundedCornerShape(50))
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.05f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(50))
                    .background(Brush.horizontalGradient(listOf(G1, AccentBlue, G3))),
            )
            Canvas(Modifier.matchParentSize()) {
                val band = size.width * 0.24f
                val x = shineX * size.width
                drawRect(
                    Brush.linearGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = 0.45f), Color.Transparent),
                        start = Offset(x, 0f), end = Offset(x + band, size.height),
                    ),
                    topLeft = Offset(x, 0f),
                    size = Size(band, size.height),
                )
            }
        }
    }
}

/** Glassy status chip (title + sub) — the design's status-chip card, sized to sit three-up. */
@Composable
private fun SplashChip(title: String, sub: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(SplashGlass.copy(alpha = 0.62f))
            .border(1.dp, SplashLine, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodySmall.copy(
                color = InkText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
            ),
        )
        Spacer(Modifier.height(3.dp))
        Text(sub, style = MaterialTheme.typography.bodySmall.copy(color = SplashMuted, fontSize = 11.sp))
    }
}
