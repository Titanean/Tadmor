package com.tadmor.domain.classification.visual

/**
 * Complete visual profile for a planet. Deterministic — same planet always
 * produces the same profile via seed from planet name hash.
 * Invisible to users; drives future planet rendering only.
 */
data class VisualProfile(
    val seed: Long,
    val bulkClass: BulkClass?,
    val surfaceComposition: SurfaceComposition?,
    val gasGiantProfile: GasGiantProfile?,
    val atmosphere: AtmosphericComposition,
    val atmosphereOptics: AtmosphereOptics,
    val ringProfile: RingProfile?,
    val seaLevel: Float,
    val volcanicActivity: Float,
    val craterProfile: CraterProfile,
    val polarCapExtent: Float,
    val roughness: Float,
    val albedo: Float,
    val oblateness: Float,
    val tidalElongation: Float,
    val tidallyLocked: Boolean,
    val axialTilt: Float,
    val rotationPeriodHours: Float,
    val surfaceTemperatureK: Float,  // greenhouse-adjusted; drives ice/water/vapor rendering
)
