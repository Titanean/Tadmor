package com.tadmor.app.ui.starmap

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.viewinterop.AndroidView
import android.view.View
import androidx.hilt.navigation.compose.hiltViewModel
import com.tadmor.app.gl.CameraController
import com.tadmor.app.gl.ExoGLSurfaceView
import com.tadmor.app.gl.RayCaster
import com.tadmor.app.ui.components.NavDestination
import com.tadmor.app.ui.components.StarTooltip
import com.tadmor.app.ui.components.touchRipple
import androidx.compose.ui.text.input.TextFieldValue
import com.tadmor.app.ui.system.OrbitalScreen
import com.tadmor.app.ui.theme.ExoColors
import com.tadmor.app.ui.theme.ExoTheme
import com.tadmor.app.ui.theme.LocalBottomBarHeight
import com.tadmor.app.ui.theme.TeffColor
import androidx.compose.ui.platform.LocalDensity
import com.tadmor.domain.model.DistanceUnit
import com.tadmor.domain.model.ProperNames
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Normal user-facing minimum camera distance for the star map.
private const val MAP_NORMAL_MIN_DIST = 0.3f

// Floor temporarily lowered below the normal minimum during the forward
// transition so the zoom-in has visible range to animate, even when the
// user is already maxed in on the target star. Restored after the reverse.
private const val MAP_TRANSITION_MIN_DIST = 0.03f

@Composable
fun StarMapScreen(
    selectedNav: NavDestination = NavDestination.STAR_MAP,
    onSelectNav: (NavDestination) -> Unit = {},
    onNavigateToSystemPlanet: (String, String?) -> Unit = { _, _ -> },
    pendingOrbitalHostname: String? = null,
    pendingOrbitalKey: Int = 0,
    onPendingOrbitalConsumed: () -> Unit = {},
    orbitalReturnsToSystem: Boolean = false,
    onOrbitalReturnToSystem: () -> Unit = {},
    isTabOnScreen: Boolean = true,
    viewModel: StarMapViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val starfield by viewModel.starfieldData.collectAsState()
    val colors = ExoTheme.colors
    val type = ExoTheme.type
    val spacing = ExoTheme.spacing
    val appContext = LocalContext.current.applicationContext

    // Cross-tab nav from System DETAIL → orbital. Keyed on the bumped
    // counter so re-tapping "View orbits" for the same host re-enters
    // orbital even if the star map already has it loaded. `onViewSystem`
    // does both `prepareOrbitalLoad` (background data) and `commitOrbital`
    // (mode flip) in one step — no intermediate animation since the
    // user-visible motion is the tab slide.
    LaunchedEffect(pendingOrbitalKey) {
        val host = pendingOrbitalHostname ?: return@LaunchedEffect
        if (pendingOrbitalKey == 0) return@LaunchedEffect
        viewModel.onViewSystem(host)
        onPendingOrbitalConsumed()
    }

    val cameraController = remember {
        CameraController(
            // Near plane must sit below MAP_TRANSITION_MIN_DIST so geometry
            // close to the camera (the target star itself, ring vertices on
            // the near side of Sol) isn't clipped during the zoom-in flight
            // toward a star.
            nearPlane = 0.01f,
            farPlane = 600f,
            minDistance = MAP_NORMAL_MIN_DIST,
            maxDistance = 300f,
            maxPanRadius = 270f,
        ).apply {
            setDistance(MAP_NORMAL_MIN_DIST)
            setOrbitAngles(0f, 30f)
        }
    }
    cameraController.invertControls = state.invertCameraControls

    // Track visibility for renderer optimization. Use isTabOnScreen so the
    // star map keeps rendering during tab transitions (not just when it is
    // the currently selected tab).
    val isVisible = isTabOnScreen
    DisposableEffect(isVisible) {
        viewModel.setVisible(isVisible)
        onDispose {}
    }

    // Track container size for tooltip positioning
    var containerSize by remember { mutableStateOf(IntSize(0, 0)) }

    // Continuously re-project selected star's world position to screen coordinates.
    // `displayedStar` latches the last non-null selection so the tooltip can
    // run its exit animation after state.selectedStar becomes null; it's
    // cleared by the tooltip's onDismissComplete callback.
    val selectedStar = state.selectedStar
    var displayedStar by remember { mutableStateOf<SelectedStarInfo?>(null) }
    LaunchedEffect(selectedStar) {
        if (selectedStar != null) displayedStar = selectedStar
    }
    var screenPos by remember { mutableStateOf(Offset.Zero) }
    var starInFront by remember { mutableStateOf(true) }
    var hasProjected by remember { mutableStateOf(false) }
    var isCameraOnStar by remember { mutableStateOf(false) }
    // True while any camera animation (orbital enter/exit, Sol home pan, star
    // center pan) is driving the camera. While set, the `isCameraOnStar` and
    // `isCameraOnSol` flags freeze — otherwise the home button / tooltip
    // center button would flip in and out as the camera passes through
    // intermediate positions mid-animation.
    var isCameraAnimating by remember { mutableStateOf(false) }

    LaunchedEffect(displayedStar, containerSize) {
        val selectedStar = displayedStar
        if (selectedStar != null && containerSize.width > 0 && containerSize.height > 0) {
            // Project immediately on selection
            val (pos, inFront) = RayCaster.projectToScreenExtended(
                selectedStar.worldPos[0], selectedStar.worldPos[1], selectedStar.worldPos[2],
                containerSize.width, containerSize.height,
                cameraController.viewMatrix, cameraController.projectionMatrix,
            )
            screenPos = pos
            starInFront = inFront
            hasProjected = true

            // Then update continuously while selected
            while (isActive) {
                delay(16) // ~60fps
                val (newPos, newInFront) = RayCaster.projectToScreenExtended(
                    selectedStar.worldPos[0], selectedStar.worldPos[1], selectedStar.worldPos[2],
                    containerSize.width, containerSize.height,
                    cameraController.viewMatrix, cameraController.projectionMatrix,
                )
                screenPos = newPos
                starInFront = newInFront
                if (!isCameraAnimating) {
                    val t = cameraController.targetPosition
                    val dx = t[0] - selectedStar.worldPos[0]
                    val dy = t[1] - selectedStar.worldPos[1]
                    val dz = t[2] - selectedStar.worldPos[2]
                    isCameraOnStar = dx * dx + dy * dy + dz * dz < 0.001f
                }
            }
        } else {
            hasProjected = false
            isCameraOnStar = false
        }
    }

    // Effective viewport excludes the bottom nav area — a star projected
    // under the nav should be treated as off-screen (tooltip switches to
    // chevron mode pointing toward it) because its actual screen position
    // is hidden.
    val navBarHeightDp = LocalBottomBarHeight.current
    val navBarHeightPx = with(LocalDensity.current) { navBarHeightDp.toPx() }
    val effectiveBottomY = (containerSize.height - navBarHeightPx).coerceAtLeast(0f)
    val isOnScreen = hasProjected && starInFront &&
        screenPos.x in 0f..containerSize.width.toFloat() &&
        screenPos.y in 0f..effectiveBottomY

    // Track whether camera is centered on Sol (origin). Skip the check while
    // any camera animation is in flight so the home button doesn't flicker
    // as the camera passes through the origin mid-animation.
    var isCameraOnSol by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(200)
            if (!isCameraAnimating) {
                isCameraOnSol = cameraController.isOnOrigin
            }
        }
    }

    // Filter sheet visibility
    var showFilterSheet by remember { mutableStateOf(false) }

    // Smooth camera recenter for Sol home + star "center view". Mirrors the
    // pan curve of the orbital transition (280 ms, FastOutSlowInEasing) but
    // doesn't touch distance. Tapping again cancels any in-flight animation
    // so the target always flies to the latest requested point.
    val panScope = rememberCoroutineScope()
    var panJob by remember { mutableStateOf<Job?>(null) }
    fun animatePanTo(x: Float, y: Float, z: Float) {
        panJob?.cancel()
        val start = cameraController.targetPosition
        val sx = start[0]
        val sy = start[1]
        val sz = start[2]
        val dx = x - sx
        val dy = y - sy
        val dz = z - sz
        if (dx * dx + dy * dy + dz * dz < 1e-6f) {
            cameraController.lookAt(x, y, z)
            return
        }
        panJob = panScope.launch {
            isCameraAnimating = true
            try {
                val startNanos = withFrameNanos { it }
                val durationMs = 420f
                var progress = 0f
                while (progress < 1f) {
                    val frameNanos = withFrameNanos { it }
                    val elapsedMs = (frameNanos - startNanos) / 1_000_000f
                    val linear = (elapsedMs / durationMs).coerceIn(0f, 1f)
                    progress = FastOutSlowInEasing.transform(linear)
                    cameraController.lookAt(
                        sx + dx * progress,
                        sy + dy * progress,
                        sz + dz * progress,
                    )
                }
            } finally {
                // Re-evaluate flags against the final camera pose once the
                // animation is done (or cancelled) so the UI reflects the
                // settled state rather than the pre-animation snapshot.
                isCameraAnimating = false
            }
        }
    }

    // Star Map ↔ Orbital transition. Forward: fly the map camera toward the
    // star (visual only — saved state restored on return), flip mode, then
    // the orbital screen runs its own zoom-in animation. Backward: orbital
    // zooms back out, mode flips to map, then the map camera animates from
    // the zoomed-in-on-star state back to the user's original camera pose.
    var pendingHostname by remember { mutableStateOf<String?>(null) }
    var pendingTarget by remember { mutableStateOf<FloatArray?>(null) }
    // Snapshot of the user's camera pose at the moment they tapped "View
    // system". Restored verbatim when the user returns, so the forward
    // animation's re-centering is purely visual.
    var savedTarget by remember { mutableStateOf<FloatArray?>(null) }
    var savedDistance by remember { mutableStateOf<Float?>(null) }
    // Set true when the user presses back inside orbital. OrbitalScreen runs
    // its zoom-out animation and calls onExitComplete, which flips mode to
    // map and kicks off the return animation below.
    var isExitingOrbital by remember { mutableStateOf(false) }
    // Set true immediately after mode flips back to map. Triggers the
    // reverse camera pan/zoom back to the saved pose.
    var pendingMapReturn by remember { mutableStateOf(false) }

    LaunchedEffect(pendingHostname) {
        val host = pendingHostname ?: return@LaunchedEffect
        val target = pendingTarget ?: return@LaunchedEffect

        // Start loading orbital data in the background while we animate —
        // by the time phase 1 completes (~280 ms) the DB query is usually done,
        // minimizing the blank gap before OrbitalScreen renders.
        viewModel.prepareOrbitalLoad(host)

        val startTargetPos = cameraController.targetPosition
        val startDist = cameraController.currentDistance
        // Remember the user's original camera pose so we can restore it
        // after they exit orbital. Re-centering here is visual only.
        savedTarget = floatArrayOf(startTargetPos[0], startTargetPos[1], startTargetPos[2])
        savedDistance = startDist

        isCameraAnimating = true
        try {
            // Bump the floor so the zoom-in has room to animate even when the
            // user is already maxed in and centered on the target star.
            // Restored to MAP_NORMAL_MIN_DIST after the reverse animation.
            cameraController.minDistance = MAP_TRANSITION_MIN_DIST
            val endDist = MAP_TRANSITION_MIN_DIST
            val startNanos = withFrameNanos { it }
            val durationMs = 280f
            var progress = 0f
            while (progress < 1f) {
                val frameNanos = withFrameNanos { it }
                val elapsedMs = (frameNanos - startNanos) / 1_000_000f
                val linear = (elapsedMs / durationMs).coerceIn(0f, 1f)
                progress = FastOutSlowInEasing.transform(linear)
                val lookX = startTargetPos[0] + (target[0] - startTargetPos[0]) * progress
                val lookY = startTargetPos[1] + (target[1] - startTargetPos[1]) * progress
                val lookZ = startTargetPos[2] + (target[2] - startTargetPos[2]) * progress
                cameraController.lookAt(lookX, lookY, lookZ)
                cameraController.setDistance(startDist + (endDist - startDist) * progress)
            }
        } finally {
            isCameraAnimating = false
        }

        viewModel.commitOrbital()
        pendingHostname = null
        pendingTarget = null
    }

    // Return animation: pan+zoom the map camera from the zoomed-in-on-star
    // state back to the user's saved original pose. Runs after the orbital
    // exit animation completes and mode has flipped back to MAP.
    LaunchedEffect(pendingMapReturn) {
        if (!pendingMapReturn) return@LaunchedEffect
        val target = savedTarget
        val dist = savedDistance
        if (target == null || dist == null) {
            pendingMapReturn = false
            return@LaunchedEffect
        }
        val startTargetPos = cameraController.targetPosition
        val startDist = cameraController.currentDistance
        isCameraAnimating = true
        try {
            val startNanos = withFrameNanos { it }
            val durationMs = 280f
            var progress = 0f
            while (progress < 1f) {
                val frameNanos = withFrameNanos { it }
                val elapsedMs = (frameNanos - startNanos) / 1_000_000f
                val linear = (elapsedMs / durationMs).coerceIn(0f, 1f)
                progress = FastOutSlowInEasing.transform(linear)
                val lookX = startTargetPos[0] + (target[0] - startTargetPos[0]) * progress
                val lookY = startTargetPos[1] + (target[1] - startTargetPos[1]) * progress
                val lookZ = startTargetPos[2] + (target[2] - startTargetPos[2]) * progress
                cameraController.lookAt(lookX, lookY, lookZ)
                cameraController.setDistance(startDist + (dist - startDist) * progress)
            }
        } finally {
            isCameraAnimating = false
        }
        // Restore the normal minDistance floor — the transition bump is done.
        cameraController.minDistance = MAP_NORMAL_MIN_DIST
        savedTarget = null
        savedDistance = null
        pendingMapReturn = false
    }

    // Back press: orbital mode triggers the exit animation; search open
    // closes it. The filter sheet owns its own BackHandler (so it can play
    // an exit animation). The actual orbital mode flip happens in
    // onExitComplete after the orbital zoom-out finishes, which then kicks
    // off the map's reverse pan so the user sees the zoom-out on the map
    // after the orbital one finishes.
    BackHandler(
        enabled = selectedNav == NavDestination.STAR_MAP &&
            (state.mode == StarMapMode.ORBITAL || state.isSearchOpen),
    ) {
        when {
            state.mode == StarMapMode.ORBITAL -> {
                if (!isExitingOrbital) {
                    isExitingOrbital = true
                }
            }
            else -> viewModel.onCloseSearch()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    containerSize = size
                },
        ) {
            // Layer 1: GL surface.
            // Visibility is toggled by selectedNav and orbital mode so the
            // native SurfaceView doesn't punch through other tabs or show
            // under the orbital overlay — Compose alpha/zIndex can't hide a
            // window-layer Surface. Kept composed (factory persisted) across
            // orbital transitions so returning to MAP is instant.
            AndroidView(
                factory = { ctx ->
                    val renderer = StarMapRenderer(appContext, cameraController, viewModel.bridge)
                    ExoGLSurfaceView(ctx, renderer, cameraController).apply {
                        onDragStart = {
                            viewModel.onCollapseSearch()
                        }
                        onTap = { x, y ->
                            if (viewModel.isSearchOpen()) {
                                // Search is open — just collapse, don't select a star
                                viewModel.onCollapseSearch()
                            } else if (viewModel.hasSelectedStar()) {
                                // Tooltip is open — always dismiss, never open another
                                viewModel.onDismissTooltip()
                            } else {
                                viewModel.onStarTapped(
                                    screenX = x,
                                    screenY = y,
                                    viewportWidth = width,
                                    viewportHeight = height,
                                    cameraController = cameraController,
                                )
                            }
                        }
                    }
                },
                update = { glView ->
                    glView.visibility = if (isTabOnScreen && state.mode == StarMapMode.MAP) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            // Search morph progress: 0 = collapsed to just the search button,
            // 1 = fully expanded into the search bar spanning start..end xxxl.
            // Drives: pill width, title/filter-button fade, textfield+X alpha,
            // results dropdown visibility. Single source of truth so all four
            // elements stay in lockstep across the animation.
            val searchProgress = remember { Animatable(0f) }
            val searchFocusRequester = remember { FocusRequester() }
            val searchFocusManager = LocalFocusManager.current
            LaunchedEffect(state.isSearchOpen) {
                if (state.isSearchOpen) {
                    launch {
                        searchProgress.animateTo(
                            1f, tween(280, easing = FastOutSlowInEasing),
                        )
                    }
                    // Wait two frames so the text field is both composed AND
                    // laid out (focusRequester only attaches during layout).
                    // try/catch is belt-and-braces — on slow first frames the
                    // modifier may still not be attached; swallowing the
                    // IllegalStateException is preferable to crashing the tab.
                    withFrameNanos { }
                    withFrameNanos { }
                    runCatching { searchFocusRequester.requestFocus() }
                } else {
                    searchFocusManager.clearFocus()
                    searchProgress.animateTo(
                        0f, tween(240, easing = FastOutSlowInEasing),
                    )
                }
            }
            val searchFade = (1f - searchProgress.value).coerceIn(0f, 1f)

            // Layer 2: "STAR MAP" header (top-left) — fades out as search opens.
            BasicText(
                text = "STAR MAP",
                style = type.labelLarge.copy(color = colors.textTertiary),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = spacing.xxxl, top = 18.dp)
                    .graphicsLayer { alpha = searchFade },
            )

            // Layer 3: Tooltip overlay (above header, below search). Uses
            // the latched displayedStar so the exit animation can play
            // after selectedStar goes null.
            val shownStar = displayedStar
            if (shownStar != null && hasProjected) {
                StarTooltip(
                    star = shownStar,
                    screenPos = screenPos,
                    isOnScreen = isOnScreen,
                    containerSize = containerSize,
                    bottomInsetPx = navBarHeightPx.toInt(),
                    distanceUnit = state.distanceUnit,
                    useEstimates = state.useEstimates,
                    useProperNames = state.useProperNames,
                    onViewSystem = { hostname ->
                        // Snapshot the star's world position so the transition
                        // animation can fly toward it even after state changes.
                        val wp = shownStar.worldPos
                        pendingTarget = floatArrayOf(wp[0], wp[1], wp[2])
                        pendingHostname = hostname
                    },
                    onCenterView = {
                        animatePanTo(
                            shownStar.worldPos[0],
                            shownStar.worldPos[1],
                            shownStar.worldPos[2],
                        )
                    },
                    isCentered = isCameraOnStar,
                    visible = selectedStar != null,
                    onDismissComplete = {
                        if (state.selectedStar == null) displayedStar = null
                    },
                )
            }

            // Layer 4: Morphing search pill + home/filter buttons (top-right).
            // Previously this was an `if (isSearchOpen) overlay else buttons`
            // swap. Now the pill is always composed; its width morphs from
            // `btnSize` (just a search button) to the full bar width as
            // `searchProgress` animates 0→1. Home + filter sit in a sibling
            // row that fades out alongside the expansion.
            val searchListState = rememberLazyListState()
            val filterActive = state.filterState.activeCount > 0
            val isAccessible = ExoTheme.isAccessible
            val btnSize = if (isAccessible) 44.dp else 36.dp
            val iconSize = if (isAccessible) 20.dp else 16.dp
            val shape = RoundedCornerShape(4.dp)
            val progress = searchProgress.value

            // Max bar width = outer container minus the matching xxxl padding
            // on both sides (same as the old expanded overlay).
            val density = LocalDensity.current
            val maxBarWidth = with(density) {
                (containerSize.width.toDp() - spacing.xxxl * 2).coerceAtLeast(btnSize)
            }
            val pillWidth = lerp(btnSize, maxBarWidth, progress)
            // Bg alpha lerps from the collapsed button's 85% to the expanded
            // bar's fully-opaque surfaceCard, matching the original visual.
            val pillBgAlpha = 0.85f + 0.15f * progress
            // Pill end-padding morphs. Collapsed: sits in the middle slot
            // (Home, <Search>, Filter) with Filter to its right. Expanded:
            // right edge flush with the bar's end=xxxl. This preserves the
            // original left-to-right order: Home, Search, Filter.
            val pillEndPad = lerp(spacing.xxxl + btnSize + 8.dp, spacing.xxxl, progress)

            // Home + (gap for search pill) + Filter — in their original
            // left-to-right order. The pill overlays the middle gap while
            // collapsed; as it expands leftward/rightward it overruns this
            // row, which has already faded to 0 by then.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = spacing.xxxl, top = 14.dp)
                    .graphicsLayer { alpha = searchFade },
            ) {
                // Home button — pure alpha fade. Always laid out (occupies
                // its slot on the left whether visible or not) so the Row's
                // other children don't shift as it appears/disappears.
                // Click handling is gated on the same condition so the
                // invisible button can't be tapped when at Sol.
                val homeAlpha by animateFloatAsState(
                    targetValue = if (isCameraOnSol) 0f else 1f,
                    animationSpec = tween(220, easing = FastOutSlowInEasing),
                    label = "homeAlpha",
                )
                Box(
                    modifier = Modifier
                        .size(btnSize)
                        .graphicsLayer { alpha = homeAlpha }
                        .clip(shape)
                        .background(colors.surfaceCard.copy(alpha = 0.85f))
                        .border(1.dp, colors.surfaceBorder, shape)
                        .touchRipple(
                            color = Color.White,
                            startAlpha = 0.22f,
                            enabled = !state.isSearchOpen && !isCameraOnSol,
                            onClick = {
                                animatePanTo(0f, 0f, 0f)
                                viewModel.onDismissTooltip()
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    HomeIconSmall()
                }

                // Reserved slot where the morphing search pill sits while
                // collapsed — invisible spacer so Filter stays at end=xxxl.
                Spacer(Modifier.size(btnSize))

                Box(
                    modifier = Modifier
                        .size(btnSize)
                        .clip(shape)
                        .background(colors.surfaceCard.copy(alpha = 0.85f))
                        .border(
                            1.dp,
                            if (filterActive) colors.accentGold else colors.surfaceBorder,
                            shape,
                        )
                        .touchRipple(
                            color = Color.White,
                            startAlpha = 0.18f,
                            enabled = !state.isSearchOpen,
                            onClick = { showFilterSheet = true },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    FilterIconSmall(isActive = filterActive)
                }
            }

            // Active filter chips below the row — fade with the same factor.
            // Always render so the FlowRow's own per-pill exit animations
            // play out when activeCount drops to 0; an outer `if (filterActive)`
            // would unmount the whole composable mid-transition and the
            // dismissed pill would just snap away.
            ActiveFilterChips(
                filterState = state.filterState,
                useTerra = state.useTerra,
                useNeptune = state.useNeptune,
                useJupiter = state.useJupiter,
                onRemove = { group, value ->
                    viewModel.onFilterChange(state.filterState.remove(group, value))
                },
                onClear = {
                    viewModel.onFilterChange(StarMapFilterState())
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = spacing.xxxl, top = 14.dp + btnSize + 6.dp)
                    .graphicsLayer { alpha = searchFade },
            )

            // The morphing search pill + its results dropdown live in a
            // Column so the dropdown tucks directly under the animating bar.
            // End padding animates so the pill's right edge moves from the
            // middle slot (progress=0) to end=xxxl (progress=1). Left edge
            // moves further via the width morph. The dropdown right-aligns
            // with the pill since the Column uses horizontalAlignment=End.
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = pillEndPad, top = 14.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Box(
                    modifier = Modifier
                        .width(pillWidth)
                        .height(btnSize)
                        .clip(shape)
                        .background(colors.surfaceCard.copy(alpha = pillBgAlpha))
                        .border(1.dp, colors.surfaceBorder, shape)
                        .touchRipple(
                            color = Color.White,
                            startAlpha = 0.22f,
                            enabled = !state.isSearchOpen,
                            onClick = viewModel::onToggleSearch,
                        ),
                ) {
                    // Search icon — always at 10dp from the pill's left edge.
                    // With btnSize=36dp and a 16dp icon, 10dp on the left
                    // lands the icon's right edge 10dp from the pill's right
                    // edge too, so it reads as centered in the collapsed
                    // button. As the pill widens leftward, the icon slides
                    // leftward with the pill's left edge — the "icon flying
                    // sideways" effect the user asked for.
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(start = 10.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        SearchIconSmall()
                    }

                    // Text field + placeholder — fade in during the second
                    // half of the expansion. Gated on `isSearchOpen` so the
                    // field is in the tree *immediately* on open (otherwise
                    // focusRequester.requestFocus() fires before the node
                    // attaches and crashes). Also kept rendered while
                    // progress > 0.01 during the close animation so it fades
                    // out visibly rather than disappearing on the flip.
                    val tfAlpha = ((progress - 0.45f) / 0.55f).coerceIn(0f, 1f)
                    if (state.isSearchOpen || progress > 0.01f) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(
                                    start = 10.dp + iconSize + 8.dp,
                                    end = 10.dp + 20.dp + 4.dp,
                                )
                                .graphicsLayer { alpha = tfAlpha },
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (state.searchQuery.text.isEmpty()) {
                                BasicText(
                                    text = "Search stars...",
                                    style = type.bodyMedium.copy(color = colors.textMuted),
                                )
                            }
                            BasicTextField(
                                value = state.searchQuery,
                                onValueChange = viewModel::onSearchQueryChanged,
                                textStyle = type.bodyMedium.copy(color = colors.textPrimary),
                                singleLine = true,
                                cursorBrush = SolidColor(colors.accentGold),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(searchFocusRequester),
                            )
                        }
                    }

                    // Close X — fades in near the end of expansion.
                    val closeAlpha = ((progress - 0.7f) / 0.3f).coerceIn(0f, 1f)
                    if (closeAlpha > 0.01f) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 10.dp)
                                .size(20.dp)
                                .graphicsLayer { alpha = closeAlpha }
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                    onClick = viewModel::onCloseSearch,
                                )
                                .drawBehind {
                                    val sw = 1.2.dp.toPx()
                                    val pad = size.width * 0.25f
                                    drawLine(
                                        colors.textMuted,
                                        Offset(pad, pad),
                                        Offset(size.width - pad, size.height - pad),
                                        sw,
                                        cap = StrokeCap.Round,
                                    )
                                    drawLine(
                                        colors.textMuted,
                                        Offset(size.width - pad, pad),
                                        Offset(pad, size.height - pad),
                                        sw,
                                        cap = StrokeCap.Round,
                                    )
                                },
                        )
                    }
                }

                // Results dropdown — appears once the bar is mostly expanded
                // and disappears while it contracts past the same threshold.
                if (progress > 0.5f) {
                    val results = state.searchResults
                    if (results.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        LazyColumn(
                            state = searchListState,
                            modifier = Modifier
                                .width(pillWidth)
                                .heightIn(max = 300.dp)
                                .clip(shape)
                                .background(colors.surfaceCard)
                                .border(1.dp, colors.surfaceBorder, shape),
                        ) {
                            items(results, key = { it.hostname }) { result ->
                                // Placement-only animateItem so rows
                                // smoothly slide as the user types and the
                                // result set changes, matching the catalog
                                // and system-search behaviour.
                                StarSearchResultRow(
                                    result = result,
                                    distanceUnit = state.distanceUnit,
                                    useProperNames = state.useProperNames,
                                    onClick = { viewModel.onSearchResultSelected(result.hostname) },
                                    modifier = Modifier.animateItem(
                                        placementSpec = tween(260, easing = FastOutSlowInEasing),
                                    ),
                                )
                            }
                        }
                    } else if (state.searchQuery.text.length >= 2) {
                        Spacer(Modifier.height(2.dp))
                        Box(
                            modifier = Modifier
                                .width(pillWidth)
                                .clip(shape)
                                .background(colors.surfaceCard)
                                .border(1.dp, colors.surfaceBorder, shape)
                                .padding(horizontal = 10.dp, vertical = 12.dp),
                        ) {
                            BasicText(
                                text = "No stars found",
                                style = type.bodyMedium.copy(color = colors.textMuted),
                            )
                        }
                    }
                }
            }

        }

        // Filter sheet overlay — covers entire screen including bottom nav
        if (showFilterSheet) {
            StarMapFilterSheet(
                filterState = state.filterState,
                onFilterChange = viewModel::onFilterChange,
                onDismiss = { showFilterSheet = false },
                useTerra = state.useTerra,
                useNeptune = state.useNeptune,
                useJupiter = state.useJupiter,
            )
        }

        // Orbital overlay — sits on top of the map layer while active. The
        // map layer below stays composed so its renderer/SurfaceView are
        // ready instantly when orbital exits, eliminating the re-init delay
        // that used to sit between the orbital zoom-out and the map reveal.
        if (state.mode == StarMapMode.ORBITAL) {
            val orbitalState = state.orbitalState
            if (orbitalState != null) {
                OrbitalScreen(
                    orbitalState = orbitalState,
                    starHostname = state.orbitalHostname,
                    starDisplayName = state.orbitalStarDisplayName,
                    settings = com.tadmor.domain.model.UserSettings(
                        distanceUnit = state.distanceUnit,
                        useProperNames = state.useProperNames,
                        useEstimates = state.useEstimates,
                        useTerra = state.useTerra,
                        useNeptune = state.useNeptune,
                        useJupiter = state.useJupiter,
                        invertCameraControls = state.invertCameraControls,
                    ),
                    starfieldData = starfield,
                    showStarfield = state.showStarfield,
                    onToggleStarfield = viewModel::onToggleStarfield,
                    showHabitableZone = state.showHabitableZone,
                    onToggleHabitableZone = viewModel::onToggleHabitableZone,
                    isTabActive = isTabOnScreen,
                    isExiting = isExitingOrbital,
                    onExitComplete = {
                        // Orbital zoom-out finished. Flip mode to MAP and
                        // start the reverse map pan so next time the user
                        // returns the camera is back at its saved pose. If
                        // this orbital was entered via System DETAIL's
                        // "View orbits" link, additionally fire the cross-
                        // tab return — the SYSTEM tab slide overlays the
                        // brief star-map flash, and the camera reverse pan
                        // runs in the background while the tab is hidden.
                        viewModel.onBackFromOrbital()
                        pendingMapReturn = true
                        isExitingOrbital = false
                        if (orbitalReturnsToSystem) {
                            onOrbitalReturnToSystem()
                        }
                    },
                    onBack = {
                        if (!isExitingOrbital) {
                            isExitingOrbital = true
                        }
                    },
                    onPlanetSelected = { planetName ->
                        onNavigateToSystemPlanet(state.orbitalHostname, planetName)
                    },
                    onViewStar = {
                        onNavigateToSystemPlanet(state.orbitalHostname, null)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun StarSearchResultRow(
    result: StarSearchResult,
    distanceUnit: DistanceUnit,
    useProperNames: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            val displayName = if (useProperNames && result.properName != null) {
                result.properName
            } else {
                result.hostname
            }
            BasicText(
                text = displayName,
                style = type.bodyMedium.copy(color = colors.textPrimary),
            )
            // Subtitle row: spectral type (colored) + distance, or catalog name
            if (useProperNames && result.properName != null) {
                BasicText(
                    text = result.hostname,
                    style = type.labelLarge.copy(color = colors.textMuted),
                )
            } else {
                val hasSpectral = result.spectralType != null
                val distText = if (result.distancePc != null) {
                    when (distanceUnit) {
                        DistanceUnit.PARSECS -> "%.1f pc".format(result.distancePc)
                        DistanceUnit.LIGHT_YEARS -> "%.1f ly".format(result.distancePc * 3.26156)
                    }
                } else null
                if (hasSpectral || distText != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (hasSpectral) {
                            val spectralColor = TeffColor.fromSpectralClass(result.spectralClass ?: result.spectralType!!)
                            BasicText(
                                text = result.spectralType,
                                style = type.labelLarge.copy(color = spectralColor),
                            )
                            if (distText != null) {
                                BasicText(
                                    text = " · ",
                                    style = type.labelLarge.copy(color = colors.textMuted),
                                )
                            }
                        }
                        if (distText != null) {
                            BasicText(
                                text = distText,
                                style = type.labelLarge.copy(color = colors.textMuted),
                            )
                        }
                    }
                }
            }
        }

        if (result.planetCount != null && result.planetCount > 0) {
            BasicText(
                text = "${result.planetCount} planet${if (result.planetCount > 1) "s" else ""}",
                style = type.labelLarge.copy(color = colors.textTertiary),
            )
        }
    }
}

@Composable
private fun SearchIconSmall() {
    val color = ExoTheme.colors.textTertiary
    val iconSize = if (ExoTheme.isAccessible) 20.dp else 16.dp
    Box(
        modifier = Modifier
            .size(iconSize)
            .drawBehind {
                val r = size.minDimension * 0.35f
                val cx = size.width * 0.4f
                val cy = size.height * 0.4f
                drawCircle(
                    color = color,
                    radius = r,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round),
                )
                val handleStart = Offset(cx + r * 0.707f, cy + r * 0.707f)
                val handleEnd = Offset(size.width * 0.9f, size.height * 0.9f)
                drawLine(
                    color = color,
                    start = handleStart,
                    end = handleEnd,
                    strokeWidth = 1.5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            },
    )
}

@Composable
private fun HomeIconSmall() {
    val color = ExoTheme.colors.textTertiary
    val iconSize = if (ExoTheme.isAccessible) 26.dp else 22.dp
    Box(
        modifier = Modifier
            .size(iconSize)
            .drawBehind {
                val sw = 1.2.dp.toPx()
                val w = size.width
                val h = size.height
                // Single continuous outline: walls, roof overhang, peak, and
                // back down — no internal seam where the roof meets the square.
                val path = Path().apply {
                    moveTo(w * 0.25f, h * 0.84f) // bottom-left of wall
                    lineTo(w * 0.25f, h * 0.42f) // up to wall top
                    lineTo(w * 0.15f, h * 0.42f) // out to left eave overhang
                    lineTo(w * 0.50f, h * 0.12f) // up to roof peak
                    lineTo(w * 0.85f, h * 0.42f) // down to right eave overhang
                    lineTo(w * 0.75f, h * 0.42f) // in to wall top
                    lineTo(w * 0.75f, h * 0.84f) // down to bottom-right
                    close()
                }
                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(
                        width = sw,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            },
    )
}

@Composable
private fun FilterIconSmall(isActive: Boolean) {
    val color = if (isActive) ExoTheme.colors.accentGold else ExoTheme.colors.textTertiary
    val iconSize = if (ExoTheme.isAccessible) 20.dp else 16.dp
    Box(
        modifier = Modifier
            .size(iconSize)
            .drawBehind {
                val sw = 1.2.dp.toPx()
                val w = size.width
                val h = size.height
                // Three horizontal lines, progressively shorter (funnel shape)
                drawLine(color, Offset(w * 0.1f, h * 0.25f), Offset(w * 0.9f, h * 0.25f), sw, cap = StrokeCap.Round)
                drawLine(color, Offset(w * 0.25f, h * 0.5f), Offset(w * 0.75f, h * 0.5f), sw, cap = StrokeCap.Round)
                drawLine(color, Offset(w * 0.38f, h * 0.75f), Offset(w * 0.62f, h * 0.75f), sw, cap = StrokeCap.Round)
            },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActiveFilterChips(
    filterState: StarMapFilterState,
    useTerra: Boolean,
    useNeptune: Boolean,
    useJupiter: Boolean,
    onRemove: (group: String, value: String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type

    // Canonical → display name mapping for planet classes
    val classDisplay = mapOf(
        "Terrestrial" to if (useTerra) "Terrestrial" else "Earth",
        "Neptune" to if (useNeptune) "Neptune" else "Ice Giant",
        "Jupiter" to if (useJupiter) "Jupiter" else "Gas Giant",
    )

    // Display-list tracking. A pill stays in `displayed` (with isVisible=false)
    // for one animation cycle after it's removed from `filterState.activeLabels`
    // so its `AnimatedVisibility` exit transition can play before the row
    // collapses. The new-pill case is symmetric: items appearing in
    // activeLabels get added with isVisible=true, fade in via expand
    // horizontal + alpha while neighbouring pills slide left from the
    // right-aligned FlowRow filling in.
    val displayed = remember { mutableStateListOf<Pair<String, String>>() }
    val visibility = remember { mutableStateMapOf<Pair<String, String>, Boolean>() }
    val animDurMs = 240
    val animSpec = tween<Float>(animDurMs, easing = FastOutSlowInEasing)
    val sizeSpec = tween<IntSize>(animDurMs, easing = FastOutSlowInEasing)

    LaunchedEffect(filterState.activeLabels) {
        val activeSet = filterState.activeLabels.toSet()
        // Add any new pills (and reset visibility=true if it was mid-fade-out)
        filterState.activeLabels.forEach { key ->
            if (key !in displayed) displayed.add(key)
            visibility[key] = true
        }
        // Mark removed pills for fade-out
        displayed.toList().forEach { key ->
            if (key !in activeSet) visibility[key] = false
        }
    }

    // Track Clear-all separately on the same fade timing.
    val clearAllVisible = filterState.activeCount > 1

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier,
    ) {
        // Paddings + dismiss size mirror ActiveFilterChip in the catalog so
        // the two surfaces read at the same physical scale across both UI modes.
        val isAccessible = ExoTheme.isAccessible
        val vPad = if (isAccessible) 10.dp else 6.dp
        val startPad = if (isAccessible) 14.dp else 10.dp
        val endPad = if (isAccessible) 8.dp else 6.dp
        val dismissSize = if (isAccessible) 24.dp else 16.dp
        val clearAllHPad = if (isAccessible) 14.dp else 10.dp

        displayed.toList().forEach { keyPair ->
            val (group, value) = keyPair
            val isVisible = visibility[keyPair] ?: false
            val displayName = if (group == "planetClass") classDisplay[value] ?: value else value
            val chipColor = when (group) {
                "spectral" -> TeffColor.fromSpectralClass(value)
                "planetClass" -> when (value) {
                    "Terrestrial" -> ExoColors.compositionTerra.text
                    "Neptune" -> ExoColors.compositionNeptune.text
                    "Jupiter" -> ExoColors.compositionJupiter.text
                    else -> colors.textSecondary
                }
                else -> colors.textSecondary
            }
            val shape = RoundedCornerShape(20.dp)
            // Drop pill from the displayed list once its exit animation has
            // played out — keyed on the pair so cancelling a removal (rapid
            // re-add) cleanly resets the timer.
            LaunchedEffect(isVisible, keyPair) {
                if (!isVisible) {
                    delay((animDurMs + 40).toLong())
                    if (visibility[keyPair] == false) {
                        displayed.remove(keyPair)
                        visibility.remove(keyPair)
                    }
                }
            }
            key(keyPair) {
                // MutableTransitionState with initialState = false ensures the
                // enter animation plays on first composition (a freshly-added
                // pill). Plain `visible = isVisible` would skip the transition
                // since AnimatedVisibility only animates on actual state
                // changes, not on initial mount.
                val visState = remember(keyPair) { MutableTransitionState(false) }
                visState.targetState = isVisible
                AnimatedVisibility(
                    visibleState = visState,
                    enter = fadeIn(animationSpec = animSpec) +
                        expandHorizontally(animationSpec = sizeSpec, expandFrom = Alignment.End),
                    exit = fadeOut(animationSpec = animSpec) +
                        shrinkHorizontally(animationSpec = sizeSpec, shrinkTowards = Alignment.End),
                ) {
                    Row(
                        modifier = Modifier
                            .clip(shape)
                            .background(colors.surfaceCard.copy(alpha = 0.9f))
                            .border(1.dp, colors.surfaceBorder, shape)
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = { onRemove(group, value) },
                            )
                            .padding(start = startPad, end = endPad, top = vPad, bottom = vPad),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicText(
                            text = displayName,
                            style = type.labelLarge.copy(color = chipColor),
                        )
                        Spacer(Modifier.width(4.dp))
                        Box(
                            modifier = Modifier
                                .size(dismissSize)
                                .drawBehind {
                                    val sw = 1.2.dp.toPx()
                                    val pad = size.width * 0.3f
                                    drawLine(
                                        colors.textMuted,
                                        Offset(pad, pad),
                                        Offset(size.width - pad, size.height - pad),
                                        sw,
                                        cap = StrokeCap.Round,
                                    )
                                    drawLine(
                                        colors.textMuted,
                                        Offset(size.width - pad, pad),
                                        Offset(pad, size.height - pad),
                                        sw,
                                        cap = StrokeCap.Round,
                                    )
                                },
                        )
                    }
                }
            }
        }

        // Clear all chip — same enter/exit treatment, same first-mount
        // transition fix.
        val clearVisState = remember { MutableTransitionState(false) }
        clearVisState.targetState = clearAllVisible
        AnimatedVisibility(
            visibleState = clearVisState,
            enter = fadeIn(animationSpec = animSpec) +
                expandHorizontally(animationSpec = sizeSpec, expandFrom = Alignment.End),
            exit = fadeOut(animationSpec = animSpec) +
                shrinkHorizontally(animationSpec = sizeSpec, shrinkTowards = Alignment.End),
        ) {
            val shape = RoundedCornerShape(20.dp)
            Box(
                modifier = Modifier
                    .clip(shape)
                    .background(colors.surfaceCard.copy(alpha = 0.9f))
                    .border(1.dp, colors.accentGoldBorder, shape)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = onClear,
                    )
                    .padding(horizontal = clearAllHPad, vertical = vPad),
            ) {
                BasicText(
                    text = "Clear all",
                    style = type.labelLarge.copy(color = colors.accentGold),
                )
            }
        }
    }
}
