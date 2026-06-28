package com.tadmor.domain.classification.visual

/**
 * Renderer-ready atmospheric optical properties.
 * Parameter model matches the atmosphere shader (atmosphere.html proof-of-concept).
 *
 * All scattering/absorption coefficients are "base" values. The shader multiplies
 * them by `1e-3 × densityMultiplier` to get per-km coefficients. This separation
 * lets the same base coefficients work across wildly different atmospheric densities.
 *
 * Units:
 * - Scale heights, thickness, altitudes: km
 * - Density: relative to Earth (1.0 = Earth surface density ≈ 1.225 kg/m³)
 * - Scattering/absorption: dimensionless base coefficients (shader scales them)
 * - Colors: ARGB Long
 */
data class AtmosphereOptics(
    // ── Atmosphere extent ──
    val atmosphereThicknessKm: Float,     // visible extent above surface

    // ── Rayleigh scattering ──
    val rayleighScaleHeightKm: Float,     // H = kT/(μg), in km
    val rayleighR: Float,                 // scattering coefficient, red channel
    val rayleighG: Float,                 // scattering coefficient, green channel
    val rayleighB: Float,                 // scattering coefficient, blue channel

    // ── Atmospheric density ──
    val densityMultiplier: Float,         // surface density relative to Earth (1.0)

    // ── Mie scattering (aerosols / dust) ──
    val mieScaleHeightKm: Float,          // dust concentration falloff height
    val mieR: Float,                      // scattering coefficient, red
    val mieG: Float,                      // scattering coefficient, green
    val mieB: Float,                      // scattering coefficient, blue
    val miePhaseG: Float,                 // forward scatter anisotropy [0, 1)
    val miePhaseG2: Float,                // back-scatter lobe [-1, 0]
    val miePhaseBlend: Float,             // blend between forward and back [0, 1]
    val mieDirtiness: Float,              // spatial noise modulation [0, 1]
    val mieAbsorptionR: Float,            // absorption coefficient, red
    val mieAbsorptionG: Float,            // absorption coefficient, green
    val mieAbsorptionB: Float,            // absorption coefficient, blue

    // ── Absorption band (ozone-like; also used for CH4 bands on ice giants) ──
    val ozoneR: Float,                    // absorption coefficient, red
    val ozoneG: Float,                    // absorption coefficient, green
    val ozoneB: Float,                    // absorption coefficient, blue
    val ozoneCenterKm: Float,             // layer center altitude
    val ozoneWidthKm: Float,              // layer thickness

    // ── Clouds ──
    val cloudType: CloudType = CloudType.NONE,
    val cloudColor: Long,                 // ARGB
    val cloudCoverage: Float,             // [0, 1]
    val cloudAltitudeKm: Float,           // km above surface
    val cloudDensity: Float,              // opacity [0, 1]
    val cloudSize: Float,                 // scale of cloud formations
    val cloudDistortion: Float,           // turbulence in cloud patterns
    val cloudBumpiness: Float,            // 3D relief appearance
    val cloudBanding: Float,              // latitudinal band strength

    // ── Fog ──
    val fogColor: Long,                   // ARGB
    val fogDensity: Float,                // opacity [0, 1]
    val fogScaleHeightKm: Float,          // altitude falloff
    val fogPatchiness: Float,             // spatial variation [0, 1]

    // ── Star illumination ──
    val starTintColor: Long,              // ARGB star color from Teff
    val sunIntensity: Float,              // illumination intensity
    val sunDistanceAU: Float,             // orbital distance
) {
    companion object {
        val NONE = AtmosphereOptics(
            atmosphereThicknessKm = 0f,
            rayleighScaleHeightKm = 0f,
            rayleighR = 0f, rayleighG = 0f, rayleighB = 0f,
            densityMultiplier = 0f,
            mieScaleHeightKm = 0f,
            mieR = 0f, mieG = 0f, mieB = 0f,
            miePhaseG = 0.76f, miePhaseG2 = -0.1f, miePhaseBlend = 0.1f,
            mieDirtiness = 0f,
            mieAbsorptionR = 0f, mieAbsorptionG = 0f, mieAbsorptionB = 0f,
            ozoneR = 0f, ozoneG = 0f, ozoneB = 0f,
            ozoneCenterKm = 25f, ozoneWidthKm = 15f,
            cloudColor = 0L, cloudCoverage = 0f, cloudAltitudeKm = 0f,
            cloudDensity = 0f, cloudSize = 1f, cloudDistortion = 0f,
            cloudBumpiness = 0f, cloudBanding = 0f,
            fogColor = 0L, fogDensity = 0f, fogScaleHeightKm = 1f, fogPatchiness = 0f,
            starTintColor = 0xFFFFFFFF.toLong(),
            sunIntensity = 0f, sunDistanceAU = 0f,
        )
    }
}
