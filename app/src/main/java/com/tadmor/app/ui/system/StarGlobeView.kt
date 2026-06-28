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

/**
 * Composable that embeds the star globe GL view.
 * Mirrors [PlanetGlobeView] pattern.
 * In HALF mode the star sits at the top of the star detail page.
 * In FULL mode it fills the screen and absorbs all touch for camera interaction.
 */
@Composable
fun StarGlobeView(
    params: StarGlobeParams,
    globeMode: GlobeMode,
    onGlobeModeChange: (GlobeMode) -> Unit,
    isActive: Boolean = true,
    /** Whether the System tab itself is on screen. Controls View visibility
     *  (and therefore EGL surface lifecycle) independently of [isActive],
     *  which only gates render mode. Lets the surface survive sub-page
     *  PLANET ↔ DETAIL transitions within the tab; sliding the page off
     *  via parent translationX positions it off-screen without destroying
     *  the surface. Going GONE during sub-page transitions caused a black
     *  flash through the slide-in when returning from the planet page,
     *  because surface recreation outlasted the slide. */
    isTabOnScreen: Boolean = true,
    isGlobeVisible: Boolean = true,
    invertControls: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val appContext = LocalContext.current.applicationContext

    val radius = params.starRadiusKm

    // One CameraController instance for the lifetime of the composable. Must
    // NOT be re-created on seed change — the AndroidView factory captures
    // this reference once and the renderer on the GL thread holds it forever.
    // A swapped-in fresh controller would sit disconnected while the renderer
    // kept using the stale one (with the previous star's bounds/target). We
    // update its fields in place in the block below whenever params change.
    val cameraController = remember { CameraController() }
    cameraController.invertControls = invertControls

    // Bounds configured once per seed/radius. Initial distance = HALF's 6R
    // so first render lands at the correct framing before the animation
    // driver below takes over.
    remember(params.seed, radius) {
        cameraController.nearPlane = radius * 0.01f
        cameraController.farPlane = radius * 20f
        cameraController.minDistance = radius * 1.5f
        cameraController.maxDistance = radius * 8f
        cameraController.setDistance(radius * 6f)
        Unit
    }

    // GL view ref — declared BEFORE the mode-flip block below so the block
    // can call `glViewRef?.cancelMomentum()` synchronously during composition.
    var glViewRef by remember { mutableStateOf<ExoGLSurfaceView?>(null) }

    // Captured camera state at the instant a mode flip begins. Must be
    // captured SYNCHRONOUSLY during composition — LaunchedEffect runs
    // after SideEffect, so by the time it fires the SideEffect would
    // have already overwritten the controller with formula defaults.
    var lastMode by remember(params.seed) { mutableStateOf(globeMode) }
    val startAzimuth = remember(params.seed) { mutableFloatStateOf(0f) }
    val startElevation = remember(params.seed) { mutableFloatStateOf(0f) }
    val startDistance = remember(params.seed) { mutableFloatStateOf(radius * 6f) }
    val startTargetY = remember(params.seed) { mutableFloatStateOf(-radius * 0.9f) }

    if (globeMode != lastMode) {
        // Kill any in-flight damping coast so its momentum doesn't fight
        // the mode-transition tween (which would otherwise look settled
        // and then suddenly resume rotating once the transition stopped
        // overwriting the controller each composition).
        glViewRef?.cancelMomentum()
        startAzimuth.floatValue = cameraController.azimuth
        startElevation.floatValue = cameraController.elevation
        startDistance.floatValue = cameraController.currentDistance
        startTargetY.floatValue = cameraController.targetPosition[1]
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

    // f = fraction from captured start state (0) toward target defaults (1).
    val targetIsFull = globeMode == GlobeMode.FULL
    val f = if (targetIsFull) progressAnim.value else 1f - progressAnim.value
    // FULL target: slight elevation, centered. HALF target: face equator,
    // -Y offset pushes star up. No roll or azimuth interpolation — stars
    // have no bands to orient, so azimuth carries whatever the user
    // rotated to and only elevation + target Y + distance change.
    val endElevation = if (targetIsFull) 15f else 0f
    val endTargetY = if (targetIsFull) 0f else -radius * 0.9f
    val endDistance = if (targetIsFull) startDistance.floatValue else radius * 6f

    SideEffect {
        val azimuth = startAzimuth.floatValue
        val elevation = startElevation.floatValue + (endElevation - startElevation.floatValue) * f
        val targetY = startTargetY.floatValue + (endTargetY - startTargetY.floatValue) * f
        val distance = startDistance.floatValue + (endDistance - startDistance.floatValue) * f
        cameraController.setOrbitAngles(azimuth, elevation)
        cameraController.setRoll(0f)
        cameraController.lookAt(0f, targetY, 0f)
        cameraController.setDistance(distance)
    }

    val bridge = remember { GLBridge(StarGlobeParams()) }
    bridge.post(params)

    DisposableEffect(Unit) {
        onDispose {
            bridge.post(StarGlobeParams(isVisible = false))
        }
    }

    AndroidView(
        factory = { ctx ->
            val renderer = StarRenderer(appContext, cameraController, bridge)
            ExoGLSurfaceView(ctx, renderer, cameraController).also { glView ->
                glViewRef = glView
                glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                glView.enableDamping = true
                cameraController.useDampedMotion = true
            }
        },
        update = { glView ->
            // Visibility is gated on tab presence, not sub-page activity, so
            // the EGL surface stays alive across PLANET ↔ DETAIL transitions.
            //
            // Render mode is *also* gated on tab presence (not sub-page
            // activity) so the star is always rendered to its surface while
            // we're in the tab, even when the user is sitting on the planet
            // page. Without this, a catalog → planet cross-tab navigation
            // (which skips DETAIL entirely) would mean the star surface
            // never received a single frame — when the user later tapped
            // back to DETAIL the surface was at its black EGL clear colour
            // and the slide-in revealed that black, with the first proper
            // star frame only arriving a vsync after the renderMode flip
            // (well after the slide was visible).
            //
            // The scroll-based gate is preserved: when the globe is fully
            // scrolled off the screen we drop to WHEN_DIRTY to save GPU,
            // since by definition no one's looking at the globe area then.
            glView.visibility = if (isTabOnScreen) View.VISIBLE else View.GONE
            glView.renderMode = if (isTabOnScreen && isGlobeVisible) {
                GLSurfaceView.RENDERMODE_CONTINUOUSLY
            } else {
                GLSurfaceView.RENDERMODE_WHEN_DIRTY
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
