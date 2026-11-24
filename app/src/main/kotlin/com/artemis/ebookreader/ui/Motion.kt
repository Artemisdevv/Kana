package com.artemis.ebookreader.ui

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.unit.IntOffset

internal object Motion {
    const val Feedback = 120
    const val Panel = 180
    const val Content = 220
    const val Navigation = 260

    private val standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val enter = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val exit = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    fun <T> standard(duration: Int = Content) = tween<T>(duration, easing = standard)

    fun <T> placement() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

    fun fadeThrough(duration: Int = Content): ContentTransform =
        fadeIn(tween(duration - Feedback / 2, delayMillis = Feedback / 2, easing = enter))
            .togetherWith(fadeOut(tween(Feedback + Feedback / 2, easing = exit)))

    fun push(forward: Boolean): ContentTransform {
        val direction = if (forward) 1 else -1
        val slide = standard<IntOffset>(Navigation)
        return slideInHorizontally(slide) { width -> direction * width }
            .togetherWith(slideOutHorizontally(slide) { width -> -direction * width })
    }
}

/** Outgoing content can still be drawn while its actions are already inactive. */
internal fun Modifier.transitionInput(active: Boolean): Modifier =
    if (active) this else clearAndSetSemantics { hideFromAccessibility() }
        .focusProperties { canFocus = false }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }
