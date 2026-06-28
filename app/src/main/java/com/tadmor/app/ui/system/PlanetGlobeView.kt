package com.tadmor.app.ui.system

import android.opengl.GLSurfaceView
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.tadmor.app.gl.CameraController
import com.tadmor.app.gl.ExoGLSurfaceView
import com.tadmor.app.gl.GLBridge
import kotlin.math.atan2

/**
 * Globe rendering modes for the planet detail page.
 * HALF: globe peeking above the text content.
 * FULL: fullscreen interactive globe, text content hidden.
 */
enum class GlobeMode { HALF, FULL }

/**
 * Composable that embeds the planet globe GL view.
 * In HALF mode, the globe sits at the top of the planet detail page at a fixed height.
 * In FULL mode, it fills the screen and absorbs all touch for camera interaction.
 * Mode transitions are driven by the parent (PlanetDetailContent).
 */
@Composable
fun PlanetGlobeView(
    params: PlanetGlobeParams,
    globeMode: GlobeMode,
    onGlobeModeChange: (GlobeMode) -> Unit,
    isActive: Boolean = true,
    isGlobeVisible: Boolean = true,
    invertControls: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val appContext = LocalContext.current.applicationContext

    // Camera configuration based on planet radius
    val radius = params.planetRadiusKm

    // Sun azimuth in CameraController coordinates (atan2(x,z) gives azimuth from +Z axis)
    val sunAz = remember(params.sunDirX, params.sunDirZ) {
        Math.toDegrees(atan2(params.sunDirX.toDouble(), params.sunDirZ.toDouble())).toFloat()
    }

    // One CameraController instance for the lifetime of the composable.
    // Critically this must NOT be re-created on seed change — the AndroidView
    // factory captures this reference and runs exactly once; the renderer on
    // the GL thread holds that captured reference forever. If we swapped in a
    // new controller here on planet change, the renderer would keep using the
    // stale one (with the old planet's bounds/target/roll) while the new one
    // sat disconnected. Instead, we update the single controller's fields in
    // place in the block below whenever params change.
    val cameraController = remember { CameraController() }

    // Sync the invert-controls flag every recomposition so the user
    // setting takes effect mid-session without recreating the controller
    // (which would dangle behind the renderer's captured reference).
    cameraController.invertControls = invertControls

    // Bounds configured once per seed/radius/ring extent. Initial distance
    // = HALF's 6R so first render lands at the correct framing before the
    // animation driver below takes over. Max zoom-out scales with the ring
    // system so Saturnian planets can pull back far enough to frame the
    // full ring *horizontally*: GL camera is 45° vertical FOV, which on a
    // portrait phone (aspect ~0.5) yields ~23° horizontal FOV, so the
    // distance needed for ringOuter radii to fit width-wise is
    // ringOuter / tan(~11.7°) ≈ ringOuter × 4.8. Use ×6 for margin.
    // farPlane must clear the back of the ring at max zoom, else the far
    // ring arc would be clipped against the frustum.
    remember(params.seed, radius, params.ringOuter) {
        val maxDist = maxOf(radius * 8f, radius * params.ringOuter * 6f)
        cameraController.nearPlane = radius * 0.01f
        cameraController.farPlane = maxDist + radius * 10f
        cameraController.minDistance = radius * 1.5f
        cameraController.maxDistance = maxDist
        cameraController.setDistance(radius * 6f)
        Unit
    }

    // GL view ref — declared BEFORE the mode-flip block below so the block
    // can call `glViewRef?.cancelMomentum()` synchronously during composition.
    var glViewRef by remember { mutableStateOf<ExoGLSurfaceView?>(null) }

    // Captured camera state at the instant a mode flip begins. These
    // values are lerp'd toward the target mode's defaults as the
    // animation progresses. Must be captured SYNCHRONOUSLY during
    // composition (below) — LaunchedEffect runs after SideEffect, so by
    // the time it fires the SideEffect would have already overwritten
    // the controller with formula-driven defaults.
    var lastMode by remember(params.seed) { mutableStateOf(globeMode) }
    val startAzimuth = remember(params.seed) { mutableFloatStateOf(sunAz) }
    val startElevation = remember(params.seed) { mutableFloatStateOf(0f) }
    val startRoll = remember(params.seed) { mutableFloatStateOf(90f) }
    val startDistance = remember(params.seed) { mutableFloatStateOf(radius * 6f) }
    val startTargetZ = remember(params.seed) { mutableFloatStateOf(radius * 0.9f) }

    if (globeMode != lastMode) {
        // Kill any in-flight damping coast so its momentum doesn't fight
        // the mode-transition tween (which would otherwise look settled
        // and then suddenly resume rotating once the transition stopped
        // overwriting the controller each composition).
        glViewRef?.cancelMomentum()
        startAzimuth.floatValue = cameraController.azimuth
        startElevation.floatValue = cameraController.elevation
        startRoll.floatValue = cameraController.roll
        startDistance.floatValue = cameraController.currentDistance
        startTargetZ.floatValue = cameraController.targetPosition[2]
        lastMode = globeMode
    }

    // Animation clock. Slightly slower than the 280/240ms UI crossfade so
    // the camera lingers through the mode switch rather than snapping.
    val progressAnim = remember(params.seed) {
        Animatable(if (globeMode == GlobeMode.FULL) 1f else 0f)
    }
    LaunchedEffect(progressAnim, globeMode) {
        progressAnim.animateTo(
            targetValue = if (globeMode == GlobeMode.FULL) 1f else 0f,
            animationSpec = tween(420, easing = FastOutSlowInEasing),
        )
    }

    // f = fraction from captured start state (0) toward target mode
    // defaults (1). progressAnim moves 0→1 on HALF→FULL and 1→0 on
    // FULL→HALF; inverting on HALF keeps f oriented start→end.
    val targetIsFull = globeMode == GlobeMode.FULL
    val f = if (targetIsFull) progressAnim.value else 1f - progressAnim.value
    // FULL target: 60° off-sun, 15° elevation, no roll, centered target.
    // HALF target: face the sun, 0° elevation, 90° roll (bands vertical),
    // +Z target offset pushes planet above text.
    // Distance is preserved across HALF→FULL (user hasn't pinched yet);
    // on FULL→HALF it returns to 6R from wherever they zoomed to.
    val endAzimuth = if (targetIsFull) sunAz - 60f else sunAz
    val endElevation = if (targetIsFull) 15f else 0f
    val endRoll = if (targetIsFull) 0f else 90f
    val endTargetZ = if (targetIsFull) 0f else radius * 0.9f
    val endDistance = if (targetIsFull) startDistance.floatValue else radius * 6f

    // Tracks the last `f` value we applied to the camera. Per-frame param
    // updates that don't move the lerp (e.g. the ambient-light animation
    // changing `params.ambientLight` but not anything camera-relevant) would
    // otherwise re-fire this SideEffect every frame and clobber the user's
    // rotation/zoom by overwriting the controller with the same target
    // angles. Skipping when `f` is unchanged preserves user interaction.
    val lastWrittenF = remember(params.seed) { mutableFloatStateOf(Float.NaN) }
    SideEffect {
        if (f == lastWrittenF.floatValue) return@SideEffect
        lastWrittenF.floatValue = f
        // Azimuth accumulates unbounded from user rotation; use shortest-
        // path delta so the FULL→HALF return never spins the long way
        // around (e.g. 359° → 0° should be +1°, not -359°).
        val rawAzDelta = (endAzimuth - startAzimuth.floatValue) % 360f
        val azDelta = when {
            rawAzDelta > 180f -> rawAzDelta - 360f
            rawAzDelta < -180f -> rawAzDelta + 360f
            else -> rawAzDelta
        }
        val azimuth = startAzimuth.floatValue + azDelta * f
        val elevation = startElevation.floatValue + (endElevation - startElevation.floatValue) * f
        val roll = startRoll.floatValue + (endRoll - startRoll.floatValue) * f
        val targetZ = startTargetZ.floatValue + (endTargetZ - startTargetZ.floatValue) * f
        val distance = startDistance.floatValue + (endDistance - startDistance.floatValue) * f
        cameraController.setOrbitAngles(azimuth, elevation)
        cameraController.setRoll(roll)
        cameraController.lookAt(0f, 0f, targetZ)
        cameraController.setDistance(distance)
    }

    val bridge = remember { GLBridge(PlanetGlobeParams()) }
    bridge.post(params)

    DisposableEffect(Unit) {
        onDispose {
            bridge.post(PlanetGlobeParams(isVisible = false))
        }
    }

    // GL view — always fullscreen. Mode transitions are handled by the parent
    // overlaying/hiding content on top; the GL view itself never resizes.
    //
    // When the containing tab is not active (e.g. user navigated to the Star
    // Map tab from the planet page), we hide the surface view. GLSurfaceView
    // draws to a native window-layer Surface that Compose's alpha/zIndex
    // can't cover, so without this the globe would punch through and block
    // the active tab's content. Setting visibility to GONE tears down the
    // surface and stops rendering; returning to VISIBLE recreates it.
    AndroidView(
        factory = { ctx ->
            val renderer = PlanetRenderer(appContext, cameraController, bridge)
            ExoGLSurfaceView(ctx, renderer, cameraController).also { glView ->
                glViewRef = glView
                glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                glView.enableDamping = true
                cameraController.useDampedMotion = true
            }
        },
        update = { glView ->
            glView.visibility = if (isActive) View.VISIBLE else View.GONE
            // Pause rendering when the globe is fully covered by scrolled content.
            // RENDERMODE_WHEN_DIRTY stops the GL thread's draw loop; switching
            // back to CONTINUOUSLY resumes it immediately. The GL context and all
            // textures survive — no re-bake needed.
            if (isActive) {
                glView.renderMode = if (isGlobeVisible) {
                    GLSurfaceView.RENDERMODE_CONTINUOUSLY
                } else {
                    GLSurfaceView.RENDERMODE_WHEN_DIRTY
                }
            }
            // Double-tap to enter FULL view from HALF. Re-assigned each
            // update so the lambda captures the current `globeMode` and
            // `onGlobeModeChange` rather than the values from the factory's
            // first composition.
            glView.onDoubleTap = { _, _ ->
                if (globeMode == GlobeMode.HALF) onGlobeModeChange(GlobeMode.FULL)
            }
        },
        modifier = modifier.fillMaxSize(),
    )
}
