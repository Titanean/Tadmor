package com.tadmor.domain.usecase

import com.tadmor.domain.classification.PlanetClassifier
import com.tadmor.domain.classification.visual.VisualProfileEngine
import com.tadmor.domain.model.CatalogEntry
import com.tadmor.domain.model.Planet
import com.tadmor.domain.model.Star
import com.tadmor.domain.repository.PlanetRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Combines planet + star flows, runs classification, and produces catalog entries.
 */
class ObserveCatalogUseCase(
    private val repository: PlanetRepository,
) {

    operator fun invoke(): Flow<List<CatalogEntry>> {
        return combine(
            repository.observeAllPlanets(),
            repository.observeAllStars(),
        ) { planets, stars ->
            val starMap = stars.associateBy { it.hostname }
            // Group planets by hostname for context building
            val planetsByHost = planets.groupBy { it.hostname }
            planets.map { planet ->
                val star = starMap[planet.hostname]
                val classification = PlanetClassifier.classify(planet, star)
                val siblings = planetsByHost[planet.hostname] ?: emptyList()
                val smaValues = siblings.mapNotNull { it.semiMajorAxisAU }
                val context = if (star != null) {
                    ObserveSystemDetailUseCase.buildSystemContext(
                        star,
                        isCircumbinary = siblings.any { it.cbFlag },
                        planetCount = siblings.size,
                        innerSmaAU = smaValues.minOrNull(),
                        outerSmaAU = smaValues.maxOrNull(),
                    )
                } else {
                    ObserveSystemDetailUseCase.buildSystemContext(
                        star = Star(
                            hostname = planet.hostname,
                            spectralType = null, teffK = null, teffKLimit = null,
                            radiusSolar = null, radiusSolarLimit = null,
                            massSolar = null, massSolarLimit = null,
                            logLuminosity = null, logLuminosityLimit = null,
                            rightAscensionDeg = null, declinationDeg = null,
                            distancePc = null, planetCount = null,
                        ),
                        isCircumbinary = false,
                        planetCount = siblings.size,
                        innerSmaAU = smaValues.minOrNull(),
                        outerSmaAU = smaValues.maxOrNull(),
                    )
                }
                CatalogEntry(
                    planet = planet,
                    star = star,
                    classification = classification,
                    dataCompleteness = computeCompleteness(planet),
                    visualProfile = VisualProfileEngine.generate(
                        planet, classification, context,
                    ),
                )
            }
        }
    }

    private fun computeCompleteness(planet: Planet): Int {
        var count = 0
        if (planet.massEarth != null || planet.massJupiter != null) count++
        if (planet.radiusEarth != null) count++
        if (planet.eqTempK != null) count++
        if (planet.densityGCm3 != null) count++
        if (planet.orbitalPeriodDays != null) count++
        if (planet.eccentricity != null) count++
        return count
    }
}
