package com.tadmor.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.tadmor.app.ui.starmap.SelectedStarInfo
import com.tadmor.app.ui.theme.ExoTheme
import com.tadmor.domain.model.DistanceUnit
import com.tadmor.domain.model.ProperNames
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Tooltip card anchored near a selected star's screen position.
 * When the star is on-screen, draws a dashed connector line to it.
 * When the star is off-screen, clamps to the screen edge and shows
 * a directional arrow pointing toward the star.
 *
 * No dismiss scrim — touches pass through to the GL view underneath,
 * allowing camera rotation while the tooltip is open.
 */
@Composable
fun StarTooltip(
    star: SelectedStarInfo,
    screenPos: Offset,
    isOnScreen: Boolean,
    containerSize: IntSize,
    distanceUnit: DistanceUnit,
    useEstimates: Boolean,
    useProperNames: Boolean = false,
    bottomInsetPx: Int = 0,
    onViewSystem: (String) -> Unit,
    onCenterView: () -> Unit = {},
    isCentered: Boolean = false,
    visible: Boolean = true,
    onDismissComplete: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val colors = ExoTheme.colors
    val type = ExoTheme.type
    val spacing = ExoTheme.spacing

    val shape = RoundedCornerShape(8.dp)

    val tooltipWidth = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val tooltipHeight = remember { androidx.compose.runtime.mutableIntStateOf(0) }

    // Bottom-to-top reveal + reverse close. Keyed on hostname so selecting
    // a different star restarts; on visible so dismissal reverses. The
    // close animation runs from the current progress down to 0, then fires
    // onDismissComplete so the parent can unmount.
    var progress by remember(star.hostname) { mutableFloatStateOf(0f) }
    LaunchedEffect(star.hostname, visible) {
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

    // Margins: larger when off-screen to leave room for direction arrow
    val margin = with(density) {
        if (isOnScreen) spacing.sm.roundToPx() else 20.dp.roundToPx()
    }

    // X position: center tooltip on star's projected X, clamp to screen
    val tooltipX = run {
        val raw = screenPos.x.toInt() - tooltipWidth.intValue / 2
        raw.coerceIn(margin, (containerSize.width - tooltipWidth.intValue - margin).coerceAtLeast(0))
    }

    // Effective viewport bottom — exclude bottom nav so the tooltip doesn't
    // clamp underneath it when the star is off-screen below.
    val usableBottom = (containerSize.height - bottomInsetPx).coerceAtLeast(0)

    // Y position: above/below star when on-screen, centered on star when off-screen
    val tooltipY = if (isOnScreen) {
        val gap = with(density) { 24.dp.roundToPx() }
        val isAbove = screenPos.y.toInt() - tooltipHeight.intValue - gap >= margin
        if (isAbove) screenPos.y.toInt() - tooltipHeight.intValue - gap
        else screenPos.y.toInt() + gap
    } else {
        val raw = screenPos.y.toInt() - tooltipHeight.intValue / 2
        raw.coerceIn(margin, (usableBottom - tooltipHeight.intValue - margin).coerceAtLeast(0))
    }

    val distanceText = if (star.distancePc != null) {
        when (distanceUnit) {
            DistanceUnit.PARSECS -> "%.1f pc".format(star.distancePc)
            DistanceUnit.LIGHT_YEARS -> "%.1f ly".format(star.distancePc * 3.26156)
        }
    } else null

    // No full-size clickable — touches pass through to GL view for camera rotation
    Box(modifier = modifier.fillMaxSize()) {
        val lineColor = colors.textMuted

        Canvas(modifier = Modifier.fillMaxSize()) {
            val tw = tooltipWidth.intValue.toFloat()
            val th = tooltipHeight.intValue.toFloat()
            if (tw <= 0f || th <= 0f) return@Canvas

            if (isOnScreen) {
                // Dashed connector line from tooltip edge to just short of the star
                val tooltipCenterX = tooltipX + tw / 2f
                val tooltipEdgeY = if (screenPos.y > tooltipY + th) {
                    (tooltipY + th).toFloat()
                } else {
                    tooltipY.toFloat()
                }

                val start = Offset(tooltipCenterX, tooltipEdgeY)
                val dx = screenPos.x - start.x
                val dy = screenPos.y - start.y
                val len = sqrt(dx * dx + dy * dy)
                val stopPx = 3.dp.toPx()
                val end = if (len > stopPx) {
                    val ratio = (len - stopPx) / len
                    Offset(start.x + dx * ratio, start.y + dy * ratio)
                } else {
                    start
                }

                if (len > stopPx && progress > 0f) {
                    // Grow the dashed line from the lower screen endpoint
                    // upward. Whichever of (tooltip edge, star-side end) has
                    // the greater y is the "bottom"; the animation fills
                    // from there toward the "top".
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
                // Direction arrow at tooltip edge pointing toward the star
                val cx = tooltipX + tw / 2f
                val cy = tooltipY + th / 2f
                val dirX = screenPos.x - cx
                val dirY = screenPos.y - cy
                val dirLen = sqrt(dirX * dirX + dirY * dirY)

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

                    // Rotated chevron: base shape points right, rotate to face star
                    val angleDeg = atan2(normY, normX) * (180f / PI.toFloat())
                    val sw = 1.2.dp.toPx()
                    val hw = 3.dp.toPx()  // half-width
                    val hh = 4.dp.toPx()  // half-height

                    rotate(angleDeg, pivot = Offset(ax, ay)) {
                        drawLine(
                            chevronColor,
                            Offset(ax - hw, ay - hh),
                            Offset(ax + hw, ay),
                            sw,
                            cap = StrokeCap.Round,
                        )
                        drawLine(
                            chevronColor,
                            Offset(ax + hw, ay),
                            Offset(ax - hw, ay + hh),
                            sw,
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }
        }

        // Tooltip card — clickable to consume taps (prevents accidental star selection through card)
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
                ) { /* consume taps on the tooltip itself */ },
        ) {
            // Hostname row with center-view button
            Row(
                verticalAlignment = Alignment.Top,
            ) {
                Column {
                    // Hostname (with IAU proper name if enabled)
                    val properName = ProperNames.forStar(star.hostname)
                    val displayName = if (useProperNames && properName != null) properName else star.hostname
                    BasicText(
                        text = displayName,
                        style = type.bodyMedium.copy(color = colors.textPrimary),
                    )
                    // Show catalog name as subtitle when proper name is used
                    if (useProperNames && properName != null) {
                        Spacer(Modifier.height(1.dp))
                        BasicText(
                            text = star.hostname,
                            style = type.labelLarge.copy(color = colors.textMuted),
                        )
                    }
                }

                if (!isCentered) {
                Spacer(Modifier.width(spacing.sm))

                // Center-view crosshair button
                val crosshairSize = if (ExoTheme.isAccessible) 22.dp else 18.dp
                Box(
                    modifier = Modifier
                        .size(crosshairSize)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = onCenterView,
                        )
                        .drawBehind {
                            val sw = 1.2.dp.toPx()
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            val radius = size.width / 2f - sw
                            val gap = 2.dp.toPx() // gap at cardinal points

                            // Draw circle as four arcs (using lines approximation)
                            // Top-right quadrant
                            val segments = 8
                            fun arcSegment(startDeg: Float, endDeg: Float) {
                                val startRad = startDeg * (PI.toFloat() / 180f)
                                val endRad = endDeg * (PI.toFloat() / 180f)
                                val step = (endRad - startRad) / segments
                                for (s in 0 until segments) {
                                    val a1 = startRad + step * s
                                    val a2 = startRad + step * (s + 1)
                                    drawLine(
                                        colors.textSecondary,
                                        Offset(
                                            cx + radius * kotlin.math.cos(a1),
                                            cy - radius * kotlin.math.sin(a1),
                                        ),
                                        Offset(
                                            cx + radius * kotlin.math.cos(a2),
                                            cy - radius * kotlin.math.sin(a2),
                                        ),
                                        sw,
                                        cap = StrokeCap.Round,
                                    )
                                }
                            }

                            // Four arcs with gaps at 0°, 90°, 180°, 270°
                            val gapDeg = 18f // angular gap in degrees
                            arcSegment(gapDeg, 90f - gapDeg)
                            arcSegment(90f + gapDeg, 180f - gapDeg)
                            arcSegment(180f + gapDeg, 270f - gapDeg)
                            arcSegment(270f + gapDeg, 360f - gapDeg)

                            // Crosshair lines (from gap edge inward)
                            val inner = 2.dp.toPx()
                            // Right
                            drawLine(colors.textSecondary, Offset(cx + inner, cy), Offset(cx + radius - gap, cy), sw, cap = StrokeCap.Round)
                            // Left
                            drawLine(colors.textSecondary, Offset(cx - inner, cy), Offset(cx - radius + gap, cy), sw, cap = StrokeCap.Round)
                            // Top
                            drawLine(colors.textSecondary, Offset(cx, cy - inner), Offset(cx, cy - radius + gap), sw, cap = StrokeCap.Round)
                            // Bottom
                            drawLine(colors.textSecondary, Offset(cx, cy + inner), Offset(cx, cy + radius - gap), sw, cap = StrokeCap.Round)
                        },
                )
                } // end if (!isCentered)
            }

            Spacer(Modifier.height(spacing.xs))

            // Spectral type + distance row
            val showSpectral = star.spectralType != null &&
                (!star.isEstimatedSpectral || useEstimates)
            if (showSpectral || distanceText != null) {
                Row {
                    if (showSpectral) {
                        BasicText(
                            text = star.spectralType!!,
                            style = type.labelLarge.copy(
                                color = Color(star.colorRgb[0], star.colorRgb[1], star.colorRgb[2]),
                                fontStyle = if (star.isEstimatedSpectral) FontStyle.Italic else FontStyle.Normal,
                            ),
                        )
                        if (distanceText != null) Spacer(Modifier.width(spacing.sm))
                    }
                    if (distanceText != null) {
                        BasicText(
                            text = distanceText,
                            style = type.labelLarge.copy(color = colors.textSecondary),
                        )
                    }
                }
            }

            // Planet count
            if (star.planetCount != null && star.planetCount > 0) {
                Spacer(Modifier.height(spacing.xs))
                BasicText(
                    text = "${star.planetCount} planet${if (star.planetCount > 1) "s" else ""}",
                    style = type.labelLarge.copy(color = colors.textTertiary),
                )
            }

            if (!star.isSol) {
                Spacer(Modifier.height(spacing.sm))

                // "View system" link with drawn chevron
                val viewSystemInteraction = remember { MutableInteractionSource() }
                Row(
                    modifier = Modifier
                        .pushOnPress(viewSystemInteraction)
                        .clickable(
                            indication = null,
                            interactionSource = viewSystemInteraction,
                            onClick = { onViewSystem(star.hostname) },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        text = "View system",
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
        }
    }
}
