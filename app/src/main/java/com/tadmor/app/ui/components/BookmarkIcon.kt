package com.tadmor.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Bookmark icon: rectangular ribbon with a downward V notch at the bottom.
 * Drawn via [Canvas] (no icon-library dependency, per the standing rule).
 *
 * - [filled] = false → 1.2dp stroked outline using [color]
 * - [filled] = true  → solid fill using [color]
 *
 * The aspect ratio of the icon (~0.7 wide / 1.0 tall) leaves a comfortable
 * margin inside its [androidx.compose.ui.Modifier.size] bounds so the icon
 * reads at the same visual weight as other icons in the app.
 */
@Composable
fun BookmarkIcon(
    color: Color,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        // Icon shape inside the bounds: ~70% wide, full height.
        val iconW = w * 0.66f
        val iconH = h * 0.92f
        val left = (w - iconW) / 2f
        val top = (h - iconH) / 2f
        val notchDepth = iconH * 0.30f
        val sw = 1.2.dp.toPx()

        val path = Path().apply {
            moveTo(left, top)
            lineTo(left + iconW, top)
            lineTo(left + iconW, top + iconH)
            lineTo(left + iconW / 2f, top + iconH - notchDepth)
            lineTo(left, top + iconH)
            close()
        }

        if (filled) {
            drawPath(path = path, color = color)
        } else {
            drawPath(
                path = path,
                color = color,
                style = Stroke(
                    width = sw,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }
    }
}
