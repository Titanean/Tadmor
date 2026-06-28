package com.tadmor.domain.model

/**
 * Catalog-displayed planet parameter that the diff display tracks.
 * [displayLabel] is the human-readable noun used in the UI's diff
 * "Old → New" rows (matches the section labels in the property grid on
 * the planet info page).
 */
enum class PlanetField(val displayLabel: String) {
    MASS_EARTH("Mass"),
    RADIUS_EARTH("Radius"),
    DENSITY("Density"),
    EQ_TEMP_K("Temperature"),
    INSOLATION("Insolation"),
    SEMI_MAJOR_AXIS("Semi-major axis"),
    ORBITAL_PERIOD("Period"),
    ECCENTRICITY("Eccentricity"),
    INCLINATION("Inclination"),
    LONG_OF_PERIAPSIS("Longitude of periapsis"),
    DISCOVERY_METHOD("Discovery method"),
    DISCOVERY_YEAR("Discovery year"),
    DISPOSITION("Disposition"),
}

data class FieldChange(
    val field: PlanetField,
    val oldValue: Any?,
    val newValue: Any?,
)

/**
 * Computed difference between a bookmark's stored snapshot and the
 * current value of the same planet. Computed on demand at flow-emission
 * time — never stored. [hasUpdates] is the read-model signal for both
 * the catalog card "n updated" indicator and the bookmark filter
 * button's badge count.
 */
data class PlanetDiff(
    val planetKey: String,
    val changes: List<FieldChange>,
) {
    val updateCount: Int get() = changes.size
    val hasUpdates: Boolean get() = changes.isNotEmpty()
}

/**
 * Diff [snapshot] (the bookmark's frozen-at-capture record) against
 * [current] (the live catalog value). Returns a [PlanetDiff] containing
 * one [FieldChange] per parameter whose value has changed.
 *
 * Equality is exact — Doubles compared with `!=`, strings with `!=`. A
 * field where one side is null and the other isn't counts as a change
 * ("data added" or "data removed").
 */
fun computeDiff(
    planetKey: String,
    snapshot: PlanetSnapshot,
    current: PlanetSnapshot,
): PlanetDiff {
    val changes = buildList {
        if (snapshot.massEarth != current.massEarth) {
            add(FieldChange(PlanetField.MASS_EARTH, snapshot.massEarth, current.massEarth))
        }
        if (snapshot.radiusEarth != current.radiusEarth) {
            add(FieldChange(PlanetField.RADIUS_EARTH, snapshot.radiusEarth, current.radiusEarth))
        }
        if (snapshot.densityGCm3 != current.densityGCm3) {
            add(FieldChange(PlanetField.DENSITY, snapshot.densityGCm3, current.densityGCm3))
        }
        if (snapshot.eqTempK != current.eqTempK) {
            add(FieldChange(PlanetField.EQ_TEMP_K, snapshot.eqTempK, current.eqTempK))
        }
        if (snapshot.insolationFlux != current.insolationFlux) {
            add(FieldChange(PlanetField.INSOLATION, snapshot.insolationFlux, current.insolationFlux))
        }
        if (snapshot.semiMajorAxisAU != current.semiMajorAxisAU) {
            add(FieldChange(PlanetField.SEMI_MAJOR_AXIS, snapshot.semiMajorAxisAU, current.semiMajorAxisAU))
        }
        if (snapshot.orbitalPeriodDays != current.orbitalPeriodDays) {
            add(FieldChange(PlanetField.ORBITAL_PERIOD, snapshot.orbitalPeriodDays, current.orbitalPeriodDays))
        }
        if (snapshot.eccentricity != current.eccentricity) {
            add(FieldChange(PlanetField.ECCENTRICITY, snapshot.eccentricity, current.eccentricity))
        }
        if (snapshot.inclination != current.inclination) {
            add(FieldChange(PlanetField.INCLINATION, snapshot.inclination, current.inclination))
        }
        if (snapshot.longOfPeriapsis != current.longOfPeriapsis) {
            add(FieldChange(PlanetField.LONG_OF_PERIAPSIS, snapshot.longOfPeriapsis, current.longOfPeriapsis))
        }
        if (snapshot.discoveryMethod != current.discoveryMethod) {
            add(FieldChange(PlanetField.DISCOVERY_METHOD, snapshot.discoveryMethod, current.discoveryMethod))
        }
        if (snapshot.discoveryYear != current.discoveryYear) {
            add(FieldChange(PlanetField.DISCOVERY_YEAR, snapshot.discoveryYear, current.discoveryYear))
        }
        if (snapshot.disposition != current.disposition) {
            add(FieldChange(PlanetField.DISPOSITION, snapshot.disposition, current.disposition))
        }
    }
    return PlanetDiff(planetKey, changes)
}
