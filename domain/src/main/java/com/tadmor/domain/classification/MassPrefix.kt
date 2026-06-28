package com.tadmor.domain.classification

/**
 * Mass prefixes from SpaceEngine's classification system.
 * SPEC.md Section 5.1.4. Ranges differ by composition class.
 */
enum class MassPrefix(val label: String) {
    MINI("Mini"),
    SUB("Sub"),
    STANDARD(""),
    SUPER("Super"),
    MEGA("Mega");

    companion object {
        /** For solid/terrestrial planets in Earth masses. */
        fun fromSolidMass(massEarth: Double): MassPrefix = when {
            massEarth < 0.02 -> MINI
            massEarth < 0.2 -> SUB
            massEarth < 2.0 -> STANDARD
            massEarth < 10.0 -> SUPER
            else -> MEGA
        }

        /** For ice giants (Neptune class) in Earth masses. */
        fun fromNeptuneMass(massEarth: Double): MassPrefix = when {
            massEarth < 4.0 -> MINI
            massEarth < 10.0 -> SUB
            massEarth < 25.0 -> STANDARD
            massEarth < 62.5 -> SUPER
            else -> MEGA
        }

        /** For gas giants (Jupiter class) in Jupiter masses. */
        fun fromJupiterMass(massJupiter: Double): MassPrefix = when {
            massJupiter < 0.2 -> SUB
            massJupiter < 2.0 -> STANDARD
            massJupiter < 10.0 -> SUPER
            else -> MEGA
        }
    }
}
