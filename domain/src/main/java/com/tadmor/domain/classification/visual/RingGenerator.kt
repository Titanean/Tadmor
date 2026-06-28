package com.tadmor.domain.classification.visual

import com.tadmor.domain.classification.CompositionClass
import com.tadmor.domain.classification.PlanetClassification
import com.tadmor.domain.classification.TemperatureClass
import com.tadmor.domain.model.Planet
import kotlin.math.cbrt
import kotlin.math.pow

/**
 * Generates [RingProfile] with physically-motivated probability model.
 * Gas giants ~30%, ice giants ~15%, terrestrials ~8%.
 *
 * Ring probability is reduced when the planet's Hill sphere is too small
 * to support the generated ring outer radius (close-in orbits or massive
 * host stars), or when the system is tightly packed (gravitational
 * perturbations from neighbours destabilize ring particles).
 */
object RingGenerator {

    fun maybeGenerate(
        planet: Planet,
        classification: PlanetClassification,
        context: SystemContext,
        rng: DeterministicRandom,
    ): RingProfile? {
        val probability = baseProbability(planet, classification, context)
        if (!rng.chance(probability)) return null
        return generate(planet, classification, context, rng)
    }

    /**
     * Hill sphere radius in planet radii.
     * R_Hill = a × (M_p / 3 M★)^(1/3).
     * Returns null when essential data is missing.
     */
    private fun hillRadiusInPlanetRadii(
        planet: Planet,
        classification: PlanetClassification,
        context: SystemContext,
    ): Double? {
        val smaAU = planet.semiMajorAxisAU
            ?: classification.estimatedSemiMajorAxisAU
            ?: return null
        val starMass = context.starMassSolar ?: return null
        val massEarth = planet.massEarth ?: return null
        val radiusEarth = planet.radiusEarth ?: return null
        if (starMass <= 0 || radiusEarth <= 0) return null

        val massSolar = massEarth / 332_946.0  // Earth masses → solar masses
        val smaKm = smaAU * 1.496e8            // AU → km
        val radiusKm = radiusEarth * 6371.0     // Earth radii → km

        val hillKm = smaKm * cbrt(massSolar / (3.0 * starMass))
        return hillKm / radiusKm
    }

    private fun baseProbability(
        planet: Planet,
        classification: PlanetClassification,
        context: SystemContext,
    ): Float {
        val tempClass = classification.temperatureClass
        val comp = classification.compositionClass

        var prob = when (comp) {
            CompositionClass.JUPITER -> 0.30f
            CompositionClass.NEPTUNE -> 0.15f
            CompositionClass.TERRA -> 0.08f
            null -> 0.05f
        }

        // Terrestrial mass scaling: heavier worlds capture and retain rings
        // more easily. Sub-Earth (<0.5 M⊕) gets halved, super-Earths (>3 M⊕)
        // get a significant boost.
        if (comp == CompositionClass.TERRA) {
            val mass = planet.massEarth ?: 1.0
            when {
                mass < 0.5 -> prob *= 0.5f
                mass > 5.0 -> prob += 0.08f
                mass > 3.0 -> prob += 0.05f
                mass > 1.5 -> prob += 0.02f
            }
        }

        // Cold giants more likely (Saturn analog)
        if (comp == CompositionClass.JUPITER || comp == CompositionClass.NEPTUNE) {
            if (tempClass == TemperatureClass.COLD || tempClass == TemperatureClass.FRIGID) {
                prob += 0.10f
            }
            // Hot giants: sublimation destroys rings
            if (tempClass == TemperatureClass.HOT || tempClass == TemperatureClass.TORRID) {
                prob -= 0.15f
            }
        }

        // Older systems slightly more likely (more captured material)
        val age = context.starAge ?: 4.6
        if (age > 5.0) prob += 0.05f

        // Many planets → more debris
        if (context.planetCount > 4) prob += 0.05f

        // ── Hill sphere constraint ──
        // Rings must fit within ~1/3 of the Hill radius to be stable long-term
        // (Domingos et al. 2006). If the Hill sphere is small (close-in orbit
        // or massive star), rings are unlikely. A typical ring outer radius is
        // ~2.5 planet radii, so we need R_Hill > ~7.5 planet radii.
        val hillR = hillRadiusInPlanetRadii(planet, classification, context)
        if (hillR != null) {
            when {
                hillR < 5.0 -> prob -= 0.30f   // rings impossible (hot Jupiters)
                hillR < 10.0 -> prob -= 0.15f  // marginal — only thin rings survive
                hillR < 20.0 -> prob -= 0.05f  // slightly constrained
            }
        }

        return prob.coerceIn(0f, 0.6f)
    }

    /** Ring style: determines extent, opacity, dustiness, and gap parameters.
     *  WISPS sits below DEBRIS — barely-visible dust torus, the kind a transient
     *  impact-debris cloud or a Jupiter-Halo-style faint ring would produce. */
    private enum class RingStyle { SATURN, URANUS, DEBRIS, WISPS }

    private fun generate(
        planet: Planet,
        classification: PlanetClassification,
        context: SystemContext,
        rng: DeterministicRandom,
    ): RingProfile {
        val comp = classification.compositionClass
        val isJupiter = comp == CompositionClass.JUPITER
        val isNeptune = comp == CompositionClass.NEPTUNE
        val tempClass = classification.temperatureClass

        // Clamp ring outer radius to 1/3 of Hill sphere if available
        val hillR = hillRadiusInPlanetRadii(planet, classification, context)
        val maxOuterRadius = if (hillR != null) (hillR / 3.0).toFloat().coerceAtMost(3.6f) else 3.6f

        // Pick ring style — usually class-typical, but allow crossover:
        // ~5% of any class gets the new WISPS tier (Jupiter-Halo-style
        // ultra-faint dust ring), then ~20% of Jupiters get Uranus-style
        // thin rings, ~20% of Neptunes get Saturn-style solid rings.
        // Terras default to WISPS (impact-debris dust torus, the most
        // physically realistic terrestrial ring) with ~30% rolling up to
        // visible DEBRIS — so most Earth-class rings are barely-there
        // wisps rather than sharp Saturn-style decks.
        val style = when {
            isJupiter -> when {
                rng.chance(0.05f) -> RingStyle.WISPS
                rng.chance(0.21f) -> RingStyle.URANUS  // 0.21 of remaining = ~0.20 overall
                else -> RingStyle.SATURN
            }
            isNeptune -> when {
                rng.chance(0.05f) -> RingStyle.WISPS
                rng.chance(0.21f) -> RingStyle.SATURN
                else -> RingStyle.URANUS
            }
            else -> if (rng.chance(0.30f)) RingStyle.DEBRIS else RingStyle.WISPS
        }

        // Power-biased 0..1: median ~0.36, tail out to 1.0. Drives the inner
        // radius along an extended range so most rings still hug the planet
        // (current look) while a minority start noticeably further out —
        // e.g. a Saturn-style system with a wider clear zone.
        val innerRoll = rng.nextFloat(0f, 1f).toDouble().pow(1.8).toFloat()

        val innerRadius: Float
        var outerRadius: Float
        val opacity: Float
        var gapCount: Int
        val dustiness: Float

        when (style) {
            RingStyle.SATURN -> {
                innerRadius = 1.2f + innerRoll * (2.2f - 1.2f)
                outerRadius = innerRadius + rng.nextFloat(0.6f, 2.0f)
                opacity = rng.nextFloat(0.4f, 0.95f)
                gapCount = rng.nextInt(4) + 1   // 1-4 gaps
                dustiness = rng.nextFloat(0.05f, 0.35f)  // mostly solid bands
            }
            RingStyle.URANUS -> {
                innerRadius = 1.4f + innerRoll * (2.6f - 1.4f)
                outerRadius = innerRadius + rng.nextFloat(0.2f, 0.8f)  // narrower
                opacity = rng.nextFloat(0.15f, 0.6f)
                gapCount = rng.nextInt(2)        // 0-1 gaps
                dustiness = rng.nextFloat(0.5f, 0.95f)  // thin/wispy ringlets
            }
            RingStyle.DEBRIS -> {
                innerRadius = 1.5f + innerRoll * (2.8f - 1.5f)
                outerRadius = innerRadius + rng.nextFloat(0.2f, 0.5f)
                opacity = rng.nextFloat(0.1f, 0.4f)
                gapCount = rng.nextInt(2)
                dustiness = rng.nextFloat(0.4f, 0.85f)
            }
            RingStyle.WISPS -> {
                // Ultra-faint dust torus. Sits BELOW DEBRIS in opacity —
                // barely visible against the planet, just enough to read as
                // "this body has a ring" on close inspection. Pure dust
                // (no banded structure), no gaps. Models impact-debris
                // dust on terras and Jupiter-Halo-style faint dust rings
                // on giants. Wider radial extent than DEBRIS because dust
                // spreads out under radiation pressure rather than
                // settling into discrete bands.
                innerRadius = 1.5f + innerRoll * (3.0f - 1.5f)
                outerRadius = innerRadius + rng.nextFloat(0.4f, 1.2f)
                opacity = rng.nextFloat(0.02f, 0.10f)
                gapCount = 0
                dustiness = rng.nextFloat(0.85f, 1.0f)
            }
        }

        // ── Random large breakup gap ──
        // ~25% chance of a major gap that visually splits the ring into
        // distinct inner/outer sections. Skipped for WISPS, which is
        // pure dust haze and has no banded structure to split.
        if (style != RingStyle.WISPS && rng.chance(0.25f)) {
            gapCount = (gapCount + 1).coerceAtMost(4)
        }

        // ── Random width modifier ──
        // Scale the annulus width from 10% to 100% of its base value.
        // Produces everything from vestigial ringlets to full ring systems.
        // Bias toward wider: uniform^0.6 pushes the median from 0.50 to ~0.63.
        val widthScale = rng.nextFloat(0f, 1f).toDouble().pow(0.6).toFloat()
            .coerceIn(0.10f, 1.0f)
        val span = outerRadius - innerRadius
        outerRadius = innerRadius + span * widthScale

        // Enforce Hill sphere limit on ring extent
        outerRadius = outerRadius.coerceAtMost(maxOuterRadius)
        if (outerRadius <= innerRadius + 0.05f) {
            outerRadius = innerRadius + 0.05f  // minimum annulus width
        }

        // Ring colors: icy (cold) vs rocky (warm)
        val isCold = tempClass == TemperatureClass.FRIGID ||
            tempClass == TemperatureClass.COLD ||
            tempClass == TemperatureClass.COOL
        val colors = if (isCold) {
            // Icy rings: white/cream/pale blue
            (1..rng.nextInt(2) + 2).map {
                val base = ColorPalettes.ICE_RING[rng.nextInt(ColorPalettes.ICE_RING.size)]
                ColorPalettes.adjustBrightness(base, rng.nextFloat(0.85f, 1.15f))
            }
        } else {
            // Rocky/dusty rings: brown/gray/tan
            (1..rng.nextInt(2) + 2).map {
                val base = ColorPalettes.ROCK_RING[rng.nextInt(ColorPalettes.ROCK_RING.size)]
                ColorPalettes.adjustBrightness(base, rng.nextFloat(0.85f, 1.15f))
            }
        }

        val tiltDeg = rng.nextFloat(0f, 30f)

        return RingProfile(
            innerRadius = innerRadius,
            outerRadius = outerRadius,
            colors = colors,
            opacity = opacity,
            gapCount = gapCount,
            dustiness = dustiness,
            tiltDeg = tiltDeg,
        )
    }
}
