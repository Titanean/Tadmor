package com.tadmor.domain.model

import kotlinx.serialization.Serializable

/**
 * Flat snapshot of the planet/candidate fields the diff display surfaces
 * on the planet info page. Captured at bookmark time, persisted as JSON
 * in the bookmarks DB, and replaced with the current values when the user
 * closes the planet info page (consume-on-close).
 *
 * Star fields are intentionally omitted — Phase 11 only diffs planet-level
 * parameters. Disposition is included so candidate→confirmed (or →FP)
 * transitions register as updates, which is the headline reason a user
 * would bookmark a candidate.
 */
@Serializable
data class PlanetSnapshot(
    val massEarth: Double?,
    val radiusEarth: Double?,
    val densityGCm3: Double?,
    val eqTempK: Double?,
    val insolationFlux: Double?,
    val semiMajorAxisAU: Double?,
    val orbitalPeriodDays: Double?,
    val eccentricity: Double?,
    val inclination: Double?,
    val longOfPeriapsis: Double?,
    val discoveryMethod: String?,
    val discoveryYear: Int?,
    /** "CONFIRMED" | "CANDIDATE" | "FALSE_POSITIVE" — string for cross-source compat. */
    val disposition: String,
)

/**
 * Builds a snapshot from a confirmed [Planet]. Disposition is always
 * CONFIRMED for entries from the main `planets` table.
 */
fun Planet.toSnapshot(): PlanetSnapshot = PlanetSnapshot(
    massEarth = massEarth,
    radiusEarth = radiusEarth,
    densityGCm3 = densityGCm3,
    eqTempK = eqTempK,
    insolationFlux = insolationFlux,
    semiMajorAxisAU = semiMajorAxisAU,
    orbitalPeriodDays = orbitalPeriodDays,
    eccentricity = eccentricity,
    inclination = inclination,
    longOfPeriapsis = longOfPeriapsis,
    discoveryMethod = discoveryMethod,
    discoveryYear = discoveryYear,
    disposition = Disposition.CONFIRMED.name,
)

/**
 * Builds a snapshot from a [CandidatePlanet]. Mass / SMA / eccentricity /
 * inclination / longOfPeriapsis / density are always null on candidates
 * (TOI/KOI tables don't carry them); they round-trip as null and never
 * register as a "change" until the candidate gets promoted to a row in
 * the confirmed `planets` table with measured values.
 */
fun CandidatePlanet.toSnapshot(): PlanetSnapshot = PlanetSnapshot(
    massEarth = null,
    radiusEarth = radiusEarth,
    densityGCm3 = null,
    eqTempK = eqTempK,
    insolationFlux = insolationFlux,
    semiMajorAxisAU = null,
    orbitalPeriodDays = orbitalPeriodDays,
    eccentricity = null,
    inclination = null,
    longOfPeriapsis = null,
    discoveryMethod = "Transit",
    discoveryYear = createdDate?.take(4)?.toIntOrNull(),
    disposition = disposition.name,
)

/**
 * Snapshot built from a presentation-layer [CatalogEntry]. Convenience
 * over `entry.planet.toSnapshot()` because it fills in the candidate's
 * actual disposition from [CatalogEntry.disposition] — the entry's
 * `planet` field is a synthesised [Planet] for candidates and
 * `Planet.toSnapshot()` defaults disposition to CONFIRMED.
 */
fun CatalogEntry.toSnapshot(): PlanetSnapshot = planet.toSnapshot().copy(
    disposition = disposition.name,
)
