package com.tadmor.domain.classification.visual

/**
 * Lightweight visual descriptor for the 2D planet icon system.
 *
 * Built by [IconProfileBuilder] on a fast path that skips chemistry,
 * retention, cratering math, and atmosphere optics. Same input always
 * yields the same profile (deterministic from planet name hash).
 *
 * All colors are ARGB Long values (0xAARRGGBB), matching [ColorPalettes].
 */
data class PlanetIconProfile(
    val bulkClass: BulkClass?,            // null for gas giants
    val dominantColor: Long,              // single average color for strip / star map
    val bodyColor: Long,                  // disk base (ocean / rock / gas deck)
    val accentColor: Long,                // terrain blob color (land / frost / dark rock)
    val bandColors: LongArray,            // 3–5 colors for gas giant strips (empty otherwise)
    /** When true, gas-giant icon renders the band colours as a domain-
     *  warped swirl bitmap rather than horizontal strips — matches the
     *  globe's swirl rendering for Class IV/V hot Jupiters and select
     *  ice giants. Ignored for non-giants. */
    val unbanded: Boolean = false,
    val atmosphereColor: Long,            // halo tint (alpha 0 if airless)
    val atmosphereIntensity: Float,       // [0,1] — halo thickness as fraction of radius
    val cloudColor: Long,
    val cloudCoverage: Float,             // [0,1] — fraction of disk covered by clouds
    val cloudDensity: Float,              // [0,1] — opacity of those clouds (low = wispy)
    val polarCapColor: Long,
    val polarCapExtent: Float,            // [0,1] — 0 = none, 0.5+ = tidal eyeball
    val tidallyLocked: Boolean,
    val craterCount: Int,                 // 0–12 (0 for active / young / gas giants)
    val hasRings: Boolean,
    val ringInnerRatio: Float,            // relative to disk radius (≥1.2)
    val ringOuterRatio: Float,            // relative to disk radius (≤3.6)
    val ringColor: Long,
    val ringOpacity: Float,               // [0,1]
    val landThreshold: Float,             // [0,1] for terrain bitmap (low = mostly land)
    val spotColor: Long = 0L,             // 0 = no spot; ARGB of an oval feature (GRS, GDS)
    val spotY: Float = 0f,                // vertical center [0,1] top-to-bottom within disk
    val spotWidthFrac: Float = 0f,        // width as fraction of disk diameter
    val spotHeightFrac: Float = 0f,       // height as fraction of disk diameter
    val seed: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlanetIconProfile) return false
        return seed == other.seed
    }

    override fun hashCode(): Int = seed.hashCode()
}
