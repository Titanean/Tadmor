package com.tadmor.domain.classification.visual

import com.tadmor.domain.classification.CompositionClass

object SolarSystemProfiles {

    val EARTH: PlanetIconProfile = PlanetIconProfile(
        bulkClass = BulkClass.TERRA,
        dominantColor = 0xFF0F3D5EL,
        bodyColor = ColorPalettes.WATER_LIQUID[0],      // deep ocean blue
        accentColor = 0xFF3C8C3CL,                      // land green
        bandColors = LongArray(0),
        atmosphereColor = 0xFF80B4E8L,
        atmosphereIntensity = 0.32f,
        cloudColor = 0xFFF0F0F0L,
        cloudCoverage = 0.40f,
        cloudDensity = 0.75f,
        polarCapColor = ColorPalettes.WATER_ICE[0],
        polarCapExtent = 0.20f,
        tidallyLocked = false,
        craterCount = 0,
        hasRings = false,
        ringInnerRatio = 0f,
        ringOuterRatio = 0f,
        ringColor = 0L,
        ringOpacity = 0f,
        landThreshold = 0.60f,
        seed = "Earth".hashCode().toLong(),
    )

    // Neptune bands top-to-bottom: slightly darker blue, blue, very slightly
    // lighter blue, slightly darker blue. Great Dark Spot sits between the
    // two middle bands.
    val NEPTUNE: PlanetIconProfile = PlanetIconProfile(
        bulkClass = null,
        dominantColor = 0xFF3060B0L,
        bodyColor = 0xFF2848B0L,
        accentColor = 0xFF4070C8L,
        bandColors = longArrayOf(
            0xFF2240A0L,   // slightly darker blue (top)
            0xFF2848B0L,   // blue
            0xFF3058BCL,   // very slightly lighter blue
            0xFF2240A0L,   // slightly darker blue (bottom)
        ),
        atmosphereColor = 0xFF80C8E8L,
        atmosphereIntensity = 0.35f,
        cloudColor = 0L,
        cloudCoverage = 0f,
        cloudDensity = 0f,
        polarCapColor = 0L,
        polarCapExtent = 0f,
        tidallyLocked = false,
        craterCount = 0,
        hasRings = false,
        ringInnerRatio = 0f,
        ringOuterRatio = 0f,
        ringColor = 0L,
        ringOpacity = 0f,
        landThreshold = 0f,
        spotColor = 0xFF1C3890L,        // slightly darker blue oval
        spotY = 0.50f,                   // between the two middle bands
        spotWidthFrac = 0.18f,
        spotHeightFrac = 0.10f,
        seed = "Neptune".hashCode().toLong(),
    )

    // Jupiter bands top-to-bottom: cream, white, orange, white, orange, white,
    // cream. Great Red Spot sits between the bottom orange and white bands.
    val JUPITER: PlanetIconProfile = PlanetIconProfile(
        bulkClass = null,
        dominantColor = 0xFFD8B878L,
        bodyColor = 0xFFE8D8B8L,
        accentColor = 0xFFC87838L,
        bandColors = longArrayOf(
            0xFFE8D8B8L,   // cream (top)
            0xFFF0ECE4L,   // white
            0xFFC87838L,   // orange
            0xFFF0ECE4L,   // white
            0xFFC87838L,   // orange
            0xFFF0ECE4L,   // white
            0xFFE8D8B8L,   // cream (bottom)
        ),
        atmosphereColor = 0xFF90B8E8L,
        atmosphereIntensity = 0.35f,
        cloudColor = 0L,
        cloudCoverage = 0f,
        cloudDensity = 0f,
        polarCapColor = 0L,
        polarCapExtent = 0f,
        tidallyLocked = false,
        craterCount = 0,
        hasRings = false,
        ringInnerRatio = 0f,
        ringOuterRatio = 0f,
        ringColor = 0L,
        ringOpacity = 0f,
        landThreshold = 0f,
        spotColor = 0xFFC04020L,         // Great Red Spot
        spotY = 0.715f,                   // between bottom orange and white bands
        spotWidthFrac = 0.16f,
        spotHeightFrac = 0.08f,
        seed = "Jupiter".hashCode().toLong(),
    )

    enum class SolarPlanet(
        val profile: PlanetIconProfile,
        val radiusEarth: Float,
        val displayName: String,
    ) {
        EARTH(SolarSystemProfiles.EARTH, 1.0f, "Earth"),
        NEPTUNE(SolarSystemProfiles.NEPTUNE, 3.88f, "Neptune"),
        JUPITER(SolarSystemProfiles.JUPITER, 11.21f, "Jupiter"),
    }

    fun defaultFor(compositionClass: CompositionClass): SolarPlanet = when (compositionClass) {
        CompositionClass.TERRA -> SolarPlanet.EARTH
        CompositionClass.NEPTUNE -> SolarPlanet.NEPTUNE
        CompositionClass.JUPITER -> SolarPlanet.JUPITER
    }
}
