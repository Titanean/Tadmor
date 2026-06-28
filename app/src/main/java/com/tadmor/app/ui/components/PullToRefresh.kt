package com.tadmor.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.tadmor.app.ui.util.Haptics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.tadmor.app.ui.theme.ExoTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val TRIGGER_DISTANCE = 80.dp
private val REFRESH_REST = 48.dp

// Matches the RegenerateButton's linear spin so the two icons feel continuous
// when the pull indicator transitions into the refreshing state.
private const val SPIN_PERIOD_MS = 800

/**
 * Custom pull-to-refresh wrapper. Shows a circular arrow indicator
 * when the user overscrolls at the top. Triggers [onRefresh] when released
 * past the threshold. Shows a spinning indicator while [isRefreshing].
 */
@Composable
fun PullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    /** Unix millis of the last successful catalog sync, or 0 if there's no
     *  local cache yet. When non-zero, a small "Last sync: …" label fades
     *  in beneath the refresh icon during the pull and fades out when the
     *  refresh commits. 0 suppresses the label entirely so first-launch
     *  users (who don't have any synced data to reference) aren't shown
     *  a "Last sync: 1 Jan 1970" placeholder. */
    lastSyncedAtMillis: Long = 0L,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val triggerPx = with(density) { TRIGGER_DISTANCE.toPx() }
    val restPx = with(density) { REFRESH_REST.toPx() }

    // Content translation, driven synchronously during drag and animated on
    // release. Alpha fades in with pull progress and fades out when the refresh
    // finishes.
    val offsetAnim = remember { Animatable(0f) }
    val alphaAnim = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val connection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                // When pulling back up while overscrolled, consume scroll to reduce offset
                if (offsetAnim.value > 0f && available.y < 0f) {
                    val consumed = available.y.coerceAtLeast(-offsetAnim.value)
                    val newOffset = (offsetAnim.value + consumed).coerceAtLeast(0f)
                    scope.launch {
                        offsetAnim.snapTo(newOffset)
                        if (!isRefreshing) {
                            alphaAnim.snapTo((newOffset / triggerPx).coerceIn(0f, 1f))
                        }
                    }
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                // Only allow pull-to-refresh on direct drag at the top of the list,
                // not during flings which can briefly overscroll mid-scroll.
                if (available.y > 0f && !isRefreshing && source == NestedScrollSource.UserInput) {
                    val newOffset = (offsetAnim.value + available.y * 0.5f)
                        .coerceAtMost(triggerPx * 1.5f)
                    scope.launch {
                        offsetAnim.snapTo(newOffset)
                        alphaAnim.snapTo((newOffset / triggerPx).coerceIn(0f, 1f))
                    }
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                // Don't block the fling — launch animations on the outer scope and
                // return immediately so normal list flings aren't stalled. Also skip
                // entirely when there's no overscroll offset to reconcile.
                if (offsetAnim.value <= 0f || isRefreshing) return Velocity.Zero

                if (offsetAnim.value >= triggerPx) {
                    // Past trigger: kick off the refresh and smoothly settle the
                    // indicator into its resting position. Alpha held at 1.
                    // Short, light haptic to confirm the commit — goes through
                    // the Vibrator-backed Haptics helper because Compose's
                    // TextHandleMove was imperceptible/silent on real devices.
                    Haptics.light(context)
                    onRefresh()
                    scope.launch {
                        offsetAnim.animateTo(
                            restPx,
                            tween(280, easing = FastOutSlowInEasing),
                        )
                    }
                    scope.launch {
                        alphaAnim.animateTo(1f, tween(150, easing = LinearEasing))
                    }
                } else {
                    // Released below threshold: retreat smoothly from release point.
                    scope.launch {
                        offsetAnim.animateTo(
                            0f,
                            tween(300, easing = FastOutSlowInEasing),
                        )
                    }
                    scope.launch {
                        alphaAnim.animateTo(
                            0f,
                            tween(300, easing = FastOutSlowInEasing),
                        )
                    }
                }
                return Velocity.Zero
            }
        }
    }

    // Exit animation: when refresh completes, scroll the page back up and fade
    // the indicator out together so their timings stay in sync.
    LaunchedEffect(isRefreshing) {
        if (!isRefreshing && offsetAnim.value > 0f) {
            coroutineScope {
                launch {
                    offsetAnim.animateTo(
                        0f,
                        tween(400, easing = FastOutSlowInEasing),
                    )
                }
                launch {
                    alphaAnim.animateTo(
                        0f,
                        tween(400, easing = LinearOutSlowInEasing),
                    )
                }
            }
        }
    }

    val progress = (offsetAnim.value / triggerPx).coerceIn(0f, 1f)
    val indicatorAlpha = alphaAnim.value
    val lastSyncLabel = formatLastSync(lastSyncedAtMillis)
    // Show the label whenever a pull is targeting a non-zero offset (i.e.
    // the indicator is on its way in or in place). Using `targetValue`
    // rather than `value` means the fade-out triggers the instant the
    // release animation starts, not when the offset finishes settling at
    // 0 — keeps the label in sync with the indicator's exit instead of
    // lagging behind it. Refresh-in-progress also drops the label.
    val showLabel = lastSyncLabel != null &&
        !isRefreshing &&
        offsetAnim.targetValue > 0f

    Box(modifier = modifier.nestedScroll(connection)) {
        Box(modifier = Modifier.offset { IntOffset(0, offsetAnim.value.roundToInt()) }) {
            content()
        }

        if (indicatorAlpha > 0f || isRefreshing) {
            RefreshIndicator(
                progress = if (isRefreshing) 1f else progress,
                isRefreshing = isRefreshing,
                alpha = indicatorAlpha,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset {
                        IntOffset(
                            0,
                            (offsetAnim.value * 0.5f - with(density) { 20.dp.toPx() })
                                .roundToInt(),
                        )
                    },
            )
        }

        // "Last sync" label sits beneath the refresh icon. Fades in with
        // a real 220 ms animation rather than snap-tracking the pull
        // progress (which read as no-animation on fast pulls).
        // Suppressed entirely on first launch (no cached data → null).
        if (lastSyncLabel != null) {
            val colors = ExoTheme.colors
            val type = ExoTheme.type
            AnimatedVisibility(
                visible = showLabel,
                enter = fadeIn(tween(220, easing = FastOutSlowInEasing)),
                exit = fadeOut(tween(220, easing = FastOutSlowInEasing)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset {
                        IntOffset(
                            0,
                            (offsetAnim.value * 0.5f + with(density) { 18.dp.toPx() })
                                .roundToInt(),
                        )
                    },
            ) {
                BasicText(
                    text = lastSyncLabel,
                    style = type.bodyMedium.copy(color = colors.textTertiary),
                )
            }
        }
    }
}

@Composable
private fun RefreshIndicator(
    progress: Float,
    isRefreshing: Boolean,
    alpha: Float,
    modifier: Modifier = Modifier,
) {
    // Continuous spin while refreshing, driven by a manual Animatable so the
    // start/stop transitions are smooth rather than snapping to whatever phase
    // a persistent infiniteTransition happens to be in.
    val spin = remember { Animatable(0f) }
    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            while (true) {
                spin.animateTo(
                    spin.value + 360f,
                    tween(SPIN_PERIOD_MS, easing = LinearEasing),
                )
            }
        } else {
            spin.snapTo(0f)
        }
    }

    // Static offset so the indicator's arrow/gap orientation matches the
    // refresh button. During pull the sweep grows in place (no progress-driven
    // rotation) so the arrow isn't whipping around while the tail draws in.
    val rotation = (if (isRefreshing) spin.value else 0f) + 0f
    val sweep = if (isRefreshing) 270f else progress * 270f

    RefreshArcIcon(
        modifier = modifier
            .size(28.dp)
            .alpha(alpha)
            .rotate(rotation),
        sweep = sweep,
    )
}
