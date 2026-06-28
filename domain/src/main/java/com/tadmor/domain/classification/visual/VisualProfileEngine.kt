package com.tadmor.domain.classification.visual

import com.tadmor.domain.classification.CompositionClass
import com.tadmor.domain.classification.PlanetClassification
import com.tadmor.domain.classification.TemperatureClass
import com.tadmor.domain.classification.sanitizedForVisuals
import com.tadmor.domain.model.Planet
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Top-level orchestrator: `(Planet, PlanetClassification, SystemContext) → VisualProfile`.
 * Pure function, deterministic via planet name hash seeding.
 */
object VisualProfileEngine {

    fun generate(
        planet: Planet,
        classification: PlanetClassification,
        context: SystemContext,
    ): VisualProfile {
        // Substitute physically-plausible mass / radius for any catalog
        // limit-flagged parameters before they reach the downstream
        // generators. PlanetClassifier does this too — applying it here
        // as well guarantees the visual engine reads the same coherent
        // (mass, radius) pair the classification was derived from,
        // regardless of caller. See PlanetVisualSanitization for rationale.
        val planet = planet.sanitizedForVisuals()
        val seed = planet.name.hashCode().toLong()
        val rng = DeterministicRandom(seed)

        val isGiant = classification.compositionClass == CompositionClass.JUPITER ||
            classification.compositionClass == CompositionClass.NEPTUNE

        // Tidal effects and rotation (computed first — feeds atmosphere retention)
        val tidal = TidalCalculator.compute(planet, classification, context, rng)

        // Volcanic activity (computed before atmosphere — feeds CO2 outgassing)
        val volcanicActivity = computeVolcanicActivity(
            planet, classification, context, isGiant, rng,
        )

        // Surface + atmosphere generation
        val bulkClass: BulkClass?
        val surfaceComposition: SurfaceComposition?
        val gasGiantProfile: GasGiantProfile?
        val atmosphere: AtmosphericComposition

        if (isGiant) {
            bulkClass = null
            surfaceComposition = null
            gasGiantProfile = GasGiantGenerator.generate(planet, classification, context, rng)
            // Pass the resolved gas-giant type so H-stripped variants
            // (HELIUM_NEPTUNE / SILICATE_NEPTUNE) produce a He+CO₂
            // atmosphere instead of the H₂/CH₄ default.
            atmosphere = AtmosphereGenerator.generateGiant(
                classification, context, rng,
                gasGiantType = gasGiantProfile.type,
            )
        } else {
            bulkClass = BulkClassifier.classify(planet, classification, context, rng)
            val baseSurface = SurfaceGenerator.generate(bulkClass, classification, context, rng)
            val (atm, updatedSurface) = AtmosphereGenerator.generateRocky(
                planet, classification, bulkClass, baseSurface, context, rng,
                tidallyLocked = tidal.tidallyLocked,
                rotationPeriodHours = tidal.rotationPeriodHours,
                volcanicActivity = volcanicActivity,
            )
            surfaceComposition = updatedSurface
            atmosphere = atm
            gasGiantProfile = null
        }

        // Surface temperature: equilibrium + greenhouse warming.
        // Gas giants have no solid surface — equilibrium temp IS the relevant
        // temperature (cloud-top radiating level). Greenhouse model is for rocky
        // worlds with a surface-atmosphere boundary only.
        val eqTempK = planet.eqTempK
            ?: classification.estimatedEqTempK
            ?: classification.temperatureClass?.let { (it.minK + it.maxK) / 2.0 }
            ?: 280.0
        val surfaceTempK = if (isGiant) eqTempK
        else computeSurfaceTemperature(eqTempK, atmosphere)

        // Atmosphere optics
        val atmosphereOptics = AtmosphereOpticsDeriver.derive(
            atmosphere, surfaceComposition, planet, classification, context,
            volcanicActivity, isGiant, surfaceTempK, rng,
            gasGiantProfile = gasGiantProfile,
        )

        // Rings
        val ringProfile = RingGenerator.maybeGenerate(planet, classification, context, rng)

        // Sea level
        val seaLevel = computeSeaLevel(
            bulkClass, surfaceComposition, classification, atmosphere, isGiant, rng,
        )

        // Crater profile
        val craterProfile = computeCraterProfile(
            atmosphere, seaLevel, volcanicActivity, context, isGiant, rng,
        )

        // Polar caps
        val polarCapExtent = computePolarCaps(
            classification, surfaceComposition, atmosphere, isGiant,
            tidallyLocked = tidal.tidallyLocked, rng,
        )

        // Terrain roughness
        val roughness = computeRoughness(
            bulkClass, volcanicActivity, seaLevel, isGiant, rng,
        )

        // Albedo
        val albedo = computeAlbedo(
            atmosphere, surfaceComposition, gasGiantProfile, classification, rng,
        )

        // Axial tilt
        val axialTilt = computeAxialTilt(tidal.tidallyLocked, rng)

        return VisualProfile(
            seed = seed,
            bulkClass = bulkClass,
            surfaceComposition = surfaceComposition,
            gasGiantProfile = gasGiantProfile,
            atmosphere = atmosphere,
            atmosphereOptics = atmosphereOptics,
            ringProfile = ringProfile,
            seaLevel = seaLevel,
            volcanicActivity = volcanicActivity,
            craterProfile = craterProfile,
            polarCapExtent = polarCapExtent,
            roughness = roughness,
            albedo = albedo,
            oblateness = tidal.oblateness,
            tidalElongation = tidal.tidalElongation,
            tidallyLocked = tidal.tidallyLocked,
            axialTilt = axialTilt,
            rotationPeriodHours = tidal.rotationPeriodHours,
            surfaceTemperatureK = surfaceTempK.toFloat(),
        )
    }

    /**
     * Sea level [0,1]. Context-dependent:
     * lava on hot Ferria, water on temperate Terra/Aquaria, methane on frigid Aquaria.
     */
    private fun computeSeaLevel(
        bulkClass: BulkClass?,
        surface: SurfaceComposition?,
        classification: PlanetClassification,
        atmosphere: AtmosphericComposition,
        isGiant: Boolean,
        rng: DeterministicRandom,
    ): Float {
        if (isGiant) return 0f
        if (surface == null) return 0f

        val tempClass = classification.temperatureClass

        // Hot worlds: lava seas if iron/silicate rich. Molten rock doesn't need
        // atmospheric pressure to stay liquid.
        if (tempClass == TemperatureClass.TORRID || tempClass == TemperatureClass.HOT) {
            val volcanicPotential = (surface.iron + surface.silicates) * 0.5f
            return (volcanicPotential * rng.nextFloat(0.3f, 0.8f)).coerceIn(0f, 0.6f)
        }

        // Liquid volatile seas (water, methane, ethane) require atmospheric pressure
        // above the triple point (~0.006 bar for water). Below that, volatiles
        // sublimate directly — an airless world can hold ice but not oceans.
        if (atmosphere.surfacePressureBar < 0.01f) return 0f

        // Aquaria: water-based sea level
        if (bulkClass == BulkClass.AQUARIA) {
            return when (tempClass) {
                TemperatureClass.TEMPERATE, TemperatureClass.WARM ->
                    rng.nextFloat(0.40f, 0.75f) // liquid water oceans
                TemperatureClass.COOL ->
                    rng.nextFloat(0.20f, 0.55f) // partial freeze
                TemperatureClass.FRIGID ->
                    rng.nextFloat(0.10f, 0.35f) // methane/ethane lakes
                else -> rng.nextFloat(0.05f, 0.20f)
            }
        }

        // Terra: moderate water if temperate/cool and water-bearing
        if (bulkClass == BulkClass.TERRA) {
            if (surface.water > 0.1f && (tempClass == TemperatureClass.TEMPERATE ||
                    tempClass == TemperatureClass.COOL)) {
                return rng.nextFloat(0.15f, 0.45f) * surface.water * 5f
            }
        }

        return 0f
    }

    /**
     * Volcanic activity [0,1]. Driven by mass (internal heat), tidal heating,
     * age, and temperature.
     *
     * Super-Earths are significantly more volcanic: more radioactive decay,
     * higher mantle pressure, more vigorous convection. Sub-Earth worlds
     * cool faster and have thinner mantles (Mars-like).
     */
    private fun computeVolcanicActivity(
        planet: Planet,
        classification: PlanetClassification,
        context: SystemContext,
        isGiant: Boolean,
        rng: DeterministicRandom,
    ): Float {
        if (isGiant) return 0f

        val massE = planet.massEarth
        val age = context.starAge ?: 4.6

        // Mass-driven internal heat: the primary driver of volcanism.
        // Radiogenic heating ∝ mass. Mantle convection vigor ∝ pressure ∝ mass.
        // Sub-lunar bodies are geologically dead. Mars-sized bodies barely active.
        // Super-Earths have intense mantles that stay active for tens of Gyr.
        val massFactor = if (massE != null) {
            when {
                massE < 0.05 -> rng.nextFloat(0f, 0.01f)    // Moon-sized: dead
                massE < 0.15 -> rng.nextFloat(0.01f, 0.04f)  // Mercury-sized: essentially dead
                massE < 0.4 -> rng.nextFloat(0.04f, 0.10f)   // Mars-sized: sluggish
                massE < 0.8 -> 0.10f + rng.nextFloat(0f, 0.10f) // sub-Earth
                massE < 1.5 -> 0.15f + rng.nextFloat(0f, 0.15f) // Earth-like
                massE < 3.0 -> 0.25f + rng.nextFloat(0.05f, 0.20f) // super-Earth: active
                massE < 6.0 -> 0.35f + rng.nextFloat(0.10f, 0.25f) // large super-Earth
                else -> 0.40f + rng.nextFloat(0.10f, 0.30f)  // massive: intense
            }
        } else {
            0.15f + rng.nextFloat(0f, 0.15f) // unknown mass, assume moderate
        }

        var activity = massFactor

        // Geological cooling: exponential decay with mass-dependent half-life.
        // Small bodies cool fast (Mercury dead in ~1 Gyr). Earth-mass stays warm
        // for ~10 Gyr. Super-Earths essentially never cool.
        // Applied after first Gyr (primordial heat dominates before that).
        if (massE != null && age > 1.0) {
            val halfLifeGyr = massE.coerceAtLeast(0.01) * 10.0  // Earth: 10 Gyr
            val elapsed = age - 1.0  // cooling starts after ~1 Gyr
            val coolingFactor = Math.pow(0.5, elapsed / halfLifeGyr).toFloat()
            activity *= coolingFactor
        }

        // Tidal heating: close-in planets get heated by stellar tides.
        // Scales with mass — larger molten interiors dissipate more tidal energy.
        val smaAU = planet.semiMajorAxisAU
            ?: classification.estimatedSemiMajorAxisAU
        if (smaAU != null && smaAU < 0.1) {
            val massScale = if (massE != null) {
                Math.sqrt(massE.coerceIn(0.01, 20.0) / 1.0).toFloat()
            } else 1f
            activity += (0.1 / smaAU.coerceAtLeast(0.01)).toFloat() * 0.08f * massScale
        }

        // Young systems: more primordial heat remaining
        if (age < 1.0) activity += rng.nextFloat(0.10f, 0.25f)
        else if (age < 2.0) activity += rng.nextFloat(0.05f, 0.15f)

        // Hot worlds: surface heating boosts volcanism, but only for bodies
        // massive enough to have a molten interior to mobilize.
        val tempClass = classification.temperatureClass
        if ((tempClass == TemperatureClass.TORRID || tempClass == TemperatureClass.HOT) &&
            (massE == null || massE > 0.5)) {
            activity += rng.nextFloat(0.05f, 0.15f)
        }

        return activity.coerceIn(0f, 1f)
    }

    /**
     * Crater profile from atmosphere, volcanism, liquids, age.
     */
    private fun computeCraterProfile(
        atmosphere: AtmosphericComposition,
        seaLevel: Float,
        volcanicActivity: Float,
        context: SystemContext,
        isGiant: Boolean,
        rng: DeterministicRandom,
    ): CraterProfile {
        if (isGiant) {
            return CraterProfile(
                density = 0f, degradation = 0f,
                sizeExponent = 3f, regionalVariation = 0f, maxCraterScale = 0f,
            )
        }

        val age = (context.starAge ?: 4.6).toFloat()
        val ageFactor = (age / 4.6f).coerceIn(0.2f, 2f)

        // Atmosphere burns up small impactors AND weathers the surface over time.
        // Earth-like atmospheres (~1 bar) wipe nearly all craters; only very thin
        // atmospheres (Mars at 0.006 bar) preserve most impacts.
        val atmReduction = if (atmosphere.present) {
            // Ramps to 95% by 0.2 bar and plateaus there.
            (atmosphere.surfacePressureBar / 0.2f).coerceIn(0f, 0.95f)
        } else 0f

        // Volcanism resurfaces → strong density reduction. Combined with a full
        // atmosphere, craters are effectively wiped out.
        val volcanicReduction = volcanicActivity * 0.95f

        // Liquids erase craters below sea level
        val liquidReduction = seaLevel * 0.4f

        val baseDensity = rng.nextFloat(0.5f, 0.9f) * ageFactor
        val density = (baseDensity * (1f - atmReduction) * (1f - volcanicReduction) *
            (1f - liquidReduction)).coerceIn(0f, 1f)

        // Degradation: weather + volcanism + liquids erode craters
        val degradation = (atmReduction * 0.5f + volcanicActivity * 0.3f +
            seaLevel * 0.2f + rng.nextFloat(0f, 0.1f)).coerceIn(0f, 1f)

        // Size exponent: thick atmosphere → fewer small craters (lower exponent)
        val sizeExponent = if (atmosphere.present) {
            rng.nextFloat(2.0f, 3.0f) - atmReduction * 0.5f
        } else {
            rng.nextFloat(2.5f, 3.5f) // airless: full power-law
        }

        val regionalVariation = rng.nextFloat(0.1f, 0.6f)
        val maxCraterScale = rng.nextFloat(0.02f, 0.12f)

        return CraterProfile(
            density = density,
            degradation = degradation,
            sizeExponent = sizeExponent.coerceIn(1.5f, 3.5f),
            regionalVariation = regionalVariation,
            maxCraterScale = maxCraterScale,
        )
    }

    /**
     * Polar cap extent [0,1]. From temperature + volatiles.
     */
    private fun computePolarCaps(
        classification: PlanetClassification,
        surface: SurfaceComposition?,
        atmosphere: AtmosphericComposition,
        isGiant: Boolean,
        tidallyLocked: Boolean,
        rng: DeterministicRandom,
    ): Float {
        if (isGiant) return 0f
        if (surface == null) return 0f

        val tempClass = classification.temperatureClass

        // Hot worlds: no polar caps
        if (tempClass == TemperatureClass.TORRID || tempClass == TemperatureClass.HOT) return 0f

        // Volatile availability
        val volatiles = surface.water + surface.methane + surface.ammonia + surface.nitrogen +
            atmosphere.co2 * 0.1f

        if (volatiles < 0.05f) return 0f

        // Tidally-locked worlds condense volatiles on the permanent nightside
        // rather than at geographic poles (which don't exist in a spin-synchronous
        // frame). The "polarCapExtent" is reinterpreted by the renderer as
        // anti-stellar coverage, so higher values → classic eyeball planets.
        //
        // The eyeball look becomes visible at ~50% coverage. Baseline is 0.50
        // (shifted by volatile abundance and temperature), not the narrow-cap
        // range used for rotating worlds.
        if (tidallyLocked) {
            //  volatiles > 1.0 in some profiles; clamp to 1 for the shift.
            val volShift = (volatiles.coerceIn(0f, 1f) - 0.5f) * 0.30f  // ±15%
            val tempBase = when (tempClass) {
                TemperatureClass.FRIGID    -> 0.75f  // near-total iceball
                TemperatureClass.COLD      -> 0.62f
                TemperatureClass.COOL      -> 0.55f  // classic eyeball baseline
                TemperatureClass.TEMPERATE -> 0.50f  // ice line at terminator
                TemperatureClass.WARM      -> 0.40f  // partial terminator ring
                else -> 0.30f
            }
            val jitter = rng.nextFloat(-0.07f, 0.07f)
            return (tempBase + volShift + jitter).coerceIn(0f, 0.90f)
        }

        return when (tempClass) {
            TemperatureClass.FRIGID -> rng.nextFloat(0.3f, 0.8f) * volatiles * 2f
            TemperatureClass.COLD -> rng.nextFloat(0.15f, 0.5f) * volatiles * 2f
            TemperatureClass.COOL -> rng.nextFloat(0.05f, 0.25f) * volatiles * 2f
            TemperatureClass.TEMPERATE -> rng.nextFloat(0.02f, 0.15f) * volatiles * 2f
            else -> 0f
        }.coerceIn(0f, 0.8f)
    }

    /**
     * Terrain roughness [0,1].
     */
    private fun computeRoughness(
        bulkClass: BulkClass?,
        volcanicActivity: Float,
        seaLevel: Float,
        isGiant: Boolean,
        rng: DeterministicRandom,
    ): Float {
        if (isGiant) return 0f

        var roughness = rng.nextFloat(0.2f, 0.6f)

        // Volcanism creates terrain
        roughness += volcanicActivity * 0.2f

        // Liquids smooth terrain
        roughness -= seaLevel * 0.15f

        // Ferria: cratered, rough
        if (bulkClass == BulkClass.FERRIA) roughness += 0.1f

        // Aquaria: smoother (ice/liquid surfaces)
        if (bulkClass == BulkClass.AQUARIA) roughness -= 0.1f

        return roughness.coerceIn(0.05f, 0.95f)
    }

    /**
     * Geometric albedo [0,1].
     */
    private fun computeAlbedo(
        atmosphere: AtmosphericComposition,
        surface: SurfaceComposition?,
        gasGiant: GasGiantProfile?,
        classification: PlanetClassification,
        rng: DeterministicRandom,
    ): Float {
        // Gas giants: cloud-dependent
        if (gasGiant != null) {
            return when (gasGiant.type) {
                GasGiantType.AMMONIA -> rng.nextFloat(0.3f, 0.5f)  // bright clouds
                GasGiantType.WATER -> rng.nextFloat(0.25f, 0.45f)
                GasGiantType.CLEAR -> rng.nextFloat(0.05f, 0.15f)  // dark, cloudless
                GasGiantType.ALKALI -> rng.nextFloat(0.05f, 0.15f) // dark absorbers
                GasGiantType.SILICATE -> rng.nextFloat(0.03f, 0.10f)
                GasGiantType.ICE_GIANT -> rng.nextFloat(0.2f, 0.35f)
                GasGiantType.SUB_NEPTUNE -> rng.nextFloat(0.15f, 0.35f)
                GasGiantType.THOLIN -> rng.nextFloat(0.10f, 0.25f)  // hazy, moderate albedo
                GasGiantType.HELIUM_NEPTUNE -> rng.nextFloat(0.30f, 0.50f)  // pearl/white cloud decks
                GasGiantType.SILICATE_NEPTUNE -> rng.nextFloat(0.05f, 0.15f) // dark silicate haze
            }
        }

        // Rocky worlds
        var albedo = rng.nextFloat(0.08f, 0.20f) // baseline rocky

        // Atmosphere adds reflectivity (clouds)
        if (atmosphere.present) {
            albedo += atmosphere.surfacePressureBar.coerceAtMost(5f) / 5f * 0.3f
        }

        // Ice/snow: high albedo
        if (surface != null) {
            val tempClass = classification.temperatureClass
            val isCold = tempClass == TemperatureClass.FRIGID ||
                tempClass == TemperatureClass.COLD
            if (isCold && surface.water > 0.1f) {
                albedo += surface.water * 0.4f
            }
        }

        // Dark carbon worlds: low albedo
        if (surface != null && surface.carbon > 0.2f) {
            albedo -= 0.1f
        }

        return albedo.coerceIn(0.02f, 0.9f)
    }

    /**
     * Greenhouse-adjusted surface temperature.
     *
     * Uses a gray atmosphere model with band-saturating absorption:
     *   T_surface⁴ = T_eq⁴ × (1 + 0.75τ)
     *
     * where τ (IR optical depth) depends on greenhouse gas column density.
     * Two components:
     * 1. Band saturation: absorption lines saturate logarithmically with column density.
     *    Adding more CO2 gives diminishing returns per molecule.
     * 2. Pressure broadening: at high pressures, absorption line wings widen,
     *    opening new spectral regions — this is what drives Venus' extreme temperature.
     *
     * Calibrated against our model's compositions (not Earth-exact):
     * - Mars-like (30% CO2, 0.05 bar) → ~+7K
     * - Earth-like (5% CO2, 1 bar) → ~+34K
     * - Venus-like (90% CO2, 80 bar) → ~+200K
     */
    private fun computeSurfaceTemperature(
        eqTempK: Double,
        atm: AtmosphericComposition,
    ): Double {
        if (!atm.present || atm.surfacePressureBar < 0.001f) return eqTempK

        val pressure = atm.surfacePressureBar.toDouble()

        // Greenhouse gas fraction weighted by IR opacity per molecule:
        // CO2: strong absorber across 15μm band (reference = 1.0)
        // H2O: broader absorption than CO2, strong feedback
        // CH4: ~25× more potent per molecule than CO2
        // SO2: moderate absorber
        // NH3: moderate absorber
        val ghFraction = (atm.co2 * 1.0f + atm.h2o * 1.5f + atm.ch4 * 25f +
            atm.so2 * 0.3f + atm.nh3 * 0.5f).toDouble()

        val column = ghFraction * pressure

        // Band saturation: logarithmic with column density
        val bandSaturation = ln(1.0 + 15.0 * column)

        // Pressure broadening: line wings widen ∝ P, opening new absorption
        val pressureBroadening = 0.01 * column * sqrt(pressure)

        val tau = bandSaturation + pressureBroadening

        // Gray atmosphere: T_surface⁴ = T_eq⁴ × (1 + 0.75τ)
        return eqTempK * (1.0 + 0.75 * tau).pow(0.25)
    }

    /**
     * Axial tilt in degrees [0, 180].
     * Tidally locked → near 0. Otherwise random, biased low.
     */
    private fun computeAxialTilt(
        tidallyLocked: Boolean,
        rng: DeterministicRandom,
    ): Float {
        if (tidallyLocked) {
            return rng.nextFloat(0f, 5f) // near-zero
        }
        // Most planets have moderate tilt, occasional high obliquity
        val base = abs(rng.nextGaussian(25f, 20f))
        return base.coerceIn(0f, 170f)
    }
}
