package com.tadmor.app.gl

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.Choreographer
import android.view.MotionEvent

/**
 * Custom GLSurfaceView configured for OpenGL ES 3.0.
 * Dispatches drag/pinch to [cameraController] and taps to [onTap].
 *
 * Touch handling defers camera forwarding until the finger moves beyond the
 * tap threshold, preventing tiny camera jitter on taps.
 */
class ExoGLSurfaceView(
    context: Context,
    renderer: ExoRenderer,
    private val cameraController: CameraController,
) : GLSurfaceView(context) {

    /** Optional tap callback. If set, short taps invoke this instead of the camera. */
    var onTap: ((Float, Float) -> Unit)? = null

    /**
     * Optional double-tap callback. Detected by tracking the time between
     * consecutive ACTION_UP events that qualified as taps (not drags); two
     * taps within [DOUBLE_TAP_TIMEOUT_MS] of each other fire this instead
     * of the second tap's [onTap]. The first tap of a pair still fires
     * [onTap] normally — callers that want strict mutual exclusion
     * (e.g. the planet/star globe views, which don't set [onTap] at all
     * for double-tap-only behaviour) get exclusion for free.
     */
    var onDoubleTap: ((Float, Float) -> Unit)? = null

    /** Called when a drag gesture begins (finger moved beyond tap threshold). */
    var onDragStart: (() -> Unit)? = null

    /** Rectangles (in view pixels) where touches are ignored (e.g. Compose overlays). */
    var exclusionZones: List<android.graphics.RectF> = emptyList()

    /**
     * When true, single-finger drag inputs are smoothed by a Three.js
     * OrbitControls-style damping pass: each move accumulates into a
     * pending-delta on [CameraController]; a Choreographer loop applies a
     * fraction of the pending each frame and decays the rest. Result is a
     * weighty "follow with lag" feel during drag and a natural decaying
     * coast after release — fast flicks coast a noticeable amount, slow or
     * stopped finger movements coast almost not at all (so a "near-stop"
     * release reads as a stop, no micro-flick). Default off so existing
     * screens (star map, orbital view) keep their direct-drag feel; the
     * planet and star globe views set this to true.
     */
    var enableDamping = false

    /**
     * Fraction of the pending delta applied per frame, with the rest
     * decayed. Lower values mean more retention per frame → less
     * friction → longer coast on release. atmosphere.html's reference
     * is 0.05 (strong lag, ~20 frames to settle); 0.10 here gives a
     * weighty feel with a noticeably longer post-release glide than
     * 0.15 had, while still stopping in well under a second on a
     * typical flick.
     */
    var dampingFactor = 0.10f

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var isDragging = false
    private var inExclusionZone = false
    private var lastTapUpMs = 0L
    private val tapThreshold = 15f * resources.displayMetrics.density // 15dp
    private val tapTimeLimit = 250L // ms
    private val doubleTapTimeoutMs = 300L

    // ── Damping loop (single-finger orbit → decaying pending delta) ──
    private var dampingActive = false
    private var dampingFrameCallback: Choreographer.FrameCallback? = null

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 24, 0) // RGBA8, 24-bit depth, no stencil
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        // Mirror the flag onto the controller. Globe views flip
        // `enableDamping` after construction, so re-sync inside the
        // touch path below as well.
        cameraController.useDampedMotion = enableDamping
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // If touch lands in an exclusion zone, let Compose handle it
                inExclusionZone = exclusionZones.any { it.contains(event.x, event.y) }
                if (inExclusionZone) return false
                // New touch — kill any in-flight damping coast so the
                // first new drag delta isn't superimposed on a leftover
                // glide.
                cancelMomentum()
                cameraController.useDampedMotion = enableDamping
                downX = event.x
                downY = event.y
                downTime = System.currentTimeMillis()
                isDragging = false
                return true // absorb — don't forward to camera yet, might be a tap
            }

            MotionEvent.ACTION_MOVE -> {
                if (inExclusionZone) return false
                if (!isDragging) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (dx * dx + dy * dy >= tapThreshold * tapThreshold) {
                        isDragging = true
                        onDragStart?.invoke()
                        // Initialize camera tracking at current position (no jump)
                        val down = MotionEvent.obtain(
                            event.downTime, event.eventTime,
                            MotionEvent.ACTION_DOWN, event.x, event.y, 0,
                        )
                        cameraController.onTouchEvent(down)
                        down.recycle()
                        if (enableDamping) ensureDampingRunning()
                    }
                }
                if (isDragging) {
                    val consumed = cameraController.onTouchEvent(event)
                    if (enableDamping) ensureDampingRunning()
                    return consumed
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (inExclusionZone) {
                    inExclusionZone = false
                    return false
                }
                if (!isDragging && event.actionMasked == MotionEvent.ACTION_UP) {
                    val elapsed = System.currentTimeMillis() - downTime
                    if (elapsed < tapTimeLimit) {
                        val now = System.currentTimeMillis()
                        val isDoubleTap = onDoubleTap != null &&
                            (now - lastTapUpMs) < doubleTapTimeoutMs
                        lastTapUpMs = if (isDoubleTap) 0L else now
                        if (isDoubleTap) {
                            onDoubleTap?.invoke(event.x, event.y)
                            isDragging = false
                            return true
                        }
                        if (onTap != null) {
                            onTap?.invoke(event.x, event.y)
                            return true
                        }
                    }
                }
                if (isDragging) {
                    cameraController.onTouchEvent(event)
                    // No explicit fling — the damping loop continues to
                    // tick on its own, applying whatever pending delta
                    // remained at release and decaying it out. The longer
                    // the user was actively dragging, the more pending
                    // delta they leave behind, and the more post-release
                    // glide the camera shows; a near-stopped finger
                    // leaves almost nothing, so it reads as a stop.
                }
                isDragging = false
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // Multi-touch — pinch zoom; pending damping deltas would
                // otherwise keep rotating the camera under the user's
                // pinch, which feels wrong, so cancel them.
                cameraController.cancelDamping()
                if (!isDragging) {
                    isDragging = true
                    val down = MotionEvent.obtain(
                        event.downTime, event.eventTime,
                        MotionEvent.ACTION_DOWN, event.getX(0), event.getY(0), 0,
                    )
                    cameraController.onTouchEvent(down)
                    down.recycle()
                }
                return cameraController.onTouchEvent(event)
            }

            else -> {
                if (isDragging) {
                    return cameraController.onTouchEvent(event)
                }
                return true
            }
        }
    }

    /**
     * Starts (or keeps running) the per-frame damping tick. Each frame
     * applies `dampingFactor` of the pending delta on the controller and
     * decays the rest by `(1 - dampingFactor)`; stops when the pending
     * delta has decayed below the noise floor.
     */
    private fun ensureDampingRunning() {
        if (dampingActive) return
        dampingActive = true
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!dampingActive) return
                val stillMoving = cameraController.tickDamping(dampingFactor)
                if (!stillMoving) {
                    dampingActive = false
                    dampingFrameCallback = null
                    return
                }
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        dampingFrameCallback = callback
        Choreographer.getInstance().postFrameCallback(callback)
    }

    /**
     * Drops any in-flight damping coast. Globe views call this when
     * [GlobeMode] flips so a still-decaying pending delta doesn't fight
     * the mode-transition camera tween and then resume rotating once the
     * tween stops overwriting the controller.
     */
    fun cancelMomentum() {
        dampingActive = false
        dampingFrameCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
        dampingFrameCallback = null
        cameraController.cancelDamping()
    }
}
