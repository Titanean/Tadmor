package com.tadmor.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.tadmor.app.ui.util.Haptics
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tadmor.app.ui.theme.ExoTheme
import kotlin.math.cos
import kotlin.math.sin

/**
 * Bottom navigation per DESIGN.md Section 5.4.
 * Solid panel bar with 1dp top border.
 * 4 items: Catalog, System, Star Map, Settings.
 */
enum class NavDestination { CATALOG, SYSTEM, STAR_MAP, SETTINGS }

@Composable
fun ExoBottomNav(
    selected: NavDestination,
    onSelect: (NavDestination) -> Unit,
    systemEnabled: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = ExoTheme.colors
    val isAccessible = ExoTheme.isAccessible

    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        // Top border line
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.divider),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surfaceCard)
                .padding(
                    start = 24.dp,
                    end = 24.dp,
                    top = ExoTheme.spacing.md,
                    bottom = ExoTheme.spacing.md,
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            NavItem(
                label = "CATALOG",
                isSelected = selected == NavDestination.CATALOG,
                enabled = true,
                onClick = { onSelect(NavDestination.CATALOG) },
                drawIcon = { color -> drawCatalogIcon(color) },
            )
            NavItem(
                label = "SYSTEM",
                isSelected = selected == NavDestination.SYSTEM,
                enabled = systemEnabled,
                onClick = { if (systemEnabled) onSelect(NavDestination.SYSTEM) },
                drawIcon = { color -> drawSystemIcon(color) },
                iconSize = 24.dp,
            )
            NavItem(
                label = "STAR MAP",
                isSelected = selected == NavDestination.STAR_MAP,
                enabled = true,
                onClick = { onSelect(NavDestination.STAR_MAP) },
                drawIcon = { color -> drawStarMapIcon(color) },
            )
            NavItem(
                label = "SETTINGS",
                isSelected = selected == NavDestination.SETTINGS,
                enabled = true,
                onClick = { onSelect(NavDestination.SETTINGS) },
                drawIcon = { color -> drawSettingsIcon(color) },
                iconSize = 22.dp,
            )
        }
    }
}

@Composable
private fun NavItem(
    label: String,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    drawIcon: DrawScope.(Color) -> Unit,
    iconSize: Dp = 20.dp,
) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type
    val context = LocalContext.current

    val itemColor = when {
        isSelected -> colors.accentGold
        !enabled -> colors.textMuted.copy(alpha = 0.5f)
        else -> colors.textMuted
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = {
                    if (enabled && !isSelected) Haptics.selectLow(context)
                    onClick()
                },
            ),
    ) {
        // Fixed height so all icons align regardless of individual icon size
        Box(
            modifier = Modifier.height(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(iconSize)
                    .drawBehind { drawIcon(itemColor) },
            )
        }
        Spacer(Modifier.height(4.dp))
        BasicText(
            text = label,
            style = type.navLabel.copy(color = itemColor),
        )
    }
}

// Custom SVG-style icons per DESIGN.md Section 8.1
// All icons sized to fill the 20dp box edge-to-edge, matching the catalog icon.

private fun DrawScope.drawCatalogIcon(color: Color) {
    val sw = 1.2.dp.toPx()
    val stroke = Stroke(width = sw, cap = StrokeCap.Round)
    val half = sw / 2f
    drawRoundRect(
        color = color,
        topLeft = Offset(half, half),
        size = Size(size.width - sw, size.height - sw),
        cornerRadius = CornerRadius(3.dp.toPx()),
        style = stroke,
    )
    drawLine(
        color = color,
        start = Offset(half, size.height * 0.5f),
        end = Offset(size.width - half, size.height * 0.5f),
        strokeWidth = sw,
        cap = StrokeCap.Round,
    )
}

private fun DrawScope.drawSystemIcon(color: Color) {
    val sw = 1.2.dp.toPx()
    val stroke = Stroke(width = sw)
    val cx = size.width / 2f
    val cy = size.height / 2f
    val half = sw / 2f
    // Central star — scaled up to match catalog proportions
    drawCircle(color, radius = 3.dp.toPx(), center = Offset(cx, cy))
    // Orbit ellipse — fill the box
    drawOval(
        color = color,
        topLeft = Offset(half, size.height * 0.15f),
        size = Size(size.width - sw, size.height * 0.7f),
        style = stroke,
    )
    // Planet dot on orbit
    val angle = Math.toRadians(45.0)
    val px = cx + (size.width * 0.48f) * cos(angle).toFloat()
    val py = cy + (size.height * 0.33f) * sin(angle).toFloat()
    drawCircle(color, radius = 2.2.dp.toPx(), center = Offset(px, py))
}

private fun DrawScope.drawStarMapIcon(color: Color) {
    // Pioneer plaque pulsar map — Sol at center, radial lines of varied length
    // to nearby pulsars (represented as dots at each line's end).
    val sw = 1.2.dp.toPx()
    val cx = size.width / 2f
    val cy = size.height / 2f
    val maxR = size.minDimension * 0.48f
    val solRadius = 1.8.dp.toPx()
    val pulsarRadius = 1.3.dp.toPx()

    // Rays chosen for visual balance — angles (screen-space, 0° = right, CW)
    // and lengths as fraction of maxR. Irregular spacing evokes real stellar
    // positions rather than a regular starburst.
    val rays = listOf(
        15.0 to 1.14f,
        70.0 to 0.86f,
        118.0 to 1.08f,
        165.0 to 0.96f,
        215.0 to 1.14f,
        265.0 to 0.80f,
        315.0 to 1.04f,
    )
    for ((angleDeg, lengthFrac) in rays) {
        val a = Math.toRadians(angleDeg)
        val r = maxR * lengthFrac
        val ex = cx + r * cos(a).toFloat()
        val ey = cy + r * sin(a).toFloat()
        // Start inside the Sol dot — Sol is drawn last and fully covers the
        // overlap, giving a seamless line-to-dot join with no visible gap.
        val startOffset = solRadius * 0.5f
        val sx = cx + startOffset * cos(a).toFloat()
        val sy = cy + startOffset * sin(a).toFloat()
        drawLine(color, Offset(sx, sy), Offset(ex, ey), sw, cap = StrokeCap.Round)
        drawCircle(color, pulsarRadius, Offset(ex, ey))
    }
    // Sol at center — drawn last so it covers the line origins cleanly.
    drawCircle(color, solRadius, Offset(cx, cy))
}

private fun DrawScope.drawSettingsIcon(color: Color) {
    val cx = size.width / 2f
    val cy = size.height / 2f

    // Cogwheel teeth — 8 teeth as trapezoidal bumps around the perimeter
    val teethCount = 8
    val baseR = size.minDimension * 0.32f   // inner edge of teeth
    val tipR = size.minDimension * 0.50f    // outer edge of teeth
    val toothHalfAngle = Math.toRadians(14.0)  // half-width of each tooth
    val gapHalfAngle = Math.toRadians(8.5)     // half-width of gap between teeth

    val path = Path()
    for (i in 0 until teethCount) {
        val centerAngle = Math.toRadians(i * 360.0 / teethCount)

        val a1 = centerAngle - toothHalfAngle
        val a2 = centerAngle + toothHalfAngle
        val nextCenter = Math.toRadians((i + 1) * 360.0 / teethCount)
        val a3 = nextCenter - gapHalfAngle

        val bx1 = cx + baseR * cos(a1).toFloat()
        val by1 = cy + baseR * sin(a1).toFloat()
        val tx1 = cx + tipR * cos(a1).toFloat()
        val ty1 = cy + tipR * sin(a1).toFloat()
        val tx2 = cx + tipR * cos(a2).toFloat()
        val ty2 = cy + tipR * sin(a2).toFloat()
        val bx2 = cx + baseR * cos(a2).toFloat()
        val by2 = cy + baseR * sin(a2).toFloat()
        val bx3 = cx + baseR * cos(a3).toFloat()
        val by3 = cy + baseR * sin(a3).toFloat()

        if (i == 0) {
            path.moveTo(bx1, by1)
        } else {
            path.lineTo(bx1, by1)
        }
        path.lineTo(tx1, ty1)
        path.lineTo(tx2, ty2)
        path.lineTo(bx2, by2)
        path.lineTo(bx3, by3)
    }
    path.close()

    // Draw into a layer so BlendMode.Clear can punch through the center
    val innerR = size.minDimension * 0.18f
    drawContext.canvas.saveLayer(
        Rect(Offset.Zero, size),
        Paint(),
    )
    drawPath(path, color)
    drawCircle(Color.Black, radius = innerR, center = Offset(cx, cy), blendMode = BlendMode.Clear)
    drawContext.canvas.restore()
}
