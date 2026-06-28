package com.tadmor.app.ui.system

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.pow

private val TRIGGER_DISTANCE = 100.dp
private val MAX_DRAG_HALF = 400.dp

// FULL-mode drag is capped tighter (~25% of a typical phone screen) so the
// preview peek can't swallow the whole viewport.
private val MAX_DRAG_FULL = 200.dp

/**
 * Pull-to-refresh-style drag state for HALF↔FULL globe transitions.
 *
 * Drag only — commit animations (HALF content sliding off, FULL overlay fade,
 * back button entry/exit) all live in the composable so the drag-release path
 * and the HW-back path share a single code path. This state object just
 * reports the live drag offset and flips the external mode when released past
 * the threshold; the composable observes the mode flip via [LaunchedEffect]
 * and drives the commit.
 */
class GlobeDragState internal constructor(
    private val scope: CoroutineScope,
    density: Density,
    private val isFullRef: State<Boolean>,
    private val onModeChange: (GlobeMode) -> Unit,
) {
    private val offsetAnim = Animatable(0f)
    private val triggerPx = with(density) { TRIGGER_DISTANCE.toPx() }
    private val maxDragHalfPx = with(density) { MAX_DRAG_HALF.toPx() }
    private val maxDragFullPx = with(density) { MAX_DRAG_FULL.toPx() }

    val offsetPx: Float get() = offsetAnim.value

    /** 0 at rest in FULL, 1 at threshold while dragging back toward HALF. */
    val fullExitProgress: Float
        get() = (-offsetAnim.value / triggerPx).coerceIn(0f, 1f)

    private var halfAccumulated = 0f
    private var fullAccumulated = 0f

    /**
     * Rubber-band dampening: full delta at rest, tapering to zero as the
     * current offset approaches [limit]. Quadratic falloff feels firmer than
     * linear — early drag is nearly 1:1, late drag asymptotes.
     */
    private fun resistDelta(raw: Float, current: Float, limit: Float): Float {
        if (limit <= 0f) return 0f
        val progress = (abs(current) / limit).coerceIn(0f, 1f)
        val factor = (1f - progress).pow(1.5f)
        return raw * factor
    }

    // --- HALF mode: globe-area gesture handlers ---

    fun onHalfGlobeDragStart() {
        halfAccumulated = offsetAnim.value.coerceAtLeast(0f)
    }

    fun onHalfGlobeDrag(dragAmountY: Float) {
        if (isFullRef.value) return
        val adjusted = resistDelta(dragAmountY, halfAccumulated, maxDragHalfPx)
        val next = (halfAccumulated + adjusted).coerceIn(0f, maxDragHalfPx)
        halfAccumulated = next
        scope.launch { offsetAnim.snapTo(next) }
    }

    fun onHalfGlobeRelease() {
        halfAccumulated = 0f
        settleHalfRelease()
    }

    // --- FULL mode: bottom-bar gesture handlers ---

    fun onFullBottomDragStart() {
        fullAccumulated = offsetAnim.value.coerceAtMost(0f)
    }

    fun onFullBottomDrag(dragAmountY: Float) {
        if (!isFullRef.value) return
        val adjusted = resistDelta(dragAmountY, fullAccumulated, maxDragFullPx)
        val next = (fullAccumulated + adjusted).coerceIn(-maxDragFullPx, 0f)
        fullAccumulated = next
        scope.launch { offsetAnim.snapTo(next) }
    }

    fun onFullBottomRelease() {
        fullAccumulated = 0f
        settleFullRelease()
    }

    // --- Nested scroll: HALF content column overscroll drives the same offset ---

    val nestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            if (isFullRef.value) return Offset.Zero
            // While content is dragged down, consume upward scroll to collapse it
            // back before the list itself scrolls up.
            if (offsetAnim.value > 0f && available.y < 0f) {
                val consumed = available.y.coerceAtLeast(-offsetAnim.value)
                val next = (offsetAnim.value + consumed).coerceAtLeast(0f)
                scope.launch { offsetAnim.snapTo(next) }
                return Offset(0f, consumed)
            }
            return Offset.Zero
        }

        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            if (isFullRef.value) return Offset.Zero
            // Only accept direct drag overscroll at the top — ignore fling spill.
            if (available.y > 0f && source == NestedScrollSource.UserInput) {
                val raw = available.y * 0.5f
                val adjusted = resistDelta(raw, offsetAnim.value, maxDragHalfPx)
                val next = (offsetAnim.value + adjusted).coerceIn(0f, maxDragHalfPx)
                scope.launch { offsetAnim.snapTo(next) }
                return Offset(0f, available.y)
            }
            return Offset.Zero
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (isFullRef.value || offsetAnim.value <= 0f) return Velocity.Zero
            settleHalfRelease()
            return Velocity.Zero
        }
    }

    // --- Settle helpers ---

    private fun settleHalfRelease() {
        if (isFullRef.value) return
        if (offsetAnim.value >= triggerPx) {
            // Past threshold — flip the mode. The composable observes the
            // external mode change via LaunchedEffect(globeMode) and runs the
            // commit animation (HALF content sliding off, FULL overlay fading
            // in, back button sliding up off-screen).
            onModeChange(GlobeMode.FULL)
        } else {
            scope.launch {
                offsetAnim.animateTo(0f, tween(250, easing = FastOutSlowInEasing))
            }
        }
    }

    private fun settleFullRelease() {
        if (!isFullRef.value) return
        if (-offsetAnim.value >= triggerPx) {
            // Past threshold — flip the mode. The composable drives the
            // rise-from-bottom animation and the back button slide-down.
            onModeChange(GlobeMode.HALF)
        } else {
            scope.launch {
                offsetAnim.animateTo(0f, tween(250, easing = FastOutSlowInEasing))
            }
        }
    }

    /**
     * Called by the composable at the start of a commit animation to clear
     * any residual drag offset without visibly snapping (the composable
     * transfers the value into its own commit-animation state first).
     */
    suspend fun snapOffsetToZero() {
        offsetAnim.snapTo(0f)
    }

    /**
     * Animates the residual drag offset back to 0 over [durationMs] so the
     * commit animation can absorb the live finger-release offset smoothly
     * instead of snapping it to zero at either end of the commit.
     */
    suspend fun animateOffsetToZero(durationMs: Int = 320) {
        offsetAnim.animateTo(0f, tween(durationMs, easing = FastOutSlowInEasing))
    }
}

@Composable
fun rememberGlobeDragState(
    isFull: Boolean,
    onModeChange: (GlobeMode) -> Unit,
): GlobeDragState {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val isFullState = rememberUpdatedState(isFull)
    val onModeChangeState = rememberUpdatedState(onModeChange)
    return remember {
        GlobeDragState(
            scope = scope,
            density = density,
            isFullRef = isFullState,
            onModeChange = { mode -> onModeChangeState.value(mode) },
        )
    }
}
