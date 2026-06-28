package com.tadmor.app.ui.system

import com.tadmor.domain.classification.BinaryOrbitalCatalog
import com.tadmor.domain.classification.CompositionClass
import com.tadmor.domain.classification.HabitableZone
import com.tadmor.domain.classification.HabitableZoneResult
import com.tadmor.domain.classification.visual.IconProfileBuilder
import com.tadmor.domain.model.SystemDetail
import com.tadmor.domain.model.effectiveRadiusSolar
import com.tadmor.domain.model.effectiveSpectralType
import com.tadmor.domain.model.UserSettings
import com.tadmor.domain.model.isLimitValue
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Per-planet orbital data resolved for display (catalog or estimated SMA).
 */
data class OrbitalPlanetState(
    val name: String,
    val smaAU: Double,
    val eccentricity: Double,
    val periodDays: Double?,
    val radiusEarth: Double?,
    val compositionClass: CompositionClass,
    val fullLabel: String,
    val isEstimated: Boolean,
    val relativeInclinationDeg: Double,
    val argPeriapsisDeg: Double,
    val longAscNodeDeg: Double,
    val transitMidpointBJD: Double?,
    val timeOfPeriapsisBJD: Double?,
    /** ARGB Long from [com.tadmor.domain.classification.visual.PlanetIconProfile.dominantColor]. */
    val dominantColor: Long,
)

/**
 * Complete orbital state for a system, derived from SystemDetail + UserSettings.
 */
data class OrbitalState(
    val planets: List<OrbitalPlanetState>,
    val excludedCount: Int,
    val starTeffK: Double?,
    val starRadiusSolar: Double?,
    val starSpectralType: String?,
    val habitableZone: HabitableZoneResult?,
    val isCircumbinary: Boolean = false,
    val companionTeffK: Double? = null,
    val companionRadiusSolar: Double? = null,
    val binaryStarSeparationAU: Double = 0.0,
    val binaryEccentricity: Double = 0.0,
    val binaryArgPeriapsisDeg: Double = 0.0,
    val binaryOrbitalPeriodDays: Double = 0.0,
    val primaryMassSolar: Double? = null,
    val companionMassSolar: Double? = null,
    val companionHostname: String? = null,
    val companionSpectralType: String? = null,
)

/**
 * Builds OrbitalState from a SystemDetail + UserSettings.
 * Shared between SystemViewModel (for the system strip) and StarMapViewModel (for the 3D orbital view).
 */
fun buildOrbitalState(detail: SystemDetail, settings: UserSettings): OrbitalState {
    val star = detail.star
    val isCircumbinary = detail.isCircumbinary
    val companion = detail.companionStars.firstOrNull()
    val effectiveMass = if (isCircumbinary) detail.combinedMassSolar else star.massSolar
    val orbitalPlanets = mutableListOf<OrbitalPlanetState>()
    var excludedCount = 0

    // Collect measured inclinations to compute system mean
    val measuredInclinations = detail.planets.mapNotNull { entry ->
        val planet = entry.planet
        if (!isLimitValue(planet.inclinationLimit) && planet.inclination != null) {
            planet.inclination
        } else null
    }
    val meanInclination = if (measuredInclinations.isNotEmpty()) {
        measuredInclinations.average()
    } else 0.0

    for (entry in detail.planets) {
        val planet = entry.planet
        val catalogSMA = planet.semiMajorAxisAU
        val estimatedSMA = entry.classification.estimatedSemiMajorAxisAU

        val sma = catalogSMA
            ?: (if (settings.useEstimates) estimatedSMA else null)

        if (sma == null || sma <= 0) {
            excludedCount++
            continue
        }

        // Relative inclination: deviation from system mean, only for measured values
        val incl = planet.inclination
        val relIncl = if (!isLimitValue(planet.inclinationLimit) && incl != null) {
            incl - meanInclination
        } else 0.0

        // Argument of periapsis: use catalog value, or deterministic random
        val catalogOmega = if (!isLimitValue(planet.longOfPeriapsisLimit)) {
            planet.longOfPeriapsis
        } else null
        val argPeriapsis = catalogOmega ?: deterministicAngle(planet.name, 0)

        // Longitude of ascending node: no catalog data, always deterministic random
        val longAscNode = deterministicAngle(planet.name, 1)

        val dominantColor = IconProfileBuilder
            .build(entry.visualProfile, entry.classification)
            .dominantColor

        orbitalPlanets.add(
            OrbitalPlanetState(
                name = planet.name,
                smaAU = sma,
                eccentricity = if (planet.eccentricityLimit == null || planet.eccentricityLimit == 0) {
                    planet.eccentricity ?: 0.0
                } else {
                    0.0 // limit values (upper/lower bounds) aren't reliable for rendering
                },
                periodDays = planet.orbitalPeriodDays
                    ?: estimatePeriod(sma, effectiveMass),
                radiusEarth = planet.radiusEarth,
                compositionClass = entry.classification.compositionClass,
                fullLabel = entry.classification.fullLabel,
                isEstimated = catalogSMA == null,
                relativeInclinationDeg = relIncl,
                argPeriapsisDeg = argPeriapsis,
                longAscNodeDeg = longAscNode,
                transitMidpointBJD = planet.transitMidpointBJD,
                timeOfPeriapsisBJD = planet.timeOfPeriapsisBJD,
                dominantColor = dominantColor,
            ),
        )
    }

    orbitalPlanets.sortBy { it.smaAU }

    // HZ: use combined luminosity for binary systems, single star luminosity otherwise
    val hz = if (isCircumbinary && detail.combinedLuminosity != null && star.teffK != null) {
        HabitableZone.calculate(detail.combinedLuminosity!!, star.teffK!!)
    } else if (star.logLuminosity != null && star.teffK != null) {
        HabitableZone.calculateFromLog(star.logLuminosity!!, star.teffK!!)
    } else {
        null
    }

    // Binary orbital elements: catalog lookup, H&W stability-limit fallback
    val binarySepAU: Double
    val binaryEcc: Double
    val binaryOmega: Double

    if (isCircumbinary && companion != null) {
        val catalog = star.syName?.let { BinaryOrbitalCatalog.lookup(it) }
        if (catalog != null) {
            binarySepAU = catalog.semiMajorAxisAU
            binaryEcc = catalog.eccentricity
            binaryOmega = catalog.argPeriapsisDeg
                ?: deterministicAngle(star.syName ?: star.hostname, 2)
        } else {
            // Holman & Wiegert (1999): a_crit = f(μ) × a_binary, solve for a_binary
            val innermostSMA = orbitalPlanets.firstOrNull()?.smaAU ?: 1.0
            val pm = star.massSolar
            val cm = companion.massSolar
            val mu = if (pm != null && cm != null && pm + cm > 0) {
                cm / (pm + cm)
            } else 0.5
            val stabilityFactor = 1.60 + 4.12 * mu - 5.09 * mu * mu
            binarySepAU = innermostSMA / stabilityFactor
            binaryEcc = 0.0
            binaryOmega = deterministicAngle(star.syName ?: star.hostname, 2)
        }
    } else {
        binarySepAU = 0.0
        binaryEcc = 0.0
        binaryOmega = 0.0
    }

    // Binary orbital period from Kepler's third law: P = sqrt(a³ / M_total) years
    val binaryPeriodDays = if (isCircumbinary && binarySepAU > 0) {
        val combinedMass = detail.combinedMassSolar
        if (combinedMass != null && combinedMass > 0) {
            sqrt(binarySepAU.pow(3) / combinedMass) * 365.25
        } else 0.0
    } else 0.0

    return OrbitalState(
        planets = orbitalPlanets,
        excludedCount = excludedCount,
        starTeffK = star.teffK,
        starRadiusSolar = star.effectiveRadiusSolar(),
        starSpectralType = star.effectiveSpectralType(),
        habitableZone = hz,
        isCircumbinary = isCircumbinary,
        companionTeffK = companion?.teffK,
        companionRadiusSolar = companion?.effectiveRadiusSolar(),
        binaryStarSeparationAU = binarySepAU,
        binaryEccentricity = binaryEcc,
        binaryArgPeriapsisDeg = binaryOmega,
        binaryOrbitalPeriodDays = binaryPeriodDays,
        primaryMassSolar = star.massSolar,
        companionMassSolar = companion?.massSolar,
        companionHostname = companion?.hostname,
        companionSpectralType = companion?.effectiveSpectralType(),
    )
}

/**
 * Generates a deterministic angle (0–360°) from a planet name and salt.
 * Uses the name's hash so each planet gets a consistent but varied orientation.
 * Different salt values produce different angles for the same planet.
 */
private fun deterministicAngle(planetName: String, salt: Int): Double {
    val hash = abs(planetName.hashCode().toLong() * 31 + salt)
    return (hash % 36000) / 100.0 // 0.00–359.99°
}

/**
 * Estimates orbital period from SMA and stellar mass via Kepler's third law.
 * P = √(a³ / M_star) × 365.25 days (solar units).
 */
private fun estimatePeriod(smaAU: Double, massSolar: Double?): Double? {
    if (massSolar == null || massSolar <= 0 || smaAU <= 0) return null
    val periodYears = sqrt(smaAU.pow(3) / massSolar)
    return periodYears * 365.25
}
