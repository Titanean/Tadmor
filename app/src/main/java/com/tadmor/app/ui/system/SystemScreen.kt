package com.tadmor.app.ui.system

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import com.tadmor.app.ui.components.NavDestination
import com.tadmor.app.ui.theme.ExoTheme
import com.tadmor.domain.model.SystemDetail
import com.tadmor.domain.model.SystemPlanetEntry

@Composable
fun SystemScreen(
    selectedNav: NavDestination = NavDestination.SYSTEM,
    onSelectNav: (NavDestination) -> Unit = {},
    initialHostname: String? = null,
    focusPlanetName: String? = null,
    navigationKey: Int = 0,
    onNavigateToCatalog: () -> Unit = {},
    onNavigateToOrbital: (String) -> Unit = {},
    onHideBottomNavChange: (Boolean) -> Unit = {},
    isTabOnScreen: Boolean = true,
    viewModel: SystemViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val targetHostname by viewModel.targetHostname.collectAsState()
    val colors = ExoTheme.colors

    // Globe mode state — hoisted above PlanetDetailContent
    var globeMode by remember { mutableStateOf(GlobeMode.HALF) }

    // Reset globe mode when planet selection changes
    LaunchedEffect(state.selectedPlanetName) {
        globeMode = GlobeMode.HALF
    }

    // Page transition state: SEARCH (0) → DETAIL (1) → PLANET (2).
    // Going "deeper" slides the old page left and the new page enters from the right;
    // going back slides the old page right and the new page enters from the left.
    fun modeOrdinal(m: SystemMode): Int = when (m) {
        SystemMode.SEARCH -> 0
        SystemMode.DETAIL -> 1
        SystemMode.PLANET -> 2
    }

    // Same pattern as MainActivity's tabProgress: write progress synchronously
    // during composition to prevent a one-frame flash where graphicsLayer would
    // otherwise see progress = 1f before LaunchedEffect could snap it back.
    var previousMode by remember { mutableStateOf(state.mode) }
    var fromMode by remember { mutableStateOf(state.mode) }
    var pageProgress by remember { mutableFloatStateOf(1f) }
    // Cross-tab nav (Catalog/StarMap → System) arrives with a bumped navigationKey
    // and an initialHostname. loadFromCatalog fires async via LaunchedEffect, so
    // state.mode lags by one or more frames. The user-visible animation on cross-tab
    // nav is the MainActivity tab slide; we suppress the inner page animation so
    // the target page is painted at its final position inside the sliding tab.
    // ackedNavKey: which navigationKey state has caught up to. While not yet
    //   acknowledged, SEARCH is still the rendered mode — hide it so the sliding
    //   tab doesn't show stale SEARCH content.
    var ackedNavKey by remember { mutableStateOf(navigationKey) }
    val externalTargetMode = if (focusPlanetName != null) SystemMode.PLANET else SystemMode.DETAIL
    val externalNavInFlight = ackedNavKey != navigationKey && initialHostname != null

    // On cross-tab exit (hardware back from PLANET/DETAIL to origin tab),
    // resetToSearch() flips state.mode to SEARCH while the tab is sliding out.
    // The tab slide carries the motion; an inner page slide simultaneously would
    // produce compound conflicting motion and flash the (stale) SEARCH page.
    val leavingTab = selectedNav != NavDestination.SYSTEM

    if (state.mode != previousMode) {
        fromMode = previousMode
        previousMode = state.mode
        // Skip the inner page slide when the mode change was triggered by cross-tab
        // nav — the outer tab slide carries the motion, and a simultaneous page
        // slide inside would create compound motion in conflicting directions.
        pageProgress = if (externalNavInFlight && state.mode == externalTargetMode) {
            ackedNavKey = navigationKey
            1f
        } else if (leavingTab) {
            1f
        } else {
            0f
        }
    } else if (externalNavInFlight && state.mode == externalTargetMode) {
        // Target already matches (e.g. same planet re-selected from another tab) —
        // no mode change to animate, just acknowledge.
        ackedNavKey = navigationKey
    }

    LaunchedEffect(previousMode) {
        if (pageProgress < 1f) {
            val startNanos = withFrameNanos { it }
            val durationMs = 280f
            while (pageProgress < 1f) {
                val frameNanos = withFrameNanos { it }
                val elapsedMs = (frameNanos - startNanos) / 1_000_000f
                val linear = (elapsedMs / durationMs).coerceIn(0f, 1f)
                pageProgress = FastOutSlowInEasing.transform(linear)
            }
        }
    }

    // Once the page animation settles and the user is viewing the tab, the
    // "from" page is no longer relevant — the visible page IS state.mode.
    // Realign fromMode so a subsequent cross-tab exit freezes on what the user
    // was actually looking at, not a stale previous page.
    if (pageProgress >= 1f && !leavingTab && fromMode != state.mode) {
        fromMode = state.mode
    }

    // Cache the last non-null SystemDetail (+ its derived OrbitalState) so
    // SystemDetailContent, the star globe, and the system strip all survive
    // exit animations. resetToSearch() clears selectedHostname → systemDetail
    // and orbitalState both flip to null mid-slide; without caching the
    // content, globe, and strip would unmount the instant state goes null.
    //
    // The downstream values are resolved SYNCHRONOUSLY during composition —
    // prefer the current state, fall back to the cache only when the state
    // has nulled out. The earlier LaunchedEffect-only update of the cache
    // ran AFTER composition, so for one composition cycle every downstream
    // `remember(... .hostname)` (star globe params, the reveal Animatable's
    // key) saw the previous system's hostname. The previous Animatable —
    // already at 1.0 — was returned, so the stale star rendered fully
    // revealed; then the cache caught up, hostname changed, a fresh
    // `Animatable(0)` was created, and the screen abruptly flipped to black
    // to start the fade. With synchronous resolution the new hostname is
    // available on the first composition and the fade starts from black
    // cleanly without a stale-flash beforehand.
    var cachedDetail by remember { mutableStateOf<SystemDetail?>(null) }
    var cachedOrbitalState by remember { mutableStateOf<OrbitalState?>(null) }
    val resolvedDetail = state.systemDetail ?: cachedDetail
    val resolvedOrbitalState = state.orbitalState ?: cachedOrbitalState
    LaunchedEffect(resolvedDetail) {
        if (resolvedDetail != null) cachedDetail = resolvedDetail
    }
    LaunchedEffect(resolvedOrbitalState) {
        if (resolvedOrbitalState != null) cachedOrbitalState = resolvedOrbitalState
    }

    // Cache the last valid planet entry so PlanetDetailContent can animate out
    // after the ViewModel has already cleared selectedPlanetName, and so a
    // cross-system navigation (catalog → system A's planet, then star map →
    // system B's planet) doesn't render a blank page during the brief window
    // where state.systemDetail still holds A's data while selectedPlanetName
    // has already advanced to a name in B.
    //
    // The entry is resolved SYNCHRONOUSLY during composition (preferring a
    // fresh match from the current state, falling back to the cache only when
    // there's no match). The cache is updated reactively for the next
    // composition cycle. The earlier LaunchedEffect-only approach lagged a
    // recomposition behind selectedPlanetName for same-system planet swaps —
    // visible as the bookmark icon flashing the previous planet's state during
    // slide-in transitions, and the bookmark toggle silently operating on the
    // previous planet (push animation fired but the icon never updated because
    // selectedPlanetName.value pointed at the new planet while the icon's
    // state was reading from the lagging cache).
    var cachedPlanetEntry by remember { mutableStateOf<SystemPlanetEntry?>(null) }
    val resolvedPlanetEntry: SystemPlanetEntry? = run {
        val pn = state.selectedPlanetName
        val det = state.systemDetail
        val match = if (pn != null && det != null) {
            // Search confirmed planets first, then candidates and false
            // positives — same display path on the planet detail page,
            // distinguished only by the disposition badge beside the
            // class badge.
            det.planets.find { it.planet.name == pn }
                ?: det.candidates.find { it.planet.name == pn }
                ?: det.falsePositives.find { it.planet.name == pn }
        } else null
        // Cache fallback: only use the cached entry when it matches the
        // requested planet (the user is animating out / repainting the
        // same planet), or when the selection has been cleared (back-
        // navigation, viewmodel reset). Without the gate, a cross-
        // system navigation (e.g. Kepler-451 → search → TOI-1338 →
        // tap a planet) finds no match in the still-stale SystemDetail
        // and silently returns the previous planet's cached entry —
        // the user lands on the wrong planet's info page.
        match ?: cachedPlanetEntry?.takeIf {
            pn == null || it.planet.name == pn
        }
    }
    LaunchedEffect(resolvedPlanetEntry) {
        if (resolvedPlanetEntry != null) cachedPlanetEntry = resolvedPlanetEntry
    }

    // During a cross-tab exit, freeze the inner page on whatever was visible just
    // before the exit (fromMode, which the mode-change block captured as the old
    // value). Otherwise state.mode flips to SEARCH mid-slide and the PLANET/DETAIL
    // page would vanish behind the outgoing tab.
    val effectiveMode = if (leavingTab) fromMode else state.mode

    fun pageModifier(mode: SystemMode): Modifier = Modifier
        .fillMaxSize()
        .zIndex(
            when (mode) {
                effectiveMode -> 2f
                fromMode -> 1f
                else -> 0f
            },
        )
        .graphicsLayer {
            val p = pageProgress
            val direction =
                if (modeOrdinal(effectiveMode) >= modeOrdinal(fromMode)) 1f else -1f
            if (p >= 1f) {
                translationX = 0f
                alpha = if (mode == effectiveMode) 1f else 0f
            } else {
                when (mode) {
                    effectiveMode -> {
                        // New page enters from the direction of travel:
                        // deeper (higher ordinal) → from right
                        translationX = size.width * direction * (1f - p)
                        alpha = 1f
                    }
                    fromMode -> {
                        // Old page exits toward the opposite side
                        translationX = -size.width * direction * p
                        alpha = 1f
                    }
                    else -> {
                        alpha = 0f
                    }
                }
            }
        }

    // Handle navigation from catalog or star map — navigationKey ensures re-trigger for same target
    LaunchedEffect(navigationKey) {
        if (initialHostname != null) {
            viewModel.loadFromCatalog(initialHostname, focusPlanetName)
        }
    }

    // Hardware back: return to the previous page the user actually visited
    val handleHardwareBack: () -> Unit = {
        when (state.mode) {
            SystemMode.PLANET -> {
                if (globeMode == GlobeMode.FULL) {
                    globeMode = GlobeMode.HALF
                } else if (state.fromCatalog) {
                    viewModel.resetToSearch()
                    onNavigateToCatalog()
                } else {
                    viewModel.onBackFromPlanet()
                }
            }
            SystemMode.DETAIL -> {
                if (state.starGlobeMode == GlobeMode.FULL) {
                    viewModel.onStarGlobeModeChange(GlobeMode.HALF)
                } else {
                    // If the user got to DETAIL by pressing GUI back from PLANET,
                    // hardware back should return to PLANET (previous page they visited).
                    val returnedToPlanet = viewModel.onHardwareBackFromDetail()
                    if (!returnedToPlanet) {
                        val handled = viewModel.onBackFromDetail()
                        if (!handled) {
                            onNavigateToCatalog()
                        }
                    }
                }
            }
            else -> {}
        }
    }

    // Star detail "Back" button: return to origin tab
    val handleDetailBack: () -> Unit = {
        val handled = viewModel.onBackFromDetail()
        if (!handled) {
            onNavigateToCatalog()
        }
    }

    // System back button handling
    BackHandler(enabled = selectedNav == NavDestination.SYSTEM && state.mode != SystemMode.SEARCH) {
        handleHardwareBack()
    }

    // Hide bottom nav when a globe is fullscreen (planet or star detail)
    val hideNav = (state.mode == SystemMode.PLANET && globeMode == GlobeMode.FULL) ||
        (state.mode == SystemMode.DETAIL && state.starGlobeMode == GlobeMode.FULL)

    LaunchedEffect(hideNav) {
        onHideBottomNavChange(hideNav)
    }

    Box(
        // Outer background is black — visible during page-slide transitions
        // (SEARCH ↔ DETAIL ↔ PLANET) in any region not yet covered by the
        // incoming page's own background. The planet page's GL view shows
        // through the SurfaceView punch-hole during slide-in before the EGL
        // surface starts producing frames; without a black outer fill the
        // user sees the dark-blue surface colour leaking through. Each child
        // content (StarSearchContent, SystemDetailContent, PlanetDetailContent)
        // already paints its own `colors.background` where needed, so this
        // only affects transition periods, not any settled-state visuals.
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize(),
        ) {
            // StarSearchContent stays composed to preserve scroll position.
            // During cross-tab nav we hide it — SEARCH is stale before state
            // catches up to the external target mode, and the tab slide would
            // otherwise reveal SEARCH content instead of the target page.
            StarSearchContent(
                searchQuery = state.searchQuery,
                results = state.searchResults,
                settings = state.settings,
                onQueryChange = { viewModel.onSearchQueryChange(it) },
                onStarSelected = { viewModel.onStarSelected(it) },
                isActive = isTabOnScreen && state.mode == SystemMode.SEARCH,
                modifier = if (externalNavInFlight) {
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = 0f }
                } else {
                    pageModifier(SystemMode.SEARCH)
                },
            )

            // A page is "on screen" if it's the current mode OR is animating out.
            // derivedStateOf keeps these stable — only flip at animation boundaries.
            // The globe GL view's visibility follows this, so the globe stays
            // rendered through the exit slide instead of disappearing the moment
            // state.mode changes.
            val pageAnimating by remember {
                derivedStateOf { pageProgress < 1f }
            }
            val detailOnScreen = effectiveMode == SystemMode.DETAIL ||
                (pageAnimating && fromMode == SystemMode.DETAIL)
            val planetOnScreen = effectiveMode == SystemMode.PLANET ||
                (pageAnimating && fromMode == SystemMode.PLANET)

            // SystemDetailContent stays composed to preserve scroll position and zoom state.
            // Uses the synchronously-resolved detail so the content and star globe survive
            // exit animations (cache fallback in resolvedDetail) AND so cross-system
            // navigation reaches the new system's data on the first composition rather
            // than reading the stale cache for a frame.
            val renderDetail = resolvedDetail
            if (renderDetail != null) {
                // Which star the user has selected for the globe view.
                // Defaults to the primary; in multi-star (binary) systems
                // the user can tap a companion's name header to swap. Keyed
                // on the primary hostname so navigation to a new system
                // resets the selection back to that system's primary.
                var selectedStarHostname by remember(renderDetail.star.hostname) {
                    mutableStateOf(renderDetail.star.hostname)
                }
                // [displayedStarHostname] trails [selectedStarHostname] across
                // the fade transition: when the user taps a different star,
                // the LaunchedEffect below fades reveal to 0 *before* writing
                // the new hostname here, then fades reveal back to 1. That
                // sequencing means the globe never visually flips to the
                // new star at full brightness — it fades out the old star
                // first, swaps under cover of darkness, then fades the new
                // star in.
                var displayedStarHostname by remember(renderDetail.star.hostname) {
                    mutableStateOf(renderDetail.star.hostname)
                }
                val displayedStar = remember(displayedStarHostname, renderDetail) {
                    if (displayedStarHostname == renderDetail.star.hostname) {
                        renderDetail.star
                    } else {
                        renderDetail.companionStars.find { it.hostname == displayedStarHostname }
                            ?: renderDetail.star
                    }
                }
                val starGlobeParams = remember(
                    displayedStar.hostname,
                    state.settings.neutronStarRotation,
                ) {
                    buildStarGlobeParams(
                        displayedStar,
                        neutronStarRotation = state.settings.neutronStarRotation,
                    )
                }
                // Reveal animation, mirroring the planet globe's pattern. Held
                // at 0 for 350 ms after a star change so the page slide-in has
                // time to settle, then ramps 0→1 over 600 ms.
                //
                // Animation key uses the navigation **target** (`targetHostname`)
                // when non-null, falling back to the **loaded** star's hostname
                // when target goes null. The fallback is critical: pressing
                // back on the star detail page calls `resetToSearch` which
                // nulls `selectedHostname` (and therefore `targetHostname`)
                // mid-slide. With a pure target-keyed Animatable, that null
                // would create a fresh `Animatable(0)` and the LaunchedEffect's
                // `if (target == null) return` would skip the animation entirely,
                // leaving uReveal at 0 — the star rendered black through the
                // entire slide-out. Falling back to the loaded hostname keeps
                // the key stable across the null-out, so the existing 1.0
                // Animatable is reused and the star stays visible.
                //
                // For direct navigation the target updates instantly so the
                // fresh Animatable(0) is created on the first composition,
                // producing the desired black-then-fade reveal.
                val animKey = targetHostname ?: renderDetail.star.hostname
                val starRevealAnim = remember(animKey) { Animatable(0f) }
                LaunchedEffect(animKey) {
                    kotlinx.coroutines.delay(350)
                    starRevealAnim.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(600, easing = FastOutSlowInEasing),
                    )
                }
                // In-system star swap (multi-star systems): fade out → swap
                // displayed hostname → fade back in. Reuses [starRevealAnim]
                // (the same `uReveal` that drives the navigation fade) so the
                // two animations don't fight if they overlap — e.g. tapping
                // a companion mid-navigation just composes naturally with
                // the in-flight reveal. The guard skips on first composition
                // (both hostnames equal) and on system change (both reset to
                // the new primary together).
                LaunchedEffect(selectedStarHostname) {
                    if (selectedStarHostname != displayedStarHostname) {
                        starRevealAnim.animateTo(
                            targetValue = 0f,
                            animationSpec = tween(220, easing = FastOutSlowInEasing),
                        )
                        displayedStarHostname = selectedStarHostname
                        starRevealAnim.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(320, easing = FastOutSlowInEasing),
                        )
                    }
                }
                val effectiveStarGlobeParams = starGlobeParams.copy(
                    revealProgress = starRevealAnim.value,
                )
                SystemDetailContent(
                    detail = renderDetail,
                    settings = state.settings,
                    orbitalState = resolvedOrbitalState,
                    onPlanetSelected = { viewModel.onPlanetSelected(it) },
                    onBack = { handleDetailBack() },
                    onViewOrbits = { onNavigateToOrbital(renderDetail.star.hostname) },
                    globeMode = state.starGlobeMode,
                    onGlobeModeChange = { viewModel.onStarGlobeModeChange(it) },
                    globeParams = effectiveStarGlobeParams,
                    isActive = isTabOnScreen && detailOnScreen,
                    isTabOnScreen = isTabOnScreen,
                    updateCountsByKey = state.updateCountsByKey,
                    selectedStarHostname = selectedStarHostname,
                    onSelectStar = { selectedStarHostname = it },
                    modifier = pageModifier(SystemMode.DETAIL),
                )
            }

            if (planetOnScreen && renderDetail != null) {
                val entry = resolvedPlanetEntry
                if (entry != null) {
                    // Key on the star's hostname too — without it, the cached
                    // params keep holding the previous render's `star` object
                    // when `cachedDetail` catches up to a new system after the
                    // first composition (e.g. orbital tooltip → planet info
                    // page cross-tab nav). Symptom: planet globe rendered the
                    // sun with the previously-visited planet's star's colour,
                    // intensity, and radius, because remember without the star
                    // key never recomputes once the planet name is stable.
                    // Circumbinary planets render with BOTH stars on the
                    // globe at the SMA-derived static angular separation.
                    // Companion + binarySepAU resolve from the same data
                    // the orbital view uses; if either is missing (system
                    // detail or orbital state hasn't loaded yet), the
                    // builder falls back to a single-star render — the
                    // next composition once the data arrives upgrades to
                    // the two-sun pipeline.
                    val isBinary = entry.planet.cbFlag &&
                        renderDetail.companionStars.isNotEmpty()
                    val companionForGlobe = if (isBinary) {
                        renderDetail.companionStars.firstOrNull()
                    } else null
                    val binarySepAU = if (isBinary) {
                        resolvedOrbitalState?.binaryStarSeparationAU ?: 0.0
                    } else 0.0
                    val globeParams = remember(
                        entry.planet.name,
                        renderDetail.star.hostname,
                        companionForGlobe?.hostname,
                        binarySepAU,
                    ) {
                        buildPlanetGlobeParams(
                            entry,
                            renderDetail.star,
                            companionStar = companionForGlobe,
                            binaryStarSeparationAU = binarySepAU,
                        )
                    }
                    PlanetDetailContent(
                        entry = entry,
                        star = renderDetail.star,
                        settings = state.settings,
                        onBack = { viewModel.onGuiBackFromPlanet() },
                        companionStars = renderDetail.companionStars,
                        globeMode = globeMode,
                        onGlobeModeChange = { globeMode = it },
                        globeParams = globeParams,
                        isActive = isTabOnScreen && planetOnScreen,
                        isBookmarked = entry.planet.name in state.bookmarkedKeys,
                        onBookmarkToggle = { viewModel.onBookmarkSelectedPlanetToggle() },
                        diff = state.selectedPlanetDiff,
                        modifier = pageModifier(SystemMode.PLANET),
                    )
                }
            }
        }

    }
}
