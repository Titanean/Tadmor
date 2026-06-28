package com.tadmor.app.ui.system

import com.tadmor.app.ui.theme.TeffColor
import com.tadmor.domain.classification.visual.ColorPalettes
import com.tadmor.domain.classification.visual.DeterministicRandom
import com.tadmor.domain.model.Star
import com.tadmor.domain.model.effectiveRadiusSolar
import com.tadmor.domain.model.effectiveSpectralType
import com.tadmor.domain.model.isPulsar
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Immutable parameter data class for [GLBridge]<StarGlobeParams>.
 * Passed from Compose to the GL thread each frame via [GLBridge].
 */
data class StarGlobeParams(
    val isVisible: Boolean = false,

    // Star geometry
    val starRadiusKm: Float = 695700f,   // default = 1 R☉

    // Star color (linear RGB from TeffColor blackbody)
    val colorR: Float = 1f,
    val colorG: Float = 0.95f,
    val colorB: Float = 0.9f,

    // Brightness
    val intensity: Float = 1f,           // emission multiplier
    val exposure: Float = 1f,            // auto-exposure for tonemapper

    // Surface character
    val limbDarkeningCoeff: Float = 0.6f,
    val noiseScale1: Float = 4f,
    val noiseScale2: Float = 8f,
    val convectionStrength: Float = 0.25f,

    // Corona
    val coronaIntensity: Float = 0.8f,
    val coronaExtent: Float = 2.0f,

    // Atmospheric limb (brown dwarfs only). Extent = 0 disables it; corona
    // is gated off in tandem for the same stars since cool T/Y dwarfs have
    // no coronal-heating mechanism and a plasma corona is physically
    // wrong. Tint is the dwarf's intrinsic atmospheric scattering colour
    // (one of the band-palette band colours); the shader multiplies it
    // by the star's own light so the limb glow is the photosphere
    // shining through that atmosphere.
    val atmosphereLimbExtent: Float = 0f,
    val atmosphereLimbColorR: Float = 1f,
    val atmosphereLimbColorG: Float = 1f,
    val atmosphereLimbColorB: Float = 1f,

    /** True for L dwarfs (Teff ≈ 1300–2400 K). Switches the procedural
     *  star surface from FBM granulation to a Voronoi cellular pattern
     *  so L dwarfs visually distinguish from M dwarfs (which share the
     *  same red colour). */
    val isLDwarf: Boolean = false,

    // Animation
    val time: Float = 0f,
    // Surface rotation rate in radians per second. Drives a real
    // rotation-around-Y of the noise sample point in star_globe.frag,
    // so the pattern visibly spins in place rather than translating in
    // noise space (which read as a radial burst toward an X pole, not
    // rotation). Default 0 — normal stars rely on the per-axis uTime
    // drift for their slow "boil" feel. Pulsars set this very high
    // (~100 rad/s ≈ 16 rotations/sec) for the millisecond-spin look.
    val spinRate: Float = 0f,

    // Brown dwarf banded surface (null for normal stars)
    val gasGiantBake: GasGiantBakeData? = null,
    val oblateness: Float = 0f,

    // Identity
    val seed: Long = 0L,

    // Reveal multiplier [0,1]. Multiplied into the shader's final colour so
    // the star fades in from black instead of popping in with stale data
    // (previous star's colour/radius briefly visible during cross-tab nav
    // before cachedDetail catches up). Driven from Compose, mirroring the
    // planet globe's reveal pattern. Default 1.0 for callers that don't
    // care about the fade.
    val revealProgress: Float = 1f,
)

/**
 * Builds [StarGlobeParams] from catalog [Star] data.
 * Color, intensity, and surface character are all Teff-driven.
 *
 * @param neutronStarRotation when false, pulsars/neutron stars render
 *   stationary instead of with their default 100 rad/s spin. User-facing
 *   setting in DISPLAY for motion-sensitive viewers.
 */
fun buildStarGlobeParams(
    star: Star,
    neutronStarRotation: Boolean = true,
): StarGlobeParams {
    // Spectral-class fallback for stars without measured Teff (e.g. L dwarfs).
    // Mirrors the resolution used by the system strip and orbital view so the
    // star color stays consistent across all three surfaces.
    val spTypeRaw = star.spectralType?.trim()?.uppercase()
    val firstClass = spTypeRaw?.firstOrNull()
    val classDefaultTeff = when (firstClass) {
        'O' -> 35000.0
        'B' -> 15000.0
        'A' -> 8500.0
        'F' -> 6700.0
        'G' -> 5600.0
        'K' -> 4400.0
        'M' -> 3200.0
        'L' -> 1900.0
        'T' -> 1000.0
        'Y' -> 500.0
        else -> 5778.0
    }
    val teff = star.teffK ?: classDefaultTeff
    // effectiveRadiusSolar() returns the canonical pulsar radius (~12 km)
    // for PSR-prefixed stars without a catalog value. Other unknown-radius
    // stars still default to 1 R☉.
    val radiusSolar = star.effectiveRadiusSolar() ?: 1.0
    val radiusKm = (radiusSolar * 695700.0).toFloat()

    // Blackbody color → linear RGB (gamma decode the sRGB from TeffColor).
    // forStar() handles WD override, Teff blackbody, and spectral-class fallback.
    // effectiveSpectralType() routes pulsars to "Q" → harsh saturated blue.
    val srgb = TeffColor.forStar(star.teffK, star.effectiveSpectralType()) ?: TeffColor.fromTeff(teff)
    val colorR = srgb.red.toDouble().pow(2.2).toFloat()
    val colorG = srgb.green.toDouble().pow(2.2).toFloat()
    val colorB = srgb.blue.toDouble().pow(2.2).toFloat()

    // Luminosity-scaled intensity
    val logL = star.logLuminosity ?: 0.0
    val intensity = (10.0.pow(logL)).toFloat().coerceIn(0.1f, 100f)
    val exposure = (2.0f / intensity).coerceIn(0.1f, 5.0f)

    // Teff-dependent surface parameters
    val limbDarkening = when {
        teff > 10000 -> 0.30f
        teff > 7500  -> 0.40f
        teff > 6000  -> 0.50f
        teff > 5200  -> 0.60f
        teff > 3700  -> 0.65f
        teff > 2400  -> 0.75f
        else         -> 0.80f
    }

    val convection = when {
        teff > 30000 -> 0.08f
        teff > 10000 -> 0.10f
        teff > 7500  -> 0.12f
        teff > 6000  -> 0.20f
        teff > 5200  -> 0.25f
        teff > 3700  -> 0.30f
        teff > 2400  -> 0.35f
        else         -> 0.12f // brown dwarfs: low (bands dominate)
    }

    // Convection cell size scales with pressure scale height H_p ∝ T·R²/M.
    // The cell-to-disk ratio H_p/R goes as R/M for fixed T, so giants show
    // a few enormous cells (Betelgeuse-style) while dwarfs show fine granulation.
    // Gentle curve + tight clamp keeps both ends visually plausible without
    // either becoming pixelated (dwarfs) or trivially blobby (giants).
    val radiusScale = (1.0 / radiusSolar.pow(0.35)).toFloat().coerceIn(0.6f, 1.6f)
    val noiseScale1 = 40.0f * radiusScale
    val noiseScale2 = 80.0f * radiusScale

    val coronaIntensity = when {
        teff > 30000 -> 0.4f
        teff > 10000 -> 0.5f
        teff > 7500  -> 0.6f
        teff > 6000  -> 0.7f
        teff > 5200  -> 0.8f
        teff > 3700  -> 0.9f
        teff > 2400  -> 1.0f
        else         -> 0.3f // brown dwarfs: faint glow
    }

    val coronaExtent = when {
        teff > 10000 -> 1.5f
        teff > 5200  -> 2.0f
        teff > 3700  -> 2.5f
        teff > 2400  -> 2.5f
        else         -> 1.5f // brown dwarfs: subtle
    }

    // White dwarf detection
    val isWhiteDwarf = spTypeRaw != null && spTypeRaw.length >= 2 &&
        spTypeRaw.startsWith("D") && spTypeRaw[1] in "ABCOZQ"

    // L dwarf detection. Same red colour temperature as M dwarfs (cooler
    // end of M overlaps the warmer end of L around 2400 K), so without a
    // distinct surface texture L dwarfs are indistinguishable from M
    // dwarfs in the globe view. L dwarfs have cooler photospheres
    // dominated by silicate/iron cloud condensation — the convective
    // pattern reads as discrete cells rather than blobby granulation,
    // which the existing FBM-only path can't reproduce. Flagging them
    // here switches the shader to a Voronoi-cell surface texture.
    // Detected by explicit "L" prefix in spectral type OR by Teff in
    // the L range (1300–2400 K) when spectral type is unknown.
    val firstClassChar = spTypeRaw?.firstOrNull()
    val isLDwarf = !isWhiteDwarf && (
        firstClassChar == 'L' ||
        (firstClassChar == null && teff in 1300.0..2400.0)
    )

    // Banded surface (gas-giant bake) only for T dwarfs and below.
    // L dwarfs fall through to the procedural star path — their photospheres
    // are still hot enough (~1500–2400K) to render plausibly as deep red stars.
    val isBandedDwarf = !isWhiteDwarf && (
        firstClass in listOf('T', 'Y') ||
        teff < 1300
    )

    // White dwarf + brown dwarf corona overrides. Brown dwarfs (T/Y at
    // Teff < 1300 K) have no coronal-heating mechanism, so the plasma
    // corona is gated off and replaced by an atmospheric-limb halo
    // (uniforms below) — see Phase 12.6 decision entry.
    val finalConvection = if (isWhiteDwarf) 0.05f else convection
    val finalLimbDarkening = if (isWhiteDwarf) 0.30f else limbDarkening
    val finalCoronaIntensity = when {
        isWhiteDwarf  -> 0.2f
        isBandedDwarf -> 0f
        else          -> coronaIntensity
    }

    val seed = star.hostname.hashCode().toLong()
    val time = (seed and 0xFFFF).toFloat() // deterministic initial appearance

    // Brown dwarf: generate gas giant bake data for banded surface +
    // atmospheric-limb tint from the same palette.
    var gasGiantBake: GasGiantBakeData? = null
    var oblateness = 0f
    var finalNoiseScale1 = noiseScale1
    var finalNoiseScale2 = noiseScale2
    var atmLimbExtent = 0f
    var atmLimbR = 1f
    var atmLimbG = 1f
    var atmLimbB = 1f

    if (isBandedDwarf) {
        val bdRender = buildBrownDwarfRender(seed)
        gasGiantBake = bdRender.bakeData
        atmLimbR = bdRender.limbR
        atmLimbG = bdRender.limbG
        atmLimbB = bdRender.limbB
        // Atmospheric scale height H = kT/(mg). BD radius sits on the
        // electron-degeneracy plateau (≈ 1 R_jupiter regardless of
        // mass), so H/R essentially scales as T/g for the visible
        // population, and gravity in turn scales with mass (M/R²
        // with R fixed). Most catalogued brown dwarfs are missing M /
        // L entries, so a Teff-driven proxy is the only practical
        // path: cooler dwarfs are typically lower mass (older and
        // closer to the planetary boundary), which means the T/g
        // ratio — and thus H/R — stays roughly conserved across the
        // BD range at ≈ 6×10⁻⁵. Visible Rayleigh/Mie halo extends
        // ~6–10 scale heights, so the apparent limb extent in star
        // radii lands at ~1 % near the L/T boundary. The 0.000012 ×
        // Teff coefficient + [0.005, 0.020] clamp gives Y dwarfs
        // (≈ 400 K) a 0.005 R★ hairline and T dwarfs at the 1300 K
        // upper boundary a 0.016 R★ rim — a fraction of the earlier
        // 0.05 prototype, matching the actual physical extent of a
        // brown-dwarf atmosphere far better.
        atmLimbExtent = (teff.toFloat() * 0.000012f).coerceIn(0.005f, 0.020f)
        // Brown dwarfs rotate fast (2–12 hours), estimate oblateness from Teff
        // Cooler = older = spun down slightly
        oblateness = when {
            teff < 700  -> 0.03f
            teff < 1300 -> 0.05f
            else        -> 0.07f
        }
        // Lower-frequency noise for shimmer on top of bands
        finalNoiseScale1 = 10.0f * radiusScale
        finalNoiseScale2 = 20.0f * radiusScale
    }

    // ── Pulsar / neutron star overrides ──
    // Catalog data is essentially absent for pulsars (no Teff, no spectral
    // type, sometimes no radius). Render them at their real ~12 km radius —
    // the globe camera bounds scale with starRadiusKm so a tiny absolute
    // size still fills the view at the same angular size as a Sun-sized
    // body.
    val isPulsar = star.isPulsar()
    var pulsarRadiusKm = radiusKm
    var pulsarColorR = colorR
    var pulsarColorG = colorG
    var pulsarColorB = colorB
    var pulsarConvection = finalConvection
    var pulsarLimb = finalLimbDarkening
    var pulsarCorona = finalCoronaIntensity
    var pulsarCoronaExtent = coronaExtent
    var pulsarOblateness = oblateness
    var pulsarIntensity = intensity
    var pulsarExposure = exposure
    var pulsarSpinRate = 0f
    if (isPulsar) {
        // Clear any brown-dwarf bake — pulsars don't get banded surfaces.
        gasGiantBake = null
        // Override color to harsh saturated blue ("Q") in linear space.
        val qColor = TeffColor.fromSpectralClass("Q")
        pulsarColorR = qColor.red.toDouble().pow(2.2).toFloat()
        pulsarColorG = qColor.green.toDouble().pow(2.2).toFloat()
        pulsarColorB = qColor.blue.toDouble().pow(2.2).toFloat()
        // Radius already set above via effectiveRadiusSolar() — pulsars
        // resolve to ~12 km when the catalog has no value, so radiusKm /
        // pulsarRadiusKm is already physical. Camera bounds in StarGlobeView
        // scale with starRadiusKm, so the body still fills the view at
        // normal angular size.
        // Bright, contrasty surface — sharp limb, low convection, intense corona.
        pulsarConvection = 0.08f
        pulsarLimb = 0.25f
        pulsarCorona = 1.6f
        pulsarCoronaExtent = 2.5f
        // Strongly oblate from millisecond-period rotation.
        pulsarOblateness = 0.18f
        // Force a luminous emission — many pulsars have no logL in the
        // catalog and would otherwise default to L = 1 (Sol). High enough
        // to dominate the bloom pipeline; exposure scaled down to keep the
        // disc readable.
        pulsarIntensity = 8f
        pulsarExposure = 0.4f
        // Rotation rate: drives a true rotation-around-Y of the noise
        // sample point in the shader (rotateY(surfPt, uSpinAngle)). At
        // 100 rad/s ≈ 16 rotations/sec, the surface visibly whirs.
        // User can disable via the Neutron star rotation setting.
        pulsarSpinRate = if (neutronStarRotation) 100f else 0f
    }

    return StarGlobeParams(
        isVisible = true,
        starRadiusKm = pulsarRadiusKm,
        colorR = pulsarColorR,
        colorG = pulsarColorG,
        colorB = pulsarColorB,
        intensity = pulsarIntensity,
        exposure = pulsarExposure,
        limbDarkeningCoeff = pulsarLimb,
        noiseScale1 = finalNoiseScale1,
        noiseScale2 = finalNoiseScale2,
        convectionStrength = pulsarConvection,
        coronaIntensity = pulsarCorona,
        coronaExtent = pulsarCoronaExtent,
        atmosphereLimbExtent = atmLimbExtent,
        atmosphereLimbColorR = atmLimbR,
        atmosphereLimbColorG = atmLimbG,
        atmosphereLimbColorB = atmLimbB,
        isLDwarf = isLDwarf,
        time = time,
        spinRate = pulsarSpinRate,
        gasGiantBake = gasGiantBake,
        oblateness = pulsarOblateness,
        seed = seed,
    )
}

/**
 * Bake data + the atmospheric-limb tint pulled from the same palette.
 * The limb tint is the brighter of the two main band colours — by
 * luminance — so the halo picks up the salmon-warm side of the
 * purple/salmon BD palette rather than the dark purple side.
 */
private data class BrownDwarfRender(
    val bakeData: GasGiantBakeData,
    val limbR: Float, val limbG: Float, val limbB: Float,
)

/**
 * Builds [GasGiantBakeData] for a brown dwarf's banded cloud surface.
 * Uses brown dwarf-specific palettes and band parameters. Also surfaces
 * the dominant band colour as the atmospheric limb tint (see
 * [BrownDwarfRender]).
 */
private fun buildBrownDwarfRender(seed: Long): BrownDwarfRender {
    val rng = DeterministicRandom(seed xor 0x42445746) // "BDWF"

    // Two-color purple/salmon palette — strict alternation for T dwarfs
    val palette = ColorPalettes.BD_T_VARIANTS[rng.nextInt(ColorPalettes.BD_T_VARIANTS.size)]

    val (c1r, c1g, c1b) = palette[0].toGlRgb()
    val (c2r, c2g, c2b) = palette[1].toGlRgb()
    val (c3r, c3g, c3b) = palette[2].toGlRgb()
    val (c4r, c4g, c4b) = palette[3].toGlRgb()
    val (c5r, c5g, c5b) = palette[4].toGlRgb()

    // Pick the brighter of the two main band colours (Rec. 709 luminance)
    // as the atmospheric-scattering tint. The BD palette pairs a dark
    // purple band with a bright salmon band; the salmon's luminance is
    // ~3-4× the purple's, so this consistently picks the salmon side —
    // visually consistent with how a real T-dwarf limb would scatter
    // the dominant photospheric chromophore band rather than the dark
    // inter-band lanes.
    val lum0 = 0.299f * c1r + 0.587f * c1g + 0.114f * c1b
    val lum1 = 0.299f * c2r + 0.587f * c2g + 0.114f * c2b
    val (limbR, limbG, limbB) = if (lum1 > lum0) {
        Triple(c2r, c2g, c2b)
    } else {
        Triple(c1r, c1g, c1b)
    }

    // Band parameters: many alternating purple/salmon bands, high contrast
    val bandCount = rng.nextFloat(8f, 13f)
    val bandBreakup = rng.nextFloat(0.3f, 0.6f)
    val bandSoftness = rng.nextFloat(0.3f, 0.5f)
    val contrast = rng.nextFloat(0.7f, 1.0f)

    // 1-2 macro storms (cloud clearings)
    val macroStorms = buildList {
        val count = rng.nextInt(1, 3)
        for (i in 0 until count) {
            val lat = rng.nextFloat(-0.4f, 0.4f)
            val lon = rng.nextFloat(0f, (2.0 * Math.PI).toFloat())
            val r = sqrt(1f - lat * lat)
            val sign = if (rng.chance(0.5f)) 1f else -1f
            add(StormData(
                x = r * cos(lon),
                y = lat,
                z = r * sin(lon),
                radius = rng.nextFloat(0.15f, 0.30f),
                strength = sign * rng.nextFloat(1.0f, 2.0f),
            ))
        }
    }

    val bake = GasGiantBakeData(
        color1R = c1r, color1G = c1g, color1B = c1b,
        color2R = c2r, color2G = c2g, color2B = c2b,
        color3R = c3r, color3G = c3g, color3B = c3b,
        color4R = c4r, color4G = c4g, color4B = c4b,
        color5R = c5r, color5G = c5g, color5B = c5b,
        bands = bandCount,
        bandBreakup = bandBreakup,
        bandSoftness = bandSoftness,
        contrast = contrast,
        microDetails = rng.nextFloat(0.3f, 0.6f),
        striations = rng.nextFloat(0.2f, 0.5f),
        turbulence = rng.nextFloat(0.3f, 0.6f),
        stormIntensity = rng.nextFloat(0.3f, 0.7f),
        poleSize = rng.nextFloat(0.05f, 0.15f),
        noiseScale = rng.nextFloat(2.5f, 3.5f),
        permSeed = (seed and 0xFFFF).toInt(),
        macroStorms = macroStorms,
        microStorms = emptyList(), // no micro storms for brown dwarfs
    )
    return BrownDwarfRender(bake, limbR, limbG, limbB)
}
