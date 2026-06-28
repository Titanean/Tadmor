package com.tadmor.domain.classification.visual

import com.tadmor.domain.classification.CompositionClass
import com.tadmor.domain.classification.PlanetClassification
import com.tadmor.domain.classification.TemperatureClass
import com.tadmor.domain.model.Planet

/**
 * Generates [GasGiantProfile] — cloud band texture parameters.
 * Band colors represent the cloud deck; the atmosphere model handles
 * Rayleigh scattering and overall apparent color separately.
 */
object GasGiantGenerator {

    fun generate(
        planet: Planet,
        classification: PlanetClassification,
        context: SystemContext,
        rng: DeterministicRandom,
    ): GasGiantProfile {
        val type = determineType(planet, classification, rng)
        val variants = ColorPalettes.gasGiantPaletteVariants(type)
        val basePalette = variants[rng.nextInt(variants.size)]

        val metallicityEnrichment = computeMetallicityEnrichment(context, classification, rng)
        val methaneAbundance = computeMethaneAbundance(classification, type, rng)

        // Generate 5 band colors from base palette with variation
        val bandColors = generateBandColors(basePalette, metallicityEnrichment, rng)

        // Pole color: sometimes matches bands, sometimes a contrasting hue
        val poleColor = generatePoleColor(type, bandColors, rng)

        val rawParams = bandParametersForType(type, rng)
        val sizeRamped = if (classification.compositionClass == CompositionClass.NEPTUNE) {
            applyNeptuneSizeRamp(rawParams, planet.radiusEarth)
        } else rawParams
        // Some giants render as un-banded, vortex-dominated swirls
        // rather than zonal-flow striped bodies — see [applySwirl] for
        // the per-type chances and rationale.
        val swirled = applySwirl(sizeRamped, type, rng)
        // Smallest sub-Neptunes get a chance to use Venus-style chevron
        // banding (three-jet zonal shearing in the advection loop). Sits
        // *between* the standard banded look and the swirl-vortex look,
        // bridging the gap between terrestrial Venus analogues and full
        // ice giants. See [applyChevron] for the gating rationale.
        val params = applyChevron(swirled, type, planet.massEarth, rng)

        return GasGiantProfile(
            type = type,
            metallicityEnrichment = metallicityEnrichment,
            methaneAbundance = methaneAbundance,
            bandColors = bandColors,
            poleColor = poleColor,
            poleFraction = params.poleFraction,
            stormIntensity = params.stormIntensity,
            bandingStrength = params.bandingStrength,
            bandCount = params.bandCount,
            bandBreakup = params.bandBreakup,
            bandSoftness = params.bandSoftness,
            microDetail = params.microDetail,
            striations = params.striations,
            turbulence = params.turbulence,
            contrast = params.contrast,
            noiseScale = params.noiseScale,
            unbanded = params.unbanded,
            chevronJets = params.chevronJets,
        )
    }

    /**
     * Smaller Neptunes have shallower rotational shear and a thinner
     * atmospheric column relative to radius, so the visible cloud field
     * shows more breakup and turbulence per unit area than a full-size
     * ice giant. Standard Neptune (~3.9 R⊕, our reference) gets the raw
     * parameters; smaller worlds get a smooth ramp that increases
     * bandBreakup, turbulence, and microDetail; larger ice giants
     * (Saturn-mass-but-Neptune-class outliers) stay at baseline. Visual
     * effect: gives sub-Neptunes and mini-Neptunes a distinct identity
     * between super-Earths and standard Neptunes without changing the
     * base palette/cloud type.
     */
    private fun applyNeptuneSizeRamp(p: BandParams, radiusEarth: Double?): BandParams {
        val r = (radiusEarth ?: 3.9).coerceIn(1.5, 11.0)
        // Ramp 0 → 1 between r = 4 R⊕ (standard Neptune, baseline) and
        // r = 2 R⊕ (smallest Neptunes in the catalog — max swirl).
        // Below 2 R⊕ stays clamped at full effect.
        val t = ((4.0 - r) / 2.0).coerceIn(0.0, 1.0).toFloat()
        // boost = 1.0 at r ≥ 4; up to 1.7× at r = 2.
        val boost = 1.0f + t * 0.7f
        // suppress = 1.0 at r ≥ 4; down to 0.5× at r = 2. Models weaker
        // zonal shear in shallow atmospheres — bands lose definition and
        // the planet reads as swirling rather than striped.
        val suppress = 1.0f - t * 0.5f
        return p.copy(
            bandingStrength = p.bandingStrength * suppress,
            striations = p.striations * suppress,
            bandSoftness = (p.bandSoftness * boost).coerceAtMost(0.95f),
            bandBreakup = (p.bandBreakup * boost).coerceAtMost(0.95f),
            turbulence = (p.turbulence * boost).coerceAtMost(0.95f),
            microDetail = (p.microDetail * boost).coerceAtMost(0.85f),
        )
    }

    /**
     * Some giants render as un-banded, vortex-dominated swirls instead
     * of zonal-flow striped bodies. The physical motivation: at high
     * temperatures (Sudarsky Class IV/V hot Jupiters), the equator-pole
     * temperature contrast is small and the radiative timescale is fast,
     * so circulation is dominated by chaotic vortices rather than stable
     * zonal jets. Wikipedia's Class V illustrations consistently show
     * this swirling-no-bands look. Smaller / lower-shear ice giants also
     * occasionally show it — Uranus is famously near-featureless and
     * the same regime can apply to some catalogued ice giants.
     *
     * The per-type chances bias toward the hot-Jupiter end of the
     * spectrum (SILICATE is mostly swirl, ALKALI sometimes) and away
     * from the cool well-banded end (AMMONIA / WATER / CLEAR /
     * HELIUM_NEPTUNE / SILICATE_NEPTUNE always keep their banded
     * structure, since those have well-defined zonal flow regimes).
     *
     * When the roll triggers swirl mode: bandCount collapses to ~1 (no
     * latitudinal structure), bandingStrength and striations drop near
     * zero (no preferred direction), bandSoftness pushes max (no visible
     * band edges), and microDetail + turbulence + bandBreakup push max
     * so the gas-giant bake's curl-noise + domain-warp paths dominate
     * the visible cloud field.
     */
    private fun applySwirl(
        p: BandParams,
        type: GasGiantType,
        rng: DeterministicRandom,
    ): BandParams {
        val swirlChance = when (type) {
            GasGiantType.SILICATE -> 0.75f
            GasGiantType.ALKALI -> 0.35f
            GasGiantType.ICE_GIANT -> 0.25f
            GasGiantType.SUB_NEPTUNE -> 0.30f
            GasGiantType.THOLIN -> 0.15f
            else -> 0f
        }
        if (swirlChance <= 0f || !rng.chance(swirlChance)) return p
        // Bake shader takes the unbanded code path entirely when this flag
        // is true — sin-based latitudinal banding is replaced by a domain-
        // warped FBM colour field. The other parameter tweaks here still
        // matter because the curl-noise advection above the colour-mapping
        // step uses them: high turbulence + breakup amplify vortex
        // structure in the advected position field.
        return p.copy(
            unbanded = true,
            bandCount = rng.nextFloat(1.0f, 1.8f),
            bandingStrength = p.bandingStrength * rng.nextFloat(0.05f, 0.25f),
            striations = 0f,  // no preferred-direction streaks in swirl mode
            bandSoftness = rng.nextFloat(0.75f, 0.95f),
            bandBreakup = rng.nextFloat(0.80f, 0.95f),
            turbulence = rng.nextFloat(0.70f, 0.95f),
            microDetail = rng.nextFloat(0.65f, 0.90f),
        )
    }

    /**
     * Venus-style three-jet chevron shearing for the smallest sub-Neptunes.
     * Bridges the visual gap between terrestrial Venus analogues (which
     * get chevron via the cloud-overlay path) and full ice giants (which
     * stay banded or vortex-swirly). At masses below ~6 M⊕ the body sits
     * close enough to the terrestrial regime that a Venus-like equatorial
     * super-rotation jet is physically plausible — the atmosphere is thick
     * but not yet deep enough to support the multi-band zonal-jet structure
     * of a full ice giant.
     *
     * Gating:
     *   • Only [GasGiantType.SUB_NEPTUNE]. Other types have well-defined
     *     regimes that don't admit chevron-style super-rotation (ice
     *     giants have deep multi-jet structure; hot Jupiters chaotic
     *     vortices; etc).
     *   • Only when mass < 7.5 M⊕. Above that threshold the body is
     *     large enough that standard ice-giant zonal flow is the better
     *     match.
     *   • 50 % chance per qualifying planet so the population shows a
     *     mix of chevron-class and standard-banded small sub-Neptunes,
     *     rather than every small SUB_NEPTUNE looking like Venus.
     *
     * When triggered, forces `unbanded = true` (chevron is only meaningful
     * in the un-banded colour path — the latitudinal sin-bands of standard
     * mode would compound chaotically with the chevron-jet rotation) and
     * applies the same parameter tweaks `applySwirl` would. The
     * distinguishing data is the `chevronJets = true` flag, which the bake
     * shader's advection loop reads to enable the three-jet shearing pass.
     */
    private fun applyChevron(
        p: BandParams,
        type: GasGiantType,
        massEarth: Double?,
        rng: DeterministicRandom,
    ): BandParams {
        if (type != GasGiantType.SUB_NEPTUNE) return p
        if (massEarth == null || massEarth >= 7.5) return p
        if (!rng.chance(0.5f)) return p
        return p.copy(
            unbanded = true,
            chevronJets = true,
            bandCount = rng.nextFloat(1.0f, 1.8f),
            bandingStrength = p.bandingStrength * rng.nextFloat(0.05f, 0.25f),
            striations = 0f,
            bandSoftness = rng.nextFloat(0.75f, 0.95f),
            bandBreakup = rng.nextFloat(0.80f, 0.95f),
            turbulence = rng.nextFloat(0.70f, 0.95f),
            microDetail = rng.nextFloat(0.65f, 0.90f),
        )
    }

    private fun determineType(
        planet: Planet,
        classification: PlanetClassification,
        rng: DeterministicRandom,
    ): GasGiantType {
        val massE = planet.massEarth
        val radiusE = planet.radiusEarth
        val tempClass = classification.temperatureClass
        if (classification.compositionClass == CompositionClass.NEPTUNE) {
            // Hot Neptune temperature dispatch comes BEFORE sub-Neptune
            // detection because the size ramp handles small-Neptune
            // appearance separately — a sub-Neptune-sized world that
            // also happens to be torrid should still get the silicate
            // cloud preset (LTT 9779 b is ~4.7 R⊕ but it's the torrid
            // class that defines its visual identity).
            if (tempClass == TemperatureClass.TORRID) {
                return GasGiantType.SILICATE_NEPTUNE
            }
            if (tempClass == TemperatureClass.HOT) {
                return GasGiantType.HELIUM_NEPTUNE
            }
            // Sub-Neptune detection (cool/temperate-and-below low-mass worlds)
            if (massE != null && massE < 10 && radiusE != null && radiusE < 4) {
                return GasGiantType.SUB_NEPTUNE
            }
            // A minority of cold/frigid ice giants develop tholin haze from
            // photochemical CH4 processing. Most remain clear blue ice giants.
            val tholinChance = when (tempClass) {
                TemperatureClass.FRIGID -> 0.30f  // ~1 in 3
                TemperatureClass.COLD   -> 0.20f  // ~1 in 5
                else -> 0f
            }
            if (tholinChance > 0f && rng.chance(tholinChance)) return GasGiantType.THOLIN
            return GasGiantType.ICE_GIANT
        }

        // Jupiter-class: map by temperature
        return when (tempClass) {
            TemperatureClass.FRIGID, TemperatureClass.COLD -> GasGiantType.AMMONIA
            TemperatureClass.COOL -> GasGiantType.WATER
            TemperatureClass.TEMPERATE -> GasGiantType.CLEAR
            TemperatureClass.WARM -> GasGiantType.ALKALI
            TemperatureClass.HOT, TemperatureClass.TORRID -> GasGiantType.SILICATE
            null -> GasGiantType.AMMONIA // default cold
        }
    }

    private fun computeMetallicityEnrichment(
        context: SystemContext,
        classification: PlanetClassification,
        rng: DeterministicRandom,
    ): Float {
        val starMet = (context.starMetallicity ?: 0.0).toFloat()
        val base = if (classification.compositionClass == CompositionClass.NEPTUNE) {
            rng.nextFloat(5f, 15f)
        } else {
            rng.nextFloat(1.5f, 6f)
        }
        return (base * (1f + starMet * 0.5f)).coerceIn(0.5f, 20f)
    }

    private fun computeMethaneAbundance(
        classification: PlanetClassification,
        type: GasGiantType,
        rng: DeterministicRandom,
    ): Float {
        // Helium and silicate Neptunes have lost most of their methane
        // (to atmospheric escape, photodissociation, or thermochemical
        // breakdown at high temp), so the planet doesn't take on the
        // CH₄-driven blue colour of a normal ice giant.
        if (type == GasGiantType.HELIUM_NEPTUNE || type == GasGiantType.SILICATE_NEPTUNE) {
            return 0f
        }
        val tempClass = classification.temperatureClass
        return when {
            tempClass == TemperatureClass.FRIGID -> rng.nextFloat(0.03f, 0.06f)
            tempClass == TemperatureClass.COLD -> rng.nextFloat(0.015f, 0.04f)
            tempClass == TemperatureClass.COOL -> rng.nextFloat(0.005f, 0.02f)
            else -> rng.nextFloat(0.0f, 0.005f)
        }
    }

    private fun generateBandColors(
        basePalette: LongArray,
        metallicityEnrichment: Float,
        rng: DeterministicRandom,
    ): List<Long> {
        return basePalette.map { baseColor ->
            // ±10° hue shift — tight enough that cool blues stay blue, warm reds stay red
            val hueShift = rng.nextFloat(-10f, 10f)
            // Higher metallicity → slightly more saturated
            val satFactor = 0.85f + (metallicityEnrichment / 20f) * 0.3f
            val brightShift = rng.nextFloat(0.88f, 1.12f)

            var color = ColorPalettes.shiftHue(baseColor, hueShift)
            color = ColorPalettes.adjustSaturation(color, satFactor)
            color = ColorPalettes.adjustBrightness(color, brightShift)
            color
        }
    }

    /**
     * Generates pole color. About 35% of planets get a contrasting pole hue
     * (Jupiter's blue-grey poles, Saturn's green hexagon, icy white caps, etc.).
     * The rest get a muted desaturated version of a band color.
     */
    private fun generatePoleColor(
        type: GasGiantType,
        bandColors: List<Long>,
        rng: DeterministicRandom,
    ): Long {
        // Decide whether to use a contrasting pole color
        val contrastingPole = rng.chance(0.35f)

        if (contrastingPole) {
            val palette = when (type) {
                GasGiantType.AMMONIA, GasGiantType.WATER ->
                    if (rng.chance(0.6f)) ColorPalettes.POLE_JUPITER_BLUE
                    else ColorPalettes.POLE_SATURN_GREEN
                GasGiantType.ALKALI, GasGiantType.SILICATE ->
                    if (rng.chance(0.5f)) ColorPalettes.POLE_JUPITER_BLUE
                    else ColorPalettes.POLE_AURORA_TEAL
                GasGiantType.ICE_GIANT ->
                    if (rng.chance(0.5f)) ColorPalettes.POLE_ICE_WHITE
                    else ColorPalettes.POLE_AURORA_TEAL
                GasGiantType.SUB_NEPTUNE ->
                    ColorPalettes.POLE_ICE_WHITE
                GasGiantType.THOLIN ->
                    ColorPalettes.POLE_ICE_WHITE  // blue-white pole peeking through haze
                GasGiantType.CLEAR ->
                    ColorPalettes.POLE_JUPITER_BLUE
                GasGiantType.HELIUM_NEPTUNE ->
                    ColorPalettes.POLE_ICE_WHITE  // pole picks up the same pearlescent tone
                GasGiantType.SILICATE_NEPTUNE ->
                    if (rng.chance(0.5f)) ColorPalettes.POLE_ICE_WHITE
                    else ColorPalettes.POLE_AURORA_TEAL
            }
            val base = palette[rng.nextInt(palette.size)]
            // Small brightness variation
            return ColorPalettes.adjustBrightness(base, rng.nextFloat(0.85f, 1.1f))
        }

        // Standard: desaturated, darkened version of a band color
        val base = bandColors.last()
        return ColorPalettes.adjustBrightness(
            ColorPalettes.adjustSaturation(base, rng.nextFloat(0.5f, 0.85f)),
            rng.nextFloat(0.72f, 0.92f),
        )
    }

    private data class BandParams(
        val poleFraction: Float,
        val stormIntensity: Float,
        val bandingStrength: Float,
        val bandCount: Float,
        val bandBreakup: Float,
        val bandSoftness: Float,
        val microDetail: Float,
        val striations: Float,
        val turbulence: Float,
        val contrast: Float,
        val noiseScale: Float,
        val unbanded: Boolean = false,
        val chevronJets: Boolean = false,
    )

    private fun bandParametersForType(
        type: GasGiantType,
        rng: DeterministicRandom,
    ): BandParams = when (type) {
        GasGiantType.AMMONIA -> BandParams(
            poleFraction = rng.nextFloat(0.15f, 0.30f),
            stormIntensity = rng.nextFloat(0.4f, 0.9f),
            bandingStrength = rng.nextFloat(0.5f, 0.85f),
            bandCount = rng.nextFloat(4f, 7f),
            bandBreakup = rng.nextFloat(0.20f, 0.50f),
            bandSoftness = rng.nextFloat(0.15f, 0.40f),
            microDetail = rng.nextFloat(0.35f, 0.65f),
            striations = rng.nextFloat(0.25f, 0.55f),
            turbulence = rng.nextFloat(0.4f, 0.75f),
            contrast = rng.nextFloat(0.55f, 1.0f),
            noiseScale = 4.0f,
        )
        GasGiantType.WATER -> BandParams(
            poleFraction = rng.nextFloat(0.15f, 0.25f),
            stormIntensity = rng.nextFloat(0.2f, 0.55f),
            bandingStrength = rng.nextFloat(0.35f, 0.65f),
            bandCount = rng.nextFloat(4f, 7f),
            bandBreakup = rng.nextFloat(0.20f, 0.45f),
            bandSoftness = rng.nextFloat(0.25f, 0.55f),
            microDetail = rng.nextFloat(0.2f, 0.45f),
            striations = rng.nextFloat(0.15f, 0.35f),
            turbulence = rng.nextFloat(0.25f, 0.5f),
            contrast = rng.nextFloat(0.35f, 0.65f),
            noiseScale = 4.0f,
        )
        GasGiantType.CLEAR -> BandParams(
            poleFraction = rng.nextFloat(0.10f, 0.20f),
            stormIntensity = rng.nextFloat(0.05f, 0.25f),
            bandingStrength = rng.nextFloat(0.15f, 0.40f),
            bandCount = rng.nextFloat(4f, 8f),
            bandBreakup = rng.nextFloat(0.15f, 0.35f),
            bandSoftness = rng.nextFloat(0.45f, 0.75f),
            microDetail = rng.nextFloat(0.1f, 0.25f),
            striations = rng.nextFloat(0.08f, 0.20f),
            turbulence = rng.nextFloat(0.15f, 0.30f),
            contrast = rng.nextFloat(0.20f, 0.45f),
            noiseScale = 4.0f,
        )
        GasGiantType.ALKALI -> BandParams(
            poleFraction = rng.nextFloat(0.15f, 0.30f),
            stormIntensity = rng.nextFloat(0.25f, 0.60f),
            bandingStrength = rng.nextFloat(0.40f, 0.70f),
            bandCount = rng.nextFloat(4f, 7f),
            bandBreakup = rng.nextFloat(0.35f, 0.65f),
            bandSoftness = rng.nextFloat(0.25f, 0.50f),
            microDetail = rng.nextFloat(0.25f, 0.50f),
            striations = rng.nextFloat(0.20f, 0.40f),
            turbulence = rng.nextFloat(0.35f, 0.60f),
            contrast = rng.nextFloat(0.40f, 0.75f),
            noiseScale = 4.0f,
        )
        GasGiantType.SILICATE -> BandParams(
            poleFraction = rng.nextFloat(0.10f, 0.25f),
            stormIntensity = rng.nextFloat(0.15f, 0.40f),
            bandingStrength = rng.nextFloat(0.20f, 0.50f),
            bandCount = rng.nextFloat(4f, 7f),
            bandBreakup = rng.nextFloat(0.30f, 0.55f),
            bandSoftness = rng.nextFloat(0.35f, 0.65f),
            microDetail = rng.nextFloat(0.15f, 0.35f),
            striations = rng.nextFloat(0.12f, 0.25f),
            turbulence = rng.nextFloat(0.25f, 0.45f),
            contrast = rng.nextFloat(0.30f, 0.55f),
            noiseScale = 4.0f,
        )
        GasGiantType.ICE_GIANT -> BandParams(
            poleFraction = rng.nextFloat(0.10f, 0.22f),
            stormIntensity = rng.nextFloat(0.08f, 0.30f),
            bandingStrength = rng.nextFloat(0.20f, 0.45f),
            bandCount = rng.nextFloat(1.5f, 3.5f),
            bandBreakup = rng.nextFloat(0.45f, 0.80f),
            bandSoftness = rng.nextFloat(0.40f, 0.70f),
            microDetail = rng.nextFloat(0.08f, 0.25f),
            striations = rng.nextFloat(0.08f, 0.20f),
            turbulence = rng.nextFloat(0.15f, 0.35f),
            contrast = rng.nextFloat(0.20f, 0.42f),
            noiseScale = 3.0f,
        )
        GasGiantType.SUB_NEPTUNE -> BandParams(
            poleFraction = rng.nextFloat(0.10f, 0.20f),
            stormIntensity = rng.nextFloat(0.05f, 0.22f),
            bandingStrength = rng.nextFloat(0.15f, 0.40f),
            bandCount = rng.nextFloat(1.0f, 2.5f),
            bandBreakup = rng.nextFloat(0.40f, 0.70f),
            bandSoftness = rng.nextFloat(0.45f, 0.75f),
            microDetail = rng.nextFloat(0.05f, 0.18f),
            striations = rng.nextFloat(0.05f, 0.15f),
            turbulence = rng.nextFloat(0.12f, 0.28f),
            contrast = rng.nextFloat(0.15f, 0.35f),
            noiseScale = 2.5f,
        )
        GasGiantType.THOLIN -> BandParams(
            poleFraction = rng.nextFloat(0.15f, 0.28f),
            stormIntensity = rng.nextFloat(0.10f, 0.35f),
            bandingStrength = rng.nextFloat(0.25f, 0.50f),
            bandCount = rng.nextFloat(2f, 4f),
            bandBreakup = rng.nextFloat(0.45f, 0.75f),
            bandSoftness = rng.nextFloat(0.30f, 0.60f),
            microDetail = rng.nextFloat(0.15f, 0.35f),
            striations = rng.nextFloat(0.10f, 0.25f),
            turbulence = rng.nextFloat(0.20f, 0.45f),
            contrast = rng.nextFloat(0.35f, 0.65f),
            noiseScale = 3.0f,
        )
        GasGiantType.HELIUM_NEPTUNE -> BandParams(
            // Helium-dominated atmosphere: high mean molecular weight,
            // shallow scale height, and CO₂ photochemistry produce a soft
            // banded deck with low contrast — the planet reads as
            // "smooth pearlescent" rather than the high-contrast bands of
            // a methane-rich Neptune. Slightly more turbulent than a
            // regular ice giant since these are hot worlds with active
            // photochemistry near the cloud tops.
            poleFraction = rng.nextFloat(0.10f, 0.22f),
            stormIntensity = rng.nextFloat(0.10f, 0.30f),
            bandingStrength = rng.nextFloat(0.25f, 0.55f),
            bandCount = rng.nextFloat(2.5f, 5f),
            bandBreakup = rng.nextFloat(0.30f, 0.55f),
            bandSoftness = rng.nextFloat(0.45f, 0.75f),
            microDetail = rng.nextFloat(0.10f, 0.25f),
            striations = rng.nextFloat(0.08f, 0.20f),
            turbulence = rng.nextFloat(0.20f, 0.40f),
            contrast = rng.nextFloat(0.20f, 0.45f),
            noiseScale = 3.0f,
        )
        GasGiantType.SILICATE_NEPTUNE -> BandParams(
            // Ultra-hot Neptune with reflective silicate cloud deck.
            // Thermal-circulation-dominated rather than rotation-banded:
            // the dayside-to-nightside heat transport produces moderate
            // banding with lots of small-scale breakup from gravity-wave
            // forced turbulence above the photochemical haze.
            poleFraction = rng.nextFloat(0.10f, 0.22f),
            stormIntensity = rng.nextFloat(0.20f, 0.50f),
            bandingStrength = rng.nextFloat(0.25f, 0.55f),
            bandCount = rng.nextFloat(3f, 6f),
            bandBreakup = rng.nextFloat(0.40f, 0.70f),
            bandSoftness = rng.nextFloat(0.30f, 0.60f),
            microDetail = rng.nextFloat(0.20f, 0.40f),
            striations = rng.nextFloat(0.15f, 0.30f),
            turbulence = rng.nextFloat(0.30f, 0.55f),
            contrast = rng.nextFloat(0.30f, 0.55f),
            noiseScale = 3.5f,
        )
    }
}
