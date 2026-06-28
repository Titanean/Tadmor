package com.tadmor.domain.usecase

import com.tadmor.domain.model.CatalogEntry
import com.tadmor.domain.repository.PlanetRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Observes the unconfirmed-candidate / false-positive catalog, paralleling
 * [ObserveCatalogUseCase] for confirmed planets. Used by [CatalogViewModel]
 * to populate the CANDIDATES + FALSE POSITIVES sub-tabs.
 *
 * For per-system filtering (System tab), [ObserveSystemDetailUseCase]
 * subscribes to the raw candidate flow directly and classifies only the
 * matching subset — running the full ~17,000-row classification on every
 * navigation would be a noticeable freeze.
 */
class ObserveCandidatesUseCase(
    private val repository: PlanetRepository,
) {
    operator fun invoke(): Flow<List<CatalogEntry>> {
        return combine(
            repository.observeAllCandidates(),
            repository.observeAllPlanets(),
            repository.observeAllStars(),
        ) { candidates, planets, stars ->
            val index = CandidateClassifier.buildIndex(stars, planets)
            candidates.mapNotNull { candidate ->
                val matchedStar = CandidateClassifier.resolveStar(candidate, index)
                val resolvedHostname = matchedStar?.hostname ?: candidate.hostname
                if (CandidateClassifier.shouldDedup(candidate, resolvedHostname, index)) {
                    return@mapNotNull null
                }
                CandidateClassifier.classify(candidate, matchedStar, resolvedHostname)
            }
        }
    }
}
