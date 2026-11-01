package com.iltan.settle.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * Tidepool's motion table from the handoff sheet; everything else uses `MotionScheme.expressive()`.
 * With "Remove animations" on, Android scales animator durations to zero, which Compose follows.
 */
object SettleMotion {
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** A cell's inset when it is selected (spring 600, 0.8). */
    fun <T> select(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = 600f)

    /** Grid density after a pinch (spring 400, 0.85). */
    fun <T> density(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.85f, stiffness = 400f)

    /** Viewer paging, the next item after a delete, sheets settling (spring 500, 1.0). */
    fun <T> settle(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 500f)

    /** The favourite pop to 1.2× (spring 800, 0.5). */
    fun <T> pop(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.5f, stiffness = 800f)

    /** The welcome art's tiles settling 8 dp (spring 300, 0.9). */
    fun <T> drift(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.9f, stiffness = 300f)

    /** Check rings, bars swapping, chrome showing and hiding, the fast scroller (150 ms). */
    fun <T> fade(): FiniteAnimationSpec<T> = tween(150, easing = Emphasized)

    /** Opening and closing the viewer from a cell (300 ms emphasized). */
    fun <T> sharedElement(): FiniteAnimationSpec<T> = tween(300, easing = Emphasized)

    /** A snackbar coming in (250 ms) and going out (150 ms). */
    fun <T> snackbarIn(): FiniteAnimationSpec<T> = tween(250, easing = EmphasizedDecelerate)

    fun <T> snackbarOut(): FiniteAnimationSpec<T> = tween(150, easing = Emphasized)

    /** A deleted item leaving, a verified badge fading (200 ms). */
    fun <T> leave(): FiniteAnimationSpec<T> = tween(200, easing = Emphasized)

    /** A thumbnail fading in once decoded (120 ms). */
    fun <T> decode(): FiniteAnimationSpec<T> = tween(120, easing = LinearEasing)

    /** How long Undo stays offered. */
    const val UNDO_MILLIS = 10_000L
}
