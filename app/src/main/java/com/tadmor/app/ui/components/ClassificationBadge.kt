package com.tadmor.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tadmor.app.ui.theme.ClassificationColor
import com.tadmor.app.ui.theme.ExoTheme

/**
 * Classification badge per DESIGN.md Section 5.1:
 * - Background: classification colour at 12% opacity
 * - Border: 1dp, classification colour at 20% opacity
 * - Corner radius: 10dp
 * - Padding: 3dp vertical, 10dp horizontal
 * - Text: labelMedium, uppercase
 */
@Composable
fun ClassificationBadge(
    label: String,
    classificationColor: ClassificationColor,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(4.dp)

    val isAccessible = ExoTheme.isAccessible
    val hPad = if (isAccessible) 12.dp else 10.dp
    val vPad = if (isAccessible) 5.dp else 3.dp

    Box(
        modifier = modifier
            .clip(shape)
            .background(classificationColor.background)
            .border(1.dp, classificationColor.border, shape)
            .padding(horizontal = hPad, vertical = vPad),
    ) {
        BasicText(
            text = label.uppercase(),
            style = ExoTheme.type.labelMedium.copy(color = classificationColor.text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
