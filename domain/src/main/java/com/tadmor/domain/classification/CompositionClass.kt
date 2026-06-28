package com.tadmor.domain.classification

/**
 * Bulk composition classes from SpaceEngine's classification system.
 * SPEC.md Section 5.1.3.
 */
enum class CompositionClass(val label: String) {
    TERRA("Terrestrial"),
    NEPTUNE("Neptune"),
    JUPITER("Jupiter"),
}
