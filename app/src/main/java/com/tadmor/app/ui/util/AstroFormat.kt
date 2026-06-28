package com.tadmor.app.ui.util

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import com.tadmor.domain.model.UserSettings

/**
 * Formatting helpers for astronomical unit symbols.
 *
 * Values like "5.2 M⊕", "1.1 R♃", "0.9 L☉" should render the trailing symbol
 * as a subscript under the base letter. astroAnnotated() detects the
 * base+symbol pattern at the end of a string and applies the subscript style.
 */
object AstroFormat {

    const val SOLAR: String = "☉"

    fun earth(settings: UserSettings): String = if (settings.useEarthSymbol) "⊕" else "E"
    fun jupiter(settings: UserSettings): String = if (settings.useJupiterSymbol) "♃" else "J"

    private const val BASE_CHARS = "MRLS"
    private const val SYMBOL_CHARS = "⊕♃☉EJ"

    /**
     * Wrap any base+symbol unit pair in [text] with a subscript span. A pair is
     * recognized when a base letter (M/R/L/S) is immediately followed by a
     * symbol char (⊕/♃/☉/E/J) that itself sits at a word boundary (end of
     * string or non-alphanumeric next char). A hair space is inserted before
     * the subscript so the symbol doesn't kiss the base letter.
     */
    fun astroAnnotated(text: String): AnnotatedString {
        if (text.length < 2) return AnnotatedString(text)
        val subscriptStyle = SpanStyle(
            fontSize = 0.72f.em,
            baselineShift = BaselineShift(-0.28f),
        )
        return buildAnnotatedString {
            var i = 0
            while (i < text.length) {
                val c = text[i]
                val prev = if (i > 0) text[i - 1] else ' '
                val atBoundary = i == text.length - 1 || !text[i + 1].isLetterOrDigit()
                if (prev in BASE_CHARS && c in SYMBOL_CHARS && atBoundary) {
                    append('\u2009')
                    withStyle(subscriptStyle) { append(c.toString()) }
                } else {
                    append(c)
                }
                i++
            }
        }
    }
}
