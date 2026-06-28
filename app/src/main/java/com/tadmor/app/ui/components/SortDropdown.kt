package com.tadmor.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.tadmor.app.ui.theme.ExoTheme

enum class SortOption(val label: String) {
    DISCOVERY_DATE("Discovery date"),
    NAME("Name"),
    MASS("Mass"),
    RADIUS("Radius"),
    TEMPERATURE("Temperature"),
    DISTANCE("Distance"),
    PERIOD("Orbital period"),
    PLANET_COUNT("Planets in system"),
}

/**
 * Sort control per DESIGN.md Section 9.1.
 * Tappable label that opens an inline dropdown.
 */
@Composable
fun SortControl(
    currentSort: SortOption,
    ascending: Boolean,
    onSortChange: (SortOption, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        // Sort label
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val sortLabelInteraction = remember { MutableInteractionSource() }
            BasicText(
                text = "Sorted by: ${currentSort.label}",
                style = type.bodyMedium.copy(color = colors.textTertiary),
                modifier = Modifier
                    .pushOnPress(sortLabelInteraction)
                    .clickable(
                        indication = null,
                        interactionSource = sortLabelInteraction,
                        onClick = { expanded = !expanded },
                    ),
            )
            Spacer(Modifier.width(4.dp))
            // Arrow indicator — tap to toggle direction
            val arrowSize = if (ExoTheme.isAccessible) 14.dp else 10.dp
            SortArrow(
                ascending = ascending,
                modifier = Modifier
                    .size(arrowSize)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = { onSortChange(currentSort, !ascending) },
                    ),
            )
        }

        // Dropdown — expands/collapses with a vertical reveal + fade.
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(
                animationSpec = tween(240, easing = FastOutSlowInEasing),
            ) + fadeIn(tween(180, easing = FastOutSlowInEasing)),
            exit = shrinkVertically(
                animationSpec = tween(220, easing = FastOutSlowInEasing),
            ) + fadeOut(tween(160, easing = FastOutSlowInEasing)),
        ) {
            Column {
                Spacer(Modifier.height(ExoTheme.spacing.xs))
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.surfaceCard)
                        .border(1.dp, colors.divider, RoundedCornerShape(4.dp))
                        .padding(vertical = ExoTheme.spacing.xs),
                ) {
                    SortOption.entries.forEach { option ->
                        val isSelected = option == currentSort
                        val isAccessible = ExoTheme.isAccessible
                        val rowHPad = if (isAccessible) 18.dp else 14.dp
                        val rowVPad = if (isAccessible) 12.dp else 8.dp
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                    onClick = {
                                        if (isSelected) {
                                            onSortChange(option, !ascending)
                                        } else {
                                            onSortChange(option, false) // default descending
                                        }
                                        expanded = false
                                    },
                                )
                                .padding(horizontal = rowHPad, vertical = rowVPad),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BasicText(
                                text = option.label,
                                style = type.bodyLarge.copy(
                                    color = if (isSelected) colors.accentGold else colors.textSecondary,
                                ),
                            )
                            if (isSelected) {
                                Spacer(Modifier.weight(1f))
                                SortArrow(
                                    ascending = ascending,
                                    modifier = Modifier.size(10.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SortArrow(
    ascending: Boolean,
    modifier: Modifier = Modifier,
) {
    val color = ExoTheme.colors.textTertiary
    // Canonical orientation: arrow head at bottom. Rotating to -180° flips it
    // to point up (counter-clockwise); rotating back to 0° spins clockwise.
    val target = if (ascending) -180f else 0f
    val rotation = remember { Animatable(target) }
    LaunchedEffect(ascending) {
        rotation.animateTo(target, tween(260, easing = FastOutSlowInEasing))
    }
    Box(
        modifier = modifier
            .rotate(rotation.value)
            .drawBehind {
                val sw = 1.2.dp.toPx()
                val cx = size.width / 2f
                // Vertical line
                drawLine(color, Offset(cx, 0f), Offset(cx, size.height), sw, cap = StrokeCap.Round)
                // Arrow head at bottom (descending orientation)
                drawLine(color, Offset(0f, size.height * 0.65f), Offset(cx, size.height), sw, cap = StrokeCap.Round)
                drawLine(color, Offset(size.width, size.height * 0.65f), Offset(cx, size.height), sw, cap = StrokeCap.Round)
            },
    )
}
