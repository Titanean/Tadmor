package com.tadmor.domain.model

/**
 * Domain model for a host star.
 * Field names match SPEC.md Section 4.3.2.
 */
data class Star(
    val hostname: String,
    val spectralType: String?,
    val teffK: Double?,
    val teffKLimit: Int?,
    val radiusSolar: Double?,
    val radiusSolarLimit: Int?,
    val massSolar: Double?,
    val massSolarLimit: Int?,
    val logLuminosity: Double?,
    val logLuminosityLimit: Int?,
    val rightAscensionDeg: Double?,
    val declinationDeg: Double?,
    val distancePc: Double?,
    val planetCount: Int?,
    val hdName: String? = null,
    val hipName: String? = null,
    val ticId: String? = null,
    val syName: String? = null,
    val sySnum: Int? = null,
    val isPrimary: Boolean = true,
    val metallicity: Double? = null,
    val metallicityLimit: Int? = null,
    val age: Double? = null,
    val ageLimit: Int? = null,
    val logg: Double? = null,
    val loggLimit: Int? = null,
    val rotationPeriodDays: Double? = null,
)

/**
 * Heuristic white-dwarf detection from catalog mass + radius. Real WDs
 * cluster tightly: 0.5–1.0 M☉ (peak ~0.6) packed into 0.008–0.020 R☉
 * (Earth-ish). The bounds here are slightly looser to catch edge cases
 * (low-mass He-core WDs, ultra-massive O/Mg/Ne WDs near Chandrasekhar).
 * Primarily used to demote stars that the archive tags ambiguously: the
 * companion in a pulsar binary like PSR B1620-26 B inherits the system's
 * "PSR" hostname prefix, has no spectral type, and would otherwise be
 * mislabeled as a pulsar — but its archive mass/radius identify it as a
 * white dwarf.
 */
fun Star.isLikelyWhiteDwarf(): Boolean {
    val mass = massSolar ?: return false
    val radius = radiusSolar ?: return false
    return mass in 0.3..1.4 && radius in 0.005..0.030
}

/**
 * Pulsar / neutron-star detection. Catalog hostnames that begin with the
 * "PSR" prefix are radio-pulsar designations (e.g. "PSR 1257+12" / Lich,
 * "PSR B1620-26"). The exoplanet archive doesn't store a Teff or spectral
 * type for these, but they're physically distinct enough — millisecond
 * rotation, ~10⁶ K surfaces — that falling back to a Sol-like default is
 * misleading. Display code synthesizes a custom "Q" spectral type and a
 * harsh-blue colour for them via this check.
 *
 * Companions in PSR binaries (e.g. PSR B1620-26 B, the WD orbiting the
 * pulsar) inherit the system's "PSR" prefix and would be mislabeled by
 * the prefix check alone. The `isLikelyWhiteDwarf` filter demotes them:
 * the actual pulsar primary in a PSR binary almost never has a catalog
 * radius (true neutron-star ~10 km values aren't typically published),
 * so it falls through; the WD companion has its archive mass + radius
 * and gets correctly classified as a WD instead.
 */
fun Star.isPulsar(): Boolean {
    if (!hostname.trim().uppercase().startsWith("PSR")) return false
    if (isLikelyWhiteDwarf()) return false
    return true
}

/**
 * Spectral type used for display. Resolution order:
 *   1. Catalog spectralType, if present.
 *   2. White-dwarf override "D" — for stars whose archive mass + radius
 *      match the WD locus, even when the spectral type is missing
 *      (catches PSR B1620-26 B and similar mislabeled binary companions).
 *   3. Pulsar override "Q" — for `PSR`-prefixed hosts that don't fit the
 *      WD heuristic.
 *   4. null.
 */
fun Star.effectiveSpectralType(): String? {
    spectralType?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    if (isLikelyWhiteDwarf()) return "D"
    if (isPulsar()) return "Q"
    return null
}

/**
 * Radius (in solar radii) used for display: the catalog value if present,
 * otherwise the canonical pulsar radius (~12 km ≈ 1.72×10⁻⁵ R☉) for
 * neutron stars. Lets the system strip, orbital view, planet globe sun
 * disc, and star globe all share a single physically-meaningful value
 * for pulsars whose radius isn't in the archive.
 *
 * 12 km is the geometric mid of the typical 10–15 km neutron-star range.
 * Views that need a guaranteed visible disc (system strip, orbital, the
 * COMPARE section) should clamp to a `min` size after consuming this
 * value — the radius itself stays physically accurate.
 */
const val PULSAR_RADIUS_SOLAR: Double = 12.0 / 695700.0

fun Star.effectiveRadiusSolar(): Double? {
    radiusSolar?.let { return it }
    if (isPulsar()) return PULSAR_RADIUS_SOLAR
    return null
}
