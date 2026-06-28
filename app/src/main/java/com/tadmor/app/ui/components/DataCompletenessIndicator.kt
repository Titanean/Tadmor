package com.tadmor.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.dp
import com.tadmor.app.ui.theme.ExoColors
import com.tadmor.app.ui.theme.ExoTheme

/**
 * 6-pip data completeness indicator.
 * Shows how many of the 6 key parameters (mass, radius, temp, density, period, eccentricity)
 * are known vs. null. Matches the DATA AVAILABLE filter options.
 */
@Composable
fun DataCompletenessIndicator(
    completeness: Int,
    modifier: Modifier = Modifier,
) {
    val colors = ExoTheme.colors
    val filledColor = ExoColors.compositionTerra.text // green
    val emptyColor = colors.divider
    val isAccessible = ExoTheme.isAccessible
    val pipSize = if (isAccessible) 8.dp else 6.dp
    val pipGap = if (isAccessible) 4.dp else 3.dp

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = "DATA",
            style = ExoTheme.type.labelSmall.copy(color = colors.textTertiary),
        )
        Spacer(Modifier.width(5.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(pipGap)) {
            repeat(6) { index ->
                val color = if (index < completeness) filledColor else emptyColor
                Box(
                    modifier = Modifier
                        .size(pipSize)
                        .drawBehind {
                            drawCircle(color = color)
                        },
                )
            }
        }
    }
}
