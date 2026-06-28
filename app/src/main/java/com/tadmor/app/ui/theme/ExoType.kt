package com.tadmor.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * All text style tokens from DESIGN.md Section 3.
 * Never hardcode font sizes or weights — always reference ExoTheme.type.
 */
@Immutable
data class ExoType(
    // DESIGN.md Section 3.2 — Type Scale
    val displayLarge: TextStyle = TextStyle(
        fontFamily = JostFontFamily,
        fontWeight = FontWeight.W200,
        fontSize = 28.sp,
        letterSpacing = (-0.3).sp,
    ),
    val displayMedium: TextStyle = TextStyle(
        fontFamily = JostFontFamily,
        fontWeight = FontWeight.W200,
        fontSize = 22.sp,
        letterSpacing = (-0.2).sp,
    ),
    val titleMedium: TextStyle = TextStyle(
        fontFamily = JostFontFamily,
        fontWeight = FontWeight.W300,
        fontSize = 15.sp,
        letterSpacing = 0.3.sp,
    ),
    val bodyLarge: TextStyle = TextStyle(
        fontFamily = JostFontFamily,
        fontWeight = FontWeight.W300,
        fontSize = 14.sp,
        letterSpacing = 0.2.sp,
    ),
    val bodyMedium: TextStyle = TextStyle(
        fontFamily = JostFontFamily,
        fontWeight = FontWeight.W300,
        fontSize = 13.sp,
        letterSpacing = 0.sp,
    ),
    val labelLarge: TextStyle = TextStyle(
        fontFamily = JostFontFamily,
        fontWeight = FontWeight.W400,
        fontSize = 11.sp,
        letterSpacing = 0.8.sp,
    ),
    val labelMedium: TextStyle = TextStyle(
        fontFamily = JostFontFamily,
        fontWeight = FontWeight.W400,
        fontSize = 10.sp,
        letterSpacing = 1.0.sp,
    ),
    val labelSmall: TextStyle = TextStyle(
        fontFamily = JostFontFamily,
        fontWeight = FontWeight.W400,
        fontSize = 9.5.sp,
        letterSpacing = 1.5.sp,
    ),
    val navLabel: TextStyle = TextStyle(
        fontFamily = JostFontFamily,
        fontWeight = FontWeight.W400,
        fontSize = 9.sp,
        letterSpacing = 2.0.sp,
    ),
) {
    companion object {
        fun accessible(): ExoType = ExoType(
            displayLarge = TextStyle(
                fontFamily = JostFontFamily,
                fontWeight = FontWeight.W200,
                fontSize = 36.sp,
                letterSpacing = (-0.2).sp,
            ),
            displayMedium = TextStyle(
                fontFamily = JostFontFamily,
                fontWeight = FontWeight.W200,
                fontSize = 28.sp,
                letterSpacing = (-0.1).sp,
            ),
            titleMedium = TextStyle(
                fontFamily = JostFontFamily,
                fontWeight = FontWeight.W300,
                fontSize = 19.sp,
                letterSpacing = 0.2.sp,
            ),
            bodyLarge = TextStyle(
                fontFamily = JostFontFamily,
                fontWeight = FontWeight.W300,
                fontSize = 18.sp,
                letterSpacing = 0.1.sp,
            ),
            bodyMedium = TextStyle(
                fontFamily = JostFontFamily,
                fontWeight = FontWeight.W300,
                fontSize = 17.sp,
                letterSpacing = 0.sp,
            ),
            labelLarge = TextStyle(
                fontFamily = JostFontFamily,
                fontWeight = FontWeight.W400,
                fontSize = 14.sp,
                letterSpacing = 0.6.sp,
            ),
            labelMedium = TextStyle(
                fontFamily = JostFontFamily,
                fontWeight = FontWeight.W400,
                fontSize = 13.sp,
                letterSpacing = 0.7.sp,
            ),
            labelSmall = TextStyle(
                fontFamily = JostFontFamily,
                fontWeight = FontWeight.W400,
                fontSize = 12.sp,
                letterSpacing = 1.0.sp,
            ),
            navLabel = TextStyle(
                fontFamily = JostFontFamily,
                fontWeight = FontWeight.W400,
                fontSize = 11.5.sp,
                letterSpacing = 1.5.sp,
            ),
        )
    }
}
