package com.tadmor.app.ui.system

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.tadmor.app.ui.theme.ExoTheme
import com.tadmor.app.ui.theme.TeffColor
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun SystemStripView(
    orbitalState: OrbitalState,
    zoomFactor: Float = 1f,
    onZoomFactorChange: (Float) -> Unit = {},
    onPlanetTap: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = ExoTheme.colors

    val starColor = TeffColor.forStar(orbitalState.starTeffK, orbitalState.starSpectralType)
        ?: colors.textTertiary
    val planets = orbitalState.planets
    val hz = orbitalState.habitableZone

    // Zoom: drag right to zoom in (inner planets spread), drag left to
    // zoom out. Mirrors a typical zoom-slider gesture where rightward
    // motion increases magnification — feels like pulling the system
    // closer, not pushing it away.
    val maxZoom = if (planets.size > 1) {
        val minSMA = planets.minOf { it.smaAU }
        val maxSMA = planets.maxOf { it.smaAU }
        if (minSMA > 0) (maxSMA / minSMA).toFloat() else 1f
    } else {
        1f
    }

    // rememberUpdatedState so the pointerInput coroutine always reads fresh values
    // without restarting the gesture on every recomposition
    val currentZoomFactor = rememberUpdatedState(zoomFactor)
    val currentOnPlanetTap = rememberUpdatedState(onPlanetTap)
    val currentOnZoomFactorChange = rememberUpdatedState(onZoomFactorChange)

    // --- Per-planet fade state ---
    // Size is needed to compute visibility (which depends on pixel layout).
    // onSizeChanged publishes it into composition so target alpha can react.
    var sizePx by remember { mutableStateOf(IntSize.Zero) }

    // Target visibility encoded as a bitmask: bit i = 1 means planet i is visible.
    // Using a Long (value-typed) as the LaunchedEffect key lets us key on the
    // actual visibility *pattern* — the mask only changes when a planet crosses
    // the visibility threshold, not every drag frame. A FloatArray key uses
    // reference equality, which made the animation restart on every
    // recomposition and only ever advance after the user lifted their finger.
    // Fade target: bit=1 means planet i should be at full alpha (drawn or
    // off-screen). Bit=0 means hidden by star/adjacent overlap and should fade
    // out. Off-screen planets stay at bit=1 so they don't trigger a fade when
    // they come back — zoom-driven reveal is instant, only star-proximity
    // culling fades.
    val targetMask: Long = remember(sizePx, planets, zoomFactor, orbitalState.starRadiusSolar) {
        if (planets.isEmpty()) {
            0L
        } else if (sizePx.width <= 0 || sizePx.height <= 0) {
            (1L shl planets.size.coerceAtMost(63)) - 1
        } else {
            val layout = computeStripLayout(
                w = sizePx.width.toFloat(),
                h = sizePx.height.toFloat(),
                planets = planets,
                starRadiusSolar = orbitalState.starRadiusSolar,
                zoomFactor = zoomFactor,
            )
            var m = 0L
            val n = planets.size.coerceAtMost(63)
            for (i in 0 until n) {
                if (!layout.overlapHidden[i]) m = m or (1L shl i)
            }
            m
        }
    }

    // Current alpha, animated toward target via withFrameNanos. Holding the array
    // in a MutableState<FloatArray> means drawBehind redraws whenever it advances.
    var currentAlpha by remember(planets) {
        mutableStateOf(FloatArray(planets.size) { 1f })
    }
    // Reset to false on system change so the first real target after measurement
    // snaps rather than fading — avoids an initial flash where initially-hidden
    // planets fade out from full alpha on page open.
    var alphaInitialized by remember(planets) { mutableStateOf(false) }

    LaunchedEffect(targetMask, planets) {
        val target = FloatArray(planets.size) { i ->
            if (i < 63 && ((targetMask shr i) and 1L) == 1L) 1f else 0f
        }
        if (!alphaInitialized) {
            currentAlpha = target
            alphaInitialized = true
            return@LaunchedEffect
        }
        // Short-circuit if already at target (prevents zero-delta animation loops).
        if (currentAlpha.size == target.size &&
            currentAlpha.indices.all { currentAlpha[it] == target[it] }
        ) return@LaunchedEffect

        val startAlpha = currentAlpha.copyOf()
        val durationMs = 320f
        val startNanos = withFrameNanos { it }
        var linear = 0f
        while (linear < 1f) {
            val frameNanos = withFrameNanos { it }
            val elapsedMs = (frameNanos - startNanos) / 1_000_000f
            linear = (elapsedMs / durationMs).coerceIn(0f, 1f)
            val eased = FastOutSlowInEasing.transform(linear)
            val next = FloatArray(startAlpha.size)
            for (i in startAlpha.indices) {
                next[i] = startAlpha[i] + (target[i] - startAlpha[i]) * eased
            }
            currentAlpha = next
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(colors.surfaceCard)
            .border(1.dp, colors.surfaceBorderHalf, RoundedCornerShape(4.dp))
            .onSizeChanged { sizePx = it }
            .pointerInput(planets, sizePx, zoomFactor, orbitalState.starRadiusSolar) {
                // Tap detection runs in parallel with the drag-to-zoom
                // handler below — `detectTapGestures` only fires on a
                // release without slop-crossing movement, while
                // `detectHorizontalDragGestures` only engages once the
                // pointer has moved past slop, so they don't compete.
                // Hit-test recomputes the strip layout (cheap) and finds
                // the visible planet whose centre is closest to the tap,
                // within a small extra tolerance for the smaller planets.
                val tapTolerancePx = 12.dp.toPx()
                detectTapGestures { offset ->
                    if (sizePx.width <= 0 || sizePx.height <= 0) return@detectTapGestures
                    val layout = computeStripLayout(
                        w = sizePx.width.toFloat(),
                        h = sizePx.height.toFloat(),
                        planets = planets,
                        starRadiusSolar = orbitalState.starRadiusSolar,
                        zoomFactor = zoomFactor,
                    )
                    var bestIdx = -1
                    var bestDist = Float.MAX_VALUE
                    for (i in planets.indices) {
                        if (!layout.drawable[i] || layout.overlapHidden[i]) continue
                        val dx = abs(offset.x - layout.planetPositionsX[i])
                        val maxHit = layout.finalPlanetRadii[i] + tapTolerancePx
                        if (dx <= maxHit && dx < bestDist) {
                            bestDist = dx
                            bestIdx = i
                        }
                    }
                    if (bestIdx >= 0) {
                        currentOnPlanetTap.value(planets[bestIdx].name)
                    }
                }
            }
            .pointerInput(maxZoom) {
                detectHorizontalDragGestures { _, dragAmount ->
                    val logZoom = ln(currentZoomFactor.value)
                    val newLogZoom = logZoom + dragAmount * 0.006f
                    currentOnZoomFactorChange.value(exp(newLogZoom).coerceIn(1f, maxZoom))
                }
            }
            .drawBehind {
                drawSystemStrip(
                    planets = planets,
                    starColor = starColor,
                    starRadiusSolar = orbitalState.starRadiusSolar,
                    hz = hz,
                    colors = colors,
                    zoomFactor = zoomFactor,
                    isCircumbinary = orbitalState.isCircumbinary,
                    companionTeffK = orbitalState.companionTeffK,
                    companionSpectralType = orbitalState.companionSpectralType,
                    companionRadiusSolar = orbitalState.companionRadiusSolar,
                    planetAlpha = currentAlpha,
                )
            },
    )
}

// --- Layout math (shared between visibility probe and draw) ---

/**
 * Pure layout computation: pixel positions, radii, and visibility decisions for
 * a strip of the given size and zoom. Called from two places — the composable
 * (to derive target fade alphas) and [drawSystemStrip]. Keeping one source of
 * truth prevents visibility drift between the two paths.
 */
private data class StripLayout(
    val w: Float,
    val h: Float,
    val starCenterX: Float,
    val starR: Float,
    val planetPositionsX: FloatArray,
    val finalPlanetRadii: FloatArray,
    // On-screen status. False when the planet has been pushed off-screen right
    // by zoom. Off-screen planets are NOT drawn and do NOT participate in the
    // fade animation — they pop in/out instantly so zooming doesn't trigger a
    // cascade of fades.
    val drawable: BooleanArray,
    // Hidden by star-proximity / adjacent-planet overlap (on-screen only).
    // This is the only visibility change that drives the per-planet fade.
    val overlapHidden: BooleanArray,
    val maxSMA: Double,
    val baseDistanceSpan: Float,
    val minStarR: Float,
    val maxStarR: Float,
)

private fun computeStripLayout(
    w: Float,
    h: Float,
    planets: List<OrbitalPlanetState>,
    starRadiusSolar: Double?,
    zoomFactor: Float,
): StripLayout {
    val padLeft = 30f
    val padRight = 30f

    // --- Planet sizing (computed first so star can clamp against it) ---
    val maxPlanetR = h * 0.14f
    val minPlanetR = h * 0.03f
    val radii = planets.map { it.radiusEarth ?: 1.0 }
    val maxRad = if (radii.isNotEmpty()) radii.max() else 1.0
    val planetScreenRadii = radii.map { r ->
        ((r / maxRad) * maxPlanetR).toFloat().coerceAtLeast(minPlanetR)
    }
    val largestPlanetScreenR = if (planetScreenRadii.isNotEmpty()) planetScreenRadii.max() else 0f

    // --- Star sizing ---
    val maxStarR = h * 0.35f
    val minStarR = h * 0.06f
    val rawStarR = starRadiusSolar?.let { (it * h * 0.08f).toFloat() } ?: (h * 0.10f)
    var starR = rawStarR.coerceIn(minStarR, maxStarR)

    val starRadiusEarth = (starRadiusSolar ?: 1.0) * 109.076
    val largestPlanetFraction = maxRad / starRadiusEarth
    if (planets.isNotEmpty()) {
        if (largestPlanetFraction < 0.3) {
            starR = max(starR, largestPlanetScreenR)
        } else {
            val proportionalR = (starRadiusEarth / maxRad).toFloat() * largestPlanetScreenR
            starR = max(starR, proportionalR)
        }
    }

    val starCenterX = padLeft + starR

    if (planets.isEmpty()) {
        return StripLayout(
            w = w, h = h,
            starCenterX = starCenterX, starR = starR,
            planetPositionsX = FloatArray(0),
            finalPlanetRadii = FloatArray(0),
            drawable = BooleanArray(0),
            overlapHidden = BooleanArray(0),
            maxSMA = 1.0, baseDistanceSpan = 0f,
            minStarR = minStarR, maxStarR = maxStarR,
        )
    }

    // --- Distance mapping (initial pass at zoom=1 with original radii) ---
    val maxSMA = planets.maxOf { it.smaAU }
    val farthestIdx = planets.indexOfFirst { it.smaAU == maxSMA }

    val initialDistanceSpan = (w - padRight - planetScreenRadii[farthestIdx]) - starCenterX
    val initialPositionsX = planets.map { p ->
        starCenterX + (p.smaAU / maxSMA).toFloat() * initialDistanceSpan
    }

    // --- Shrink planets uniformly if any overlap (min 15px edge-to-edge gap) ---
    val minGap = 15f
    var scale = 1f
    val starRightEdge = starCenterX + starR
    val firstGap = (initialPositionsX[0] - starRightEdge) - planetScreenRadii[0]
    if (firstGap < minGap && planetScreenRadii[0] > minPlanetR) {
        val needed = planetScreenRadii[0] - (firstGap - minGap)
        if (needed > 0) scale = min(scale, max(minPlanetR / planetScreenRadii[0], 1f - needed / planetScreenRadii[0]))
    }
    val minPairSpace = 2 * minPlanetR + minGap
    for (i in 1 until initialPositionsX.size) {
        val dist = initialPositionsX[i] - initialPositionsX[i - 1]
        if (dist < minPairSpace) continue
        val radiiSum = planetScreenRadii[i] + planetScreenRadii[i - 1]
        val gap = dist - radiiSum * scale
        if (gap < minGap) {
            val requiredScale = if (radiiSum > 0) (dist - minGap) / radiiSum else 1f
            scale = min(scale, requiredScale)
        }
    }
    scale = max(scale, minPlanetR / planetScreenRadii.max())
    scale = scale.coerceIn(0.1f, 1f)

    val finalPlanetRadii = FloatArray(planets.size) { i ->
        (planetScreenRadii[i] * scale).coerceAtLeast(minPlanetR)
    }

    val baseDistanceSpan = (w - padRight - finalPlanetRadii[farthestIdx]) - starCenterX
    val planetPositionsX = FloatArray(planets.size) { i ->
        starCenterX + (planets[i].smaAU / maxSMA).toFloat() * baseDistanceSpan * zoomFactor
    }

    // --- Determine planet visibility ---
    // drawable: true unless planet is completely off-screen right (from zooming in).
    // overlapHidden: true when an on-screen planet collides with the star or with
    //                the next outer visible planet.
    val drawable = BooleanArray(planets.size) { true }
    for (i in planets.indices) {
        if (planetPositionsX[i] - finalPlanetRadii[i] > w) drawable[i] = false
    }
    val overlapHidden = BooleanArray(planets.size) { false }
    var nextVisibleIdx = -1
    for (i in planets.indices.reversed()) {
        if (!drawable[i]) continue
        if (nextVisibleIdx >= 0) {
            val gap = (planetPositionsX[nextVisibleIdx] - planetPositionsX[i]) -
                (finalPlanetRadii[nextVisibleIdx] + finalPlanetRadii[i])
            if (gap < 0) {
                overlapHidden[i] = true
                continue
            }
        }
        val starGap = (planetPositionsX[i] - (starCenterX + starR)) - finalPlanetRadii[i]
        if (starGap < 0) {
            overlapHidden[i] = true
            continue
        }
        nextVisibleIdx = i
    }

    return StripLayout(
        w = w, h = h,
        starCenterX = starCenterX, starR = starR,
        planetPositionsX = planetPositionsX,
        finalPlanetRadii = finalPlanetRadii,
        drawable = drawable,
        overlapHidden = overlapHidden,
        maxSMA = maxSMA,
        baseDistanceSpan = baseDistanceSpan,
        minStarR = minStarR, maxStarR = maxStarR,
    )
}

// --- Drawing logic ---

private fun DrawScope.drawSystemStrip(
    planets: List<OrbitalPlanetState>,
    starColor: Color,
    starRadiusSolar: Double?,
    hz: com.tadmor.domain.classification.HabitableZoneResult?,
    colors: com.tadmor.app.ui.theme.ExoColors,
    zoomFactor: Float = 1f,
    isCircumbinary: Boolean = false,
    companionTeffK: Double? = null,
    companionSpectralType: String? = null,
    companionRadiusSolar: Double? = null,
    planetAlpha: FloatArray = FloatArray(0),
) {
    if (planets.isEmpty()) return

    val w = size.width
    val h = size.height
    val cy = h / 2f

    val layout = computeStripLayout(w, h, planets, starRadiusSolar, zoomFactor)
    val starCenterX = layout.starCenterX
    val starR = layout.starR
    val planetPositionsX = layout.planetPositionsX
    val finalPlanetRadii = layout.finalPlanetRadii
    val baseDistanceSpan = layout.baseDistanceSpan
    val maxSMA = layout.maxSMA
    val minStarR = layout.minStarR
    val maxStarR = layout.maxStarR

    // --- Habitable zone (donut centered on star) ---
    if (hz != null) {
        val hzInnerR = (hz.innerAU / maxSMA).toFloat() * baseDistanceSpan * zoomFactor
        val hzOuterR = (hz.outerAU / maxSMA).toFloat() * baseDistanceSpan * zoomFactor
        val hzColor = com.tadmor.app.ui.theme.ExoColors.compositionTerra.text.copy(alpha = 0.06f)
        if (hzOuterR > hzInnerR) {
            val annulus = Path().apply {
                fillType = PathFillType.EvenOdd
                addOval(
                    Rect(
                        starCenterX - hzOuterR, cy - hzOuterR,
                        starCenterX + hzOuterR, cy + hzOuterR,
                    ),
                )
                addOval(
                    Rect(
                        starCenterX - hzInnerR, cy - hzInnerR,
                        starCenterX + hzInnerR, cy + hzInnerR,
                    ),
                )
            }
            clipRect(0f, 0f, w, h) {
                drawPath(annulus, hzColor)
            }
        }
    }

    // --- Orbit arcs (all planets, including hidden — signals hidden planets to the user) ---
    clipRect(0f, 0f, w, h) {
        for (i in planets.indices) {
            val p = planets[i]
            val orbitRadius = planetPositionsX[i] - starCenterX

            val orbitColor = colors.textTertiary.copy(alpha = 0.25f)
            val strokeStyle = if (p.isEstimated) {
                Stroke(
                    width = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)),
                )
            } else {
                Stroke(width = 1f)
            }
            drawCircle(
                color = orbitColor,
                radius = orbitRadius,
                center = Offset(starCenterX, cy),
                style = strokeStyle,
            )
        }
    }

    // --- Star(s) with glow ---
    if (isCircumbinary) {
        // Binary: two stars stacked vertically, sized relative to each other
        val companionColor = TeffColor.forStar(companionTeffK, companionSpectralType)
            ?: colors.textTertiary

        // Scale companion relative to primary using physical radii ratio
        val primaryRSolar = starRadiusSolar ?: 1.0
        val companionRSolar = companionRadiusSolar ?: (primaryRSolar * 0.7)
        val ratio = (companionRSolar / primaryRSolar).toFloat().coerceIn(0.3f, 1.5f)
        val companionR = (starR * ratio).coerceIn(minStarR, maxStarR)

        // Center the pair around cy with a consistent gap
        val gap = 10f
        val primaryCenterY = cy - companionR - gap / 2f
        val companionCenterY = cy + starR + gap / 2f
        drawStarGlow(starColor, starR, Offset(starCenterX, primaryCenterY))
        drawStarGlow(companionColor, companionR, Offset(starCenterX, companionCenterY))
        drawCircle(color = starColor, radius = starR, center = Offset(starCenterX, primaryCenterY))
        drawCircle(color = companionColor, radius = companionR, center = Offset(starCenterX, companionCenterY))
    } else {
        drawStarGlow(starColor, starR, Offset(starCenterX, cy))
        drawCircle(color = starColor, radius = starR, center = Offset(starCenterX, cy))
    }

    // --- Planets, faded by per-planet alpha ---
    // Off-screen planets (pushed past the right edge by zoom) are skipped
    // entirely — no fade. Only star/overlap culling triggers the fade.
    for (i in planets.indices) {
        if (!layout.drawable[i]) continue
        val a = if (i < planetAlpha.size) planetAlpha[i] else 1f
        if (a <= 0f) continue
        val px = planetPositionsX[i]
        val pr = finalPlanetRadii[i]
        val pColor = Color(planets[i].dominantColor.toInt()).copy(alpha = a)

        drawCircle(
            color = pColor,
            radius = pr,
            center = Offset(px, cy),
        )
    }
}

private fun DrawScope.drawStarGlow(color: Color, radius: Float, center: Offset) {
    val glowRadius = radius * 1.35f
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
}
