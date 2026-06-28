package com.tadmor.domain.classification

/**
 * Result of classifying a planet using the SpaceEngine taxonomy.
 * SPEC.md Section 5.3.
 */
data class PlanetClassification(
    val compositionClass: CompositionClass,
    val temperatureClass: TemperatureClass?,
    val massPrefix: MassPrefix?,
    val fullLabel: String,
    val estimatedEqTempK: Double? = null,
    val estimatedInsolation: Double? = null,
    val estimatedSemiMajorAxisAU: Double? = null,
    val estimatedOrbitalPeriodDays: Double? = null,
    val temperatureEstimated: Boolean = false,
)
