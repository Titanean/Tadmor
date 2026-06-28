package com.tadmor.domain.classification.visual

/**
 * Molecular percentages of a planet's atmosphere.
 * Built from physical/chemical rules, not rigid labels.
 * Optical properties (Rayleigh, clouds, etc.) are derived from this.
 */
data class AtmosphericComposition(
    val present: Boolean,
    val surfacePressureBar: Float,
    val h2: Float = 0f,
    val he: Float = 0f,
    val n2: Float = 0f,
    val o2: Float = 0f,
    val co2: Float = 0f,
    val h2o: Float = 0f,
    val ch4: Float = 0f,
    val nh3: Float = 0f,
    val so2: Float = 0f,
    val h2s: Float = 0f,
) {
    companion object {
        val NONE = AtmosphericComposition(present = false, surfacePressureBar = 0f)
    }

    /** Mean molecular weight in g/mol, weighted by molar fractions. */
    val meanMolecularWeight: Float get() {
        if (!present) return 0f
        return h2 * 2.016f + he * 4.003f + n2 * 28.014f + o2 * 31.998f +
            co2 * 44.01f + h2o * 18.015f + ch4 * 16.043f + nh3 * 17.031f +
            so2 * 64.066f + h2s * 34.08f
    }
}
