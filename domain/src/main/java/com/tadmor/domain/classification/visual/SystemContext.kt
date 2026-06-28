package com.tadmor.domain.classification.visual

/**
 * System-level data influencing individual planet visual profiles.
 * Built from [Star] and system-wide planet data.
 */
data class SystemContext(
    val starTeffK: Double?,
    val starMetallicity: Double?,
    val starAge: Double?,
    val starLuminosity: Double?,
    val starRadiusSolar: Double?,
    val starMassSolar: Double?,
    val starLogg: Double?,
    val starRotationPeriodDays: Double?,
    val isCircumbinary: Boolean,
    val planetCount: Int,
    val innerPlanetSmaAU: Double?,
    val outerPlanetSmaAU: Double?,
)
