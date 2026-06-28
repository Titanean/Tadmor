package com.tadmor.domain.classification

/**
 * Temperature classes from SpaceEngine's classification system.
 * SPEC.md Section 5.1.1. Derived from equilibrium temperature (pl_eqt).
 */
enum class TemperatureClass(
    val label: String,
    val minK: Double,
    val maxK: Double,
) {
    FRIGID("Frigid", 0.0, 90.0),
    COLD("Cold", 90.0, 170.0),
    COOL("Cool", 170.0, 250.0),
    TEMPERATE("Temperate", 250.0, 330.0),
    WARM("Warm", 330.0, 500.0),
    HOT("Hot", 500.0, 1000.0),
    TORRID("Torrid", 1000.0, Double.MAX_VALUE);

    companion object {
        fun fromTemperature(tempK: Double): TemperatureClass =
            entries.first { tempK >= it.minK && tempK < it.maxK }
    }
}
