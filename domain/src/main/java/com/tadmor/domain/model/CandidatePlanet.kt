package com.tadmor.domain.model

/**
 * Domain model for a NASA exoplanet candidate or confirmed false positive.
 *
 * Sourced from the TAP `toi` table (TESS Objects of Interest) and the
 * `q1_q17_dr25_koi` table (Kepler Objects of Interest). Carries the
 * minimal slice of transit data, plus enough stellar fields to render a
 * stand-in [Star] when the host catalog ID isn't matched against an entry
 * in our `ps`-derived stars table.
 */
data class CandidatePlanet(
    /**
     * Source-native primary identifier without the source prefix:
     *   - TOI: e.g. "1234.01"
     *   - KOI: e.g. "K00752.01"
     */
    val sourceId: String,
    /** Source-native host identifier (TIC for TOI, KIC for KOI). */
    val hostId: String,
    val disposition: Disposition,
    /**
     * Resolved hostname for grouping under a system in the System tab:
     *   - TOI: "TIC <id>" placeholder, or the matched primary's hostname
     *     when reconciliation finds an existing star with the same TIC.
     *   - KOI: derived from `kepler_name` (e.g. "Kepler-186"), or
     *     "KIC <id>" when the candidate hasn't been promoted yet.
     */
    val hostname: String,
    /** Planet radius in Earth radii (transit-derived). */
    val radiusEarth: Double?,
    /** Orbital period in days (transit-derived). */
    val orbitalPeriodDays: Double?,
    val eqTempK: Double?,
    val insolationFlux: Double?,
    val transitMidpointBJD: Double?,
    val hostTeffK: Double?,
    val hostRadiusSolar: Double?,
    val hostDistancePc: Double?,
    val ra: Double?,
    val dec: Double?,
    val createdDate: String?,
    /** "TOI" or "KOI". */
    val source: String,
) {
    /**
     * Synthesises a [Planet] suitable for the existing classification +
     * visual profile pipeline. Mass left null — neither TOI nor KOI
     * tables provide it directly.
     */
    fun toPlanet(): Planet = Planet(
        name = displayName,
        hostname = hostname,
        massJupiter = null,
        massJupiterLimit = null,
        massEarth = null,
        massEarthLimit = null,
        radiusEarth = radiusEarth,
        radiusEarthLimit = null,
        semiMajorAxisAU = null,
        semiMajorAxisLimit = null,
        orbitalPeriodDays = orbitalPeriodDays,
        orbitalPeriodLimit = null,
        eccentricity = null,
        eccentricityLimit = null,
        inclination = null,
        inclinationLimit = null,
        longOfPeriapsis = null,
        longOfPeriapsisLimit = null,
        eqTempK = eqTempK,
        eqTempKLimit = null,
        insolationFlux = insolationFlux,
        insolationFluxLimit = null,
        densityGCm3 = null,
        densityLimit = null,
        transitMidpointBJD = transitMidpointBJD,
        timeOfPeriapsisBJD = null,
        discoveryMethod = "Transit",
        discoveryYear = createdDate?.take(4)?.toIntOrNull(),
        discoveryPubDate = createdDate,
        cbFlag = false,
        syName = null,
    )

    /**
     * Display name shown in catalog and system page lists. K2's `pl_name`
     * is already a fully-namespaced display label ("K2-3 b" / "EPIC X b")
     * so we use it as-is. TOI / KOI prefix with the source for clarity
     * — TFOPWG and KOI numbers don't communicate their origin without it.
     */
    val displayName: String get() = when (source) {
        "K2" -> sourceId
        else -> "$source-$sourceId"
    }
}
