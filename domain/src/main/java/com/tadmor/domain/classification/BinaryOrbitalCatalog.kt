package com.tadmor.domain.classification

/**
 * Catalog of known binary star orbital elements for circumbinary planet systems.
 * Data sourced from Thébault (LIRA/Observatoire de Paris) compilation and discovery papers.
 * Only systems with measured orbital solutions are included (not projected separations).
 *
 * Systems not found here fall back to Holman & Wiegert (1999) stability-limit estimation.
 */
object BinaryOrbitalCatalog {

    data class Elements(
        val semiMajorAxisAU: Double,
        val eccentricity: Double,
        val argPeriapsisDeg: Double? = null,
    )

    /**
     * Look up binary orbital elements by system name (sy_name from NASA archive).
     * Returns null if the system is not in the catalog.
     */
    fun lookup(syName: String): Elements? = CATALOG[syName]

    private val CATALOG = mapOf(
        // Post-common-envelope binaries (circular, very tight)
        "DP Leo" to Elements(0.0027, 0.0),
        "V808 Aur" to Elements(0.0035, 0.0),
        "NN Ser" to Elements(0.0039, 0.0),
        "Kepler-451" to Elements(0.0041, 0.0),
        "NY Vir" to Elements(0.0044, 0.0),
        "DD CrB" to Elements(0.0047, 0.0),
        "BX Tri" to Elements(0.0060, 0.0),
        "RR Cae" to Elements(0.0076, 0.0),
        "KIC 3853259" to Elements(0.0100, 0.0),
        // Kepler transit systems — ω from discovery papers
        "Kepler-47" to Elements(0.0836, 0.023, 212.5),
        "Kepler-413" to Elements(0.1015, 0.040, 279.5),
        "BEBOP-3" to Elements(0.1210, 0.063),
        "TOI-2924" to Elements(0.1210, 0.063), // alias for BEBOP-3
        "Kepler-1647" to Elements(0.1276, 0.160, 300.7),
        "TOI-1338" to Elements(0.1310, 0.160, 75.6),
        "Kepler-38" to Elements(0.1470, 0.103, 105.0),
        "Kepler-35" to Elements(0.1770, 0.142, 89.2),
        "Kepler-64" to Elements(0.1770, 0.204, 217.6),
        "PH1" to Elements(0.1770, 0.204, 217.6), // alias for Kepler-64
        "Kepler-453" to Elements(0.1848, 0.052, 251.8),
        "HD 143811" to Elements(0.1854, 0.495),
        "Kepler-1661" to Elements(0.1900, 0.112, 287.0),
        "KIC 7177553" to Elements(0.1914, 0.398),
        "TIC 172900988" to Elements(0.1918, 0.448, 54.7),
        "PSR B1620-26" to Elements(0.2000, 0.025, 117.0),
        "Kepler-1660" to Elements(0.2040, 0.503),
        "Kepler-16" to Elements(0.2240, 0.159, 263.5),
        "Kepler-34" to Elements(0.2290, 0.521, 71.0),
        "KIC 7821010" to Elements(0.2889, 0.679),
        "HD 106906" to Elements(0.6000, 0.660),
    )
}
