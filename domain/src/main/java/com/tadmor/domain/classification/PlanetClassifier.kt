package com.tadmor.domain.classification

import com.tadmor.domain.model.Planet
import com.tadmor.domain.model.Star
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Classification engine implementing SPEC.md Section 5.2.
 * Pure function: parameters in, PlanetClassification out.
 */
object PlanetClassifier {

    // Earth radius in cm for density calculation
    private const val EARTH_RADIUS_CM = 6.371e8
    private const val EARTH_MASS_G = 5.972e27

    // Solar radius in AU for equilibrium temperature calculation
    private const val SOLAR_RADIUS_AU = 0.00465047

    // Kepler's third law: a³ = (P/2π)² × GM☉ → in solar/AU/day units:
    // a³/M_star = P_days² / 365.25²  (since a=1 AU, M=1 M☉, P=365.25 days)
    private const val DAYS_PER_YEAR = 365.25

    // Solar luminosity in Earth insolation units (L☉ at 1 AU = 1 S⊕)
    // Stefan-Boltzmann: L = 4π R² σ T⁴ ; insolation S = L / (4π a²)
    // In solar/AU units: S/S⊕ = (R/R☉)² × (T/T☉)⁴ / (a/1AU)²
    private const val SOLAR_TEFF = 5778.0

    // Density sanity threshold: osmium is ~22.6 g/cm³; anything above 40 is bad data
    private const val MAX_PLAUSIBLE_DENSITY = 40.0

    fun classify(
        planet: Planet,
        star: Star?,
        combinedMassSolar: Double? = null,
    ): PlanetClassification {
        // First substitute physically-plausible values for any catalog
        // limit-flagged mass / radius — see PlanetVisualSanitization for
        // why (Kepler-62 f's "< 35 M⊕" upper bound paired with measured
        // 1.41 R⊕ would otherwise give 69 g/cm³ density and misclassify
        // the planet as iron-rich super-Earth). Then run the existing
        // density-impossibility guard as a final catch.
        val sanitized = sanitizeMass(planet.sanitizedForVisuals())
        val composition = determineComposition(sanitized)
        val massPrefix = determineMassPrefix(sanitized, composition)

        // Use catalog temperature if available, otherwise estimate from star data
        val catalogTemp = sanitized.eqTempK
        val estimated = estimateFromStar(sanitized, star)
        val eqTempK = catalogTemp ?: estimated?.first
        val temperatureEstimated = catalogTemp == null && eqTempK != null

        val temperature = eqTempK?.let { TemperatureClass.fromTemperature(it) }
        val label = buildLabel(temperature, massPrefix, composition)

        // Insolation: use catalog value, or estimate
        val catalogInsol = sanitized.insolationFlux
        val estimatedInsol = if (catalogInsol == null) estimated?.second else null

        // For circumbinary planets, use combined stellar mass for Kepler's law
        val effectiveMass = combinedMassSolar ?: star?.massSolar

        // SMA: expose estimate only when catalog value is missing
        val estimatedSMA = if (sanitized.semiMajorAxisAU == null && star != null) {
            estimateSMA(sanitized, star)
        } else null

        // Period: expose estimate only when catalog value is missing
        val estimatedPeriod = if (sanitized.orbitalPeriodDays == null) {
            estimatePeriod(sanitized.semiMajorAxisAU ?: estimatedSMA, effectiveMass)
        } else null

        return PlanetClassification(
            compositionClass = composition,
            temperatureClass = temperature,
            massPrefix = massPrefix,
            fullLabel = label,
            estimatedEqTempK = if (temperatureEstimated) eqTempK else null,
            estimatedInsolation = estimatedInsol,
            estimatedSemiMajorAxisAU = estimatedSMA,
            estimatedOrbitalPeriodDays = estimatedPeriod,
            temperatureEstimated = temperatureEstimated,
        )
    }

    /**
     * Nulls out mass fields if they produce a physically impossible density.
     * Catches bad data from pscomppars composite (e.g. superseded paper values).
     */
    private fun sanitizeMass(planet: Planet): Planet {
        // Check catalog density directly
        val catalogDensity = planet.densityGCm3
        if (catalogDensity != null && catalogDensity > MAX_PLAUSIBLE_DENSITY) {
            return planet.copy(massEarth = null, massJupiter = null, densityGCm3 = null)
        }
        // Check computed density from mass + radius
        val massE = planet.massEarth ?: return planet
        val radius = planet.radiusEarth ?: return planet
        val density = computeDensity(massE, radius) ?: return planet
        if (density > MAX_PLAUSIBLE_DENSITY) {
            return planet.copy(massEarth = null, massJupiter = null, densityGCm3 = null)
        }
        return planet
    }

    /**
     * Estimates equilibrium temperature and insolation flux from stellar parameters.
     * T_eq = T_star × √(R_star / 2a) assuming Bond albedo = 0 (NASA convention).
     * S = (R_star/R_sun)² × (T_star/T_sun)⁴ / (a/1AU)² in Earth insolation units.
     * If SMA is missing, estimates it from orbital period + stellar mass via Kepler's 3rd law:
     *   a = (GM☉ × M_star × P²)^(1/3)
     * Returns (T_eq in K, S in S⊕) or null if insufficient data.
     */
    private fun estimateFromStar(planet: Planet, star: Star?): Pair<Double, Double>? {
        if (star == null) return null
        val tStar = star.teffK ?: return null
        val rStarAU = (star.radiusSolar ?: return null) * SOLAR_RADIUS_AU

        // Use catalog SMA, or estimate from period + stellar mass
        val aAU = planet.semiMajorAxisAU ?: estimateSMA(planet, star) ?: return null
        if (aAU <= 0.0) return null

        val tEq = tStar * sqrt(rStarAU / (2.0 * aAU))
        val rSolar = star.radiusSolar!!
        val insolation = rSolar.pow(2) * (tStar / SOLAR_TEFF).pow(4) / aAU.pow(2)

        return tEq to insolation
    }

    /**
     * Estimates semi-major axis from orbital period and stellar mass via Kepler's third law.
     * a = (GM☉ × M_star × P²)^(1/3)  in AU, with P in days.
     */
    private fun estimateSMA(planet: Planet, star: Star): Double? {
        val periodDays = planet.orbitalPeriodDays ?: return null
        val massSolar = star.massSolar ?: return null
        if (periodDays <= 0.0 || massSolar <= 0.0) return null
        val periodYears = periodDays / DAYS_PER_YEAR
        return (massSolar * periodYears * periodYears).pow(1.0 / 3.0)
    }

    /**
     * Estimates orbital period from SMA and stellar mass via Kepler's third law.
     * P = √(a³ / M_star) × 365.25 days.
     */
    private fun estimatePeriod(smaAU: Double?, massSolar: Double?): Double? {
        if (smaAU == null || massSolar == null || smaAU <= 0 || massSolar <= 0) return null
        val periodYears = kotlin.math.sqrt(smaAU.pow(3) / massSolar)
        return periodYears * DAYS_PER_YEAR
    }

    private fun determineComposition(planet: Planet): CompositionClass {
        val massE = planet.massEarth
        val massJ = planet.massJupiter
        val radius = planet.radiusEarth
        val density = planet.densityGCm3 ?: computeDensity(massE, radius)

        // Gas giant: R > 6 R⊕ or M > 50 M⊕ or M > 0.2 M♃
        if (radius != null && radius > 6.0) return CompositionClass.JUPITER
        if (massE != null && massE > 50.0) return CompositionClass.JUPITER
        if (massJ != null && massJ > 0.2) return CompositionClass.JUPITER

        // Neptune/ice giant: 2-6 R⊕ and 5-50 M⊕, or density 1-3 in that size range
        if (radius != null && radius in 2.0..6.0) {
            if (massE != null && massE in 5.0..50.0) return CompositionClass.NEPTUNE
            if (density != null && density in 1.0..3.0) return CompositionClass.NEPTUNE
            // Radius in Neptune range but no mass info — assume Neptune
            if (massE == null) return CompositionClass.NEPTUNE
        }
        if (massE != null && massE in 5.0..50.0 && radius == null) {
            return CompositionClass.NEPTUNE
        }

        // Default solid: Terra (terrestrial)
        return CompositionClass.TERRA
    }

    private fun determineMassPrefix(
        planet: Planet,
        composition: CompositionClass,
    ): MassPrefix? {
        return when (composition) {
            CompositionClass.JUPITER -> {
                planet.massJupiter?.let { MassPrefix.fromJupiterMass(it) }
                    ?: planet.massEarth?.let { MassPrefix.fromJupiterMass(it / 317.8) }
            }
            CompositionClass.NEPTUNE -> {
                planet.massEarth?.let { MassPrefix.fromNeptuneMass(it) }
                    ?: planet.massJupiter?.let { MassPrefix.fromNeptuneMass(it * 317.8) }
            }
            else -> {
                planet.massEarth?.let { MassPrefix.fromSolidMass(it) }
                    ?: planet.massJupiter?.let { MassPrefix.fromSolidMass(it * 317.8) }
            }
        }
    }

    private fun computeDensity(massEarth: Double?, radiusEarth: Double?): Double? {
        if (massEarth == null || radiusEarth == null) return null
        val massG = massEarth * EARTH_MASS_G
        val radiusCm = radiusEarth * EARTH_RADIUS_CM
        val volumeCm3 = (4.0 / 3.0) * PI * radiusCm.pow(3)
        return massG / volumeCm3
    }

    private fun buildLabel(
        temperature: TemperatureClass?,
        massPrefix: MassPrefix?,
        composition: CompositionClass,
    ): String = buildString {
        temperature?.let {
            append(it.label)
            append(" ")
        }
        massPrefix?.let {
            if (it != MassPrefix.STANDARD) {
                append(it.label)
                append("-")
            }
        }
        append(composition.label)
    }
}
