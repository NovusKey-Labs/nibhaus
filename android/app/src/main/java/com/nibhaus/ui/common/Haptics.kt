package com.nibhaus.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Feature 20a: a tiny wrapper around [LocalHapticFeedback] for Nibhaus's handful of confirm/
 * segment-tick moments — pen connect, the zone-share chooser appearing, a bookmark toggle landing,
 * a saved-pen tile flipping to READY. Deliberately narrow (two calls, both subtle system feedback
 * types) — this is never [HapticFeedbackType.Reject] or another "something went wrong" buzz.
 */
class Haptics internal constructor(private val feedback: HapticFeedback) {
    /** A decisive confirm tick — the pen connects, a chooser sheet appears. */
    fun confirm() = feedback.performHapticFeedback(HapticFeedbackType.Confirm)

    /** A light segment tick — a toggle lands (bookmark on/off, a saved pen going READY). */
    fun tick() = feedback.performHapticFeedback(HapticFeedbackType.SegmentTick)
}

@Composable
fun rememberHaptics(): Haptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { Haptics(feedback) }
}
