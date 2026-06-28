package com.tadmor.domain.classification.visual

import com.tadmor.domain.classification.PlanetClassification
import kotlin.math.max
import kotlin.math.min

/**
 * Builds a [PlanetIconProfile] from a real [VisualProfile].
 *
 * Every visual decision (rings, atmosphere, clouds, polar caps, craters,
 * surface/band colors) is read straight from the engine output so the 2D
 * icon matches the 3D globe.
 */
object IconProfileBuilder {

    fun build(profile: VisualProfile, classification: PlanetClassification): PlanetIconProfile {
        val seed = profile.seed
        return if (profile.gasGiantProfile != null) {
            buildGasGiant(profile, profile.gasGiantProfile)
        } else {
            buildRocky(profile, classification)
        }
    }

    // ────────────────────────────── ROCKY ──────────────────────────────

    private fun buildRocky(
        profile: VisualProfile,
        classification: PlanetClassification,
    ): PlanetIconProfile {
        val seed = profile.seed
        val surface = profile.surfaceComposition
        val temp = profile.surfaceTemperatureK
        val atm = profile.atmosphere
        val optics = profile.atmosphereOptics

        // Dominant solid material (silicate/iron/carbon/…) — matches the globe's
        // SurfaceColorBlender material→palette mapping exactly.
        val (solidBody, solidAccent, solidAccentFrac) = dominantSolidColors(surface, profile)

        // Body/accent and the terrain-noise threshold depend on whether the engine
        // put a sea (liquid / ice / lava) on the surface, and how much of it.
        // The globe only paints lava emission above ~900K (Phase 7c), and the
        // engine assigns lava seaLevel to *every* hot rocky regardless of the
        // HotRockyOutcome roll — so a 700K stripped-residual world would render
        // as a lava world in the icon while the globe shows barren rocky.
        // Filter out engine sea hits that don't correspond to a renderable sea.
        val isMolten = temp >= 900f
        // Per Phase 6 retention zones: 0.01–0.10 bar is exospheric (trace gas
        // only) and renders barren on the globe. The engine still computes
        // sea/cloud/atmosphere values for those worlds, so we have to filter
        // them out icon-side.
        // Water seas require a substantial atmosphere — trace/exospheric worlds
        // (< 0.50 bar) render barren on the globe even when the engine assigns
        // a nonzero seaLevel. Kepler-138 e and Kepler-1649 b are examples.
        val hasSubstantialAtmosphere = atm.present && atm.surfacePressureBar >= 0.50f
        val hasRealAtmosphere = atm.present && atm.surfacePressureBar >= 0.10f
        val temperaturePermitsSea = when {
            temp >= 900f -> true                              // lava (no atmosphere needed)
            temp in 273f..373f -> hasSubstantialAtmosphere    // liquid water
            temp < 273f -> hasSubstantialAtmosphere           // ice / frost lakes
            else -> false                                      // 373–900K = dry hot rock
        }
        val realSeaLevel = if (temperaturePermitsSea) profile.seaLevel else 0f
        // Volcanic hotspots match the globe's Phase 7c gate (`volcanism > 0.70`).
        // Skip when the engine intended ANY sea (profile.seaLevel > 0) — even if
        // our temperature filter zeroed realSeaLevel, the globe may render that
        // sea as water rather than lava (TOI-7166 b).
        val hasVisibleLava = profile.volcanicActivity > 0.70f && temp >= 300f
            && profile.seaLevel < 0.05f
        val bodyColor: Long
        val accentColor: Long
        val landThreshold: Float
        when {
            isMolten && realSeaLevel >= 0.10f -> {
                // Lava world (>900K): lava dominates. Engine gives molten worlds
                // seaLevel ~0.15–0.6 — lava must be the body, not accent.
                bodyColor = seaColor(profile)
                accentColor = solidBody
                landThreshold = (0.55f + realSeaLevel * 0.30f).coerceIn(0.55f, 0.85f)
            }
            !isMolten && realSeaLevel >= 0.55f -> {
                // Sea-dominated water world (global ocean). Raised from 0.50 so
                // only clearly ocean-dominated worlds flip body to water.
                bodyColor = seaColor(profile)
                accentColor = solidBody
                landThreshold = realSeaLevel.coerceIn(0.55f, 0.88f)
            }
            !isMolten && realSeaLevel >= 0.30f -> {
                // Solid-dominated with visible surface seas (Proxima-b style).
                // Raised from 0.15 — small engine seaLevel values produced
                // disproportionately large blue patches at icon resolution
                // (Kepler-138 e, Kepler-1649 b looked like ocean worlds).
                bodyColor = solidBody
                accentColor = seaColor(profile)
                landThreshold = (1f - realSeaLevel * 0.7f).coerceIn(0.60f, 0.85f)
            }
            hasVisibleLava && !isMolten && realSeaLevel < 0.15f -> {
                // Volcanically active rocky world (Io-like). Gated on low seaLevel
                // so worlds with water lakes (TOI-7166 b) don't also show lava
                // patches — the globe renders water, not lava, on those surfaces.
                bodyColor = solidBody
                accentColor = pickPalette(ColorPalettes.LAVA, seed, 10)
                val coverage = (profile.volcanicActivity * 0.45f).coerceIn(0.15f, 0.40f)
                landThreshold = 1f - coverage
            }
            else -> {
                // Dry world — two-material terrain from the dominant solids only.
                bodyColor = solidBody
                accentColor = solidAccent
                landThreshold = (1f - solidAccentFrac).coerceIn(0.45f, 0.75f)
            }
        }

        // Atmosphere overlay opacity — log-scaled density so Venus-class
        // atmospheres (60+ bar) become near-opaque and Mars-class (<0.01 bar)
        // contribute almost nothing. Earth ≈ 0.55, Titan ≈ 0.65, Venus ≈ 0.92.
        // Gated on `hasRealAtmosphere` so exospheric trace-gas worlds (the same
        // ones the globe renders barren) don't get a tinted overlay.
        val atmosphereColor: Long
        val atmosphereIntensity: Float
        if (hasRealAtmosphere && optics.densityMultiplier > 0.005f) {
            atmosphereColor = haloColorFromComposition(atm)
            val log10dm = kotlin.math.log10(optics.densityMultiplier.coerceAtLeast(0.01f))
            // Map log10(density) ∈ [-2, +2] → [0, 1].
            // Earth (≈0.5) → 0.32, Titan (≈0.65) → 0.40, Venus (≈0.95) → 0.74.
            val densityFactor = ((log10dm + 2f) / 4f).coerceIn(0f, 1f)
            atmosphereIntensity = (0.08f + densityFactor * 0.70f).coerceIn(0.08f, 0.78f)
        } else {
            atmosphereColor = 0x00000000L
            atmosphereIntensity = 0f
        }

        // Clouds — coverage, density, and color from AtmosphereOpticsDeriver.
        // Coverage = how much of the disk is cloudy. Density = how opaque those
        // clouds are. TRAPPIST-1 f has wide coverage but low density → wispy.
        // Zeroed on exospheric worlds: L 98-59 d would otherwise show a thick
        // cloud deck the globe never renders.
        val cloudCoverage = if (hasRealAtmosphere) optics.cloudCoverage.coerceIn(0f, 1f) else 0f
        val cloudDensity = if (hasRealAtmosphere) optics.cloudDensity.coerceIn(0f, 1f) else 0f
        val cloudColor = if (optics.cloudColor != 0L) optics.cloudColor else 0xFFF0F0F0L

        // Polar caps — extent straight from the engine; pick ice color by dominant volatile.
        val polarCapColor: Long = when {
            surface != null && surface.nitrogen > 0.2f ->
                pickPalette(ColorPalettes.NITROGEN_ICE, seed, 9)
            surface != null && surface.methane > 0.2f ->
                pickPalette(ColorPalettes.METHANE_ICE, seed, 9)
            surface != null && surface.sulfur > 0.2f && temp < 200f ->
                pickPalette(ColorPalettes.SULFUR_FROST, seed, 9)
            else ->
                pickPalette(ColorPalettes.WATER_ICE, seed, 9)
        }

        // Crater count — from the real density. Up to 14 for a 60dp icon.
        val craterCount = (profile.craterProfile.density * 14f).toInt().coerceIn(0, 14)

        // Rings — straight from the engine.
        val ring = profile.ringProfile
        val hasRings = ring != null
        val ringColor = ring?.colors?.firstOrNull() ?: 0x00000000L
        val ringInner = ring?.innerRadius ?: 0f
        val ringOuter = ring?.outerRadius ?: 0f
        val ringOpacity = ring?.opacity ?: 0f

        val dominant = ColorPalettes.interpolateColor(bodyColor, accentColor, 0.35f)

        return PlanetIconProfile(
            bulkClass = profile.bulkClass,
            dominantColor = dominant,
            bodyColor = bodyColor,
            accentColor = accentColor,
            bandColors = LongArray(0),
            atmosphereColor = atmosphereColor,
            atmosphereIntensity = atmosphereIntensity,
            cloudColor = cloudColor,
            cloudCoverage = cloudCoverage,
            cloudDensity = cloudDensity,
            polarCapColor = polarCapColor,
            polarCapExtent = profile.polarCapExtent.coerceIn(0f, 0.90f),
            tidallyLocked = profile.tidallyLocked,
            craterCount = craterCount,
            hasRings = hasRings,
            ringInnerRatio = ringInner,
            ringOuterRatio = ringOuter,
            ringColor = ringColor,
            ringOpacity = ringOpacity,
            landThreshold = landThreshold,
            seed = seed,
        )
    }

    // ──────────────────────────── GAS GIANTS ────────────────────────────

    private fun buildGasGiant(
        profile: VisualProfile,
        gas: GasGiantProfile,
    ): PlanetIconProfile {
        val seed = profile.seed

        // Take up to 6 real band colors from the engine — no hue jitter, no
        // palette re-rolls, no substitutions.
        val source = gas.bandColors
        val bands: LongArray = if (source.isEmpty()) {
            longArrayOf(0xFF8C7860L, 0xFFA08868L, 0xFF706050L)
        } else {
            val n = source.size.coerceIn(3, 6)
            LongArray(n) { source[it % source.size] }
        }

        // Halo: same composition-driven color as rocky worlds — the gas giant's
        // atmosphere composition still dictates the scattered limb color.
        val atm = profile.atmosphere
        val atmosphereColor = if (atm.present) haloColorFromComposition(atm) else 0L
        val atmosphereIntensity = if (atm.present) 0.35f else 0f

        val ring = profile.ringProfile
        val hasRings = ring != null
        val ringColor = ring?.colors?.firstOrNull() ?: 0x00000000L
        val ringInner = ring?.innerRadius ?: 0f
        val ringOuter = ring?.outerRadius ?: 0f
        val ringOpacity = ring?.opacity ?: 0f

        val dominant = averageColors(bands)

        return PlanetIconProfile(
            bulkClass = null,
            dominantColor = dominant,
            bodyColor = bands[0],
            accentColor = bands[bands.size - 1],
            bandColors = bands,
            unbanded = gas.unbanded,
            atmosphereColor = atmosphereColor,
            atmosphereIntensity = atmosphereIntensity,
            cloudColor = 0L,
            cloudCoverage = 0f,
            cloudDensity = 0f,
            polarCapColor = 0L,
            polarCapExtent = 0f,
            tidallyLocked = false,
            craterCount = 0,
            hasRings = hasRings,
            ringInnerRatio = ringInner,
            ringOuterRatio = ringOuter,
            ringColor = ringColor,
            ringOpacity = ringOpacity,
            landThreshold = 0f,
            seed = seed,
        )
    }

    // ──────────────────────────── helpers ────────────────────────────

    /**
     * Computes (dominant, second) material colors using the exact same
     * palette mapping the globe's SurfaceColorBlender uses.
     */
    private fun dominantSolidColors(
        surface: SurfaceComposition?,
        profile: VisualProfile,
    ): Triple<Long, Long, Float> {
        if (surface == null) return Triple(0xFF808080L, 0xFF606060L, 0.30f)
        val temp = profile.surfaceTemperatureK
        val atm = profile.atmosphere
        // Iron oxidation requires a real O₂-bearing atmosphere with non-trivial
        // pressure — abiotic photolysis O₂ doesn't rust the surface. Without this
        // gate, TRAPPIST-1 e/h pick up a rust-red iron-oxide accent that doesn't
        // match the airless globe.
        val canOxidizeIron = atm.present && atm.o2 > 0.05f && atm.surfacePressureBar > 0.10f
        val seed = profile.seed

        val entries = mutableListOf<Pair<Float, Long>>()

        if (surface.silicates > 0f) {
            val t = ((temp - 200f) / 1200f).coerceIn(0f, 1f)
            val palette = if (t < 0.5f) ColorPalettes.SILICATE_LIGHT else ColorPalettes.SILICATE_DARK
            entries += surface.silicates to pickPalette(palette, seed, 0)
        }
        if (surface.iron > 0f) {
            val palette = if (canOxidizeIron) ColorPalettes.IRON_OXIDE else ColorPalettes.IRON_METALLIC
            entries += surface.iron to pickPalette(palette, seed, 1)
        }
        if (surface.sulfur > 0f) {
            val palette = if (temp > 200f) ColorPalettes.SULFUR_WARM else ColorPalettes.SULFUR_FROST
            entries += surface.sulfur to pickPalette(palette, seed, 3)
        }
        if (surface.carbon > 0f) {
            val palette = if (temp < 1500f) ColorPalettes.CARBON_ORGANIC else ColorPalettes.CARBON_GRAPHITE
            entries += surface.carbon to pickPalette(palette, seed, 4)
        }
        if (surface.tholins > 0f) {
            entries += surface.tholins to pickPalette(ColorPalettes.THOLIN, seed, 8)
        }
        // Surface volatiles. Liquid water at the surface is handled via the
        // sea branches and never appears as a terrain accent (an icy world's
        // ocean is a sea, not a continent of ice). Solid water frost above
        // sea level — Mars-style polar cap extending into mid-latitudes,
        // Europa-style ice crust on a cold airless rock — has no other route
        // onto the icon, so admit it as a candidate accent when the temperature
        // is cold enough for ice to be stable on the surface AND no liquid sea
        // is rendered. Other volatiles (nitrogen/methane/ammonia) are still
        // routed exclusively through the polar-cap palette selector.
        val realSeaLevelForIce = if (temp < 273f && atm.present && atm.surfacePressureBar >= 0.10f)
            profile.seaLevel else 0f
        if (surface.water > 0f && temp < 240f && realSeaLevelForIce < 0.10f) {
            entries += surface.water to pickPalette(ColorPalettes.WATER_ICE, seed, 2)
        }

        if (entries.isEmpty()) return Triple(0xFF808080L, 0xFF606060L, 0.30f)
        entries.sortByDescending { it.first }
        val dominant = entries[0].second
        // Accent threshold lowered from 0.15 to 0.08: a 10% iron or sulphur
        // trace should still tint the icon — otherwise sub-dominant materials
        // disappear entirely. Below the threshold, fall back to a darkened
        // body color (one-material world look).
        val secondEntry = entries.getOrNull(1)
        return if (secondEntry != null && secondEntry.first >= 0.08f) {
            val total = entries[0].first + secondEntry.first
            val rawFrac = if (total > 0f) secondEntry.first / total else 0.35f
            // Amplify so accent is visually present at small icon sizes — a
            // 10% trace reads as ~20% of the icon, a 30% material as ~40%.
            // Floor at 0.20 (visible patches) and cap at 0.55 (don't drown
            // the dominant material).
            Triple(dominant, secondEntry.second, (rawFrac * 1.4f).coerceIn(0.20f, 0.55f))
        } else {
            Triple(dominant, darken(dominant, 0.30f), 0.35f)
        }
    }

    /**
     * Sea color by temperature regime — matches the globe's shader logic for
     * ocean appearance.
     */
    private fun seaColor(profile: VisualProfile): Long {
        val temp = profile.surfaceTemperatureK
        val seed = profile.seed
        val surface = profile.surfaceComposition
        return when {
            // Lava oceans — matches the globe's Phase 7c lava-emission gate.
            // Below 900K the surface is hot rock but cools fast enough to look
            // barren in the renderer; the icon must agree.
            temp >= 900f -> pickPalette(ColorPalettes.LAVA, seed, 10)
            // Liquid water
            temp >= 273f -> pickPalette(ColorPalettes.WATER_LIQUID, seed, 2)
            // Water/ice mix — cold Aquaria
            temp >= 90f -> pickPalette(ColorPalettes.WATER_ICE, seed, 2)
            // Cryogenic methane/ethane lakes (Titan-like)
            surface != null && (surface.methane > 0.1f || surface.tholins > 0.1f) ->
                pickPalette(ColorPalettes.METHANE_ICE, seed, 6)
            else -> pickPalette(ColorPalettes.NITROGEN_ICE, seed, 5)
        }
    }

    /**
     * Halo tint from dominant atmospheric composition. Maps real physical
     * regimes to recognizable limb colors (Titan orange, Venus pale-yellow,
     * Earth blue, Uranus/Neptune cyan, H₂/He pale blue).
     */
    private fun haloColorFromComposition(atm: AtmosphericComposition): Long {
        // Titan-like tholin haze: N2 + CH4 photochemistry
        if (atm.n2 > 0.3f && atm.ch4 > 0.02f) return 0xFFE0B068L
        // Methane-dominated (ice giant): deep cyan-blue
        if (atm.ch4 > 0.10f) return 0xFF80C8E8L
        // Thick CO2 (Venus-like): pale yellow-pink
        if (atm.co2 > 0.5f && atm.surfacePressureBar > 10f) return 0xFFE8B890L
        // Thin CO2 (Mars-like): dusty butterscotch
        if (atm.co2 > 0.5f) return 0xFFCC9878L
        // Sulfur volcanism (Io/Venus upper deck): yellow haze
        if (atm.so2 > 0.05f || atm.h2s > 0.05f) return 0xFFE8D8A0L
        // H2/He primary (sub-Neptune / gas giant): pale Rayleigh blue
        if (atm.h2 + atm.he > 0.5f) return 0xFF90B8E8L
        // Default N2/O2 or mixed: Earth Rayleigh blue
        return 0xFF80B4E8L
    }

    private fun pickPalette(palette: LongArray, seed: Long, salt: Int): Long {
        val index = ((seed xor (salt.toLong() * -7046029254386353131L)) ushr 32).toInt()
            .let { (it and 0x7FFFFFFF) % palette.size }
        return palette[index]
    }

    private fun averageColors(colors: LongArray): Long {
        if (colors.isEmpty()) return 0xFF808080L
        var ar = 0L; var ag = 0L; var ab = 0L
        for (c in colors) {
            ar += (c shr 16) and 0xFF
            ag += (c shr 8) and 0xFF
            ab += c and 0xFF
        }
        val n = colors.size.toLong()
        return (0xFFL shl 24) or
            ((ar / n) shl 16) or
            ((ag / n) shl 8) or
            (ab / n)
    }

    private fun darken(c: Long, amount: Float): Long {
        val k = (1f - amount).coerceIn(0f, 1f)
        val r = (((c shr 16) and 0xFF) * k).toLong().coerceIn(0L, 255L)
        val g = (((c shr 8) and 0xFF) * k).toLong().coerceIn(0L, 255L)
        val b = ((c and 0xFF) * k).toLong().coerceIn(0L, 255L)
        return (0xFFL shl 24) or (r shl 16) or (g shl 8) or b
    }
}
