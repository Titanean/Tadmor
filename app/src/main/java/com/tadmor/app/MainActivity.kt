package com.tadmor.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import com.tadmor.app.ui.catalog.CatalogScreen
import com.tadmor.app.ui.components.ExoBottomNav
import com.tadmor.app.ui.components.NavDestination
import com.tadmor.app.ui.settings.SettingsScreen
import com.tadmor.app.ui.settings.SettingsViewModel
import com.tadmor.app.ui.starmap.StarMapScreen
import com.tadmor.app.ui.system.SystemScreen
import com.tadmor.app.ui.theme.ExoSpacing
import com.tadmor.app.ui.theme.ExoTheme
import com.tadmor.app.ui.theme.ExoType
import com.tadmor.app.sync.SyncScheduler
import com.tadmor.app.ui.theme.LocalBottomBarHeight
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var syncScheduler: SyncScheduler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // App-launch catch-up sync: WorkManager's 24h periodic schedule can
        // be dropped by OS battery / Doze policies (especially on Xiaomi /
        // OnePlus / Huawei), and first-launch users have no schedule yet.
        // savedInstanceState == null restricts this to a genuine fresh
        // activity creation, so rotation doesn't re-trigger it. The 22h
        // threshold inside `topUpIfStale` does the real de-duplication.
        if (savedInstanceState == null) {
            lifecycleScope.launch { syncScheduler.topUpIfStale() }
        }

        setContent {
            val settingsVm: SettingsViewModel = hiltViewModel()
            val settings by settingsVm.settings.collectAsState()
            val accessible = settings.accessibleMode

            ExoTheme(
                type = if (accessible) ExoType.accessible() else ExoType(),
                spacing = if (accessible) ExoSpacing.accessible() else ExoSpacing(),
                isAccessible = accessible,
            ) {
                var selectedNav by rememberSaveable { mutableStateOf(NavDestination.CATALOG) }
                var pendingHostname by remember { mutableStateOf<String?>(null) }
                var pendingPlanet by remember { mutableStateOf<String?>(null) }
                var navigationKey by remember { mutableStateOf(0) }
                var systemOriginTab by remember { mutableStateOf(NavDestination.CATALOG) }
                // Cross-tab nav from System DETAIL → Star Map ORBITAL.
                // Bumped each time so re-tapping "View orbits" on the same
                // host re-enters orbital even if the user is already viewing
                // it on the star map tab.
                var pendingOrbitalHostname by remember { mutableStateOf<String?>(null) }
                var pendingOrbitalKey by remember { mutableStateOf(0) }
                // Mirror of `systemOriginTab` for the System → Orbital path.
                // When true, exiting orbital (HW back or breadcrumb) routes
                // back to the SYSTEM tab after the zoom-out animation
                // instead of falling to Star Map MAP. Cleared once consumed.
                var orbitalReturnsToSystem by remember { mutableStateOf(false) }

                // Tab transition animation state. We intentionally avoid
                // Animatable + LaunchedEffect here: that pattern lets one frame
                // recompose with the previous end-state (progress = 1f) before
                // the effect runs snapTo(0f), causing a visible flash. Instead
                // we write progress synchronously during composition and drive
                // it forward with withFrameNanos.
                var previousNav by remember { mutableStateOf(selectedNav) }
                var fromNav by remember { mutableStateOf(selectedNav) }
                var tabProgress by remember { mutableFloatStateOf(1f) }

                if (selectedNav != previousNav) {
                    fromNav = previousNav
                    previousNav = selectedNav
                    tabProgress = 0f
                }

                LaunchedEffect(previousNav) {
                    if (tabProgress < 1f) {
                        val startNanos = withFrameNanos { it }
                        val durationMs = 280f
                        while (tabProgress < 1f) {
                            val frameNanos = withFrameNanos { it }
                            val elapsedMs = (frameNanos - startNanos) / 1_000_000f
                            val linear = (elapsedMs / durationMs).coerceIn(0f, 1f)
                            tabProgress = FastOutSlowInEasing.transform(linear)
                        }
                    }
                }

                // GL views (star map, planet/star globes) live on a window-layer
                // Surface that Compose can't cover with alpha/zIndex. We keep
                // them visible whenever their tab is *on screen* — either
                // currently selected, or still animating out of view. Using
                // derivedStateOf so this only flips at animation boundaries,
                // not every frame.
                val isStarMapOnScreen by remember {
                    derivedStateOf {
                        selectedNav == NavDestination.STAR_MAP ||
                            (tabProgress < 1f && fromNav == NavDestination.STAR_MAP)
                    }
                }
                val isSystemOnScreen by remember {
                    derivedStateOf {
                        selectedNav == NavDestination.SYSTEM ||
                            (tabProgress < 1f && fromNav == NavDestination.SYSTEM)
                    }
                }

                // Tabs can request the bottom nav be hidden (e.g. System tab
                // fullscreen globe mode). Only the active tab's state matters.
                var systemHidesNav by remember { mutableStateOf(false) }
                val hideBottomNav = selectedNav == NavDestination.SYSTEM && systemHidesNav

                // Measured nav height, exposed to screens via LocalBottomBarHeight
                // so they can pad bottom-anchored content (scroll tails, sliders,
                // filter menus) to avoid being hidden behind the opaque nav.
                var navHeightPx by remember { mutableFloatStateOf(0f) }
                val density = LocalDensity.current
                val navHeightDp = with(density) { navHeightPx.toDp() }

                val navigateToSystem: (String, String?, NavDestination) -> Unit = { hostname, planetName, origin ->
                    pendingHostname = hostname
                    pendingPlanet = planetName
                    systemOriginTab = origin
                    navigationKey++
                    selectedNav = NavDestination.SYSTEM
                }

                val onSelectNav: (NavDestination) -> Unit = {
                    pendingHostname = null
                    pendingPlanet = null
                    // User-initiated tab change resets the cross-tab return
                    // flag — they've navigated away under their own steam,
                    // so a later orbital exit shouldn't bounce back to
                    // System DETAIL on its own.
                    orbitalReturnsToSystem = false
                    selectedNav = it
                }

                // All tabs stay composed to preserve scroll positions and state.
                // During transitions, the incoming tab enters from the side matching
                // its bottom-nav position relative to the outgoing tab: going to a
                // higher-index tab (e.g. CATALOG → SYSTEM) pushes the old tab leftward
                // and slides the new tab in from the right. Going to a lower-index tab
                // does the opposite.
                //
                // Root is a Box (not a Column) so the tab layer is always the full
                // screen. The nav is absolutely positioned at the bottom and slides
                // via graphicsLayer — it never participates in layout, so tab
                // content (globe, FULL-mode bar) naturally reaches the true screen
                // bottom. The nav draws on top of tabs; since it's opaque, content
                // behind it in HALF mode is invisibly covered.
                Box(modifier = Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalBottomBarHeight provides navHeightDp) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                    ) {
                    fun tabModifier(dest: NavDestination): Modifier {
                        val zIndex = when (dest) {
                            selectedNav -> 2f
                            fromNav -> 1f
                            else -> 0f
                        }
                        return Modifier
                            .fillMaxSize()
                            .zIndex(zIndex)
                            .graphicsLayer {
                                val p = tabProgress
                                val direction =
                                    if (selectedNav.ordinal >= fromNav.ordinal) 1f else -1f
                                if (p >= 1f) {
                                    translationX = 0f
                                    alpha = if (dest == selectedNav) 1f else 0f
                                } else {
                                    when (dest) {
                                        selectedNav -> {
                                            // New tab enters from the side it sits on in
                                            // the bottom nav: higher-index → from right
                                            translationX = size.width * direction * (1f - p)
                                            alpha = 1f
                                        }
                                        fromNav -> {
                                            // Old tab exits toward the opposite side
                                            translationX = -size.width * direction * p
                                            alpha = 1f
                                        }
                                        else -> {
                                            alpha = 0f
                                        }
                                    }
                                }
                            }
                    }

                    key(NavDestination.CATALOG) {
                        Box(modifier = tabModifier(NavDestination.CATALOG)) {
                            CatalogScreen(
                                selectedNav = selectedNav,
                                onSelectNav = onSelectNav,
                                onViewSystem = { hostname, planetName ->
                                    navigateToSystem(hostname, planetName, NavDestination.CATALOG)
                                },
                            )
                        }
                    }

                    key(NavDestination.SYSTEM) {
                        Box(modifier = tabModifier(NavDestination.SYSTEM)) {
                            SystemScreen(
                                selectedNav = selectedNav,
                                onSelectNav = onSelectNav,
                                initialHostname = pendingHostname,
                                focusPlanetName = pendingPlanet,
                                navigationKey = navigationKey,
                                onNavigateToCatalog = { selectedNav = systemOriginTab },
                                onNavigateToOrbital = { hostname ->
                                    pendingOrbitalHostname = hostname
                                    pendingOrbitalKey++
                                    orbitalReturnsToSystem = true
                                    selectedNav = NavDestination.STAR_MAP
                                },
                                onHideBottomNavChange = { systemHidesNav = it },
                                isTabOnScreen = isSystemOnScreen,
                            )
                        }
                    }

                    key(NavDestination.STAR_MAP) {
                        Box(modifier = tabModifier(NavDestination.STAR_MAP)) {
                            StarMapScreen(
                                selectedNav = selectedNav,
                                onSelectNav = onSelectNav,
                                onNavigateToSystemPlanet = { hostname, planetName ->
                                    navigateToSystem(hostname, planetName, NavDestination.STAR_MAP)
                                },
                                pendingOrbitalHostname = pendingOrbitalHostname,
                                pendingOrbitalKey = pendingOrbitalKey,
                                onPendingOrbitalConsumed = { pendingOrbitalHostname = null },
                                orbitalReturnsToSystem = orbitalReturnsToSystem,
                                onOrbitalReturnToSystem = {
                                    orbitalReturnsToSystem = false
                                    selectedNav = NavDestination.SYSTEM
                                },
                                isTabOnScreen = isStarMapOnScreen,
                            )
                        }
                    }

                    key(NavDestination.SETTINGS) {
                        Box(modifier = tabModifier(NavDestination.SETTINGS)) {
                            SettingsScreen(
                                selectedNav = selectedNav,
                                onSelectNav = onSelectNav,
                            )
                        }
                    }
                    } // tab Box
                    } // CompositionLocalProvider

                    // Nav is absolutely positioned at the root Box's bottom edge
                    // (screen bottom) and slides via graphicsLayer translation.
                    // Because it's not in any Column, hiding/showing it has zero
                    // layout impact — the tab Box above keeps its fullscreen size,
                    // GL surfaces never re-layout.
                    val navHideProgress by animateFloatAsState(
                        targetValue = if (hideBottomNav) 1f else 0f,
                        animationSpec = tween(320, easing = FastOutSlowInEasing),
                        label = "navHide",
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .onGloballyPositioned { coords ->
                                navHeightPx = coords.size.height.toFloat()
                            }
                            .graphicsLayer {
                                translationY = size.height * navHideProgress
                            },
                    ) {
                        ExoBottomNav(
                            selected = selectedNav,
                            onSelect = onSelectNav,
                            systemEnabled = true,
                        )
                    }
                }

            }
        }
    }
}
