package com.tadmor.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * All colour tokens from DESIGN.md Section 2.
 * Never use raw hex colours in composables — always reference ExoTheme.colors.
 */
@Immutable
data class ExoColors(
    // Core palette (DESIGN.md Section 2.1)
    val background: Color = Color(0xFF06080F),
    val surfaceCard: Color = Color(0xFF0C0F18),
    val surfaceBorder: Color = Color(0xFF121622),
    val surfaceBorderHalf: Color = Color(0x80121622), // 50% opacity
    val surfaceRaised: Color = Color(0xFF151929),
    val surfaceInput: Color = Color(0xFF0E1220),
    val divider: Color = Color(0xFF1A2035),

    val textPrimary: Color = Color(0xFFC8D0DC),
    val textSecondary: Color = Color(0xFF97A1B3),
    val textTertiary: Color = Color(0xFF6B7A8F),
    val textMuted: Color = Color(0xFF556275),

    val accentGold: Color = Color(0xFFB89660),
    val accentGoldDim: Color = Color(0xFF8A7048),
    val accentGoldSubtle: Color = Color(0x12B89660), // 7% opacity
    val accentGoldBorder: Color = Color(0xFF2A1F35),

    // Candidate-disposition tokens (Phase 10). Picked to read clearly
    // against [surfaceCard] / [background] without competing with the
    // gold accent used for confirmed planets, since users browse all
    // three dispositions side-by-side in catalog sub-tabs.
    val accentCandidate: Color = Color(0xFFB0B8C4),       // light gray
    val accentCandidateSubtle: Color = Color(0x12B0B8C4), // 7% opacity
    val accentCandidateBorder: Color = Color(0xFF2A2F3A),
    val accentFalsePositive: Color = Color(0xFFC25A4A),       // muted red
    val accentFalsePositiveSubtle: Color = Color(0x12C25A4A), // 7% opacity
    val accentFalsePositiveBorder: Color = Color(0xFF3A1F1B),

    // Notification accent (Phase 11.2) — saturated red used for the
    // unread-update badge on the bookmark filter button. Distinct from
    // [accentFalsePositive] (muted red, "this is wrong") so the badge
    // reads as "new attention needed" rather than as a negative outcome.
    val accentNotification: Color = Color(0xFFD45050),
) {
    companion object {
        // Bulk composition badge colours (DESIGN.md Section 2.2, SpaceEngine system)
        val compositionTerra = ClassificationColor(Color(0xFF7AB89E))
        val compositionNeptune = ClassificationColor(Color(0xFF5A82B8))
        val compositionJupiter = ClassificationColor(Color(0xFFC4886A))

        // Temperature class colours (DESIGN.md Section 2.2)
        val tempFrigid = Color(0xFF9EB8D4)
        val tempCold = Color(0xFF7A98B0)
        val tempCool = Color(0xFFB89660)
        val tempTemperate = Color(0xFF7AB89E)
        val tempWarm = Color(0xFFC4B078)
        val tempHot = Color(0xFFD46A4A)
        val tempTorrid = Color(0xFFD44A4A)

        // Spectral type star colours (DESIGN.md Section 2.3)
        val spectralO = Color(0xFF9BB0FF)
        val spectralB = Color(0xFFAABFFF)
        val spectralA = Color(0xFFCAD7FF)
        val spectralF = Color(0xFFF8F7FF)
        val spectralG = Color(0xFFFFF4EA)
        val spectralK = Color(0xFFFFD2A1)
        val spectralM = Color(0xFFFFB56C)
        val spectralLTY = Color(0xFFC45030)
    }
}

/**
 * Bundles a classification type's text, background (12% opacity),
 * and border (20% opacity) colours.
 */
@Immutable
data class ClassificationColor(val text: Color) {
    val background: Color get() = text.copy(alpha = 0.07f)
    val border: Color get() = text.copy(alpha = 0.12f)
}
