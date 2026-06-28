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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tadmor.app.ui.theme.ExoTheme
import com.tadmor.domain.model.Disposition

/**
 * Static disposition badge mirroring [ClassificationBadge]'s footprint so
 * the two can sit side-by-side on the planet info page.
 *
 * No badge is shown for [Disposition.CONFIRMED] (default state — absence
 * of a badge is the "confirmed" signal). [Candidate]s and [false positive]s
 * each get their own [ExoColors] token trio.
 */
@Composable
fun DispositionBadge(
    disposition: Disposition,
    modifier: Modifier = Modifier,
) {
    if (disposition == Disposition.CONFIRMED) return

    val colors = ExoTheme.colors
    val shape = RoundedCornerShape(4.dp)

    val isAccessible = ExoTheme.isAccessible
    val hPad = if (isAccessible) 12.dp else 10.dp
    val vPad = if (isAccessible) 5.dp else 3.dp

    val (label, textColor, bgColor, borderColor) = when (disposition) {
        Disposition.CANDIDATE -> Quad(
            "Candidate", colors.accentCandidate, colors.accentCandidateSubtle, colors.accentCandidateBorder,
        )
        Disposition.FALSE_POSITIVE -> Quad(
            "False positive", colors.accentFalsePositive, colors.accentFalsePositiveSubtle, colors.accentFalsePositiveBorder,
        )
        Disposition.CONFIRMED -> return
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(bgColor)
            .border(1.dp, borderColor, shape)
            .padding(horizontal = hPad, vertical = vPad),
    ) {
        BasicText(
            text = label.uppercase(),
            style = ExoTheme.type.labelMedium.copy(color = textColor),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private data class Quad(
    val label: String,
    val text: Color,
    val background: Color,
    val border: Color,
)
