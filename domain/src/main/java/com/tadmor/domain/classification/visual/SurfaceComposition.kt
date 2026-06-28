package com.tadmor.domain.classification.visual

/**
 * Percentage-based surface materials for rocky worlds.
 * Each material's visual color is resolved by the renderer using temperature/pressure context.
 * Percentages should sum to ~1.0.
 */
data class SurfaceComposition(
    val silicates: Float = 0f,
    val iron: Float = 0f,
    val water: Float = 0f,
    val sulfur: Float = 0f,
    val carbon: Float = 0f,
    val nitrogen: Float = 0f,
    val methane: Float = 0f,
    val ammonia: Float = 0f,
    val tholins: Float = 0f,
)
