package com.tadmor.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Adds a "press and hold" shrink to a tappable element: while the pointer is
 * down, the element scales to [pressedScale]; on release (or cancel) it
 * springs back to 1.0. Replaces the default Compose ripple indication for
 * controls that should feel physical rather than flat-highlighted.
 *
 * The release always waits for the press-in animation to complete first, so
 * a fast tap still shows the full push-in → push-out cycle. Without this
 * sequencing, a quick release cancels the in-flight shrink and the user
 * sees either nothing or a tiny wobble.
 *
 * Typical usage pairs this with `clickable(indication = null, ...)` so the
 * underlying interaction source drives only the scale effect.
 */
fun Modifier.pushOnPress(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.94f,
    pressDurationMs: Int = 90,
    releaseDurationMs: Int = 160,
): Modifier = composed {
    val scale = remember { Animatable(1f) }
    LaunchedEffect(interactionSource) {
        var pressJob: Job? = null
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    pressJob = launch {
                        scale.animateTo(
                            pressedScale,
                            tween(pressDurationMs, easing = FastOutSlowInEasing),
                        )
                    }
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    launch {
                        pressJob?.join()
                        scale.animateTo(
                            1f,
                            tween(releaseDurationMs, easing = FastOutSlowInEasing),
                        )
                    }
                }
            }
        }
    }
    graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}
