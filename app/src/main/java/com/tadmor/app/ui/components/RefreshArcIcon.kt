package com.tadmor.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.tadmor.app.ui.theme.ExoTheme
import kotlin.math.cos
import kotlin.math.sin

/**
 * Shared refresh arc icon used by both the pull-to-refresh indicator
 * and the refresh button. Draws a circular arc with an arrowhead
 * at the top (12 o'clock) pointing upward.
 *
 * @param sweep arc sweep in degrees (0–270). 270 = full icon.
 */
@Composable
fun RefreshArcIcon(
    modifier: Modifier = Modifier,
    sweep: Float = 270f,
) {
    val colors = ExoTheme.colors
    Box(
        modifier = modifier.drawBehind {
            val color = colors.textSecondary
            val sw = 1.2.dp.toPx()
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = size.minDimension * 0.38f

            if (sweep <= 0f) return@drawBehind

            // Arc from -90° (12 o'clock) sweeping clockwise
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                style = Stroke(width = sw, cap = StrokeCap.Round),
                topLeft = Offset(cx - r, cy - r),
                size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
            )

            // Arrowhead at arc endpoint
            val endDeg = -90.0 + sweep
            val endRad = Math.toRadians(endDeg)
            val ax = cx + r * cos(endRad).toFloat()
            val ay = cy + r * sin(endRad).toFloat()
            val arr = 3.dp.toPx()

            // Tangent direction at endpoint (perpendicular to radius, clockwise)
            val tangentRad = endRad + Math.PI / 2.0
            // Arms: short backward along tangent, wide spread along radial direction.
            // Backward kept short so the inner arm doesn't merge with the arc tail.
            val back = arr * 0.55f
            val spread = arr * 0.85f
            val arrowPath = Path().apply {
                moveTo(
                    ax - (back * cos(tangentRad) + spread * cos(endRad)).toFloat(),
                    ay - (back * sin(tangentRad) + spread * sin(endRad)).toFloat(),
                )
                lineTo(ax, ay)
                lineTo(
                    ax - (back * cos(tangentRad) - spread * cos(endRad)).toFloat(),
                    ay - (back * sin(tangentRad) - spread * sin(endRad)).toFloat(),
                )
            }
            drawPath(arrowPath, color, style = Stroke(sw, cap = StrokeCap.Round))
        },
    )
}
