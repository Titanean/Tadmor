package com.tadmor.app.ui.system

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.coroutineScope
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.tadmor.app.ui.catalog.toColor
import com.tadmor.app.ui.components.ChevronButton
import com.tadmor.app.ui.components.DispositionBadge
import com.tadmor.app.ui.components.PlanetBookmarkToggle
import com.tadmor.app.ui.components.PlanetIcon
import com.tadmor.app.ui.components.pushOnPress
import com.tadmor.app.ui.components.touchRipple
import com.tadmor.app.ui.theme.ExoTheme
import com.tadmor.app.ui.theme.LocalBottomBarHeight
import com.tadmor.app.ui.util.AstroFormat
import com.tadmor.domain.classification.visual.IconProfileBuilder
import com.tadmor.domain.classification.visual.PlanetIconProfile
import com.tadmor.domain.classification.visual.SolarSystemProfiles
import com.tadmor.domain.classification.CompositionClass
import com.tadmor.domain.classification.PlanetClassification
import com.tadmor.domain.model.DistanceUnit
import com.tadmor.domain.model.Planet
import com.tadmor.domain.model.ProperNames
import com.tadmor.domain.model.Star
import com.tadmor.domain.model.isPulsar
import com.tadmor.domain.model.SystemPlanetEntry
import com.tadmor.domain.model.TemperatureUnit
import com.tadmor.domain.model.UserSettings
import com.tadmor.domain.model.limitPrefix

private val GLOBE_HALF_HEIGHT = 200.dp

@Composable
fun PlanetDetailContent(
    entry: SystemPlanetEntry,
    star: Star,
    settings: UserSettings,
    onBack: () -> Unit,
    companionStars: List<Star> = emptyList(),
    globeMode: GlobeMode = GlobeMode.HALF,
    onGlobeModeChange: (GlobeMode) -> Unit = {},
    globeParams: PlanetGlobeParams = PlanetGlobeParams(),
    isActive: Boolean = true,
    isBookmarked: Boolean = false,
    onBookmarkToggle: () -> Unit = {},
    diff: com.tadmor.domain.model.PlanetDiff? = null,
    modifier: Modifier = Modifier,
) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type
    val spacing = ExoTheme.spacing
    val planet = entry.planet
    val classColor = entry.classification.compositionClass.toColor()
    val properName = ProperNames.forPlanet(planet.name)
    val displayName = if (settings.useProperNames && properName != null) properName else planet.name
    val altName = if (settings.useProperNames && properName != null) planet.name else properName
    val starProperName = ProperNames.forStar(star.hostname)
    val starBackLabel = if (settings.useProperNames && starProperName != null) starProperName else star.hostname
    val isFull = globeMode == GlobeMode.FULL
    // Key on planet name so each planet gets its own scroll position.
    val scrollState = remember(planet.name) { ScrollState(0) }
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val dragState = rememberGlobeDragState(isFull, onGlobeModeChange)

    // Commit-animation state. The external [globeMode] flips instantly (drag
    // release past threshold OR HW back); this state trails it so both paths
    // run the same animation. `displayMode` gates mount/unmount, `commitAlpha`
    // crossfades the FULL overlay (scrim + gradient + text), `commitOffsetPx`
    // translates HALF content off-screen on entry / up from below on exit,
    // and `backButtonOffsetPx` slides the back button up on enter and down
    // from the top on exit.
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

    // Observe mode flips and run the commit animation. Both drag-release
    // (GlobeDragState.settle*Release calls onModeChange) and HW back (the
    // parent sets globeMode = HALF) funnel through here.
    LaunchedEffect(globeMode) {
        if (globeMode == displayMode) return@LaunchedEffect
        // Slide distance = viewport height, so HALF content travels exactly
        // off-screen over the full animation duration (previously 2000dp,
        // which completed the visible travel in the first 2–3 frames).
        val exitPx = with(density) { configuration.screenHeightDp.dp.toPx() }
        val durationMs = 320
        if (globeMode == GlobeMode.FULL) {
            // HALF → FULL: animate dragState.offsetPx to 0 in parallel with
            // the commit slide so the bottom bar (which follows
            // dragState.offsetPx) rides up smoothly instead of snapping at
            // the end. commitOffsetPx carries the HALF content off-screen
            // the rest of the way.
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
            // FULL → HALF: keep dragState.offsetPx alive (finger-release
            // value is typically negative from the upward drag) and animate
            // it to 0 in parallel. Bottom bar, fullExitProgress-driven
            // alphas, and rising-slab translation all trail that animation
            // smoothly instead of snapping when dragState resets.
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

    // Back button Y: pinned at spacing.lg over the globe, then scrolls with content.
    // Natural position = 60dp above the title (matching old layout: xxxl + xxl + lg).
    val backFixedPx = with(density) { spacing.lg.toPx() }
    val backNaturalPx = with(density) { (GLOBE_HALF_HEIGHT - spacing.xxxl - spacing.xxl).toPx() }

    // Globe is fully covered when content has scrolled past the globe area.
    // In HALF mode the globe occupies the top GLOBE_HALF_HEIGHT; once the
    // scroll offset exceeds that the opaque background covers it entirely.
    val globeHalfPx = with(density) { GLOBE_HALF_HEIGHT.roundToPx() }
    val isGlobeVisible = isFull || scrollState.value < globeHalfPx

    // FULL-view inspection toggles. Each one resets to its default whenever
    // the user leaves FULL mode so the next entry starts in a known state,
    // and is keyed on planet.name so cycling planets also resets. The
    // actual values animate over 400 ms (matching the orbital HZ and
    // starfield toggle fade) so flipping them on/off doesn't pop.
    //   • ambient defaults OFF — adds a flat lift to dark pixels.
    //   • atmosphere defaults ON — toggle hides Rayleigh/Mie/ozone scatter.
    //   • clouds defaults ON — toggle hides cloud + fog layers.
    var ambientLightOn by remember(planet.name) { mutableStateOf(false) }
    var atmosphereOn by remember(planet.name) { mutableStateOf(true) }
    var cloudsOn by remember(planet.name) { mutableStateOf(true) }
    LaunchedEffect(globeMode) {
        if (globeMode == GlobeMode.HALF) {
            ambientLightOn = false
            atmosphereOn = true
            cloudsOn = true
        }
    }
    val animSpec = tween<Float>(400, easing = FastOutSlowInEasing)
    val ambientLightAnim by animateFloatAsState(
        targetValue = if (ambientLightOn) 1f else 0f,
        animationSpec = animSpec,
        label = "ambientLight",
    )
    val atmosphereAnim by animateFloatAsState(
        targetValue = if (atmosphereOn) 1f else 0f,
        animationSpec = animSpec,
        label = "atmosphere",
    )
    val cloudsAnim by animateFloatAsState(
        targetValue = if (cloudsOn) 1f else 0f,
        animationSpec = animSpec,
        label = "clouds",
    )
    // Reveal animation. Held at 0 for 350 ms after entry to give the page
    // slide-in (~280–420 ms) time to settle and the GLSurfaceView's EGL
    // surface time to start producing frames; then ramps 0→1 over 600 ms.
    // Driven through `revealProgress` in the params so the renderer just
    // uploads the value as `uReveal` — Compose owns the timing relative to
    // the slide animation. Re-keyed on `planet.name` so navigating to a
    // different planet replays the fade-in.
    val revealAnim = remember(planet.name) { Animatable(0f) }
    LaunchedEffect(planet.name) {
        kotlinx.coroutines.delay(350)
        revealAnim.animateTo(
            targetValue = 1f,
            animationSpec = tween(600, easing = FastOutSlowInEasing),
        )
    }

    val effectiveGlobeParams = globeParams.copy(
        ambientLight = ambientLightAnim,
        atmosphereVisibility = atmosphereAnim,
        cloudsVisibility = cloudsAnim,
        revealProgress = revealAnim.value,
    )

    // Visibility gates for the toggle buttons themselves. `atmosphere
    // thickness > 0` was too lax — planets like Proxima b have a non-
    // exospheric but Triton-thin atmosphere whose scattering is
    // essentially invisible to the user, yet the engine still assigns
    // them a non-zero thickness and sparse clouds. Gating on the actual
    // pre-scaled scattering / cloud-opacity magnitudes matches what the
    // user can see:
    //   • Atmosphere — sum of Rayleigh + Mie scattering coefficients
    //     across all three colour channels. These are pre-scaled in
    //     PlanetGlobeParams by `1e-3 × densityMultiplier`, so the sum
    //     captures both the chemistry (base scattering rate) and the
    //     density (multiplier). A threshold of 0.003 corresponds
    //     roughly to the engine's own `surfacePressureBar >= 0.10` gate
    //     for a "real atmosphere" — anything thinner reads as airless
    //     in the renderer regardless of the assigned thickness.
    //   • Clouds — effective opacity = coverage × density (both [0, 1]).
    //     0.05 captures meaningful decks (Earth: 0.5 × 0.7 = 0.35,
    //     Venus: ~1 × ~0.85 = 0.85) while excluding sparse trace wisps
    //     (Proxima b-class: 0.05 × 0.3 = 0.015). Gas giants are also
    //     excluded since their "clouds" are baked into the surface
    //     texture and don't run through the cloud-compositing path.
    val atmosphericScattering = globeParams.rayleighR + globeParams.rayleighG + globeParams.rayleighB +
        globeParams.mieR + globeParams.mieG + globeParams.mieB
    // Gas giants always show the atmosphere toggle — even Jupiters with
    // thin engine-assigned atmospheres should be toggle-able, since by
    // definition a giant planet IS its atmosphere and users expect the
    // control to be present.
    val showAtmosphereToggle = globeParams.gasGiantBake != null ||
        (atmosphericScattering > 0.003f && globeParams.atmosphereThicknessKm > 0f)
    val showCloudsToggle = globeParams.cloudCoverage * globeParams.cloudDensity > 0.05f &&
        globeParams.gasGiantBake == null

    Box(modifier = modifier
        .fillMaxSize()
        .nestedScroll(dragState.nestedScrollConnection)
    ) {
        // Layer 0: Globe — always fullscreen. Camera target offset handles
        // HALF positioning. Parallax: while in HALF mode the globe view
        // translates upward at half the scroll rate, so the planet feels
        // "anchored deeper" than the surrounding text as the page scrolls.
        // The effect fades out via `commitAlpha` during the HALF→FULL
        // transition so the FULL-mode globe sits at its natural origin
        // regardless of how far the user had scrolled before swiping up.
        PlanetGlobeView(
            params = effectiveGlobeParams,
            globeMode = globeMode,
            onGlobeModeChange = onGlobeModeChange,
            isActive = isActive,
            isGlobeVisible = isGlobeVisible,
            invertControls = settings.invertCameraControls,
            modifier = Modifier.offset {
                val fade = 1f - commitAlpha.value
                IntOffset(0, (-scrollState.value / 5f * fade).roundToInt())
            },
        )

        // Layer 1: Scrollable content. Mounted in HALF mode and while the
        // commit animation is sliding the HALF content off-screen (or into
        // view from below). `displayMode` drives mount/unmount, so HALF stays
        // alive during HALF→FULL slide-off and gets re-mounted early during
        // FULL→HALF so it can rise from commitOffset = exitPx.
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
                // so the gradient overlaps the globe's lower edge
                Spacer(Modifier.height(GLOBE_HALF_HEIGHT - 32.dp))

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
                    // Planet name + badge (back button is a position-computed overlay)
                    Row(
                        modifier = Modifier.padding(horizontal = spacing.xxxl),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            BasicText(
                                text = displayName,
                                style = type.displayMedium.copy(color = colors.textPrimary),
                            )
                            if (altName != null) {
                                Spacer(Modifier.height(2.dp))
                                BasicText(
                                    text = altName,
                                    style = type.bodyMedium.copy(color = colors.textMuted),
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                            ) {
                                val badgeShape = RoundedCornerShape(4.dp)
                                Box(
                                    modifier = Modifier
                                        .clip(badgeShape)
                                        .background(classColor.background)
                                        .border(1.dp, classColor.border, badgeShape)
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                ) {
                                    BasicText(
                                        text = formatClassLabel(entry.classification.fullLabel, settings),
                                        style = type.labelSmall.copy(color = classColor.text),
                                    )
                                }
                                DispositionBadge(disposition = entry.disposition)
                            }
                        }
                        PlanetBookmarkToggle(
                            isBookmarked = isBookmarked,
                            planetKey = planet.name,
                            onClick = onBookmarkToggle,
                        )
                    }

                    Spacer(Modifier.height(spacing.xxxl))

                    // Diff overlay setup — when the user has a bookmark for
                    // this planet and any parameter has changed, project the
                    // diff into a label-keyed map for the property grids and
                    // surface a banner above the grids if the disposition
                    // itself changed.
                    val composition = entry.classification.compositionClass
                    val diffByLabel: Map<String, String?> = remember(diff, composition, settings) {
                        diff?.changes
                            ?.filter { it.field != com.tadmor.domain.model.PlanetField.DISPOSITION }
                            ?.associate { change ->
                                propertyLabelFor(change.field) to formatOldValue(change.field, change.oldValue, composition, settings)
                            } ?: emptyMap()
                    }
                    val dispositionChange = diff?.changes
                        ?.firstOrNull { it.field == com.tadmor.domain.model.PlanetField.DISPOSITION }
                    if (dispositionChange != null) {
                        DispositionChangeBanner(
                            oldDisposition = dispositionChange.oldValue as? String,
                            newDisposition = dispositionChange.newValue as? String,
                            modifier = Modifier.padding(horizontal = spacing.xxxl),
                        )
                        Spacer(Modifier.height(spacing.xxxl))
                    }

                    // --- Physical Properties ---
                    SectionLabel("PHYSICAL PROPERTIES")
                    Spacer(Modifier.height(spacing.md))
                    PropertyGrid(
                        modifier = Modifier.padding(horizontal = spacing.xxxl),
                        items = listOf(
                            "Mass" to formatMass(planet, entry.classification.compositionClass, settings),
                            "Radius" to formatRadius(planet, entry.classification.compositionClass, settings),
                            "Density" to planet.densityGCm3?.let { "${limitPrefix(planet.densityLimit)}%.2f g/cm³".format(it) },
                            "Temperature" to formatTemp(planet, entry.classification, settings.temperatureUnit, settings.useEstimates),
                            "Insolation" to formatInsolation(planet, entry.classification, settings),
                        ),
                        diffByLabel = diffByLabel,
                    )

                    Spacer(Modifier.height(spacing.xxxl))

                    // --- Orbital Parameters ---
                    SectionLabel("ORBITAL PARAMETERS")
                    Spacer(Modifier.height(spacing.md))
                    PropertyGrid(
                        modifier = Modifier.padding(horizontal = spacing.xxxl),
                        items = listOf(
                            "Period" to formatPeriod(planet, entry.classification, settings.useEstimates),
                            "Semi-major axis" to (planet.semiMajorAxisAU?.let { "${limitPrefix(planet.semiMajorAxisLimit)}%.4f AU".format(it) }
                                ?: if (settings.useEstimates) entry.classification.estimatedSemiMajorAxisAU?.let { "~%.4f AU".format(it) } else null),
                            "Eccentricity" to planet.eccentricity?.let { "${limitPrefix(planet.eccentricityLimit)}%.4f".format(it) },
                            "Inclination" to planet.inclination?.let { "${limitPrefix(planet.inclinationLimit)}%.2f°".format(it) },
                            "Long. of periapsis" to planet.longOfPeriapsis?.let { "${limitPrefix(planet.longOfPeriapsisLimit)}%.2f°".format(it) },
                        ),
                        diffByLabel = diffByLabel,
                    )

                    Spacer(Modifier.height(spacing.xxxl))

                    // --- Discovery ---
                    SectionLabel("DISCOVERY")
                    Spacer(Modifier.height(spacing.md))
                    PropertyGrid(
                        modifier = Modifier.padding(horizontal = spacing.xxxl),
                        items = listOf(
                            "Method" to planet.discoveryMethod,
                            "Year" to planet.discoveryYear?.toString(),
                        ),
                        diffByLabel = diffByLabel,
                    )

                    Spacer(Modifier.height(spacing.xxxl))

                    // --- Host Star ---
                    val isCircumbinary = planet.cbFlag && companionStars.isNotEmpty()
                    SectionLabel(if (isCircumbinary) "HOST STARS (CIRCUMBINARY)" else "HOST STAR")
                    Spacer(Modifier.height(spacing.md))
                    PropertyGrid(
                        modifier = Modifier.padding(horizontal = spacing.xxxl),
                        items = listOf(
                            "Name" to star.hostname,
                            "Spectral type" to formatStarSpectral(star, settings.useEstimates),
                            "Teff" to formatStarTeff(star, settings),
                            "Distance" to formatDistance(star.distancePc, settings),
                            "Mass" to star.massSolar?.let { "${limitPrefix(star.massSolarLimit)}%.3f M☉".format(it) },
                            "Radius" to star.radiusSolar?.let { "${limitPrefix(star.radiusSolarLimit)}%.3f R☉".format(it) },
                        ),
                    )

                    // Companion star(s) for circumbinary systems
                    if (isCircumbinary) {
                        for (companion in companionStars) {
                            Spacer(Modifier.height(spacing.lg))
                            PropertyGrid(
                                modifier = Modifier.padding(horizontal = spacing.xxxl),
                                items = listOf(
                                    "Name" to companion.hostname,
                                    "Spectral type" to formatStarSpectral(companion, settings.useEstimates),
                                    "Teff" to formatStarTeff(companion, settings),
                                    "Mass" to companion.massSolar?.let { "${limitPrefix(companion.massSolarLimit)}%.3f M☉".format(it) },
                                    "Radius" to companion.radiusSolar?.let { "${limitPrefix(companion.radiusSolarLimit)}%.3f R☉".format(it) },
                                ),
                            )
                        }
                    }

                    Spacer(Modifier.height(spacing.xxxl))

                    SectionLabel("COMPARE")
                    Spacer(Modifier.height(spacing.md))
                    val compareRadius = planet.radiusEarth?.toFloat()
                    if (compareRadius != null) {
                        PlanetCompareSection(
                            exoProfile = IconProfileBuilder.build(entry.visualProfile, entry.classification),
                            exoRadiusEarth = compareRadius,
                            exoName = displayName,
                            compositionClass = entry.classification.compositionClass,
                        )
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
                    .height(GLOBE_HALF_HEIGHT)
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
        // then scrolls with content once the content catches up. Stays static
        // during drag (ignores dragState.offsetPx); commit animations slide
        // it up off-screen on HALF→FULL and down from the top on FULL→HALF.
        val backPlanetInteraction = remember { MutableInteractionSource() }
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
                .pushOnPress(backPlanetInteraction)
                .clickable(
                    enabled = globeMode == GlobeMode.HALF,
                    indication = null,
                    interactionSource = backPlanetInteraction,
                    onClick = onBack,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .drawBehind {
                            val sw = 1.2.dp.toPx()
                            val chevronColor = colors.accentGold
                            val cx = size.width * 0.55f
                            val top = size.height * 0.2f
                            val bot = size.height * 0.8f
                            val left = size.width * 0.25f
                            drawLine(chevronColor, Offset(cx, top), Offset(left, size.height / 2f), sw, cap = StrokeCap.Round)
                            drawLine(chevronColor, Offset(left, size.height / 2f), Offset(cx, bot), sw, cap = StrokeCap.Round)
                        },
                )
            Spacer(Modifier.width(spacing.sm))
            BasicText(
                text = starBackLabel,
                style = type.bodyMedium.copy(color = colors.accentGold),
            )
        }

        // Fullscreen overlay — planet info and swipe-up exit prompt at the bottom.
        // Mounted in FULL mode and during commit animations (crossfade via
        // commitAlpha). displayMode drives unmount once the fade-out finishes.
        if (displayMode == GlobeMode.FULL || commitAlpha.value > 0f) {
            val promptColor = colors.textTertiary

            // Top-right toggles: flat ambient lighting + atmosphere +
            // clouds. Same square button style as the orbital screen's
            // HZ + starfield toggles. The whole Row fades with
            // `commitAlpha` so the toggles appear/disappear synchronously
            // with the FULL-mode transition. Atmosphere/cloud toggles
            // are conditionally shown based on what the planet actually
            // has — airless worlds skip the atmosphere button, and
            // surface-only worlds (incl. gas giants) skip the cloud
            // button.
            val isAccessible = ExoTheme.isAccessible
            val toggleSize = if (isAccessible) 44.dp else 36.dp
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = spacing.xxxl, top = 14.dp)
                    .graphicsLayer { alpha = commitAlpha.value },
            ) {
                if (showAtmosphereToggle) {
                    Box(
                        modifier = Modifier
                            .size(toggleSize)
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.surfaceCard.copy(alpha = 0.85f))
                            .touchRipple(
                                color = Color.White,
                                startAlpha = 0.22f,
                                onClick = { atmosphereOn = !atmosphereOn },
                            )
                            .border(1.dp, colors.surfaceBorder, RoundedCornerShape(4.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        AtmosphereToggleIcon(visible = atmosphereOn)
                    }
                }
                if (showCloudsToggle) {
                    Box(
                        modifier = Modifier
                            .size(toggleSize)
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.surfaceCard.copy(alpha = 0.85f))
                            .touchRipple(
                                color = Color.White,
                                startAlpha = 0.22f,
                                onClick = { cloudsOn = !cloudsOn },
                            )
                            .border(1.dp, colors.surfaceBorder, RoundedCornerShape(4.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CloudToggleIcon(visible = cloudsOn)
                    }
                }
                Box(
                    modifier = Modifier
                        .size(toggleSize)
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.surfaceCard.copy(alpha = 0.85f))
                        .touchRipple(
                            color = Color.White,
                            startAlpha = 0.22f,
                            onClick = { ambientLightOn = !ambientLightOn },
                        )
                        .border(1.dp, colors.surfaceBorder, RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    FlashlightToggleIcon(visible = ambientLightOn)
                }
            }

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
                        // Coerce to <= 0: the slab should only rise in response
                        // to FULL-mode upward drag (negative offset), never be
                        // pushed down by HALF-mode downward-drag momentum
                        // carried through the commit animation.
                        translationY = size.height +
                            dragState.offsetPx.coerceAtMost(0f) - 1.dp.toPx()
                        alpha = commitAlpha.value
                    }
                    .background(colors.background),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = spacing.xxxl,
                            end = spacing.xxxl,
                            top = spacing.lg,
                        )
                        .graphicsLayer { alpha = dragState.fullExitProgress },
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                    BasicText(
                        text = displayName,
                        style = type.displayMedium.copy(color = colors.textPrimary),
                    )
                    if (altName != null) {
                        Spacer(Modifier.height(2.dp))
                        BasicText(
                            text = altName,
                            style = type.bodyMedium.copy(color = colors.textMuted),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        val badgeShape = RoundedCornerShape(4.dp)
                        Box(
                            modifier = Modifier
                                .clip(badgeShape)
                                .background(classColor.background)
                                .border(1.dp, classColor.border, badgeShape)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            BasicText(
                                text = formatClassLabel(entry.classification.fullLabel, settings),
                                style = type.labelSmall.copy(color = classColor.text),
                            )
                        }
                        DispositionBadge(disposition = entry.disposition)
                    }
                    }
                    PlanetBookmarkToggle(
                        isBookmarked = isBookmarked,
                        planetKey = planet.name,
                        onClick = onBookmarkToggle,
                    )
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
                    // Coerce to <= 0: the FULL bar should only track FULL-mode
                    // upward swipe (negative offset); HALF-mode downward-drag
                    // momentum zeroing through the commit must not push the
                    // bar down and back up.
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

                // Full-mode bar text — planet name, alt name, swipe-up prompt.
                // Fades out together with the scrim.
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
                        text = displayName,
                        style = type.displayMedium.copy(color = colors.textPrimary),
                    )
                    if (altName != null) {
                        Spacer(Modifier.height(2.dp))
                        BasicText(
                            text = altName,
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

private val MAX_COMPARE_SIZE = 80.dp

@Composable
private fun PlanetCompareSection(
    exoProfile: PlanetIconProfile,
    exoRadiusEarth: Float?,
    exoName: String,
    compositionClass: CompositionClass,
) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type
    val spacing = ExoTheme.spacing

    var selectedSolar by remember(compositionClass) {
        mutableStateOf(SolarSystemProfiles.defaultFor(compositionClass))
    }
    // direction sign drives slide direction for the AnimatedContent transitions.
    // +1 = up chevron (new content slides up from below); -1 = down chevron
    // (new content slides down from above). Seeded to +1 so the initial
    // composition has a stable value if the user happens to cycle before
    // recomposition sees a tap.
    var direction by remember(compositionClass) { mutableIntStateOf(1) }
    val planets = SolarSystemProfiles.SolarPlanet.entries

    val exoRadius = exoRadiusEarth ?: 1.0f
    val maxRadius = maxOf(exoRadius, selectedSolar.radiusEarth)
    // Animate the exoplanet icon size: the dominant planet swaps when the
    // user cycles Solar System analogues, so the exoplanet shrinks/grows
    // relative to the analogue. Without animation this would snap.
    val exoSize by animateDpAsState(
        targetValue = MAX_COMPARE_SIZE * (exoRadius / maxRadius),
        animationSpec = tween(320, easing = FastOutSlowInEasing),
        label = "exoSize",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.xxxl),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Top,
    ) {
        // Exoplanet column — icon centered in the fixed-height region.
        // Invisible spacers match the solar column's chevron + gap so both
        // icon boxes sit at the same vertical position.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f),
        ) {
            Spacer(Modifier.height(32.dp + spacing.xs))
            Box(
                modifier = Modifier.height(MAX_COMPARE_SIZE),
                contentAlignment = Alignment.Center,
            ) {
                PlanetIcon(profile = exoProfile, size = exoSize)
            }
            Spacer(Modifier.height(spacing.sm))
            BasicText(
                text = exoName,
                style = type.labelLarge.copy(
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                ),
            )
        }

        // Solar System column — chevrons above/below, icon centered.
        // Icon + label both use AnimatedContent so the outgoing analogue
        // slides, fades, and scales down as it leaves the fixed 80dp box
        // while the incoming analogue slides, fades, and scales in. The
        // `direction` state picks which vertical edge they enter/exit on.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f),
        ) {
            ChevronButton(up = true) {
                direction = 1
                val idx = planets.indexOf(selectedSolar)
                selectedSolar = planets[(idx + 1) % planets.size]
            }
            Spacer(Modifier.height(spacing.xs))
            AnimatedContent(
                targetState = selectedSolar,
                modifier = Modifier
                    .height(MAX_COMPARE_SIZE)
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
                label = "solarIcon",
            ) { solar ->
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    PlanetIcon(
                        profile = solar.profile,
                        size = MAX_COMPARE_SIZE * (solar.radiusEarth / maxOf(exoRadius, solar.radiusEarth)),
                    )
                }
            }
            Spacer(Modifier.height(spacing.sm))
            // Fixed-width container so different label widths (Earth/Neptune/
            // Jupiter) don't introduce a horizontal size animation; the label
            // just slides vertically and fades.
            AnimatedContent(
                targetState = selectedSolar,
                modifier = Modifier.fillMaxWidth(),
                transitionSpec = {
                    (slideInVertically(tween(280, easing = FastOutSlowInEasing)) { h -> h * direction } +
                        fadeIn(tween(140, easing = FastOutSlowInEasing))) togetherWith
                        (slideOutVertically(tween(280, easing = FastOutSlowInEasing)) { h -> -h * direction } +
                            fadeOut(tween(140, easing = FastOutSlowInEasing)))
                },
                contentAlignment = Alignment.Center,
                label = "solarLabel",
            ) { solar ->
                BasicText(
                    text = solar.displayName,
                    style = type.labelLarge.copy(
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(spacing.xs))
            ChevronButton(up = false) {
                direction = -1
                val idx = planets.indexOf(selectedSolar)
                selectedSolar = planets[(idx - 1 + planets.size) % planets.size]
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type
    val spacing = ExoTheme.spacing

    Column(modifier = Modifier.padding(horizontal = spacing.xxxl)) {
        BasicText(
            text = text,
            style = type.labelSmall.copy(color = colors.textTertiary),
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.divider),
        )
    }
}

@Composable
private fun PropertyGrid(
    items: List<Pair<String, String?>>,
    modifier: Modifier = Modifier,
    diffByLabel: Map<String, String?> = emptyMap(),
) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type

    Column(modifier = modifier) {
        items.chunked(2).forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
            ) {
                row.forEach { (label, value) ->
                    val isEstimated = value?.startsWith("~") == true
                    val displayValue = if (isEstimated) value?.removePrefix("~") else value
                    val oldValue = diffByLabel[label]
                    val isDiffed = label in diffByLabel
                    Column(modifier = Modifier.weight(1f)) {
                        BasicText(
                            text = label,
                            style = type.labelLarge.copy(color = colors.textMuted),
                        )
                        Spacer(Modifier.height(2.dp))
                        if (isDiffed) {
                            // Old value (struck-through, dim) on top, new
                            // value (gold, with arrow prefix) below. Two-line
                            // stack reads "old → new" without overflowing
                            // a half-width column on long values.
                            BasicText(
                                text = oldValue ?: "—",
                                style = type.bodyLarge.copy(
                                    color = colors.textTertiary,
                                    textDecoration = TextDecoration.LineThrough,
                                ),
                            )
                            BasicText(
                                text = "→ ${AstroFormat.astroAnnotated(displayValue ?: "—")}",
                                style = type.bodyLarge.copy(color = colors.accentGold),
                            )
                        } else {
                            BasicText(
                                text = AstroFormat.astroAnnotated(displayValue ?: "—"),
                                style = type.bodyLarge.copy(
                                    color = if (value != null) colors.textPrimary else colors.textTertiary,
                                    fontStyle = if (isEstimated) FontStyle.Italic else FontStyle.Normal,
                                ),
                            )
                        }
                    }
                }
                // Pad if odd number
                if (row.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * Banner shown above the property grids when a bookmarked planet's
 * disposition has flipped between syncs (typical case: a TOI/KOI
 * candidate gets promoted to CONFIRMED, or an apparently-real signal
 * gets re-classified as a FALSE_POSITIVE). Disposition is too important
 * to bury in a property-grid row — it's the headline reason a user
 * would bookmark a candidate.
 */
@Composable
private fun DispositionChangeBanner(
    oldDisposition: String?,
    newDisposition: String?,
    modifier: Modifier = Modifier,
) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.accentGoldSubtle)
            .border(1.dp, colors.accentGoldBorder, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        BasicText(
            text = "Was: ${prettyDisposition(oldDisposition)}  →  Now: ${prettyDisposition(newDisposition)}",
            style = type.labelLarge.copy(color = colors.accentGold),
        )
    }
}

private fun prettyDisposition(raw: String?): String = when (raw) {
    "CONFIRMED" -> "Confirmed"
    "CANDIDATE" -> "Candidate"
    "FALSE_POSITIVE" -> "False positive"
    null -> "—"
    else -> raw
}

/**
 * Maps a [com.tadmor.domain.model.PlanetField] to the label used by
 * the property grids in this file. Most match the field's
 * `displayLabel`, but a few are abbreviated for column-width budget
 * (e.g. "Long. of periapsis" instead of "Longitude of periapsis").
 */
private fun propertyLabelFor(field: com.tadmor.domain.model.PlanetField): String = when (field) {
    com.tadmor.domain.model.PlanetField.MASS_EARTH -> "Mass"
    com.tadmor.domain.model.PlanetField.RADIUS_EARTH -> "Radius"
    com.tadmor.domain.model.PlanetField.DENSITY -> "Density"
    com.tadmor.domain.model.PlanetField.EQ_TEMP_K -> "Temperature"
    com.tadmor.domain.model.PlanetField.INSOLATION -> "Insolation"
    com.tadmor.domain.model.PlanetField.ORBITAL_PERIOD -> "Period"
    com.tadmor.domain.model.PlanetField.SEMI_MAJOR_AXIS -> "Semi-major axis"
    com.tadmor.domain.model.PlanetField.ECCENTRICITY -> "Eccentricity"
    com.tadmor.domain.model.PlanetField.INCLINATION -> "Inclination"
    com.tadmor.domain.model.PlanetField.LONG_OF_PERIAPSIS -> "Long. of periapsis"
    com.tadmor.domain.model.PlanetField.DISCOVERY_METHOD -> "Method"
    com.tadmor.domain.model.PlanetField.DISCOVERY_YEAR -> "Year"
    com.tadmor.domain.model.PlanetField.DISPOSITION -> "" // handled by banner
}

/**
 * Formats the bookmark's *old* (snapshot) value of a single field
 * matching the display style used for the current value in the
 * property grid above. Returns null when the value is null
 * (data was missing at bookmark time).
 */
private fun formatOldValue(
    field: com.tadmor.domain.model.PlanetField,
    value: Any?,
    composition: CompositionClass,
    settings: UserSettings,
): String? {
    if (value == null) return null
    return when (field) {
        com.tadmor.domain.model.PlanetField.MASS_EARTH -> {
            val mass = value as? Double ?: return null
            if (composition == CompositionClass.JUPITER) {
                "%.2f M${AstroFormat.jupiter(settings)}".format(mass / 317.8)
            } else {
                "%.2f M${AstroFormat.earth(settings)}".format(mass)
            }
        }
        com.tadmor.domain.model.PlanetField.RADIUS_EARTH -> {
            val rad = value as? Double ?: return null
            if (composition == CompositionClass.JUPITER) {
                "%.2f R${AstroFormat.jupiter(settings)}".format(rad / 11.21)
            } else {
                "%.2f R${AstroFormat.earth(settings)}".format(rad)
            }
        }
        com.tadmor.domain.model.PlanetField.DENSITY -> "%.2f g/cm³".format(value as Double)
        com.tadmor.domain.model.PlanetField.EQ_TEMP_K -> {
            val k = value as Double
            val v = when (settings.temperatureUnit) {
                TemperatureUnit.KELVIN -> k
                TemperatureUnit.CELSIUS -> k - 273.15
                TemperatureUnit.FAHRENHEIT -> (k - 273.15) * 9.0 / 5.0 + 32.0
            }
            "%.0f ${settings.temperatureUnit.suffix}".format(v)
        }
        com.tadmor.domain.model.PlanetField.INSOLATION -> "%.2f S⊕".format(value as Double)
        com.tadmor.domain.model.PlanetField.ORBITAL_PERIOD -> {
            val days = value as Double
            if (days > 365) "%.1f yr".format(days / 365.25) else "%.1f d".format(days)
        }
        com.tadmor.domain.model.PlanetField.SEMI_MAJOR_AXIS -> "%.4f AU".format(value as Double)
        com.tadmor.domain.model.PlanetField.ECCENTRICITY -> "%.4f".format(value as Double)
        com.tadmor.domain.model.PlanetField.INCLINATION -> "%.2f°".format(value as Double)
        com.tadmor.domain.model.PlanetField.LONG_OF_PERIAPSIS -> "%.2f°".format(value as Double)
        com.tadmor.domain.model.PlanetField.DISCOVERY_METHOD -> value as? String
        com.tadmor.domain.model.PlanetField.DISCOVERY_YEAR -> (value as? Int)?.toString()
        com.tadmor.domain.model.PlanetField.DISPOSITION -> null
    }
}

// --- Formatting helpers ---

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

private fun formatInsolation(planet: Planet, classification: PlanetClassification, settings: UserSettings): String? {
    planet.insolationFlux?.let { return "${limitPrefix(planet.insolationFluxLimit)}${formatInsolationValue(it, settings)}" }
    if (settings.useEstimates) classification.estimatedInsolation?.let { return "~${formatInsolationValue(it, settings)}" }
    return null
}

private fun formatInsolationValue(value: Double, settings: UserSettings): String {
    val abs = kotlin.math.abs(value)
    val e = AstroFormat.earth(settings)
    return when {
        abs >= 100 -> "%.0f S$e".format(value)
        abs >= 1 -> "%.1f S$e".format(value)
        abs >= 0.1 -> "%.2f S$e".format(value)
        abs >= 0.01 -> "%.3f S$e".format(value)
        else -> "%.4f S$e".format(value)
    }
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

private fun formatClassLabel(fullLabel: String, settings: UserSettings): String {
    var result = fullLabel
    if (!settings.useTerra) result = result.replace("Terrestrial", "Earth", ignoreCase = true)
    if (!settings.useNeptune) result = result.replace("Neptune", "Ice Giant", ignoreCase = true)
    if (!settings.useJupiter) result = result.replace("Jupiter", "Gas Giant", ignoreCase = true)
    return result.uppercase()
}

/**
 * Flashlight icon for the FULL-view ambient-light toggle. Body is drawn
 * as a single closed Path stroked once so the corners don't pile up
 * alpha at line junctions (the previous 6-drawLine version had visibly
 * darker patches where strokes overlapped at 50% alpha). The on/off
 * state is instant — same vocabulary as the orbital HZ + starfield
 * toggles: 50% alpha body when off, 100% alpha body + rays when on.
 */
@Composable
private fun FlashlightToggleIcon(visible: Boolean) {
    val tertiary = ExoTheme.colors.textTertiary
    val baseColor = if (visible) tertiary else tertiary.copy(alpha = 0.5f)
    val iconSize = if (ExoTheme.isAccessible) 28.dp else 24.dp
    Box(
        modifier = Modifier
            .size(iconSize)
            .drawBehind {
                val w = size.width
                val h = size.height
                val sw = 1.5.dp.toPx()
                // In ON mode, nudge the body slightly down-left so the
                // visual mass balances the rays extending toward the
                // upper-right corner. Subtle (≈4% of icon size).
                val cx = w * 0.5f + (if (visible) -w * 0.04f else 0f)
                val cy = h * 0.5f + (if (visible) h * 0.04f else 0f)

                // Local axes for the rotated flashlight: U along its
                // long axis (handle → bezel), V perpendicular.
                val angleRad = (-45.0) * (kotlin.math.PI / 180.0)
                val ux = kotlin.math.cos(angleRad).toFloat()
                val uy = kotlin.math.sin(angleRad).toFloat()
                val vx = -uy
                val vy = ux

                val handleU = -w * 0.30f
                val bezelU = w * 0.04f
                val lensU = w * 0.18f
                val handleHalf = w * 0.10f
                val lensHalf = w * 0.16f

                fun pt(u: Float, v: Float) = Offset(cx + u * ux + v * vx, cy + u * uy + v * vy)

                // Single closed path traced as a continuous outline so
                // the stroke applies uniformly — no double-painting at
                // corners.
                val flashlightPath = androidx.compose.ui.graphics.Path().apply {
                    val hb1 = pt(handleU, -handleHalf)
                    val hb2 = pt(handleU, handleHalf)
                    val br1 = pt(bezelU, -handleHalf)
                    val br2 = pt(bezelU, handleHalf)
                    val ln1 = pt(lensU, -lensHalf)
                    val ln2 = pt(lensU, lensHalf)
                    moveTo(hb1.x, hb1.y)
                    lineTo(br1.x, br1.y)
                    lineTo(ln1.x, ln1.y)
                    lineTo(ln2.x, ln2.y)
                    lineTo(br2.x, br2.y)
                    lineTo(hb2.x, hb2.y)
                    close()
                }
                drawPath(
                    path = flashlightPath,
                    color = baseColor,
                    style = Stroke(
                        width = sw,
                        cap = StrokeCap.Round,
                        join = androidx.compose.ui.graphics.StrokeJoin.Round,
                    ),
                )

                // Rays — three short segments from past the lens. Drawn
                // only when the toggle is on; instant on/off (no fade).
                if (visible) {
                    val rayBaseU = lensU + w * 0.04f
                    val rayLen = w * 0.28f
                    val sideOffset = w * 0.08f
                    drawLine(tertiary,
                        pt(rayBaseU, 0f),
                        pt(rayBaseU + rayLen, 0f),
                        sw, cap = StrokeCap.Round)
                    drawLine(tertiary,
                        pt(rayBaseU, -sideOffset),
                        pt(rayBaseU + rayLen * 0.85f, -sideOffset * 1.6f),
                        sw, cap = StrokeCap.Round)
                    drawLine(tertiary,
                        pt(rayBaseU, sideOffset),
                        pt(rayBaseU + rayLen * 0.85f, sideOffset * 1.6f),
                        sw, cap = StrokeCap.Round)
                }
            },
    )
}

/**
 * Atmosphere toggle icon — two parallel arcs representing a planet's
 * curved horizon (lower arc, surface) and the limb of its atmosphere
 * (upper arc). Centre of curvature sits inside the box at `cy = h*0.65`
 * so the arcs sit comfortably within the icon without spilling off the
 * sides. Same on/off vocabulary as the rest of the toggles: 50% alpha
 * + diagonal slash when off, 100% alpha + no slash when on.
 */
@Composable
private fun AtmosphereToggleIcon(visible: Boolean) {
    val tertiary = ExoTheme.colors.textTertiary
    val baseColor = if (visible) tertiary else tertiary.copy(alpha = 0.5f)
    val iconSize = if (ExoTheme.isAccessible) 28.dp else 24.dp
    Box(
        modifier = Modifier
            .size(iconSize)
            .drawBehind {
                val w = size.width
                val h = size.height
                val sw = 1.2.dp.toPx()
                val cx = w * 0.5f
                // Centre of curvature lower in the box so the arc pair
                // sits more vertically centred — atm top at ≈ h*0.35,
                // planet edges at ≈ h*0.65, so visual centre lands
                // around mid-icon.
                val cy = h * 0.85f
                val planetR = h * 0.40f
                val atmRadius = h * 0.50f
                // 210° → 330° (sweep 120°) is the top of each circle —
                // 270° is straight up. With these radii both arcs stay
                // within ±0.866 × R horizontally from cx.
                fun drawCircleArc(radius: Float) {
                    drawArc(
                        color = baseColor,
                        startAngle = 210f,
                        sweepAngle = 120f,
                        useCenter = false,
                        topLeft = Offset(cx - radius, cy - radius),
                        size = Size(radius * 2f, radius * 2f),
                        style = Stroke(width = sw, cap = StrokeCap.Round),
                    )
                }
                drawCircleArc(planetR)   // surface
                drawCircleArc(atmRadius) // atmosphere limb
                if (!visible) {
                    drawLine(
                        tertiary,
                        Offset(w * 0.08f, h * 0.92f),
                        Offset(w * 0.92f, h * 0.08f),
                        1.5.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            },
    )
}

/**
 * Cloud toggle icon — opaque three-bump silhouette with flat bottom.
 * Composed of three overlapping ovals (the bumps) plus a rect (the
 * flat base) into a single Path with non-zero winding so the union
 * fills as one shape. The base rect's top sits just above where the
 * bumps merge so the cloud reads as a low, settled cloud rather than
 * a chef hat. Same on/off vocabulary as the rest of the toggles.
 */
@Composable
private fun CloudToggleIcon(visible: Boolean) {
    val tertiary = ExoTheme.colors.textTertiary
    val baseColor = if (visible) tertiary else tertiary.copy(alpha = 0.5f)
    val iconSize = if (ExoTheme.isAccessible) 28.dp else 24.dp
    Box(
        modifier = Modifier
            .size(iconSize)
            .drawBehind {
                val w = size.width
                val h = size.height
                val bottom = h * 0.78f
                val cornerR = w * 0.14f
                val cloudPath = androidx.compose.ui.graphics.Path().apply {
                    // Three overlapping bumps — chunky, full-width.
                    // Bottoms aligned to `bottom` so they sit on the
                    // body's top edge cleanly.
                    addOval(androidx.compose.ui.geometry.Rect(
                        left = w * 0.04f, top = h * 0.44f,
                        right = w * 0.50f, bottom = bottom,
                    ))
                    addOval(androidx.compose.ui.geometry.Rect(
                        left = w * 0.22f, top = h * 0.28f,
                        right = w * 0.74f, bottom = bottom,
                    ))
                    addOval(androidx.compose.ui.geometry.Rect(
                        left = w * 0.50f, top = h * 0.42f,
                        right = w * 0.96f, bottom = bottom,
                    ))
                    // Body with rounded bottom corners (flat bottom +
                    // rounded bottom-left/right) — gives the cloud a
                    // chunky, settled silhouette like a stylized
                    // weather glyph.
                    addRoundRect(
                        androidx.compose.ui.geometry.RoundRect(
                            left = w * 0.04f,
                            top = h * 0.56f,
                            right = w * 0.96f,
                            bottom = bottom,
                            topLeftCornerRadius = androidx.compose.ui.geometry.CornerRadius.Zero,
                            topRightCornerRadius = androidx.compose.ui.geometry.CornerRadius.Zero,
                            bottomLeftCornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerR, cornerR),
                            bottomRightCornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerR, cornerR),
                        )
                    )
                }
                drawPath(cloudPath, baseColor)
                if (!visible) {
                    drawLine(
                        tertiary,
                        Offset(w * 0.08f, h * 0.92f),
                        Offset(w * 0.92f, h * 0.08f),
                        1.5.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            },
    )
}
