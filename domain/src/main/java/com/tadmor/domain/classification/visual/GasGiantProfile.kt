package com.tadmor.domain.classification.visual

/**
 * Cloud band texture parameters for gas giants.
 * Band colors represent the cloud deck — the atmosphere model handles
 * Rayleigh scattering and overall apparent color separately.
 */
data class GasGiantProfile(
    val type: GasGiantType,
    val metallicityEnrichment: Float,
    val methaneAbundance: Float,
    val bandColors: List<Long>,
    val poleColor: Long,
    val poleFraction: Float,
    val stormIntensity: Float,
    val bandingStrength: Float,
    val bandCount: Float,
    val bandBreakup: Float,
    val bandSoftness: Float,
    val microDetail: Float,
    val striations: Float,
    val turbulence: Float,
    val contrast: Float,
    val noiseScale: Float = 4.0f,
    /** When true, the gas giant bake skips the latitudinal sin-wave band
     *  pattern and renders a pure-fluid swirly cloud field driven by
     *  domain-warped FBM. Applied to a subset of hot Jupiters
     *  (Sudarsky Class IV/V) and some ice giants where chaotic vortex
     *  circulation dominates over zonal jets. See `applySwirl` in
     *  GasGiantGenerator. */
    val unbanded: Boolean = false,
    /** When true, the gas giant bake's advection loop adds Venus-style
     *  three-jet zonal shearing (equatorial + two mid-latitude jets) to
     *  bend the swirl field into a Y-chevron. Only meaningful with
     *  `unbanded = true`. Applied to the smallest sub-Neptunes (mass <
     *  6 M⊕, 50 % chance) so they read as an intermediate between
     *  terrestrial Venus analogues (which get chevron via the cloud
     *  overlay path) and full ice giants. See `applyChevron` in
     *  GasGiantGenerator. */
    val chevronJets: Boolean = false,
)
