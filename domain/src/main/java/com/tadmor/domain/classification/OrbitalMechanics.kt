package com.tadmor.domain.classification

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Keplerian orbital mechanics utilities.
 */
object OrbitalMechanics {

    /**
     * Solve Kepler's equation M = E - e·sin(E) for eccentric anomaly E
     * using Newton-Raphson iteration.
     *
     * @param meanAnomaly Mean anomaly in radians.
     * @param eccentricity Orbital eccentricity (0 ≤ e < 1).
     * @param iterations Number of Newton-Raphson iterations (default 6).
     * @return Eccentric anomaly E in radians.
     */
    fun solveKeplerEquation(
        meanAnomaly: Double,
        eccentricity: Double,
        iterations: Int = 6,
    ): Double {
        // Normalize M to [0, 2π)
        val m = ((meanAnomaly % TWO_PI) + TWO_PI) % TWO_PI
        var e = m // Initial guess: E = M (good for low eccentricity)

        for (i in 0 until iterations) {
            val sinE = sin(e)
            val dE = (e - eccentricity * sinE - m) / (1.0 - eccentricity * cos(e))
            e -= dE
        }
        return e
    }

    /**
     * Convert eccentric anomaly to (x, y) position in the orbital plane.
     * The star (focus) is at the origin. +x points toward periapsis.
     *
     * @param semiMajorAxisAU Semi-major axis in AU.
     * @param eccentricity Orbital eccentricity.
     * @param eccentricAnomaly Eccentric anomaly E in radians.
     * @return Pair of (x, y) coordinates in AU relative to the star.
     */
    fun orbitalPosition(
        semiMajorAxisAU: Double,
        eccentricity: Double,
        eccentricAnomaly: Double,
    ): Pair<Double, Double> {
        val a = semiMajorAxisAU
        val e = eccentricity
        val b = a * sqrt(1.0 - e * e) // semi-minor axis

        // Position relative to ellipse center
        val xCenter = a * cos(eccentricAnomaly)
        val yCenter = b * sin(eccentricAnomaly)

        // Shift so focus (star) is at origin: focus is at (a*e, 0) from center
        val x = xCenter - a * e
        // Negate y so orbits are counterclockwise (prograde) when viewed from +Y in GL
        val y = -yCenter

        return x to y
    }

    /**
     * Compute mean anomaly at a given time (no epoch reference — starts at periapsis).
     *
     * @param elapsedDays Time elapsed in days.
     * @param periodDays Orbital period in days.
     * @return Mean anomaly in radians.
     */
    fun meanAnomaly(elapsedDays: Double, periodDays: Double): Double {
        return TWO_PI * elapsedDays / periodDays
    }

    /**
     * Compute mean anomaly at a given Julian Date, using an epoch reference.
     *
     * For transit midpoint epochs the conversion uses the standard NASA archive
     * convention (e.g. Winn 2010): true anomaly at transit `f_t = π/2 − ω`,
     * where ω is the argument of periastron. The eccentric anomaly at transit
     * follows from the half-angle identity
     *   E/2 = atan2(√(1−e)·sin(f/2), √(1+e)·cos(f/2))
     * and the mean anomaly at transit is `M_t = E_t − e·sin(E_t)`. This is
     * exact for any eccentricity. The earlier `M_t = π/2` shortcut was only
     * correct for circular orbits with ω = 0 — for a randomly-oriented circular
     * orbit it produced an average phase error of ~25 % of the orbital period.
     *
     * For time-of-periastron epochs the planet is at periastron (true anomaly,
     * eccentric anomaly, and mean anomaly all 0) at the epoch.
     *
     * @param currentJD Current Julian Date.
     * @param epochBJD Epoch reference time in Barycentric Julian Date.
     * @param periodDays Orbital period in days.
     * @param eccentricity Orbital eccentricity (0 ≤ e < 1).
     * @param argPeriapsisRad Argument of periastron ω in radians; only used
     *   for transit-midpoint epochs.
     * @param isTransitEpoch True if epochBJD is a transit midpoint, false if periastron.
     * @return Mean anomaly in radians.
     */
    fun meanAnomalyAtEpoch(
        currentJD: Double,
        epochBJD: Double,
        periodDays: Double,
        eccentricity: Double,
        argPeriapsisRad: Double,
        isTransitEpoch: Boolean,
    ): Double {
        val dt = currentJD - epochBJD
        val mFromEpoch = TWO_PI * dt / periodDays

        val mAtEpoch = if (isTransitEpoch) {
            val f = PI / 2.0 - argPeriapsisRad
            val eAtEpoch = 2.0 * atan2(
                sqrt(1.0 - eccentricity) * sin(f / 2.0),
                sqrt(1.0 + eccentricity) * cos(f / 2.0),
            )
            eAtEpoch - eccentricity * sin(eAtEpoch)
        } else {
            0.0
        }

        return mAtEpoch + mFromEpoch
    }

    /**
     * Current Julian Date from system clock.
     * JD = unix_seconds / 86400 + 2440587.5
     */
    fun currentJulianDate(): Double {
        return System.currentTimeMillis() / 86_400_000.0 + 2_440_587.5
    }

    /**
     * Generate points along an orbit ellipse for rendering.
     * Returns a list of (x, y) pairs in AU, star at origin.
     *
     * @param semiMajorAxisAU Semi-major axis in AU.
     * @param eccentricity Eccentricity (default 0).
     * @param segments Number of line segments (default 64).
     * @return List of (x, y) pairs tracing the orbit.
     */
    fun orbitPath(
        semiMajorAxisAU: Double,
        eccentricity: Double = 0.0,
        segments: Int = 64,
    ): List<Pair<Double, Double>> {
        val points = mutableListOf<Pair<Double, Double>>()
        for (i in 0 until segments) {
            val angle = TWO_PI * i / segments
            points.add(orbitalPosition(semiMajorAxisAU, eccentricity, angle))
        }
        return points
    }

    private const val TWO_PI = 2.0 * PI
}
