package io.github.lycheeappf.tmm.ui.component

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically

/**
 * Expressive Motion-Tokens auf Basis stabiler Compose-Springs.
 *
 * Hintergrund: M3 1.4.0 hält `MotionScheme`/`MaterialExpressiveTheme` noch
 * `internal` (Alpha-Track), darum definieren wir die Spring-Specs selbst — exakt
 * das, was eine MotionScheme intern auch tut. So bleibt die App auf stabilen APIs
 * und fühlt sich trotzdem "expressive" an (leicht federnde räumliche Bewegung,
 * straffe Effekt-Übergänge ohne Bounce).
 *
 * Verwendung in Screens/Komponenten:
 *  - Aufklappen: `Modifier.animateContentSize(MfsMotion.spatial())`
 *  - Ein-/Ausblenden: `AnimatedVisibility(v, enter = mfsExpandEnter(), exit = mfsExpandExit())`
 *  - Fortschritt/Slider-Wert: `animateFloatAsState(target, MfsMotion.spatial())`
 *  - Status-Container-Farbe: `animateColorAsState(target, MfsMotion.effects())`
 *  - Tab-/Label-Wechsel: `Crossfade`/`AnimatedContent` (Default-Specs ok)
 */
object MfsMotion {
    /** Räumliche Bewegung (Grösse/Position) — leicht federnd. */
    fun <T> spatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.82f, stiffness = 380f)

    /** Schnellere räumliche Bewegung für kleine Elemente. */
    fun <T> spatialFast(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.9f, stiffness = 700f)

    /** Effekt-Übergänge (Alpha/Farbe) — straff, ohne Bounce. */
    fun <T> effects(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 1400f)
}

/** Standard-Enter für Banner/Resultate: einblenden + vertikal aufklappen. */
fun mfsExpandEnter(): EnterTransition =
    fadeIn(MfsMotion.effects()) + expandVertically(MfsMotion.spatial())

/** Standard-Exit für Banner/Resultate: ausblenden + vertikal einklappen. */
fun mfsExpandExit(): ExitTransition =
    fadeOut(MfsMotion.effects()) + shrinkVertically(MfsMotion.spatial())
