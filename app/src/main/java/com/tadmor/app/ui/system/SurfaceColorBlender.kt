package com.tadmor.app.ui.system

import com.tadmor.domain.classification.visual.ColorPalettes
import com.tadmor.domain.classification.visual.GasGiantProfile
import com.tadmor.domain.classification.visual.SurfaceComposition
import com.tadmor.domain.classification.visual.VisualProfile

/**
 * Computes a single flat surface RGB from [VisualProfile] data.
 * All planets get one solid color in Phase 7a — both rocky and gas giant.
 *
 * Rocky worlds: weighted blend of [ColorPalettes] material colors using
 * [SurfaceComposition] percentages + surface temperature.
 *
 * Gas giants: average of [GasGiantProfile.bandColors].
 */
object SurfaceColorBlender {

    data class Rgb(val r: Float, val g: Float, val b: Float)

    fun blend(profile: VisualProfile): Rgb {
        val gasGiant = profile.gasGiantProfile
        if (gasGiant != null) return blendGasGiant(gasGiant)

        val surface = profile.surfaceComposition ?: return Rgb(0.5f, 0.5f, 0.5f)
        return blendRocky(surface, profile)
    }

    private fun blendGasGiant(gas: GasGiantProfile): Rgb {
        if (gas.bandColors.isEmpty()) return Rgb(0.6f, 0.55f, 0.45f)
        var r = 0f; var g = 0f; var b = 0f
        for (c in gas.bandColors) {
            r += ((c shr 16) and 0xFF) / 255f
            g += ((c shr 8) and 0xFF) / 255f
            b += (c and 0xFF) / 255f
        }
        val n = gas.bandColors.size.toFloat()
        return Rgb(r / n, g / n, b / n)
    }

    private fun blendRocky(surface: SurfaceComposition, profile: VisualProfile): Rgb {
        val temp = profile.surfaceTemperatureK
        val seed = profile.seed
        val hasAtmosphere = profile.atmosphere.present
        val hasO2 = profile.atmosphere.o2 > 0.01f

        var r = 0f; var g = 0f; var b = 0f

        // Silicates: cool → SILICATE_LIGHT, hot → SILICATE_DARK
        if (surface.silicates > 0f) {
            val t = ((temp - 200f) / 1200f).coerceIn(0f, 1f)
            val palette = if (t < 0.5f) ColorPalettes.SILICATE_LIGHT else ColorPalettes.SILICATE_DARK
            val c = pick(palette, seed, 0)
            r += surface.silicates * ((c shr 16) and 0xFF) / 255f
            g += surface.silicates * ((c shr 8) and 0xFF) / 255f
            b += surface.silicates * (c and 0xFF) / 255f
        }

        // Iron: oxidized if atmosphere with O2, otherwise metallic
        if (surface.iron > 0f) {
            val palette = if (hasAtmosphere && hasO2) ColorPalettes.IRON_OXIDE else ColorPalettes.IRON_METALLIC
            val c = pick(palette, seed, 1)
            r += surface.iron * ((c shr 16) and 0xFF) / 255f
            g += surface.iron * ((c shr 8) and 0xFF) / 255f
            b += surface.iron * (c and 0xFF) / 255f
        }

        // Water: ice (<273K), liquid (273-373K), white vapor/steam (>373K)
        if (surface.water > 0f) {
            val c = when {
                temp < 273f -> pick(ColorPalettes.WATER_ICE, seed, 2)
                temp < 373f -> pick(ColorPalettes.WATER_LIQUID, seed, 2)
                else -> 0xFFF0F0F0.toLong()  // white steam
            }
            r += surface.water * ((c shr 16) and 0xFF) / 255f
            g += surface.water * ((c shr 8) and 0xFF) / 255f
            b += surface.water * (c and 0xFF) / 255f
        }

        // Sulfur: warm yellow (>200K), frost (<=200K)
        if (surface.sulfur > 0f) {
            val palette = if (temp > 200f) ColorPalettes.SULFUR_WARM else ColorPalettes.SULFUR_FROST
            val c = pick(palette, seed, 3)
            r += surface.sulfur * ((c shr 16) and 0xFF) / 255f
            g += surface.sulfur * ((c shr 8) and 0xFF) / 255f
            b += surface.sulfur * (c and 0xFF) / 255f
        }

        // Carbon: organic (warm), graphite (extreme heat)
        if (surface.carbon > 0f) {
            val palette = if (temp < 1500f) ColorPalettes.CARBON_ORGANIC else ColorPalettes.CARBON_GRAPHITE
            val c = pick(palette, seed, 4)
            r += surface.carbon * ((c shr 16) and 0xFF) / 255f
            g += surface.carbon * ((c shr 8) and 0xFF) / 255f
            b += surface.carbon * (c and 0xFF) / 255f
        }

        // Nitrogen ice
        if (surface.nitrogen > 0f) {
            val c = pick(ColorPalettes.NITROGEN_ICE, seed, 5)
            r += surface.nitrogen * ((c shr 16) and 0xFF) / 255f
            g += surface.nitrogen * ((c shr 8) and 0xFF) / 255f
            b += surface.nitrogen * (c and 0xFF) / 255f
        }

        // Methane ice
        if (surface.methane > 0f) {
            val c = pick(ColorPalettes.METHANE_ICE, seed, 6)
            r += surface.methane * ((c shr 16) and 0xFF) / 255f
            g += surface.methane * ((c shr 8) and 0xFF) / 255f
            b += surface.methane * (c and 0xFF) / 255f
        }

        // Ammonia ice
        if (surface.ammonia > 0f) {
            val c = pick(ColorPalettes.AMMONIA_ICE, seed, 7)
            r += surface.ammonia * ((c shr 16) and 0xFF) / 255f
            g += surface.ammonia * ((c shr 8) and 0xFF) / 255f
            b += surface.ammonia * (c and 0xFF) / 255f
        }

        // Tholins
        if (surface.tholins > 0f) {
            val c = pick(ColorPalettes.THOLIN, seed, 8)
            r += surface.tholins * ((c shr 16) and 0xFF) / 255f
            g += surface.tholins * ((c shr 8) and 0xFF) / 255f
            b += surface.tholins * (c and 0xFF) / 255f
        }

        return Rgb(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
    }

    /** Deterministic palette index selection from seed + salt. */
    private fun pick(palette: LongArray, seed: Long, salt: Int): Long {
        val index = ((seed xor (salt.toLong() * -7046029254386353131L)) ushr 32).toInt()
            .let { (it and 0x7FFFFFFF) % palette.size }
        return palette[index]
    }
}
