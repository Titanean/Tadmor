package com.tadmor.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.tadmor.app.ui.theme.ExoTheme
import kotlin.math.roundToInt

/**
 * Toggle switch per DESIGN.md Section 5.8.
 * Track: 40dp x 22dp, thumb: 18dp circle.
 */
@Composable
fun ExoToggle(
    label: String,
    isOn: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type

    val thumbOffset by animateFloatAsState(
        targetValue = if (isOn) 20f else 2f,
        label = "thumbOffset",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        BasicText(
            text = label,
            style = type.labelLarge.copy(color = colors.textSecondary),
        )
        Spacer(Modifier.width(ExoTheme.spacing.sm))
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(22.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(if (isOn) colors.accentGoldSubtle else colors.surfaceRaised)
                .border(1.dp, colors.divider, RoundedCornerShape(11.dp))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = { onToggle(!isOn) },
                ),
        ) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(thumbOffset.dp.roundToPx(), 0) }
                    .align(Alignment.CenterStart)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(if (isOn) colors.accentGold else colors.textMuted),
            )
        }
    }
}
