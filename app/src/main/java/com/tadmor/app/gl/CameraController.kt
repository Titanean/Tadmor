package com.tadmor.app.gl

import android.opengl.Matrix
import android.view.MotionEvent
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Orbit camera that translates touch gestures into view and projection matrices.
 *
 * Touch events arrive on the UI thread; matrices are read on the GL thread.
 * Thread safety is achieved by writing to a pending matrix pair, then atomically
 * swapping the volatile reference so the GL thread always reads a consistent snapshot.
 */
class CameraController(
    var fovDegrees: Float = 45f,
    var nearPlane: Float = 0.1f,
    var farPlane: Float = 100f,
    var minDistance: Float = 1.5f,
    var maxDistance: Float = 50f,
    var maxPanRadius: Float = 50f,
) {

    // Orbit state (UI thread only)
    var azimuth = 0f            // horizontal angle in degrees
        private set
    var elevation = 15f         // vertical angle in degrees
        private set
    private var distance = 3f           // distance from target
    var roll = 0f              // roll around view axis in degrees
        private set

    /** Current orbit distance, readable from any thread. */
    @Volatile
    var currentDistance = 3f
        private set

    /**
     * Current eye position in world space. Backed by [matrices] so it stays
     * in lockstep with [viewMatrix] and [projectionMatrix] — a single GL
     * frame that reads all three independently would otherwise see an eye
     * from a newer matrix swap than the view/projection (or vice versa)
     * during active dragging, producing a one-frame "jump in the drag
     * direction" artifact. For multi-field reads on the GL thread, prefer
     * [snapshot] so view + projection + eye come from one atomic read.
     */
    val eyePosition: FloatArray get() = matrices.eye

    private var targetX = 0f
    private var targetY = 0f
    private var targetZ = 0f

    // Aspect ratio (set from onSurfaceChanged on GL thread)
    private var aspect = 1f

    // Triple-buffered camera state (view, projection, eye position). UI
    // thread writes a new MatrixPair atomically; GL thread reads it via the
    // [snapshot] helper or the individual getters.
    @Volatile
    private var matrices = MatrixPair()

    // Touch tracking
    private var prevX = 0f
    private var prevY = 0f
    private var prevSpacing = 0f
    private var prevMidX = 0f
    private var prevMidY = 0f
    private var pointerCount = 0
    private var pinchEndTime = 0L
    private val pinchCooldownMs = 150L

    /** Read by the GL thread each frame. Prefer [snapshot] when reading
     *  more than one camera field together — every property access here is
     *  a separate volatile read of [matrices], so two sequential reads can
     *  observe two different swaps during active dragging. */
    val viewMatrix: FloatArray get() = matrices.view

    /** Read by the GL thread each frame. See [viewMatrix]. */
    val projectionMatrix: FloatArray get() = matrices.projection

    /**
     * Atomic snapshot of view + projection + eye position. Reads
     * [matrices] exactly once so a UI-thread [tickDamping] / [rebuildMatrices]
     * cannot interleave between the renderer's reads of view/proj/eye and
     * produce a torn camera. GL renderers should call this once at the top
     * of `onDrawFrame` and reuse the snapshot's fields for the entire
     * frame.
     */
    fun snapshot(): Snapshot {
        val p = matrices
        return Snapshot(p.view, p.projection, p.eye)
    }

    class Snapshot internal constructor(
        val view: FloatArray,
        val projection: FloatArray,
        val eye: FloatArray,
    )

    /** Called from onSurfaceChanged on the GL thread. */
    fun setAspectRatio(width: Int, height: Int) {
        aspect = width.toFloat() / height.toFloat()
        rebuildMatrices()
    }

    fun setOrbitAngles(azimuth: Float, elevation: Float) {
        this.azimuth = azimuth
        this.elevation = elevation.coerceIn(-89f, 89f)
        rebuildMatrices()
    }

    fun setDistance(distance: Float) {
        this.distance = distance.coerceIn(minDistance, maxDistance)
        currentDistance = this.distance
        rebuildMatrices()
    }

    fun lookAt(x: Float, y: Float, z: Float) {
        targetX = x
        targetY = y
        targetZ = z
        rebuildMatrices()
    }

    fun setRoll(degrees: Float) {
        this.roll = degrees
        rebuildMatrices()
    }

    /**
     * Applies an orbit delta in screen pixels — same 0.3°/px sensitivity as
     * a single-finger drag. Used by external animators (e.g. fling). When
     * [useDampedMotion] is on, single-finger drag inputs are routed
     * through [pendingAzDelta] / [pendingElDelta] instead so per-frame
     * damping can apply them gradually. This direct method bypasses
     * damping. Elevation is clamped to ±89°; azimuth wraps unbounded.
     */
    fun applyOrbitDelta(dxPx: Float, dyPx: Float) {
        val sensitivity = 0.3f
        azimuth += dxPx * sensitivity
        elevation = (elevation - dyPx * sensitivity).coerceIn(-89f, 89f)
        rebuildMatrices()
    }

    // ── Damped motion state (Three.js OrbitControls style) ──
    // When [useDampedMotion] is true, single-finger drag deltas and pinch
    // zoom factors accumulate into pendingAzDelta/pendingElDelta /
    // pendingZoomLogDelta and are applied gradually by tickDamping each
    // frame. This produces a weighty "follow with lag" feel during touch
    // and a natural decaying coast on release — the longer the user has
    // been actively manipulating, the larger the pending delta and the
    // more post-release motion. A nearly-stopped finger at release leaves
    // a small pending delta and so produces a near-stop instead of a
    // micro-flick.
    //
    // Zoom uses log space so the multiplicative scale composes additively
    // (ln(s1) + ln(s2) = ln(s1·s2)); applying `pending * factor` per frame
    // becomes `distance *= exp(pending * factor)`.
    var useDampedMotion = false

    /**
     * When true, single-finger drags directly translate the camera
     * (drag right → camera rotates right, scene appears to drift left).
     * When false (default), drags translate the scene under a fixed
     * camera (drag right → scene rotates right, the standard 3D-viewer
     * convention used by Three.js OrbitControls / Blender / Maya).
     * Surfaced via the user setting `invertCameraControls`.
     */
    var invertControls = false

    private var pendingAzDelta = 0f
    private var pendingElDelta = 0f
    private var pendingZoomLogDelta = 0f

    /**
     * Per-frame damping tick. Applies [dampingFactor] of each pending
     * delta (orbit + zoom) and decays the rest by `(1 - factor)`. Returns
     * true while there's still meaningful motion, so the caller can keep
     * ticking; false when all pending deltas have decayed below the
     * noise floor.
     */
    fun tickDamping(dampingFactor: Float): Boolean {
        val hasOrbit = pendingAzDelta != 0f || pendingElDelta != 0f
        val hasZoom = pendingZoomLogDelta != 0f
        if (!hasOrbit && !hasZoom) return false

        val retain = 1f - dampingFactor

        if (hasOrbit) {
            azimuth += pendingAzDelta * dampingFactor
            elevation = (elevation + pendingElDelta * dampingFactor).coerceIn(-89f, 89f)
            pendingAzDelta *= retain
            pendingElDelta *= retain
        }

        if (hasZoom) {
            val applyLog = pendingZoomLogDelta * dampingFactor
            // exp(0) = 1, so this is a no-op when applyLog is zero.
            distance = (distance * kotlin.math.exp(applyLog))
                .coerceIn(minDistance, maxDistance)
            currentDistance = distance
            pendingZoomLogDelta *= retain
        }

        // Below these floors the per-frame contribution is sub-pixel /
        // sub-percent and imperceptible — clamp to zero so the loop can
        // stop.
        val orbitStill = kotlin.math.abs(pendingAzDelta) > 0.005f ||
            kotlin.math.abs(pendingElDelta) > 0.005f
        val zoomStill = kotlin.math.abs(pendingZoomLogDelta) > 0.0005f
        if (!orbitStill) {
            pendingAzDelta = 0f
            pendingElDelta = 0f
        }
        if (!zoomStill) {
            pendingZoomLogDelta = 0f
        }

        rebuildMatrices()
        return orbitStill || zoomStill
    }

    /** Drop any in-flight damping motion (e.g. on globe-mode change). */
    fun cancelDamping() {
        pendingAzDelta = 0f
        pendingElDelta = 0f
        pendingZoomLogDelta = 0f
    }

    /** Current orbit target position. */
    val targetPosition: FloatArray get() = floatArrayOf(targetX, targetY, targetZ)

    /** Whether the camera is currently centered on the origin (Sol). */
    val isOnOrigin: Boolean
        get() = targetX * targetX + targetY * targetY + targetZ * targetZ < 0.001f

    /**
     * Processes touch events. Returns true to consume the event.
     * Called on the UI thread.
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                prevX = event.x
                prevY = event.y
                pointerCount = 1
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                pointerCount = event.pointerCount
                if (pointerCount >= 2) {
                    prevSpacing = spacing(event)
                    prevMidX = (event.getX(0) + event.getX(1)) / 2f
                    prevMidY = (event.getY(0) + event.getY(1)) / 2f
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (pointerCount >= 2 && event.pointerCount >= 2) {
                    // Pinch zoom
                    val newSpacing = spacing(event)
                    if (prevSpacing > 10f) {
                        val scale = prevSpacing / newSpacing
                        if (useDampedMotion) {
                            // Queue the multiplicative scale in log space
                            // so it composes additively with the rest of
                            // the damping accumulator. tickDamping will
                            // exponentiate when applying.
                            pendingZoomLogDelta += kotlin.math.ln(scale)
                        } else {
                            distance = (distance * scale).coerceIn(minDistance, maxDistance)
                            currentDistance = distance
                            rebuildMatrices()
                        }
                    }
                    prevSpacing = newSpacing
                } else if (pointerCount == 1) {
                    // Single-finger orbit — suppress briefly after pinch ends
                    // to prevent residual finger movement from causing rapid rotation
                    if (System.currentTimeMillis() - pinchEndTime < pinchCooldownMs) {
                        // Still in cooldown — just update tracking position, no orbit
                    } else {
                        val dx = event.x - prevX
                        val dy = event.y - prevY
                        val sensitivity = 0.3f
                        // Default convention: scene follows the finger
                        // (drag right → scene rotates right; drag up →
                        // scene rotates up), matching Three.js
                        // OrbitControls / Blender / Maya. Horizontal axis
                        // needs a sign flip from the raw delta to achieve
                        // this feel; vertical is already correct under the
                        // raw sign. [invertControls] flips both back for
                        // users who prefer the "drag = move the camera"
                        // convention.
                        val azSign = if (invertControls) 1f else -1f
                        val elSign = if (invertControls) -1f else 1f
                        if (useDampedMotion) {
                            // Queue for per-frame damping — don't rebuild
                            // matrices here; tickDamping handles that.
                            pendingAzDelta += dx * sensitivity * azSign
                            pendingElDelta += dy * sensitivity * elSign
                        } else {
                            azimuth += dx * sensitivity * azSign
                            elevation = (elevation + dy * sensitivity * elSign).coerceIn(-89f, 89f)
                            rebuildMatrices()
                        }
                    }
                }
                prevX = event.x
                prevY = event.y
            }

            MotionEvent.ACTION_POINTER_UP -> {
                pointerCount = event.pointerCount - 1
                // Reset single-finger tracking to avoid jump
                if (pointerCount == 1) {
                    val remaining = if (event.actionIndex == 0) 1 else 0
                    prevX = event.getX(remaining)
                    prevY = event.getY(remaining)
                    // Start cooldown to suppress residual orbit from pinch release
                    pinchEndTime = System.currentTimeMillis()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pointerCount = 0
            }
        }
        return true
    }

    /**
     * Translates the look-at target along the camera's right and up vectors.
     * Pan speed scales with orbit distance so it feels consistent at any zoom,
     * with a floor so panning never stalls when zoomed in close.
     * Target is clamped to [maxPanRadius] to prevent panning into empty space.
     */
    private fun panTarget(screenDx: Float, screenDy: Float) {
        val panSpeed = max(distance, 1f) * 0.0006f

        // Camera right vector from azimuth (horizontal pan)
        val azRad = azimuth * DEG_TO_RAD
        val rightX = cos(azRad)
        val rightZ = -sin(azRad)

        // Camera up vector in world space (vertical pan)
        val elRad = elevation * DEG_TO_RAD
        val upX = -sin(elRad) * sin(azRad)
        val upY = cos(elRad)
        val upZ = -sin(elRad) * cos(azRad)

        targetX += (-screenDx * rightX + screenDy * upX) * panSpeed
        targetY += (screenDy * upY) * panSpeed
        targetZ += (-screenDx * rightZ + screenDy * upZ) * panSpeed

        // Clamp target to prevent panning beyond the star field
        val dist = sqrt(targetX * targetX + targetY * targetY + targetZ * targetZ)
        if (dist > maxPanRadius) {
            val scale = maxPanRadius / dist
            targetX *= scale
            targetY *= scale
            targetZ *= scale
        }

        rebuildMatrices()
    }

    private fun rebuildMatrices() {
        val pair = MatrixPair()

        // Eye position from spherical coordinates
        val azRad = azimuth * DEG_TO_RAD
        val elRad = elevation * DEG_TO_RAD
        val cosEl = cos(elRad)
        val eyeX = targetX + distance * cosEl * sin(azRad)
        val eyeY = targetY + distance * sin(elRad)
        val eyeZ = targetZ + distance * cosEl * cos(azRad)

        pair.eye[0] = eyeX
        pair.eye[1] = eyeY
        pair.eye[2] = eyeZ

        // Compute up vector, applying roll if non-zero
        var upX = 0f
        var upY = 1f
        var upZ = 0f
        if (roll != 0f) {
            // Rodrigues' rotation of (0,1,0) around the forward axis
            val dx = targetX - eyeX
            val dy = targetY - eyeY
            val dz = targetZ - eyeZ
            val fLen = sqrt(dx * dx + dy * dy + dz * dz)
            val fx = dx / fLen
            val fy = dy / fLen
            val fz = dz / fLen
            val rollRad = roll * DEG_TO_RAD
            val cosR = cos(rollRad)
            val sinR = sin(rollRad)
            // k×(0,1,0) where k=(fx,fy,fz) = (-fz, 0, fx)
            val kDotV = fy
            upX = (-fz) * sinR + fx * kDotV * (1 - cosR)
            upY = cosR + fy * kDotV * (1 - cosR)
            upZ = fx * sinR + fz * kDotV * (1 - cosR)
        }

        Matrix.setLookAtM(
            pair.view, 0,
            eyeX, eyeY, eyeZ,
            targetX, targetY, targetZ,
            upX, upY, upZ,
        )

        Matrix.perspectiveM(
            pair.projection, 0,
            fovDegrees, aspect, nearPlane, farPlane,
        )

        // Atomic swap — GL thread will pick up the new pair on next frame
        matrices = pair
    }

    private fun spacing(event: MotionEvent): Float {
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return sqrt(dx * dx + dy * dy)
    }

    private class MatrixPair {
        val view = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        val projection = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        val eye = FloatArray(3)
    }

    companion object {
        private const val DEG_TO_RAD = (PI / 180.0).toFloat()
    }
}
