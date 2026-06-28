package com.tadmor.app.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.tadmor.app.ui.theme.ExoTheme

/**
 * Search bar per DESIGN.md Section 5.3.
 */
@Composable
fun ExoSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search...",
    isActive: Boolean = true,
) {
    val colors = ExoTheme.colors
    val type = ExoTheme.type
    val shape = RoundedCornerShape(4.dp)

    val isAccessible = ExoTheme.isAccessible
    val barHeight = if (isAccessible) 52.dp else 44.dp
    val iconSize = if (isAccessible) 20.dp else 16.dp

    val focusManager = LocalFocusManager.current
    var isFocused by remember { mutableStateOf(false) }

    // Back press clears focus (dismisses keyboard) instead of navigating away
    // Only active when the parent tab is visible, so hidden tabs don't steal back presses
    BackHandler(enabled = isFocused && isActive) {
        focusManager.clearFocus()
    }

    // Outer Box wraps the Row so the persistent X-ripple overlay can be a
    // sibling at a fixed position. The overlay always exists (regardless of
    // whether the visible X icon is mounted), so its in-flight ripples keep
    // animating after `query` clears and the X icon unmounts.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight)
            .clip(shape)
            .background(colors.surfaceInput)
            .border(1.dp, colors.divider, shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                // Only the start side gets the standard 14 dp inset — the
                // end side is owned by the visible X wrapper (which carries
                // its own 14 dp on the X icon) so that wrapper, and the
                // matching ripple hitbox, can extend all the way to the
                // bar's outer right edge.
                .padding(start = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Inner Row with the wide bar ripple — covers everything except
            // the X area. Decoration is non-consuming so child taps still
            // reach the text field. White reads as a lighter tint on the
            // dark surfaceInput. Disabled while focused so an already-active
            // field doesn't ripple on re-tap.
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .touchRippleDecoration(
                        color = Color.White,
                        startAlpha = 0.18f,
                        enabled = !isFocused,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchIcon(modifier = Modifier.size(iconSize))

                Spacer(Modifier.width(10.dp))

                Box(modifier = Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        BasicText(
                            text = placeholder,
                            style = type.bodyLarge.copy(color = colors.textMuted),
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        textStyle = type.bodyLarge.copy(color = colors.textPrimary),
                        singleLine = true,
                        cursorBrush = SolidColor(colors.accentGold),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { isFocused = it.isFocused },
                    )
                }
            }

            // Visible X icon, sitting at the bar's trailing inner padding
            // inside a `barHeight × barHeight` square wrapper that extends
            // all the way to the bar's outer right edge. The X icon itself
            // carries an internal `padding(end = 14.dp)` so its visible
            // strokes land exactly where they did before the hitbox grew.
            // The wrapper claims `barHeight` in the row layout, which
            // pushes the inner row's right edge back by `barHeight` so the
            // wide-bar decoration ripple and the X ripple never overlap.
            if (query.isNotEmpty()) {
                Box(
                    modifier = Modifier.size(barHeight),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Box(
                        modifier = Modifier
                            .padding(end = 14.dp)
                            .size(iconSize)
                            .drawBehind {
                                val sw = 1.2.dp.toPx()
                                val pad = size.width * 0.25f
                                drawLine(
                                    colors.textMuted,
                                    Offset(pad, pad),
                                    Offset(size.width - pad, size.height - pad),
                                    sw,
                                    cap = StrokeCap.Round,
                                )
                                drawLine(
                                    colors.textMuted,
                                    Offset(size.width - pad, pad),
                                    Offset(pad, size.height - pad),
                                    sw,
                                    cap = StrokeCap.Round,
                                )
                            },
                    )
                }
            }
        }

        // Persistent X-ripple hitbox — `barHeight × barHeight` square flush
        // with the bar's outer right edge (no end padding), exactly aligned
        // with the visible X wrapper above. The ripple fills the full
        // height of the bar AND extends to the bar's true right edge
        // (clipped by the outer rounded corners where it meets them).
        // RectangleShape confines the expanding circle to the square
        // footprint. Always mounted so in-flight ripples keep animating
        // even after `query` clears and the visible wrapper unmounts.
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(barHeight)
                .clip(RectangleShape)
                .touchRipple(
                    color = Color.White,
                    startAlpha = 0.22f,
                    enabled = query.isNotEmpty(),
                    onClick = {
                        onQueryChange("")
                        focusManager.clearFocus()
                    },
                ),
        )
    }
}

@Composable
private fun SearchIcon(modifier: Modifier = Modifier) {
    val color = ExoTheme.colors.textTertiary
    Box(
        modifier = modifier.drawBehind {
            val r = size.minDimension * 0.35f
            val cx = size.width * 0.4f
            val cy = size.height * 0.4f
            drawCircle(
                color = color,
                radius = r,
                center = Offset(cx, cy),
                style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round),
            )
            val handleStart = Offset(
                cx + r * 0.707f,
                cy + r * 0.707f,
            )
            val handleEnd = Offset(size.width * 0.9f, size.height * 0.9f)
            drawLine(
                color = color,
                start = handleStart,
                end = handleEnd,
                strokeWidth = 1.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
        },
    )
}
