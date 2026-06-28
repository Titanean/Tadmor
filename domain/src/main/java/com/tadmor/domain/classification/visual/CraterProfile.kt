package com.tadmor.domain.classification.visual

/**
 * Crater bombardment parameters. The renderer (phase 7) will simulate
 * individual crater impacts on the height map using these parameters.
 */
data class CraterProfile(
    val density: Float,
    val degradation: Float,
    val sizeExponent: Float,
    val regionalVariation: Float,
    val maxCraterScale: Float,
)
