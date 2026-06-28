package com.tadmor.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.tadmor.app.ui.theme.ExoTheme

/**
 * "Filters" button per DESIGN.md Section 5.2.
 * Pill-shaped button that opens the filter bottom sheet.
 * Shows active state + count when filters are applied.
 */
@Composable
fun ExoFilterButton(
    activeCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ExoTheme.colors
    val shape = RoundedCornerShape(20.dp)

    val hasActive = activeCount > 0
    val backgroundColor = if (hasActive) colors.accentGoldSubtle else colors.surfaceRaised
    val borderColor = if (hasActive) colors.accentGoldBorder else colors.divider
    val iconColor = if (hasActive) colors.accentGold else colors.textSecondary

    val isAccessible = ExoTheme.isAccessible
    // Square icon button — height matches ExoSearchBar, width = height so
    // the bookmark filter button can sit beside it without visual rhythm
    // changes. Active state is signalled via the gold tint on the whole
    // button + the active-filter chips that appear below.
    val buttonSize = if (isAccessible) 52.dp else 44.dp
    val filterIconSize = if (isAccessible) 22.dp else 18.dp

    Box(
        modifier = modifier
            .size(buttonSize)
            .clip(shape)
            .background(backgroundColor)
            .touchRipple(
                color = Color.White,
                startAlpha = 0.18f,
                onClick = onClick,
            )
            .border(1.dp, borderColor, shape),
        contentAlignment = Alignment.Center,
    ) {
        FilterIcon(color = iconColor, modifier = Modifier.size(filterIconSize))
    }
}

@Composable
private fun FilterIcon(
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.drawBehind {
            val sw = 1.2.dp.toPx()
            val y1 = size.height * 0.2f
            val y2 = size.height * 0.5f
            val y3 = size.height * 0.8f
            drawLine(color, Offset(0f, y1), Offset(size.width, y1), sw, cap = StrokeCap.Round)
            drawLine(color, Offset(size.width * 0.2f, y2), Offset(size.width * 0.8f, y2), sw, cap = StrokeCap.Round)
            drawLine(color, Offset(size.width * 0.35f, y3), Offset(size.width * 0.65f, y3), sw, cap = StrokeCap.Round)
        },
    )
}

/**
 * Active filter chip — dismissable pill showing an applied filter value.
 * Per DESIGN.md Section 5.2.
 */
@Composable
fun ActiveFilterChip(
    label: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type
    val shape = RoundedCornerShape(20.dp)

    val isAccessible = ExoTheme.isAccessible
    val vPad = if (isAccessible) 10.dp else 6.dp
    val startPad = if (isAccessible) 14.dp else 10.dp
    val endPad = if (isAccessible) 8.dp else 6.dp
    val dismissSize = if (isAccessible) 24.dp else 16.dp

    Row(
        modifier = modifier
            .clip(shape)
            .background(colors.accentGoldSubtle)
            .border(1.dp, colors.accentGoldBorder, shape)
            .padding(start = startPad, end = endPad, top = vPad, bottom = vPad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = label,
            style = type.labelLarge.copy(color = colors.accentGold),
        )
        Spacer(Modifier.width(4.dp))
        // × dismiss icon
        Box(
            modifier = Modifier
                .size(dismissSize)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onDismiss)
                .drawBehind {
                    val pad = size.width * 0.3f
                    val color = colors.accentGoldDim
                    drawLine(color, Offset(pad, pad), Offset(size.width - pad, size.height - pad), 1.2.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(color, Offset(size.width - pad, pad), Offset(pad, size.height - pad), 1.2.dp.toPx(), cap = StrokeCap.Round)
                },
        )
    }
}

/**
 * "Clear all" chip — appears at the end of active filter row.
 */
@Composable
fun ClearAllChip(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type
    val shape = RoundedCornerShape(20.dp)

    val isAccessible = ExoTheme.isAccessible
    val hPad = if (isAccessible) 14.dp else 10.dp
    val vPad = if (isAccessible) 10.dp else 6.dp

    Box(
        modifier = modifier
            .clip(shape)
            .background(colors.surfaceRaised)
            .border(1.dp, colors.divider, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = hPad, vertical = vPad),
    ) {
        BasicText(
            text = "Clear all",
            style = type.labelLarge.copy(color = colors.textTertiary),
        )
    }
}
