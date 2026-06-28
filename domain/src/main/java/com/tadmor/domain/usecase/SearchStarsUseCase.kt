package com.tadmor.domain.usecase

import com.tadmor.domain.model.ProperNames
import com.tadmor.domain.model.Star
import com.tadmor.domain.repository.PlanetRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class SearchStarsUseCase(
    private val repository: PlanetRepository,
) {
    /**
     * Search stars by hostname OR IAU proper name.
     * Combines the SQL LIKE search on hostname with a lookup of hostnames
     * whose proper star names match the query.
     */
    operator fun invoke(query: String): Flow<List<Star>> {
        val q = query.lowercase()
        // Find hostnames whose proper name matches the query
        val properNameHostnames = ProperNames.findStarHostnamesByProperName(q)

        return if (properNameHostnames.isEmpty()) {
            repository.searchStars(query)
        } else {
            combine(
                repository.searchStars(query),
                repository.searchStarsByHostnames(properNameHostnames),
            ) { byHostname, byProperName ->
                (byHostname + byProperName)
                    .distinctBy { it.hostname }
                    .sortedBy { it.hostname }
            }
        }
    }
}
