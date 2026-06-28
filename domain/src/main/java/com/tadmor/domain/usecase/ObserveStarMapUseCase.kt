package com.tadmor.domain.usecase

import com.tadmor.domain.model.Star
import com.tadmor.domain.repository.PlanetRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Observes all stars that have valid coordinates for 3D positioning.
 * Stars missing RA, Dec, or distance cannot be placed on the map and are excluded.
 */
class ObserveStarMapUseCase(
    private val repository: PlanetRepository,
) {

    operator fun invoke(): Flow<List<Star>> =
        repository.observeAllStars().map { stars ->
            stars.filter { it.rightAscensionDeg != null && it.declinationDeg != null && it.distancePc != null }
        }
}
