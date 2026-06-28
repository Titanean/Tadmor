package com.tadmor.domain.classification.visual

import com.tadmor.domain.classification.PlanetClassification
import com.tadmor.domain.classification.TemperatureClass
import com.tadmor.domain.model.Planet
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Assigns a [BulkClass] (Ferria/Carbonia/Aquaria/Terra) to a terrestrial planet.
 *
 * Represents the planet's **bulk interior composition**, not its surface.
 * A Ferria world has an iron-dominated interior but may have a silicate crust.
 * An Aquaria world has a water-dominated bulk but its surface phase depends on
 * temperature (ice, liquid ocean, or steam).
 *
 * Classification priority:
 * 1. Known density → direct classification (strongest signal)
 * 2. Orbital context → inside/outside ice line, proximity to star
 * 3. Star metallicity → iron enrichment bias
 * 4. Random fallback with physically motivated probabilities
 */
object BulkClassifier {

    private const val EARTH_RADIUS_CM = 6.371e8
    private const val EARTH_MASS_G = 5.972e27

    fun classify(
        planet: Planet,
        classification: PlanetClassification,
        context: SystemContext,
        rng: DeterministicRandom,
    ): BulkClass {
        val density = planet.densityGCm3 ?: computeDensity(planet)
        val tempClass = classification.temperatureClass
        val metallicity = context.starMetallicity ?: 0.0
        val smaAU = planet.semiMajorAxisAU
            ?: classification.estimatedSemiMajorAxisAU

        // ── Density gates (strongest physical signal) ──
        // Density constrains which bulk classes are physically possible.
        // Water worlds (Aquaria) are low-density (~2–3 g/cm³).
        // Iron worlds (Ferria) are high-density (>5.5 g/cm³).
        // Earth-like density (~5.5) rules out Aquaria entirely.
        val densityRulesOutAquaria = density != null && density > 4.0
        val densityRulesOutFerria = density != null && density < 4.0

        if (density != null) {
            // Very high density → iron-dominated interior
            if (density > 7.0) return BulkClass.FERRIA
            // Medium-high + close-in → likely Ferria (volatiles stripped)
            if (density > 5.5 && smaAU != null && smaAU < 0.3) return BulkClass.FERRIA
            // Earth-like density range (4.5–6.5) → almost certainly Terra or Ferria
            if (density in 4.5..6.5) {
                // Could still be Ferria if high metallicity and dense
                if (density > 5.5 && metallicity > 0.2 && rng.chance(0.25f)) {
                    return BulkClass.FERRIA
                }
                // Carbonia extremely unlikely at this density, but not impossible
                if (rng.chance(0.01f)) return BulkClass.CARBONIA
                return BulkClass.TERRA
            }
            // Low density (< 3.0) → likely water-rich, BUT only if cool enough
            // to retain water. Hot/warm low-density bodies have been desiccated
            // over Gyr — their low density is from low-iron silicate composition,
            // not retained water. Tiny hot bodies (Kepler-138b) especially.
            val tooHotForWater = tempClass == TemperatureClass.HOT ||
                tempClass == TemperatureClass.TORRID
            val warmAndSmall = tempClass == TemperatureClass.WARM &&
                (planet.massEarth ?: 1.0) < 1.0

            if (density < 2.0 && !tooHotForWater && !warmAndSmall) {
                return BulkClass.AQUARIA
            }
            if (density < 3.0 && (tempClass == TemperatureClass.FRIGID ||
                    tempClass == TemperatureClass.COLD)) return BulkClass.AQUARIA
            if (density < 3.0 && !tooHotForWater && !warmAndSmall) {
                if (rng.chance(0.40f)) return BulkClass.AQUARIA
            }
            // Intermediate density (3.0–4.5): ambiguous zone
            // Could be low-iron Terra or high-rock Aquaria
            if (density < 3.5 && (tempClass == TemperatureClass.FRIGID ||
                    tempClass == TemperatureClass.COLD)) {
                if (rng.chance(0.30f)) return BulkClass.AQUARIA
            }
            // Otherwise fall through to heuristics, but density gates still apply
        }

        // ── Orbital context ──
        val iceLine = estimateIceLine(context)
        val insideIceLine = iceLine != null && smaAU != null && smaAU < iceLine

        // M dwarf activity penalty: active M dwarfs strip volatiles from close-in planets.
        val starTeff = context.starTeffK ?: 5778.0
        val isMDwarf = starTeff < 4000
        val closeInAroundMDwarf = isMDwarf && smaAU != null && smaAU < 0.15

        if (smaAU != null) {
            // Very close-in + hot → volatiles stripped, likely iron-rich
            if (!densityRulesOutFerria && smaAU < 0.05 &&
                (tempClass == TemperatureClass.TORRID || tempClass == TemperatureClass.HOT)) {
                val ferriaChance = 0.35f + (metallicity * 0.15f).toFloat().coerceIn(-0.1f, 0.2f)
                if (rng.chance(ferriaChance)) return BulkClass.FERRIA
            }

            // Close-in around M dwarf + not cold → Ferria candidate
            if (!densityRulesOutFerria && closeInAroundMDwarf &&
                tempClass != TemperatureClass.FRIGID && tempClass != TemperatureClass.COLD) {
                val ferriaChance = 0.20f + (metallicity * 0.10f).toFloat().coerceIn(-0.05f, 0.10f)
                if (rng.chance(ferriaChance)) return BulkClass.FERRIA
            }

            // Beyond ice line → water-rich candidate (accreted beyond snow line)
            if (!densityRulesOutAquaria && iceLine != null && smaAU > iceLine) {
                val aquariaChance = 0.40f + if (tempClass == TemperatureClass.FRIGID ||
                    tempClass == TemperatureClass.COLD) 0.15f else 0f
                if (rng.chance(aquariaChance)) return BulkClass.AQUARIA
            }
        }

        // ── High metallicity bias toward Ferria ──
        if (!densityRulesOutFerria && metallicity > 0.3 && rng.chance(0.20f)) {
            return BulkClass.FERRIA
        }

        // ── Carbonia is rare ──
        if (rng.chance(0.03f)) return BulkClass.CARBONIA

        // ── Aquaria: only for planets with plausible water retention ──
        // Gated by: density (if known), ice line, M dwarf proximity
        if (!densityRulesOutAquaria && !closeInAroundMDwarf && !insideIceLine) {
            if (tempClass == TemperatureClass.COOL) {
                if (rng.chance(0.12f)) return BulkClass.AQUARIA
            }
            if (tempClass == TemperatureClass.TEMPERATE) {
                if (rng.chance(0.08f)) return BulkClass.AQUARIA
            }
        }

        // Default: Terra (most common)
        return BulkClass.TERRA
    }

    /**
     * Rough estimate of the ice line in AU.
     * Beyond this distance, water ice is stable and planets accrete more volatiles.
     */
    private fun estimateIceLine(context: SystemContext): Double? {
        val lum = context.starLuminosity ?: return null
        return 2.7 * sqrt(lum) // ~2.7 AU for solar luminosity
    }

    private fun computeDensity(planet: Planet): Double? {
        val massE = planet.massEarth ?: return null
        val radiusE = planet.radiusEarth ?: return null
        if (radiusE <= 0) return null
        val massG = massE * EARTH_MASS_G
        val radiusCm = radiusE * EARTH_RADIUS_CM
        val volumeCm3 = (4.0 / 3.0) * PI * radiusCm.pow(3)
        return massG / volumeCm3
    }
}
