package com.tadmor.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

private val LocalExoColors = staticCompositionLocalOf { ExoColors() }
private val LocalExoType = staticCompositionLocalOf { ExoType() }
private val LocalExoSpacing = staticCompositionLocalOf { ExoSpacing() }
private val LocalAccessibleMode = staticCompositionLocalOf { false }

/**
 * Height of the bottom navigation bar, measured at the app root.
 * Screens read this to pad their scrollable/bottom-anchored content so it
 * isn't hidden behind the nav. Not in [ExoTheme] because it's a layout
 * inset, not a design token.
 */
val LocalBottomBarHeight = compositionLocalOf { 0.dp }

/**
 * Theme wrapper providing design system tokens throughout the Compose tree.
 * Usage: ExoTheme.colors, ExoTheme.type, ExoTheme.spacing, ExoTheme.isAccessible
 *
 * See DESIGN.md Section 10.3 for wiring pattern.
 */
object ExoTheme {
    val colors: ExoColors
        @Composable @ReadOnlyComposable
        get() = LocalExoColors.current

    val type: ExoType
        @Composable @ReadOnlyComposable
        get() = LocalExoType.current

    val spacing: ExoSpacing
        @Composable @ReadOnlyComposable
        get() = LocalExoSpacing.current

    val isAccessible: Boolean
        @Composable @ReadOnlyComposable
        get() = LocalAccessibleMode.current
}

@Composable
fun ExoTheme(
    colors: ExoColors = ExoColors(),
    type: ExoType = ExoType(),
    spacing: ExoSpacing = ExoSpacing(),
    isAccessible: Boolean = false,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalExoColors provides colors,
        LocalExoType provides type,
        LocalExoSpacing provides spacing,
        LocalAccessibleMode provides isAccessible,
        content = content,
    )
}
