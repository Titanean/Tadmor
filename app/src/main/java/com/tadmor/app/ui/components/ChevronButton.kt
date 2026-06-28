package com.tadmor.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.tadmor.app.ui.theme.ExoTheme
import kotlinx.coroutines.launch

/**
 * Cycling-style chevron button used by the COMPARE sections on the
 * planet and star detail pages. Up chevron points up and bounces upward
 * on tap; down chevron points down and bounces downward — the movement
 * direction follows the arrow.
 *
 * No ripple or background; just the chevron silhouette + bounce tween.
 * Single source of truth so the planet-page Solar System cycle and the
 * star-page host-star cycle share visual + interaction conventions.
 */
@Composable
fun ChevronButton(up: Boolean, onClick: () -> Unit) {
    val colors = ExoTheme.colors
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    // Bounce animation on tap: the chevron hops ~10dp in the direction it
    // points (up chevron → -10dp, down chevron → +10dp), fast (120 ms) then
    // eases back to rest (260 ms). The bounce direction matches the arrow so
    // the movement reads as "follow the arrow".
    val bouncePx = remember { Animatable(0f) }
    val bounceTarget = with(density) { (if (up) -10.dp else 10.dp).toPx() }
    Box(
        modifier = Modifier
            .size(32.dp)
            .graphicsLayer { translationY = bouncePx.value }
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = {
                    scope.launch {
                        bouncePx.animateTo(bounceTarget, tween(120, easing = FastOutSlowInEasing))
                        bouncePx.animateTo(0f, tween(260, easing = FastOutSlowInEasing))
                    }
                    onClick()
                },
            )
            .drawBehind {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val armW = 6.dp.toPx()
                val armH = 4.dp.toPx()
                val strokeW = 1.2.dp.toPx()
                if (up) {
                    drawLine(
                        color = colors.textSecondary,
                        start = Offset(cx - armW, cy + armH / 2f),
                        end = Offset(cx, cy - armH / 2f),
                        strokeWidth = strokeW,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = colors.textSecondary,
                        start = Offset(cx, cy - armH / 2f),
                        end = Offset(cx + armW, cy + armH / 2f),
                        strokeWidth = strokeW,
                        cap = StrokeCap.Round,
                    )
                } else {
                    drawLine(
                        color = colors.textSecondary,
                        start = Offset(cx - armW, cy - armH / 2f),
                        end = Offset(cx, cy + armH / 2f),
                        strokeWidth = strokeW,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = colors.textSecondary,
                        start = Offset(cx, cy + armH / 2f),
                        end = Offset(cx + armW, cy - armH / 2f),
                        strokeWidth = strokeW,
                        cap = StrokeCap.Round,
                    )
                }
            },
    )
}
