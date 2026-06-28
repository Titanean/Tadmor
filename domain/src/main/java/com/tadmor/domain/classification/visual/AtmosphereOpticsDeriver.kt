package com.tadmor.domain.classification.visual

import com.tadmor.domain.classification.CompositionClass
import com.tadmor.domain.classification.PlanetClassification
import com.tadmor.domain.model.Planet
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Derives [AtmosphereOptics] from [AtmosphericComposition] + surface + planet data.
 * Output parameter model matches the atmosphere shader (atmosphere.html proof-of-concept):
 *
 * - Scale heights and altitudes in km (no normalization, no clamping)
 * - Density as a multiplier relative to Earth (1.0 = Earth surface density)
 * - Per-channel Rayleigh scattering coefficients following λ⁻⁴, scaled by composition
 * - Per-channel Mie scattering + absorption from surface dust / aerosol composition
 * - Absorption band for ozone (O₂-bearing) or CH₄ (ice giants / cold CH₄-rich worlds)
 * - Cloud altitude in km, fog scale height in km
 */
object AtmosphereOpticsDeriver {

    private const val BOLTZMANN_K = 1.38e-23   // J/K
    private const val AMU = 1.66e-27            // kg
    private const val G_EARTH = 9.81            // m/s²

    // ── Earth reference Rayleigh base coefficients ──
    // From atmosphere.html tuned presets. The λ⁻⁴ ratio is baked in (R:G:B ≈ 1:2.4:6).
    // These are universal baseline values — the density multiplier handles absolute
    // scattering strength. Dense atmospheres don't need higher base values; the
    // density already accounts for it. Matching the presets: Venus (density 53×)
    // uses base blue = 8, not 33 × 2.27. The base is kept moderate to prevent
    // over-scattering in the renderer.
    private const val BASE_RAY_R = 5.5f
    private const val BASE_RAY_G = 13.0f
    private const val BASE_RAY_B = 33.0f

    // Earth reference for density calculation
    private const val EARTH_MU_AMU = 29.0   // mean molecular weight
    private const val EARTH_TEMP_K = 288.0  // surface temperature

    fun derive(
        atm: AtmosphericComposition,
        surface: SurfaceComposition?,
        planet: Planet,
        classification: PlanetClassification,
        context: SystemContext,
        volcanicActivity: Float,
        isGiant: Boolean,
        surfaceTempK: Double,
        rng: DeterministicRandom,
        gasGiantProfile: GasGiantProfile? = null,
    ): AtmosphereOptics {
        if (!atm.present) return AtmosphereOptics.NONE

        // Gas giants: dedicated path — cloud deck is the "surface",
        // thin haze layer above with very different optical properties
        if (isGiant && gasGiantProfile != null) {
            return deriveGiantOptics(
                atm, planet, classification, context, gasGiantProfile, surfaceTempK, rng,
            )
        }

        val gravity = computeSurfaceGravity(planet, isGiant)
        val mu = atm.meanMolecularWeight.toDouble()
        val planetRadiusKm = (planet.radiusEarth ?: if (isGiant) 11.2 else 1.0) * 6371.0

        // ── Scale height: H = kT / (μg), in km ──
        val rayleighScaleHeightKm = if (mu > 0 && gravity > 0) {
            (BOLTZMANN_K * surfaceTempK) / (mu * AMU * gravity) / 1000.0
        } else 8.5 // Earth default

        // ── Density multiplier: surface density relative to Earth ──
        // ρ = P × μ / (R × T), so ratio = (P/P_earth)(μ/μ_earth)(T_earth/T)
        val densityMultiplier = atm.surfacePressureBar.toDouble() *
            (mu / EARTH_MU_AMU) *
            (EARTH_TEMP_K / surfaceTempK)

        // ── Rayleigh scattering coefficients (per-channel, λ⁻⁴ ratio preserved) ──
        // Base values scaled by molecular Rayleigh cross-section relative to Earth's
        // N₂/O₂ mix. Cross-section ∝ (n-1)² where n is refractive index:
        //   Earth (N₂/O₂): 1.0×, CO₂: 2.38×, H₂: 0.22×, CH₄: 2.22×, SO₂: ~2.5×
        // This means CO₂ atmospheres scatter MORE per molecule (bluer if optically thin),
        // while H₂-rich sub-Neptunes scatter less.
        val rayCrossSection = computeRayleighCrossSection(atm)
        var rayleighR = BASE_RAY_R * rayCrossSection
        var rayleighG = BASE_RAY_G * rayCrossSection
        var rayleighB = BASE_RAY_B * rayCrossSection

        // ── Particulate bias for thin atmospheres ──
        // Pure molecular Rayleigh (λ⁻⁴) always produces a blue sky regardless of
        // composition, but real Mars-like to sub-Earth-like atmospheres are
        // dominated by suspended surface dust rather than gas molecules — Mars's
        // butterscotch sky is iron-oxide dust scattering, not the thin CO₂ doing
        // molecular Rayleigh. We sway the Rayleigh coefficients toward the
        // surface-derived dust tint, with a strength that ramps from 0 at
        // Earth-density to 1 below ~0.05× Earth-density. Total Rayleigh
        // magnitude is preserved — only the spectral distribution shifts. This
        // intentionally violates the "molecular base values" rule for thin
        // atmospheres because it's the only way to get the right sky colour
        // when particulate scattering dominates molecular Rayleigh.
        if (surface != null) {
            // Cubic falloff so Mars-thin atmospheres (density ≈ 0.006) still
            // get near-full surface tint while sub-Earth densities (0.3–0.7)
            // get only a gentle nudge — the middle range was reading as
            // overly dust-tinted with a linear ramp.
            //   d=0.05 → 1.00   (Mars-thin, full bias)
            //   d=0.10 → 0.85
            //   d=0.30 → 0.40
            //   d=0.50 → 0.15
            //   d=0.70 → 0.03
            //   d=1.00 → 0      (Earth, pure molecular Rayleigh)
            val linearBias = ((1f - densityMultiplier.toFloat()) / 0.95f).coerceIn(0f, 1f)
            val particulateBias = linearBias * linearBias * linearBias
            if (particulateBias > 0f) {
                val tint = computeDustTint(surface)
                if (tint != null) {
                    val total = rayleighR + rayleighG + rayleighB
                    val particR = total * tint.r
                    val particG = total * tint.g
                    val particB = total * tint.b
                    rayleighR = lerp(rayleighR, particR, particulateBias)
                    rayleighG = lerp(rayleighG, particG, particulateBias)
                    rayleighB = lerp(rayleighB, particB, particulateBias)
                }
            }
        }

        // ── Mie scattering from aerosols / dust ──
        val mie = deriveMie(atm, surface, volcanicActivity, surfaceTempK, rng)

        // ── Absorption band (ozone for O₂-bearing, CH₄ bands for ice giants) ──
        val absorption = deriveAbsorptionBand(atm, isGiant, rayleighScaleHeightKm, rng)

        // ── Clouds ──
        val cloud = deriveClouds(atm, surface, surfaceTempK, isGiant,
            rayleighScaleHeightKm, rng)

        // ── Fog ──
        val fog = deriveFog(atm, surface, volcanicActivity, surfaceTempK,
            rayleighScaleHeightKm, rng)

        // ── Atmosphere thickness: visible extent ──
        val maxScaleHeight = max(rayleighScaleHeightKm, mie.scaleHeightKm.toDouble())
        val atmosphereThicknessKm = (maxScaleHeight * 15.0).toFloat()

        // ── Sun illumination ──
        val smaAU = planet.semiMajorAxisAU
            ?: classification.estimatedSemiMajorAxisAU
            ?: 1.0
        val starLum = context.starLuminosity ?: 1.0
        // Intensity relative to Earth: L / d² (Earth = 1.0 at 1 AU from Sun)
        val sunIntensity = (starLum / (smaAU * smaAU)) * 40.0  // 40 = Earth preset value

        val starTint = ColorPalettes.starTint(context.starTeffK)

        val base = AtmosphereOptics(
            atmosphereThicknessKm = atmosphereThicknessKm,
            rayleighScaleHeightKm = rayleighScaleHeightKm.toFloat(),
            rayleighR = rayleighR,
            rayleighG = rayleighG,
            rayleighB = rayleighB,
            densityMultiplier = densityMultiplier.toFloat(),
            mieScaleHeightKm = mie.scaleHeightKm,
            mieR = mie.r, mieG = mie.g, mieB = mie.b,
            miePhaseG = mie.phaseG,
            miePhaseG2 = mie.phaseG2,
            miePhaseBlend = mie.phaseBlend,
            mieDirtiness = mie.dirtiness,
            mieAbsorptionR = mie.absR, mieAbsorptionG = mie.absG, mieAbsorptionB = mie.absB,
            ozoneR = absorption.r, ozoneG = absorption.g, ozoneB = absorption.b,
            ozoneCenterKm = absorption.centerKm,
            ozoneWidthKm = absorption.widthKm,
            cloudType = cloud.type,
            cloudColor = cloud.color,
            cloudCoverage = cloud.coverage,
            cloudAltitudeKm = cloud.altitudeKm,
            cloudDensity = cloud.density,
            cloudSize = cloud.size,
            cloudDistortion = cloud.distortion,
            cloudBumpiness = cloud.bumpiness,
            cloudBanding = cloud.banding,
            fogColor = fog.color,
            fogDensity = fog.density,
            fogScaleHeightKm = fog.scaleHeightKm,
            fogPatchiness = fog.patchiness,
            starTintColor = starTint,
            sunIntensity = sunIntensity.toFloat(),
            sunDistanceAU = smaAU.toFloat(),
        )
        return applySpice(base, rng)
    }

    // ── Helper data classes for grouping derived results ──

    private data class MieResult(
        val scaleHeightKm: Float,
        val r: Float, val g: Float, val b: Float,
        val phaseG: Float, val phaseG2: Float, val phaseBlend: Float,
        val dirtiness: Float,
        val absR: Float, val absG: Float, val absB: Float,
    )

    private data class AbsorptionResult(
        val r: Float, val g: Float, val b: Float,
        val centerKm: Float, val widthKm: Float,
    )

    private data class CloudResult(
        val color: Long, val coverage: Float, val altitudeKm: Float,
        val density: Float, val size: Float, val distortion: Float,
        val bumpiness: Float, val banding: Float,
        val type: CloudType = CloudType.NONE,
    )

    private data class FogResult(
        val color: Long, val density: Float,
        val scaleHeightKm: Float, val patchiness: Float,
    )

    // ── Gas giant dedicated path ──

    /**
     * Gas giant atmosphere optics.
     *
     * The "surface" in the renderer is the cloud tops (ammonia, water, or methane
     * depending on temperature). Only a thin haze layer sits above. This is why
     * density is 0.03–0.15 (not 1.0+ like rocky worlds) and clouds are off.
     *
     * Reference presets: Jupiter density=0.05, Neptune density=0.10.
     */
    private fun deriveGiantOptics(
        atm: AtmosphericComposition,
        planet: Planet,
        classification: PlanetClassification,
        context: SystemContext,
        gasGiant: GasGiantProfile,
        surfaceTempK: Double,
        rng: DeterministicRandom,
    ): AtmosphereOptics {
        val isIceGiant = classification.compositionClass == CompositionClass.NEPTUNE
        val gravity = computeSurfaceGravity(planet, true)

        // H2-dominated atmosphere above clouds. Ice giants have slightly higher μ
        // from heavier species mixed into the observable atmosphere.
        val mu = if (isIceGiant) {
            2.3 + gasGiant.metallicityEnrichment.coerceAtMost(1f) * 0.3
        } else {
            2.2 + gasGiant.metallicityEnrichment.coerceAtMost(1f) * 0.1
        }

        // Scale height: kT/(μg)
        val scaleHeightKm = if (gravity > 0) {
            (BOLTZMANN_K * surfaceTempK) / (mu * AMU * gravity) / 1000.0
        } else 20.0

        // ── Temperature-driven atmosphere inflation ──
        // Hotter atmospheres are puffier: larger scale height exposes more gas above
        // the cloud deck, increasing optical depth. Baseline ~150K (Neptune-like).
        val tempScale = sqrt(surfaceTempK / 150.0).toFloat().coerceIn(0.5f, 3f)

        // ── Density: thin haze above cloud deck, scaled by temperature ──
        val baseDensity = when (gasGiant.type) {
            GasGiantType.ICE_GIANT -> rng.nextFloat(0.07f, 0.15f)
            GasGiantType.SUB_NEPTUNE -> rng.nextFloat(0.05f, 0.12f)
            GasGiantType.AMMONIA -> rng.nextFloat(0.03f, 0.08f)
            GasGiantType.WATER -> rng.nextFloat(0.04f, 0.10f)
            GasGiantType.CLEAR -> rng.nextFloat(0.02f, 0.05f)
            GasGiantType.ALKALI -> rng.nextFloat(0.01f, 0.04f)
            GasGiantType.SILICATE -> rng.nextFloat(0.02f, 0.06f)
            GasGiantType.THOLIN -> rng.nextFloat(0.10f, 0.20f)  // denser organic haze
            // H-stripped He+CO₂ atmosphere — moderate column density above
            // the cloud deck, comparable to a normal ice giant.
            GasGiantType.HELIUM_NEPTUNE -> rng.nextFloat(0.05f, 0.12f)
            // Thin ultra-hot atmosphere above a reflective silicate deck.
            GasGiantType.SILICATE_NEPTUNE -> rng.nextFloat(0.03f, 0.07f)
        }
        // ── Size correction: super-puffs (large R, low mass) spread their atmosphere ──
        // over a much larger volume → lower column density above the cloud deck.
        // Jupiter (11.2 R⊕) is the reference; larger planets get proportionally thinner.
        val radiusE = planet.radiusEarth ?: 11.2
        val sizeScale = (11.2 / radiusE.coerceAtLeast(1.0)).toFloat().coerceIn(0.3f, 2f)

        val density = (baseDensity * tempScale * sizeScale).coerceIn(0.01f, 0.5f)

        // ── Atmosphere thickness: scale height × 15, wide range for temperature variation ──
        // Cold giants (~100K): ~300 km. Hot giants (~1000K): ~2000+ km.
        // Super-puffs (very low gravity) can have scale heights of thousands of km;
        // a tight cap creates a hard atmosphere edge that manifests as a colored limb ring.
        // Cap at 20,000 km — the density multiplier is already very low for these planets.
        val atmosphereThicknessKm = (scaleHeightKm * 15.0).toFloat().coerceIn(100f, 20_000f)

        // ── Rayleigh: weak above cloud deck ──
        // Ice giants have more metals → slightly stronger scattering.
        // Hot clear giants are very tenuous above.
        // Rayleigh scale — ice giants need to stay close to Neptune preset (rayScale ≈ 0.10)
        // to avoid green-dominant atmospheres from an over-strong green Rayleigh channel.
        val rayScale = when (gasGiant.type) {
            GasGiantType.ICE_GIANT -> rng.nextFloat(0.06f, 0.14f)
            GasGiantType.SUB_NEPTUNE -> rng.nextFloat(0.08f, 0.18f)
            GasGiantType.AMMONIA, GasGiantType.WATER -> rng.nextFloat(0.03f, 0.08f)
            GasGiantType.CLEAR, GasGiantType.ALKALI -> rng.nextFloat(0.01f, 0.04f)
            GasGiantType.SILICATE -> rng.nextFloat(0.02f, 0.05f)
            GasGiantType.THOLIN -> rng.nextFloat(0.06f, 0.14f)  // similar to ice giant
            // Helium has an exceptionally low Rayleigh cross-section
            // ((n−1)² ≈ 0.12 vs N₂'s 8.88), so even a non-trivial helium
            // column scatters very weakly — the planet's apparent colour
            // comes from the cloud deck almost entirely.
            GasGiantType.HELIUM_NEPTUNE -> rng.nextFloat(0.015f, 0.05f)
            // Thin haze above silicate clouds — minor Rayleigh contribution.
            GasGiantType.SILICATE_NEPTUNE -> rng.nextFloat(0.02f, 0.05f)
        }

        // ── Mie: type-specific ──
        val mie = deriveGiantMieByType(gasGiant, atm, rng)

        // ── Absorption band: CH4 or alkali ──
        val absorption = deriveGiantAbsorption(gasGiant, atm, scaleHeightKm, rng)

        // ── Clouds: cloud deck IS the surface — no additional layer ──
        // Exception: some ice giants have thin high-altitude methane wisps
        val cloud = if (isIceGiant && rng.chance(0.4f)) {
            CloudResult(
                color = 0xFFD6E8FF.toLong(),
                coverage = 0f,
                altitudeKm = rng.nextFloat(50f, 150f),
                density = rng.nextFloat(0.05f, 0.2f),
                size = rng.nextFloat(1.7f, 4.3f),
                distortion = rng.nextFloat(1f, 2f),
                bumpiness = rng.nextFloat(0.02f, 0.08f),
                banding = rng.nextFloat(1f, 3f),
            )
        } else {
            CloudResult(0L, 0f, 0f, 0f, 1f, 0f, 0f, 0f)
        }

        // Sun
        val smaAU = planet.semiMajorAxisAU
            ?: classification.estimatedSemiMajorAxisAU
            ?: 5.0
        val starLum = context.starLuminosity ?: 1.0
        val sunIntensity = (starLum / (smaAU * smaAU)) * 40.0
        val starTint = ColorPalettes.starTint(context.starTeffK)

        val base = AtmosphereOptics(
            atmosphereThicknessKm = atmosphereThicknessKm,
            // Super-puffs can have true scale heights of thousands of km. Capping too
            // low makes density fall off much faster than reality → hard limb edge.
            rayleighScaleHeightKm = scaleHeightKm.toFloat().coerceIn(8f, 4000f),
            rayleighR = BASE_RAY_R * rayScale,
            rayleighG = BASE_RAY_G * rayScale,
            rayleighB = BASE_RAY_B * rayScale,
            densityMultiplier = density,
            mieScaleHeightKm = mie.scaleHeightKm,
            mieR = mie.r, mieG = mie.g, mieB = mie.b,
            miePhaseG = mie.phaseG,
            miePhaseG2 = mie.phaseG2,
            miePhaseBlend = mie.phaseBlend,
            mieDirtiness = mie.dirtiness,
            mieAbsorptionR = mie.absR, mieAbsorptionG = mie.absG, mieAbsorptionB = mie.absB,
            ozoneR = absorption.r, ozoneG = absorption.g, ozoneB = absorption.b,
            ozoneCenterKm = absorption.centerKm,
            ozoneWidthKm = absorption.widthKm,
            cloudType = cloud.type,
            cloudColor = cloud.color,
            cloudCoverage = cloud.coverage,
            cloudAltitudeKm = cloud.altitudeKm,
            cloudDensity = cloud.density,
            cloudSize = cloud.size,
            cloudDistortion = cloud.distortion,
            cloudBumpiness = cloud.bumpiness,
            cloudBanding = cloud.banding,
            fogColor = 0L,
            fogDensity = 0f,
            fogScaleHeightKm = 1f, // safe default even when fog density = 0
            fogPatchiness = 0f,
            starTintColor = starTint,
            sunIntensity = sunIntensity.toFloat(),
            sunDistanceAU = smaAU.toFloat(),
        )
        return applySpice(base, rng)
    }

    /**
     * Gas giant Mie scattering by type.
     *
     * AMMONIA (Jupiter-like): massive chromophore absorption — sulfur/phosphorus compounds
     * produced by UV photochemistry give the warm brown/tan/orange hues. The absorption
     * coefficients (50–95) are much higher than rocky worlds because the chromophores are
     * concentrated in the cloud deck haze layer.
     *
     * ICE_GIANT (Neptune-like): clean atmosphere, minimal Mie. The blue color comes from
     * CH4 absorption band removing red light, not from Mie scattering.
     */
    private fun deriveGiantMieByType(
        gasGiant: GasGiantProfile,
        atm: AtmosphericComposition,
        rng: DeterministicRandom,
    ): MieResult = when (gasGiant.type) {
        GasGiantType.AMMONIA -> {
            // Jupiter/Saturn-like: chromophore absorption from sulfur/phosphorus UV
            // photochemistry products. Chromophores absorb BLUE/UV (that's why Jupiter
            // looks orange/brown), so blue absorption > red absorption.
            // Jupiter preset reference: absR=73, absG=56, absB=63 at density=0.05.
            val scatter = rng.nextFloat(3f, 7f)
            val absBase = rng.nextFloat(50f, 80f)
            MieResult(
                scaleHeightKm = rng.nextFloat(3f, 7f),
                r = scatter * rng.nextFloat(0.9f, 1.1f),
                g = scatter * rng.nextFloat(0.9f, 1.1f),
                b = scatter * rng.nextFloat(1.0f, 1.5f),
                phaseG = rng.nextFloat(0.75f, 0.90f),
                phaseG2 = rng.nextFloat(-0.30f, -0.15f),
                phaseBlend = rng.nextFloat(0.15f, 0.35f),
                dirtiness = rng.nextFloat(0.5f, 0.9f),
                // Blue/green absorbed more than red → orange/brown appearance
                absR = absBase * rng.nextFloat(0.70f, 0.90f),
                absG = absBase * rng.nextFloat(0.75f, 0.95f),
                absB = absBase * rng.nextFloat(0.85f, 1.05f),
            )
        }
        GasGiantType.WATER -> {
            // Warmer gas giant: water cloud haze is relatively clean.
            // Water droplets scatter efficiently with minimal absorption (white clouds).
            val scatter = rng.nextFloat(2f, 5f)
            MieResult(
                scaleHeightKm = rng.nextFloat(4f, 8f),
                r = scatter, g = scatter, b = scatter * rng.nextFloat(1.0f, 1.3f),
                phaseG = rng.nextFloat(0.70f, 0.85f),
                phaseG2 = rng.nextFloat(-0.25f, -0.10f),
                phaseBlend = rng.nextFloat(0.10f, 0.25f),
                dirtiness = rng.nextFloat(0.3f, 0.7f),
                absR = rng.nextFloat(1f, 6f),
                absG = rng.nextFloat(0.5f, 4f),
                absB = rng.nextFloat(0.3f, 3f),
            )
        }
        GasGiantType.CLEAR -> {
            // Hot Jupiter: cleared of clouds, very little haze.
            // Rayleigh-dominated — the clear atmosphere scatters but absorbs little.
            MieResult(
                scaleHeightKm = rng.nextFloat(5f, 12f),
                r = rng.nextFloat(0.5f, 2f),
                g = rng.nextFloat(0.5f, 2f),
                b = rng.nextFloat(0.5f, 2f),
                phaseG = rng.nextFloat(0.60f, 0.80f),
                phaseG2 = rng.nextFloat(-0.20f, -0.05f),
                phaseBlend = rng.nextFloat(0.05f, 0.15f),
                dirtiness = rng.nextFloat(0.1f, 0.3f),
                absR = rng.nextFloat(1f, 8f),
                absG = rng.nextFloat(1f, 8f),
                absB = rng.nextFloat(1f, 8f),
            )
        }
        GasGiantType.ALKALI -> {
            // Very hot: Na/K absorption, dark atmosphere
            MieResult(
                scaleHeightKm = rng.nextFloat(8f, 15f),
                r = rng.nextFloat(1f, 3f),
                g = rng.nextFloat(1f, 3f),
                b = rng.nextFloat(1f, 3f),
                phaseG = rng.nextFloat(0.65f, 0.80f),
                phaseG2 = rng.nextFloat(-0.20f, -0.05f),
                phaseBlend = rng.nextFloat(0.05f, 0.15f),
                dirtiness = rng.nextFloat(0.2f, 0.5f),
                absR = rng.nextFloat(40f, 80f),
                absG = rng.nextFloat(50f, 90f),
                absB = rng.nextFloat(30f, 70f),
            )
        }
        GasGiantType.SILICATE -> {
            // Extremely hot: silicate cloud haze
            MieResult(
                scaleHeightKm = rng.nextFloat(5f, 10f),
                r = rng.nextFloat(3f, 8f),
                g = rng.nextFloat(2f, 6f),
                b = rng.nextFloat(1f, 4f),
                phaseG = rng.nextFloat(0.75f, 0.90f),
                phaseG2 = rng.nextFloat(-0.25f, -0.10f),
                phaseBlend = rng.nextFloat(0.10f, 0.25f),
                dirtiness = rng.nextFloat(0.4f, 0.8f),
                absR = rng.nextFloat(20f, 50f),
                absG = rng.nextFloat(25f, 55f),
                absB = rng.nextFloat(30f, 60f),
            )
        }
        GasGiantType.ICE_GIANT -> {
            // Neptune/Uranus: blue Mie from haze. Neptune preset: mie = (0, 0.6, 7.3).
            // Green channel kept low — even small Mie green values contribute visibly
            // because Rayleigh green is already present.
            MieResult(
                scaleHeightKm = rng.nextFloat(4f, 8f),
                r = rng.nextFloat(0f, 0.2f),
                g = rng.nextFloat(0.1f, 0.6f),
                b = rng.nextFloat(4f, 8f),
                phaseG = rng.nextFloat(0.45f, 0.65f),
                phaseG2 = rng.nextFloat(-0.30f, -0.15f),
                phaseBlend = rng.nextFloat(0.05f, 0.15f),
                dirtiness = rng.nextFloat(0.1f, 0.4f),
                absR = rng.nextFloat(0f, 1.5f),
                absG = rng.nextFloat(0f, 0.5f),
                absB = rng.nextFloat(0f, 0.3f),
            )
        }
        GasGiantType.THOLIN -> {
            // Cold ice giant with photochemical tholin haze.
            // Tholins absorb blue/green preferentially → purple/reddish tint over
            // the underlying blue H2/CH4 Rayleigh background.
            val scatter = rng.nextFloat(2f, 5f)
            MieResult(
                scaleHeightKm = rng.nextFloat(5f, 10f),
                r = scatter * rng.nextFloat(1.0f, 1.5f),
                g = scatter * rng.nextFloat(0.6f, 1.0f),
                b = scatter * rng.nextFloat(0.3f, 0.7f),
                phaseG = rng.nextFloat(0.50f, 0.70f),
                phaseG2 = rng.nextFloat(-0.30f, -0.15f),
                phaseBlend = rng.nextFloat(0.10f, 0.25f),
                dirtiness = rng.nextFloat(0.3f, 0.6f),
                // Tholin aerosols absorb green and blue more than red → orange-brown haze
                absR = rng.nextFloat(5f, 15f),
                absG = rng.nextFloat(15f, 35f),
                absB = rng.nextFloat(20f, 45f),
            )
        }
        GasGiantType.SUB_NEPTUNE -> {
            // Transitional: CH4-rich sub-Neptunes approach ice giant blue;
            // CH4-poor ones are more neutral with moderate haze.
            // Absorption scales inversely with CH4 — CH4-rich atmospheres are
            // clean like Neptune (abs ~0.5), CH4-poor have moderate haze absorption.
            val scatter = rng.nextFloat(1f, 4f)
            val ch4Factor = (atm.ch4 * 20f).coerceAtMost(1f)
            val absScale = 2f + (1f - ch4Factor) * 8f
            MieResult(
                scaleHeightKm = rng.nextFloat(3f, 7f),
                r = scatter * (1f - ch4Factor * 0.5f),
                g = scatter * (1f - ch4Factor * 0.2f),
                b = scatter * (1f + ch4Factor * 1.5f),
                phaseG = rng.nextFloat(0.55f, 0.75f),
                phaseG2 = rng.nextFloat(-0.25f, -0.10f),
                phaseBlend = rng.nextFloat(0.08f, 0.20f),
                dirtiness = rng.nextFloat(0.2f, 0.6f),
                absR = rng.nextFloat(absScale * 0.3f, absScale),
                absG = rng.nextFloat(absScale * 0.2f, absScale * 0.8f),
                absB = rng.nextFloat(absScale * 0.1f, absScale * 0.6f),
            )
        }
        GasGiantType.HELIUM_NEPTUNE -> {
            // Hot Neptune that's lost its hydrogen envelope. He+CO₂
            // atmosphere with very little methane → no CH₄-driven blue
            // absorption. The cloud deck appears as soft pearlescent
            // grey-white; Mie scatters all wavelengths roughly equally
            // (high-altitude photochemical haze, not coloured droplets)
            // with low absorption so the disc reads as a near-neutral
            // pearlescent rather than the methane-blue of a normal ice
            // giant. Slight warm bias models Na/K alkali photochemistry
            // products that would be present at this temperature.
            val scatter = rng.nextFloat(2f, 5f)
            MieResult(
                scaleHeightKm = rng.nextFloat(4f, 9f),
                r = scatter * rng.nextFloat(1.0f, 1.2f),
                g = scatter * rng.nextFloat(1.0f, 1.15f),
                b = scatter * rng.nextFloat(0.95f, 1.10f),
                phaseG = rng.nextFloat(0.65f, 0.80f),
                phaseG2 = rng.nextFloat(-0.25f, -0.10f),
                phaseBlend = rng.nextFloat(0.08f, 0.20f),
                dirtiness = rng.nextFloat(0.3f, 0.6f),
                absR = rng.nextFloat(2f, 8f),
                absG = rng.nextFloat(2f, 8f),
                absB = rng.nextFloat(3f, 10f),
            )
        }
        GasGiantType.SILICATE_NEPTUNE -> {
            // Ultra-hot Neptune (LTT 9779 b class). Reflective silicate
            // cloud deck recondenses on the cooler limb / nightside,
            // giving the planet a high albedo. Mie scatters strongly
            // with a slight red bias from alkali-flame chemistry above
            // the cloud deck. Lower absorption than the Jupiter-class
            // SILICATE preset because the Neptune-mass atmosphere is
            // less dust-loaded.
            val scatter = rng.nextFloat(4f, 9f)
            MieResult(
                scaleHeightKm = rng.nextFloat(4f, 9f),
                r = scatter * rng.nextFloat(1.05f, 1.30f),
                g = scatter * rng.nextFloat(0.95f, 1.10f),
                b = scatter * rng.nextFloat(0.85f, 1.0f),
                phaseG = rng.nextFloat(0.75f, 0.90f),
                phaseG2 = rng.nextFloat(-0.25f, -0.10f),
                phaseBlend = rng.nextFloat(0.10f, 0.22f),
                dirtiness = rng.nextFloat(0.4f, 0.7f),
                // Slight blue/green bias in absorption gives the warm
                // red-pink tint above the silicate deck.
                absR = rng.nextFloat(8f, 20f),
                absG = rng.nextFloat(15f, 30f),
                absB = rng.nextFloat(20f, 40f),
            )
        }
    }

    /**
     * Gas giant absorption band.
     *
     * CH4 absorption drives the blue appearance of ice giants (removes red light).
     * Alkali metals (Na/K) drive broad absorption in very hot gas giants.
     * Jupiter-class has moderate CH4 absorption plus chromophore effects.
     */
    private fun deriveGiantAbsorption(
        gasGiant: GasGiantProfile,
        atm: AtmosphericComposition,
        scaleHeightKm: Double,
        rng: DeterministicRandom,
    ): AbsorptionResult {
        val ch4 = atm.ch4
        val isIce = gasGiant.type == GasGiantType.ICE_GIANT ||
            gasGiant.type == GasGiantType.SUB_NEPTUNE
        // HELIUM_NEPTUNE and SILICATE_NEPTUNE skip CH₄ absorption entirely
        // — those atmospheres are post-escape He+CO₂ chemistry with no
        // methane left to drive the blue-side absorption that gives
        // ordinary ice giants their colour.
        if (gasGiant.type == GasGiantType.HELIUM_NEPTUNE ||
            gasGiant.type == GasGiantType.SILICATE_NEPTUNE) {
            return AbsorptionResult(0f, 0f, 0f, 25f, 15f)
        }

        // CH4 absorption: present on all cold/cool giants
        // Neptune preset: (2.0, 0.6, 7.6) at density 0.1 and ch4 ~2%.
        // Cap ch4Strength to 1.5 to avoid over-absorbing blue (which causes green).
        if (ch4 > 0.005f) {
            val ch4Strength = (ch4 * 20f).coerceAtMost(1.5f)
            return if (isIce) {
                // Neptune-like: CH4 absorbs red → blue appearance.
                // Neptune preset: (2.0, 0.6, 7.6) at center=15, width=16.
                // Use fixed-range width (12–20 km) instead of scaling with scale height,
                // which can produce 40–70 km widths that over-absorb and turn green.
                AbsorptionResult(
                    r = rng.nextFloat(1.5f, 2.5f) * ch4Strength,
                    g = rng.nextFloat(0.3f, 0.7f) * ch4Strength,
                    b = rng.nextFloat(5f, 8f) * ch4Strength,
                    centerKm = rng.nextFloat(10f, 20f),
                    widthKm = rng.nextFloat(12f, 20f),
                )
            } else {
                // Jupiter-like: moderate, broader
                AbsorptionResult(
                    r = rng.nextFloat(2f, 5f) * ch4Strength * 0.5f,
                    g = rng.nextFloat(2f, 5f) * ch4Strength * 0.5f,
                    b = rng.nextFloat(6f, 12f) * ch4Strength * 0.5f,
                    centerKm = rng.nextFloat(8f, 15f),
                    widthKm = rng.nextFloat(8f, 14f),
                )
            }
        }

        // Alkali metal absorption: Na/K for hot giants
        if (gasGiant.type == GasGiantType.ALKALI) {
            return AbsorptionResult(
                r = rng.nextFloat(1f, 4f),
                g = rng.nextFloat(2f, 6f),
                b = rng.nextFloat(3f, 8f),
                centerKm = (scaleHeightKm * 0.5).toFloat(),
                widthKm = (scaleHeightKm * 1.0).toFloat(),
            )
        }

        return AbsorptionResult(0f, 0f, 0f, 25f, 15f)
    }

    // ── Core computations ──

    /**
     * Rayleigh scattering cross-section relative to Earth's N₂/O₂ mix.
     * Based on (n-1)² where n is the refractive index of each gas.
     * Weighted by mixing ratio in the atmosphere.
     */
    private fun computeRayleighCrossSection(atm: AtmosphericComposition): Float {
        // (n-1)² values × 1e8 for each gas (from literature refractive indices at STP)
        // Earth mix reference: 0.78×8.88 + 0.21×7.34 = 8.47
        val n2Sq = 8.88f   // N₂: (2.98e-4)²
        val o2Sq = 7.34f   // O₂: (2.71e-4)²
        val co2Sq = 20.16f // CO₂: (4.49e-4)²
        val h2Sq = 1.93f   // H₂: (1.39e-4)²
        val heSq = 0.12f   // He: (0.35e-4)²
        val ch4Sq = 19.71f // CH₄: (4.44e-4)²
        val h2oSq = 6.55f  // H₂O: (2.56e-4)²
        val so2Sq = 22.0f  // SO₂: ~(4.69e-4)²
        val nh3Sq = 8.0f   // NH₃: ~(2.83e-4)²
        val h2sSq = 14.0f  // H₂S: ~(3.74e-4)²

        val earthRef = 8.47f

        // Weighted sum by mixing ratio
        val sigma = atm.n2 * n2Sq +
            atm.o2 * o2Sq +
            atm.co2 * co2Sq +
            atm.h2 * h2Sq +
            atm.he * heSq +
            atm.ch4 * ch4Sq +
            atm.h2o * h2oSq +
            atm.so2 * so2Sq +
            atm.nh3 * nh3Sq +
            atm.h2s * h2sSq

        return if (sigma > 0f) (sigma / earthRef).coerceIn(0.1f, 3f) else 1f
    }

    private fun computeSurfaceGravity(planet: Planet, isGiant: Boolean): Double {
        val massE = planet.massEarth ?: if (isGiant) 318.0 else 1.0
        val radiusE = planet.radiusEarth ?: if (isGiant) 11.2 else massE.pow(0.27)
        if (radiusE <= 0) return G_EARTH
        return G_EARTH * massE / (radiusE * radiusE)
    }

    /**
     * Mie scattering from atmospheric aerosols (dust, volcanic ash, tholins).
     *
     * The scattering coefficients represent the aerosol's ability to redirect light;
     * absorption coefficients represent light absorbed by the material. Together they
     * determine the aerosol's single-scattering albedo per channel. Iron oxide dust
     * absorbs blue/green light (high absG/absB) while scattering relatively neutrally —
     * the surface appears red because scattered red light survives while blue is absorbed.
     */
    private fun deriveMie(
        atm: AtmosphericComposition,
        surface: SurfaceComposition?,
        volcanicActivity: Float,
        surfaceTempK: Double,
        rng: DeterministicRandom,
    ): MieResult {
        // Mie scale height: dust concentrated near surface, typically 1-3 km.
        // Higher for dusty, thin-atmosphere worlds (Mars: 2.5km) or thick volcanic
        // atmospheres. Lower for wet worlds (rain scavenges aerosols).
        val waterFraction = surface?.water ?: 0f
        val baseMieH = when {
            atm.surfacePressureBar < 0.02f -> 1.0f         // thin: low aerosol transport
            waterFraction > 0.3f -> 0.8f                    // wet: rain washes dust out
            volcanicActivity > 0.5f -> 3.0f + volcanicActivity * 2f  // volcanic: ash lofted high
            surface?.iron ?: 0f > 0.2f -> 2.5f              // iron oxide dust (Mars-like)
            else -> 1.2f                                     // moderate default
        }

        // Aridity: less surface water → more available dust
        val aridity = (1f - waterFraction * 2f).coerceIn(0f, 1f)

        // Atmosphere carrying capacity (need some air to loft dust)
        val carrying = when {
            atm.surfacePressureBar < 0.01f -> 0.1f
            atm.surfacePressureBar < 0.1f -> 0.4f
            atm.surfacePressureBar < 5f -> 1.0f
            else -> 0.8f  // very thick: dust settles
        }

        // Dust loading strength [0, ~1]
        val dustLoading = (aridity * carrying * rng.nextFloat(0.3f, 0.8f) +
            volcanicActivity * 0.4f).coerceIn(0f, 1f)

        // Mie dirtiness: spatial variation in dust
        val dirtiness = when {
            volcanicActivity > 0.5f -> rng.nextFloat(0.4f, 0.8f) // patchy volcanic ash
            aridity > 0.6f -> rng.nextFloat(0.2f, 0.5f)          // dust storms
            else -> rng.nextFloat(0.05f, 0.25f)
        }

        // Compute per-channel scattering and absorption from surface dust composition
        val (scatR, scatG, scatB, absR, absG, absB) = computeDustChannels(
            surface, volcanicActivity, dustLoading, surfaceTempK, rng,
        )

        // Tholins: strong orange-brown aerosol (Titan-like)
        val tholins = surface?.tholins ?: 0f
        val finalScatR: Float; val finalScatG: Float; val finalScatB: Float
        val finalAbsR: Float; val finalAbsG: Float; val finalAbsB: Float
        if (tholins > 0.02f) {
            val t = (tholins * 5f).coerceAtMost(0.8f) // tholin dominance factor
            // Tholin scattering: orange-brown (high R, moderate G, low B)
            finalScatR = lerp(scatR, 26.5f * dustLoading, t)
            finalScatG = lerp(scatG, 20.0f * dustLoading, t)
            finalScatB = lerp(scatB, 3.0f * dustLoading, t)
            // Tholin absorption: absorbs blue strongly (like Titan)
            finalAbsR = lerp(absR, 7.3f * dustLoading, t)
            finalAbsG = lerp(absG, 6.0f * dustLoading, t)
            finalAbsB = lerp(absB, 0.0f, t)
        } else {
            finalScatR = scatR; finalScatG = scatG; finalScatB = scatB
            finalAbsR = absR; finalAbsG = absG; finalAbsB = absB
        }

        // Phase function: larger particles → more forward scattering
        val phaseG = rng.nextFloat(0.6f, 0.85f)
        val phaseG2 = rng.nextFloat(-0.25f, -0.05f)
        val phaseBlend = rng.nextFloat(0.05f, 0.2f)

        return MieResult(
            scaleHeightKm = baseMieH * rng.nextFloat(0.8f, 1.2f),
            r = finalScatR, g = finalScatG, b = finalScatB,
            phaseG = phaseG, phaseG2 = phaseG2, phaseBlend = phaseBlend,
            dirtiness = dirtiness,
            absR = finalAbsR, absG = finalAbsG, absB = finalAbsB,
        )
    }

    /**
     * Dust optical channels from surface composition.
     * Returns (scatR, scatG, scatB, absR, absG, absB).
     *
     * Scattering is relatively wavelength-independent for large dust particles (Mie regime),
     * so the scatter coefficients are similar across channels. The COLOR comes from
     * differential absorption — iron oxide absorbs blue/green, sulfur absorbs blue/violet,
     * carbon absorbs broadly. Earth's neutral aerosols (water/sulfate droplets) have
     * near-equal scatter and minimal absorption.
     */
    private fun computeDustChannels(
        surface: SurfaceComposition?,
        volcanicActivity: Float,
        dustLoading: Float,
        surfaceTempK: Double,
        rng: DeterministicRandom,
    ): DustChannels {
        if (surface == null) {
            // No surface data: neutral, Earth-like aerosols
            val s = 1.5f * dustLoading
            return DustChannels(s, s, s, 0.2f * dustLoading, 0.3f * dustLoading, 0.4f * dustLoading)
        }

        // Base scattering: relatively neutral for large particles
        val baseScatter = dustLoading * rng.nextFloat(1.0f, 3.0f)
        var scatR = baseScatter; var scatG = baseScatter; var scatB = baseScatter
        var absR = 0f; var absG = 0f; var absB = 0f

        // Iron oxide dust: absorbs blue/green strongly, appears red
        if (surface.iron > 0.05f) {
            val w = surface.iron * dustLoading
            // Scatter is relatively neutral (large particles)
            scatR += w * 2f; scatG += w * 2.5f; scatB += w * 3f
            // Absorption: strong blue/green absorption is what makes it RED
            absR += w * 0.5f
            absG += w * 8f
            absB += w * 15f
        }

        // Silicate dust: tan/gray, weak absorption
        if (surface.silicates > 0.1f) {
            val w = surface.silicates * dustLoading
            scatR += w * 1.5f; scatG += w * 1.5f; scatB += w * 1.5f
            absR += w * 0.3f; absG += w * 0.4f; absB += w * 0.6f
        }

        // Sulfur dust: absorbs blue/violet, appears yellow
        if (surface.sulfur > 0.02f) {
            val w = surface.sulfur * dustLoading
            scatR += w * 2f; scatG += w * 2f; scatB += w * 1.5f
            absR += w * 0.2f; absG += w * 1f; absB += w * 5f
        }

        // Carbon/soot: absorbs broadly, dark
        if (surface.carbon > 0.05f) {
            val w = surface.carbon * dustLoading
            scatR += w * 0.5f; scatG += w * 0.5f; scatB += w * 0.5f
            absR += w * 5f; absG += w * 5f; absB += w * 5f
        }

        // Volcanic ash: similar to silicate dust with sulfur contribution
        if (volcanicActivity > 0.3f) {
            val v = volcanicActivity * dustLoading * 0.5f
            scatR += v * 2f; scatG += v * 2f; scatB += v * 2f
            absR += v * 0.5f; absG += v * 0.8f; absB += v * 1.2f
        }

        return DustChannels(scatR, scatG, scatB, absR, absG, absB)
    }

    private data class DustChannels(
        val scatR: Float, val scatG: Float, val scatB: Float,
        val absR: Float, val absG: Float, val absB: Float,
    )

    /**
     * Absorption band layer (ozone-like structure in the shader).
     *
     * For O₂-bearing atmospheres: actual ozone (O₃) absorption — Chappuis band
     * absorbs orange/red, Hartley band absorbs UV.
     *
     * For CH₄-rich atmospheres (ice giants, cold CH₄ worlds): methane absorption
     * bands at 619nm, 727nm, 890nm selectively remove red/NIR light, making the
     * planet appear blue/cyan.
     *
     * For H₂S-rich atmospheres: broad UV/blue absorption.
     */
    private fun deriveAbsorptionBand(
        atm: AtmosphericComposition,
        isGiant: Boolean,
        scaleHeightKm: Double,
        rng: DeterministicRandom,
    ): AbsorptionResult {
        // CH4 absorption: dominant on ice giants and CH4-rich worlds
        // Absorbs red/NIR strongly → blue/cyan appearance
        if (atm.ch4 > 0.01f) {
            val ch4Strength = (atm.ch4 * 10f).coerceAtMost(1.5f)
            return AbsorptionResult(
                // Red absorbed most (CH4 bands at 619nm, 727nm, 890nm)
                r = 2.0f * ch4Strength,
                g = 0.6f * ch4Strength,
                b = 0.1f * ch4Strength,
                centerKm = rng.nextFloat(10f, 25f),
                widthKm = rng.nextFloat(10f, 18f),
            )
        }

        // Ozone: forms when O₂ > 0.5% and UV is available
        if (atm.o2 > 0.005f) {
            val o3Strength = (atm.o2 * 5f).coerceAtMost(1.5f)
            return AbsorptionResult(
                // Chappuis band absorbs orange/red; Hartley absorbs UV (not modeled)
                r = 0.9f * o3Strength,
                g = 1.3f * o3Strength,
                b = 0.1f * o3Strength,
                centerKm = 25f,  // ~25 km for Earth-like
                widthKm = 11f,
            )
        }

        // H2S: broad UV/blue absorption (some exoplanet atmospheres)
        if (atm.h2s > 0.01f) {
            val strength = (atm.h2s * 5f).coerceAtMost(1f)
            return AbsorptionResult(
                r = 0.1f * strength,
                g = 0.5f * strength,
                b = 1.5f * strength,
                centerKm = rng.nextFloat(10f, 20f),
                widthKm = rng.nextFloat(10f, 18f),
            )
        }

        return AbsorptionResult(0f, 0f, 0f, 25f, 15f)
    }

    /**
     * Cloud formation from volatile evaporation, condensation at altitude.
     * Returns cloud parameters in physical units.
     */
    private fun deriveClouds(
        atm: AtmosphericComposition,
        surface: SurfaceComposition?,
        surfaceTempK: Double,
        isGiant: Boolean,
        scaleHeightKm: Double,
        rng: DeterministicRandom,
    ): CloudResult {
        val pressure = atm.surfacePressureBar.toDouble()
        val convection = sqrt(pressure).toFloat().coerceIn(0.05f, 1.5f)

        // Baseline cloud texture parameters (can be overridden by specific cloud types)
        var size = rng.nextFloat(1.3f, 3.0f)
        var distortion = rng.nextFloat(0.3f, 0.8f)
        var bumpiness = rng.nextFloat(0.03f, 0.12f)
        var banding = rng.nextFloat(0.0f, 0.3f)

        // ── Desiccated hot worlds: cloudless greenhouse ──
        // Ultra-hot terrestrials (>700K) that have lost their water through photolysis +
        // hydrogen escape. No water → no H₂SO₄ → no cloud condensation nuclei. The planet
        // has a thick CO₂/N₂ atmosphere but clear skies revealing the scorched surface.
        // - Lava worlds (>1500K): mineral vapor doesn't condense at these temps
        // - Post-runaway greenhouse (700-1500K): water baked out, CO₂ remains
        // - Tidally-locked hot Terras: extreme substellar heating prevents condensation
        if (surfaceTempK > 1500 && !isGiant) {
            // Lava world: silicate cloud deck.
            // Day-side temperatures (~2000–3500 K) vaporize Na, K, SiO, Mg, Fe
            // from the molten surface. As those vapors circulate toward the
            // terminator and night-side, they cool below ~1700 K and
            // re-condense as dark grey/slate silicate and iron particulates
            // (MgSiO₃, Fe, FeO). Kite et al. 2016, "Atmosphere-interior
            // exchange on hot, rocky exoplanets".
            //
            // Unlike Venus's bright sulfuric acid decks, silicate clouds
            // absorb strongly in the visible → low albedo (0.08–0.22) and
            // dark grey-blue colour. Coverage is partial and banded: only
            // the cooler side of the planet condenses, and the distribution
            // is strongly wind-driven.
            //
            // The yellow-orange tint is contributed by Na/K vapor flames
            // above the cloud deck, not by the particulates themselves.
            // Volcanic-ash palette: R>G>B warmth inspired by Etna/Pinatubo
            // plumes. Clear hue bias so the ash character survives Reinhard
            // tonemap compression when the cloud deck is lit by intense
            // TORRID-host sunlight (near-grey decks tonemap toward white).
            val silicatePalette = longArrayOf(
                0xFF3A2C22L,   // dark volcanic ash-brown (Fe-rich)
                0xFF483828L,   // warm basaltic ash
                0xFF2E2620L,   // charred MgSiO₃
                0xFF523C2CL,   // iron-oxide dust warmth
                0xFF362A20L,   // dense plume shadow
            )
            val baseColour = silicatePalette[rng.nextInt(silicatePalette.size)]
            val alkaliTint = rng.nextFloat(0.08f, 0.20f)  // Na/K flame warmth
            val silicateColor = blendTowardWarm(baseColour, alkaliTint)

            return CloudResult(
                color = tintColor(silicateColor, rng, 12),
                coverage = rng.nextFloat(0.45f, 0.75f),  // partial, wind-driven
                altitudeKm = rng.nextFloat(8f, 25f),     // low; very small scale height
                density = rng.nextFloat(0.55f, 0.80f),
                size = rng.nextFloat(2.2f, 4.3f),        // patchy clumps
                distortion = rng.nextFloat(0.8f, 1.8f),
                bumpiness = rng.nextFloat(0.03f, 0.08f),
                banding = rng.nextFloat(0.4f, 0.8f),     // strong banding from global circulation
                type = CloudType.SILICATE,
            )
        }
        if (surfaceTempK > 700 && atm.h2o < 0.05f && !isGiant) {
            // Dry greenhouse: thick atmosphere, clear skies. Occasionally very sparse
            // high-altitude haze from volcanic outgassing or photochemical smog.
            val hazeCoverage = if (atm.so2 > 0.001f || atm.co2 > 0.3f) {
                rng.nextFloat(0.05f, 0.25f)
            } else {
                0f
            }
            return CloudResult(
                color = tintColor(0xFFE8D8B8.toLong(), rng, 20), // warm beige photochemical haze
                coverage = hazeCoverage,
                altitudeKm = (scaleHeightKm * 3.0).toFloat() + rng.nextFloat(10f, 40f),
                density = rng.nextFloat(0.1f, 0.3f),
                size = rng.nextFloat(3.4f, 6f), // large-scale streaks
                distortion = rng.nextFloat(0.5f, 1.5f),
                bumpiness = rng.nextFloat(0.01f, 0.02f),
                banding = rng.nextFloat(0.3f, 0.7f),
                type = CloudType.HAZE,
            )
        }

        // ── Venus-like cloud deck: thick, hot CO₂ atmosphere ──
        // Any thick hot CO₂ atmosphere develops H₂SO₄ or photochemical cloud decks.
        // Venus has only ~20 ppm H₂O but maintains a complete cloud envelope via
        // sulfuric acid aerosol cycling. No water check needed.
        if (atm.co2 > 0.5f && pressure > 10) {
            return CloudResult(
                color = tintColor(0xFFEFE3CC.toLong(), rng, 15), // cream with random tint
                coverage = rng.nextFloat(0.80f, 1.0f),
                altitudeKm = (scaleHeightKm * 5.0).toFloat() + rng.nextFloat(20f, 60f),
                density = rng.nextFloat(0.7f, 0.9f),
                size = rng.nextFloat(2.6f, 5.2f),
                distortion = rng.nextFloat(1.0f, 2.0f),
                bumpiness = rng.nextFloat(0.01f, 0.03f),
                banding = rng.nextFloat(0.5f, 0.9f),
                type = CloudType.SULFURIC,
            )
        }

        // ── Opaque steam deck ──
        if (atm.h2o > 0.3f && surfaceTempK > 380) {
            return CloudResult(
                color = tintColor(0xFFE8E8E8.toLong(), rng, 10), // near-white steam
                coverage = rng.nextFloat(0.9f, 1.0f),
                altitudeKm = (scaleHeightKm * 0.5).toFloat() + rng.nextFloat(2f, 8f),
                density = rng.nextFloat(0.8f, 0.95f),
                size = rng.nextFloat(1.7f, 3.4f),
                distortion = rng.nextFloat(0.5f, 1.2f),
                bumpiness = rng.nextFloat(0.02f, 0.06f),
                banding = rng.nextFloat(0.1f, 0.4f),
                type = CloudType.STEAM,
            )
        }

        // ── Find dominant cloud type ──
        var bestCoverage = 0f
        var bestColor = 0xFFF0F0F0.toLong()
        var bestAltitudeKm = (scaleHeightKm * 0.5).toFloat()
        var bestDensity = 0.5f
        var bestType = CloudType.NONE

        // Water clouds
        val surfaceWater = surface?.water ?: 0f
        val waterSource = (surfaceWater + atm.h2o * 5f).coerceAtMost(1f)
        if (waterSource > 0.01f && surfaceTempK > 200) {
            val evapRate = when {
                surfaceTempK > 350 -> 1.0f
                surfaceTempK > 300 -> 0.7f
                surfaceTempK > 273 -> 0.5f
                surfaceTempK > 230 -> 0.2f
                else -> 0.08f
            }
            val waterCoverage = waterSource * evapRate * convection * 4.0f *
                rng.nextFloat(0.7f, 1.3f)
            if (waterCoverage > bestCoverage) {
                bestCoverage = waterCoverage
                bestColor = tintColor(0xFFFFFFFF.toLong(), rng, 8) // near-white water
                bestAltitudeKm = rng.nextFloat(2f, 8f) // Earth: ~4 km
                // Density correlates with coverage: scattered clouds thinner, full deck thick
                bestDensity = rng.nextFloat(0.5f, 0.7f) + waterCoverage.coerceAtMost(1f) * 0.3f
                distortion = rng.nextFloat(0.4f, 0.9f)
                bumpiness = rng.nextFloat(0.04f, 0.12f)
                bestType = CloudType.WATER
            }
        }

        // CO₂ ice clouds
        if (atm.co2 > 0.05f && surfaceTempK < 350) {
            val coldFactor = when {
                surfaceTempK < 200 -> 1.5f
                surfaceTempK < 250 -> 1.0f
                surfaceTempK < 300 -> 0.4f
                else -> 0.15f
            }
            val co2Coverage = atm.co2 * coldFactor * convection * 1.0f *
                rng.nextFloat(0.7f, 1.3f)
            if (co2Coverage > bestCoverage) {
                bestCoverage = co2Coverage
                bestColor = tintColor(0xFFFFEBD1.toLong(), rng, 15) // pale cream (Mars CO₂ clouds)
                bestAltitudeKm = rng.nextFloat(15f, 50f) // high altitude
                bestDensity = rng.nextFloat(0.20f, 0.50f) // thin but visible
                bumpiness = rng.nextFloat(0.05f, 0.2f)
                bestType = CloudType.CO2_ICE
            }
        }

        // Methane clouds (Titan-like)
        val surfaceMethane = surface?.methane ?: 0f
        val methaneSource = (surfaceMethane + atm.ch4 * 5f).coerceAtMost(1f)
        if (methaneSource > 0.01f && surfaceTempK > 80 && surfaceTempK < 150) {
            val evapRate = when {
                surfaceTempK > 112 -> 0.6f
                surfaceTempK > 90 -> 0.3f
                else -> 0.1f
            }
            val methaneCoverage = methaneSource * evapRate * convection * 2.4f *
                rng.nextFloat(0.7f, 1.3f)
            if (methaneCoverage > bestCoverage) {
                bestCoverage = methaneCoverage
                bestColor = tintColor(0xFFD9AA41.toLong(), rng, 20) // Titan-like golden haze
                bestAltitudeKm = rng.nextFloat(10f, 50f)
                bestDensity = rng.nextFloat(0.4f, 0.85f)
                banding = rng.nextFloat(0.1f, 0.3f)
                bestType = CloudType.METHANE
            }
        }

        // NH₃ clouds
        if (atm.nh3 > 0.0005f && surfaceTempK < 200) {
            val nh3Coverage = rng.nextFloat(0.4f, 0.8f) * convection *
                rng.nextFloat(0.8f, 1.2f)
            if (nh3Coverage > bestCoverage) {
                bestCoverage = nh3Coverage
                bestColor = tintColor(0xFFF8F0E8.toLong(), rng, 12) // cream NH₃
                bestAltitudeKm = rng.nextFloat(5f, 30f)
                bestDensity = rng.nextFloat(0.5f, 0.8f)
                bestType = CloudType.AMMONIA
            }
        }

        // Remap raw source-fraction coverage onto the shader's visibility scale.
        // The cloud shader thresholds noise against (1 - coverage); values below
        // ~0.40 are effectively invisible. The bottom-up water/CO2/CH4/NH3
        // accumulators naturally land in 0.10–0.35 for real worlds, leaving
        // water planets conspicuously cloudless. The promote curve pushes
        // meaningful sources into the visible band while keeping truly trace
        // material (< 0.08 raw) as barely-there wisps.
        val promotedCoverage = promoteCloudCoverage(bestCoverage)
        return CloudResult(
            color = bestColor,
            coverage = promotedCoverage,
            altitudeKm = bestAltitudeKm,
            density = if (promotedCoverage > 0f) bestDensity else 0f,
            size = size,
            distortion = distortion,
            bumpiness = bumpiness,
            banding = banding,
            type = if (promotedCoverage > 0f) bestType else CloudType.NONE,
        )
    }

    /**
     * Maps raw cloud-source coverage to the shader's visibility scale.
     *
     *  raw  → out
     *  0.00 → 0.000   (no source)
     *  0.05 → 0.015   (trace wisps, invisible)
     *  0.08 → 0.024   (ceiling for "clear" worlds)
     *  0.15 → 0.185
     *  0.25 → 0.415   (substantial — shader visibility begins)
     *  0.35 → 0.585
     *  0.40 → 0.640   (cloudy baseline)
     *  0.60 → 0.860   (heavy deck)
     *  1.00 → 1.000
     *
     * Four-segment piecewise curve with a hard transition at 0.08 so that
     * genuinely sparse atmospheres stay clear rather than all getting promoted
     * to global cover.
     */
    private fun promoteCloudCoverage(raw: Float): Float {
        if (raw <= 0f) return 0f
        val y = when {
            raw < 0.08f -> raw * 0.30f
            raw < 0.30f -> 0.024f + (raw - 0.08f) * 2.30f
            raw < 0.60f -> 0.530f + (raw - 0.30f) * 1.10f
            else        -> 0.860f + (raw - 0.60f) * 0.35f
        }
        return y.coerceIn(0f, 1f)
    }

    /**
     * Fog: low-altitude haze. Water fog near seas, dust fog on arid worlds,
     * volcanic smog, tholin haze.
     */
    private fun deriveFog(
        atm: AtmosphericComposition,
        surface: SurfaceComposition?,
        volcanicActivity: Float,
        surfaceTempK: Double,
        scaleHeightKm: Double,
        rng: DeterministicRandom,
    ): FogResult {
        var density = 0f
        var color = tintColor(0xFFC5D3DE.toLong(), rng, 15) // blueish-white water fog
        var fogH = 3.0f  // default fog scale height
        var patchiness = 0.5f

        // Water fog: common near seas and on humid worlds
        if (atm.h2o > 0.01f && (surface?.water ?: 0f) > 0.1f) {
            density += (atm.h2o * 2f).coerceAtMost(0.15f)
            color = tintColor(0xFFC5D3DE.toLong(), rng, 15)
            fogH = 3.0f
            patchiness = rng.nextFloat(0.6f, 0.95f) // patchy fog banks
        }

        // Dust fog on arid worlds
        if (surface != null && surface.water < 0.05f && atm.surfacePressureBar > 0.1f) {
            val dustDensity = 0.05f + surface.iron * 0.15f
            if (dustDensity > density) {
                density = dustDensity
                color = dustFogColor(surface)
                fogH = rng.nextFloat(3f, 8f)
                patchiness = rng.nextFloat(0.7f, 0.95f) // dust storms are patchy
            }
        }

        // Volcanic smog
        if (volcanicActivity > 0.3f && atm.surfacePressureBar > 0.05f) {
            val volcDensity = volcanicActivity * 0.1f
            density += volcDensity
            if (volcDensity > 0.05f) {
                color = tintColor(0xFFD0B878.toLong(), rng, 18) // sulfurous yellow-brown
                patchiness = rng.nextFloat(0.3f, 0.7f)
            }
        }

        // Tholin haze near surface (Titan-like)
        val tholins = surface?.tholins ?: 0f
        if (tholins > 0.02f) {
            val tholinDensity = tholins * 2f
            density += tholinDensity
            color = tintColor(0xFFDC8025.toLong(), rng, 20) // Titan orange fog
            fogH = (scaleHeightKm * 0.3).toFloat().coerceIn(2f, 15f)
            patchiness = 0f // uniform tholin haze
        }

        // ── Pressure scaling: fog needs atmosphere to exist in ──
        // A 0.1 bar atmosphere can't support the same fog as a 1 bar atmosphere.
        // Scale: 0.01 bar → 0.1×, 0.1 bar → 0.32×, 1 bar → 1.0×, 10 bar → 1.0× (capped)
        val pressureScale = sqrt(atm.surfacePressureBar.toDouble().coerceIn(0.001, 1.0))
            .toFloat()

        // Patchiness–density coupling: concentrated fog (high patchiness, e.g. dust storms)
        // is visually denser where it appears. Uniform haze (low patchiness) is spread thin.
        // Scale: patchiness 0.0 → density × 0.3, patchiness 1.0 → density × 1.0.
        val patchScale = 0.3f + patchiness * 0.7f
        val finalDensity = (density * patchScale * pressureScale * 0.1f).coerceIn(0f, 0.05f)

        return FogResult(
            color = color,
            density = finalDensity,
            scaleHeightKm = fogH,
            patchiness = patchiness,
        )
    }

    /** Fog color from surface dust composition (iron → red-brown, silicate → tan). */
    private fun dustFogColor(surface: SurfaceComposition): Long {
        val iron = surface.iron; val silicates = surface.silicates; val sulfur = surface.sulfur
        val total = iron + silicates + sulfur
        if (total < 0.01f) return 0xFFB0A090.toLong()

        // Weighted blend
        val r = (iron * 0xD7 + silicates * 0xB0 + sulfur * 0xD0) / total
        val g = (iron * 0xAD + silicates * 0xA0 + sulfur * 0xC0) / total
        val b = (iron * 0x7F + silicates * 0x90 + sulfur * 0x60) / total
        return 0xFF000000.toLong() or
            (r.toInt().coerceIn(0, 255).toLong() shl 16) or
            (g.toInt().coerceIn(0, 255).toLong() shl 8) or
            b.toInt().coerceIn(0, 255).toLong()
    }

    /**
     * Blend an ARGB base colour toward a warm Na/K flame tint
     * (#C07A32, orange-amber). [strength] ∈ [0,1] controls blend amount.
     * Used for silicate cloud decks on lava worlds, where alkali vapors
     * above the cloud layer contribute warm emission to an otherwise
     * dark grey particulate deck.
     */
    private fun blendTowardWarm(argb: Long, strength: Float): Long {
        val a = (argb shr 24) and 0xFFL
        val br = ((argb shr 16) and 0xFFL).toFloat()
        val bg = ((argb shr 8) and 0xFFL).toFloat()
        val bb = (argb and 0xFFL).toFloat()
        val tr = 0xC0f
        val tg = 0x7Af
        val tb = 0x32f
        val r = (br + (tr - br) * strength).toInt().coerceIn(0, 255).toLong()
        val g = (bg + (tg - bg) * strength).toInt().coerceIn(0, 255).toLong()
        val b = (bb + (tb - bb) * strength).toInt().coerceIn(0, 255).toLong()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * Randomly tint an ARGB color by ±[maxShift] per channel.
     * Preserves alpha. Keeps the base character while adding per-planet variation.
     */
    private fun tintColor(argb: Long, rng: DeterministicRandom, maxShift: Int): Long {
        val a = (argb shr 24) and 0xFFL
        val r = (((argb shr 16) and 0xFF) + rng.nextInt(-maxShift, maxShift)).coerceIn(0L, 255L)
        val g = (((argb shr 8) and 0xFF) + rng.nextInt(-maxShift, maxShift)).coerceIn(0L, 255L)
        val b = ((argb and 0xFF) + rng.nextInt(-maxShift, maxShift)).coerceIn(0L, 255L)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * Post-generation "spice": applies random multiplicative variation to an
     * AtmosphereOptics result. Gives each planet a unique character even when
     * the base parameters fall into the same category.
     */
    private fun applySpice(optics: AtmosphereOptics, rng: DeterministicRandom): AtmosphereOptics {
        // Random multiplier in [1-spread, 1+spread]
        fun spice(spread: Float): Float = 1f + rng.nextFloat(-spread, spread)

        return optics.copy(
            // Rayleigh: ±12% — subtle shift in scattering strength
            rayleighR = optics.rayleighR * spice(0.12f),
            rayleighG = optics.rayleighG * spice(0.12f),
            rayleighB = optics.rayleighB * spice(0.12f),
            // Density: ±15% — varies overall atmospheric opacity
            densityMultiplier = optics.densityMultiplier * spice(0.15f),
            // Mie: ±20% — dust/aerosol loading varies widely in reality
            mieR = optics.mieR * spice(0.20f),
            mieG = optics.mieG * spice(0.20f),
            mieB = optics.mieB * spice(0.20f),
            mieAbsorptionR = optics.mieAbsorptionR * spice(0.20f),
            mieAbsorptionG = optics.mieAbsorptionG * spice(0.20f),
            mieAbsorptionB = optics.mieAbsorptionB * spice(0.20f),
            // Absorption band: ±15%
            ozoneR = optics.ozoneR * spice(0.15f),
            ozoneG = optics.ozoneG * spice(0.15f),
            ozoneB = optics.ozoneB * spice(0.15f),
            // Cloud coverage: ±10% (clamped)
            cloudCoverage = (optics.cloudCoverage * spice(0.10f)).coerceIn(0f, 1f),
            cloudDensity = (optics.cloudDensity * spice(0.10f)).coerceIn(0f, 1f),
            // Fog: ±15%
            fogDensity = (optics.fogDensity * spice(0.15f)).coerceIn(0f, 0.05f),
        )
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /**
     * Surface-dust tint normalized to (r,g,b) where r+g+b = 1, suitable for
     * weighting Rayleigh coefficients toward the dust's spectral signature
     * on thin atmospheres. Returns null if the surface has too little
     * dust-forming material to give a meaningful tint (pure water/ice
     * worlds, etc.). Per-material RGB signatures match `dustFogColor`:
     * iron oxide → red-dominant, silicate → tan, sulfur → yellow, carbon →
     * neutral grey.
     */
    private fun computeDustTint(surface: SurfaceComposition): RgbTriple? {
        val iron = surface.iron
        val silicates = surface.silicates
        val sulfur = surface.sulfur
        val carbon = surface.carbon
        val totalDust = iron + silicates + sulfur + carbon
        if (totalDust < 0.05f) return null
        val r = (iron * 0.84f + silicates * 0.69f + sulfur * 0.82f + carbon * 0.30f) / totalDust
        val g = (iron * 0.68f + silicates * 0.63f + sulfur * 0.75f + carbon * 0.30f) / totalDust
        val b = (iron * 0.50f + silicates * 0.56f + sulfur * 0.38f + carbon * 0.30f) / totalDust
        val sum = r + g + b
        if (sum < 0.01f) return null
        return RgbTriple(r / sum, g / sum, b / sum)
    }

    private data class RgbTriple(val r: Float, val g: Float, val b: Float)
}
