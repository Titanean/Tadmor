package com.tadmor.domain.model

/**
 * Domain model for a confirmed exoplanet.
 * All numeric fields are nullable — many planets have incomplete data.
 * Field names match SPEC.md Section 4.3.1.
 */
data class Planet(
    val name: String,
    val hostname: String,
    val massJupiter: Double?,
    val massJupiterLimit: Int?,
    val massEarth: Double?,
    val massEarthLimit: Int?,
    val radiusEarth: Double?,
    val radiusEarthLimit: Int?,
    val semiMajorAxisAU: Double?,
    val semiMajorAxisLimit: Int?,
    val orbitalPeriodDays: Double?,
    val orbitalPeriodLimit: Int?,
    val eccentricity: Double?,
    val eccentricityLimit: Int?,
    val inclination: Double?,
    val inclinationLimit: Int?,
    val longOfPeriapsis: Double?,
    val longOfPeriapsisLimit: Int?,
    val eqTempK: Double?,
    val eqTempKLimit: Int?,
    val insolationFlux: Double?,
    val insolationFluxLimit: Int?,
    val densityGCm3: Double?,
    val densityLimit: Int?,
    val transitMidpointBJD: Double? = null,
    val timeOfPeriapsisBJD: Double? = null,
    val discoveryMethod: String?,
    val discoveryYear: Int?,
    // Discovery reference publication date from the NASA archive.
    // Format: ISO YYYY-MM-DD when fully known, YYYY-MM when only the month
    // is, occasionally YYYY (rarely null). More precise than `discoveryYear`
    // alone — used for "newest first" sort ordering. Display sites still
    // use `discoveryYear` since users only expect a year in the UI.
    val discoveryPubDate: String? = null,
    val cbFlag: Boolean = false,
    val syName: String? = null,
) {
    /**
     * Normalised ISO date string (`YYYY-MM-DD`) used for sort comparisons.
     * Preferred order:
     *   1. `discoveryPubDate` — uses day if present, else 01 of the named
     *      month, else 01 January of the named year.
     *   2. `discoveryYear` — treated as 01 January of that year.
     *   3. null — sorts last.
     */
    fun discoveryDateSortKey(): String? {
        discoveryPubDate?.trim()?.takeIf { it.isNotEmpty() }?.let { p ->
            return when (p.length) {
                10 -> p              // YYYY-MM-DD
                7 -> "$p-01"          // YYYY-MM → YYYY-MM-01
                4 -> "$p-01-01"       // YYYY → YYYY-01-01
                else -> p             // Unexpected; lex-compare as-is
            }
        }
        return discoveryYear?.let { "$it-01-01" }
    }
}
