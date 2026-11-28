package com.sxvuj.streaksaver.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.using
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Motion tokens for the whole app. The springs follow the Material 3 Expressive motion scheme:
 * "spatial" springs move and resize things and may overshoot a little, "effects" springs fade and
 * recolor and never overshoot. Springs are interruptible, so a tap in the middle of an animation
 * redirects it smoothly instead of restarting it.
 */
object Motion {
    fun <T> fastSpatial(visibilityThreshold: T? = null) =
        spring(dampingRatio = 0.6f, stiffness = 800f, visibilityThreshold = visibilityThreshold)

    fun <T> defaultSpatial(visibilityThreshold: T? = null) =
        spring(dampingRatio = 0.8f, stiffness = 380f, visibilityThreshold = visibilityThreshold)

    fun <T> slowSpatial(visibilityThreshold: T? = null) =
        spring(dampingRatio = 0.8f, stiffness = 200f, visibilityThreshold = visibilityThreshold)

    fun <T> fastEffects() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 3800f)

    fun <T> defaultEffects() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 1600f)

    fun <T> slowEffects() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 800f)

    /** A visibly bouncy spring for small playful pops (nav icons, a completed gauge). */
    fun <T> bouncy() = spring<T>(dampingRatio = 0.45f, stiffness = 500f)

    /** Material 3 emphasized easing curves, for the few places a fixed duration reads better. */
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    val sizeSpec: FiniteAnimationSpec<IntSize> = defaultSpatial(IntSize.VisibilityThreshold)
    val offsetSpec: FiniteAnimationSpec<IntOffset> = defaultSpatial(IntOffset.VisibilityThreshold)
}

// --- Screen transitions --------------------------------------------------------------------------

/** Material "fade through": used between bottom bar tabs, which are not related to each other. */
fun fadeThrough(): ContentTransform =
    (fadeIn(tween(220, delayMillis = 90, easing = Motion.EmphasizedDecelerate)) +
        scaleIn(tween(320, delayMillis = 90, easing = Motion.EmphasizedDecelerate), initialScale = 0.94f))
        .togetherWith(fadeOut(tween(90, easing = Motion.EmphasizedAccelerate)))
        .using(SizeTransform(clip = false))

/** Material "shared axis X": used to go one level deeper (forward) and back again. */
fun sharedAxisX(forward: Boolean): ContentTransform {
    val sign = if (forward) 1 else -1
    return (slideInHorizontally(Motion.offsetSpec) { sign * it / 4 } + fadeIn(tween(220, delayMillis = 60))) togetherWith
        (slideOutHorizontally(Motion.offsetSpec) { -sign * it / 4 } + fadeOut(tween(90)))
}

/** For a number or short label that changes in place: new value rolls in from below (or above when it went down). */
fun <S> AnimatedContentTransitionScope<S>.rollingText(up: Boolean = true): ContentTransform {
    val sign = if (up) 1 else -1
    return (slideInVertically(Motion.offsetSpec) { sign * it / 2 } + fadeIn(Motion.defaultEffects())) togetherWith
        (slideOutVertically(Motion.offsetSpec) { -sign * it / 2 } + fadeOut(Motion.fastEffects())) using
        SizeTransform(clip = false) { _, _ -> Motion.sizeSpec }
}

/** Crossfade with a small scale, for swapping one block of content for another of a different size. */
fun <S> AnimatedContentTransitionScope<S>.softSwap(): ContentTransform =
    (fadeIn(tween(220, delayMillis = 60)) + scaleIn(Motion.defaultSpatial(), initialScale = 0.96f)) togetherWith
        (fadeOut(tween(90)) + scaleOut(Motion.fastEffects(), targetScale = 0.98f)) using
        SizeTransform(clip = false) { _, _ -> Motion.sizeSpec }

// --- Modifiers -----------------------------------------------------------------------------------

/**
 * Shrinks the element slightly while it is pressed and springs it back on release, the squishy
 * press feedback Google apps use on cards and buttons. Pass the same interaction source that the
 * clickable or button uses.
 */
fun Modifier.pressScale(interactionSource: InteractionSource, pressedScale: Float = 0.96f): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = if (pressed) Motion.fastEffects() else Motion.bouncy(),
        label = "pressScale",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** Remembers which items already played their entrance, so scrolling back does not replay it. */
@Stable
class EntranceTracker {
    internal val played: MutableSet<Any> = HashSet()
}

@Composable
fun rememberEntrance(): EntranceTracker = remember { EntranceTracker() }

/**
 * Staggered entrance: the element fades in, rises and grows into place, a little after the one
 * before it. Everything runs in the draw phase, so it never causes extra recompositions.
 */
fun Modifier.entrance(index: Int, tracker: EntranceTracker? = null, key: Any = index): Modifier = composed {
    val already = tracker != null && key in tracker.played
    val progress = remember { Animatable(if (already) 1f else 0f) }
    val rise = with(LocalDensity.current) { 32.dp.toPx() }
    LaunchedEffect(Unit) {
        if (progress.value < 1f) {
            tracker?.played?.add(key)
            delay(index.coerceIn(0, 8) * 40L)
            progress.animateTo(1f, Motion.slowSpatial())
        }
    }
    graphicsLayer {
        val p = progress.value
        alpha = (p * 1.6f).coerceIn(0f, 1f)
        translationY = (1f - p) * rise
        val s = 0.92f + 0.08f * p
        scaleX = s
        scaleY = s
    }
}

/** Stable, unique LazyColumn keys for a list that might contain repeats ("a", "a" becomes "a#0", "a#1"). */
fun <T> uniqueKeys(items: List<T>, keyOf: (T) -> Any): List<String> {
    val seen = HashMap<Any, Int>()
    return items.map { item ->
        val base = keyOf(item)
        val n = seen[base] ?: 0
        seen[base] = n + 1
        "$base#$n"
    }
}
