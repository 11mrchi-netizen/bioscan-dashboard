package com.bioscan.fieldterminal.ui.components

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith

// Polish brief, motion (contract section 12; tokens.css --ft-motion-*). Three durations and one easing for the whole
// app: 120 ms for small state changes (a chip, a press released), 180 ms for a tab or sub-tab switch, an expanding row
// and a chart selection, 240 ms for a pushed page, an overlay or a sheet. Moves are a fade plus a short directional
// slide (a fifth of the width at most), never a bounce. When the system's animator scale is 0 (Settings ›
// Accessibility › Remove animations) every transition here snaps: [FTMotion.reduced] is read once per process.
object FTMotion {
    const val FAST = 120
    const val BASE = 180
    const val SLOW = 240

    @Volatile
    var reduced: Boolean = false
        private set

    /** Read the system's animation setting (called once from the activity; tests can force it). */
    fun init(context: Context) {
        reduced = runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }

    fun forceReduced(value: Boolean) { reduced = value }

    fun <T> tweenFast(): FiniteAnimationSpec<T> = if (reduced) snap() else tween(FAST, easing = FastOutSlowInEasing)
    fun <T> tweenBase(): FiniteAnimationSpec<T> = if (reduced) snap() else tween(BASE, easing = FastOutSlowInEasing)
    fun <T> tweenSlow(): FiniteAnimationSpec<T> = if (reduced) snap() else tween(SLOW, easing = FastOutSlowInEasing)

    // ---- pushed pages (NavHost): in from the right, back out to the right ----
    fun pushEnter(): EnterTransition = if (reduced) EnterTransition.None else
        fadeIn(tween(SLOW, easing = LinearOutSlowInEasing)) + slideInHorizontally(tween(SLOW, easing = FastOutSlowInEasing)) { it / 6 }
    fun pushExit(): ExitTransition = if (reduced) ExitTransition.None else fadeOut(tween(BASE))
    fun popEnter(): EnterTransition = if (reduced) EnterTransition.None else fadeIn(tween(BASE))
    fun popExit(): ExitTransition = if (reduced) ExitTransition.None else
        fadeOut(tween(BASE)) + slideOutHorizontally(tween(SLOW, easing = FastOutSlowInEasing)) { it / 6 }

    // ---- tabs (the dock): a crossfade, no direction (tabs are peers) ----
    fun tabEnter(): EnterTransition = if (reduced) EnterTransition.None else fadeIn(tween(BASE))
    fun tabExit(): ExitTransition = if (reduced) ExitTransition.None else fadeOut(tween(FAST))

    // ---- overlays and sheets: a fade (the sheet's own slide is Material's) ----
    fun overlayEnter(): EnterTransition = if (reduced) EnterTransition.None else fadeIn(tween(SLOW))
    fun overlayExit(): ExitTransition = if (reduced) ExitTransition.None else fadeOut(tween(BASE))

    /** Sub-tabs inside a page: a short slide toward the newer tab's side, with a fade. */
    fun <S> subTab(scope: AnimatedContentTransitionScope<S>, forward: Boolean): ContentTransform =
        if (reduced) (EnterTransition.None togetherWith ExitTransition.None)
        else with(scope) {
            val d = if (forward) 1 else -1
            (fadeIn(tween(BASE)) + slideInHorizontally(tween(BASE, easing = FastOutSlowInEasing)) { d * it / 12 }) togetherWith
                fadeOut(tween(FAST))
        }
}

