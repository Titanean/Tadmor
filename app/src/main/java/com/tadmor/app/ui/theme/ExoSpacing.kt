package com.tadmor.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * All spacing tokens from DESIGN.md Section 4.
 * Never hardcode dp values — always reference ExoTheme.spacing.
 */
@Immutable
data class ExoSpacing(
    val xs: Dp = 3.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 18.dp,
    val xxl: Dp = 20.dp,
    val xxxl: Dp = 24.dp,
    val xxxxl: Dp = 32.dp,
) {
    companion object {
        fun accessible(): ExoSpacing = ExoSpacing(
            xs = 4.dp,
            sm = 10.dp,
            md = 16.dp,
            lg = 20.dp,
            xl = 22.dp,
            xxl = 26.dp,
            xxxl = 30.dp,
            xxxxl = 40.dp,
        )
    }
}
