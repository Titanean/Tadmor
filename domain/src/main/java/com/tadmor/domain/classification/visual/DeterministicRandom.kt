package com.tadmor.domain.classification.visual

import java.util.Random

/**
 * Seeded random utility for reproducible visual choices.
 * Same seed always produces the same sequence of values.
 */
class DeterministicRandom(seed: Long) {

    private val rng = Random(seed)

    fun nextFloat(): Float = rng.nextFloat()

    fun nextFloat(min: Float, max: Float): Float =
        min + rng.nextFloat() * (max - min)

    fun nextDouble(min: Double, max: Double): Double =
        min + rng.nextDouble() * (max - min)

    fun nextInt(bound: Int): Int = rng.nextInt(bound)

    fun nextInt(min: Int, max: Int): Int =
        min + rng.nextInt(max - min + 1)

    fun nextGaussian(mean: Float, stdDev: Float): Float =
        (rng.nextGaussian() * stdDev + mean).toFloat()

    fun <T> pick(list: List<T>): T = list[rng.nextInt(list.size)]

    fun chance(probability: Float): Boolean = rng.nextFloat() < probability
}
