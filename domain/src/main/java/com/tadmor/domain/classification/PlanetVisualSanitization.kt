package com.tadmor.domain.classification

import com.tadmor.domain.model.Planet
import kotlin.math.pow

/**
 * Substitutes physically-plausible values for catalog limit-flagged
 * mass / radius before they reach the classifier or the visual profile
 * engine.
 *
 * NASA's `pscomppars` table sometimes records only an upper or lower
 * bound (`pl_bmasselim = ±1`, `pl_radelim = ±1`) rather than a direct
 * measurement. Those bounds are frequently orders of magnitude away
 * from any physically plausible value. Real example: Kepler-62 f is
 * recorded as mass < 35 M⊕ with a measured 1.41 R⊕ radius — the upper
 * bound combined with the measured radius gives a density of 69 g/cm³,
 * denser than osmium. Feeding that to the classifier produces an "iron-
 * rich super-Earth" misclassification, and the visual profile engine's
 * volcanism / atmospheric retention / surface composition models all
 * receive nonsensical mass inputs, producing bizarre globe renders.
 *
 * This helper detects a limit-flagged mass or radius and substitutes
 * an M–R-relation-derived estimate from the measured complement:
 *
 *   • Rocky regime (R < 1.6 R⊕): Valencia et al. M = R^3.7
 *   • Sub-Neptune regime (1.6 ≤ R < 4): continuous extension from
 *     Valencia at R = 1.6, growing more slowly via M = 5.69 × (R/1.6)^1.3
 *     so the curve hits ~17 M⊕ near Neptune's 3.88 R⊕.
 *   • Gas-giant regime (R ≥ 4): anchored at Jupiter (R = 11.21 R⊕,
 *     M = 317.8 M⊕) via M = 317.8 × (R/11.21)^2.
 *
 * Radius-from-mass uses the existing `estimateRadiusFromMass` shape:
 * rocky R = M^0.27, giant R = 11.2 × (M/317.8)^0.06.
 *
 * If both mass and radius are limit-flagged the planet is returned
 * unchanged — neither measurement is reliable enough to derive the
 * other from. The downstream `sanitizeMass` in PlanetClassifier still
 * catches genuinely impossible densities as a final guard.
 *
 * **Important**: the original `Planet` carried by `SystemPlanetEntry`
 * is untouched — UI surfaces still show "< 35 M⊕" with the limit
 * prefix; only the visual pipeline (classifier + visual profile
 * engine) sees the substituted value.
 */
fun Planet.sanitizedForVisuals(): Planet {
    val mLimited = (massEarthLimit ?: 0) != 0
    val rLimited = (radiusEarthLimit ?: 0) != 0
    if (!mLimited && !rLimited) return this
    if (mLimited && rLimited) return this  // neither reliable — leave alone

    val originalR = radiusEarth
    val originalM = massEarth ?: massJupiter?.let { it * 317.8 }

    return if (mLimited && originalR != null) {
        // Mass limit + measured radius — substitute mass from M–R.
        val safeM = estimateMassFromRadius(originalR)
        copy(
            massEarth = safeM,
            massEarthLimit = 0,
            massJupiter = safeM / 317.8,
            massJupiterLimit = 0,
            densityGCm3 = computePlausibleDensity(safeM, originalR),
            densityLimit = 0,
        )
    } else if (rLimited && originalM != null) {
        // Radius limit + measured mass — substitute radius from M–R.
        val isGiantByMass = originalM > 50.0
        val safeR = if (isGiantByMass) {
            11.2 * (originalM / 317.8).pow(0.06)
        } else {
            originalM.pow(0.27)
        }
        copy(
            radiusEarth = safeR,
            radiusEarthLimit = 0,
            densityGCm3 = computePlausibleDensity(originalM, safeR),
            densityLimit = 0,
        )
    } else {
        this
    }
}

private fun estimateMassFromRadius(radiusEarth: Double): Double = when {
    radiusEarth < 1.6 -> radiusEarth.pow(3.7)
    radiusEarth < 4.0 -> 5.69 * (radiusEarth / 1.6).pow(1.3)
    else -> 317.8 * (radiusEarth / 11.21).pow(2.0)
}

/** Density in g/cm³ assuming Earth mean density 5.51 g/cm³ for unit conversion. */
private fun computePlausibleDensity(massEarth: Double, radiusEarth: Double): Double =
    5.51 * massEarth / radiusEarth.pow(3.0)
