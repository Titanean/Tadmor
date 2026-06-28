package com.tadmor.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.hypot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A single in-flight ripple. Radius grows from 0 to the far-corner distance
 * with a decelerating curve (fast → slow) while alpha hangs at its initial
 * value, then fades linearly to 0 once the pointer lifts.
 */
private data class RippleEntry(
    val center: Offset,
    val radius: Animatable<Float, *>,
    val alpha: Animatable<Float, *>,
)

/**
 * Standard Android-style ink ripple on press. Originates at the touch point,
 * expands fast-then-slow to cover the element, and fades out after release.
 * Intended for primary action buttons where tactile feedback matters more
 * than the `pushOnPress` scale effect — pairs cleanly with a custom bg.
 *
 * The element using this modifier should be pre-clipped (e.g. `.clip(shape)`
 * before `.touchRipple(...)`) so the ripple doesn't bleed outside the
 * visible bounds.
 *
 * @param color Ripple fill colour. Default white reads well on dark/accent
 *   backgrounds; pass a dark colour for light-bg buttons.
 * @param startAlpha Starting opacity of the ripple overlay (0..1).
 * @param expandDurationMs Duration of the radius animation.
 * @param fadeDurationMs Duration of the alpha fade-out after release.
 * @param enabled When false, the modifier is a no-op — no ripple, no click.
 *   Use this instead of branching the modifier chain so the composable tree
 *   stays stable across enabled/disabled states.
 */
fun Modifier.touchRipple(
    color: Color = Color.White,
    startAlpha: Float = 0.22f,
    expandDurationMs: Int = 420,
    fadeDurationMs: Int = 260,
    enabled: Boolean = true,
    /**
     * Whether to dismiss the soft keyboard (clear the active focus owner)
     * before invoking [onClick]. Default `true`: tapping any ripple-driven
     * button — planet card, filter chip, back arrow, etc. — closes the
     * search keyboard without each callsite having to remember to clear
     * focus. Pass `false` for buttons that should leave the keyboard up,
     * e.g. the search bar's clear-X (where the user typically wants to
     * keep typing after clearing the query).
     */
    dismissKeyboard: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    val ripples = remember { mutableStateListOf<RippleEntry>() }
    val scope = rememberCoroutineScope()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    // Chain shape is constant across `enabled` changes — pointerInput is
    // always present (no-ops when disabled), drawBehind is always present.
    // Toggling `enabled` only re-keys the pointerInput, which restarts its
    // gesture loop but leaves the `ripples` list and the ripple animation
    // coroutines (launched on the external `scope`) untouched. An earlier
    // version returned a different chain shape when disabled, which caused
    // the remembered ripple state to be discarded mid-fade — visible as
    // the ripple snapping out of existence the moment a tap toggled the
    // enabled state (e.g. clearing the search field via the X).
    this
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            val slop = viewConfiguration.touchSlop
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = true)
                // Wait for confirmation that the gesture is a tap, not the
                // start of a scroll. We watch positions ourselves rather
                // than calling `awaitTouchSlopOrCancellation`, because that
                // helper returns `null` (treated by us as "tap confirmed")
                // whenever ANY handler consumes the slop change — and a
                // parent `LazyColumn` consumes drag changes the moment it
                // claims the gesture, which made every scroll register as
                // a tap mid-way. Reading positions directly is consumption-
                // agnostic.
                val isTap = waitForTapOrDrag(
                    pointerId = down.id,
                    downPosition = down.position,
                    slop = slop,
                    pass = PointerEventPass.Main,
                )
                if (!isTap) return@awaitEachGesture

                val maxR = computeMaxRadius(
                    down.position, size.width.toFloat(), size.height.toFloat(),
                )
                val entry = spawnRipple(
                    scope, ripples, down.position, maxR, startAlpha, expandDurationMs,
                )
                fadeRipple(scope, ripples, entry, fadeDurationMs)
                // Frame-yield + onClick run on `scope`, not inside the
                // pointer scope: AwaitPointerEventScope is `@RestrictsSuspension`,
                // so generic suspends like `withFrameNanos` aren't allowed
                // inline here. Launching on `scope` also returns control to
                // the gesture loop immediately so the next tap isn't blocked
                // by the click handler.
                scope.launch {
                    withFrameNanos { }
                    if (dismissKeyboard) focusManager.clearFocus()
                    onClick()
                }
            }
        }
        .drawBehind { drawRipples(ripples, color) }
}

/**
 * Decorative variant of [touchRipple] for containers whose children already
 * handle taps (e.g. a search bar wrapping a text field). Watches down/up
 * events at the Initial pointer pass without consuming them, so child
 * pointer handlers — text field focus/cursor positioning, inline dismiss
 * buttons — continue to work normally. No `onClick` because the children
 * own the semantic interaction; this modifier only provides the visual.
 */
fun Modifier.touchRippleDecoration(
    color: Color = Color.White,
    startAlpha: Float = 0.22f,
    expandDurationMs: Int = 420,
    fadeDurationMs: Int = 260,
    enabled: Boolean = true,
): Modifier = composed {
    val ripples = remember { mutableStateListOf<RippleEntry>() }
    val scope = rememberCoroutineScope()

    this
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            val slop = viewConfiguration.touchSlop
            awaitEachGesture {
                val down = awaitFirstDown(
                    requireUnconsumed = false,
                    pass = PointerEventPass.Initial,
                )
                // Same consumption-agnostic tap-vs-drag detection as
                // `touchRipple`. Initial pass so we observe events before
                // the text field (or any child / parent scrollable)
                // consumes them, and we don't consume either.
                val isTap = waitForTapOrDrag(
                    pointerId = down.id,
                    downPosition = down.position,
                    slop = slop,
                    pass = PointerEventPass.Initial,
                )
                if (!isTap) return@awaitEachGesture

                val maxR = computeMaxRadius(
                    down.position, size.width.toFloat(), size.height.toFloat(),
                )
                val entry = spawnRipple(
                    scope, ripples, down.position, maxR, startAlpha, expandDurationMs,
                )
                fadeRipple(scope, ripples, entry, fadeDurationMs)
            }
        }
        // drawBehind places the ripple between the parent's bg and the
        // child content (text field, icons). Keeps text fully legible
        // during the ripple rather than tinting it.
        .drawBehind { drawRipples(ripples, color) }
}

/**
 * Plays a ripple from the element's centre whenever [signal] changes — used
 * to programmatically trigger a tap-style highlight without an actual touch.
 * The first signal seen at composition is treated as the baseline and does
 * NOT spawn a ripple, so a card that mounts already-highlighted (e.g. after
 * a config change) doesn't get a phantom ripple. Subsequent changes trigger.
 *
 * Pair with `touchRipple` on the same element — they keep separate ripple
 * lists, so a programmatic flash can play simultaneously with a real tap
 * without interfering. Position the modifier between `.background` and
 * `.border` (same place as `touchRipple`) so the ripple sits between the
 * card surface and its outline.
 */
fun Modifier.programmaticRipple(
    signal: Int,
    color: Color = Color.White,
    startAlpha: Float = 0.10f,
    expandDurationMs: Int = 420,
    fadeDurationMs: Int = 260,
): Modifier = composed {
    val ripples = remember { mutableStateListOf<RippleEntry>() }
    val scope = rememberCoroutineScope()
    var sizeState by remember { mutableStateOf(IntSize.Zero) }
    var lastSignal by remember { mutableStateOf(signal) }

    LaunchedEffect(signal) {
        if (signal == lastSignal) return@LaunchedEffect
        lastSignal = signal
        val w = sizeState.width.toFloat()
        val h = sizeState.height.toFloat()
        if (w <= 0f || h <= 0f) return@LaunchedEffect
        val center = Offset(w / 2f, h / 2f)
        val maxR = computeMaxRadius(center, w, h)
        val entry = spawnRipple(scope, ripples, center, maxR, startAlpha, expandDurationMs)
        fadeRipple(scope, ripples, entry, fadeDurationMs)
    }

    this
        .onSizeChanged { sizeState = it }
        .drawBehind { drawRipples(ripples, color) }
}

/**
 * Waits until the active pointer either releases (tap, returns true) or
 * crosses the touch slop (drag, returns false). Reads positions directly
 * and ignores `change.isConsumed`, so a parent scrollable consuming the
 * slop event doesn't get mis-read as a release.
 */
private suspend fun AwaitPointerEventScope.waitForTapOrDrag(
    pointerId: PointerId,
    downPosition: Offset,
    slop: Float,
    pass: PointerEventPass,
): Boolean {
    while (true) {
        val event = awaitPointerEvent(pass)
        val change = event.changes.firstOrNull { it.id == pointerId }
            ?: return true
        if (!change.pressed) return true
        val dx = change.position.x - downPosition.x
        val dy = change.position.y - downPosition.y
        if (hypot(dx, dy) > slop) return false
    }
}

/**
 * Fires [onClick] after yielding one frame. Use this in children that sit
 * inside a `touchRippleDecoration` container when the handler triggers
 * heavy recomposition (clearing a query, submitting a form) — the frame
 * yield lets the container's ripple render its first paint before the
 * recomposition cascade hits the main thread.
 */
suspend fun deferredClick(onClick: () -> Unit) {
    withFrameNanos { }
    onClick()
}

private fun computeMaxRadius(pos: Offset, w: Float, h: Float): Float =
    hypot(maxOf(pos.x, w - pos.x), maxOf(pos.y, h - pos.y))

private fun spawnRipple(
    scope: CoroutineScope,
    ripples: MutableList<RippleEntry>,
    position: Offset,
    maxR: Float,
    startAlpha: Float,
    expandDurationMs: Int,
): RippleEntry {
    val entry = RippleEntry(
        center = position,
        radius = Animatable(0f),
        alpha = Animatable(startAlpha),
    )
    ripples.add(entry)
    // Expand fires regardless of release — if the user holds, the ripple
    // reaches full radius and rests there until the pointer lifts.
    scope.launch {
        entry.radius.animateTo(
            maxR,
            tween(expandDurationMs, easing = LinearOutSlowInEasing),
        )
    }
    return entry
}

private fun fadeRipple(
    scope: CoroutineScope,
    ripples: MutableList<RippleEntry>,
    entry: RippleEntry,
    fadeDurationMs: Int,
) {
    scope.launch {
        entry.alpha.animateTo(0f, tween(fadeDurationMs, easing = LinearEasing))
        ripples.remove(entry)
    }
}

private fun DrawScope.drawRipples(ripples: List<RippleEntry>, color: Color) {
    ripples.forEach { r ->
        drawCircle(
            color = color.copy(alpha = r.alpha.value),
            radius = r.radius.value,
            center = r.center,
        )
    }
}
