package com.tadmor.app.ui.catalog

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import com.tadmor.app.ui.util.Haptics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tadmor.app.ui.components.ActiveFilterChip
import com.tadmor.app.ui.components.BookmarkFilterButton
import com.tadmor.app.ui.components.ClearAllChip
import com.tadmor.app.ui.components.ExoFilterButton
import com.tadmor.app.ui.components.ExoSearchBar
import com.tadmor.app.ui.components.FilterBottomSheet
import com.tadmor.app.ui.components.SavedOnlyChip
import com.tadmor.app.ui.components.NavDestination
import com.tadmor.app.ui.components.PlanetCard
import com.tadmor.app.ui.components.PullToRefreshBox
import com.tadmor.app.ui.components.RegenerateButton
import com.tadmor.app.ui.components.SortControl
import com.tadmor.app.ui.components.pushOnPress
import com.tadmor.app.ui.components.touchRipple
import com.tadmor.app.ui.util.AstroFormat
import com.tadmor.domain.classification.CompositionClass
import com.tadmor.domain.classification.PlanetClassification
import com.tadmor.domain.classification.visual.IconProfileBuilder
import com.tadmor.domain.model.DistanceUnit
import com.tadmor.domain.model.Planet
import com.tadmor.domain.model.Star
import com.tadmor.domain.model.effectiveSpectralType
import com.tadmor.domain.model.isPulsar
import com.tadmor.domain.model.TemperatureUnit
import com.tadmor.domain.model.UserSettings
import com.tadmor.domain.model.limitPrefix
import com.tadmor.app.ui.theme.ExoTheme
import com.tadmor.app.ui.theme.LocalBottomBarHeight
import com.tadmor.app.ui.theme.TeffColor
import com.tadmor.domain.model.ProperNames
import kotlinx.coroutines.launch
import java.text.NumberFormat

@Composable
fun CatalogScreen(
    selectedNav: NavDestination = NavDestination.CATALOG,
    onSelectNav: (NavDestination) -> Unit = {},
    onViewSystem: (hostname: String, planetName: String?) -> Unit = { _, _ -> },
    viewModel: CatalogViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val colors = ExoTheme.colors
    val type = ExoTheme.type
    val spacing = ExoTheme.spacing

    var showFilterSheet by remember { mutableStateOf(false) }

    // The filter sheet owns its own BackHandler (so it can play an exit
    // animation). No tab-level handler needed here.

        val listState = rememberLazyListState()
        val coroutineScope = rememberCoroutineScope()

        // Track scroll direction: show sticky bar when scrolling up past the search bar
        var previousOffset by remember { mutableIntStateOf(0) }
        var previousIndex by remember { mutableIntStateOf(0) }
        var showStickyBar by remember { mutableStateOf(false) }

        // The search bar is item index 1 (header is 0, search is 1)
        val isPastSearchBar by remember {
            derivedStateOf {
                listState.firstVisibleItemIndex > 1
            }
        }

        LaunchedEffect(listState) {
            snapshotFlow {
                Triple(
                    listState.firstVisibleItemIndex,
                    listState.firstVisibleItemScrollOffset,
                    isPastSearchBar,
                )
            }.collect { (index, offset, pastSearch) ->
                val scrollingUp = index < previousIndex ||
                    (index == previousIndex && offset < previousOffset)
                previousIndex = index
                previousOffset = offset

                showStickyBar = pastSearch && scrollingUp
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                PullToRefreshBox(
                    isRefreshing = state.isRefreshing,
                    onRefresh = { viewModel.onRefresh() },
                    lastSyncedAtMillis = state.settings.lastSyncedAtMillis,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                // Sync error banner
                if (state.syncError != null) {
                    val errorColor = Color(0xFFFF6B6B)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(errorColor.copy(alpha = 0.15f))
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                            ) { viewModel.onDismissSyncError() }
                            .padding(horizontal = spacing.xxxl, vertical = spacing.md),
                    ) {
                        BasicText(
                            text = state.syncError ?: "",
                            style = type.labelSmall.copy(color = errorColor),
                        )
                    }
                }

                val navPadding = LocalBottomBarHeight.current
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = spacing.xxxl + navPadding),
                ) {
                    // Header
                    item {
                        Row(
                            modifier = Modifier.padding(
                                start = spacing.xxxl,
                                end = spacing.xxxl,
                                top = 18.dp,
                            ),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                BasicText(
                                    text = "CATALOG",
                                    style = type.labelLarge.copy(color = colors.textTertiary),
                                )
                                Spacer(Modifier.height(4.dp))
                                val noun = when (state.activeTab) {
                                    CatalogTab.CONFIRMED -> "exoplanets"
                                    CatalogTab.CANDIDATES -> "candidates"
                                    CatalogTab.FALSE_POSITIVES -> "false positives"
                                }
                                val countText = if (state.filterState.activeCount > 0 || state.searchQuery.isNotEmpty() || state.savedOnlyActive) {
                                    "${formatCount(state.filteredCount)} $noun"
                                } else {
                                    "${formatCount(state.totalCount)} $noun"
                                }
                                BasicText(
                                    text = countText,
                                    style = type.displayLarge.copy(color = colors.textPrimary),
                                )
                            }
                            RegenerateButton(
                                onClick = { viewModel.onRefresh() },
                                isRefreshing = state.isRefreshing,
                            )
                        }
                    }

                    // Disposition sub-tabs (CONFIRMED / CANDIDATES / FALSE
                    // POSITIVES). Hidden unless the user has opted in via the
                    // DATA setting; with the toggle off, the catalog reads as
                    // a single confirmed-only experience as before.
                    if (state.settings.includeCandidates) {
                        item(key = "catalogTabs") {
                            CatalogTabBar(
                                activeTab = state.activeTab,
                                onTabChange = { viewModel.onTabChange(it) },
                                modifier = Modifier.padding(
                                    start = spacing.xxxl,
                                    end = spacing.xxxl,
                                    top = spacing.md,
                                ),
                            )
                        }
                    }

                    // Search + filter button
                    item {
                        Row(
                            modifier = Modifier.padding(
                                horizontal = spacing.xxxl,
                                vertical = spacing.md,
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ExoSearchBar(
                                query = state.searchQuery,
                                onQueryChange = { viewModel.onSearchQueryChange(it) },
                                modifier = Modifier.weight(1f),
                                isActive = selectedNav == NavDestination.CATALOG,
                            )
                            Spacer(Modifier.width(spacing.sm))
                            ExoFilterButton(
                                activeCount = state.filterState.activeCount,
                                onClick = { showFilterSheet = true },
                            )
                            Spacer(Modifier.width(spacing.sm))
                            BookmarkFilterButton(
                                isActive = state.savedOnlyActive,
                                onClick = { viewModel.onSavedOnlyToggle() },
                                unreadCount = state.unreadUpdatesCount,
                            )
                        }
                    }

                    // Active filter chips. The whole row is its own LazyColumn
                    // item, keyed so it animates as a unit when activeCount
                    // transitions 0↔1 — fade in/out plus the LazyColumn's
                    // own placement animation slides the items below it
                    // (sort control, planet cards) up/down to make room.
                    // Inside, individual pills use a keyed LazyRow so a
                    // newly-enabled filter fades in, dismissed pills fade
                    // out, and others smoothly slide to their new positions.
                    if (state.filterState.activeCount > 0 || state.savedOnlyActive) {
                        item(key = "filterRow") {
                            LazyRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateItem(
                                        fadeInSpec = tween(220, easing = FastOutSlowInEasing),
                                        placementSpec = tween(260, easing = FastOutSlowInEasing),
                                        fadeOutSpec = tween(180, easing = FastOutSlowInEasing),
                                    ),
                                contentPadding = PaddingValues(horizontal = spacing.xxxl),
                                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                            ) {
                                // "Saved only" pill always sits at the
                                // leftmost position when the bookmark
                                // filter is on. Tapping × turns the
                                // bookmark filter off (mirror of tapping
                                // the BookmarkFilterButton in the header).
                                if (state.savedOnlyActive) {
                                    item(key = "savedOnly") {
                                        SavedOnlyChip(
                                            onDismiss = { viewModel.onSavedOnlyToggle() },
                                            modifier = Modifier.animateItem(
                                                fadeInSpec = tween(220, easing = FastOutSlowInEasing),
                                                placementSpec = tween(260, easing = FastOutSlowInEasing),
                                                fadeOutSpec = tween(180, easing = FastOutSlowInEasing),
                                            ),
                                        )
                                    }
                                }
                                items(
                                    items = state.filterState.activeLabels,
                                    key = { (group, label) -> "$group:$label" },
                                ) { (group, label) ->
                                    val displayLabel = if (group == "composition") {
                                        translateClassLabel(label, state.settings)
                                    } else label
                                    ActiveFilterChip(
                                        label = displayLabel,
                                        onDismiss = {
                                            viewModel.onFilterChange(
                                                state.filterState.remove(group, label),
                                            )
                                        },
                                        modifier = Modifier.animateItem(
                                            fadeInSpec = tween(220, easing = FastOutSlowInEasing),
                                            placementSpec = tween(260, easing = FastOutSlowInEasing),
                                            fadeOutSpec = tween(180, easing = FastOutSlowInEasing),
                                        ),
                                    )
                                }
                                item(key = "clearAll") {
                                    ClearAllChip(
                                        onClick = {
                                            viewModel.onFilterChange(state.filterState.clear())
                                            if (state.savedOnlyActive) viewModel.onSavedOnlyToggle()
                                        },
                                        modifier = Modifier.animateItem(
                                            fadeInSpec = tween(220, easing = FastOutSlowInEasing),
                                            placementSpec = tween(260, easing = FastOutSlowInEasing),
                                            fadeOutSpec = tween(180, easing = FastOutSlowInEasing),
                                        ),
                                    )
                                }
                            }
                        }
                    }

                    // Sort control. Keyed + animateItem so it slides down
                    // when the filter row appears and back up when it's
                    // cleared, instead of snapping.
                    item(key = "sortControl") {
                        SortControl(
                            currentSort = state.sortOption,
                            ascending = state.sortAscending,
                            onSortChange = { option, asc ->
                                viewModel.onSortChange(option, asc)
                            },
                            modifier = Modifier
                                .padding(
                                    horizontal = spacing.xxxl,
                                    vertical = spacing.sm,
                                )
                                .animateItem(
                                    placementSpec = tween(260, easing = FastOutSlowInEasing),
                                ),
                        )
                    }

                    item(key = "filterSpacer") {
                        Spacer(
                            Modifier
                                .height(spacing.md)
                                .animateItem(
                                    placementSpec = tween(260, easing = FastOutSlowInEasing),
                                ),
                        )
                    }

                    // Empty cache prompt — appears only when the catalog
                    // database itself is empty (first launch before sync, or
                    // if auto-sync hasn't run yet). Mirrors the System tab's
                    // empty-search prompt so users have a consistent place
                    // to look for guidance.
                    if (state.totalCount == 0) {
                        item(key = "emptyCachePrompt") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        horizontal = spacing.xxxl,
                                        vertical = spacing.xxxxl,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                BasicText(
                                    text = "Pull down or tap the refresh button to sync the catalog — may take a few minutes.",
                                    style = type.bodyLarge.copy(
                                        color = colors.textTertiary,
                                        textAlign = TextAlign.Center,
                                    ),
                                )
                            }
                        }
                    }

                    // Planet cards
                    items(
                        items = state.entries,
                        key = { it.planet.name },
                    ) { entry ->
                        val classColor = entry.classification.compositionClass.toColor()
                        val starColor = entry.star?.let { TeffColor.forStar(it.teffK, it.effectiveSpectralType()) }
                        val properName = ProperNames.forPlanet(entry.planet.name)
                        val rawDisplayName = if (state.settings.useProperNames && properName != null) {
                            properName
                        } else {
                            entry.planet.name
                        }
                        // Defensive cap: a multi-MB or newline-heavy name string
                        // makes BasicText allocate huge text-layout buffers and
                        // grow the card vertically without bound, masquerading
                        // as an "empty card with infinite expansion" because the
                        // visible content is all whitespace / control characters.
                        // TAP catalog names are < 30 chars in practice; anything
                        // longer than 80 is data corruption — truncate so the
                        // card stays sensibly sized and log the offender so the
                        // root cause can be tracked down.
                        val displayName = if (rawDisplayName.length > 80) {
                            timber.log.Timber.w(
                                "Catalog: planet '${rawDisplayName.take(40)}…' " +
                                "has abnormally long display name (" +
                                "${rawDisplayName.length} chars) — truncated"
                            )
                            rawDisplayName.take(80) + "…"
                        } else {
                            rawDisplayName
                        }
                        val iconProfile = remember(entry.planet.name) {
                            IconProfileBuilder.build(entry.visualProfile, entry.classification)
                        }
                        PlanetCard(
                            planetName = displayName,
                            spectralType = formatSpectralType(entry.star, state.settings.useEstimates),
                            starDistance = formatDistance(entry.star?.distancePc, state.settings.distanceUnit),
                            classificationLabel = formatClassLabel(entry.classification.fullLabel, state.settings),
                            classificationColor = classColor,
                            iconProfile = iconProfile,
                            mass = formatMass(entry.planet, entry.classification.compositionClass, state.settings),
                            radius = formatRadius(entry.planet, entry.classification.compositionClass, state.settings),
                            temp = formatTemp(entry.planet, entry.classification, state.settings.temperatureUnit, state.settings.useEstimates),
                            period = formatPeriod(entry.planet, entry.classification, state.settings.useEstimates),
                            isExpanded = state.expandedPlanetName == entry.planet.name,
                            onClick = { viewModel.onPlanetClick(entry.planet.name) },
                            dataCompleteness = entry.dataCompleteness,
                            showDataIndicator = state.settings.showDataIndicator,
                            spectralColor = starColor ?: Color.Unspecified,
                            eccentricity = entry.planet.eccentricity?.let { "${limitPrefix(entry.planet.eccentricityLimit)}%.3f".format(it) },
                            inclination = entry.planet.inclination?.let { "${limitPrefix(entry.planet.inclinationLimit)}%.1f°".format(it) },
                            semiMajorAxis = entry.planet.semiMajorAxisAU?.let { "${limitPrefix(entry.planet.semiMajorAxisLimit)}%.3f AU".format(it) }
                                ?: if (state.settings.useEstimates) entry.classification.estimatedSemiMajorAxisAU?.let { "~%.3f AU".format(it) } else null,
                            density = entry.planet.densityGCm3?.let { "${limitPrefix(entry.planet.densityLimit)}%.2f g/cm³".format(it) },
                            discoveryMethod = entry.planet.discoveryMethod,
                            discoveryYear = entry.planet.discoveryYear?.toString(),
                            onViewSystem = { onViewSystem(entry.planet.hostname, entry.planet.name) },
                            isBookmarked = entry.planet.name in state.bookmarkedKeys,
                            onBookmarkToggle = { viewModel.onBookmarkToggle(entry.planet.name) },
                            updateCount = state.diffsByKey[entry.planet.name]?.updateCount ?: 0,
                            modifier = Modifier
                                .animateItem(
                                    placementSpec = tween(260, easing = FastOutSlowInEasing),
                                )
                                .padding(
                                    horizontal = spacing.lg,
                                    vertical = spacing.xs / 2,
                                ),
                        )
                    }
                }
                } // PullToRefreshBox
            }

            // Sticky search bar overlay — slides in from top when scrolling up past search bar
            AnimatedVisibility(
                visible = showStickyBar,
                enter = slideInVertically(tween(200)) { -it },
                exit = slideOutVertically(tween(200)) { -it },
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.background)
                        .padding(
                            start = spacing.xxxl,
                            end = spacing.xxxl,
                            top = 18.dp,
                            bottom = spacing.md,
                        ),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ExoSearchBar(
                            query = state.searchQuery,
                            onQueryChange = { viewModel.onSearchQueryChange(it) },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(spacing.sm))
                        ExoFilterButton(
                            activeCount = state.filterState.activeCount,
                            onClick = { showFilterSheet = true },
                        )
                        Spacer(Modifier.width(spacing.sm))
                        JumpToTopButton(
                            onClick = {
                                coroutineScope.launch {
                                    listState.animateScrollToItem(0)
                                }
                            },
                        )
                    }
                }
            }

            // Filter sheet overlay
            if (showFilterSheet) {
                FilterBottomSheet(
                    filterState = state.filterState,
                    settings = state.settings,
                    onFilterChange = { viewModel.onFilterChange(it) },
                    onDismiss = { showFilterSheet = false },
                )
            }
        }
}

@Composable
private fun JumpToTopButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ExoTheme.colors
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(shape)
            .background(colors.surfaceInput)
            .touchRipple(
                color = Color.White,
                startAlpha = 0.22f,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Up arrow icon
        Box(
            modifier = Modifier
                .size(20.dp)
                .drawBehind {
                    val color = colors.textSecondary
                    val sw = 1.2.dp.toPx()
                    val cx = size.width / 2f
                    // Arrow pointing up
                    drawLine(
                        color, Offset(0f, size.height * 0.6f),
                        Offset(cx, size.height * 0.2f), sw, cap = StrokeCap.Round,
                    )
                    drawLine(
                        color, Offset(size.width, size.height * 0.6f),
                        Offset(cx, size.height * 0.2f), sw, cap = StrokeCap.Round,
                    )
                    // Vertical line
                    drawLine(
                        color, Offset(cx, size.height * 0.2f),
                        Offset(cx, size.height * 0.9f), sw, cap = StrokeCap.Round,
                    )
                },
        )
    }
}

/**
 * Conjoined three-segment selector for confirmed / candidates / false
 * positives. Single rounded rectangle with a sliding accent-gold highlight
 * that animates between equal-width segments on selection. Labels are
 * uppercase to match the section-header convention used elsewhere.
 *
 * The highlight is painted via [Modifier.drawBehind] on the bar itself
 * rather than as a sibling Box — sibling layout couldn't determine a
 * reliable height (BoxWithConstraints' height is content-driven by the
 * Row, but `fillMaxHeight()` on a sibling resolves before the Row's
 * intrinsic height is known, so the highlight painted at zero height).
 * Drawing inside the bar's own draw scope sidesteps the layout cycle.
 */
@Composable
private fun CatalogTabBar(
    activeTab: CatalogTab,
    onTabChange: (CatalogTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type
    val context = LocalContext.current
    val tabs = CatalogTab.entries
    val activeIndex = tabs.indexOf(activeTab).coerceAtLeast(0)
    val outerShape = RoundedCornerShape(20.dp)

    val animatedIndex by animateFloatAsState(
        targetValue = activeIndex.toFloat(),
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label = "catalogTabHighlight",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(outerShape)
            .drawBehind {
                // No filled bar background — the bar is a thin outlined
                // rectangle on the catalog page bg. Painting the 7%-alpha
                // highlight on top of an opaque surfaceRaised mid-tone made
                // both the gold fill and the gold border read washed out
                // (the lighter base reduced the perceptual saturation of
                // every accent on top). Letting the highlight blend with
                // the dark page bg directly matches the saturation level
                // of the same accent tokens used by OptionRow in settings.
                val segmentWidth = size.width / tabs.size.toFloat()
                val pad = 2.dp.toPx()
                val cornerR = 18.dp.toPx()
                val left = segmentWidth * animatedIndex + pad
                val top = pad
                val width = segmentWidth - pad * 2
                val height = size.height - pad * 2
                drawRoundRect(
                    color = colors.accentGoldSubtle,
                    topLeft = Offset(left, top),
                    size = Size(width, height),
                    cornerRadius = CornerRadius(cornerR, cornerR),
                )
                drawRoundRect(
                    color = colors.accentGoldBorder,
                    topLeft = Offset(left, top),
                    size = Size(width, height),
                    cornerRadius = CornerRadius(cornerR, cornerR),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
            .border(1.dp, colors.divider, outerShape),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            tabs.forEach { tab ->
                val isActive = tab == activeTab
                val pressSource = remember(tab) { MutableInteractionSource() }
                val animatedTextColor by animateColorAsState(
                    targetValue = if (isActive) colors.accentGold else colors.textSecondary,
                    animationSpec = tween(260, easing = FastOutSlowInEasing),
                    label = "catalogTabText",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .pushOnPress(pressSource)
                        .clickable(
                            indication = null,
                            interactionSource = pressSource,
                            onClick = {
                                if (tab != activeTab) Haptics.select(context)
                                onTabChange(tab)
                            },
                        )
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        text = tab.label.uppercase(),
                        style = type.labelLarge.copy(color = animatedTextColor),
                    )
                }
            }
        }
    }
}

// --- Formatting helpers ---

private val numberFormat = NumberFormat.getNumberInstance()

private fun formatCount(count: Int): String = numberFormat.format(count)

private fun formatMass(planet: Planet, composition: CompositionClass, settings: UserSettings): String? {
    val e = AstroFormat.earth(settings)
    val j = AstroFormat.jupiter(settings)
    if (composition == CompositionClass.JUPITER) {
        planet.massJupiter?.let { return "${limitPrefix(planet.massJupiterLimit)}%.2f M$j".format(it) }
        planet.massEarth?.let { return "${limitPrefix(planet.massEarthLimit)}%.2f M$j".format(it / 317.8) }
    } else {
        planet.massEarth?.let { return "${limitPrefix(planet.massEarthLimit)}%.2f M$e".format(it) }
        planet.massJupiter?.let { return "${limitPrefix(planet.massJupiterLimit)}%.2f M$e".format(it * 317.8) }
    }
    return null
}

private fun formatRadius(planet: Planet, composition: CompositionClass, settings: UserSettings): String? {
    val re = planet.radiusEarth ?: return null
    val prefix = limitPrefix(planet.radiusEarthLimit)
    if (composition == CompositionClass.JUPITER) {
        return "${prefix}%.2f R${AstroFormat.jupiter(settings)}".format(re / 11.21)
    }
    return "${prefix}%.2f R${AstroFormat.earth(settings)}".format(re)
}

private fun formatTemp(
    planet: Planet,
    classification: PlanetClassification,
    unit: TemperatureUnit = TemperatureUnit.KELVIN,
    useEstimates: Boolean = true,
): String? {
    val k = planet.eqTempK ?: (if (useEstimates) classification.estimatedEqTempK else null) ?: return null
    val estimated = planet.eqTempK == null
    val prefix = if (estimated) "~" else limitPrefix(planet.eqTempKLimit)
    val value = when (unit) {
        TemperatureUnit.KELVIN -> k
        TemperatureUnit.CELSIUS -> k - 273.15
        TemperatureUnit.FAHRENHEIT -> (k - 273.15) * 9.0 / 5.0 + 32.0
    }
    val unitLabel = when (unit) {
        TemperatureUnit.KELVIN -> "K"
        TemperatureUnit.CELSIUS -> "°C"
        TemperatureUnit.FAHRENHEIT -> "°F"
    }
    val rounded = "%.0f".format(value).let { if (it == "-0") "0" else it }
    return "$prefix$rounded $unitLabel"
}

private fun formatPeriod(
    planet: Planet,
    classification: PlanetClassification,
    useEstimates: Boolean = true,
): String? {
    val days = planet.orbitalPeriodDays
        ?: (if (useEstimates) classification.estimatedOrbitalPeriodDays else null)
        ?: return null
    val estimated = planet.orbitalPeriodDays == null
    val prefix = if (estimated) "~" else limitPrefix(planet.orbitalPeriodLimit)
    return if (days > 365) "${prefix}%.1f yr".format(days / 365.25)
    else "${prefix}%.1f d".format(days)
}

private fun formatSpectralType(star: Star?, useEstimates: Boolean = true): String? {
    if (star == null) return null
    star.spectralType?.let { return decodeHtmlEntities(it.trim()) }
    // Pulsars get the synthetic "Q" label even with no Teff/spectral data.
    if (star.isPulsar()) return "Q"
    if (useEstimates) star.teffK?.let { return inferSpectralClass(it) }
    return null
}

private fun inferSpectralClass(teffK: Double): String = when {
    teffK >= 30000 -> "~O"
    teffK >= 10000 -> "~B"
    teffK >= 7500 -> "~A"
    teffK >= 6000 -> "~F"
    teffK >= 5200 -> "~G"
    teffK >= 3700 -> "~K"
    teffK >= 2400 -> "~M"
    teffK >= 1300 -> "~L"
    teffK >= 300 -> "~T"
    else -> "~Y"
}

private fun formatDistance(distancePc: Double?, unit: DistanceUnit = DistanceUnit.PARSECS): String? {
    if (distancePc == null) return null
    return when (unit) {
        DistanceUnit.PARSECS -> "%.1f pc".format(distancePc)
        DistanceUnit.LIGHT_YEARS -> "%.1f ly".format(distancePc * 3.26156)
    }
}

private fun formatClassLabel(
    fullLabel: String,
    settings: UserSettings,
): String {
    var result = fullLabel
    if (!settings.useTerra) result = result.replace("Terrestrial", "Earth", ignoreCase = true)
    if (!settings.useNeptune) result = result.replace("Neptune", "Ice Giant", ignoreCase = true)
    if (!settings.useJupiter) result = result.replace("Jupiter", "Gas Giant", ignoreCase = true)
    return result.uppercase()
}

private fun translateClassLabel(canonical: String, settings: UserSettings): String = when {
    canonical.equals("Terrestrial", ignoreCase = true) -> if (settings.useTerra) "Terrestrial" else "Earth"
    canonical.equals("Neptune", ignoreCase = true) -> if (settings.useNeptune) "Neptune" else "Ice Giant"
    canonical.equals("Jupiter", ignoreCase = true) -> if (settings.useJupiter) "Jupiter" else "Gas Giant"
    else -> canonical
}

private fun decodeHtmlEntities(text: String): String = text
    .replace("&plusmn;", "±")
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&deg;", "°")
    .replace("&ndash;", "–")
    .replace("&mdash;", "—")
