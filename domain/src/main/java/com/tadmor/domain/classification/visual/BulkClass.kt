package com.tadmor.domain.classification.visual

/**
 * Bulk interior composition class for rocky worlds (SpaceEngine taxonomy).
 *
 * Represents what the planet is fundamentally made of, NOT what you see on
 * the surface. A Ferria world has an iron-dominated interior but may have
 * a silicate crust (like Mercury). Surface composition is generated separately
 * by [SurfaceGenerator], influenced by — but not mechanically constrained to —
 * the bulk class.
 *
 * @param dominantThreshold approximate mass fraction threshold for this class
 *        in the planet's bulk interior (used for reference, not enforced on surface)
 */
enum class BulkClass(val label: String, val dominantThreshold: Float) {
    FERRIA("Ferria", 0.50f),      // iron > 50% of bulk mass
    CARBONIA("Carbonia", 0.25f),  // carbon compounds > 25% of bulk mass
    AQUARIA("Aquaria", 0.25f),    // water (any phase) > 25% of bulk mass
    TERRA("Terra", 0.0f),         // silicate-dominated (default)
}
