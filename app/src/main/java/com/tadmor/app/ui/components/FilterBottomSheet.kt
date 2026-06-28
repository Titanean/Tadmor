package com.tadmor.app.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.math.hypot
import com.tadmor.app.ui.theme.ExoColors
import com.tadmor.app.ui.theme.ExoTheme
import com.tadmor.app.ui.theme.LocalBottomBarHeight
import com.tadmor.app.ui.theme.TeffColor
import com.tadmor.domain.model.UserSettings
import kotlinx.coroutines.launch

/**
 * Filter state holding all active filter selections.
 */
data class FilterState(
    val compositions: Set<String> = emptySet(),
    val massPrefixes: Set<String> = emptySet(),
    val temperatures: Set<String> = emptySet(),
    val spectralClasses: Set<String> = emptySet(),
    val luminosityClasses: Set<String> = emptySet(),
    val discoveryMethods: Set<String> = emptySet(),
    val dataFields: Set<String> = emptySet(),
    val minPlanets: Int = 0,
) {
    val activeCount: Int
        get() = compositions.size + massPrefixes.size + temperatures.size +
            spectralClasses.size + luminosityClasses.size + discoveryMethods.size +
            dataFields.size + (if (minPlanets > 0) 1 else 0)

    val activeLabels: List<Pair<String, String>>
        get() = buildList {
            compositions.forEach { add("composition" to it) }
            massPrefixes.forEach { add("massPrefix" to it) }
            temperatures.forEach { add("temperature" to it) }
            spectralClasses.forEach { add("spectral" to it) }
            luminosityClasses.forEach { add("luminosity" to it) }
            discoveryMethods.forEach { add("discovery" to it) }
            dataFields.forEach { add("data" to it) }
            if (minPlanets > 0) add("minPlanets" to "$minPlanets+")
        }

    fun remove(group: String, value: String): FilterState = when (group) {
        "composition" -> copy(compositions = compositions - value)
        "massPrefix" -> copy(massPrefixes = massPrefixes - value)
        "temperature" -> copy(temperatures = temperatures - value)
        "spectral" -> copy(spectralClasses = spectralClasses - value)
        "luminosity" -> copy(luminosityClasses = luminosityClasses - value)
        "discovery" -> copy(discoveryMethods = discoveryMethods - value)
        "data" -> copy(dataFields = dataFields - value)
        "minPlanets" -> copy(minPlanets = 0)
        else -> this
    }

    fun clear(): FilterState = FilterState()
}

/**
 * Full-screen overlay filter bottom sheet per DESIGN.md Section 5.2.
 * Slides up from bottom with expandable filter groups.
 */
@Composable
fun FilterBottomSheet(
    filterState: FilterState,
    settings: UserSettings = UserSettings(),
    onFilterChange: (FilterState) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ExoTheme.colors
    val density = LocalDensity.current
    var sheetHeightPx by remember { mutableFloatStateOf(0f) }
    val dismissThresholdPx = with(density) { 150.dp.toPx() }
    // 0 = fully off-screen (bottom), 1 = fully open. Drives translation + scrim.
    val openness = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var isClosing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        openness.animateTo(1f, tween(280, easing = FastOutSlowInEasing))
    }

    fun requestClose() {
        if (isClosing) return
        isClosing = true
        scope.launch {
            openness.animateTo(0f, tween(240, easing = FastOutSlowInEasing))
            onDismiss()
        }
    }

    // Always enabled so the sheet captures back even mid-close animation
    // (requestClose is idempotent). Without this, back during close would
    // fall through to the host screen's BackHandler.
    BackHandler { requestClose() }

    val dragState = rememberDraggableState { delta ->
        if (sheetHeightPx <= 0f || isClosing) return@rememberDraggableState
        scope.launch {
            val deltaFrac = delta / sheetHeightPx
            openness.snapTo((openness.value - deltaFrac).coerceIn(0f, 1f))
        }
    }

    // Scrim
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(colors.background.copy(alpha = 0.7f * openness.value))
            }
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = { requestClose() },
            ),
    ) {
        // Sheet content
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .offset {
                    IntOffset(0, ((1f - openness.value) * sheetHeightPx).roundToInt())
                }
                .onSizeChanged { sheetHeightPx = it.height.toFloat() }
                .draggable(
                    state = dragState,
                    orientation = Orientation.Vertical,
                    onDragStopped = {
                        if (isClosing) return@draggable
                        val offsetPx = (1f - openness.value) * sheetHeightPx
                        if (offsetPx > dismissThresholdPx) {
                            requestClose()
                        } else {
                            scope.launch {
                                openness.animateTo(
                                    1f,
                                    tween(220, easing = FastOutSlowInEasing),
                                )
                            }
                        }
                    },
                )
                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                .background(colors.surfaceCard)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {}, // consume clicks so they don't dismiss
                ),
        ) {
            // Drag handle — outer Box widens the grab area (the scroll
            // region below eats vertical drags, so the handle padding is
            // the only reliable place to catch a drag).
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 18.dp, horizontal = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(32.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.divider),
                )
            }

            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = ExoTheme.spacing.xxxl)
                    .padding(bottom = ExoTheme.spacing.xxxl + LocalBottomBarHeight.current),
            ) {
                // Temperature group
                FilterGroup(
                    title = "TEMPERATURE",
                    options = listOf("Frigid", "Cold", "Cool", "Temperate", "Warm", "Hot", "Torrid"),
                    selected = filterState.temperatures,
                    onToggle = { value ->
                        val updated = if (value in filterState.temperatures) {
                            filterState.temperatures - value
                        } else {
                            filterState.temperatures + value
                        }
                        onFilterChange(filterState.copy(temperatures = updated))
                    },
                )

                Spacer(Modifier.height(ExoTheme.spacing.xxl))

                // Mass Prefix group
                FilterGroup(
                    title = "MASS PREFIX",
                    options = listOf("Mini", "Sub", "Standard", "Super", "Mega"),
                    selected = filterState.massPrefixes,
                    onToggle = { value ->
                        val updated = if (value in filterState.massPrefixes) {
                            filterState.massPrefixes - value
                        } else {
                            filterState.massPrefixes + value
                        }
                        onFilterChange(filterState.copy(massPrefixes = updated))
                    },
                )

                Spacer(Modifier.height(ExoTheme.spacing.xxl))

                // Planet Class group — display names follow user settings
                val terraLabel = if (settings.useTerra) "Terrestrial" else "Earth"
                val neptuneLabel = if (settings.useNeptune) "Neptune" else "Ice Giant"
                val jupiterLabel = if (settings.useJupiter) "Jupiter" else "Gas Giant"
                // Map display labels to canonical filter values
                val classDisplayToCanonical = mapOf(
                    terraLabel to "Terrestrial",
                    neptuneLabel to "Neptune",
                    jupiterLabel to "Jupiter",
                )
                val classCanonicalToDisplay = classDisplayToCanonical.entries
                    .associate { (display, canonical) -> canonical to display }
                // Convert selected canonical values to display labels for highlighting
                val selectedDisplay = filterState.compositions.mapNotNull {
                    classCanonicalToDisplay[it]
                }.toSet()

                FilterGroup(
                    title = "PLANET CLASS",
                    options = classDisplayToCanonical.keys.toList(),
                    selected = selectedDisplay,
                    onToggle = { displayLabel ->
                        val canonical = classDisplayToCanonical[displayLabel] ?: return@FilterGroup
                        val updated = if (canonical in filterState.compositions) {
                            filterState.compositions - canonical
                        } else {
                            filterState.compositions + canonical
                        }
                        onFilterChange(filterState.copy(compositions = updated))
                    },
                    labelColors = mapOf(
                        terraLabel to ExoColors.compositionTerra.text,
                        neptuneLabel to ExoColors.compositionNeptune.text,
                        jupiterLabel to ExoColors.compositionJupiter.text,
                    ),
                )

                Spacer(Modifier.height(ExoTheme.spacing.xxl))

                // Spectral Class group
                FilterGroup(
                    title = "SPECTRAL CLASS",
                    options = listOf("O", "B", "A", "F", "G", "K", "M", "L", "T", "Y", "Q", "D", "Other"),
                    selected = filterState.spectralClasses,
                    onToggle = { value ->
                        val updated = if (value in filterState.spectralClasses) {
                            filterState.spectralClasses - value
                        } else {
                            filterState.spectralClasses + value
                        }
                        onFilterChange(filterState.copy(spectralClasses = updated))
                    },
                    labelColors = mapOf(
                        "O" to TeffColor.fromSpectralClass("O"),
                        "B" to TeffColor.fromSpectralClass("B"),
                        "A" to TeffColor.fromSpectralClass("A"),
                        "F" to TeffColor.fromSpectralClass("F"),
                        "G" to TeffColor.fromSpectralClass("G"),
                        "K" to TeffColor.fromSpectralClass("K"),
                        "M" to TeffColor.fromSpectralClass("M"),
                        "L" to TeffColor.fromSpectralClass("L"),
                        "T" to TeffColor.fromSpectralClass("T"),
                        "Y" to TeffColor.fromSpectralClass("Y"),
                        "Q" to TeffColor.fromSpectralClass("Q"),
                        "D" to TeffColor.fromSpectralClass("D"),
                        "Other" to ExoTheme.colors.textMuted,
                    ),
                )

                Spacer(Modifier.height(ExoTheme.spacing.xxl))

                // Luminosity Class group
                FilterGroup(
                    title = "LUMINOSITY CLASS",
                    options = listOf("V", "IV", "III", "II", "I"),
                    selected = filterState.luminosityClasses,
                    onToggle = { value ->
                        val updated = if (value in filterState.luminosityClasses) {
                            filterState.luminosityClasses - value
                        } else {
                            filterState.luminosityClasses + value
                        }
                        onFilterChange(filterState.copy(luminosityClasses = updated))
                    },
                )

                Spacer(Modifier.height(ExoTheme.spacing.xxl))

                // Planet Count — minimum slider
                PlanetCountSlider(
                    minPlanets = filterState.minPlanets,
                    onValueChange = { value ->
                        onFilterChange(filterState.copy(minPlanets = value))
                    },
                )

                Spacer(Modifier.height(ExoTheme.spacing.xxl))

                // Discovery Method group
                FilterGroup(
                    title = "DISCOVERY METHOD",
                    options = listOf("Transit", "Radial Velocity", "Direct Imaging", "Microlensing", "Timing"),
                    selected = filterState.discoveryMethods,
                    onToggle = { value ->
                        val updated = if (value in filterState.discoveryMethods) {
                            filterState.discoveryMethods - value
                        } else {
                            filterState.discoveryMethods + value
                        }
                        onFilterChange(filterState.copy(discoveryMethods = updated))
                    },
                )

                Spacer(Modifier.height(ExoTheme.spacing.xxl))

                // Data Available group
                FilterGroup(
                    title = "DATA AVAILABLE",
                    options = listOf("Mass", "Radius", "Temperature", "Period", "Density", "Eccentricity"),
                    selected = filterState.dataFields,
                    onToggle = { value ->
                        val updated = if (value in filterState.dataFields) {
                            filterState.dataFields - value
                        } else {
                            filterState.dataFields + value
                        }
                        onFilterChange(filterState.copy(dataFields = updated))
                    },
                )

                Spacer(Modifier.height(ExoTheme.spacing.xxl))

                // Apply / close button
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.accentGold)
                        .touchRipple(
                            color = colors.background,
                            startAlpha = 0.28f,
                            onClick = { requestClose() },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        text = "Close",
                        style = ExoTheme.type.bodyLarge.copy(
                            color = colors.background,
                            fontWeight = FontWeight.W500,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * A single expandable filter group with a title and toggle chips.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FilterGroup(
    title: String,
    options: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    labelColors: Map<String, Color> = emptyMap(),
) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type
    var expanded by remember { mutableStateOf(true) }

    // Header row
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = { expanded = !expanded },
            )
            .padding(vertical = ExoTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = title,
            style = type.labelSmall.copy(color = colors.textTertiary),
        )
        Spacer(Modifier.weight(1f))
        // Chevron
        ChevronIcon(
            pointsDown = expanded,
            modifier = Modifier.size(12.dp),
        )
    }

    // Expandable chip area
    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(tween(200)),
        exit = shrinkVertically(tween(200)),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ExoTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(ExoTheme.spacing.sm),
            modifier = Modifier.padding(top = ExoTheme.spacing.xs),
        ) {
            options.forEach { option ->
                ToggleChip(
                    label = option,
                    isActive = option in selected,
                    onClick = { onToggle(option) },
                    inactiveTextColor = labelColors[option],
                )
            }
        }
    }
}

@Composable
internal fun ToggleChip(
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
    inactiveTextColor: Color? = null,
) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type
    val shape = RoundedCornerShape(20.dp)

    // Radial fill progress: 0 = no gold wash, 1 = entire pill filled. Drives
    // both the expanding-circle highlight on select and the collapsing one
    // on deselect. Animated so both directions read as the same motion in
    // reverse, not a snap.
    val fillProgress = remember { Animatable(if (isActive) 1f else 0f) }
    LaunchedEffect(isActive) {
        fillProgress.animateTo(
            if (isActive) 1f else 0f,
            tween(260, easing = FastOutSlowInEasing),
        )
    }

    // Text + border fade together with the fill so the whole pill reads as
    // one motion. Driven strictly by fillProgress (not by isActive) so on
    // deselect the gold text/border animate back down in lockstep with the
    // radial wash collapsing, rather than snapping at the isActive flip.
    val inactiveColor = inactiveTextColor ?: colors.textSecondary
    val textColor = lerpColor(inactiveColor, colors.accentGold, fillProgress.value)
    val borderColor = lerpColor(colors.divider, colors.accentGoldBorder, fillProgress.value)

    val isAccessible = ExoTheme.isAccessible
    val hPad = if (isAccessible) 18.dp else 14.dp
    val vPad = if (isAccessible) 10.dp else 6.dp

    val interactionSource = remember { MutableInteractionSource() }
    val fillColor = colors.accentGoldSubtle

    Box(
        modifier = Modifier
            .pushOnPress(interactionSource)
            .clip(shape)
            // Offscreen compositing layer so BlendMode.Src below can REPLACE
            // (rather than stack on top of) the surfaceRaised base where the
            // gold circle covers. Without this, goldSubtle (7% alpha) ends
            // up composited over surfaceRaised, producing a visibly brighter
            // highlight than the settings-row wipe — which draws goldSubtle
            // directly on the parent bg. This equalises the two.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawBehind {
                // Inactive base layer
                drawRoundRect(
                    color = colors.surfaceRaised,
                    cornerRadius = CornerRadius(size.height / 2f),
                )
                val p = fillProgress.value
                if (p > 0f) {
                    // Radial highlight grows from the pill's centre outward,
                    // reaching the far corner at p=1. Clipping to the pill
                    // shape above keeps the circle visually confined to the
                    // rounded rectangle. BlendMode.Src replaces the pixels
                    // inside the circle so the 7% gold composites against
                    // the layer's transparent bg (and ultimately the parent)
                    // rather than against surfaceRaised.
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val maxR = hypot(size.width / 2f, size.height / 2f)
                    drawCircle(
                        color = fillColor,
                        radius = maxR * p,
                        center = Offset(cx, cy),
                        blendMode = BlendMode.Src,
                    )
                }
            }
            .border(1.dp, borderColor, shape)
            .clickable(
                indication = null,
                interactionSource = interactionSource,
                onClick = onClick,
            )
            .padding(horizontal = hPad, vertical = vPad),
    ) {
        BasicText(
            text = label,
            style = type.labelLarge.copy(color = textColor),
        )
    }
}

/** Linear interpolation between two Colors in sRGB. */
private fun lerpColor(a: Color, b: Color, t: Float): Color {
    val clamped = t.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * clamped,
        green = a.green + (b.green - a.green) * clamped,
        blue = a.blue + (b.blue - a.blue) * clamped,
        alpha = a.alpha + (b.alpha - a.alpha) * clamped,
    )
}

/**
 * Step slider for minimum planet count (0 = any, 1–8).
 * Custom-drawn track + thumb + labels, no Material.
 * Supports drag, tap-on-dot, and live updates.
 */
@Composable
internal fun PlanetCountSlider(
    minPlanets: Int,
    onValueChange: (Int) -> Unit,
) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type
    val maxStep = 8

    // Header
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = ExoTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = "PLANET COUNT",
            style = type.labelSmall.copy(color = colors.textTertiary),
        )
        Spacer(Modifier.weight(1f))
        BasicText(
            text = if (minPlanets == 0) "Any" else "$minPlanets+",
            style = type.labelLarge.copy(
                color = if (minPlanets > 0) colors.accentGold else colors.textMuted,
            ),
        )
    }

    Spacer(Modifier.height(ExoTheme.spacing.xs))

    val density = LocalDensity.current
    var trackWidthPx by remember { mutableFloatStateOf(0f) }
    val thumbRadius = with(density) { 10.dp.toPx() }
    val trackHeight = with(density) { 3.dp.toPx() }
    val labelStyle = type.labelSmall
    val textMeasurer = rememberTextMeasurer()

    var dragPx by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var lastEmitted by remember { mutableStateOf(minPlanets) }

    // Thumb "pickup" scale — grows on touch-down, springs back on release.
    // 180 ms FastOutSlowInEasing matches the slider's tactile feel without
    // feeling sluggish. Scale factor 1.4 is enough to read as a clear
    // hand-on-the-thumb signal at both standard and accessible sizes.
    val thumbScale = remember { Animatable(1f) }
    LaunchedEffect(isDragging) {
        thumbScale.animateTo(
            if (isDragging) 1.4f else 1f,
            tween(180, easing = FastOutSlowInEasing),
        )
    }

    val isActive = minPlanets > 0
    val thumbColor = if (isActive) colors.accentGold else colors.textMuted
    val sliderHeight = 48.dp
    val padH = 10.dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(sliderHeight)
            .padding(horizontal = padH)
            .onSizeChanged { size ->
                trackWidthPx = size.width.toFloat()
                if (!isDragging) {
                    val trackRange = trackWidthPx - 2f * thumbRadius
                    dragPx = minPlanets.toFloat() / maxStep * trackRange + thumbRadius
                }
            }
            .pointerInput(Unit) {
                // One unified gesture handler so press, tap-to-snap, and
                // drag don't fight over the same pointer. See the matching
                // explanation in OrbitalScreen.TimeScaleSlider.
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
                                        onValueChange(tapped)
                                    }
                                } else if (trackWidthPx > 0f) {
                                    // Drag release → snap to nearest step
                                    val trackRange = trackWidthPx - 2f * thumbRadius
                                    val rel = ((dragPx - thumbRadius) / trackRange).coerceIn(0f, 1f)
                                    val snapped = (rel * maxStep + 0.5f).toInt().coerceIn(0, maxStep)
                                    dragPx = snapped.toFloat() / maxStep * trackRange + thumbRadius
                                    if (snapped != minPlanets) onValueChange(snapped)
                                }
                                break
                            }
                            if (!draggedPastSlop) {
                                val dx = change.position.x - down.position.x
                                val dy = change.position.y - down.position.y
                                if (dx * dx + dy * dy >= slop * slop) {
                                    draggedPastSlop = true
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
                                        onValueChange(snapped)
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
                val activeX = minPlanets.toFloat() / maxStep * trackRange + trackStartX

                drawRoundRect(
                    color = colors.surfaceRaised,
                    topLeft = Offset(trackStartX, trackCy - trackHeight / 2f),
                    size = Size(trackRange, trackHeight),
                    cornerRadius = CornerRadius(trackHeight / 2f),
                )

                if (isActive) {
                    drawRoundRect(
                        color = colors.accentGold,
                        topLeft = Offset(trackStartX, trackCy - trackHeight / 2f),
                        size = Size(activeX - trackStartX, trackHeight),
                        cornerRadius = CornerRadius(trackHeight / 2f),
                    )
                }

                for (i in 0..maxStep) {
                    val x = i.toFloat() / maxStep * trackRange + trackStartX
                    val dotColor = if (i <= minPlanets && isActive) colors.accentGold else colors.divider
                    drawCircle(dotColor, radius = 2.5f.dp.toPx(), center = Offset(x, trackCy))
                }

                val scaledThumbR = thumbRadius * thumbScale.value
                drawCircle(thumbColor, radius = scaledThumbR, center = Offset(activeX, trackCy))
                drawCircle(colors.surfaceCard, radius = scaledThumbR * 0.5f, center = Offset(activeX, trackCy))

                val labelTop = trackCy + thumbRadius + 4.dp.toPx()
                for (i in 0..maxStep) {
                    val x = i.toFloat() / maxStep * trackRange + trackStartX
                    val text = if (i == 0) "Any" else "$i"
                    val labelColor = when {
                        i == minPlanets && isActive -> colors.accentGold
                        i == minPlanets && !isActive -> colors.textMuted
                        else -> colors.textMuted
                    }
                    val textLayoutResult = labelStyle.let { style ->
                        textMeasurer.measure(
                            text = text,
                            style = style.copy(color = labelColor),
                        )
                    }
                    drawText(
                        textLayoutResult,
                        topLeft = Offset(
                            x - textLayoutResult.size.width / 2f,
                            labelTop,
                        ),
                    )
                }
            },
    )
}

@Composable
internal fun ChevronIcon(
    pointsDown: Boolean,
    modifier: Modifier = Modifier,
) {
    val color = ExoTheme.colors.textTertiary
    // Canonical orientation: chevron pointing down (group expanded). Rotating
    // to -180° flips it to point up (counter-clockwise); rotating back to 0°
    // spins clockwise. Mirrors the SortArrow direction-toggle animation so
    // disclosure indicators across the app share one motion vocabulary.
    val target = if (pointsDown) 0f else -180f
    val rotation = remember { Animatable(target) }
    LaunchedEffect(pointsDown) {
        rotation.animateTo(target, tween(260, easing = FastOutSlowInEasing))
    }
    Box(
        modifier = modifier
            .rotate(rotation.value)
            .drawBehind {
                val sw = 1.2.dp.toPx()
                val cx = size.width / 2f
                // Always draw the downward-pointing chevron; rotation handles
                // the flipped state.
                drawLine(color, Offset(0f, size.height * 0.3f), Offset(cx, size.height * 0.7f), sw, cap = StrokeCap.Round)
                drawLine(color, Offset(size.width, size.height * 0.3f), Offset(cx, size.height * 0.7f), sw, cap = StrokeCap.Round)
            },
    )
}
