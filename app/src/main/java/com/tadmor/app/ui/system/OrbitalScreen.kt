package com.tadmor.app.ui.system

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.view.View
import com.tadmor.app.gl.CameraController
import com.tadmor.app.gl.ExoGLSurfaceView
import com.tadmor.app.gl.GLBridge
import com.tadmor.app.gl.RayCaster
import com.tadmor.app.ui.catalog.toColor
import com.tadmor.app.ui.components.pushOnPress
import com.tadmor.app.ui.components.touchRipple
import com.tadmor.app.ui.theme.ExoTheme
import com.tadmor.app.ui.theme.LocalBottomBarHeight
import com.tadmor.app.ui.theme.TeffColor
import com.tadmor.domain.model.ProperNames
import com.tadmor.domain.model.UserSettings

@Composable
fun OrbitalScreen(
    orbitalState: OrbitalState,
    starHostname: String,
    starDisplayName: String,
    settings: UserSettings,
    starfieldData: com.tadmor.app.ui.starmap.StarfieldData? = null,
    showStarfield: Boolean = true,
    onToggleStarfield: () -> Unit = {},
    showHabitableZone: Boolean = true,
    onToggleHabitableZone: () -> Unit = {},
    isTabActive: Boolean = true,
    isExiting: Boolean = false,
    onExitComplete: () -> Unit = {},
    onBack: () -> Unit,
    onPlanetSelected: (String) -> Unit,
    onViewStar: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type
    val spacing = ExoTheme.spacing
    val appContext = LocalContext.current.applicationContext

    // Camera: top-down default, zoom range adapts to system spread
    // For wide systems (e.g. Copernicus), allow zooming in far enough
    // to separate the innermost planet from the star visually.
    val cameraController = remember(orbitalState) {
        val smas = orbitalState.planets.map { it.smaAU }
        val minSMA = smas.minOrNull() ?: 1.0
        val maxSMA = smas.maxOrNull() ?: 1.0
        // At minDistance, the innermost planet should subtend ~30% of screen width.
        // innerPlanetGL = (minSMA / maxSMA) * SCALE_RADIUS = fraction of 5.0 GL units
        // Camera sees ~distance * tan(fov/2) GL units across half the screen.
        // We want innerPlanetGL to be clearly visible, so:
        // minDistance ≈ innerPlanetGL * 3 (gives planet ~1/6 of screen from center)
        val innerGL = if (maxSMA > 0) (minSMA / maxSMA) * 5.0 else 1.0
        val adaptiveMinDist = (innerGL * 3.0).toFloat().coerceIn(0.05f, 2f)

        // Far plane must accommodate both the HZ annulus and the wide
        // intro/exit distance (ORBITAL_INTRO_WIDE_DIST) that the transition
        // animation temporarily pushes the camera out to.
        val hzOuterGL = orbitalState.habitableZone?.let {
            if (maxSMA > 0) (it.outerAU / maxSMA) * 5.0 else 5.0
        } ?: 5.0
        val maxGeometry = maxOf(5.0, hzOuterGL) // 5.0 = SCALE_RADIUS (outermost orbit)
        val adaptiveFarPlane = (ORBITAL_INTRO_WIDE_DIST + maxGeometry.toFloat() + 10f)
            .coerceAtLeast(100f)

        CameraController(
            nearPlane = adaptiveMinDist * 0.1f, // scale near plane with zoom range
            farPlane = adaptiveFarPlane,
            minDistance = adaptiveMinDist,
            // Bumped past the normal cap so the intro animation can start
            // far out and zoom IN; restored to ORBITAL_NORMAL_MAX_DIST once
            // the intro completes. Also re-raised during the exit animation.
            maxDistance = ORBITAL_INTRO_WIDE_DIST,
        ).apply {
            // Start far out so the intro animation can zoom IN, reading as
            // the camera diving into the system after the map's zoom toward
            // the star in StarMapScreen.
            setDistance(ORBITAL_INTRO_WIDE_DIST)
            setOrbitAngles(0f, 30f)
        }
    }
    cameraController.invertControls = settings.invertCameraControls

    // Intro zoom-in: runs once per star entry. Camera starts far out and
    // dives in to the normal view distance, completing the illusion started
    // by the map's zoom-in animation.
    LaunchedEffect(starHostname) {
        val startDist = cameraController.currentDistance
        val endDist = ORBITAL_NORMAL_MAX_DIST
        if (startDist <= endDist) {
            cameraController.maxDistance = ORBITAL_NORMAL_MAX_DIST
            return@LaunchedEffect
        }
        val startNanos = withFrameNanos { it }
        val durationMs = 450f
        var progress = 0f
        while (progress < 1f) {
            val frameNanos = withFrameNanos { it }
            val elapsedMs = (frameNanos - startNanos) / 1_000_000f
            val linear = (elapsedMs / durationMs).coerceIn(0f, 1f)
            progress = FastOutSlowInEasing.transform(linear)
            cameraController.setDistance(startDist + (endDist - startDist) * progress)
        }
        // Lock out further zoom-out now that the intro is done.
        cameraController.maxDistance = ORBITAL_NORMAL_MAX_DIST
    }

    // Exit zoom-out: triggered by parent when the user taps back. Symmetric
    // inverse of the intro — once complete, onExitComplete() lets the parent
    // flip back to the map and run its own reverse animation.
    LaunchedEffect(isExiting) {
        if (!isExiting) return@LaunchedEffect
        cameraController.maxDistance = ORBITAL_INTRO_WIDE_DIST
        val startDist = cameraController.currentDistance
        val endDist = ORBITAL_INTRO_WIDE_DIST
        if (startDist >= endDist) {
            onExitComplete()
            return@LaunchedEffect
        }
        val startNanos = withFrameNanos { it }
        val durationMs = 450f
        var progress = 0f
        while (progress < 1f) {
            val frameNanos = withFrameNanos { it }
            val elapsedMs = (frameNanos - startNanos) / 1_000_000f
            val linear = (elapsedMs / durationMs).coerceIn(0f, 1f)
            progress = FastOutSlowInEasing.transform(linear)
            cameraController.setDistance(startDist + (endDist - startDist) * progress)
        }
        onExitComplete()
    }

    // GL bridge + renderer ref (for reading elapsed time on UI thread)
    val bridge = remember { GLBridge(OrbitalParams()) }
    var rendererRef by remember { mutableStateOf<OrbitalRenderer?>(null) }
    var glViewRef by remember { mutableStateOf<ExoGLSurfaceView?>(null) }

    // Orbital view state
    var containerSize by remember { mutableStateOf(IntSize(0, 0)) }

    // Selected object tooltip (planet or star). `displayedSelection`
    // latches the last non-null selection so the tooltip can play its
    // exit animation after selectedObject goes null; cleared by
    // onDismissComplete.
    var selectedObject by remember { mutableStateOf<OrbitalSelection?>(null) }
    var displayedSelection by remember { mutableStateOf<OrbitalSelection?>(null) }
    LaunchedEffect(selectedObject) {
        if (selectedObject != null) displayedSelection = selectedObject
    }
    var selectedScreenPos by remember { mutableStateOf(Offset.Zero) }

    // Time scale slider: auto-select so innermost planet orbits in ~5 seconds
    var timeScaleIndex by remember(orbitalState) {
        val minPeriod = orbitalState.planets
            .mapNotNull { it.periodDays }.filter { it > 0 }.minOrNull()
        val targetDps = if (minPeriod != null) minPeriod / 5.0 else 73.0
        val best = TIME_SCALE_STEPS.indices.minByOrNull {
            kotlin.math.abs(TIME_SCALE_STEPS[it] - targetDps)
        } ?: 7
        mutableIntStateOf(best)
    }
    val daysPerSecond = TIME_SCALE_STEPS[timeScaleIndex]

    // Build and post OrbitalParams when state changes
    val starColor = TeffColor.forStar(orbitalState.starTeffK, orbitalState.starSpectralType)
        ?: colors.textTertiary
    val companionColor = if (orbitalState.isCircumbinary) {
        TeffColor.forStar(orbitalState.companionTeffK, orbitalState.companionSpectralType) ?: colors.textTertiary
    } else colors.textTertiary

    // Fade HZ + starfield smoothly when toggled (mirrors the star map filter fade).
    val fadeSpec = tween<Float>(durationMillis = 400, easing = FastOutSlowInEasing)
    val hzAlpha by animateFloatAsState(
        targetValue = if (showHabitableZone) 1f else 0f,
        animationSpec = fadeSpec,
        label = "hzAlpha",
    )
    val starfieldAlpha by animateFloatAsState(
        targetValue = if (showStarfield) 1f else 0f,
        animationSpec = fadeSpec,
        label = "starfieldAlpha",
    )

    val orbitalParams = remember(orbitalState, daysPerSecond, starfieldData, hzAlpha, starfieldAlpha) {
        OrbitalParams(
            planets = orbitalState.planets.map { p ->
                val pColor = Color(p.dominantColor.toInt())
                OrbitalParamsEntry(
                    smaAU = p.smaAU,
                    eccentricity = p.eccentricity,
                    periodDays = p.periodDays,
                    radiusEarth = p.radiusEarth,
                    colorR = pColor.red,
                    colorG = pColor.green,
                    colorB = pColor.blue,
                    isEstimated = p.isEstimated,
                    relativeInclinationDeg = p.relativeInclinationDeg,
                    argPeriapsisDeg = p.argPeriapsisDeg,
                    longAscNodeDeg = p.longAscNodeDeg,
                    transitMidpointBJD = p.transitMidpointBJD,
                    timeOfPeriapsisBJD = p.timeOfPeriapsisBJD,
                )
            },
            starRadiusSolar = orbitalState.starRadiusSolar,
            starColorR = starColor.red,
            starColorG = starColor.green,
            starColorB = starColor.blue,
            hzInnerAU = orbitalState.habitableZone?.innerAU,
            hzOuterAU = orbitalState.habitableZone?.outerAU,
            hzAlpha = hzAlpha,
            isPlaying = true,
            daysPerSecond = daysPerSecond,
            isVisible = true,
            version = orbitalState.hashCode(),
            isCircumbinary = orbitalState.isCircumbinary,
            companionRadiusSolar = orbitalState.companionRadiusSolar,
            companionColorR = companionColor.red,
            companionColorG = companionColor.green,
            companionColorB = companionColor.blue,
            binaryStarSeparationAU = orbitalState.binaryStarSeparationAU,
            binaryEccentricity = orbitalState.binaryEccentricity.toFloat(),
            binaryArgPeriapsisDeg = orbitalState.binaryArgPeriapsisDeg.toFloat(),
            binaryOrbitalPeriodDays = orbitalState.binaryOrbitalPeriodDays,
            primaryMassFraction = run {
                val pm = orbitalState.primaryMassSolar
                val cm = orbitalState.companionMassSolar
                if (pm != null && cm != null && pm + cm > 0) {
                    (pm / (pm + cm)).toFloat()
                } else 0.5f
            },
            starfieldPositions = starfieldData?.positions ?: FloatArray(0),
            starfieldColors = starfieldData?.colors ?: FloatArray(0),
            starfieldSizes = starfieldData?.sizes ?: FloatArray(0),
            starfieldCount = starfieldData?.count ?: 0,
            solPosition = starfieldData?.solPosition ?: floatArrayOf(0f, 0f, 0f),
            solColor = starfieldData?.solColor ?: floatArrayOf(0f, 0f, 0f),
            solSize = starfieldData?.solSize ?: 0f,
            starfieldVersion = starfieldData?.hashCode() ?: 0,
            starfieldAlpha = starfieldAlpha,
        )
    }
    bridge.post(orbitalParams)

    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            bridge.post(orbitalParams.copy(isVisible = false))
        }
    }

    Box(modifier = modifier.fillMaxSize().background(colors.background)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { containerSize = it },
        ) {
            // GL view
            AndroidView(
                factory = { ctx ->
                    val renderer = OrbitalRenderer(appContext, cameraController, bridge)
                    renderer.resetToRealTime()
                    rendererRef = renderer
                    ExoGLSurfaceView(ctx, renderer, cameraController).also { glView ->
                        glViewRef = glView
                        glView.onTap = { x, y ->
                            val elapsed = renderer.currentElapsedDays
                            val visible = renderer.visiblePlanets
                            val refJD = renderer.referenceJD
                            val hit = handleOrbitalTap(
                                x, y, orbitalState, containerSize,
                                cameraController, elapsed, visible, refJD,
                                renderer.binaryStarPositions,
                            )
                            if (hit != null) {
                                selectedObject = hit.first
                                selectedScreenPos = hit.second
                            } else {
                                selectedObject = null
                            }
                        }
                    }
                },
                update = { glView ->
                    // Hide the native Surface when the Star Map tab is
                    // inactive — Compose alpha/zIndex can't cover a
                    // GLSurfaceView at the window layer.
                    glView.visibility = if (isTabActive) View.VISIBLE else View.GONE
                },
                modifier = Modifier.fillMaxSize(),
            )

            // Back breadcrumb (top-left)
            val orbitalBackInteraction = remember { MutableInteractionSource() }
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(horizontal = spacing.lg, vertical = spacing.lg)
                    .pushOnPress(orbitalBackInteraction)
                    .clickable(
                        indication = null,
                        interactionSource = orbitalBackInteraction,
                        onClick = onBack,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OrbitalBackChevron(modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(spacing.sm))
                BasicText(
                    text = "Back",
                    style = type.bodyMedium.copy(color = colors.accentGold),
                )
            }

            // Toggle buttons (top-right)
            val isAccessible = ExoTheme.isAccessible
            val btnSize = if (isAccessible) 44.dp else 36.dp
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = spacing.xxxl, top = 14.dp),
            ) {
                if (orbitalState.habitableZone != null) {
                    Box(
                        modifier = Modifier
                            .size(btnSize)
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.surfaceCard.copy(alpha = 0.85f))
                            .touchRipple(
                                color = Color.White,
                                startAlpha = 0.22f,
                                onClick = onToggleHabitableZone,
                            )
                            .border(1.dp, colors.surfaceBorder, RoundedCornerShape(4.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        HabitableZoneToggleIcon(visible = showHabitableZone)
                    }
                }
                Box(
                    modifier = Modifier
                        .size(btnSize)
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.surfaceCard.copy(alpha = 0.85f))
                        .touchRipple(
                            color = Color.White,
                            startAlpha = 0.22f,
                            onClick = onToggleStarfield,
                        )
                        .border(1.dp, colors.surfaceBorder, RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    StarfieldToggleIcon(visible = showStarfield)
                }
            }

            // Time scale slider (bottom) — inset above the bottom nav so it
            // isn't hidden underneath the opaque nav bar. Slides up + fades
            // in on entry; slides down + fades out on exit, concurrent with
            // the camera zoom-out.
            val sliderAnim = remember { Animatable(0f) }
            var sliderHeightPx by remember { mutableIntStateOf(0) }
            LaunchedEffect(Unit) {
                sliderAnim.animateTo(1f, tween(350, easing = FastOutSlowInEasing))
            }
            LaunchedEffect(isExiting) {
                if (isExiting) {
                    sliderAnim.animateTo(0f, tween(350, easing = FastOutSlowInEasing))
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { sliderHeightPx = it.height }
                    .graphicsLayer {
                        translationY = (1f - sliderAnim.value) * sliderHeightPx.toFloat()
                        alpha = sliderAnim.value
                    },
            ) {
                TimeScaleSlider(
                    stepIndex = timeScaleIndex,
                    onStepChange = { newIndex ->
                        // RT from the slider smoothly interpolates the
                        // simulated clock back to real time; initial-load
                        // entry still uses the instant `resetToRealTime()`
                        // path because there's nothing to interpolate from.
                        if (newIndex == 0) rendererRef?.smoothToRealTime()
                        timeScaleIndex = newIndex
                    },
                    modifier = Modifier
                        .padding(
                            start = spacing.lg,
                            end = spacing.lg,
                            bottom = spacing.lg + LocalBottomBarHeight.current,
                        )
                        .onGloballyPositioned { coords ->
                            val pos = coords.positionInParent()
                            val w = coords.size.width.toFloat()
                            val h = coords.size.height.toFloat()
                            glViewRef?.exclusionZones = listOf(
                                android.graphics.RectF(pos.x, pos.y, pos.x + w, pos.y + h),
                            )
                        },
                )
            }

            // Continuously re-project displayed object's position — follows
            // the latched selection so projection keeps updating during the
            // exit animation.
            LaunchedEffect(displayedSelection) {
                val sel = displayedSelection ?: return@LaunchedEffect
                while (true) {
                    withFrameNanos { }
                    val renderer = rendererRef ?: continue
                    val elapsed = renderer.currentElapsedDays
                    val refJD = renderer.referenceJD
                    val pos = projectSelection(
                        sel, orbitalState, containerSize, cameraController, elapsed, refJD,
                        renderer.binaryStarPositions,
                    )
                    if (pos != null) selectedScreenPos = pos
                }
            }

            // Sol indicator — circle + label, below tooltips in z-order
            val solPos = starfieldData?.solPosition
            if (solPos != null && containerSize.width > 0 && starfieldAlpha > 0.001f) {
                var solScreenPos by remember { mutableStateOf<Offset?>(null) }
                var solInFront by remember { mutableStateOf(false) }

                LaunchedEffect(solPos) {
                    while (true) {
                        withFrameNanos { }
                        val view = cameraController.viewMatrix
                        val rotView = view.copyOf()
                        rotView[12] = 0f; rotView[13] = 0f; rotView[14] = 0f
                        val proj = cameraController.projectionMatrix
                        val (pos, front) = RayCaster.projectToScreenExtended(
                            solPos[0], solPos[1], solPos[2],
                            containerSize.width, containerSize.height,
                            rotView, proj,
                        )
                        solScreenPos = pos
                        solInFront = front
                    }
                }

                val sp = solScreenPos
                if (sp != null && solInFront) {
                    val density = LocalDensity.current
                    val circleRadius = with(density) { 8.dp.toPx() }
                    val circleStroke = with(density) { 1.dp.toPx() }
                    val labelGap = with(density) { 3.dp.toPx() }
                    val labelColor = colors.textSecondary.copy(alpha = 0.7f * starfieldAlpha)

                    val solLabelStyle = type.labelSmall.copy(color = labelColor)
                    val solTextMeasurer = rememberTextMeasurer()
                    val solLayout = remember(solLabelStyle) {
                        solTextMeasurer.measure("Sol", solLabelStyle)
                    }
                    val labelX = sp.x - solLayout.size.width / 2f
                    val labelY = sp.y + circleRadius + labelGap

                    // Circle + label drawn together in a single full-size overlay
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawBehind {
                                drawCircle(
                                    color = labelColor,
                                    radius = circleRadius,
                                    center = sp,
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                                        width = circleStroke,
                                    ),
                                )
                                if (labelX >= 0f && labelY >= 0f &&
                                    labelX + solLayout.size.width <= containerSize.width &&
                                    labelY + solLayout.size.height <= containerSize.height
                                ) {
                                    drawText(
                                        solLayout,
                                        topLeft = Offset(labelX, labelY),
                                    )
                                }
                            },
                    )
                }
            }

            // Tooltip — uses latched displayedSelection so the exit
            // animation can play after selectedObject goes null.
            val sel = displayedSelection
            if (sel != null) {
                // Objects projected under the bottom nav read as off-screen so
                // the tooltip switches to chevron mode instead of drawing a
                // dashed line into an invisible region.
                val navPx = with(LocalDensity.current) { LocalBottomBarHeight.current.toPx() }
                val effectiveBottomY = (containerSize.height - navPx).coerceAtLeast(0f)
                val isOnScreen = selectedScreenPos.x in 0f..containerSize.width.toFloat() &&
                    selectedScreenPos.y in 0f..effectiveBottomY
                // Compute stop distance from actual rendered icon radius
                val gap = with(LocalDensity.current) { 3.dp.toPx() }
                val iconRadius = when (sel) {
                    is OrbitalSelection.Star -> (rendererRef?.starScreenRadius ?: 25f) + gap
                    is OrbitalSelection.Planet -> {
                        val idx = orbitalState.planets.indexOfFirst { it.name == sel.name }
                        val radii = rendererRef?.planetScreenRadii
                        val r = if (idx >= 0 && radii != null && idx < radii.size) radii[idx] else 15f
                        r + gap
                    }
                }
                // Resolve star info for the selected star (primary or companion)
                val tooltipHostname: String
                val tooltipDisplayName: String
                val tooltipSpectralType: String?
                val tooltipTeffK: Double?
                if (sel is OrbitalSelection.Star && !sel.isPrimary) {
                    val compHost = orbitalState.companionHostname ?: starHostname
                    tooltipHostname = compHost
                    val compProper = ProperNames.forStar(compHost)
                    tooltipDisplayName = if (settings.useProperNames && compProper != null) compProper else compHost
                    tooltipSpectralType = orbitalState.companionSpectralType
                    tooltipTeffK = orbitalState.companionTeffK
                } else {
                    tooltipHostname = starHostname
                    tooltipDisplayName = starDisplayName
                    tooltipSpectralType = orbitalState.starSpectralType
                    tooltipTeffK = orbitalState.starTeffK
                }
                OrbitalTooltip(
                    selection = sel,
                    screenPos = selectedScreenPos,
                    isOnScreen = isOnScreen,
                    containerSize = containerSize,
                    stopDistance = iconRadius,
                    starHostname = tooltipHostname,
                    starDisplayName = tooltipDisplayName,
                    starSpectralType = tooltipSpectralType,
                    starTeffK = tooltipTeffK,
                    settings = settings,
                    onAction = {
                        when (sel) {
                            is OrbitalSelection.Planet -> onPlanetSelected(sel.name)
                            is OrbitalSelection.Star -> onViewStar()
                        }
                    },
                    visible = selectedObject != null,
                    onDismissComplete = {
                        if (selectedObject == null) displayedSelection = null
                    },
                    bottomInsetPx = navPx.toInt(),
                )
            }

        }
    }
}

// --- Selection types ---

private sealed interface OrbitalSelection {
    data class Planet(
        val name: String,
        val compositionClass: com.tadmor.domain.classification.CompositionClass,
        val fullLabel: String,
        val smaAU: Double,
        val isEstimated: Boolean,
    ) : OrbitalSelection

    data class Star(val isPrimary: Boolean = true) : OrbitalSelection
}

// --- Tap handling & projection ---

private val OM = com.tadmor.domain.classification.OrbitalMechanics

/**
 * Computes a planet's current GL position from elapsed days,
 * matching the renderer's rebuildPlanetPositions logic exactly.
 */
private fun computePlanetGLPosition(
    planet: OrbitalPlanetState,
    maxAU: Double,
    elapsedDays: Double,
    referenceJD: Double = 0.0,
): FloatArray {
    val scaleRadius = 5.0
    val a = planet.smaAU
    val e = planet.eccentricity

    val (xAU, zAU) = if (planet.periodDays != null && planet.periodDays > 0) {
        val simulatedJD = referenceJD + elapsedDays
        val epoch = planet.timeOfPeriapsisBJD ?: planet.transitMidpointBJD
        val M = if (epoch != null && referenceJD > 0.0) {
            OM.meanAnomalyAtEpoch(
                simulatedJD, epoch, planet.periodDays,
                eccentricity = e,
                argPeriapsisRad = Math.toRadians(planet.argPeriapsisDeg),
                isTransitEpoch = planet.timeOfPeriapsisBJD == null,
            )
        } else {
            OM.meanAnomaly(elapsedDays, planet.periodDays)
        }
        val E = OM.solveKeplerEquation(M, e)
        OM.orbitalPosition(a, e, E)
    } else {
        OM.orbitalPosition(a, e, 0.0)
    }

    val aScaled = if (maxAU > 0) ((a / maxAU) * scaleRadius).toFloat() else 0f
    val ratio = if (a > 0) aScaled / a.toFloat() else 1f
    val xf = (xAU * ratio).toFloat()
    val zf = (zAU * ratio).toFloat()

    // ω rotation
    val wRad = Math.toRadians(planet.argPeriapsisDeg).toFloat()
    val cosW = kotlin.math.cos(wRad); val sinW = kotlin.math.sin(wRad)
    val x1 = xf * cosW - zf * sinW
    val z1 = xf * sinW + zf * cosW

    // i tilt
    val iRad = Math.toRadians(planet.relativeInclinationDeg).toFloat()
    val cosI = kotlin.math.cos(iRad); val sinI = kotlin.math.sin(iRad)
    val y2 = z1 * sinI; val z2 = z1 * cosI

    // Ω rotation
    val oRad = Math.toRadians(planet.longAscNodeDeg).toFloat()
    val cosO = kotlin.math.cos(oRad); val sinO = kotlin.math.sin(oRad)
    return floatArrayOf(x1 * cosO + z2 * sinO, y2, -x1 * sinO + z2 * cosO)
}

private fun handleOrbitalTap(
    screenX: Float,
    screenY: Float,
    orbitalState: OrbitalState,
    containerSize: IntSize,
    camera: CameraController,
    elapsedDays: Double,
    visiblePlanets: BooleanArray,
    referenceJD: Double = 0.0,
    binaryStarPositions: FloatArray = FloatArray(6),
): Pair<OrbitalSelection, Offset>? {
    if (containerSize.width == 0) return null

    val maxAU = orbitalState.planets.maxOfOrNull { it.smaAU } ?: 1.0
    val thresholdPx = 60f
    val thresholdSq = thresholdPx * thresholdPx

    var bestDist = Float.MAX_VALUE
    var bestSelection: OrbitalSelection? = null
    var bestPos = Offset.Zero

    fun checkStar(x: Float, y: Float, z: Float, isPrimary: Boolean) {
        val proj = RayCaster.projectToScreen(
            x, y, z,
            containerSize.width, containerSize.height,
            camera.viewMatrix, camera.projectionMatrix,
        )
        if (proj != null) {
            val dx = proj.x - screenX
            val dy = proj.y - screenY
            val dist = dx * dx + dy * dy
            if (dist < bestDist && dist < thresholdSq) {
                bestDist = dist
                bestSelection = OrbitalSelection.Star(isPrimary = isPrimary)
                bestPos = proj
            }
        }
    }

    if (orbitalState.isCircumbinary && binaryStarPositions.size >= 6) {
        // Check both stars at their animated positions
        checkStar(binaryStarPositions[0], binaryStarPositions[1], binaryStarPositions[2], true)
        checkStar(binaryStarPositions[3], binaryStarPositions[4], binaryStarPositions[5], false)
    } else {
        // Single star at origin
        checkStar(0f, 0f, 0f, true)
    }

    // Check planets at their current animated positions (skip hidden ones)
    for ((i, planet) in orbitalState.planets.withIndex()) {
        if (i < visiblePlanets.size && !visiblePlanets[i]) continue

        val pos = computePlanetGLPosition(planet, maxAU, elapsedDays, referenceJD)
        val projected = RayCaster.projectToScreen(
            pos[0], pos[1], pos[2],
            containerSize.width, containerSize.height,
            camera.viewMatrix, camera.projectionMatrix,
        )
        if (projected != null) {
            val dx = projected.x - screenX
            val dy = projected.y - screenY
            val dist = dx * dx + dy * dy
            if (dist < bestDist && dist < thresholdSq) {
                bestDist = dist
                bestSelection = OrbitalSelection.Planet(
                    name = planet.name,
                    compositionClass = planet.compositionClass,
                    fullLabel = planet.fullLabel,
                    smaAU = planet.smaAU,
                    isEstimated = planet.isEstimated,
                )
                bestPos = projected
            }
        }
    }

    return if (bestSelection != null) bestSelection to bestPos else null
}

/** Projects the selected object's current position to screen space. */
private fun projectSelection(
    selection: OrbitalSelection,
    orbitalState: OrbitalState,
    containerSize: IntSize,
    camera: CameraController,
    elapsedDays: Double,
    referenceJD: Double = 0.0,
    binaryStarPositions: FloatArray = FloatArray(6),
): Offset? {
    if (containerSize.width == 0) return null
    return when (selection) {
        is OrbitalSelection.Star -> {
            val (sx, sy, sz) = if (orbitalState.isCircumbinary && binaryStarPositions.size >= 6) {
                if (selection.isPrimary) {
                    Triple(binaryStarPositions[0], binaryStarPositions[1], binaryStarPositions[2])
                } else {
                    Triple(binaryStarPositions[3], binaryStarPositions[4], binaryStarPositions[5])
                }
            } else {
                Triple(0f, 0f, 0f)
            }
            RayCaster.projectToScreen(
                sx, sy, sz,
                containerSize.width, containerSize.height,
                camera.viewMatrix, camera.projectionMatrix,
            )
        }
        is OrbitalSelection.Planet -> {
            val maxAU = orbitalState.planets.maxOfOrNull { it.smaAU } ?: 1.0
            val planet = orbitalState.planets.find { it.name == selection.name } ?: return null
            val pos = computePlanetGLPosition(planet, maxAU, elapsedDays, referenceJD)
            RayCaster.projectToScreen(
                pos[0], pos[1], pos[2],
                containerSize.width, containerSize.height,
                camera.viewMatrix, camera.projectionMatrix,
            )
        }
    }
}

// Wide camera distance used only by the intro/exit transition animations.
// Intro starts here and zooms IN to the normal cap; exit zooms OUT back to
// this value before the parent flips to the map view.
private const val ORBITAL_INTRO_WIDE_DIST = 60f

// Normal user-facing max zoom-out distance. The camera is clamped to this
// after the intro animation completes, and re-raised to ORBITAL_INTRO_WIDE_DIST
// while the exit animation runs.
private const val ORBITAL_NORMAL_MAX_DIST = 30f

// --- Time scale ---

private val TIME_SCALE_STEPS = doubleArrayOf(
    1.0 / 86400.0,   // 1 sec/sec (real-time)
    1.0 / 24.0,       // 1 hour/sec
    6.0 / 24.0,       // 6 hours/sec
    12.0 / 24.0,      // 12 hours/sec
    1.0,              // 1 day/sec
    2.0,              // 2 days/sec
    3.0,              // 3 days/sec
    7.0,              // 1 week/sec
    14.0,             // 2 weeks/sec
    21.0,             // 3 weeks/sec
    30.44,            // 1 month/sec
    60.88,            // 2 months/sec
    91.31,            // 3 months/sec
    152.19,           // 5 months/sec
    243.5,            // 8 months/sec
    365.25,           // 1 year/sec
)

private val TIME_SCALE_LABELS = arrayOf(
    "1 sec / sec",
    "1 hour / sec",
    "6 hours / sec",
    "12 hours / sec",
    "1 day / sec",
    "2 days / sec",
    "3 days / sec",
    "1 week / sec",
    "2 weeks / sec",
    "3 weeks / sec",
    "1 month / sec",
    "2 months / sec",
    "3 months / sec",
    "5 months / sec",
    "8 months / sec",
    "1 year / sec",
)

// Indices that get a short label below the track
private val LABELED_NOTCHES = mapOf(
    0 to "RT",
    1 to "1h",
    4 to "1d",
    7 to "1w",
    10 to "1mo",
    15 to "1yr",
)

// --- Small UI components ---

@Composable
private fun TimeScaleSlider(
    stepIndex: Int,
    onStepChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type
    val maxStep = TIME_SCALE_STEPS.size - 1
    val shape = RoundedCornerShape(8.dp)

    val density = LocalDensity.current
    var trackWidthPx by remember { mutableFloatStateOf(0f) }
    val thumbRadius = with(density) { 8.dp.toPx() }
    val trackHeight = with(density) { 3.dp.toPx() }
    val labelStyle = type.labelSmall
    val textMeasurer = rememberTextMeasurer()

    var dragPx by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var lastEmitted by remember { mutableIntStateOf(stepIndex) }

    // Thumb "pickup" scale — grows on touch-down, springs back on release.
    // Shared cadence with the filter-sheet PlanetCountSlider so all sliders
    // in the app feel tactile in the same way.
    val thumbScale = remember { Animatable(1f) }
    LaunchedEffect(isDragging) {
        thumbScale.animateTo(
            if (isDragging) 1.4f else 1f,
            tween(180, easing = FastOutSlowInEasing),
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceCard.copy(alpha = 0.85f))
            .border(1.dp, colors.surfaceBorder, shape)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Current rate label
        BasicText(
            text = TIME_SCALE_LABELS[stepIndex],
            style = type.labelLarge.copy(color = colors.textSecondary),
        )

        Spacer(Modifier.height(4.dp))

        // Slider track
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .onSizeChanged { size ->
                    trackWidthPx = size.width.toFloat()
                    if (!isDragging) {
                        val trackRange = trackWidthPx - 2f * thumbRadius
                        dragPx = stepIndex.toFloat() / maxStep * trackRange + thumbRadius
                    }
                }
                .pointerInput(Unit) {
                    // One unified gesture handler so press, tap-to-snap, and
                    // drag don't fight over the same pointer. Composing
                    // `detectTapGestures` + `draggable` previously had two
                    // problems: tryAwaitRelease cancelled when the user moved
                    // past slop or vertically (killing the pickup scale
                    // mid-drag), and the two detectors competed for the down
                    // event in a way that left `draggable`'s delta accounting
                    // off (thumb drifted faster than the finger).
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        isDragging = true
                        try {
                            val slop = viewConfiguration.touchSlop
                            var draggedPastSlop = false
                            var lastX = down.position.x
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                    ?: break
                                if (!change.pressed) {
                                    if (!draggedPastSlop) {
                                        // Tap → snap to position
                                        if (trackWidthPx > 0f) {
                                            val trackRange = trackWidthPx - 2f * thumbRadius
                                            val rel = ((change.position.x - thumbRadius) / trackRange).coerceIn(0f, 1f)
                                            val tapped = (rel * maxStep + 0.5f).toInt().coerceIn(0, maxStep)
                                            dragPx = tapped.toFloat() / maxStep * trackRange + thumbRadius
                                            onStepChange(tapped)
                                        }
                                    } else if (trackWidthPx > 0f) {
                                        // Drag release → snap to nearest step
                                        val trackRange = trackWidthPx - 2f * thumbRadius
                                        val rel = ((dragPx - thumbRadius) / trackRange).coerceIn(0f, 1f)
                                        val snapped = (rel * maxStep + 0.5f).toInt().coerceIn(0, maxStep)
                                        dragPx = snapped.toFloat() / maxStep * trackRange + thumbRadius
                                        if (snapped != stepIndex) onStepChange(snapped)
                                    }
                                    break
                                }
                                if (!draggedPastSlop) {
                                    val dx = change.position.x - down.position.x
                                    val dy = change.position.y - down.position.y
                                    if (dx * dx + dy * dy >= slop * slop) {
                                        draggedPastSlop = true
                                        // Snap thumb under the finger so subsequent
                                        // delta tracking is 1:1 from the touch point.
                                        if (trackWidthPx > 0f) {
                                            dragPx = down.position.x.coerceIn(thumbRadius, trackWidthPx - thumbRadius)
                                            val trackRange = trackWidthPx - 2f * thumbRadius
                                            val rel = ((dragPx - thumbRadius) / trackRange).coerceIn(0f, 1f)
                                            lastEmitted = (rel * maxStep + 0.5f).toInt().coerceIn(0, maxStep)
                                        }
                                        lastX = change.position.x
                                    }
                                }
                                if (draggedPastSlop) {
                                    val deltaX = change.position.x - lastX
                                    if (trackWidthPx > 0f) {
                                        dragPx = (dragPx + deltaX).coerceIn(thumbRadius, trackWidthPx - thumbRadius)
                                        val trackRange = trackWidthPx - 2f * thumbRadius
                                        val rel = ((dragPx - thumbRadius) / trackRange).coerceIn(0f, 1f)
                                        val snapped = (rel * maxStep + 0.5f).toInt().coerceIn(0, maxStep)
                                        if (snapped != lastEmitted) {
                                            lastEmitted = snapped
                                            onStepChange(snapped)
                                        }
                                    }
                                    lastX = change.position.x
                                    change.consume()
                                }
                            }
                        } finally {
                            isDragging = false
                        }
                    }
                }
                .drawBehind {
                    val trackCy = size.height * 0.35f
                    val trackStartX = thumbRadius
                    val trackEndX = size.width - thumbRadius
                    val trackRange = trackEndX - trackStartX
                    val activeX = stepIndex.toFloat() / maxStep * trackRange + trackStartX

                    // Background track
                    drawRoundRect(
                        color = colors.surfaceRaised,
                        topLeft = Offset(trackStartX, trackCy - trackHeight / 2f),
                        size = Size(trackRange, trackHeight),
                        cornerRadius = CornerRadius(trackHeight / 2f),
                    )

                    // Active portion
                    drawRoundRect(
                        color = colors.accentGold,
                        topLeft = Offset(trackStartX, trackCy - trackHeight / 2f),
                        size = Size(activeX - trackStartX, trackHeight),
                        cornerRadius = CornerRadius(trackHeight / 2f),
                    )

                    // Step dots
                    for (i in 0..maxStep) {
                        val x = i.toFloat() / maxStep * trackRange + trackStartX
                        val dotColor = if (i <= stepIndex) colors.accentGold else colors.divider
                        drawCircle(dotColor, radius = 2.5f.dp.toPx(), center = Offset(x, trackCy))
                    }

                    // Thumb
                    val scaledThumbR = thumbRadius * thumbScale.value
                    drawCircle(colors.accentGold, radius = scaledThumbR, center = Offset(activeX, trackCy))
                    drawCircle(colors.surfaceCard, radius = scaledThumbR * 0.5f, center = Offset(activeX, trackCy))

                    // Key notch labels below track
                    val labelTop = trackCy + thumbRadius + 3.dp.toPx()
                    for ((i, label) in LABELED_NOTCHES) {
                        val x = i.toFloat() / maxStep * trackRange + trackStartX
                        val labelColor = if (i == stepIndex) colors.accentGold else colors.textMuted
                        val measured = textMeasurer.measure(
                            text = label,
                            style = labelStyle.copy(color = labelColor),
                        )
                        drawText(
                            measured,
                            topLeft = Offset(
                                x - measured.size.width / 2f,
                                labelTop,
                            ),
                        )
                    }
                },
        )
    }
}

@Composable
private fun OrbitalBackChevron(modifier: Modifier = Modifier) {
    val color = ExoTheme.colors.accentGold
    Box(
        modifier = modifier.drawBehind {
            val sw = 1.2.dp.toPx()
            val cx = size.width * 0.55f
            val top = size.height * 0.2f
            val bot = size.height * 0.8f
            val left = size.width * 0.25f
            drawLine(color, Offset(cx, top), Offset(left, size.height / 2f), sw, cap = StrokeCap.Round)
            drawLine(color, Offset(left, size.height / 2f), Offset(cx, bot), sw, cap = StrokeCap.Round)
        },
    )
}

@Composable
private fun OrbitalTooltip(
    selection: OrbitalSelection,
    screenPos: Offset,
    isOnScreen: Boolean,
    containerSize: IntSize,
    stopDistance: Float,
    starHostname: String,
    starDisplayName: String,
    starSpectralType: String?,
    starTeffK: Double?,
    settings: UserSettings,
    onAction: () -> Unit,
    visible: Boolean = true,
    onDismissComplete: () -> Unit = {},
    bottomInsetPx: Int = 0,
) {
    val density = LocalDensity.current
    val colors = ExoTheme.colors
    val type = ExoTheme.type
    val spacing = ExoTheme.spacing
    val shape = RoundedCornerShape(8.dp)

    val tooltipWidth = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val tooltipHeight = remember { androidx.compose.runtime.mutableIntStateOf(0) }

    // Bottom-to-top reveal + reverse close. Keyed on selection identity so
    // switching restarts; on visible so dismissal reverses. The close
    // animation runs from the current progress down to 0, then fires
    // onDismissComplete so the parent can unmount.
    var progress by remember(selection) { mutableFloatStateOf(0f) }
    LaunchedEffect(selection, visible) {
        val target = if (visible) 1f else 0f
        if (progress == target) {
            if (!visible) onDismissComplete()
            return@LaunchedEffect
        }
        val startProgress = progress
        val delta = target - startProgress
        val durationMs = 220f
        val startNanos = withFrameNanos { it }
        var linear = 0f
        while (linear < 1f) {
            val frameNanos = withFrameNanos { it }
            val elapsedMs = (frameNanos - startNanos) / 1_000_000f
            linear = (elapsedMs / durationMs).coerceIn(0f, 1f)
            progress = startProgress + delta * FastOutSlowInEasing.transform(linear)
        }
        if (!visible) onDismissComplete()
    }

    // Wider margin when off-screen to leave room for the direction chevron
    val margin = with(density) {
        if (isOnScreen) spacing.sm.roundToPx() else 20.dp.roundToPx()
    }

    // Center on object's X, clamp to screen
    val tooltipX = run {
        val raw = screenPos.x.toInt() - tooltipWidth.intValue / 2
        raw.coerceIn(margin, (containerSize.width - tooltipWidth.intValue - margin).coerceAtLeast(0))
    }

    // Y: above/below when on-screen, centered on star when off-screen
    val gap = with(density) { 24.dp.roundToPx() }
    val tooltipY = if (isOnScreen) {
        val isAbove = screenPos.y.toInt() - tooltipHeight.intValue - gap >= margin
        if (isAbove) screenPos.y.toInt() - tooltipHeight.intValue - gap
        else screenPos.y.toInt() + gap
    } else {
        val usableBottom = (containerSize.height - bottomInsetPx).coerceAtLeast(0)
        val raw = screenPos.y.toInt() - tooltipHeight.intValue / 2
        raw.coerceIn(margin, (usableBottom - tooltipHeight.intValue - margin).coerceAtLeast(0))
    }

    // No scrim — touches pass through to GL view
    Box(modifier = Modifier.fillMaxSize()) {
        val lineColor = colors.textMuted

        // Dashed connector line OR off-screen chevron
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val tw = tooltipWidth.intValue.toFloat()
            val th = tooltipHeight.intValue.toFloat()
            if (tw <= 0f || th <= 0f) return@Canvas

            if (isOnScreen) {
                // Dashed connector line from tooltip edge to just short of the object
                val tooltipCenterX = tooltipX + tw / 2f
                val tooltipEdgeY = if (screenPos.y > tooltipY + th) {
                    (tooltipY + th).toFloat()
                } else {
                    tooltipY.toFloat()
                }
                val start = Offset(tooltipCenterX, tooltipEdgeY)
                val dx = screenPos.x - start.x
                val dy = screenPos.y - start.y
                val len = kotlin.math.sqrt(dx * dx + dy * dy)
                // Stop just short of the icon edge
                if (len > stopDistance && progress > 0f) {
                    val ratio = (len - stopDistance) / len
                    val end = Offset(start.x + dx * ratio, start.y + dy * ratio)
                    // Grow the dashed line from the lower screen endpoint
                    // upward for a bottom-to-top reveal.
                    val bottomPt = if (start.y >= end.y) start else end
                    val topPt = if (start.y >= end.y) end else start
                    val animEnd = Offset(
                        bottomPt.x + (topPt.x - bottomPt.x) * progress,
                        bottomPt.y + (topPt.y - bottomPt.y) * progress,
                    )
                    drawLine(
                        color = lineColor,
                        start = bottomPt,
                        end = animEnd,
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(4.dp.toPx(), 3.dp.toPx()),
                        ),
                    )
                }
            } else {
                // Direction chevron at tooltip edge pointing toward the object
                val cx = tooltipX + tw / 2f
                val cy = tooltipY + th / 2f
                val dirX = screenPos.x - cx
                val dirY = screenPos.y - cy
                val dirLen = kotlin.math.sqrt(dirX * dirX + dirY * dirY)

                if (dirLen > 1f && progress > 0f) {
                    val chevronColor = lineColor.copy(alpha = lineColor.alpha * progress)
                    val normX = dirX / dirLen
                    val normY = dirY / dirLen

                    // Ray from tooltip center → find intersection with tooltip rectangle edge
                    val halfW = tw / 2f
                    val halfH = th / 2f
                    val tR = if (normX > 0.001f) halfW / normX else Float.MAX_VALUE
                    val tL = if (normX < -0.001f) -halfW / normX else Float.MAX_VALUE
                    val tB = if (normY > 0.001f) halfH / normY else Float.MAX_VALUE
                    val tT = if (normY < -0.001f) -halfH / normY else Float.MAX_VALUE
                    val t = minOf(tR, tL, tB, tT)

                    // Arrow center: offset slightly outside the tooltip edge
                    val arrowGap = 8.dp.toPx()
                    val ax = cx + t * normX + arrowGap * normX
                    val ay = cy + t * normY + arrowGap * normY

                    // Rotated chevron: base shape points right, rotate to face object
                    val angleDeg = kotlin.math.atan2(normY, normX) * (180f / kotlin.math.PI.toFloat())
                    val sw = 1.2.dp.toPx()
                    val hw = 3.dp.toPx()
                    val hh = 4.dp.toPx()

                    rotate(angleDeg, pivot = Offset(ax, ay)) {
                        drawLine(chevronColor, Offset(ax - hw, ay - hh), Offset(ax + hw, ay), sw, cap = StrokeCap.Round)
                        drawLine(chevronColor, Offset(ax + hw, ay), Offset(ax - hw, ay + hh), sw, cap = StrokeCap.Round)
                    }
                }
            }
        }

        // Tooltip card
        Column(
            modifier = Modifier
                .offset { IntOffset(tooltipX, tooltipY) }
                .onSizeChanged {
                    tooltipWidth.intValue = it.width
                    tooltipHeight.intValue = it.height
                }
                .graphicsLayer {
                    // Bottom-anchored reveal: card grows from its bottom
                    // edge upward. Measured size is unaffected, so tooltip
                    // positioning math stays correct.
                    scaleY = progress
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    alpha = progress
                }
                .clip(shape)
                .background(colors.surfaceCard)
                .border(1.dp, colors.surfaceBorder, shape)
                .padding(horizontal = spacing.md, vertical = spacing.sm)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) { /* consume taps on tooltip */ },
        ) {
            when (selection) {
                is OrbitalSelection.Planet -> {
                    val classColor = selection.compositionClass.toColor()
                    val planetProperName = ProperNames.forPlanet(selection.name)
                    val planetDisplayName = if (settings.useProperNames && planetProperName != null) {
                        planetProperName
                    } else {
                        selection.name
                    }
                    BasicText(
                        text = planetDisplayName,
                        style = type.bodyMedium.copy(color = colors.textPrimary),
                    )
                    if (settings.useProperNames && planetProperName != null) {
                        Spacer(Modifier.height(1.dp))
                        BasicText(
                            text = selection.name,
                            style = type.labelLarge.copy(color = colors.textMuted),
                        )
                    }
                    Spacer(Modifier.height(spacing.xs))
                    // Full classification label respecting naming settings
                    BasicText(
                        text = formatOrbitalClassLabel(selection.fullLabel, settings),
                        style = type.labelLarge.copy(color = classColor.text),
                    )
                    Spacer(Modifier.height(spacing.xs))
                    BasicText(
                        text = if (selection.isEstimated) "~%.2f AU".format(selection.smaAU)
                        else "%.2f AU".format(selection.smaAU),
                        style = type.labelLarge.copy(
                            color = colors.textSecondary,
                            fontStyle = if (selection.isEstimated)
                                FontStyle.Italic
                            else FontStyle.Normal,
                        ),
                    )
                    Spacer(Modifier.height(spacing.sm))
                    ActionLink(text = "View planet", onClick = onAction)
                }

                is OrbitalSelection.Star -> {
                    // Display name (proper name if enabled)
                    BasicText(
                        text = starDisplayName,
                        style = type.bodyMedium.copy(color = colors.textPrimary),
                    )
                    // Show catalog name as subtitle when proper name is used
                    val properName = ProperNames.forStar(starHostname)
                    if (settings.useProperNames && properName != null) {
                        Spacer(Modifier.height(1.dp))
                        BasicText(
                            text = starHostname,
                            style = type.labelLarge.copy(color = colors.textMuted),
                        )
                    }
                    // Spectral type (colored, italic if estimated)
                    val displaySpectral = formatOrbitalStarSpectral(starSpectralType, starTeffK, settings.useEstimates)
                    if (displaySpectral != null) {
                        Spacer(Modifier.height(spacing.xs))
                        val spectralColor = TeffColor.forStar(starTeffK, starSpectralType) ?: colors.textTertiary
                        BasicText(
                            text = displaySpectral.first,
                            style = type.labelLarge.copy(
                                color = spectralColor,
                                fontStyle = if (displaySpectral.second) FontStyle.Italic
                                else FontStyle.Normal,
                            ),
                        )
                    }
                    Spacer(Modifier.height(spacing.sm))
                    ActionLink(text = "View star", onClick = onAction)
                }
            }
        }
    }
}

/** Returns (displayText, isEstimated) or null if no spectral info available. */
private fun formatOrbitalStarSpectral(
    catalogSpectralType: String?,
    teffK: Double?,
    useEstimates: Boolean,
): Pair<String, Boolean>? {
    catalogSpectralType?.let {
        return it.trim().replace("&plusmn;", "±").replace("&amp;", "&") to false
    }
    if (useEstimates && teffK != null) {
        val estimated = when {
            teffK >= 30000 -> "O"
            teffK >= 10000 -> "B"
            teffK >= 7500 -> "A"
            teffK >= 6000 -> "F"
            teffK >= 5200 -> "G"
            teffK >= 3700 -> "K"
            teffK >= 2400 -> "M"
            teffK >= 1300 -> "L"
            teffK >= 300 -> "T"
            else -> "Y"
        }
        return estimated to true
    }
    return null
}

/** Formats the full classification label respecting the user's class naming settings. */
private fun formatOrbitalClassLabel(fullLabel: String, settings: UserSettings): String {
    var result = fullLabel
    if (!settings.useTerra) result = result.replace("Terrestrial", "Earth", ignoreCase = true)
    if (!settings.useNeptune) result = result.replace("Neptune", "Ice Giant", ignoreCase = true)
    if (!settings.useJupiter) result = result.replace("Jupiter", "Gas Giant", ignoreCase = true)
    return result
}

@Composable
private fun ActionLink(text: String, onClick: () -> Unit) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .pushOnPress(interaction)
            .clickable(
                indication = null,
                interactionSource = interaction,
                onClick = onClick,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = text,
            style = type.labelLarge.copy(color = colors.accentGold),
        )
        Spacer(Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .size(12.dp)
                .drawBehind {
                    val sw = 1.2.dp.toPx()
                    val left = size.width * 0.3f
                    val right = size.width * 0.7f
                    val mid = size.height / 2f
                    val top = size.height * 0.2f
                    val bot = size.height * 0.8f
                    drawLine(colors.accentGold, Offset(left, top), Offset(right, mid), sw, cap = StrokeCap.Round)
                    drawLine(colors.accentGold, Offset(right, mid), Offset(left, bot), sw, cap = StrokeCap.Round)
                },
        )
    }
}

@Composable
private fun StarfieldToggleIcon(visible: Boolean) {
    val color = if (visible) ExoTheme.colors.textTertiary else ExoTheme.colors.textTertiary.copy(alpha = 0.5f)
    val iconSize = if (ExoTheme.isAccessible) 28.dp else 24.dp
    Box(
        modifier = Modifier
            .size(iconSize)
            .drawBehind {
                val w = size.width
                val h = size.height

                // Filled 8-pointed star path: 4 long cardinal spikes + 4 short diagonal spikes
                fun drawStar(cx: Float, cy: Float, r: Float) {
                    val rd = r * 0.45f // diagonal spike length
                    val ri = r * 0.18f // inner waist radius
                    val path = androidx.compose.ui.graphics.Path()
                    // 16 vertices: spike tip, waist, spike tip, waist, ...
                    // Starting from top (-90°), clockwise in 22.5° steps
                    for (i in 0 until 16) {
                        val angleDeg = i * 22.5f - 90f
                        val rad = angleDeg * (kotlin.math.PI / 180.0).toFloat()
                        val radius = when (i % 4) {
                            0 -> r     // cardinal spike tip
                            2 -> rd    // diagonal spike tip
                            else -> ri // pinched waist
                        }
                        val px = cx + radius * kotlin.math.cos(rad.toDouble()).toFloat()
                        val py = cy + radius * kotlin.math.sin(rad.toDouble()).toFloat()
                        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    }
                    path.close()
                    drawPath(path, color)
                }

                // Large star (top-left)
                drawStar(w * 0.28f, h * 0.25f, w * 0.24f)
                // Medium star (right)
                drawStar(w * 0.74f, h * 0.48f, w * 0.18f)
                // Small star (bottom-left)
                drawStar(w * 0.35f, h * 0.74f, w * 0.12f)

                // Diagonal slash when hidden
                if (!visible) {
                    drawLine(
                        color.copy(alpha = 1f),
                        Offset(w * 0.08f, h * 0.92f),
                        Offset(w * 0.92f, h * 0.08f),
                        1.5.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            },
    )
}

@Composable
private fun HabitableZoneToggleIcon(visible: Boolean) {
    val color = if (visible) ExoTheme.colors.textTertiary else ExoTheme.colors.textTertiary.copy(alpha = 0.5f)
    val iconSize = if (ExoTheme.isAccessible) 28.dp else 24.dp
    Box(
        modifier = Modifier
            .size(iconSize)
            .drawBehind {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val outerR = size.minDimension * 0.36f
                val innerR = size.minDimension * 0.22f
                val sw = 1.2.dp.toPx()

                drawCircle(color, radius = outerR, center = Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
                drawCircle(color, radius = innerR, center = Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(sw))

                // Diagonal slash when hidden
                if (!visible) {
                    drawLine(
                        color.copy(alpha = 1f),
                        Offset(size.width * 0.08f, size.height * 0.92f),
                        Offset(size.width * 0.92f, size.height * 0.08f),
                        1.5.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            },
    )
}
