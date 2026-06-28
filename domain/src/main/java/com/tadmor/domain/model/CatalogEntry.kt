package com.tadmor.domain.model

import com.tadmor.domain.classification.PlanetClassification
import com.tadmor.domain.classification.visual.VisualProfile

/**
 * A presentation-ready catalog row combining planet, star, and classification data.
 *
 * [disposition] defaults to [Disposition.CONFIRMED] so the existing pipeline
 * (ObserveCatalogUseCase) doesn't need to set it explicitly. Candidate and
 * false-positive entries set it via [ObserveCandidatesUseCase].
 */
data class CatalogEntry(
    val planet: Planet,
    val star: Star?,
    val classification: PlanetClassification,
    val dataCompleteness: Int,
    val visualProfile: VisualProfile,
    val disposition: Disposition = Disposition.CONFIRMED,
)
