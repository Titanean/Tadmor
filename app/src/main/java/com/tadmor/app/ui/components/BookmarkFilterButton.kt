package com.tadmor.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tadmor.app.ui.theme.ExoTheme

/**
 * Catalog-header button that toggles the "saved-only" filter. Companion
 * to [ExoFilterButton] — same square icon-button footprint, sits to its
 * right.
 *
 * Visual states match the active-filter accent system:
 * - Inactive: hollow [BookmarkIcon] in [textSecondary], `surfaceRaised`
 *   bg, `divider` outline
 * - Active: solid-filled [BookmarkIcon] in `accentGold`, `accentGoldSubtle`
 *   bg, `accentGoldBorder` outline
 *
 * When [unreadCount] > 0, a saturated red badge ([accentNotification])
 * with the count appears at the top-right corner. Used to surface the
 * total number of bookmarked planets with pending parameter updates.
 */
@Composable
fun BookmarkFilterButton(
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    unreadCount: Int = 0,
) {
    val colors = ExoTheme.colors
    val shape = RoundedCornerShape(20.dp)

    // Animate every accent property — bg, border, and icon colour all flip
    // together as the user toggles the filter. Same 220ms `FastOutSlowInEasing`
    // curve used elsewhere in the app for state transitions, so the bookmark
    // toggle feels consistent with the catalog tab bar text-colour fade and
    // settings option-row press animations.
    val animSpec = tween<Color>(220, easing = FastOutSlowInEasing)
    val backgroundColor by animateColorAsState(
        targetValue = if (isActive) colors.accentGoldSubtle else colors.surfaceRaised,
        animationSpec = animSpec,
        label = "bookmarkBg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (isActive) colors.accentGoldBorder else colors.divider,
        animationSpec = animSpec,
        label = "bookmarkBorder",
    )
    val iconColor by animateColorAsState(
        targetValue = if (isActive) colors.accentGold else colors.textSecondary,
        animationSpec = animSpec,
        label = "bookmarkIcon",
    )

    val isAccessible = ExoTheme.isAccessible
    val buttonSize = if (isAccessible) 52.dp else 44.dp
    val iconSize = if (isAccessible) 22.dp else 18.dp

    Box(
        modifier = modifier.size(buttonSize),
    ) {
        Box(
            modifier = Modifier
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
            // Crossfade between hollow and filled icon variants on toggle.
            // Both render at the same animated [iconColor] so the colour
            // and fill transitions are visually unified — looks like the
            // icon "fills in" rather than "swaps out".
            Crossfade(
                targetState = isActive,
                animationSpec = tween(220, easing = FastOutSlowInEasing),
                label = "bookmarkFill",
            ) { active ->
                BookmarkIcon(
                    color = iconColor,
                    filled = active,
                    modifier = Modifier.size(iconSize),
                )
            }
        }
        // Red badge at top-right. Fades and scales in/out so the count
        // appearing after a sync feels like an "arrival" instead of a snap.
        AnimatedVisibility(
            visible = unreadCount > 0,
            enter = fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.6f),
            exit = fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 0.6f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 4.dp, y = (-4).dp),
        ) {
            val badgeShape = RoundedCornerShape(50)
            val badgeMinSize = if (isAccessible) 18.dp else 14.dp
            Box(
                modifier = Modifier
                    .sizeIn(minWidth = badgeMinSize, minHeight = badgeMinSize)
                    .clip(badgeShape)
                    .background(colors.accentNotification)
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = unreadCount.toString(),
                    style = ExoTheme.type.labelSmall.copy(
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
        }
    }
}
