package com.tadmor.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.tadmor.app.ui.theme.ExoTheme

/**
 * Disclaimer label per DESIGN.md Section 5.6.
 * Semi-transparent background, anchored bottom-left on GL views.
 */
@Composable
fun DisclaimerLabel(
    modifier: Modifier = Modifier,
) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type
    val shape = RoundedCornerShape(4.dp)

    Box(
        modifier = modifier
            .clip(shape)
            .background(colors.background.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        BasicText(
            text = "Speculative visualisation \u2014 not based on direct observation",
            style = type.labelLarge.copy(color = colors.textTertiary),
        )
    }
}
