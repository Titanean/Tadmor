package com.tadmor.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.tadmor.app.ui.theme.ExoTheme

/**
 * "Saved only" active-filter pill. Mirrors [ActiveFilterChip] visually
 * but prepends a small filled bookmark icon. Always rendered as the
 * leftmost chip in the active-filter row when the bookmark filter is on.
 *
 * Tapping the dismiss × turns the bookmark filter off (same effect as
 * tapping the [BookmarkFilterButton] in the header again).
 */
@Composable
fun SavedOnlyChip(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type
    val shape = RoundedCornerShape(20.dp)

    val isAccessible = ExoTheme.isAccessible
    val vPad = if (isAccessible) 10.dp else 6.dp
    val startPad = if (isAccessible) 12.dp else 8.dp
    val endPad = if (isAccessible) 8.dp else 6.dp
    val dismissSize = if (isAccessible) 24.dp else 16.dp
    val iconSize = if (isAccessible) 14.dp else 11.dp

    Row(
        modifier = modifier
            .clip(shape)
            .background(colors.accentGoldSubtle)
            .border(1.dp, colors.accentGoldBorder, shape)
            .padding(start = startPad, end = endPad, top = vPad, bottom = vPad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookmarkIcon(
            color = colors.accentGold,
            filled = true,
            modifier = Modifier.size(iconSize),
        )
        Spacer(Modifier.width(6.dp))
        BasicText(
            text = "Saved only",
            style = type.labelLarge.copy(color = colors.accentGold),
        )
        Spacer(Modifier.width(4.dp))
        // × dismiss — same Canvas-drawn cross as ActiveFilterChip
        Box(
            modifier = Modifier
                .size(dismissSize)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onDismiss)
                .drawBehind {
                    val sw = 1.2.dp.toPx()
                    val pad = size.width * 0.28f
                    drawLine(
                        color = colors.accentGold,
                        start = Offset(pad, pad),
                        end = Offset(size.width - pad, size.height - pad),
                        strokeWidth = sw,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = colors.accentGold,
                        start = Offset(size.width - pad, pad),
                        end = Offset(pad, size.height - pad),
                        strokeWidth = sw,
                        cap = StrokeCap.Round,
                    )
                },
        )
    }
}
