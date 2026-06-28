package com.tadmor.domain.classification.visual

import com.tadmor.domain.classification.CompositionClass
import com.tadmor.domain.classification.PlanetClassification
import com.tadmor.domain.model.Planet
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Computes tidal locking, rotation period, rotational oblateness,
 * and Roche-lobe tidal elongation.
 */
object TidalCalculator {

    private const val EARTH_MASS_KG = 5.972e24
    private const val EARTH_RADIUS_M = 6.371e6
    private const val JUPITER_MASS_KG = 1.898e27
    private const val SOLAR_MASS_KG = 1.989e30
    private const val AU_M = 1.496e11
    private const val G = 6.674e-11 // m³ kg⁻¹ s⁻²

    data class TidalResult(
        val tidallyLocked: Boolean,
        val rotationPeriodHours: Float,
        val oblateness: Float,
        val tidalElongation: Float,
    )

    fun compute(
        planet: Planet,
        classification: PlanetClassification,
        context: SystemContext,
        rng: DeterministicRandom,
    ): TidalResult {
        val isGiant = classification.compositionClass == CompositionClass.JUPITER ||
            classification.compositionClass == CompositionClass.NEPTUNE

        val locked = isTidallyLocked(planet, classification, context, isGiant)
        val rotationHours = estimateRotation(planet, classification, context, isGiant, locked, rng)
        val oblateness = computeOblateness(planet, rotationHours, isGiant)
        val elongation = computeTidalElongation(planet, context)

        return TidalResult(
            tidallyLocked = locked,
            rotationPeriodHours = rotationHours,
            oblateness = oblateness,
            tidalElongation = elongation,
        )
    }

    /**
     * Tidal locking: τ_lock ∝ a⁶ × M_planet / (M_star² × R_planet⁵)
     * If τ_lock < star age → locked.
     */
    private fun isTidallyLocked(
        planet: Planet,
        classification: PlanetClassification,
        context: SystemContext,
        isGiant: Boolean,
    ): Boolean {
        val smaAU = planet.semiMajorAxisAU
            ?: classification.estimatedSemiMajorAxisAU ?: return false
        val starMass = context.starMassSolar ?: 1.0
        val age = context.starAge ?: 4.6 // Gyr

        // Planet mass and radius (defaults if unknown)
        val massE = planet.massEarth ?: if (isGiant) 100.0 else 1.0
        val radiusE = planet.radiusEarth ?: if (isGiant) 11.0 else 1.0

        // Tidal locking timescale (Gyr), derived from:
        // τ = ω₀ α Q a⁶ M_p / (3 G k₂ M*² R³)
        // where α=0.4 (moment of inertia), Q=tidal quality factor,
        // k₂=0.3 (Love number), ω₀=2π/12hr (primordial spin).
        //
        // In Earth/Solar units: τ(Gyr) ≈ C × a⁶ × M_p / (M*² × R³)
        // C ≈ 200 for rocky (Q~100), ≈ 200,000 for gas giants (Q~10⁵)
        val c = if (isGiant) 2e5 else 200.0
        val a6 = smaAU.pow(6)
        val mStar2 = starMass.pow(2)
        val rP3 = radiusE.pow(3)

        if (rP3 <= 0 || mStar2 <= 0) return false
        val tauLock = c * a6 * massE / (mStar2 * rP3)

        return tauLock < age
    }

    /**
     * Rotation period estimation.
     * - Tidally locked → period = orbital period
     * - Rocky: mass-based scaling with tidal braking
     * - Giant: fast rotation (~10hr baseline) with mass/radius scaling
     */
    private fun estimateRotation(
        planet: Planet,
        classification: PlanetClassification,
        context: SystemContext,
        isGiant: Boolean,
        locked: Boolean,
        rng: DeterministicRandom,
    ): Float {
        if (locked) {
            // Rotation = orbital period
            val periodDays = planet.orbitalPeriodDays
                ?: classification.estimatedOrbitalPeriodDays
                ?: 1.0
            return (periodDays * 24.0).toFloat().coerceIn(1f, 100000f)
        }

        if (isGiant) {
            // Baseline rotation scales with physical class:
            //   Jupiter-class (radius ≥ 8 R⊕):       ~10h (Jupiter 9.9h, Saturn 10.7h)
            //   Ice giant     (4 ≤ radius < 8 R⊕):   ~16h (Neptune 16.1h, Uranus 17.2h)
            //   Sub-Neptune   (radius < 4 R⊕):       ~26h  (slow primordial spin,
            //                                               little KH contraction,
            //                                               close orbits → tidal braking)
            val massE = planet.massEarth ?: 318.0 // Jupiter default
            val radiusE = planet.radiusEarth ?: 11.2
            val isNeptuneClass = classification.compositionClass == CompositionClass.NEPTUNE
            val isSubNeptune = isNeptuneClass && radiusE < 4.0 && massE < 10.0

            val baselineHours: Double
            val minHours: Float
            when {
                isSubNeptune -> { baselineHours = 26.0; minHours = 14f }
                isNeptuneClass -> { baselineHours = 16.0; minHours = 12f }
                else -> { baselineHours = 10.0; minHours = 8f }
            }

            // Scale by sqrt(R³/M) relative to Jupiter so denser bodies spin faster
            // for their class and puffier ones spin slower. Ratio is normalised so
            // a Jupiter-like (massJ=1, radiusJ=1) gives 1.0 and the class baseline
            // applies unchanged.
            val massJ = massE / 317.8
            val radiusJ = radiusE / 11.2
            val shape = if (massJ > 0) sqrt(radiusJ.pow(3) / massJ) else 1.0
            val base = baselineHours * shape

            val variation = rng.nextFloat(0.8f, 1.3f)
            return (base * variation).toFloat().coerceIn(minHours, 100f)
        }

        // Rocky: mass-based baseline with tidal braking
        val massE = planet.massEarth ?: 1.0
        val smaAU = planet.semiMajorAxisAU
            ?: classification.estimatedSemiMajorAxisAU ?: 1.0

        // Base period: P ∝ M^(-0.3). Earth-mass → ~24hr
        val basePeriod = 24.0 * massE.pow(-0.3)

        // Tidal braking for close-in planets
        val brakingFactor = if (smaAU < 0.5) {
            1.0 + (0.5 / smaAU.coerceAtLeast(0.01)).pow(2) * 0.5
        } else 1.0

        val variation = rng.nextFloat(0.7f, 1.3f)
        return (basePeriod * brakingFactor * variation).toFloat().coerceIn(4f, 10000f)
    }

    /**
     * Rotational oblateness: f ≈ (5/4) × ω²R³ / (GM)
     */
    private fun computeOblateness(
        planet: Planet,
        rotationPeriodHours: Float,
        isGiant: Boolean,
    ): Float {
        val massE = planet.massEarth ?: if (isGiant) 318.0 else 1.0
        val radiusE = planet.radiusEarth ?: if (isGiant) 11.2 else 1.0

        val massKg = massE * EARTH_MASS_KG
        val radiusM = radiusE * EARTH_RADIUS_M
        val periodS = rotationPeriodHours * 3600.0

        if (periodS <= 0 || massKg <= 0) return 0f

        val omega = 2.0 * PI / periodS
        // The (5/4)ω²R³/GM formula assumes uniform density, which overestimates for
        // centrally-concentrated gas giants by ~1.7×. Scale by 0.6 to match real planets:
        // Jupiter (10hr, 318 M⊕) → f ≈ 0.065 (actual: 0.0649). Cap at 0.10 — Saturn-level.
        val f = 0.6 * (5.0 / 4.0) * omega * omega * radiusM.pow(3) / (G * massKg)

        return f.toFloat().coerceIn(0f, 0.10f)
    }

    /**
     * Tidal elongation from Roche lobe filling factor.
     * R_roche ≈ a × 0.49q^(2/3) / (0.6q^(2/3) + ln(1 + q^(1/3)))
     * fillingFactor = R_planet / R_roche
     */
    private fun computeTidalElongation(
        planet: Planet,
        context: SystemContext,
    ): Float {
        val smaAU = planet.semiMajorAxisAU ?: return 0f
        val massE = planet.massEarth ?: return 0f
        val radiusE = planet.radiusEarth ?: return 0f
        val starMass = context.starMassSolar ?: return 0f

        val smaM = smaAU * AU_M
        val planetMassKg = massE * EARTH_MASS_KG
        val starMassKg = starMass * SOLAR_MASS_KG

        if (starMassKg <= 0) return 0f
        val q = planetMassKg / starMassKg

        val q23 = q.pow(2.0 / 3.0)
        val q13 = q.pow(1.0 / 3.0)
        val rocheRadius = smaM * 0.49 * q23 / (0.6 * q23 + ln(1.0 + q13))

        if (rocheRadius <= 0) return 0f
        val planetRadiusM = radiusE * EARTH_RADIUS_M
        val fillingFactor = planetRadiusM / rocheRadius

        // Elongation becomes noticeable above ~0.3 filling
        return if (fillingFactor > 0.3) {
            ((fillingFactor - 0.3) / 0.7).toFloat().coerceIn(0f, 0.3f)
        } else 0f
    }
}
