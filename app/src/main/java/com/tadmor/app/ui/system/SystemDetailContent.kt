package com.tadmor.app.ui.system

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.tadmor.app.ui.catalog.toColor
import com.tadmor.app.ui.components.ChevronButton
import com.tadmor.app.ui.components.PlanetIcon
import com.tadmor.app.ui.components.programmaticRipple
import com.tadmor.app.ui.components.pushOnPress
import com.tadmor.app.ui.components.touchRipple
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import com.tadmor.app.ui.theme.ClassificationColor
import com.tadmor.app.ui.theme.ExoTheme
import com.tadmor.app.ui.theme.LocalBottomBarHeight
import com.tadmor.app.ui.theme.TeffColor
import com.tadmor.app.ui.util.AstroFormat
import com.tadmor.domain.classification.CompositionClass
import com.tadmor.domain.classification.PlanetClassification
import com.tadmor.domain.classification.visual.IconProfileBuilder
import com.tadmor.domain.model.DistanceUnit
import com.tadmor.domain.model.Planet
import com.tadmor.domain.model.ProperNames
import com.tadmor.domain.model.Star
import com.tadmor.domain.model.effectiveRadiusSolar
import com.tadmor.domain.model.effectiveSpectralType
import com.tadmor.domain.model.isPulsar
import com.tadmor.domain.model.SystemDetail
import com.tadmor.domain.model.SystemPlanetEntry
import com.tadmor.domain.model.TemperatureUnit
import com.tadmor.domain.model.UserSettings
import com.tadmor.domain.model.limitPrefix

private val STAR_GLOBE_HALF_HEIGHT = 200.dp

@Composable
fun SystemDetailContent(
    detail: SystemDetail,
    settings: UserSettings,
    orbitalState: OrbitalState?,
    onPlanetSelected: (String) -> Unit,
    onBack: () -> Unit,
    onViewOrbits: () -> Unit = {},
    globeMode: GlobeMode = GlobeMode.HALF,
    onGlobeModeChange: (GlobeMode) -> Unit = {},
    globeParams: StarGlobeParams = StarGlobeParams(),
    isActive: Boolean = true,
    /** Whether the System tab itself is currently on screen. Distinct from
     *  [isActive] (which is also gated on the DETAIL sub-page being active)
     *  so the star globe surface can stay alive across PLANET ↔ DETAIL
     *  sub-page transitions without going GONE — only the tab leaving the
     *  screen entirely should destroy the EGL surface. Without this, going
     *  back from the planet page caused a surface-recreation lag that read
     *  as a black flash through the slide-in animation. */
    isTabOnScreen: Boolean = true,
    /** Per-planet update counts (planet name → count), used by the
     *  per-planet cards in this system to render the gold left edge +
     *  "n updated" indicator. Empty map = no bookmark updates. */
    updateCountsByKey: Map<String, Int> = emptyMap(),
    /** Hostname of the star currently driving the globe view. In multi-
     *  star (binary) systems this can be any star in [detail]; tapping a
     *  different star's name header in the page header swaps the globe.
     *  In single-star systems this is always [SystemDetail.star.hostname]. */
    selectedStarHostname: String = detail.star.hostname,
    /** Invoked with the tapped star's hostname when the user taps a
     *  multi-star name header. Caller is responsible for fading the globe
     *  to/from black around the swap (see SystemScreen). */
    onSelectStar: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type
    val spacing = ExoTheme.spacing
    // Star name headers behave as a selector in multi-star systems —
    // tapping a non-selected star's name routes through [onSelectStar]
    // and the parent fades the globe to that star. Single-star systems
    // skip the tappable affordance entirely.
    val multiStar = detail.companionStars.isNotEmpty()
    // Currently-selected star — the one driving the globe. Defaults to the
    // primary when [selectedStarHostname] doesn't match any known star
    // (defensive against stale callers). The FULL-mode title bars follow
    // this so the fullscreen view's labels match the rendered globe.
    val selectedStar = if (selectedStarHostname == detail.star.hostname) {
        detail.star
    } else {
        detail.companionStars.find { it.hostname == selectedStarHostname } ?: detail.star
    }
    // Primary-star display name (the COMPARE section compares the system's
    // primary star to Sol regardless of which star the user has selected
    // for the globe — selecting a companion doesn't reframe the
    // comparison). [StarNameHeader] handles per-star resolution for the
    // tappable name headers.
    val starProperName = ProperNames.forStar(detail.star.hostname)
    val starDisplayName = if (settings.useProperNames && starProperName != null) starProperName else detail.star.hostname
    // FULL-mode title bars track the selected star so the fullscreen
    // globe view labels match the rendered surface in binary systems.
    val selectedProperName = ProperNames.forStar(selectedStar.hostname)
    val selectedDisplayName = if (settings.useProperNames && selectedProperName != null) selectedProperName else selectedStar.hostname
    val selectedAltName = if (settings.useProperNames && selectedProperName != null) selectedStar.hostname else selectedProperName

    // Hoisted so it survives recomposition while scrolling the strip off-screen
    var stripZoomFactor by remember(detail.star.hostname) { mutableFloatStateOf(1f) }

    val isFull = globeMode == GlobeMode.FULL
    // Key on hostname so each star gets its own scroll position — otherwise
    // scrolling star A and then navigating to star B carries A's offset over.
    val scrollState = remember(detail.star.hostname) { ScrollState(0) }
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val dragState = rememberGlobeDragState(isFull, onGlobeModeChange)

    // System-strip planet tap support. Tapping a planet on the strip
    // shouldn't navigate — it should scroll the card into view and play a
    // ripple on it. We track each card's Y offset (set via
    // onGloballyPositioned) so we can scroll to it, and a per-planet ripple
    // signal counter that the card watches via `programmaticRipple`.
    // Re-keyed on hostname so switching stars resets both maps.
    val planetCardYs = remember(detail.star.hostname) {
        mutableStateMapOf<String, Int>()
    }
    val planetRippleSignals = remember(detail.star.hostname) {
        mutableStateMapOf<String, Int>()
    }
    val stripScope = rememberCoroutineScope()

    // Commit-animation state. The external [globeMode] flips instantly (drag
    // release past threshold OR HW back); this state trails it so both paths
    // run the same animation. See PlanetDetailContent for details.
    val backOffscreenPx = with(density) { 80.dp.toPx() }
    var displayMode by remember { mutableStateOf(globeMode) }
    val commitAlpha = remember {
        Animatable(if (globeMode == GlobeMode.FULL) 1f else 0f)
    }
    val commitOffsetPx = remember { Animatable(0f) }
    val backButtonOffsetPx = remember {
        Animatable(if (globeMode == GlobeMode.FULL) -backOffscreenPx else 0f)
    }

    // Reset scroll position when exiting fullscreen
    LaunchedEffect(isFull) {
        if (!isFull) scrollState.scrollTo(0)
    }

    LaunchedEffect(globeMode) {
        if (globeMode == displayMode) return@LaunchedEffect
        // Slide distance = viewport height, so HALF content travels exactly
        // off-screen over the full animation duration.
        val exitPx = with(density) { configuration.screenHeightDp.dp.toPx() }
        val durationMs = 320
        if (globeMode == GlobeMode.FULL) {
            // HALF → FULL: animate dragState.offsetPx to 0 in parallel with
            // the commit slide so the bottom bar rides up smoothly instead
            // of snapping at the end.
            displayMode = GlobeMode.FULL
            coroutineScope {
                launch {
                    commitOffsetPx.animateTo(
                        exitPx,
                        tween(durationMs, easing = FastOutSlowInEasing),
                    )
                }
                launch { dragState.animateOffsetToZero(durationMs) }
                launch {
                    commitAlpha.animateTo(
                        1f,
                        tween(durationMs, easing = FastOutSlowInEasing),
                    )
                }
                launch {
                    backButtonOffsetPx.animateTo(
                        -backOffscreenPx,
                        tween(220, easing = FastOutSlowInEasing),
                    )
                }
            }
            commitOffsetPx.snapTo(0f)
        } else {
            // FULL → HALF: keep dragState.offsetPx alive and animate it to 0
            // in parallel so the bottom bar, fullExitProgress alphas, and
            // rising-slab translation all trail the commit smoothly instead
            // of snapping when dragState resets.
            commitOffsetPx.snapTo(exitPx)
            backButtonOffsetPx.snapTo(-backOffscreenPx)
            displayMode = GlobeMode.HALF
            coroutineScope {
                launch {
                    commitOffsetPx.animateTo(
                        0f,
                        tween(durationMs, easing = FastOutSlowInEasing),
                    )
                }
                launch { dragState.animateOffsetToZero(durationMs) }
                launch {
                    commitAlpha.animateTo(
                        0f,
                        tween(durationMs, easing = FastOutSlowInEasing),
                    )
                }
                launch {
                    backButtonOffsetPx.animateTo(
                        0f,
                        tween(durationMs, easing = FastOutSlowInEasing),
                    )
                }
            }
        }
    }

    // Back button Y: pinned at spacing.lg over the globe, then scrolls with content
    val backFixedPx = with(density) { spacing.lg.toPx() }
    val backNaturalPx = with(density) { (STAR_GLOBE_HALF_HEIGHT - spacing.xxxl - spacing.xxl).toPx() }

    val globeHalfPx = with(density) { STAR_GLOBE_HALF_HEIGHT.roundToPx() }
    val isGlobeVisible = isFull || scrollState.value < globeHalfPx

    Box(modifier = modifier
        .fillMaxSize()
        .nestedScroll(dragState.nestedScrollConnection)
    ) {
        // Layer 0: Star globe — always fullscreen. Camera target offset
        // handles HALF positioning. Parallax: while in HALF mode the globe
        // view translates upward at half the scroll rate, so the star feels
        // "anchored deeper" than the surrounding text as the page scrolls.
        // The effect fades out via `commitAlpha` during the HALF→FULL
        // transition so the FULL-mode globe sits at its natural origin
        // regardless of how far the user had scrolled before swiping up.
        StarGlobeView(
            params = globeParams,
            globeMode = globeMode,
            onGlobeModeChange = onGlobeModeChange,
            isActive = isActive,
            isTabOnScreen = isTabOnScreen,
            isGlobeVisible = isGlobeVisible,
            invertControls = settings.invertCameraControls,
            modifier = Modifier.offset {
                val fade = 1f - commitAlpha.value
                IntOffset(0, (-scrollState.value / 5f * fade).roundToInt())
            },
        )

        // Layer 1: Scrollable content. Mounted in HALF mode and during commit
        // animations so it can slide off / rise into view.
        if (displayMode == GlobeMode.HALF || commitAlpha.value < 1f) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .offset {
                        IntOffset(
                            0,
                            (dragState.offsetPx + commitOffsetPx.value).roundToInt(),
                        )
                    }
                    .verticalScroll(scrollState),
            ) {
                // Transparent spacer — content starts 32dp before globe bottom
                Spacer(Modifier.height(STAR_GLOBE_HALF_HEIGHT - 32.dp))

                // Gradient fade from transparent (showing globe) to app background
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, colors.background),
                            ),
                        ),
                )

                // Text content on app background
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.background)
                        .padding(top = spacing.lg),
                ) {
                    // Star name header (back button is a position-computed overlay)
                    StarNameHeader(
                        star = detail.star,
                        settings = settings,
                        isSelected = selectedStarHostname == detail.star.hostname,
                        multiStar = multiStar,
                        onTap = { onSelectStar(detail.star.hostname) },
                        modifier = Modifier.padding(horizontal = spacing.xxxl),
                    )

                    // Star properties
                    StarPropertiesSection(
                        star = detail.star,
                        settings = settings,
                        modifier = Modifier.padding(
                            horizontal = spacing.xxxl,
                            vertical = spacing.lg,
                        ),
                    )

                    // Companion star properties (binary systems only). Each
                    // companion name doubles as a tappable selector for the
                    // globe view — see [StarNameHeader].
                    for (companion in detail.companionStars) {
                        Column(modifier = Modifier.padding(horizontal = spacing.xxxl)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(colors.divider),
                            )
                            Spacer(Modifier.height(spacing.lg))
                        }
                        StarNameHeader(
                            star = companion,
                            settings = settings,
                            isSelected = selectedStarHostname == companion.hostname,
                            multiStar = multiStar,
                            onTap = { onSelectStar(companion.hostname) },
                            modifier = Modifier.padding(horizontal = spacing.xxxl),
                        )
                        StarPropertiesSection(
                            star = companion,
                            settings = settings,
                            modifier = Modifier.padding(
                                horizontal = spacing.xxxl,
                                vertical = spacing.lg,
                            ),
                        )
                    }

                    // System structure strip
                    if (orbitalState != null && orbitalState.planets.isNotEmpty()) {
                        Column(modifier = Modifier.padding(horizontal = spacing.xxxl)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(colors.divider),
                            )
                            Spacer(Modifier.height(spacing.lg))
                            // Header row: "SYSTEM STRUCTURE" left, "View
                            // orbits >" right. The link cross-navigates to
                            // the Star Map's ORBITAL mode for this host —
                            // the only path from the System tab into the
                            // 3D orbital view.
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                BasicText(
                                    text = "SYSTEM STRUCTURE",
                                    style = type.labelSmall.copy(color = colors.textTertiary),
                                )
                                val viewOrbitsInteraction = remember { MutableInteractionSource() }
                                Row(
                                    modifier = Modifier
                                        .pushOnPress(viewOrbitsInteraction)
                                        .clickable(
                                            indication = null,
                                            interactionSource = viewOrbitsInteraction,
                                            onClick = onViewOrbits,
                                        ),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    BasicText(
                                        text = "View orbits",
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
                            Spacer(Modifier.height(spacing.md))
                        }
                        SystemStripView(
                            orbitalState = orbitalState,
                            zoomFactor = stripZoomFactor,
                            onZoomFactorChange = { stripZoomFactor = it },
                            onPlanetTap = { name ->
                                // Scroll the card to ~100 dp from the
                                // viewport top, then pause briefly, then
                                // bump the ripple counter so the wash plays
                                // on a settled card. Sequencing the ripple
                                // *after* the scroll avoids two competing
                                // animations and reads as "navigate to it,
                                // then highlight it." Skip when the card's
                                // position hasn't been measured yet.
                                val targetY = planetCardYs[name] ?: return@SystemStripView
                                val topInsetPx = with(density) { 100.dp.toPx().toInt() }
                                stripScope.launch {
                                    scrollState.animateScrollTo(
                                        (targetY - topInsetPx).coerceAtLeast(0),
                                    )
                                    delay(120)
                                    planetRippleSignals[name] =
                                        (planetRippleSignals[name] ?: 0) + 1
                                }
                            },
                            modifier = Modifier.padding(horizontal = spacing.lg),
                        )
                        if (orbitalState.excludedCount > 0) {
                            BasicText(
                                text = "${orbitalState.excludedCount} planet${if (orbitalState.excludedCount > 1) "s" else ""} not shown — insufficient orbital data",
                                style = type.labelSmall.copy(color = colors.textMuted),
                                modifier = Modifier.padding(
                                    horizontal = spacing.xxxl,
                                    vertical = spacing.sm,
                                ),
                            )
                        }
                        Spacer(Modifier.height(spacing.lg))
                    }

                    // Planets section header — hidden when empty (only
                    // possible on candidate-only systems landed via the
                    // System tab now that candidates are surfaced).
                    if (detail.planets.isNotEmpty()) {
                        Column(modifier = Modifier.padding(horizontal = spacing.xxxl)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(colors.divider),
                            )
                            Spacer(Modifier.height(spacing.lg))
                            BasicText(
                                text = "PLANETS (${detail.planets.size})",
                                style = type.labelSmall.copy(color = colors.textTertiary),
                            )
                            Spacer(Modifier.height(spacing.md))
                        }
                    }

                    // Planet cards — tap to open planet detail page. Each
                    // card publishes its Y offset (relative to the parent
                    // scrolling Column) so a strip-tap can scroll it into
                    // view, and watches its ripple-signal counter so the
                    // strip-tap can also play a centre ripple on it.
                    for (entry in detail.planets) {
                        val planetName = entry.planet.name
                        SystemPlanetCard(
                            entry = entry,
                            settings = settings,
                            onClick = { onPlanetSelected(planetName) },
                            externalRippleSignal = planetRippleSignals[planetName] ?: 0,
                            updateCount = updateCountsByKey[planetName] ?: 0,
                            modifier = Modifier
                                .padding(
                                    horizontal = spacing.lg,
                                    vertical = spacing.xs / 2,
                                )
                                .onGloballyPositioned { coords ->
                                    // positionInParent gives the y offset
                                    // inside the scrollable Column's
                                    // content (it doesn't shift as the
                                    // user scrolls), which is exactly the
                                    // value `scrollState.animateScrollTo`
                                    // takes.
                                    planetCardYs[planetName] = coords.positionInParent().y.toInt()
                                },
                        )
                    }

                    // CANDIDATES + FALSE POSITIVES sections, gated by the
                    // master setting. Each section header is suppressed when
                    // its bucket is empty (per plan: "show PLANETS (2) and
                    // FALSE POSITIVES (1) sections but not the CANDIDATES
                    // section" when there are no candidates).
                    if (settings.includeCandidates) {
                        if (detail.candidates.isNotEmpty()) {
                            Spacer(Modifier.height(spacing.xxxl))
                            Column(modifier = Modifier.padding(horizontal = spacing.xxxl)) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .background(colors.divider),
                                )
                                Spacer(Modifier.height(spacing.lg))
                                BasicText(
                                    text = "CANDIDATES (${detail.candidates.size})",
                                    style = type.labelSmall.copy(color = colors.textTertiary),
                                )
                                Spacer(Modifier.height(spacing.md))
                            }
                            for (entry in detail.candidates) {
                                val planetName = entry.planet.name
                                SystemPlanetCard(
                                    entry = entry,
                                    settings = settings,
                                    onClick = { onPlanetSelected(planetName) },
                                    externalRippleSignal = planetRippleSignals[planetName] ?: 0,
                                    updateCount = updateCountsByKey[planetName] ?: 0,
                                    modifier = Modifier
                                        .padding(
                                            horizontal = spacing.lg,
                                            vertical = spacing.xs / 2,
                                        )
                                        .onGloballyPositioned { coords ->
                                            planetCardYs[planetName] = coords.positionInParent().y.toInt()
                                        },
                                )
                            }
                        }
                        if (detail.falsePositives.isNotEmpty()) {
                            Spacer(Modifier.height(spacing.xxxl))
                            Column(modifier = Modifier.padding(horizontal = spacing.xxxl)) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .background(colors.divider),
                                )
                                Spacer(Modifier.height(spacing.lg))
                                BasicText(
                                    text = "FALSE POSITIVES (${detail.falsePositives.size})",
                                    style = type.labelSmall.copy(color = colors.textTertiary),
                                )
                                Spacer(Modifier.height(spacing.md))
                            }
                            for (entry in detail.falsePositives) {
                                val planetName = entry.planet.name
                                SystemPlanetCard(
                                    entry = entry,
                                    settings = settings,
                                    onClick = { onPlanetSelected(planetName) },
                                    externalRippleSignal = planetRippleSignals[planetName] ?: 0,
                                    updateCount = updateCountsByKey[planetName] ?: 0,
                                    modifier = Modifier
                                        .padding(
                                            horizontal = spacing.lg,
                                            vertical = spacing.xs / 2,
                                        )
                                        .onGloballyPositioned { coords ->
                                            planetCardYs[planetName] = coords.positionInParent().y.toInt()
                                        },
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(spacing.xxxl))

                    // COMPARE section — exoplanet star beside Sol, scaled
                    // by their real radii. Mirrors the planet-page compare
                    // section but with no chevrons and the analogue fixed
                    // to Sol.
                    Column(modifier = Modifier.padding(horizontal = spacing.xxxl)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(colors.divider),
                        )
                        Spacer(Modifier.height(spacing.lg))
                        BasicText(
                            text = "COMPARE",
                            style = type.labelSmall.copy(color = colors.textTertiary),
                        )
                    }
                    Spacer(Modifier.height(spacing.md))
                    // effectiveRadiusSolar() is non-null for pulsars even
                    // without a catalog radius (canonical ~12 km), so the
                    // COMPARE section renders for them rather than showing
                    // "Radius unknown".
                    if (detail.star.effectiveRadiusSolar() != null) {
                        // Build the cycle: primary first, then every
                        // companion with a known radius. Companions
                        // missing a radius are silently skipped — they
                        // can't render a sized disc, but the primary
                        // still works. In a single-star system this is
                        // a one-element list and the chevrons are hidden.
                        val compareStars = remember(
                            detail.star.hostname,
                            settings.useProperNames,
                            detail.companionStars,
                        ) {
                            buildList {
                                add(CompareStar(detail.star, starDisplayName))
                                for (companion in detail.companionStars) {
                                    if (companion.effectiveRadiusSolar() == null) continue
                                    val proper = ProperNames.forStar(companion.hostname)
                                    val name = if (settings.useProperNames && proper != null) {
                                        proper
                                    } else {
                                        companion.hostname
                                    }
                                    add(CompareStar(companion, name))
                                }
                            }
                        }
                        StarCompareSection(stars = compareStars)
                    } else {
                        BasicText(
                            text = "Radius unknown",
                            style = type.bodyMedium.copy(
                                color = colors.textTertiary,
                                textAlign = TextAlign.Center,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = spacing.xxxl, vertical = spacing.lg),
                        )
                    }
                    Spacer(Modifier.height(spacing.xxxl))

                    Spacer(Modifier.height(spacing.xxxl + LocalBottomBarHeight.current))
                }
            }
        }

        // Swipe-down trigger on globe area — drives dragState directly.
        // The surrounding scroll view uses nestedScroll for the same effect
        // when the finger lands on content instead of the globe. Disabled
        // while the commit animation is running (displayMode != globeMode).
        if (globeMode == GlobeMode.HALF && displayMode == GlobeMode.HALF &&
            scrollState.value < with(density) { 5.dp.roundToPx() }) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(STAR_GLOBE_HALF_HEIGHT)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = { dragState.onHalfGlobeDragStart() },
                            onDragEnd = { dragState.onHalfGlobeRelease() },
                            onDragCancel = { dragState.onHalfGlobeRelease() },
                            onVerticalDrag = { _, dragAmount ->
                                dragState.onHalfGlobeDrag(dragAmount)
                            },
                        )
                    },
            )
        }

        // Overlay back button — pinned at spacing.xxxl over the globe,
        // then scrolls with content once the content catches up. Commit
        // animations drive backButtonOffsetPx (slide up off-screen on
        // HALF→FULL, down from the top on FULL→HALF).
        val backStarInteraction = remember { MutableInteractionSource() }
        Row(
            modifier = Modifier
                .offset {
                    val baseY = kotlin.math.min(
                        backFixedPx,
                        backNaturalPx - scrollState.value.toFloat(),
                    )
                    IntOffset(0, (baseY + backButtonOffsetPx.value).roundToInt())
                }
                .padding(horizontal = spacing.lg)
                .pushOnPress(backStarInteraction)
                .clickable(
                    enabled = globeMode == GlobeMode.HALF,
                    indication = null,
                    interactionSource = backStarInteraction,
                    onClick = onBack,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackChevron(modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(spacing.sm))
            BasicText(
                text = "Back",
                style = type.bodyMedium.copy(color = colors.accentGold),
            )
        }

        // Fullscreen overlay — swipe-up exit prompt at the bottom. Mounted
        // in FULL mode and during commit animations (commitAlpha crossfade).
        if (displayMode == GlobeMode.FULL || commitAlpha.value > 0f) {
            val promptColor = colors.textTertiary

            // Rising-page illusion: a solid page-background slab sits offscreen
            // below at rest and slides up 1:1 with the bottom text bar, so its
            // top edge tracks the bar's bottom edge. Pulled up 1px to overlap
            // the bar's bottom and hide any sub-pixel seam between the slab's
            // float translation and the bar's IntOffset-rounded position. The
            // HALF-mode preview header is nested inside so it inherits the
            // slab's translation and rides up from the bottom of the screen
            // with the rising page rather than floating at the HALF absolute
            // position during the swipe.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // Coerce to <= 0: slab only rises in response to
                        // FULL-mode upward drag (negative offset), never
                        // pushed down by HALF-mode downward-drag momentum.
                        translationY = size.height +
                            dragState.offsetPx.coerceAtMost(0f) - 1.dp.toPx()
                        alpha = commitAlpha.value
                    }
                    .background(colors.background),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = spacing.xxxl,
                            end = spacing.xxxl,
                            top = spacing.lg,
                        )
                        .graphicsLayer { alpha = dragState.fullExitProgress },
                ) {
                    BasicText(
                        text = selectedDisplayName,
                        style = type.displayLarge.copy(color = colors.textPrimary),
                    )
                    if (selectedAltName != null) {
                        BasicText(
                            text = selectedAltName,
                            style = type.bodyMedium.copy(color = colors.textMuted),
                        )
                    }
                    val designations = listOfNotNull(
                        selectedStar.hdName,
                        selectedStar.hipName,
                        selectedStar.ticId,
                    ).filter { it != selectedStar.hostname }
                    if (designations.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        BasicText(
                            text = designations.joinToString(" · "),
                            style = type.labelSmall.copy(color = colors.textTertiary),
                        )
                    }
                }
            }

            // Half-mode gradient riding just above the slab's top edge. Appears
            // as the slab starts rising during swipe-up. Mounted between slab
            // and FULL bar so it sits behind the FULL-mode scrim in z-order —
            // as the scrim fades with the text, this gradient emerges.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(32.dp)
                    .graphicsLayer {
                        // Natural bottom = size.height. Want bottom at slab
                        // top = size.height + offsetPx - 1.dp. ΔY = offsetPx - 1.dp.
                        translationY = dragState.offsetPx.coerceAtMost(0f) - 1.dp.toPx()
                        alpha = commitAlpha.value
                    }
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, colors.background),
                        ),
                    ),
            )

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    // Coerce to <= 0: FULL bar only tracks FULL-mode upward
                    // swipe; HALF-mode downward-drag momentum zeroing through
                    // the commit must not push the bar down and back up.
                    .offset {
                        IntOffset(
                            0,
                            dragState.offsetPx.coerceAtMost(0f).roundToInt(),
                        )
                    }
                    .graphicsLayer {
                        alpha = commitAlpha.value
                        translationY = (1f - commitAlpha.value) * size.height
                    }
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = { dragState.onFullBottomDragStart() },
                            onDragEnd = { dragState.onFullBottomRelease() },
                            onDragCancel = { dragState.onFullBottomRelease() },
                            onVerticalDrag = { _, dragAmount ->
                                dragState.onFullBottomDrag(dragAmount)
                            },
                        )
                    },
            ) {
                // Full-mode scrim — darkens the globe behind the FULL-mode text
                // for readability. Fades out as the user swipes up, revealing
                // the half-mode gradient rendered above the rising slab behind.
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = 1f - dragState.fullExitProgress }
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)),
                            ),
                        ),
                )

                // Full-mode bar text — fades out with the scrim
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = spacing.xxxl,
                            end = spacing.xxxl,
                            bottom = spacing.xxxl,
                            top = 64.dp,
                        )
                        .graphicsLayer { alpha = 1f - dragState.fullExitProgress },
                ) {
                    BasicText(
                        text = selectedDisplayName,
                        style = type.displayLarge.copy(color = colors.textPrimary),
                    )
                    if (selectedAltName != null) {
                        Spacer(Modifier.height(2.dp))
                        BasicText(
                            text = selectedAltName,
                            style = type.bodyMedium.copy(color = colors.textMuted),
                        )
                    }
                    Spacer(Modifier.height(spacing.lg))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .drawBehind {
                                    val sw = 1.dp.toPx()
                                    val cy = size.height * 0.55f
                                    val left = size.width * 0.2f
                                    val right = size.width * 0.8f
                                    val top = size.height * 0.25f
                                    drawLine(promptColor, Offset(left, cy), Offset(size.width / 2f, top), sw, cap = StrokeCap.Round)
                                    drawLine(promptColor, Offset(size.width / 2f, top), Offset(right, cy), sw, cap = StrokeCap.Round)
                                },
                        )
                        Spacer(Modifier.width(spacing.xs))
                        BasicText(
                            text = "Swipe up to return",
                            style = type.labelLarge.copy(color = promptColor),
                        )
                    }
                }

            }
        }
    }
}

/**
 * Star name header used by both the primary and companion stars on the
 * star-detail page. In single-star systems (or when [multiStar] is false)
 * it renders as static text in the standard `textPrimary` colour. In
 * multi-star systems each star's header becomes a tappable selector:
 *
 * - The currently selected star uses `textPrimary` (full brightness)
 *   and a `pushOnPress` no-op tap (re-selecting the active one is a
 *   no-op for the parent, but the feedback animation still plays so
 *   the surface always feels responsive).
 * - Non-selected stars use `textSecondary` (dimmer) and route their
 *   tap to [onTap], which the parent uses to swap the globe view.
 *
 * Active / inactive name colour interpolates over 280 ms so the
 * brightness change tracks the globe's fade-out → swap → fade-in
 * sequence (220 ms out + 320 ms in in [SystemScreen]) — they read as
 * a single coordinated transition. Sub-line colours (alt name, catalog
 * designations) follow the same active/inactive split with the
 * existing muted / tertiary tones.
 */
@Composable
private fun StarNameHeader(
    star: Star,
    settings: UserSettings,
    isSelected: Boolean,
    multiStar: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type

    val properName = ProperNames.forStar(star.hostname)
    val displayName = if (settings.useProperNames && properName != null) properName else star.hostname
    val altName = if (settings.useProperNames && properName != null) star.hostname else properName
    val designations = listOfNotNull(star.hdName, star.hipName, star.ticId)
        .filter { it != star.hostname }

    val colorSpec = tween<Color>(280, easing = FastOutSlowInEasing)
    val active = isSelected || !multiStar
    val nameColor by animateColorAsState(
        targetValue = if (active) colors.textPrimary else colors.textSecondary,
        animationSpec = colorSpec,
        label = "starNameHeaderName",
    )
    val altColor by animateColorAsState(
        targetValue = if (active) colors.textMuted else colors.textTertiary,
        animationSpec = colorSpec,
        label = "starNameHeaderAlt",
    )
    val designationColor by animateColorAsState(
        targetValue = if (active) colors.textTertiary else colors.textMuted,
        animationSpec = colorSpec,
        label = "starNameHeaderDesig",
    )

    val pressSource = remember(star.hostname) { MutableInteractionSource() }
    val tapModifier = if (multiStar) {
        Modifier
            .pushOnPress(pressSource)
            .clickable(
                indication = null,
                interactionSource = pressSource,
                onClick = onTap,
            )
    } else {
        Modifier
    }

    Column(modifier = modifier.then(tapModifier)) {
        BasicText(
            text = displayName,
            style = type.displayLarge.copy(color = nameColor),
        )
        if (altName != null) {
            BasicText(
                text = altName,
                style = type.bodyMedium.copy(color = altColor),
            )
        }
        if (designations.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            BasicText(
                text = designations.joinToString(" · "),
                style = type.labelSmall.copy(color = designationColor),
            )
        }
    }
}

@Composable
private fun BackChevron(modifier: Modifier = Modifier) {
    val colors = ExoTheme.colors
    Box(
        modifier = modifier.drawBehind {
            val sw = 1.2.dp.toPx()
            val color = colors.accentGold
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
private fun StarPropertiesSection(
    star: Star,
    settings: UserSettings,
    modifier: Modifier = Modifier,
) {
    val spectralColor = TeffColor.forStar(star.teffK, star.effectiveSpectralType())

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            PropRow("Spectral type", formatStarSpectral(star, settings.useEstimates), spectralColor)
            PropRow("Teff", formatStarTeff(star, settings))
            PropRow("Distance", formatDistance(star.distancePc, settings))
            PropRow("RA", star.rightAscensionDeg?.let { "%.4f°".format(it) })
        }
        Column(modifier = Modifier.weight(1f)) {
            PropRow("Mass", star.massSolar?.let { "${limitPrefix(star.massSolarLimit)}%.3f M☉".format(it) })
            PropRow("Radius", star.radiusSolar?.let { "${limitPrefix(star.radiusSolarLimit)}%.3f R☉".format(it) })
            PropRow("Luminosity", star.logLuminosity?.let { "${limitPrefix(star.logLuminosityLimit)}10^%.2f L☉".format(it) })
            PropRow("Dec", star.declinationDeg?.let { "%.4f°".format(it) })
        }
    }
}

@Composable
private fun PropRow(
    label: String,
    value: String?,
    valueColor: Color? = null,
) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type
    val isEstimated = value?.startsWith("~") == true
    val displayValue = if (isEstimated) value?.removePrefix("~") else value

    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = "$label: ",
            style = type.labelLarge.copy(color = colors.textMuted),
        )
        BasicText(
            text = AstroFormat.astroAnnotated(displayValue ?: "—"),
            style = type.bodyMedium.copy(
                color = when {
                    value == null -> colors.textTertiary
                    valueColor != null -> valueColor
                    else -> colors.textSecondary
                },
                fontStyle = if (isEstimated) FontStyle.Italic else FontStyle.Normal,
            ),
        )
    }
}

// --- Planet summary card (no expand, navigates to planet page) ---

@Composable
private fun SystemPlanetCard(
    entry: SystemPlanetEntry,
    settings: UserSettings,
    onClick: () -> Unit,
    externalRippleSignal: Int = 0,
    /** Number of bookmark-vs-current parameter changes for this planet,
     *  or 0 if not bookmarked / no changes. Drives the same gold left
     *  edge + "n updated" indicator as the catalog card. */
    updateCount: Int = 0,
    modifier: Modifier = Modifier,
) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type
    val spacing = ExoTheme.spacing
    val cardShape = RoundedCornerShape(4.dp)
    val planet = entry.planet
    val classColor = entry.classification.compositionClass.toColor()
    val properName = ProperNames.forPlanet(planet.name)
    val displayName = if (settings.useProperNames && properName != null) properName else planet.name
    val altName = if (settings.useProperNames && properName != null) planet.name else properName

    val isAccessible = ExoTheme.isAccessible
    val thumbnailSize = if (isAccessible) 56.dp else 52.dp
    val iconProfile = remember(planet.name) {
        IconProfileBuilder.build(entry.visualProfile, entry.classification)
    }

    val hasUpdates = updateCount > 0
    val goldEdgeColor = colors.accentGold
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(colors.surfaceCard)
            .programmaticRipple(
                signal = externalRippleSignal,
                color = Color.White,
                startAlpha = 0.10f,
            )
            .touchRipple(
                color = Color.White,
                startAlpha = 0.10f,
                onClick = onClick,
            )
            .border(1.dp, colors.surfaceBorderHalf, cardShape)
            .drawBehind {
                // Thin gold left edge — same glance signal as the catalog
                // card. Painted between border and content so it lands on
                // top of the rounded-corner clipping.
                if (hasUpdates) {
                    drawRect(
                        color = goldEdgeColor,
                        topLeft = androidx.compose.ui.geometry.Offset.Zero,
                        size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height),
                    )
                }
            }
            .padding(vertical = spacing.lg, horizontal = spacing.xl),
    ) {
        // Top section: thumbnail + name/badge
        Row(verticalAlignment = if (isAccessible) Alignment.CenterVertically else Alignment.Top) {
            PlanetIcon(profile = iconProfile, size = thumbnailSize)

            Spacer(Modifier.width(if (ExoTheme.isAccessible) 18.dp else 14.dp))

            if (isAccessible) {
                // Accessible: name + badge stacked
                Column {
                    BasicText(
                        text = displayName,
                        style = type.titleMedium.copy(color = colors.textPrimary),
                    )
                    if (altName != null) {
                        BasicText(
                            text = altName,
                            style = type.labelSmall.copy(color = colors.textMuted),
                        )
                    }
                    Spacer(Modifier.height(spacing.xs))
                    ClassBadge(
                        label = formatClassLabel(entry.classification.fullLabel, settings),
                        color = classColor,
                    )
                    // "n updated" sits BELOW the class badge — same vertical
                    // ordering as the catalog card (where it lives in the
                    // subtitle row beneath the name + badge row).
                    if (hasUpdates) {
                        Spacer(Modifier.height(2.dp))
                        BasicText(
                            text = "$updateCount updated",
                            style = type.labelLarge.copy(color = colors.accentGold),
                        )
                    }
                }
            } else {
                // Standard: name + badge side by side
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            BasicText(
                                text = displayName,
                                style = type.titleMedium.copy(color = colors.textPrimary),
                            )
                            if (altName != null) {
                                BasicText(
                                    text = altName,
                                    style = type.labelSmall.copy(color = colors.textMuted),
                                )
                            }
                        }
                        Spacer(Modifier.width(spacing.sm))
                        ClassBadge(
                            label = formatClassLabel(entry.classification.fullLabel, settings),
                            color = classColor,
                        )
                    }
                    // "n updated" sits BELOW the class badge — right-aligned
                    // to land directly under the badge, mirroring the
                    // catalog card's subtitle-row position.
                    if (hasUpdates) {
                        Spacer(Modifier.height(2.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            BasicText(
                                text = "$updateCount updated",
                                style = type.labelLarge.copy(color = colors.accentGold),
                            )
                        }
                    }
                }
            }
        }

        // Summary stats — full width in both modes
        Spacer(Modifier.height(spacing.md))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            PlanetStat("MASS", formatMass(planet, entry.classification.compositionClass, settings))
            PlanetStat("RADIUS", formatRadius(planet, entry.classification.compositionClass, settings))
            PlanetStat("TEMP", formatTemp(planet, entry.classification, settings.temperatureUnit, settings.useEstimates))
            PlanetStat("PERIOD", formatPeriod(planet, entry.classification, settings.useEstimates))
        }
    }
}

@Composable
private fun ClassBadge(
    label: String,
    color: ClassificationColor,
) {
    val type = ExoTheme.type
    val shape = RoundedCornerShape(4.dp)

    Box(
        modifier = Modifier
            .clip(shape)
            .background(color.background)
            .border(1.dp, color.border, shape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        BasicText(
            text = label,
            style = type.labelSmall.copy(color = color.text),
        )
    }
}

@Composable
private fun PlanetStat(label: String, value: String?) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type
    val isEstimated = value?.startsWith("~") == true
    val displayValue = if (isEstimated) value?.removePrefix("~") else value

    Column {
        BasicText(
            text = label,
            style = type.labelLarge.copy(color = colors.textMuted),
        )
        Spacer(Modifier.height(2.dp))
        BasicText(
            text = AstroFormat.astroAnnotated(displayValue ?: "—"),
            style = type.bodyMedium.copy(
                color = if (value != null) colors.textSecondary else colors.textTertiary,
                fontStyle = if (isEstimated) FontStyle.Italic else FontStyle.Normal,
            ),
        )
    }
}

// --- Formatting helpers ---

private fun formatStarSpectral(star: Star, useEstimates: Boolean = true): String? {
    star.spectralType?.let {
        return it.trim()
            .replace("&plusmn;", "±")
            .replace("&amp;", "&")
    }
    if (star.isPulsar()) return "Q"
    if (useEstimates) {
        star.teffK?.let {
            return when {
                it >= 30000 -> "~O"
                it >= 10000 -> "~B"
                it >= 7500 -> "~A"
                it >= 6000 -> "~F"
                it >= 5200 -> "~G"
                it >= 3700 -> "~K"
                it >= 2400 -> "~M"
                it >= 1300 -> "~L"
                it >= 300 -> "~T"
                else -> "~Y"
            }
        }
    }
    return null
}

private fun formatStarTeff(star: Star, settings: UserSettings): String? =
    star.teffK?.let { k ->
        val value = when (settings.starTemperatureUnit) {
            TemperatureUnit.KELVIN -> k
            TemperatureUnit.CELSIUS -> k - 273.15
            TemperatureUnit.FAHRENHEIT -> (k - 273.15) * 9.0 / 5.0 + 32.0
        }
        val unit = when (settings.starTemperatureUnit) {
            TemperatureUnit.KELVIN -> "K"
            TemperatureUnit.CELSIUS -> "°C"
            TemperatureUnit.FAHRENHEIT -> "°F"
        }
        val rounded = "%.0f".format(value).let { if (it == "-0") "0" else it }
        "${limitPrefix(star.teffKLimit)}$rounded $unit"
    }

private fun formatDistance(distancePc: Double?, settings: UserSettings): String? {
    if (distancePc == null) return null
    return when (settings.distanceUnit) {
        DistanceUnit.PARSECS -> "%.1f pc".format(distancePc)
        DistanceUnit.LIGHT_YEARS -> "%.1f ly".format(distancePc * 3.26156)
    }
}

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
    if (composition == CompositionClass.JUPITER) return "${prefix}%.2f R${AstroFormat.jupiter(settings)}".format(re / 11.21)
    return "${prefix}%.2f R${AstroFormat.earth(settings)}".format(re)
}

private fun formatTemp(
    planet: Planet,
    classification: PlanetClassification,
    unit: TemperatureUnit,
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

private fun formatClassLabel(fullLabel: String, settings: UserSettings): String {
    var result = fullLabel
    if (!settings.useTerra) result = result.replace("Terrestrial", "Earth", ignoreCase = true)
    if (!settings.useNeptune) result = result.replace("Neptune", "Ice Giant", ignoreCase = true)
    if (!settings.useJupiter) result = result.replace("Jupiter", "Gas Giant", ignoreCase = true)
    return result.uppercase()
}

private val MAX_STAR_COMPARE_SIZE = 80.dp

/** A star + its already-resolved display name (proper or hostname). */
private data class CompareStar(val star: Star, val name: String)

/**
 * COMPARE section on the star detail page. Renders the host star on the
 * left next to Sol on the right, scaled by `effectiveRadiusSolar`. In
 * multi-star (binary) systems the left column gains chevron buttons
 * that cycle through every star in [stars] — the user can flip the
 * comparison between the primary and each companion against Sol. The
 * cycling pattern matches the planet detail page's COMPARE section,
 * but with the switcher on the LEFT (since the comparable in the
 * star-page layout is on the left, not the right).
 *
 * In single-star systems the chevrons are hidden and the layout is
 * identical to the prior single-star rendering.
 */
@Composable
private fun StarCompareSection(stars: List<CompareStar>) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type
    val spacing = ExoTheme.spacing

    val canCycle = stars.size > 1
    // Selected host star index. Re-keyed on the star list so navigation
    // to a different system always starts on the primary.
    var selectedIndex by remember(stars) { mutableIntStateOf(0) }
    // Slide direction for the AnimatedContent transitions. +1 = up
    // chevron (new content enters from below); -1 = down chevron.
    var direction by remember(stars) { mutableIntStateOf(1) }

    val safeIndex = selectedIndex.coerceIn(0, stars.size - 1)
    val selected = stars[safeIndex]
    val selectedStar = selected.star

    // effectiveRadiusSolar() resolves pulsars without a catalog radius to
    // their physical ~12 km value (≈1.7×10⁻⁵ R☉). No visibility floor —
    // accuracy wins, so a pulsar next to Sol renders as a sub-pixel speck
    // and that's the correct relationship.
    val exoRadiusSolar = selectedStar.effectiveRadiusSolar()?.toFloat() ?: 1f
    val maxRadius = maxOf(exoRadiusSolar, 1f)
    // Animate both icon sizes — when the user cycles between primary and
    // companion, the relative scaling against Sol changes (different host
    // radii), so the icons need to grow/shrink smoothly rather than snap.
    val exoSize by animateDpAsState(
        targetValue = MAX_STAR_COMPARE_SIZE * (exoRadiusSolar / maxRadius),
        animationSpec = tween(320, easing = FastOutSlowInEasing),
        label = "exoStarSize",
    )
    val solSize by animateDpAsState(
        targetValue = MAX_STAR_COMPARE_SIZE * (1f / maxRadius),
        animationSpec = tween(320, easing = FastOutSlowInEasing),
        label = "solStarSize",
    )
    // Star discs + their glow extend 1.35× beyond the disc diameter, so
    // the icon row's vertical allocation must accommodate that without
    // clipping or pushing into the label below.
    val iconRowHeight = MAX_STAR_COMPARE_SIZE * 1.35f

    // Sol color from blackbody at 5778 K — matches how Sol is rendered
    // everywhere else in the app (star map marker, orbital starfield).
    val solColor = TeffColor.fromTeff(5778.0)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.xxxl),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Top,
    ) {
        // ── Host star column (left) — chevrons in multi-star systems ──
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f),
        ) {
            if (canCycle) {
                ChevronButton(up = true) {
                    direction = 1
                    selectedIndex = (safeIndex + 1) % stars.size
                }
                Spacer(Modifier.height(spacing.xs))
            }
            AnimatedContent(
                targetState = selected,
                modifier = Modifier
                    .height(iconRowHeight)
                    .fillMaxWidth()
                    .clipToBounds(),
                transitionSpec = {
                    (slideInVertically(tween(320, easing = FastOutSlowInEasing)) { h -> h * direction } +
                        fadeIn(tween(160, easing = FastOutSlowInEasing)) +
                        scaleIn(tween(320, easing = FastOutSlowInEasing), initialScale = 0.5f)) togetherWith
                        (slideOutVertically(tween(320, easing = FastOutSlowInEasing)) { h -> -h * direction } +
                            fadeOut(tween(160, easing = FastOutSlowInEasing)) +
                            scaleOut(tween(320, easing = FastOutSlowInEasing), targetScale = 0.5f))
                },
                contentAlignment = Alignment.Center,
                label = "hostStarIcon",
            ) { entry ->
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    val entryColor = TeffColor.forStar(entry.star.teffK, entry.star.effectiveSpectralType())
                        ?: TeffColor.fromTeff(5778.0)
                    StarDiscIcon(color = entryColor, size = exoSize)
                }
            }
            Spacer(Modifier.height(spacing.sm))
            AnimatedContent(
                targetState = selected,
                modifier = Modifier.fillMaxWidth(),
                transitionSpec = {
                    (slideInVertically(tween(280, easing = FastOutSlowInEasing)) { h -> h * direction } +
                        fadeIn(tween(140, easing = FastOutSlowInEasing))) togetherWith
                        (slideOutVertically(tween(280, easing = FastOutSlowInEasing)) { h -> -h * direction } +
                            fadeOut(tween(140, easing = FastOutSlowInEasing)))
                },
                contentAlignment = Alignment.Center,
                label = "hostStarLabel",
            ) { entry ->
                BasicText(
                    text = entry.name,
                    style = type.labelLarge.copy(
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (canCycle) {
                Spacer(Modifier.height(spacing.xs))
                ChevronButton(up = false) {
                    direction = -1
                    selectedIndex = (safeIndex - 1 + stars.size) % stars.size
                }
            }
        }

        // ── Sol column (right) — fixed analogue, no cycling ──
        // Invisible spacers match the left column's chevron + gap so both
        // icon boxes sit at the same vertical position in multi-star
        // systems. Single-star layout is unchanged.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f),
        ) {
            if (canCycle) Spacer(Modifier.height(32.dp + spacing.xs))
            Box(
                modifier = Modifier.height(iconRowHeight),
                contentAlignment = Alignment.Center,
            ) {
                StarDiscIcon(color = solColor, size = solSize)
            }
            Spacer(Modifier.height(spacing.sm))
            BasicText(
                text = "Sol",
                style = type.labelLarge.copy(
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                ),
            )
        }
    }
}

@Composable
private fun StarDiscIcon(color: Color, size: Dp) {
    // Disc + radial-gradient glow matching the star-rendering convention
    // used by SystemStripView (strip). Glow radius is 1.35× the disc radius;
    // the outer box is sized large enough to contain the glow without
    // clipping. The disc is rendered on top of the glow with full opacity.
    val containerSize = size * 1.35f
    Box(
        modifier = Modifier
            .size(containerSize)
            .drawBehind {
                val center = Offset(this.size.width / 2f, this.size.height / 2f)
                val discRadius = size.toPx() / 2f
                val glowRadius = discRadius * 1.35f
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.0f to color,
                            0.55f to color.copy(alpha = 0.8f),
                            0.75f to color.copy(alpha = 0.35f),
                            1.0f to Color.Transparent,
                        ),
                        center = center,
                        radius = glowRadius,
                    ),
                    radius = glowRadius,
                    center = center,
                )
                drawCircle(color = color, radius = discRadius, center = center)
            },
    )
}
