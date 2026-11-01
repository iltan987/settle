package com.iltan.settle.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/** Tidepool's motion notes as named specs; everything else uses `MotionScheme.expressive()`. */
object SettleMotion {
    /** Grid density after a pinch (stiffness 400, damping 0.85). */
    fun <T> density(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.85f, stiffness = 400f)

    /** Sheets, cards and the status chip changing size (stiffness 500, damping 1). */
    fun <T> settle(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 500f)

    /** A cell going into or out of the selection (stiffness 600, damping 0.8). */
    fun <T> select(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = 600f)

    /** Bars and markers cross-fading in place (150 ms). */
    fun <T> fade(): FiniteAnimationSpec<T> = tween(durationMillis = 150)
}
