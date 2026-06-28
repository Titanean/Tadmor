package com.tadmor.domain.usecase

import com.tadmor.domain.classification.PlanetClassifier
import com.tadmor.domain.classification.visual.SystemContext
import com.tadmor.domain.classification.visual.VisualProfileEngine
import com.tadmor.domain.model.CandidatePlanet
import com.tadmor.domain.model.Disposition
import com.tadmor.domain.model.Planet
import com.tadmor.domain.model.Star
import com.tadmor.domain.model.SystemDetail
import com.tadmor.domain.model.SystemPlanetEntry
import com.tadmor.domain.repository.PlanetRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlin.math.pow

/**
 * Builds the per-system aggregate (host star, confirmed planets, candidates,
 * false positives) shown on the System tab. Subscribes to the *raw*
 * candidate flow rather than [ObserveCandidatesUseCase] — running the full
 * ~17,000-row classification pipeline per emission caused a ~500ms freeze
 * on every Catalog → Planet and Star Map → Orbital navigation. Filtering
 * candidates to the current hostname first and classifying only the
 * (typically 0–10) matching rows is essentially free.
 */
class ObserveSystemDetailUseCase(
    private val repository: PlanetRepository,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(hostname: String): Flow<SystemDetail?> =
        combine(
            repository.observeStarByHostname(hostname),
            repository.observePlanetsByHostname(hostname),
            repository.observeAllCandidates(),
            repository.observeAllStars(),
            repository.observeAllPlanets(),
        ) { star, planets, allCandidates, allStars, allPlanets ->
            SystemDetailInputs(star, planets, allCandidates, allStars, allPlanets)
        }.flatMapLatest { inputs ->
            val rawStar = inputs.star
            val planets = inputs.planets
            val allCandidates = inputs.allCandidates
            val allStars = inputs.allStars
            val allPlanets = inputs.allPlanets

            // Find candidates that resolve to this hostname (TIC match
            // for TOI, direct hostname match for KOI, or the candidate's
            // stored hostname placeholder for un-matched TIC-only / KIC-
            // only systems).
            val index = CandidateClassifier.buildIndex(allStars, allPlanets)
            val matchingCandidates = filterCandidatesForHost(
                hostname = hostname,
                allCandidates = allCandidates,
                index = index,
            )

            // For candidate-only systems (no entry in our `ps`-derived
            // stars table — typically TIC- or KIC-prefixed hostnames),
            // synthesise a Star from the first matching candidate's
            // stellar fields so the System tab renders rather than 404.
            val star = rawStar ?: matchingCandidates.firstOrNull()?.let { firstCand ->
                CandidateClassifier.classify(
                    candidate = firstCand,
                    matchedStar = null,
                    resolvedHostname = hostname,
                ).star
            }
            if (star == null) {
                flowOf(null)
            } else {
                val hasCircumbinary = planets.any { it.cbFlag }
                val companionFlow = if (hasCircumbinary && star.syName != null) {
                    repository.observeCompanionStars(star.syName)
                } else {
                    flowOf(emptyList())
                }
                companionFlow.combine(flowOf(Pair(star, planets))) { companions, (s, p) ->
                    val detail = SystemDetail(
                        star = s,
                        planets = emptyList(),
                        companionStars = companions,
                    )
                    val combinedMass = detail.combinedMassSolar
                    val smaValues = p.mapNotNull { it.semiMajorAxisAU }
                    val context = buildSystemContext(
                        s, detail.isCircumbinary, p.size,
                        smaValues.minOrNull(), smaValues.maxOrNull(),
                    )
                    val confirmedEntries = p.map { planet ->
                        val classification = PlanetClassifier.classify(
                            planet, s,
                            combinedMassSolar = if (planet.cbFlag) combinedMass else null,
                        )
                        SystemPlanetEntry(
                            planet = planet,
                            classification = classification,
                            dataCompleteness = computeCompleteness(planet),
                            visualProfile = VisualProfileEngine.generate(
                                planet, classification, context,
                            ),
                            disposition = Disposition.CONFIRMED,
                        )
                    }.sortedWith(compareBy(nullsLast()) { it.planet.semiMajorAxisAU })

                    // Classify only the matching candidates (small set,
                    // typically 0–10 per system). The full-list classify
                    // happens in ObserveCandidatesUseCase for the catalog.
                    val matchingEntries = matchingCandidates.map { candidate ->
                        val matchedStar = CandidateClassifier.resolveStar(candidate, index)
                        val resolvedHostname = matchedStar?.hostname ?: candidate.hostname
                        val entry = CandidateClassifier.classify(
                            candidate = candidate,
                            matchedStar = matchedStar,
                            resolvedHostname = resolvedHostname,
                        )
                        SystemPlanetEntry(
                            planet = entry.planet,
                            classification = entry.classification,
                            dataCompleteness = entry.dataCompleteness,
                            visualProfile = entry.visualProfile,
                            disposition = entry.disposition,
                        )
                    }
                    val candidateEntries = matchingEntries
                        .filter { it.disposition == Disposition.CANDIDATE }
                        .sortedWith(compareBy(nullsLast()) { it.planet.orbitalPeriodDays })
                    val falsePositiveEntries = matchingEntries
                        .filter { it.disposition == Disposition.FALSE_POSITIVE }
                        .sortedWith(compareBy(nullsLast()) { it.planet.orbitalPeriodDays })

                    detail.copy(
                        planets = confirmedEntries,
                        candidates = candidateEntries,
                        falsePositives = falsePositiveEntries,
                    )
                }
            }
        }

    /**
     * Resolves each candidate's hostname (cheap — no classification) and
     * keeps the ones whose resolved hostname matches the requested
     * [hostname]. Drops CONFIRMED-disposition rows that match a row in
     * the confirmed `planets` table (handled inline so the system page
     * doesn't double-list a confirmed planet).
     */
    private fun filterCandidatesForHost(
        hostname: String,
        allCandidates: List<CandidatePlanet>,
        index: CandidateClassifier.Index,
    ): List<CandidatePlanet> = allCandidates.filter { candidate ->
        val matchedStar = CandidateClassifier.resolveStar(candidate, index)
        val resolved = matchedStar?.hostname ?: candidate.hostname
        if (resolved != hostname) return@filter false
        !CandidateClassifier.shouldDedup(candidate, resolved, index)
    }

    private data class SystemDetailInputs(
        val star: Star?,
        val planets: List<Planet>,
        val allCandidates: List<CandidatePlanet>,
        val allStars: List<Star>,
        val allPlanets: List<Planet>,
    )

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

    companion object {
        private const val SOLAR_TEFF = 5778.0

        fun buildSystemContext(
            star: Star,
            isCircumbinary: Boolean,
            planetCount: Int,
            innerSmaAU: Double?,
            outerSmaAU: Double?,
        ): SystemContext {
            val luminosity = star.logLuminosity?.let { 10.0.pow(it) }
                ?: run {
                    val teff = star.teffK ?: return@run null
                    val radius = star.radiusSolar ?: return@run null
                    radius.pow(2) * (teff / SOLAR_TEFF).pow(4)
                }
            return SystemContext(
                starTeffK = star.teffK,
                starMetallicity = star.metallicity,
                starAge = star.age,
                starLuminosity = luminosity,
                starRadiusSolar = star.radiusSolar,
                starMassSolar = star.massSolar,
                starLogg = star.logg,
                starRotationPeriodDays = star.rotationPeriodDays,
                isCircumbinary = isCircumbinary,
                planetCount = planetCount,
                innerPlanetSmaAU = innerSmaAU,
                outerPlanetSmaAU = outerSmaAU,
            )
        }
    }
}
