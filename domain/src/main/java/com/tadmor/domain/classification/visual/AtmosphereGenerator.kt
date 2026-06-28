package com.tadmor.domain.classification.visual

import com.tadmor.domain.classification.CompositionClass
import com.tadmor.domain.classification.PlanetClassification
import com.tadmor.domain.classification.TemperatureClass
import com.tadmor.domain.model.Planet
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Generates [AtmosphericComposition] from physical/chemical rules.
 * Two entry points: [generateRocky] and [generateGiant].
 *
 * Also computes tholin production and deposits them onto
 * the surface composition as a post-processing step.
 */
object AtmosphereGenerator {

    private const val SOLAR_TEFF = 5778.0

    /**
     * Generate atmosphere for a rocky/terrestrial world.
     * Returns the atmosphere and optionally an updated surface composition
     * with tholin deposits applied.
     *
     * @param tidallyLocked whether the planet is tidally locked (kills magnetic dynamo)
     * @param rotationPeriodHours planet rotation period (feeds magnetic field estimate)
     * @param volcanicActivity [0,1] volcanic activity level (feeds CO2 outgassing)
     */
    fun generateRocky(
        planet: Planet,
        classification: PlanetClassification,
        bulkClass: BulkClass,
        surface: SurfaceComposition,
        context: SystemContext,
        rng: DeterministicRandom,
        tidallyLocked: Boolean = false,
        rotationPeriodHours: Float = 24f,
        volcanicActivity: Float = 0.15f,
    ): Pair<AtmosphericComposition, SurfaceComposition> {
        val massE = planet.massEarth ?: classification.estimatedSemiMajorAxisAU?.let { 1.0 }
        val radiusE = planet.radiusEarth
        val tempK = classification.temperatureClass?.let {
            (it.minK + it.maxK) / 2.0
        } ?: planet.eqTempK ?: classification.estimatedEqTempK
        val smaAU = planet.semiMajorAxisAU
            ?: classification.estimatedSemiMajorAxisAU

        // Atmospheric retention: XUV escape + magnetic shielding + gravity
        val retention = computeRetention(
            massE, radiusE, smaAU, tempK,
            tidallyLocked, rotationPeriodHours,
            surface.iron, context,
        )

        // ── Retention zones ──
        // retention < 0.01: airless (Mercury, Moon, Io — no meaningful atmosphere)
        // retention 0.01–0.10: exosphere (10⁻¹⁰ to 10⁻⁴ bar — trace gas only)
        // retention 0.10+: real atmosphere (Mars through Venus)

        // Airless: fully stripped, or mass too small
        if (retention < 0.01f) {
            return AtmosphericComposition.NONE to surface
        }

        // Exosphere: ultra-thin, trace molecules from sputtering/outgassing/sublimation
        // Not a retained atmosphere — scattered particles, like Mercury or Ganymede
        if (retention < 0.10f) {
            val exospherePressure = generateExospherePressure(retention, rng)
            val exoAtm = generateExosphere(tempK, surface, context, exospherePressure, rng)
            val updatedSurface = applyTholins(exoAtm, surface, context, rng)
            return exoAtm to updatedSurface
        }

        // Full atmosphere generation
        val tempClass = classification.temperatureClass
        val basePressure = estimateSurfacePressure(massE, tempClass, rng)
        // Retention scales surface pressure
        var surfacePressure = basePressure * retention

        // Build molecular fractions
        var h2 = 0f; var he = 0f; var n2 = 0f; var o2 = 0f
        var co2 = 0f; var h2o = 0f; var ch4 = 0f; var nh3 = 0f
        var so2 = 0f; var h2s = 0f

        // H2/He: only retained by very massive, cool rocky planets with strong retention.
        // At < 8 M⊕, XUV photoevaporation strips primordial H2/He over Gyr timescales.
        // Significant H2/He envelopes are what define sub-Neptunes — truly rocky worlds
        // have at most trace amounts from volcanic outgassing.
        if (massE != null && massE > 8.0 && (tempK == null || tempK < 400) && retention > 0.7f) {
            h2 = rng.nextFloat(0.005f, 0.03f) * retention
            he = h2 * rng.nextFloat(0.15f, 0.30f)
        }

        // CO2: volcanic outgassing — the primary source of CO2 on rocky worlds.
        // Temperature class sets the baseline (carbonate-silicate weathering cycle),
        // volcanism amplifies it (more eruptions = more CO2 injected).
        val co2Base = when (tempClass) {
            TemperatureClass.HOT, TemperatureClass.TORRID -> rng.nextFloat(0.50f, 0.90f)
            TemperatureClass.WARM -> rng.nextFloat(0.15f, 0.45f)
            TemperatureClass.TEMPERATE -> rng.nextFloat(0.01f, 0.10f)
            TemperatureClass.COOL -> rng.nextFloat(0.20f, 0.50f)
            TemperatureClass.COLD, TemperatureClass.FRIGID -> rng.nextFloat(0.05f, 0.25f)
            null -> rng.nextFloat(0.10f, 0.30f)
        }
        // Volcanism boost: activity 0→1 maps to 1×→2.5× CO2
        val volcanicCO2Boost = 1f + volcanicActivity * 1.5f
        co2 = (co2Base * volcanicCO2Boost).coerceAtMost(0.97f)

        // N2: common for > 0.5 M⊕, stable across wide temperature range
        if (massE == null || massE > 0.3) {
            n2 = when (tempClass) {
                TemperatureClass.TEMPERATE, TemperatureClass.COOL ->
                    rng.nextFloat(0.40f, 0.80f)
                TemperatureClass.WARM -> rng.nextFloat(0.10f, 0.40f)
                TemperatureClass.COLD -> rng.nextFloat(0.20f, 0.60f)
                TemperatureClass.FRIGID -> rng.nextFloat(0.10f, 0.50f)
                else -> rng.nextFloat(0.02f, 0.15f)
            }
        }

        // H2O vapor: depends on temperature and water in surface composition.
        // Even cold worlds have trace H2O from ice sublimation (Mars: ~0.03%).
        // Warmer worlds: more evaporation, especially with surface water.
        if (surface.water > 0.02f) {
            h2o = when (tempClass) {
                TemperatureClass.TORRID, TemperatureClass.HOT ->
                    rng.nextFloat(0.10f, 0.60f) * surface.water * 3f
                TemperatureClass.WARM -> rng.nextFloat(0.02f, 0.15f) * surface.water * 2f
                TemperatureClass.TEMPERATE -> rng.nextFloat(0.005f, 0.04f) * surface.water * 3f
                TemperatureClass.COOL -> rng.nextFloat(0.002f, 0.015f) * surface.water * 2f
                TemperatureClass.COLD -> rng.nextFloat(0.0005f, 0.005f) * surface.water
                TemperatureClass.FRIGID -> rng.nextFloat(0.0001f, 0.001f) * surface.water
                null -> rng.nextFloat(0.001f, 0.01f) * surface.water
            }
            h2o = h2o.coerceAtMost(0.80f)
        }

        // CH4: stable below ~400K in reducing atmospheres
        if (tempK != null && tempK < 400) {
            ch4 = when {
                tempClass == TemperatureClass.FRIGID -> rng.nextFloat(0.01f, 0.08f)
                tempClass == TemperatureClass.COLD -> rng.nextFloat(0.005f, 0.04f)
                tempClass == TemperatureClass.COOL -> rng.nextFloat(0.001f, 0.01f)
                else -> 0f
            }
            // Anti-correlated with CO2 (reducing vs oxidizing)
            if (co2 > 0.3f) ch4 *= 0.2f
        }

        // SO2: volcanism + sulfur in surface — scales with volcanic activity
        if (surface.sulfur > 0.01f && volcanicActivity > 0.1f) {
            val so2Chance = 0.2f + volcanicActivity * 0.6f // 0.2 at low, 0.8 at high volcanism
            if (rng.chance(so2Chance)) {
                so2 = rng.nextFloat(0.001f, 0.05f) *
                    (surface.sulfur * 5f).coerceAtMost(1f) *
                    (0.5f + volcanicActivity)
            }
        }

        // NH3: stable below ~200K
        if (tempK != null && tempK < 200) {
            nh3 = rng.nextFloat(0.0f, 0.02f)
        }

        // H2S: trace, volcanic — scales with activity
        if (surface.sulfur > 0.02f && volcanicActivity > 0.05f && rng.chance(0.15f + volcanicActivity * 0.3f)) {
            h2s = rng.nextFloat(0.0f, 0.005f) * (0.5f + volcanicActivity)
        }

        // O2: abiotic only — photolysis of H2O. Trace levels.
        if (h2o > 0.01f && context.starTeffK != null && context.starTeffK > 4000) {
            o2 = rng.nextFloat(0.0f, 0.02f)
        }

        // Low retention preferentially strips light molecules (H2, He, H2O)
        if (retention < 0.5f) {
            val lightStrip = (1f - retention * 2f) // 1.0 at retention=0, 0.0 at retention=0.5
            h2 *= (1f - lightStrip)
            he *= (1f - lightStrip)
            h2o *= (1f - lightStrip * 0.5f) // H2O somewhat lighter, partially stripped
        }

        // ── Greenhouse runaway amplification ──
        // CO2 + H2O vapor trap outgoing IR → surface temperature rises → more H2O
        // evaporates → stronger greenhouse. We can't model the iterative feedback
        // loop, but we detect conditions that would trigger it and apply the result.
        //
        // Venus: ~96% CO2, 92 bar. Started with modest atmosphere, runaway greenhouse
        // boiled off oceans and baked carbonates out of rock → massive CO2 buildup.
        //
        // HotRockyOutcome selector: for Earth-mass HOT-band worlds, we roll
        // between full runaway (Venus), failed runaway (thin CO2), and
        // stripped residual (Venus that lost its atmosphere). Previously all
        // such worlds got Venus amplification, producing unrealistic uniformity.
        val outcome = rollHotRockyOutcome(massE, tempClass, retention, surface.water, context, rng)
        when (outcome) {
            HotRockyOutcome.VENUS_THICK -> {
                surfacePressure = applyGreenhouseAmplification(
                    surfacePressure, co2, h2o, tempClass, surface.water, massE, retention, rng,
                )
            }
            HotRockyOutcome.THIN_CO2 -> {
                // Failed runaway: carbonate-silicate cycle died before H2O
                // photodissociated enough to sustain runaway. Atmosphere
                // remains at outgassed pressures (0.1–3 bar range), no decks.
                surfacePressure = (surfacePressure * rng.nextFloat(0.3f, 2.0f)).coerceIn(0.08f, 4f)
                // Ditch the sulfuric acid precursor — dry thin CO2 worlds
                // don't form H2SO4 clouds without water photolysis.
                so2 *= 0.3f
                h2s *= 0.3f
            }
            HotRockyOutcome.STRIPPED_RESIDUAL -> {
                // Post-Venus: atmosphere went runaway, then got sputtered away
                // over Gyr. Trace residual at Mars-like pressures.
                surfacePressure = rng.nextFloat(0.01f, 0.15f)
                // Strip hydrogen-bearing species further (they escape first).
                h2o *= 0.1f; ch4 *= 0.1f; nh3 *= 0.1f
            }
            HotRockyOutcome.DEFAULT -> {
                // Not in the hot-rocky branch — apply normal greenhouse.
                surfacePressure = applyGreenhouseAmplification(
                    surfacePressure, co2, h2o, tempClass, surface.water, massE, retention, rng,
                )
            }
        }

        // Normalize fractions to sum to 1
        val atm = normalizeAtmosphere(
            h2, he, n2, o2, co2, h2o, ch4, nh3, so2, h2s, surfacePressure,
        )

        // Tholin production and deposition
        val updatedSurface = applyTholins(atm, surface, context, rng)

        return atm to updatedSurface
    }

    /**
     * Generate atmosphere for a gas giant / ice giant / sub-Neptune.
     */
    fun generateGiant(
        classification: PlanetClassification,
        context: SystemContext,
        rng: DeterministicRandom,
        gasGiantType: GasGiantType? = null,
    ): AtmosphericComposition {
        val tempClass = classification.temperatureClass
        val tempK = tempClass?.let { (it.minK + it.maxK) / 2.0 }

        // ── Hot-Neptune H-stripped variants ──
        // Helium and silicate Neptunes have lost most of their hydrogen
        // envelope to atmospheric escape. Atmosphere is He-dominated with
        // CO₂ photochemistry products and essentially no methane (which
        // would otherwise drive the blue colour). Modelled with He >> H₂
        // and a substantial CO₂ fraction so downstream Mie/Rayleigh
        // generators see the right chemistry.
        if (gasGiantType == GasGiantType.HELIUM_NEPTUNE ||
            gasGiantType == GasGiantType.SILICATE_NEPTUNE) {
            val he = rng.nextFloat(0.65f, 0.80f)
            val h2 = rng.nextFloat(0.05f, 0.15f)
            val co2 = rng.nextFloat(0.08f, 0.18f)
            val h2o = if (gasGiantType == GasGiantType.SILICATE_NEPTUNE) {
                // Ultra-hot worlds keep some vapour aloft.
                rng.nextFloat(0.005f, 0.02f)
            } else {
                rng.nextFloat(0.001f, 0.005f)
            }
            // SO₂ traces from photochemistry — mostly silicate-class, but
            // also occasionally on hot helium worlds.
            val so2 = if (gasGiantType == GasGiantType.SILICATE_NEPTUNE) {
                rng.nextFloat(0.001f, 0.004f)
            } else 0f
            val surfacePressure = 1000f
            return normalizeAtmosphere(
                h2 = h2, he = he, n2 = 0f, o2 = 0f, co2 = co2,
                h2o = h2o, ch4 = 0f, nh3 = 0f, so2 = so2, h2s = 0f,
                surfacePressure = surfacePressure,
            )
        }

        // Base H2/He dominant
        var h2 = rng.nextFloat(0.82f, 0.90f)
        var he = rng.nextFloat(0.08f, 0.14f)

        // Methane: temperature-dependent
        var ch4 = when {
            tempK != null && tempK < 150 -> rng.nextFloat(0.01f, 0.04f)
            tempK != null && tempK < 300 -> rng.nextFloat(0.002f, 0.015f)
            else -> rng.nextFloat(0.0f, 0.002f)
        }

        // Ammonia: cold atmospheres
        var nh3 = when {
            tempK != null && tempK < 200 -> rng.nextFloat(0.0005f, 0.003f)
            else -> 0f
        }

        // H2O in warmer giants
        var h2o = when {
            tempK != null && tempK > 200 -> rng.nextFloat(0.0005f, 0.005f)
            else -> 0f
        }

        // H2S: trace
        var h2s = if (rng.chance(0.3f)) rng.nextFloat(0.0f, 0.001f) else 0f

        // Ice giants: less H2/He, more CH4
        if (classification.compositionClass == CompositionClass.NEPTUNE) {
            h2 = rng.nextFloat(0.70f, 0.82f)
            he = rng.nextFloat(0.14f, 0.20f)
            ch4 = rng.nextFloat(0.02f, 0.06f)
        }

        val surfacePressure = 1000f // arbitrary high for giants
        return normalizeAtmosphere(
            h2, he, 0f, 0f, 0f, h2o, ch4, nh3, 0f, h2s, surfacePressure,
        )
    }

    /**
     * Compute atmospheric retention factor [0, 1].
     *
     * Combines:
     * - Escape velocity (gravity holding atmosphere)
     * - XUV flux from host star (stripping atmosphere)
     * - Magnetic field shielding (protecting atmosphere)
     * - Cumulative exposure (age × flux)
     *
     * Returns 0 for fully stripped / too small, 1 for full retention.
     */
    private fun computeRetention(
        massE: Double?,
        radiusE: Double?,
        smaAU: Double?,
        tempK: Double?,
        tidallyLocked: Boolean,
        rotationPeriodHours: Float,
        ironFraction: Float,
        context: SystemContext,
    ): Float {
        // Absolute minimum mass threshold — too small to hold anything
        if (massE != null && massE < 0.08) return 0f

        // Escape velocity factor: v_esc = sqrt(2GM/R)
        // Normalized to Earth: v_esc_E = 11.2 km/s
        val mE = massE ?: 1.0
        val rE = radiusE ?: (mE.pow(0.27)) // mass-radius relation fallback
        val vEscRatio = if (rE > 0) sqrt(mE / rE) else 1.0 // relative to Earth

        // XUV flux estimate: driven by star Teff, proximity, and stellar activity
        val starTeff = context.starTeffK ?: 5778.0
        val dist = smaAU ?: 1.0

        // XUV luminosity relative to solar XUV luminosity.
        //
        // Key insight: M dwarfs emit a MUCH higher fraction of their luminosity
        // as XUV. L_XUV/L_bol ≈ 10⁻³ for M dwarfs vs 10⁻⁶ for the Sun.
        // So an M dwarf with L_bol = 0.001 L☉ still has L_XUV ≈ 1 L_XUV☉.
        //
        // We compute L_XUV / L_XUV☉ directly, then apply inverse-square law.
        val luminosity = context.starLuminosity ?: 1.0
        val xuvLuminosity = if (starTeff < 4000) {
            // M dwarfs: L_XUV/L_bol ≈ 10⁻³, Sun: L_XUV/L_bol ≈ 10⁻⁶
            // So XUV enhancement = (10⁻³/10⁻⁶) = 1000× per unit bolometric
            // Scales further with Teff (cooler M dwarfs are even more XUV-active)
            val mDwarfEnhancement = 1000.0 * (4000.0 / starTeff.coerceAtLeast(2500.0)).pow(2)
            luminosity * mDwarfEnhancement
        } else {
            // F/G/K stars: XUV fraction relatively constant, scales with bolometric
            luminosity * (starTeff / SOLAR_TEFF).pow(2)
        }

        // Stellar activity from rotation period — faster rotation = more active = more XUV
        // Young fast rotators (P < 10 days) can have 100× solar XUV
        val rotPDays = context.starRotationPeriodDays
        val activityMultiplier = if (rotPDays != null && rotPDays > 0) {
            // Empirical: L_XUV ∝ P_rot^(-2) for P > ~2 days
            (25.0 / rotPDays.coerceAtLeast(2.0)).pow(2).coerceIn(0.3, 30.0)
        } else {
            // Unknown rotation — assume moderate activity, age-dependent fallback
            val age = context.starAge ?: 4.6
            if (age < 1.0) 5.0
            else if (age < 2.0) 2.0
            else 1.0
        }

        // Received XUV flux at planet's orbit, relative to Earth:
        // F_XUV = L_XUV × activity / d²
        val xuvFlux = (xuvLuminosity * activityMultiplier / (dist * dist))
            .coerceIn(0.01, 10000.0)

        // Energy-limited escape saturation: at very high XUV flux, atmospheric
        // escape rate saturates — the upper atmosphere can only absorb and convert
        // energy to escape at a finite rate (Lopez & Fortney 2013, Owen & Wu 2017).
        // Power-law exponent 0.6 captures the sublinear scaling.
        val effectiveFlux = xuvFlux.pow(0.6)

        // Magnetic field estimate [0, 1]
        // Driven by: mass (core size), rotation (dynamo), iron fraction, tidal lock
        val magField = estimateMagneticField(mE, rotationPeriodHours, ironFraction, tidallyLocked)

        // Shielding: magnetic field deflects XUV-driven ion escape
        // Earth's field provides ~10× reduction in ion escape rate
        // magField=1.0 → 90% shielding, magField=0 → no shielding
        val shielding = 1.0f - magField * 0.9f  // fraction of XUV that gets through

        // Effective stripping rate: saturated XUV vs gravity
        val strippingRate = (effectiveFlux * shielding / (vEscRatio * vEscRatio * 8.0))
            .coerceIn(0.0, 100.0)

        // Cumulative exposure: older systems have lost more atmosphere
        val age = context.starAge ?: 4.6
        val cumulativeExposure = strippingRate * (age / 4.6)

        // Thermal escape: Jeans parameter — light molecules escape from hot, low-gravity worlds
        var thermalLoss = 0.0
        if (tempK != null) {
            // Thermal velocity of H relative to escape velocity
            // Higher temperature + lower gravity → more loss
            thermalLoss = (tempK / 1000.0) / (vEscRatio * vEscRatio)
            thermalLoss = thermalLoss.coerceIn(0.0, 5.0)
        }

        // Total retention: exponential decay from cumulative stripping + thermal loss
        // Exponent 0.3 gives meaningful spread across the 0.01–0.5 retention range
        val totalLoss = cumulativeExposure + thermalLoss * 0.3
        val retention = exp(-totalLoss * 0.3).toFloat()

        return retention.coerceIn(0f, 1f)
    }

    /**
     * Estimate planetary magnetic field strength [0, 1] relative to Earth.
     *
     * Dynamo requires: conducting fluid (iron core), convection, and rotation.
     * - Mass → core size (larger planets have bigger iron cores)
     * - Rotation → Coriolis force drives dynamo (faster = stronger)
     * - Iron fraction → core conducting fluid availability
     * - Tidal lock → kills dynamo (very slow rotation, reduced convection)
     */
    private fun estimateMagneticField(
        massE: Double,
        rotationPeriodHours: Float,
        ironFraction: Float,
        tidallyLocked: Boolean,
    ): Float {
        // Tidally locked planets: severely weakened or dead dynamo
        // (Mercury-like — technically has a weak field, but negligible for shielding)
        if (tidallyLocked) return 0.05f

        // Core size factor: scales with mass^0.7 (core mass fraction ~constant)
        val coreFactor = massE.pow(0.7).toFloat().coerceIn(0.1f, 5f)

        // Iron fraction boosts conducting fluid
        val ironFactor = (ironFraction * 3f + 0.3f).coerceIn(0.3f, 1.5f)

        // Rotation factor: faster rotation → stronger dynamo
        // Earth at 24hr = 1.0, scale as (24/P)^0.5
        val rotFactor = if (rotationPeriodHours > 0) {
            sqrt(24.0 / rotationPeriodHours.toDouble().coerceAtLeast(4.0)).toFloat()
        } else 0.5f

        val field = coreFactor * ironFactor * rotFactor * 0.3f
        return field.coerceIn(0f, 1f)
    }

    private fun estimateSurfacePressure(
        massE: Double?,
        tempClass: TemperatureClass?,
        rng: DeterministicRandom,
    ): Float {
        val basePressure = when (tempClass) {
            TemperatureClass.TORRID, TemperatureClass.HOT -> rng.nextFloat(10f, 100f)
            TemperatureClass.WARM -> rng.nextFloat(1f, 50f)
            TemperatureClass.TEMPERATE -> rng.nextFloat(0.3f, 3f)
            TemperatureClass.COOL -> rng.nextFloat(0.1f, 2f)
            TemperatureClass.COLD -> rng.nextFloat(0.01f, 0.5f)
            TemperatureClass.FRIGID -> rng.nextFloat(0.001f, 0.1f)
            null -> rng.nextFloat(0.1f, 2f)
        }
        // Scale with planet mass (heavier planets retain more atmosphere)
        val massFactor = if (massE != null) (massE / 1.0).coerceIn(0.3, 3.0).toFloat() else 1f
        return basePressure * massFactor
    }

    /**
     * Compute tholin production from N2 x CH4 x UV flux x star age,
     * and deposit onto the surface composition.
     */
    internal fun applyTholins(
        atmosphere: AtmosphericComposition,
        surface: SurfaceComposition,
        context: SystemContext,
        rng: DeterministicRandom,
    ): SurfaceComposition {
        if (!atmosphere.present) return surface

        val n2Frac = atmosphere.n2
        val ch4Frac = atmosphere.ch4
        if (n2Frac < 0.05f || ch4Frac < 0.005f) return surface

        // UV flux estimate: hotter stars produce more UV, closer planets receive more
        val uvFlux = estimateUvFlux(context)
        val ageFactor = ((context.starAge ?: 4.6) / 4.6).coerceIn(0.2, 3.0).toFloat()

        val tholinRate = n2Frac * ch4Frac * uvFlux * ageFactor * 8f
        if (tholinRate < 0.005f) return surface

        val tholinDeposit = (tholinRate * rng.nextFloat(0.6f, 1.4f)).coerceAtMost(0.15f)

        // Add tholins by reducing other components proportionally
        val scale = 1f - tholinDeposit
        return SurfaceComposition(
            silicates = surface.silicates * scale,
            iron = surface.iron * scale,
            water = surface.water * scale,
            sulfur = surface.sulfur * scale,
            carbon = surface.carbon * scale,
            nitrogen = surface.nitrogen * scale,
            methane = surface.methane * scale,
            ammonia = surface.ammonia * scale,
            tholins = surface.tholins + tholinDeposit,
        )
    }

    private fun estimateUvFlux(context: SystemContext): Float {
        val teff = context.starTeffK ?: return 1f
        // UV flux scales steeply with star temperature
        return ((teff / SOLAR_TEFF).let { it * it }).toFloat().coerceIn(0.1f, 10f)
    }

    /**
     * Map retention 0.01–0.10 to exosphere pressure.
     * Spans many orders of magnitude:
     * - retention ~0.01 → ~10⁻¹⁰ bar (Mercury-like, ~10⁻¹⁵ is true vacuum but
     *   we treat anything below 10⁻¹⁰ as NONE)
     * - retention ~0.05 → ~10⁻⁷ bar (Ganymede-like)
     * - retention ~0.10 → ~10⁻⁴ bar (approaching Mars-thin)
     */
    private fun generateExospherePressure(retention: Float, rng: DeterministicRandom): Float {
        // Map retention [0.01, 0.10] → log-pressure [-10, -4] (bar)
        val t = ((retention - 0.01f) / 0.09f).coerceIn(0f, 1f)
        val logPressure = -10f + t * 6f // -10 at retention=0.01, -4 at retention=0.10
        val variation = rng.nextFloat(-0.5f, 0.5f) // ±half order of magnitude
        return 10.0.pow((logPressure + variation).toDouble()).toFloat()
    }

    /**
     * Generate an exosphere-level atmosphere: trace molecules from solar wind
     * sputtering, outgassing, sublimation. Not a retained atmosphere.
     *
     * Composition depends on surface materials and temperature:
     * - Na, O from sputtered silicates (Mercury)
     * - O₂ from sputtered ice (Europa, Ganymede)
     * - SO₂ from volcanic sublimation (Io)
     * - CO₂ trace from outgassing
     *
     * We use our existing molecular species as proxies:
     * - o2 for sputtered oxygen
     * - co2 for trace outgassing
     * - so2 for volcanic worlds with sulfur
     * - h2o for sublimation near ice
     */
    private fun generateExosphere(
        tempK: Double?,
        surface: SurfaceComposition,
        context: SystemContext,
        pressure: Float,
        rng: DeterministicRandom,
    ): AtmosphericComposition {
        var o2 = 0f; var co2 = 0f; var so2 = 0f; var h2o = 0f; var n2 = 0f

        // Sputtered O from silicates/oxides (most common exosphere component)
        if (surface.silicates > 0.1f || surface.iron > 0.1f) {
            o2 = rng.nextFloat(0.1f, 0.5f)
        }

        // CO₂ trace outgassing
        co2 = rng.nextFloat(0.05f, 0.3f)

        // SO₂ from sulfur-bearing surfaces (Io-like)
        if (surface.sulfur > 0.05f) {
            so2 = rng.nextFloat(0.1f, 0.6f)
        }

        // H₂O sublimation from icy surfaces
        if (surface.water > 0.1f && tempK != null && tempK > 100) {
            h2o = rng.nextFloat(0.05f, 0.3f)
        }

        // N₂ from nitrogen ices sublimating (Triton-like)
        if (surface.nitrogen > 0.02f) {
            n2 = rng.nextFloat(0.1f, 0.4f)
        }

        return normalizeAtmosphere(
            h2 = 0f, he = 0f, n2 = n2, o2 = o2, co2 = co2,
            h2o = h2o, ch4 = 0f, nh3 = 0f, so2 = so2, h2s = 0f,
            surfacePressure = pressure,
        )
    }

    /**
     * Fate of an Earth-mass HOT-band rocky world's atmosphere. Controls
     * whether greenhouse amplification fires, fails, or ran away and was
     * subsequently stripped. Outside the target band (mass, temp), we fall
     * back to [DEFAULT] which applies the normal greenhouse path.
     */
    private enum class HotRockyOutcome { VENUS_THICK, THIN_CO2, STRIPPED_RESIDUAL, DEFAULT }

    /**
     * Decide the atmospheric fate of an Earth-mass HOT-band world.
     *
     * Selection is gated to the narrow band where Venus vs. non-Venus is a
     * real dice roll: 0.5 < M < 2.5 M⊕, HOT temperature class, non-trivial
     * retention. Outside that band returns DEFAULT and normal greenhouse
     * logic applies.
     *
     * Weights shift with host-star type (M dwarfs strip more aggressively),
     * stellar age, and surface water availability.
     */
    private fun rollHotRockyOutcome(
        massE: Double?,
        tempClass: TemperatureClass?,
        retention: Float,
        surfaceWater: Float,
        context: SystemContext,
        rng: DeterministicRandom,
    ): HotRockyOutcome {
        // Only Earth-mass HOT rocky worlds enter this branch. Super-Earths
        // (> 2.5 M⊕) reliably go Venus. Small bodies (< 0.5 M⊕) are already
        // handled by the airless / exosphere retention path.
        val m = massE ?: return HotRockyOutcome.DEFAULT
        if (m < 0.5 || m > 2.5) return HotRockyOutcome.DEFAULT
        if (tempClass != TemperatureClass.HOT) return HotRockyOutcome.DEFAULT
        if (retention < 0.15f) return HotRockyOutcome.DEFAULT  // already fragile

        // Base distribution for Earth-mass HOT world around a Sun-like star.
        var venus = 0.60f
        var thin = 0.25f
        var stripped = 0.15f

        // Host star shift: M dwarfs strip atmospheres harder via XUV, so
        // stripped/thin outcomes increase. G/K stars favour Venus retention.
        val teff = context.starTeffK ?: 5778.0
        if (teff < 4000.0) {
            venus -= 0.20f; thin += 0.05f; stripped += 0.15f
        } else if (teff < 5000.0) {
            venus -= 0.08f; thin += 0.04f; stripped += 0.04f
        }

        // Age: old stars had more time to strip runaway atmospheres.
        val age = context.starAge ?: 4.6
        if (age > 7.0) {
            venus -= 0.08f; stripped += 0.08f
        } else if (age < 2.0) {
            venus += 0.05f; stripped -= 0.05f  // hasn't had time to strip
        }

        // Water: Venus needs water to photodissociate. Dry worlds can't
        // sustain runaway; they fail into the thin-CO2 branch.
        if (surfaceWater < 0.05f) {
            venus -= 0.25f; thin += 0.25f
        }

        // Clamp to valid weights
        venus = venus.coerceIn(0f, 1f)
        thin = thin.coerceIn(0f, 1f)
        stripped = stripped.coerceIn(0f, 1f)
        val total = venus + thin + stripped
        if (total <= 0f) return HotRockyOutcome.VENUS_THICK

        val roll = rng.nextFloat(0f, total)
        return when {
            roll < venus -> HotRockyOutcome.VENUS_THICK
            roll < venus + thin -> HotRockyOutcome.THIN_CO2
            else -> HotRockyOutcome.STRIPPED_RESIDUAL
        }
    }

    /**
     * Greenhouse runaway amplification.
     *
     * Detects Venus-like conditions and amplifies surface pressure accordingly.
     * The CO2-H2O feedback loop: CO2 traps IR → temperature rises → more H2O
     * evaporates → stronger greenhouse → carbonates bake out of rock → more CO2.
     *
     * We identify the *conditions* for runaway and apply the *result*:
     * - High CO2 + warm/hot insolation → moderate amplification
     * - High CO2 + high H2O + strong insolation → strong amplification (Venus, 50–90 bar)
     * - Requires sufficient mass to retain the thick atmosphere
     */
    private fun applyGreenhouseAmplification(
        basePressure: Float,
        co2: Float,
        h2o: Float,
        tempClass: TemperatureClass?,
        surfaceWater: Float,
        massE: Double?,
        retention: Float,
        rng: DeterministicRandom,
    ): Float {
        // Need significant CO2 to start the cycle
        if (co2 < 0.15f) return basePressure

        // Need warm-to-hot conditions for the feedback to kick in.
        // Cold worlds sequester CO2 as dry ice, breaking the cycle.
        // TORRID worlds (>1500K) are molten rock — carbonate-silicate cycle
        // doesn't operate. Their atmospheres are mineral vapor (Na, K, SiO, Fe),
        // not CO2 greenhouse runaway. Different mechanism entirely.
        val insolationFactor = when (tempClass) {
            TemperatureClass.HOT -> 0.85f
            TemperatureClass.WARM -> 0.5f
            TemperatureClass.TEMPERATE -> 0.1f // marginal — only with very high CO2
            else -> return basePressure // too cold for runaway, or too hot (lava world)
        }

        // Need enough gravity to hold the amplified atmosphere
        val mE = massE ?: 1.0
        if (mE < 0.4) return basePressure // too small, atmosphere escapes before runaway

        // Greenhouse potential: CO2 fraction × insolation × H2O feedback
        // H2O is a powerful greenhouse gas — if surface water is available, evaporation
        // amplifies the effect (Venus had oceans early on; they boiled off)
        val h2oFeedback = 1f + (h2o + surfaceWater * 0.3f).coerceAtMost(0.5f) * 2f
        val greenhousePotential = co2 * insolationFactor * h2oFeedback * retention

        // Below threshold: no significant amplification
        if (greenhousePotential < 0.15f) return basePressure

        // Amplification curve: gentle at low potential, steep at high
        // greenhousePotential ~0.15 → 1.5× pressure (mild Venus)
        // greenhousePotential ~0.5  → 5–10× pressure (moderate)
        // greenhousePotential ~0.8+ → 20–50× pressure (full Venus, ~90 bar)
        val amplification = when {
            greenhousePotential > 0.7f -> {
                // Full runaway: Venus territory
                rng.nextFloat(15f, 50f)
            }
            greenhousePotential > 0.4f -> {
                // Strong greenhouse
                rng.nextFloat(4f, 15f)
            }
            greenhousePotential > 0.25f -> {
                // Moderate greenhouse
                rng.nextFloat(1.5f, 5f)
            }
            else -> {
                // Mild greenhouse
                rng.nextFloat(1.1f, 2f)
            }
        }

        // Mass factor: heavier planets sustain higher pressures
        val massBoost = mE.pow(0.5).toFloat().coerceIn(0.7f, 3f)
        val amplifiedPressure = basePressure * amplification * massBoost

        // Cap at Venus-like levels (~92 bar) — beyond that the model breaks down
        return amplifiedPressure.coerceAtMost(100f)
    }

    private fun normalizeAtmosphere(
        h2: Float, he: Float, n2: Float, o2: Float, co2: Float,
        h2o: Float, ch4: Float, nh3: Float, so2: Float, h2s: Float,
        surfacePressure: Float,
    ): AtmosphericComposition {
        val total = h2 + he + n2 + o2 + co2 + h2o + ch4 + nh3 + so2 + h2s
        if (total <= 0f) return AtmosphericComposition.NONE
        val s = 1f / total
        return AtmosphericComposition(
            present = true,
            surfacePressureBar = surfacePressure,
            h2 = h2 * s, he = he * s, n2 = n2 * s, o2 = o2 * s,
            co2 = co2 * s, h2o = h2o * s, ch4 = ch4 * s, nh3 = nh3 * s,
            so2 = so2 * s, h2s = h2s * s,
        )
    }
}
