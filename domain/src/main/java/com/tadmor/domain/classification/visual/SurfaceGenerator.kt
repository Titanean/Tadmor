package com.tadmor.domain.classification.visual

import com.tadmor.domain.classification.PlanetClassification
import com.tadmor.domain.classification.TemperatureClass

/**
 * Generates [SurfaceComposition] — the **crustal/visible** materials on the planet's
 * surface, not the bulk interior composition.
 *
 * [BulkClass] determines what materials are available from the interior:
 * - FERRIA: iron-rich interior → iron exposed at impact sites, iron oxide weathering
 * - TERRA: silicate mantle → silicate-dominated crust, water if temperature allows
 * - AQUARIA: water-rich bulk → water/ice dominates surface, some exposed rock
 * - CARBONIA: carbon-rich interior → dark graphite/carbide surfaces
 *
 * Temperature, atmosphere, and weathering then determine what you actually see.
 * A Ferria world with a thick atmosphere has weathered silicate crust with iron oxides.
 * A tiny stripped Ferria core (Psyche-like) is exposed metallic iron.
 *
 * Tholins are NOT assigned here — computed by [AtmosphereGenerator].
 */
object SurfaceGenerator {

    fun generate(
        bulkClass: BulkClass,
        classification: PlanetClassification,
        context: SystemContext,
        rng: DeterministicRandom,
    ): SurfaceComposition = when (bulkClass) {
        BulkClass.FERRIA -> generateFerria(classification, context, rng)
        BulkClass.CARBONIA -> generateCarbonia(classification, context, rng)
        BulkClass.AQUARIA -> generateAquaria(classification, context, rng)
        BulkClass.TERRA -> generateTerra(classification, context, rng)
    }

    /**
     * FERRIA surface: iron-dominated interior, but surface depends on context.
     *
     * - Large + atmosphere → weathered silicate crust, iron oxides (Mars-like rust)
     * - Small + airless → cratered, exposed iron at impact sites (Mercury-like)
     * - Very small / stripped core → mostly metallic iron (Psyche-like)
     * - Hot → possible molten iron pools, higher iron visibility
     * - Volcanic → iron-rich lava flows
     */
    private fun generateFerria(
        classification: PlanetClassification,
        context: SystemContext,
        rng: DeterministicRandom,
    ): SurfaceComposition {
        val tempClass = classification.temperatureClass
        val met = ((context.starMetallicity ?: 0.0) * 0.05).toFloat().coerceIn(-0.05f, 0.08f)

        // Estimate if this is a stripped core (very small mass) or a differentiated planet
        val massE = classification.estimatedSemiMajorAxisAU?.let { 1.0 } // placeholder if unknown

        // Base: differentiated Ferria has a silicate crust with iron exposure
        // More iron reaches the surface on small/airless bodies (no weathering layer)
        val isSmall = massE != null && massE < 0.3
        val isHot = tempClass == TemperatureClass.TORRID || tempClass == TemperatureClass.HOT

        val iron: Float
        val silicates: Float
        if (isSmall) {
            // Small/stripped: more exposed metallic iron (Psyche-like)
            iron = rng.nextFloat(0.35f, 0.65f) + met
            silicates = rng.nextFloat(0.20f, 0.40f)
        } else if (isHot) {
            // Hot Ferria: some molten metal exposure, iron oxide surfaces
            iron = rng.nextFloat(0.20f, 0.45f) + met
            silicates = rng.nextFloat(0.30f, 0.50f)
        } else {
            // Standard differentiated Ferria: silicate crust dominates,
            // iron exposed at impact basins and as oxide weathering products
            iron = rng.nextFloat(0.10f, 0.30f) + met
            silicates = rng.nextFloat(0.45f, 0.65f)
        }

        val sulfur = rng.nextFloat(0.0f, 0.10f)
        val carbon = rng.nextFloat(0.0f, 0.06f)

        return normalize(iron = iron, silicates = silicates, sulfur = sulfur, carbon = carbon)
    }

    /**
     * CARBONIA surface: carbon-rich interior → dark surfaces.
     * Graphite, silicon carbide, possible diamond at depth.
     */
    private fun generateCarbonia(
        classification: PlanetClassification,
        context: SystemContext,
        rng: DeterministicRandom,
    ): SurfaceComposition {
        val carbon = rng.nextFloat(0.25f, 0.50f)
        val silicates = rng.nextFloat(0.20f, 0.40f)
        val iron = rng.nextFloat(0.05f, 0.20f)
        val sulfur = rng.nextFloat(0.0f, 0.08f)
        return normalize(carbon = carbon, silicates = silicates, iron = iron, sulfur = sulfur)
    }

    /**
     * AQUARIA surface: water-dominated bulk → surface phase from temperature.
     *
     * - Frigid/Cold: ice world — water ice dominates, with ammonia and methane ices
     * - Cool: partial ice + exposed rock at equator
     * - Temperate: global or near-global ocean with some exposed land
     * - Warm: ocean world with steam-heavy atmosphere, less visible land
     * - Hot: steam envelope, surface may be hidden; residual water in crust
     */
    private fun generateAquaria(
        classification: PlanetClassification,
        context: SystemContext,
        rng: DeterministicRandom,
    ): SurfaceComposition {
        val tempClass = classification.temperatureClass

        val water: Float
        val silicates: Float
        val ammonia: Float
        val methane: Float
        val nitrogen: Float

        when (tempClass) {
            TemperatureClass.FRIGID -> {
                // Deep frozen: water ice + exotic ices, minimal exposed rock
                water = rng.nextFloat(0.45f, 0.70f)
                silicates = rng.nextFloat(0.05f, 0.20f)
                ammonia = rng.nextFloat(0.05f, 0.18f)
                methane = rng.nextFloat(0.03f, 0.12f)
                nitrogen = rng.nextFloat(0.02f, 0.08f)
            }
            TemperatureClass.COLD -> {
                // Ice world: mostly water ice, some ammonia frost
                water = rng.nextFloat(0.50f, 0.75f)
                silicates = rng.nextFloat(0.10f, 0.25f)
                ammonia = rng.nextFloat(0.0f, 0.12f)
                methane = rng.nextFloat(0.0f, 0.06f)
                nitrogen = 0f
            }
            TemperatureClass.COOL -> {
                // Partial ice: frozen at poles, exposed rock at equator
                water = rng.nextFloat(0.35f, 0.60f)
                silicates = rng.nextFloat(0.20f, 0.40f)
                ammonia = 0f
                methane = 0f
                nitrogen = 0f
            }
            TemperatureClass.TEMPERATE -> {
                // Ocean world: global/near-global liquid water, some land
                water = rng.nextFloat(0.50f, 0.80f)
                silicates = rng.nextFloat(0.10f, 0.30f)
                ammonia = 0f
                methane = 0f
                nitrogen = 0f
            }
            TemperatureClass.WARM -> {
                // Hot ocean: evaporating, steam-heavy, less visible surface
                water = rng.nextFloat(0.30f, 0.55f)
                silicates = rng.nextFloat(0.25f, 0.45f)
                ammonia = 0f
                methane = 0f
                nitrogen = 0f
            }
            else -> {
                // Hot/Torrid: water mostly lost to steam, residual in crust
                water = rng.nextFloat(0.05f, 0.20f)
                silicates = rng.nextFloat(0.50f, 0.70f)
                ammonia = 0f
                methane = 0f
                nitrogen = 0f
            }
        }

        val carbon = rng.nextFloat(0.0f, 0.08f)

        return normalize(
            water = water, silicates = silicates, ammonia = ammonia,
            methane = methane, nitrogen = nitrogen, carbon = carbon,
        )
    }

    /**
     * TERRA surface: silicate-dominated interior → silicate crust.
     *
     * Water on the surface comes from outgassing and volatile delivery, not
     * from bulk composition. A Terra world CAN have oceans — Earth is Terra
     * (0.02% water by mass) but has 71% ocean surface coverage.
     *
     * Temperature determines water phase and availability:
     * - Temperate: liquid water oceans possible (thin veneer over rock)
     * - Cool: partial ice + rock
     * - Cold/Frigid: ice deposits, frozen volatiles
     * - Warm: trace water, mostly evaporated
     * - Hot: no surface water
     */
    private fun generateTerra(
        classification: PlanetClassification,
        context: SystemContext,
        rng: DeterministicRandom,
    ): SurfaceComposition {
        val tempClass = classification.temperatureClass
        val met = ((context.starMetallicity ?: 0.0) * 0.04).toFloat().coerceIn(-0.04f, 0.06f)

        val silicates = rng.nextFloat(0.40f, 0.65f)
        val iron = rng.nextFloat(0.05f, 0.25f) + met

        // Surface water: thin veneer from outgassing/delivery, not bulk.
        // Earth has ~0.02% water by mass but 71% surface coverage.
        val water = when (tempClass) {
            TemperatureClass.TEMPERATE -> rng.nextFloat(0.0f, 0.30f) // can have oceans
            TemperatureClass.COOL -> rng.nextFloat(0.0f, 0.20f) // partial ice/liquid
            TemperatureClass.COLD -> rng.nextFloat(0.0f, 0.12f) // ice deposits
            TemperatureClass.FRIGID -> rng.nextFloat(0.02f, 0.10f) // frozen volatiles
            TemperatureClass.WARM -> rng.nextFloat(0.0f, 0.05f) // mostly evaporated
            else -> 0f // hot/torrid: water lost
        }

        val sulfur = rng.nextFloat(0.0f, 0.08f)
        val carbon = rng.nextFloat(0.0f, 0.08f)
        val nitrogen = if (tempClass == TemperatureClass.FRIGID)
            rng.nextFloat(0.0f, 0.05f) else 0f

        return normalize(
            silicates = silicates, iron = iron, water = water,
            sulfur = sulfur, carbon = carbon, nitrogen = nitrogen,
        )
    }

    /**
     * Normalizes all components to sum to 1.0.
     */
    private fun normalize(
        silicates: Float = 0f,
        iron: Float = 0f,
        water: Float = 0f,
        sulfur: Float = 0f,
        carbon: Float = 0f,
        nitrogen: Float = 0f,
        methane: Float = 0f,
        ammonia: Float = 0f,
        tholins: Float = 0f,
    ): SurfaceComposition {
        val total = silicates + iron + water + sulfur + carbon +
            nitrogen + methane + ammonia + tholins
        if (total <= 0f) return SurfaceComposition(silicates = 1f)
        val s = 1f / total
        return SurfaceComposition(
            silicates = silicates * s,
            iron = iron * s,
            water = water * s,
            sulfur = sulfur * s,
            carbon = carbon * s,
            nitrogen = nitrogen * s,
            methane = methane * s,
            ammonia = ammonia * s,
            tholins = tholins * s,
        )
    }
}
