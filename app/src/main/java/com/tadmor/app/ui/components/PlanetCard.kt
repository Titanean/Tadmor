package com.tadmor.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.tadmor.app.ui.theme.ClassificationColor
import com.tadmor.app.ui.theme.ExoTheme
import com.tadmor.app.ui.util.AstroFormat
import com.tadmor.domain.classification.visual.PlanetIconProfile

/**
 * Planet card per DESIGN.md Section 5.1.
 * Horizontal card: thumbnail, name/subtitle, classification badge, stat row.
 * Tap to expand and show full parameter detail.
 */
@Composable
fun PlanetCard(
    planetName: String,
    spectralType: String?,
    starDistance: String?,
    classificationLabel: String,
    classificationColor: ClassificationColor,
    iconProfile: PlanetIconProfile,
    mass: String?,
    radius: String?,
    temp: String?,
    period: String?,
    modifier: Modifier = Modifier,
    isExpanded: Boolean = false,
    onClick: () -> Unit = {},
    dataCompleteness: Int = 0,
    showDataIndicator: Boolean = true,
    spectralColor: Color = Color.Unspecified,
    // Expanded detail fields
    eccentricity: String? = null,
    inclination: String? = null,
    semiMajorAxis: String? = null,
    density: String? = null,
    discoveryMethod: String? = null,
    discoveryYear: String? = null,
    onViewSystem: (() -> Unit)? = null,
    isBookmarked: Boolean = false,
    onBookmarkToggle: (() -> Unit)? = null,
    /** Number of bookmarked-vs-current parameter changes for this planet,
     *  or 0 if not bookmarked / no changes. Drives the gold left edge and
     *  replaces the data-availability indicator with "n updated" text. */
    updateCount: Int = 0,
) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type
    val spacing = ExoTheme.spacing
    val cardShape = RoundedCornerShape(4.dp)

    val hasUpdates = updateCount > 0
    val goldEdgeColor = colors.accentGold
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(colors.surfaceCard)
            .touchRipple(
                color = Color.White,
                startAlpha = 0.10f,
                onClick = onClick,
            )
            .border(1.dp, colors.surfaceBorderHalf, cardShape)
            .drawBehind {
                // Thin gold left edge — glance signal that this bookmarked
                // planet has parameter updates pending review. Drawn on top
                // of the surface fill (drawBehind paints after preceding
                // draw modifiers, before children).
                if (hasUpdates) {
                    drawRect(
                        color = goldEdgeColor,
                        topLeft = Offset.Zero,
                        size = Size(2.dp.toPx(), size.height),
                    )
                }
            }
            .padding(vertical = spacing.lg, horizontal = spacing.xl),
    ) {
        Column {
            if (ExoTheme.isAccessible) {
                // Accessible layout: thumbnail + name/badge top row,
                // subtitle full-width, stat row full-width underneath
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlanetIcon(
                        profile = iconProfile,
                        size = 52.dp,
                    )
                    Spacer(Modifier.width(if (ExoTheme.isAccessible) 18.dp else 14.dp))
                    Column {
                        BasicText(
                            text = planetName,
                            style = type.titleMedium.copy(color = colors.textPrimary),
                        )
                        Spacer(Modifier.height(spacing.xs))
                        ClassificationBadge(
                            label = classificationLabel,
                            classificationColor = classificationColor,
                        )
                    }
                }

                Spacer(Modifier.height(spacing.sm))
                SubtitleRow(
                    spectralType = spectralType,
                    starDistance = starDistance,
                    spectralColor = spectralColor,
                    dataCompleteness = dataCompleteness,
                    showDataIndicator = showDataIndicator,
                    updateCount = updateCount,
                )

                Spacer(Modifier.height(spacing.md))
                StatRow(
                    mass = mass,
                    radius = radius,
                    temp = temp,
                    period = period,
                )
            } else {
                // Standard layout: thumbnail left, name+badge+subtitle+stats right
                Row(verticalAlignment = Alignment.Top) {
                    PlanetIcon(
                        profile = iconProfile,
                        size = 52.dp,
                    )

                    Spacer(Modifier.width(if (ExoTheme.isAccessible) 18.dp else 14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        // Top row: planet name + badge
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BasicText(
                                text = planetName,
                                style = type.titleMedium.copy(color = colors.textPrimary),
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Spacer(Modifier.width(spacing.sm))
                            ClassificationBadge(
                                label = classificationLabel,
                                classificationColor = classificationColor,
                            )
                        }

                        Spacer(Modifier.height(3.dp))
                        SubtitleRow(
                            spectralType = spectralType,
                            starDistance = starDistance,
                            spectralColor = spectralColor,
                            dataCompleteness = dataCompleteness,
                            showDataIndicator = showDataIndicator,
                            updateCount = updateCount,
                        )

                        // Stat row
                        Spacer(Modifier.height(spacing.md))
                        StatRow(
                            mass = mass,
                            radius = radius,
                            temp = temp,
                            period = period,
                        )
                    }
                }
            }

            // Expanded detail section. Duration + easing matched to the
            // LazyColumn's `animateItem` placement spec (260ms,
            // FastOutSlowInEasing) used by the catalog screen so that
            // cards below this one slide into their new positions in
            // sync with this card's height growth — otherwise the
            // expanded content visibly "outruns" the panel when there
            // are items below.
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(tween(260, easing = FastOutSlowInEasing)),
                exit = shrinkVertically(tween(260, easing = FastOutSlowInEasing)),
            ) {
                Column {
                    Spacer(Modifier.height(spacing.md))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(colors.divider),
                    )
                    Spacer(Modifier.height(spacing.md))

                    // Additional parameters in two columns
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            DetailRow("Density", density)
                            DetailRow("Eccentricity", eccentricity)
                            DetailRow("Inclination", inclination)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            DetailRow("Semi-major axis", semiMajorAxis)
                            DetailRow("Method", discoveryMethod)
                            DetailRow("Year", discoveryYear)
                        }
                    }

                    // Bottom row: bookmark toggle on the left, "View in System >"
                    // on the right. Both use push-only feedback (no ripple) so
                    // the symmetric pair behaves identically. Either side may
                    // be hidden via its callback being null.
                    if (onViewSystem != null || onBookmarkToggle != null) {
                        Spacer(Modifier.height(spacing.md))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Bookmark toggle (left). Crossfade the entire
                            // icon + label row on state change so the
                            // hollow→filled icon swap and "Save"/"Saved"
                            // label flip both fade together at 220ms,
                            // matching the BookmarkFilterButton's polish.
                            if (onBookmarkToggle != null) {
                                val bookmarkInteraction = remember { MutableInteractionSource() }
                                Row(
                                    modifier = Modifier
                                        .pushOnPress(bookmarkInteraction)
                                        .clickable(
                                            indication = null,
                                            interactionSource = bookmarkInteraction,
                                            onClick = onBookmarkToggle,
                                        ),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    androidx.compose.animation.Crossfade(
                                        targetState = isBookmarked,
                                        animationSpec = tween(220),
                                        label = "cardBookmarkToggle",
                                    ) { saved ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            BookmarkIcon(
                                                color = colors.accentGold,
                                                filled = saved,
                                                modifier = Modifier.size(14.dp),
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            BasicText(
                                                text = if (saved) "Saved" else "Save",
                                                style = type.labelLarge.copy(color = colors.accentGold),
                                            )
                                        }
                                    }
                                }
                            } else {
                                Spacer(Modifier.width(0.dp))
                            }

                            // View in System (right)
                            if (onViewSystem != null) {
                                val viewSysInteraction = remember { MutableInteractionSource() }
                                Row(
                                    modifier = Modifier
                                        .pushOnPress(viewSysInteraction)
                                        .clickable(
                                            indication = null,
                                            interactionSource = viewSysInteraction,
                                            onClick = onViewSystem,
                                        ),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    BasicText(
                                        text = "View in System",
                                        style = type.labelLarge.copy(color = colors.accentGold),
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    // Chevron right
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .drawBehind {
                                                val sw = 1.2.dp.toPx()
                                                val left = size.width * 0.3f
                                                val right = size.width * 0.7f
                                                val mid = size.height / 2f
                                                val top = size.height * 0.2f
                                                val bot = size.height * 0.8f
                                                drawLine(colors.accentGold, Offset(left, top), Offset(right, mid), sw, cap = StrokeCap.Round)
                                                drawLine(colors.accentGold, Offset(right, mid), Offset(left, bot), sw, cap = StrokeCap.Round)
                                            },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String?) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type
    val isEstimated = value?.startsWith("~") == true
    val displayValue = if (isEstimated) value?.removePrefix("~") else value
    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = "$label: ",
            style = type.labelLarge.copy(color = colors.textMuted),
        )
        BasicText(
            text = AstroFormat.astroAnnotated(displayValue ?: "—"),
            style = type.bodyMedium.copy(
                color = if (value != null) colors.textSecondary else colors.textTertiary,
                fontStyle = if (isEstimated) FontStyle.Italic else FontStyle.Normal,
            ),
        )
    }
}

@Composable
private fun SubtitleRow(
    spectralType: String?,
    starDistance: String?,
    spectralColor: Color,
    dataCompleteness: Int,
    showDataIndicator: Boolean = true,
    updateCount: Int = 0,
) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type
    val spacing = ExoTheme.spacing

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f, fill = false),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val hasSpectral = spectralType != null
            val hasDistance = starDistance != null
            val isEstimatedSpectral = spectralType?.startsWith("~") == true
            val displaySpectral = if (isEstimatedSpectral) spectralType?.removePrefix("~") else spectralType
            if (hasSpectral) {
                BasicText(
                    text = displaySpectral!!,
                    style = type.labelLarge.copy(
                        color = if (spectralColor != Color.Unspecified) spectralColor
                        else colors.textTertiary,
                        fontStyle = if (isEstimatedSpectral) FontStyle.Italic else FontStyle.Normal,
                    ),
                )
            }
            if (hasSpectral && hasDistance) {
                BasicText(
                    text = " · ",
                    style = type.labelLarge.copy(color = colors.textTertiary),
                )
            }
            if (hasDistance) {
                BasicText(
                    text = starDistance!!,
                    style = type.labelLarge.copy(color = colors.textTertiary),
                )
            }
            if (!hasSpectral && !hasDistance) {
                BasicText(
                    text = "—",
                    style = type.labelLarge.copy(color = colors.textTertiary),
                )
            }
        }
        // Right-side slot: "n updated" gold text takes priority over the
        // data-availability indicator. When showDataIndicator is off and
        // there are no updates, the slot stays empty. The indicator
        // returns automatically once the user opens the planet info page
        // (consume-on-close clears the diff and updateCount drops to 0).
        when {
            updateCount > 0 -> {
                Spacer(Modifier.width(spacing.sm))
                BasicText(
                    text = "$updateCount updated",
                    style = type.labelLarge.copy(color = colors.accentGold),
                )
            }
            showDataIndicator -> {
                Spacer(Modifier.width(spacing.sm))
                DataCompletenessIndicator(completeness = dataCompleteness)
            }
        }
    }
}

@Composable
private fun StatRow(
    mass: String?,
    radius: String?,
    temp: String?,
    period: String?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        StatColumn("MASS", mass)
        StatColumn("RADIUS", radius)
        StatColumn("TEMP", temp)
        StatColumn("PERIOD", period)
    }
}

@Composable
private fun StatColumn(
    label: String,
    value: String?,
) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type
    val isEstimated = value?.startsWith("~") == true
    val displayValue = if (isEstimated) value?.removePrefix("~") else value

    Column {
        BasicText(
            text = label,
            style = type.labelLarge.copy(color = colors.textMuted),
        )
        Spacer(Modifier.height(2.dp))
        BasicText(
            text = AstroFormat.astroAnnotated(displayValue ?: "—"),
            style = type.bodyMedium.copy(
                color = if (value != null) colors.textSecondary else colors.textTertiary,
                fontStyle = if (isEstimated) FontStyle.Italic else FontStyle.Normal,
            ),
        )
    }
}
