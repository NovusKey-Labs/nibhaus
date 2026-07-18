package com.nibhaus.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.nibhaus.ui.NibEasing
import com.nibhaus.ui.rememberReducedMotion
import kotlin.math.roundToInt

/**
 * an integer stat (page / stroke / notebook / pending counts) that counts up from 0 on
 * first composition — and re-counts smoothly if [target] changes later — instead of snapping
 * straight to a static number. Fast (~400ms), design-system [NibEasing]; reduced-motion users get
 * the plain value with no animation.
 */
@Composable
fun rememberCountUp(target: Int): Int {
    val reduced = rememberReducedMotion()
    val anim = remember { Animatable(0f) }
    LaunchedEffect(target, reduced) {
        if (reduced) anim.snapTo(target.toFloat()) else anim.animateTo(target.toFloat(), tween(400, easing = NibEasing))
    }
    return anim.value.roundToInt()
}
