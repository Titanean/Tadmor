package com.tadmor.domain.classification

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Habitable zone boundaries using Kopparapu et al. (2013) parameterization.
 * "Habitable Zones Around Main-Sequence Stars: New Estimates"
 * ApJ 765:131, Table 3.
 */
data class HabitableZoneResult(
    val innerAU: Double,
    val outerAU: Double,
)

object HabitableZone {

    /**
     * Calculate conservative habitable zone boundaries.
     * Inner edge: Recent Venus limit (runaway greenhouse).
     * Outer edge: Early Mars limit (maximum greenhouse).
     *
     * @param luminositySolar Stellar luminosity in solar units (L/L☉), linear scale.
     * @param teffK Stellar effective temperature in Kelvin.
     * @return HZ boundaries in AU, or null if inputs are out of valid range.
     */
    fun calculate(luminositySolar: Double, teffK: Double): HabitableZoneResult? {
        // Kopparapu model parameterized for 2600–7200 K main-sequence stars.
        // Extended to 2200 K to cover ultra-cool M dwarfs (e.g. TRAPPIST-1 at 2566 K).
        // The polynomial extrapolation remains well-behaved down to ~2200 K.
        if (teffK < 2200 || teffK > 7200 || luminositySolar <= 0) return null

        val tStar = teffK - 5780.0 // Teff offset from solar

        val sEffInner = solarFluxBoundary(RECENT_VENUS, tStar)
        val sEffOuter = solarFluxBoundary(EARLY_MARS, tStar)

        val innerAU = sqrt(luminositySolar / sEffInner)
        val outerAU = sqrt(luminositySolar / sEffOuter)

        return HabitableZoneResult(innerAU, outerAU)
    }

    /**
     * Overload accepting log10(L/L☉) directly, as stored in the database.
     */
    fun calculateFromLog(logLuminosity: Double, teffK: Double): HabitableZoneResult? {
        val luminositySolar = 10.0.pow(logLuminosity)
        return calculate(luminositySolar, teffK)
    }

    // --- Kopparapu et al. 2013 Table 3 coefficients ---
    // Each boundary: Seff(T*) = Seff_sun + a*T* + b*T*^2 + c*T*^3 + d*T*^4
    // where T* = Teff - 5780

    private data class BoundaryCoefficients(
        val sEffSun: Double,
        val a: Double,
        val b: Double,
        val c: Double,
        val d: Double,
    )

    private val RECENT_VENUS = BoundaryCoefficients(
        sEffSun = 1.7763,
        a = 1.4335e-4,
        b = 3.3954e-9,
        c = -7.6364e-12,
        d = -1.1950e-15,
    )

    private val EARLY_MARS = BoundaryCoefficients(
        sEffSun = 0.3207,
        a = 5.4471e-5,
        b = 1.5275e-9,
        c = -2.1709e-12,
        d = -3.8282e-16,
    )

    private fun solarFluxBoundary(c: BoundaryCoefficients, tStar: Double): Double {
        val t2 = tStar * tStar
        val t3 = t2 * tStar
        val t4 = t3 * tStar
        return c.sEffSun + c.a * tStar + c.b * t2 + c.c * t3 + c.d * t4
    }
}
