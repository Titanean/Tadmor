package com.tadmor.domain.model

import com.tadmor.domain.classification.PlanetClassification
import com.tadmor.domain.classification.visual.VisualProfile
import kotlin.math.pow

/**
 * A star-centric aggregate: a host star and all its confirmed planets with classifications.
 *
 * [candidates] and [falsePositives] are populated only when the user has
 * opted into showing unconfirmed candidates (DATA setting). They use the
 * same [SystemPlanetEntry] shape but carry a non-CONFIRMED disposition.
 */
data class SystemDetail(
    val star: Star,
    val planets: List<SystemPlanetEntry>,
    val companionStars: List<Star> = emptyList(),
    val candidates: List<SystemPlanetEntry> = emptyList(),
    val falsePositives: List<SystemPlanetEntry> = emptyList(),
) {
    private companion object {
        const val SOLAR_TEFF = 5778.0
    }

    val isCircumbinary: Boolean get() = planets.any { it.planet.cbFlag }

    /** Sum of primary + companion masses for Kepler's generalized third law. */
    val combinedMassSolar: Double? get() {
        val primaryMass = star.massSolar ?: return null
        val companionMass = companionStars.firstOrNull()?.massSolar ?: return primaryMass
        return primaryMass + companionMass
    }

    /** Combined luminosity from all stars (linear, not log). */
    val combinedLuminosity: Double? get() {
        val primaryLum = starLuminosity(star) ?: return null
        val companionLum = companionStars.firstOrNull()?.let { starLuminosity(it) }
        return if (companionLum != null) primaryLum + companionLum else primaryLum
    }

    private fun starLuminosity(s: Star): Double? {
        // Use log luminosity if available
        s.logLuminosity?.let { return 10.0.pow(it) }
        // Estimate from Teff + radius: L/L☉ = (R/R☉)² × (T/T☉)⁴
        val teff = s.teffK ?: return null
        val radius = s.radiusSolar ?: return null
        return radius.pow(2) * (teff / SOLAR_TEFF).pow(4)
    }
}

data class SystemPlanetEntry(
    val planet: Planet,
    val classification: PlanetClassification,
    val dataCompleteness: Int,
    val visualProfile: VisualProfile,
    val disposition: Disposition = Disposition.CONFIRMED,
)
