package com.tadmor.app.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.ln
import kotlin.math.pow

/**
 * Converts stellar effective temperature (Kelvin) to an approximate sRGB colour
 * via Tanner Helland's blackbody approximation, which closely matches
 * D65-relative chromaticity for typical stellar Teff ranges (2000–40000 K).
 */
object TeffColor {

    fun fromTeff(teffK: Double): Color {
        val temp = (teffK / 100.0).coerceIn(10.0, 400.0)

        // Red channel
        val r = if (temp <= 66) {
            255.0
        } else {
            (329.698727446 * (temp - 60).pow(-0.1332047592)).coerceIn(0.0, 255.0)
        }

        // Green channel
        val g = if (temp <= 66) {
            (99.4708025861 * ln(temp) - 161.1195681661).coerceIn(0.0, 255.0)
        } else {
            (288.1221695283 * (temp - 60).pow(-0.0755148492)).coerceIn(0.0, 255.0)
        }

        // Blue channel
        val b = when {
            temp >= 66 -> 255.0
            temp <= 19 -> 0.0
            else -> (138.5177312231 * ln(temp - 10) - 305.0447927307).coerceIn(0.0, 255.0)
        }

        return Color(
            red = (r / 255.0).toFloat(),
            green = (g / 255.0).toFloat(),
            blue = (b / 255.0).toFloat(),
        )
    }

    /** Representative Teff for each spectral class letter. */
    fun fromSpectralClass(spectralClass: String): Color = when (spectralClass.uppercase()) {
        "O" -> fromTeff(35000.0)
        "B" -> fromTeff(15000.0)
        "A" -> fromTeff(8500.0)
        "F" -> fromTeff(6750.0)
        "G" -> fromTeff(5600.0)
        "K" -> fromTeff(4400.0)
        "M" -> fromTeff(3000.0)
        "L" -> fromTeff(1800.0)
        "T" -> fromTeff(1200.0)
        // Y dwarfs (sub-brown-dwarfs, ~250–500 K) are below the blackbody
        // table's bottom (1000 K) — at those temps actual visual emission
        // is essentially zero. Use a hand-picked red that is further down
        // the spectrum from the preceding L and T dwarfs.
        "Y" -> Color(0xFFD91200)
        "WD", "D" -> Color(0xFFCAD7FF) // pale blue-white for white dwarfs
        // "Q" is our internal designation for pulsars / neutron stars
        // (not a real MK class). Surface temps run ~10⁶ K, well off the
        // top of the blackbody table, so we use a hand-picked saturated
        // electric blue rather than a true Teff lookup.
        "Q" -> Color(0xFF7AAEFF)
        else -> fromTeff(2200.0)
    }

    /**
     * Resolves the best display color for a star, handling:
     * - White dwarfs: always pale blue-white regardless of Teff
     * - Teff available: blackbody approximation
     * - No Teff but spectral type available: representative color from spectral class
     */
    fun forStar(teffK: Double?, spectralType: String?): Color? {
        val spType = spectralType?.trim()?.uppercase()
        // White dwarfs get a fixed pale blue-white regardless of Teff
        if (spType != null && spType.length >= 2 && spType.startsWith("D") && spType[1] in "ABCOZQ") {
            return fromSpectralClass("WD")
        }
        // Pulsars / neutron stars (synthetic "Q" class — see fromSpectralClass)
        // resolve to the harsh-blue palette regardless of any Teff slip-through.
        if (spType != null && spType.startsWith("Q")) return fromSpectralClass("Q")
        // Y dwarfs (sub-brown-dwarfs) sit below the blackbody-table floor —
        // any catalog Teff (~250–500 K) would clamp to 1000 K and produce a
        // bright orange-red, which is the wrong perceptual cue. Override
        // with the hand-picked deep maroon when the spectral type names Y.
        if (spType != null && spType.startsWith("Y")) return fromSpectralClass("Y")
        // Use Teff if available
        if (teffK != null) return fromTeff(teffK)
        // Fall back to spectral class
        if (spType != null) {
            val first = spType.firstOrNull()
            if (first != null && first in "OBAFGKMLTY") return fromSpectralClass(first.toString())
        }
        return null
    }
}
