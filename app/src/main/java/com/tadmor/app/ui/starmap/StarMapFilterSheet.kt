package com.tadmor.app.ui.starmap

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.tadmor.app.ui.components.FilterGroup
import com.tadmor.app.ui.components.PlanetCountSlider
import com.tadmor.app.ui.components.touchRipple
import com.tadmor.app.ui.theme.ExoColors
import com.tadmor.app.ui.theme.ExoTheme
import com.tadmor.app.ui.theme.LocalBottomBarHeight
import com.tadmor.app.ui.theme.TeffColor
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Filter state for the star map.
 */
data class StarMapFilterState(
    val spectralClasses: Set<String> = emptySet(),
    val luminosityClasses: Set<String> = emptySet(),
    val minPlanets: Int = 0,
    val planetClasses: Set<String> = emptySet(),
) {
    val activeCount: Int
        get() = spectralClasses.size + luminosityClasses.size +
            (if (minPlanets > 0) 1 else 0) + planetClasses.size

    val activeLabels: List<Pair<String, String>>
        get() = buildList {
            spectralClasses.forEach { add("spectral" to it) }
            luminosityClasses.forEach { add("luminosity" to it) }
            if (minPlanets > 0) add("minPlanets" to "${minPlanets}+ planets")
            planetClasses.forEach { add("planetClass" to it) }
        }

    fun remove(group: String, value: String): StarMapFilterState = when (group) {
        "spectral" -> copy(spectralClasses = spectralClasses - value)
        "luminosity" -> copy(luminosityClasses = luminosityClasses - value)
        "minPlanets" -> copy(minPlanets = 0)
        "planetClass" -> copy(planetClasses = planetClasses - value)
        else -> this
    }

    fun clear(): StarMapFilterState = StarMapFilterState()
}

/**
 * Filter bottom sheet for the star map.
 * Categories: spectral class, planet count, planet composition class.
 */
@Composable
fun StarMapFilterSheet(
    filterState: StarMapFilterState,
    onFilterChange: (StarMapFilterState) -> Unit,
    onDismiss: () -> Unit,
    useTerra: Boolean = true,
    useNeptune: Boolean = true,
    useJupiter: Boolean = true,
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
                // Spectral Class group
                FilterGroup(
                    title = "SPECTRAL CLASS",
                    options = listOf("O", "B", "A", "F", "G", "K", "M", "L", "T", "Y", "Q", "D"),
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

                // Planet Class group — display names follow user settings
                val terraLabel = if (useTerra) "Terrestrial" else "Earth"
                val neptuneLabel = if (useNeptune) "Neptune" else "Ice Giant"
                val jupiterLabel = if (useJupiter) "Jupiter" else "Gas Giant"
                val classDisplayToCanonical = mapOf(
                    terraLabel to "Terrestrial",
                    neptuneLabel to "Neptune",
                    jupiterLabel to "Jupiter",
                )
                val classCanonicalToDisplay = classDisplayToCanonical.entries
                    .associate { (display, canonical) -> canonical to display }
                val selectedDisplay = filterState.planetClasses.mapNotNull {
                    classCanonicalToDisplay[it]
                }.toSet()

                FilterGroup(
                    title = "PLANET CLASS",
                    options = classDisplayToCanonical.keys.toList(),
                    selected = selectedDisplay,
                    onToggle = { displayLabel ->
                        val canonical = classDisplayToCanonical[displayLabel] ?: return@FilterGroup
                        val updated = if (canonical in filterState.planetClasses) {
                            filterState.planetClasses - canonical
                        } else {
                            filterState.planetClasses + canonical
                        }
                        onFilterChange(filterState.copy(planetClasses = updated))
                    },
                    labelColors = mapOf(
                        terraLabel to ExoColors.compositionTerra.text,
                        neptuneLabel to ExoColors.compositionNeptune.text,
                        jupiterLabel to ExoColors.compositionJupiter.text,
                    ),
                )

                Spacer(Modifier.height(ExoTheme.spacing.xxl))

                // Close button — same dark-tone ripple on accent-gold bg as
                // the catalog filter sheet so both close buttons feel
                // identical to tap.
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
