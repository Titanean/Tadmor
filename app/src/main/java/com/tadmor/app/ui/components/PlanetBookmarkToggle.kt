package com.tadmor.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tadmor.app.ui.theme.ExoTheme

/**
 * Bookmark toggle used in the planet info page slab header. Uses
 * push-only feedback (no ripple) to match the rest of the slab's
 * interactive elements.
 *
 * Wraps the icon in [AnimatedContent] keyed on [planetKey] so that
 * cross-planet navigation (planet A → planet B) fades the bookmark
 * state in/out at 220ms instead of snapping. Bookmark toggles within
 * the same planet update the icon immediately — controlled via
 * `contentKey = { planetKey }` so AnimatedContent only treats planet
 * changes as a "transition", not state changes within the same planet.
 */
@Composable
fun PlanetBookmarkToggle(
    isBookmarked: Boolean,
    planetKey: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ExoTheme.colors
    val pressSource = remember { MutableInteractionSource() }
    val isAccessible = ExoTheme.isAccessible
    val tapTargetSize = if (isAccessible) 48.dp else 40.dp
    val iconSize = if (isAccessible) 24.dp else 20.dp

    Box(
        modifier = modifier
            .size(tapTargetSize)
            .pushOnPress(pressSource)
            .clickable(
                indication = null,
                interactionSource = pressSource,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = planetKey to isBookmarked,
            // contentKey makes AnimatedContent treat only planet-key
            // changes as a "transition" — same-planet bookmark toggles
            // update the icon directly without a fade.
            contentKey = { it.first },
            transitionSpec = {
                fadeIn(tween(220)).togetherWith(fadeOut(tween(220)))
            },
            label = "planetBookmark",
        ) { (_, bookmarked) ->
            BookmarkIcon(
                color = colors.accentGold,
                filled = bookmarked,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}
