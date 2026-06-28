package com.tadmor.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tadmor.app.ui.theme.ExoTheme
import kotlin.math.ceil

// Matches PullToRefresh's indicator spin period so the two icons feel continuous
// when the pull-to-refresh hands off to the button.
private const val SPIN_PERIOD_MS = 800

/**
 * Regenerate/refresh button — 44dp circle with a circular-arrow icon.
 * Spins continuously while [isRefreshing] is true. Starts from zero (no
 * phase jump) and decelerates to the next full rotation when stopping.
 */
@Composable
fun RegenerateButton(
    onClick: () -> Unit,
    isRefreshing: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = ExoTheme.colors

    // Manual Animatable instead of rememberInfiniteTransition so the spin
    // starts at 0 (not whatever phase a persistent transition drifted to)
    // and can smoothly decelerate on stop rather than snapping back.
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            // Ease-in: accelerate from rest to the linear spin rate over one rotation.
            rotation.animateTo(
                rotation.value + 360f,
                tween(SPIN_PERIOD_MS, easing = FastOutLinearInEasing),
            )
            while (true) {
                rotation.animateTo(
                    rotation.value + 360f,
                    tween(SPIN_PERIOD_MS, easing = LinearEasing),
                )
            }
        } else if (rotation.value % 360f != 0f) {
            val target = ceil(rotation.value / 360f) * 360f
            rotation.animateTo(target, tween(400, easing = LinearOutSlowInEasing))
            rotation.snapTo(0f)
        }
    }

    // Outer Box owns the bg, border, clip, and ripple — these stay fixed
    // while the inner Box rotates the icon. If the ripple lived inside the
    // rotating layer the expanding circle would spin along with the arrow
    // while refreshing, which looks wrong for a tap effect.
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(colors.surfaceRaised)
            .touchRipple(
                color = Color.White,
                startAlpha = 0.22f,
                enabled = !isRefreshing,
                onClick = onClick,
            )
            .border(1.dp, colors.divider, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .rotate(rotation.value),
            contentAlignment = Alignment.Center,
        ) {
            RefreshArcIcon(
                modifier = Modifier.size(20.dp),
                sweep = 270f,
            )
        }
    }
}
