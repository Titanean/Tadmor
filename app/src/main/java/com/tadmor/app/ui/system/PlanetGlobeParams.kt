package com.tadmor.app.ui.system

import com.tadmor.domain.classification.CompositionClass
import com.tadmor.domain.classification.sanitizedForVisuals
import com.tadmor.domain.classification.visual.CloudType
import com.tadmor.domain.classification.visual.ColorPalettes
import com.tadmor.domain.classification.visual.DeterministicRandom
import com.tadmor.app.ui.theme.TeffColor
import com.tadmor.domain.model.Star
import com.tadmor.domain.model.effectiveRadiusSolar
import com.tadmor.domain.model.effectiveSpectralType
import com.tadmor.domain.model.SystemPlanetEntry
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Immutable parameter data class for [GLBridge]<PlanetGlobeParams>.
 * Passed from Compose to the GL thread each frame. Contains all shader uniforms
 * as primitive floats/arrays, ready for upload.
 */
/** Holds the most recently built planet params for the debug screen. */
object PlanetGlobeDebug {
    @Volatile
    var lastParams: PlanetGlobeParams? = null
    @Volatile
    var lastPlanetName: String = ""
}

data class PlanetGlobeParams(
    val isVisible: Boolean = false,

    // ── Planet geometry ──
    val planetRadiusKm: Float = 6371f,
    val oblateness: Float = 0f,
    val planetColorR: Float = 0.5f,
    val planetColorG: Float = 0.5f,
    val planetColorB: Float = 0.5f,

    // ── Atmosphere extent ──
    val atmosphereThicknessKm: Float = 0f,

    // ── Rayleigh scattering (pre-scaled: base * 1e-3 * densityMultiplier) ──
    val rayleighR: Float = 0f,
    val rayleighG: Float = 0f,
    val rayleighB: Float = 0f,
    val rayleighScaleHeightKm: Float = 8.5f,

    // ── Mie scattering (pre-scaled) ──
    val mieR: Float = 0f,
    val mieG: Float = 0f,
    val mieB: Float = 0f,
    val mieAbsorptionR: Float = 0f,
    val mieAbsorptionG: Float = 0f,
    val mieAbsorptionB: Float = 0f,
    val mieScaleHeightKm: Float = 1.2f,
    val miePhaseG: Float = 0.76f,
    val miePhaseG2: Float = -0.1f,
    val miePhaseBlend: Float = 0.1f,
    val mieDirtiness: Float = 0f,

    // ── Absorption band (ozone / CH4 / H2S) ──
    val ozoneR: Float = 0f,
    val ozoneG: Float = 0f,
    val ozoneB: Float = 0f,
    val ozoneCenterKm: Float = 25f,
    val ozoneWidthKm: Float = 15f,

    // ── Clouds ──
    val cloudColorR: Float = 1f,
    val cloudColorG: Float = 1f,
    val cloudColorB: Float = 1f,
    val cloudCoverage: Float = 0f,
    val cloudDensity: Float = 0f,
    val cloudAltitudeKm: Float = 0f,
    val cloudSize: Float = 1f,
    val cloudDistortion: Float = 0f,
    val cloudBumpiness: Float = 0f,
    val cloudBanding: Float = 0f,
    // Tidally locked planets get a substellar-centered "bullseye" cloud
    // pattern (clear at the substellar hotspot, dense ring at the terminator,
    // stratus on the antistellar side) instead of the standard zonal banding.
    // Drift then rotates around the substellar axis rather than the spin axis.
    val tidallyLocked: Boolean = false,

    // ── Fog ──
    val fogColorR: Float = 0.5f,
    val fogColorG: Float = 0.5f,
    val fogColorB: Float = 0.5f,
    val fogDensity: Float = 0f,
    val fogScaleHeightKm: Float = 0f,
    val fogPatchiness: Float = 0f,

    // ── Sun ──
    val sunDirX: Float = 0.866f,  // default: 30° elevation, 90° azimuth
    val sunDirY: Float = 0.5f,
    val sunDirZ: Float = 0f,
    val sunColorR: Float = 1f,
    val sunColorG: Float = 1f,
    val sunColorB: Float = 1f,
    val sunIntensity: Float = 22f,
    val sunDistanceAU: Float = 1f,
    val sunSize: Float = 1f,

    // ── Secondary sun (circumbinary planets only) ──
    // hasSecondarySun = true when [buildPlanetGlobeParams] receives a
    // companion star + a positive binaryStarSeparationAU. The shader sums
    // per-sun atmospheric in-scattering, surface Lambertian, sun disc,
    // corona, diffraction spikes, and ring shadows; when false, the
    // second-sun branches are skipped.
    val hasSecondarySun: Boolean = false,
    val sun2DirX: Float = 0f,
    val sun2DirY: Float = 0f,
    val sun2DirZ: Float = 1f,
    val sun2ColorR: Float = 1f,
    val sun2ColorG: Float = 1f,
    val sun2ColorB: Float = 1f,
    val sun2Intensity: Float = 0f,
    val sun2DistanceAU: Float = 1f,
    val sun2Size: Float = 1f,

    // ── Rendering ──
    val cameraExposure: Float = 1f,
    val time: Float = 0f,
    val cloudDriftSpeed: Float = 0f,
    // [0, 1] flat ambient lighting. The fullscreen-globe inspection
    // toggle drives this so the user can light up the night side
    // uniformly to view the whole surface; the value animates between
    // 0 and the on-target so the transition reads as a smooth fade.
    val ambientLight: Float = 0f,
    // [0, 1] atmosphere/cloud visibility — also driven by FULL-view
    // toggles. The renderer multiplies these into the relevant uniform
    // values when posting to GL: atmosphereVisibility scales Rayleigh,
    // Mie, and ozone coefficients (so all atmospheric scattering /
    // absorption fades together); cloudsVisibility scales cloud
    // coverage + density and fog density (clouds and fog are toggled
    // together since the user reads them as a single "weather" layer).
    val atmosphereVisibility: Float = 1f,
    val cloudsVisibility: Float = 1f,

    // ── Planet seed for deterministic clouds ──
    val seed: Long = 0L,

    // ── Bump mapping (0 = flat, >0 = perturb normal from texture alpha) ──
    val bumpStrength: Float = 0f,

    // ── Blackbody thermal emission (for hot surfaces >700 K) ──
    // Pre-multiplied emission colour × intensity in linear RGB.
    val thermalEmissionR: Float = 0f,
    val thermalEmissionG: Float = 0f,
    val thermalEmissionB: Float = 0f,

    // ── Water ocean specular glint (0 = matte, 1 = glassy) ──
    // Non-zero only for liquid water oceans (not ice, lava, hydrocarbon).
    val waterSpecularStrength: Float = 0f,

    // ── Lava emission gate (0 = no lava, 1 = has lava surface) ──
    // Prevents the warm-pixel emissive mask from false-glowing on normal
    // tan/rust/iron-oxide terrain that happens to pass its colour test.
    val lavaEmission: Float = 0f,

    // ── Hapke / Lommel-Seeliger reflectance blend (0 = Lambertian, 1 = full) ──
    // Drives the airless-surface lighting path in planet.frag. Set per-planet
    // CPU-side for airless dry rocky worlds — bodies with no atmosphere whose
    // visible disc should read "Moon-flat" rather than Lambert-darkened at
    // the limb. Atmospheric worlds always pass 0 (Lambert) since the
    // air-mass integral itself produces the limb darkening users expect.
    val hapkeStrength: Float = 0f,

    // ── Ring system ──
    val hasRings: Boolean = false,
    val ringInner: Float = 0f,      // inner radius in planet radii
    val ringOuter: Float = 0f,      // outer radius in planet radii
    val ringOpacity: Float = 0f,    // 0-1 overall opacity
    val ringColors: List<Triple<Float, Float, Float>> = emptyList(), // up to 5 RGB colors
    val ringGapCount: Int = 0,      // number of deterministic gaps (0-4)
    val ringDustiness: Float = 0f,  // 0 = solid Saturn bands, 1 = thin Uranus ringlets
    val ringSeed: Float = 0f,       // deterministic noise seed

    // ── Gas giant texture bake data (null for rocky planets) ──
    val gasGiantBake: GasGiantBakeData? = null,

    // ── Terrestrial texture bake data (null for gas giants) ──
    val terrestrialBake: TerrestrialBakeData? = null,

    // ── Cloud overlay bake (Venus-class opaque cloud decks). Non-null
    // only for terrestrial worlds whose cloud cover is dense enough to
    // read as a unified atmosphere disc rather than discrete weather
    // systems. The renderer bakes a second texture using the gas-giant
    // bake shader (always in swirl mode) with a palette tinted around
    // uCloudColor, then samples it in the cloud lighting block as a
    // per-pixel cloud colour modulation — thickness, bumpiness, and
    // shading still come from the existing procedural cloud field.
    val cloudOverlayBake: GasGiantBakeData? = null,

    // ── Reveal multiplier [0,1]. Multiplied into the shader's final colour
    // so the planet, atmosphere, corona, spikes, and bloom all fade in
    // together. Driven from Compose so the animation start can be delayed
    // past the page slide-in animation. Default 1.0 for callers that don't
    // care (e.g. star globe view doesn't need this). ──
    val revealProgress: Float = 1f,
)

data class StormData(
    val x: Float, val y: Float, val z: Float,
    val radius: Float, val strength: Float,
)

data class MicroStormData(
    val x: Float, val y: Float, val z: Float,
    val radius: Float, val strength: Float, val type: Int,
)

data class GasGiantBakeData(
    // 5 band colors (RGB 0-1)
    val color1R: Float, val color1G: Float, val color1B: Float,
    val color2R: Float, val color2G: Float, val color2B: Float,
    val color3R: Float, val color3G: Float, val color3B: Float,
    val color4R: Float, val color4G: Float, val color4B: Float,
    val color5R: Float, val color5G: Float, val color5B: Float,
    // When true, the bake shader skips the latitudinal sin-wave band
    // pattern and renders a domain-warped FBM cloud field instead —
    // pure-fluid swirl look for Class IV/V hot Jupiters and select
    // ice giants. Other band params still influence the curl-noise
    // advection that produces the underlying vortex structure.
    val unbanded: Boolean = false,
    // Venus-style three-jet chevron mode. Only takes effect when
    // `unbanded = true` — adds three latitude-aligned zonal jets
    // (equatorial + two mid-latitude) to the advection loop. The
    // differential rotation shears the swirl noise into the Y/chevron
    // pattern Venus's UV imagery shows. Set by `buildCloudOverlayBakeData`
    // for the Venus-class cloud-overlay path; gas-giant swirl giants
    // leave it false to keep their isotropic vortex look.
    val chevronJets: Boolean = false,
    // Band structure
    val bands: Float,        // direct band count (2-10)
    val bandBreakup: Float,
    val bandSoftness: Float,
    val contrast: Float,
    // Detail
    val microDetails: Float,
    val striations: Float,
    val turbulence: Float,
    // Storms
    val stormIntensity: Float,
    val poleSize: Float,
    val noiseScale: Float,
    // Permutation seed
    val permSeed: Int,
    // Storm arrays
    val macroStorms: List<StormData>,
    val microStorms: List<MicroStormData>,
)

data class CraterData(
    val x: Float, val y: Float, val z: Float,
    val radius: Float,
    val depth: Float,       // [0,1] — 0=shallow/old, 1=deep/fresh
    val degradation: Float, // [0,1] — from CraterProfile.degradation
)

data class TerrestrialBakeData(
    // 6-stop terrain elevation palette (linear RGB 0-1, composition-derived CPU-side)
    // Fixed elevation thresholds: 0.00, 0.30, 0.50, 0.75, 0.95, 1.00
    val t0R: Float, val t0G: Float, val t0B: Float,   // deep basins  (v=0.00)
    val t1R: Float, val t1G: Float, val t1B: Float,   // lowlands     (v=0.30)
    val t2R: Float, val t2G: Float, val t2B: Float,   // mid plains   (v=0.50)
    val t3R: Float, val t3G: Float, val t3B: Float,   // highlands    (v=0.75)
    val t4R: Float, val t4G: Float, val t4B: Float,   // peaks        (v=0.95)
    val t5R: Float, val t5G: Float, val t5B: Float,   // summits      (v=1.00)
    // Ocean / ice / lava / hydrocarbon
    val colorWaterR: Float, val colorWaterG: Float, val colorWaterB: Float,
    /** True for frozen seas — bake keeps some depth-driven darkening to suggest cracks/ridges/topology. */
    val waterIsIce: Boolean,
    // Polar cap material
    val colorPolarR: Float, val colorPolarG: Float, val colorPolarB: Float,
    // Composition weights (for spatial shader variation)
    val waterFraction: Float,
    val carbonFraction: Float,
    val tholinFraction: Float,
    // Surface context
    val seaLevel: Float,
    val polarCap: Float,
    val roughness: Float,
    val noiseScale: Float,          // planet-radius-derived [0.8, 4.0]
    val tidallyLocked: Boolean,
    val subSolarX: Float, val subSolarY: Float, val subSolarZ: Float,
    val craterDensityField: Float,
    val volcanism: Float,           // [0,1] — hotspots only activate when >0.7
    /** True for genuine lava worlds (surface T > 900 K) — hotspot paint
     *  uses the full red/orange/yellow gradient. False for volcanic
     *  worlds with cooler surfaces (Io-class, Venus-class volcanic but
     *  not molten) — hotspots render as gray-black cooled basalt with
     *  a thin glowing rim instead, since the local material would have
     *  cooled even though the planet's still volcanically active. */
    val moltenSurface: Boolean,
    val waterSpecular: Float,       // [0,1] — lifted to PlanetGlobeParams.waterSpecularStrength
    // Permutation seed
    val permSeed: Int,
    // Large craters (CPU-generated, max 30)
    val craters: List<CraterData>,
)

/** Convert ARGB Long to GL-ready RGB floats. */
fun Long.toGlRgb(): Triple<Float, Float, Float> = Triple(
    ((this shr 16) and 0xFF) / 255f,
    ((this shr 8) and 0xFF) / 255f,
    (this and 0xFF) / 255f,
)

/**
 * Builds [PlanetGlobeParams] from domain data.
 * Pre-scales scattering coefficients by `1e-3 * densityMultiplier`.
 *
 * For circumbinary planets pass [companionStar] and [binaryStarSeparationAU]
 * — the planet's globe will then render BOTH stars at their SMA-derived
 * static angular separation around the barycenter direction. With either
 * argument null/zero, the secondary-sun pipeline is skipped (single-star
 * default).
 */
fun buildPlanetGlobeParams(
    entry: SystemPlanetEntry,
    star: Star,
    companionStar: Star? = null,
    binaryStarSeparationAU: Double = 0.0,
): PlanetGlobeParams {
    timber.log.Timber.i("buildPlanetGlobeParams: ${entry.planet.name} " +
        "class=${entry.classification.compositionClass} temp=${entry.visualProfile.surfaceTemperatureK}K")
    // Substitute physically-plausible mass / radius for any catalog
    // limit-flagged parameters. The downstream renderer (`uPlanetRadius`,
    // camera bounds, mass-driven volcanism / activity bias) sees the
    // substituted values too — same convention the classifier and visual
    // profile engine use. UI-side display still reads `entry.planet`
    // directly so users see "< 35 M⊕" with the limit prefix.
    val planet = entry.planet.sanitizedForVisuals()
    val profile = entry.visualProfile
    val optics = profile.atmosphereOptics

    // ── Planet radius in km ──
    val isGiant = entry.classification.compositionClass != CompositionClass.TERRA
    val radiusEarth = planet.radiusEarth
        ?: if (isGiant) 11.2 else estimateRadiusFromMass(planet.massEarth, isGiant)
    val planetRadiusKm = (radiusEarth * 6371.0).toFloat()

    // ── Surface color ──
    val surfaceColor = SurfaceColorBlender.blend(profile)

    // ── Gas giant / terrestrial bake data (sun direction injected later) ──
    val gasGiantBake = try {
        buildGasGiantBakeData(profile)
    } catch (t: Throwable) {
        timber.log.Timber.e(t, "buildGasGiantBakeData failed for ${entry.planet.name}")
        throw t
    }
    val terrestrialBakeRaw = try {
        if (gasGiantBake == null) buildTerrestrialBakeData(profile, entry, planetRadiusKm) else null
    } catch (t: Throwable) {
        timber.log.Timber.e(t, "buildTerrestrialBakeData failed for ${entry.planet.name}")
        throw t
    }
    // Bump strength is a pure function of planet size — smaller worlds look craggier,
    // larger worlds smoother. Linear in radiusEarth, clamped to a sane range.
    val bumpStrength = if (terrestrialBakeRaw != null) {
        (2.0 - radiusEarth).coerceIn(0.3, 2.0).toFloat() * 0.8f
    } else 0f

    // ── Blackbody thermal emission for hot rocky surfaces ──
    // Silicate rock begins glowing visibly around 700 K (dim deep red). Output
    // rises sharply via T^4 (Stefan-Boltzmann) until saturation around 2500 K.
    // Gas giants skip this — their "surface" is a radiating cloud top and
    // already embedded in the equilibrium temperature model.
    val thermalRGB = if (terrestrialBakeRaw != null) {
        computeThermalEmission(profile.surfaceTemperatureK.toFloat())
    } else floatArrayOf(0f, 0f, 0f)

    // ── Lava emission gate ──
    // Only planets that actually have lava (molten surface or volcanic hotspots)
    // get the emissive-warm-pixel treatment in planet.frag. Without this gate,
    // normal warm-toned terrain (desert tans, iron-oxide reds) would falsely
    // glow after being scaled by 9× and pushed past tonemap saturation.
    val lavaEmission = if (terrestrialBakeRaw != null &&
        (profile.surfaceTemperatureK > 900f || profile.volcanicActivity > 0.70f)
    ) 1f else 0f

    // ── Hapke / Lommel-Seeliger strength for airless regolith surfaces ──
    // Activate the "Moon look" only when ALL of:
    //   • rocky (terrestrial bake present)
    //   • no atmosphere (atmosphereThicknessKm <= 0)
    //   • not molten (< 900 K, so the warm-pixel emission path isn't fighting
    //     a flat diffuse term that ignores its limb cue)
    //   • dry surface (water + tholin + carbon ices below 25 %) — the
    //     coherent-back-scatter physics that motivates Lommel-Seeliger
    //     describes loose regolith, not ice sheets or hydrocarbon seas
    //   • cratered enough to read as regolith (crater density ≥ 0.25)
    // Crater density is the strongest single signal that the surface is
    // ancient unweathered powder — exactly the population (Mercury, Moon,
    // Callisto-class) that needs the flat-disc treatment. Scaled by crater
    // density so heavily-cratered worlds (Mercury/Moon ~ 1.0) reach full
    // Hapke, moderately-cratered ones blend smoothly into Lambertian.
    val surfComp = profile.surfaceComposition
    val iceFrac = (surfComp?.water ?: 0f) +
                  (surfComp?.methane ?: 0f) +
                  (surfComp?.tholins ?: 0f)
    val hapkeStrength = if (
        terrestrialBakeRaw != null &&
        optics.atmosphereThicknessKm <= 0f &&
        profile.surfaceTemperatureK < 900f &&
        iceFrac < 0.25f &&
        profile.craterProfile.density >= 0.25f
    ) {
        ((profile.craterProfile.density - 0.25f) / 0.55f).coerceIn(0f, 1f)
    } else 0f

    // ── Pre-scale scattering coefficients ──
    val scale = 1e-3f * optics.densityMultiplier

    // ── Star illumination (independent of atmosphere — airless worlds still receive starlight) ──
    val smaAU = planet.semiMajorAxisAU
        ?: entry.classification.estimatedSemiMajorAxisAU
        ?: 1.0
    val starLum = if (star.logLuminosity != null) {
        10.0.pow(star.logLuminosity!!)
    } else if (star.radiusSolar != null && star.teffK != null) {
        star.radiusSolar!! * star.radiusSolar!! * (star.teffK!! / 5778.0).pow(4.0)
    } else {
        1.0
    }
    val sunIntensity = ((starLum / maxOf(smaAU * smaAU, 0.0001)) * 40.0).toFloat()
    val sunDistanceAU = maxOf(smaAU, 0.01).toFloat()

    // ── Star tint → sun color (blackbody with saturation boost) ──
    // effectiveSpectralType() routes pulsars to "Q" → harsh saturated blue
    // so planets around pulsars (Lich, PSR B1620-26 b) light up cold-blue
    // rather than reading as Sol-lit.
    val (sunR, sunG, sunB) = blackbodySunColor(star.teffK, star.effectiveSpectralType())

    // Auto-exposure is computed AFTER the binary placement block below
    // because it needs the secondary sun's intensity for combined-flux
    // calibration. See `combinedSunIntensity` further down.

    // ── Cloud color ──
    val (cloudR, cloudG, cloudB) = if (optics.cloudColor != 0L) {
        optics.cloudColor.toGlRgb()
    } else {
        Triple(1f, 1f, 1f)
    }

    // ── Cloud overlay bake (Venus-class opaque cloud deck) ──
    // Activated for terrestrial worlds whose cloud cover is dense enough
    // that the surface is never visible through gaps — at that point the
    // procedural cloud noise (good for Earth-class discrete weather
    // systems) reads as a uniform monochrome smear, and the planet wants
    // the swirling banded-flow patterns Venus's UV imagery shows. Gate
    // requires ≥ 0.85 coverage AND a non-trivial atmosphere (> 50 km
    // effective thickness — the regime where opaque cloud decks form).
    // Below either threshold the procedural cloud field is visually
    // correct and the overlay would just impose foreign structure on a
    // thin discrete-cloud planet.
    val cloudOverlayBake = if (
        terrestrialBakeRaw != null &&
        optics.cloudCoverage >= 0.95f &&
        optics.atmosphereThicknessKm > 50f
    ) {
        buildCloudOverlayBakeData(profile, cloudR, cloudG, cloudB)
    } else null

    // ── Fog color ──
    // Dust fog (arid worlds, no tholins, low volcanism) is an average of the terrain
    // palette, so airborne dust matches the ground it was lifted from. Other fog types
    // (water, volcanic, tholin) keep their characteristic tints from the optics deriver.
    val surface = profile.surfaceComposition
    val isDustFog = terrestrialBakeRaw != null &&
        surface != null &&
        surface.water < 0.1f &&
        surface.tholins < 0.02f &&
        profile.volcanicActivity < 0.3f &&
        optics.fogDensity > 0f
    val (fogR, fogG, fogB) = when {
        isDustFog -> {
            val b = terrestrialBakeRaw!!
            Triple(
                (b.t0R + b.t1R + b.t2R + b.t3R + b.t4R + b.t5R) / 6f,
                (b.t0G + b.t1G + b.t2G + b.t3G + b.t4G + b.t5G) / 6f,
                (b.t0B + b.t1B + b.t2B + b.t3B + b.t4B + b.t5B) / 6f,
            )
        }
        optics.fogColor != 0L -> optics.fogColor.toGlRgb()
        else -> Triple(0.5f, 0.5f, 0.5f)
    }

    // ── Deterministic time from seed ──
    val deterministicTime = (profile.seed and 0xFFFFF).toFloat()

    // ── Cloud/fog drift speed ──
    // Fixed rate matching the PoC's uTime * 0.02 rotation. The PoC also
    // re-bakes clouds every frame with domain warp (uTime * 0.015 etc.)
    // which adds shape morphing on top of rotation. Since we only rotate
    // the pre-baked texture, 0.04 compensates for the missing morph to
    // give roughly equivalent perceived motion.
    val cloudDrift = 0.04f

    // ── Sun radius in solar radii (shader computes angular size from this + distance) ──
    // effectiveRadiusSolar() returns the pulsar canonical 12 km / R☉ for
    // PSR-prefixed hosts that don't have a catalog radius — a planet around
    // Lich/PSR B1620-26 then sees a near-pointlike sun in the sky rather
    // than a Sol-sized disc.
    val sunSize = (star.effectiveRadiusSolar() ?: 1.0).toFloat()

    // ── Sun direction from axial tilt ──
    // Axial tilt determines how the terminator crosses the globe.
    // A deterministic "season" phase from the seed picks a point in the orbit,
    // so each planet shows a unique illumination angle. In single-star
    // systems this is the star's direction; in binary systems it's the
    // *barycenter* direction, with the two stars sitting symmetrically
    // around it at the SMA-derived angular separation (computed below).
    val tiltRad = Math.toRadians(profile.axialTilt.toDouble())
    val seasonPhase = ((profile.seed xor 0x7A3B9E1F) and 0xFFFF).toDouble() / 0xFFFF * 2.0 * Math.PI
    val sunElevation = tiltRad * sin(seasonPhase)
    val sunAzimuth = Math.toRadians(90.0)  // lit from the right

    // ── Binary star placement (circumbinary planets only) ──
    // Angular separation seen from the planet. The instantaneous
    // separation `a_b / a_p` is the maximum — when the binary axis is
    // perpendicular to the line of sight. Averaged across the binary
    // orbital phase for an edge-on view (the geometry of every transit-
    // discovered circumbinary in NASA's catalogue), the time-averaged
    // projected separation is `(2/π) × a_b / a_p`, since the projected
    // distance varies as `a_b × |sin(φ)|` over a circular orbit and
    // `⟨|sin(φ)|⟩ = 2/π`. Using that mean gives a representative "this is
    // what you'd usually see" pose instead of the extreme. Split the
    // barycenter direction symmetrically in azimuth so the two stars sit
    // ±θ/2 either side, sharing the season-driven elevation. Both stars
    // are at approximately a_p distance from the planet (a_b << a_p), so
    // 1/d² intensity uses the planet SMA — only the angular placement,
    // colour, and luminosity-driven brightness differ between them.
    val hasSecondary = companionStar != null && binaryStarSeparationAU > 0.0
    val angSepRad = if (hasSecondary) {
        val maxSep = binaryStarSeparationAU / smaAU
        (maxSep * (2.0 / Math.PI)).coerceIn(0.0, Math.PI * 0.5)
    } else 0.0
    val halfSep = angSepRad * 0.5
    val primaryAz = sunAzimuth - halfSep
    val secondaryAz = sunAzimuth + halfSep
    val sunDirX = (cos(sunElevation) * sin(primaryAz)).toFloat()
    val sunDirY = sin(sunElevation).toFloat()
    val sunDirZ = (cos(sunElevation) * cos(primaryAz)).toFloat()

    // Secondary star parameters: derived from the companion's own
    // luminosity / radius / Teff (or fallbacks matching the primary path).
    val (sun2R, sun2G, sun2B, sun2Intensity, sun2Size) = if (hasSecondary) {
        val companion = companionStar!!
        val companionLum = if (companion.logLuminosity != null) {
            10.0.pow(companion.logLuminosity!!)
        } else if (companion.radiusSolar != null && companion.teffK != null) {
            companion.radiusSolar!! * companion.radiusSolar!! * (companion.teffK!! / 5778.0).pow(4.0)
        } else {
            1.0
        }
        val i2 = ((companionLum / maxOf(smaAU * smaAU, 0.0001)) * 40.0).toFloat()
        val (r, g, b) = blackbodySunColor(companion.teffK, companion.effectiveSpectralType())
        val size = (companion.effectiveRadiusSolar() ?: 1.0).toFloat()
        SecondarySunSpec(r, g, b, i2, size)
    } else {
        SecondarySunSpec(1f, 1f, 1f, 0f, 1f)
    }
    val sun2DirX = if (hasSecondary) (cos(sunElevation) * sin(secondaryAz)).toFloat() else 0f
    val sun2DirY = if (hasSecondary) sin(sunElevation).toFloat() else 0f
    val sun2DirZ = if (hasSecondary) (cos(sunElevation) * cos(secondaryAz)).toFloat() else 1f

    // ── Auto-exposure: inverse of *combined* sun intensity, so a binary
    // system's brighter combined flux doesn't blow out the auto-exposure
    // calibration that was tuned for single-star systems. ──
    val combinedSunIntensity = sunIntensity + (if (hasSecondary) sun2Intensity else 0f)
    val exposure = 20f / maxOf(combinedSunIntensity, 0.001f)

    // Inject sun direction into terrestrial bake data (used for eyeball
    // tidal pole placement). Tidal locking on circumbinary planets locks
    // to the barycenter, not either individual star — the bake keeps the
    // primary direction as its single-axis input. For multi-star systems
    // the visual placement of the ice cap follows the brighter star's
    // direction by virtue of the season-driven elevation being shared.
    val terrestrialBake = terrestrialBakeRaw?.copy(
        subSolarX = sunDirX, subSolarY = sunDirY, subSolarZ = sunDirZ,
    )

    // ── Ring system from visual profile ──
    val ring = profile.ringProfile
    val ringColors = ring?.colors
        ?.take(5)
        ?.map { it.toGlRgb() }
        ?: emptyList()
    val ringSeed = ((profile.seed xor 0x52494E47L) and 0xFFFF).toFloat() / 0xFFFF.toFloat()

    val result = PlanetGlobeParams(
        isVisible = true,
        planetRadiusKm = planetRadiusKm,
        oblateness = profile.oblateness,
        planetColorR = surfaceColor.r,
        planetColorG = surfaceColor.g,
        planetColorB = surfaceColor.b,
        atmosphereThicknessKm = optics.atmosphereThicknessKm,
        rayleighR = optics.rayleighR * scale,
        rayleighG = optics.rayleighG * scale,
        rayleighB = optics.rayleighB * scale,
        rayleighScaleHeightKm = optics.rayleighScaleHeightKm,
        mieR = optics.mieR * scale,
        mieG = optics.mieG * scale,
        mieB = optics.mieB * scale,
        mieAbsorptionR = optics.mieAbsorptionR * scale,
        mieAbsorptionG = optics.mieAbsorptionG * scale,
        mieAbsorptionB = optics.mieAbsorptionB * scale,
        mieScaleHeightKm = optics.mieScaleHeightKm,
        miePhaseG = optics.miePhaseG,
        miePhaseG2 = optics.miePhaseG2,
        miePhaseBlend = optics.miePhaseBlend,
        mieDirtiness = optics.mieDirtiness,
        ozoneR = optics.ozoneR * scale,
        ozoneG = optics.ozoneG * scale,
        ozoneB = optics.ozoneB * scale,
        ozoneCenterKm = optics.ozoneCenterKm,
        ozoneWidthKm = optics.ozoneWidthKm,
        cloudColorR = cloudR,
        cloudColorG = cloudG,
        cloudColorB = cloudB,
        cloudCoverage = optics.cloudCoverage,
        cloudDensity = optics.cloudDensity,
        cloudAltitudeKm = optics.cloudAltitudeKm,
        cloudSize = optics.cloudSize,
        cloudDistortion = optics.cloudDistortion,
        // Cap cloud bumpiness on terrestrial worlds. The deriver allows up
        // to 0.2 for CO₂-ice clouds (Mars-like) and 0.12 for water clouds,
        // but at those levels the cloud bump-mapping reads as exaggerated
        // 3-D mountain ranges floating above the surface rather than a
        // weather layer. 0.06 keeps the texture's relief visible without
        // letting it dominate — Earth's preset already sits around 0.05.
        cloudBumpiness = optics.cloudBumpiness.coerceAtMost(0.06f),
        cloudBanding = optics.cloudBanding,
        fogColorR = fogR,
        fogColorG = fogG,
        fogColorB = fogB,
        fogDensity = optics.fogDensity,
        fogScaleHeightKm = optics.fogScaleHeightKm,
        fogPatchiness = optics.fogPatchiness,
        sunDirX = sunDirX,
        sunDirY = sunDirY,
        sunDirZ = sunDirZ,
        sunColorR = sunR,
        sunColorG = sunG,
        sunColorB = sunB,
        sunIntensity = sunIntensity,
        sunDistanceAU = sunDistanceAU,
        sunSize = sunSize,
        hasSecondarySun = hasSecondary,
        sun2DirX = sun2DirX,
        sun2DirY = sun2DirY,
        sun2DirZ = sun2DirZ,
        sun2ColorR = sun2R,
        sun2ColorG = sun2G,
        sun2ColorB = sun2B,
        sun2Intensity = sun2Intensity,
        sun2DistanceAU = sunDistanceAU,  // approximately equal (a_b << a_p)
        sun2Size = sun2Size,
        cameraExposure = exposure,
        time = deterministicTime,
        cloudDriftSpeed = cloudDrift,
        seed = profile.seed,
        bumpStrength = bumpStrength,
        thermalEmissionR = thermalRGB[0],
        thermalEmissionG = thermalRGB[1],
        thermalEmissionB = thermalRGB[2],
        waterSpecularStrength = terrestrialBake?.waterSpecular ?: 0f,
        // Substellar-aligned bullseye cloud pattern is a wet-convection
        // feature: rising H₂O vapour at the substellar hotspot, condensing
        // into a cyclonic deck and outflow toward the terminator. It only
        // makes sense for water clouds. Every other condensate has its own
        // circulation regime — silicate decks form night-side after day-
        // side mineral vapour cools (Kite et al.), Venus's H₂SO₄ deck rides
        // a global super-rotating jet, dry-greenhouse haze follows zonal
        // bands, Titan-CH₄ and NH₃ are non-water chemistries entirely.
        // Strict gate: only [CloudType.WATER] gets the bullseye, regardless
        // of tidal lock state. Steam decks (post-runaway H₂O at T > 380 K)
        // are explicitly NOT classified as WATER for this purpose — at
        // those temperatures circulation is already in the runaway regime.
        tidallyLocked = profile.tidallyLocked &&
            optics.cloudType == CloudType.WATER,
        lavaEmission = lavaEmission,
        hapkeStrength = hapkeStrength,
        hasRings = ring != null,
        ringInner = ring?.innerRadius ?: 0f,
        ringOuter = ring?.outerRadius ?: 0f,
        ringOpacity = ring?.opacity ?: 0f,
        ringColors = ringColors,
        ringGapCount = ring?.gapCount?.coerceIn(0, 4) ?: 0,
        ringDustiness = ring?.dustiness ?: 0f,
        ringSeed = ringSeed,
        gasGiantBake = gasGiantBake,
        terrestrialBake = terrestrialBake,
        cloudOverlayBake = cloudOverlayBake,
    )
    PlanetGlobeDebug.lastParams = result
    PlanetGlobeDebug.lastPlanetName = planet.name
    return result
}

/**
 * Tuple of resolved secondary-star parameters (RGB color × intensity × size).
 * Returned by the binary-system branch of [buildPlanetGlobeParams] so the
 * downstream constructor call has all five values inline. Single-star call
 * sites never construct this — they short-circuit on `hasSecondary`.
 */
private data class SecondarySunSpec(
    val r: Float, val g: Float, val b: Float,
    val intensity: Float,
    val size: Float,
)

/**
 * Blackbody sun color for globe illumination, with saturation boost.
 * Uses Tanner Helland approximation (via [TeffColor]) then raises non-dominant
 * channel ratios to a power > 1, deepening hue for cool stars.
 * At 2566 K (TRAPPIST-1) this produces ≈ (1.0, 0.58, 0.24) — close to
 * the atmosphere.html preset's #ff8943 (1.0, 0.54, 0.26).
 *
 * Resolution mirrors the star map / orbital / system strip path: spectral
 * type takes precedence for white dwarfs (always pale blue-white regardless
 * of Teff), then Teff blackbody, then a spectral-class fallback. Using only
 * `fromTeff(teff ?: 5778)` previously meant WDs without measured Teff (e.g.
 * WD 1856+534) defaulted to Sol's yellow on the planet globe while every
 * other surface rendered them blue.
 */
private fun blackbodySunColor(
    teffK: Double?,
    spectralType: String?,
): Triple<Float, Float, Float> {
    val color = TeffColor.forStar(teffK, spectralType) ?: TeffColor.fromTeff(5778.0)
    val r = color.red
    val g = color.green
    val b = color.blue
    val maxC = maxOf(r, g, b)
    if (maxC <= 0f) return Triple(1f, 1f, 1f)
    val boost = 1.2f
    return Triple(
        (r / maxC).pow(boost) * maxC,
        (g / maxC).pow(boost) * maxC,
        (b / maxC).pow(boost) * maxC,
    )
}

/**
 * Builds a [GasGiantBakeData] configured for use as a Venus-style
 * **cloud overlay** texture on a terrestrial world. The renderer feeds
 * this through the same `gas_giant_bake.frag` pipeline as actual gas
 * giants but binds the result to a separate texture and samples it as a
 * per-pixel cloud-colour modulation in the cloud lighting block.
 *
 * Configuration vs the gas-giant path:
 *   • Always swirl (`unbanded = true`) — Venus's UV imagery is dominated
 *     by curvy chevron flow patterns, not the discrete latitudinal jet
 *     bands a banded gas giant shows.
 *   • Palette derived from the planet's own [uCloudColor] (passed as
 *     RGB), with brightness variations only — keeps the overall cloud
 *     hue intact while letting the swirl mode paint subtle light/dark
 *     bands across the deck. Five entries mirror the gas-giant slots:
 *     `color1` (bright band A), `color2` (band B), `color3` (dark
 *     accent for shadowed regions), `color4` (pole tint), `color5`
 *     (storm centre tint).
 *   • Modest contrast / turbulence — clouds read softer than gas-giant
 *     atmospheres; pushing these too high produces hard-edged features
 *     that fight the cloud field's own bumpiness shading.
 *   • No storms — cloud decks don't have Great Red Spot-class
 *     persistent vortices. Empty storm lists short-circuit the storm
 *     loops in the bake shader.
 */
private fun buildCloudOverlayBakeData(
    profile: com.tadmor.domain.classification.visual.VisualProfile,
    cloudR: Float,
    cloudG: Float,
    cloudB: Float,
): GasGiantBakeData {
    // Brightness-only palette around the base cloud colour. Multiplying
    // each entry by a different scalar keeps the cloud's hue stable
    // (so a yellow Venus stays yellow, a green methane haze stays green)
    // while giving the swirl bake's colour map enough range to paint
    // visible light/dark structure across the deck.
    fun tint(scale: Float) = Triple(
        (cloudR * scale).coerceIn(0f, 1f),
        (cloudG * scale).coerceIn(0f, 1f),
        (cloudB * scale).coerceIn(0f, 1f),
    )
    val (c1r, c1g, c1b) = tint(1.10f)  // bright band A
    val (c2r, c2g, c2b) = tint(0.82f)  // band B
    val (c3r, c3g, c3b) = tint(0.55f)  // dark accent
    val (c4r, c4g, c4b) = tint(0.92f)  // pole
    val (c5r, c5g, c5b) = tint(1.05f)  // storm

    return GasGiantBakeData(
        unbanded = true,
        chevronJets = true,
        color1R = c1r, color1G = c1g, color1B = c1b,
        color2R = c2r, color2G = c2g, color2B = c2b,
        color3R = c3r, color3G = c3g, color3B = c3b,
        color4R = c4r, color4G = c4g, color4B = c4b,
        color5R = c5r, color5G = c5g, color5B = c5b,
        // Band structure params don't drive layout in swirl mode but
        // still feed the curl-noise advection, so values here shape the
        // underlying flow texture.
        bands = 3f,
        bandBreakup = 0.6f,
        bandSoftness = 0.85f,
        contrast = 0.55f,
        microDetails = 0.45f,
        striations = 0f,           // gated to 0 by the bake shader in swirl mode anyway
        turbulence = 0.7f,
        stormIntensity = 0f,
        poleSize = 0.0f,
        noiseScale = 2.6f,
        permSeed = (profile.seed and 0xFFFF).toInt() xor 0x434C4F44, // "CLOD" salt
        macroStorms = emptyList(),
        microStorms = emptyList(),
    )
}

/**
 * Builds [GasGiantBakeData] from a [GasGiantProfile].
 * Storm positions are generated on CPU using deterministic random,
 * then passed as uniform arrays to the GPU bake shader.
 */
private fun buildGasGiantBakeData(
    profile: com.tadmor.domain.classification.visual.VisualProfile,
): GasGiantBakeData? {
    val gg = profile.gasGiantProfile ?: return null
    val colors = gg.bandColors
    if (colors.size < 4) return null

    // 5 band colors: [0]=bandA, [1]=bandB, [2]=dark accent, [3]=storm, poleColor=polar
    val (c1r, c1g, c1b) = colors[0].toGlRgb()
    val (c2r, c2g, c2b) = colors[1].toGlRgb()
    val (c3r, c3g, c3b) = colors[2].toGlRgb()
    val (c4r, c4g, c4b) = gg.poleColor.toGlRgb()
    val (c5r, c5g, c5b) = colors[3].toGlRgb()

    // Generate storms deterministically
    val rng = DeterministicRandom(profile.seed xor 0x47415347) // "GASG"
    val macroStorms = generateMacroStorms(rng, gg.stormIntensity)
    val microStorms = generateMicroStorms(rng, gg.stormIntensity)

    return GasGiantBakeData(
        unbanded = gg.unbanded,
        chevronJets = gg.chevronJets,
        color1R = c1r, color1G = c1g, color1B = c1b,
        color2R = c2r, color2G = c2g, color2B = c2b,
        color3R = c3r, color3G = c3g, color3B = c3b,
        color4R = c4r, color4G = c4g, color4B = c4b,
        color5R = c5r, color5G = c5g, color5B = c5b,
        bands = gg.bandCount,
        bandBreakup = gg.bandBreakup,
        bandSoftness = gg.bandSoftness,
        contrast = gg.contrast,
        microDetails = gg.microDetail,
        striations = gg.striations,
        turbulence = gg.turbulence,
        stormIntensity = gg.stormIntensity,
        poleSize = gg.poleFraction,
        noiseScale = gg.noiseScale,
        permSeed = (profile.seed and 0xFFFF).toInt(),
        macroStorms = macroStorms,
        microStorms = microStorms,
    )
}

private fun generateMacroStorms(rng: DeterministicRandom, stormIntensity: Float): List<StormData> {
    if (stormIntensity < 0.1f) return emptyList()
    val count = rng.nextInt(1, 3)
    return List(count) {
        val lat = rng.nextFloat(-0.4f, 0.4f)
        val lon = rng.nextFloat(0f, (2.0 * Math.PI).toFloat())
        val r = sqrt(1f - lat * lat)
        val sign = if (rng.chance(0.5f)) 1f else -1f
        StormData(
            x = r * cos(lon),
            y = lat,
            z = r * sin(lon),
            radius = rng.nextFloat(0.15f, 0.30f),
            strength = sign * rng.nextFloat(1.0f, 2.5f),
        )
    }
}

private fun generateMicroStorms(rng: DeterministicRandom, stormIntensity: Float): List<MicroStormData> {
    if (stormIntensity < 0.1f) return emptyList()
    val storms = mutableListOf<MicroStormData>()
    val numBands = rng.nextInt(2, 4)
    for (b in 0 until numBands) {
        val bandLat = rng.nextFloat(-0.75f, 0.75f)
        val isPearl = rng.chance(0.5f)
        val type = if (isPearl) 1 else 0
        val sign = if (rng.chance(0.5f)) 1f else -1f
        val strengthBase = sign * rng.nextFloat(1.0f, 3.0f)

        if (isPearl) {
            val numPearls = rng.nextInt(5, 12)
            val startLon = rng.nextFloat(0f, (2.0 * Math.PI).toFloat())
            val spacing = ((2.0 * Math.PI) / (numPearls + rng.nextFloat(0f, 5f))).toFloat()
            for (i in 0 until numPearls) {
                if (storms.size >= 20) break
                val lat = bandLat + rng.nextFloat(-0.01f, 0.01f)
                val lon = startLon + i * spacing + rng.nextFloat(-0.05f, 0.05f)
                val r = sqrt(1f - lat * lat)
                storms.add(MicroStormData(
                    x = r * cos(lon), y = lat, z = r * sin(lon),
                    radius = rng.nextFloat(0.015f, 0.030f),
                    strength = strengthBase,
                    type = type,
                ))
            }
        } else {
            val numCyclones = rng.nextInt(2, 5)
            val centerLon = rng.nextFloat(0f, (2.0 * Math.PI).toFloat())
            for (i in 0 until numCyclones) {
                if (storms.size >= 20) break
                val lat = bandLat + rng.nextFloat(-0.05f, 0.05f)
                val lon = centerLon + rng.nextFloat(-0.1f, 0.1f)
                val r = sqrt(1f - lat * lat)
                storms.add(MicroStormData(
                    x = r * cos(lon), y = lat, z = r * sin(lon),
                    radius = rng.nextFloat(0.010f, 0.030f),
                    strength = strengthBase * rng.nextFloat(0.75f, 1.25f),
                    type = type,
                ))
            }
        }
    }
    return storms.take(20)
}

/**
 * Builds [TerrestrialBakeData] from a rocky [VisualProfile].
 * The 6-stop terrain palette is derived from [SurfaceComposition] fractions so that
 * silicate-rich worlds appear as warm browns, iron-oxidised as reds, carbon worlds
 * as near-black graphite, sulfur worlds as yellows, etc. Seed-based ±8% per-channel
 * variation ensures each planet looks unique.
 */
private fun buildTerrestrialBakeData(
    profile: com.tadmor.domain.classification.visual.VisualProfile,
    entry: SystemPlanetEntry,
    planetRadiusKm: Float,
): TerrestrialBakeData? {
    val surface = profile.surfaceComposition ?: return null
    val temp = profile.surfaceTemperatureK
    val hasO2 = profile.atmosphere.o2 > 0.01f
    val seed = profile.seed

    // ── 6-stop terrain palette derived from surface composition ──
    val terrain = buildTerrainColors(surface, temp, hasO2, seed, entry.planet.densityGCm3)

    // ── Ocean / ice / lava / hydrocarbon color + effective sea level ──
    // Lava pools in basins exactly like oceans do — we just swap the "water" color
    // and drive seaLevel from temperature. Only worlds hot enough to partially
    // melt rock (>900K) show lava; below that, standard water/ice/hydrocarbon.
    val isLavaCapable = temp > 900f
    // waterSpecular: now a global on/off gate for ALL surface specular
    // (water glint + ice highlights + rock gloss), gating per-pixel
    // material detection inside the shader's `surfaceSpecular` helper.
    // Always 1.0 for terrestrial worlds — the helper auto-detects which
    // material a pixel is and applies the right specular profile. Gas
    // giants take the alternative codepath in PlanetGlobeParams that
    // never sets this, so it falls through to 0 and gas giant colours
    // never trigger spurious gloss from the per-pixel detection.
    var waterSpecular = 1f
    // Drives the bake's depth-darkening on water: liquid is flat (0),
    // frozen ice keeps subtle topology (true). Set per branch below.
    var waterIsIce = false
    val (waterColor, effectiveSeaLevel) = when {
        isLavaCapable -> {
            // Lava coverage ramps with temperature:
            //   ~900K  → 15% coverage (isolated magma ponds)
            //   ~1500K → 90% coverage (near-fully molten surface)
            val lavaLevel = ((temp - 900f) / 600f).coerceIn(0.15f, 0.90f)
            0xFFFF3A08L to lavaLevel
        }
        surface.methane > 0.25f && temp < 150f ->
            0xFF2A1A0AL to profile.seaLevel                          // Titan hydrocarbon
        temp < 273f -> {
            waterIsIce = true                                        // frozen sea
            // Salt 4 (same as the polar cap below) so the frozen sea and the
            // cap pick the same index from WATER_ICE on a given seed —
            // visually unifies the two ice surfaces on the same planet.
            pickColor(ColorPalettes.WATER_ICE, seed, 4) to scaleWaterSeaLevel(profile.seaLevel)
        }
        temp < 373f ->
            pickColor(ColorPalettes.WATER_LIQUID, seed, 2) to scaleWaterSeaLevel(profile.seaLevel)
        else ->
            0xFFF0F0F0L to 0f                                        // above boiling, no sea
    }
    val (waterR, waterG, waterB) = waterColor.toGlRgb()

    // ── Polar cap material ──
    val polarColor: Long = when {
        temp > 350f -> pickColor(ColorPalettes.SILICATE_LIGHT, seed, 4)
        surface.nitrogen > 0.05f && temp < 100f -> pickColor(ColorPalettes.NITROGEN_ICE, seed, 4)
        surface.sulfur > 0.10f && temp < 200f -> pickColor(ColorPalettes.SULFUR_FROST, seed, 4)
        else -> pickColor(ColorPalettes.WATER_ICE, seed, 4)
    }
    val (polarR, polarG, polarB) = polarColor.toGlRgb()

    // ── Safety gate: no polar ice caps on hot worlds ──
    val safePolarCap = if (temp > 350f) 0f else profile.polarCapExtent

    // ── Noise scale from planet radius ──
    // Mars (3389 km) → 1.8, ~gas giant core (10204 km) → 3.8. Clamped [1.2, 5.0].
    // Raised from the PoC's [0.8, 4.0] to produce noticeably craggier terrain
    // without crossing the 1024×512 bake's Nyquist limit on the rough octave.
    val pocM = (3.8f - 1.8f) / (10204f - 3389f)
    val pocC = 1.8f - pocM * 3389f
    val noiseScale = (pocM * planetRadiusKm + pocC).coerceIn(1.2f, 5.0f)

    // ── Crater generation ──
    val craterRng = DeterministicRandom(seed xor 0x43524154L)
    val craters = generateCraters(craterRng, profile.craterProfile)
    val craterDensityField = (profile.craterProfile.density *
        (1f - profile.craterProfile.degradation)).coerceIn(0f, 1f)

    return TerrestrialBakeData(
        t0R = terrain[0],  t0G = terrain[1],  t0B = terrain[2],
        t1R = terrain[3],  t1G = terrain[4],  t1B = terrain[5],
        t2R = terrain[6],  t2G = terrain[7],  t2B = terrain[8],
        t3R = terrain[9],  t3G = terrain[10], t3B = terrain[11],
        t4R = terrain[12], t4G = terrain[13], t4B = terrain[14],
        t5R = terrain[15], t5G = terrain[16], t5B = terrain[17],
        colorWaterR = waterR, colorWaterG = waterG, colorWaterB = waterB,
        waterIsIce = waterIsIce,
        colorPolarR = polarR, colorPolarG = polarG, colorPolarB = polarB,
        waterFraction = surface.water,
        carbonFraction = surface.carbon,
        tholinFraction = surface.tholins,
        seaLevel = effectiveSeaLevel,
        polarCap = safePolarCap,
        roughness = profile.roughness,
        noiseScale = noiseScale,
        tidallyLocked = profile.tidallyLocked,
        subSolarX = 0f, subSolarY = 0f, subSolarZ = 1f, // overwritten after sun dir calc
        craterDensityField = craterDensityField,
        volcanism = profile.volcanicActivity,
        // Match the 900 K threshold that planet.frag's lavaMask uses to
        // gate emissive lava colours. Above the threshold the surface is
        // considered genuinely molten (lava world) and hotspots paint
        // full lava across the patch; below, the surface has cooled to
        // basalt and only the patch rim glows.
        moltenSurface = profile.surfaceTemperatureK > 900.0,
        waterSpecular = waterSpecular,
        permSeed = (seed and 0xFFFFL).toInt(),
        craters = craters,
    )
}

/**
 * Derives a 6-stop terrain elevation palette from surface composition fractions.
 *
 * Returns 18 floats: [t0R, t0G, t0B, t1R, t1G, t1B, ..., t5R, t5G, t5B].
 * Stops span elevation 0.00→1.00 (deep basins → summits) via a brightness ramp
 * applied to a composition-blended base rock color:
 *   - Silicates (dominant): warm brown/tan baseline
 *   - Iron + O₂ → rust reds; iron alone → metallic grey
 *   - Carbon → near-black graphite (desaturates everything)
 *   - Sulfur → yellow-orange (hot) or pale frost (cold)
 *   - Tholins → orange-brown
 *   - Temperature scales overall brightness and shifts toward grey-blue when cold
 * Seed-based ±8% per-channel variation gives each planet a unique look.
 */
internal fun buildTerrainColors(
    surface: com.tadmor.domain.classification.visual.SurfaceComposition,
    temp: Float,
    hasO2: Boolean,
    seed: Long,
    densityGCm3: Double? = null,
): FloatArray {
    val rng = DeterministicRandom(seed xor 0x54455252L) // "TERR"

    // ── Base rock color: silicate warm tan at mid-brightness ──
    var r = 0.608f; var g = 0.478f; var b = 0.314f  // #9B7A50

    // ── Visual prominence curve ──
    // Trace species (carbon, sulfur, tholins) naturally appear at small mass
    // fractions but should read clearly in the palette. sqrt() boosts low
    // values disproportionately: 5% → 22% weight, 25% → 50% weight, 100% → 100%.
    // Without this, a 5% sulfur world looks identical to pure silicate.
    fun prominence(frac: Float) = kotlin.math.sqrt(frac.coerceIn(0f, 1f))

    // Iron shift
    val iron = surface.iron.coerceIn(0f, 1f)
    if (iron > 0.02f) {
        val w = prominence(iron)
        if (hasO2) {
            // Oxidised iron → rust red (#C04028)
            r = lerp(r, 0.753f, w * 0.75f)
            g = lerp(g, 0.251f, w * 0.75f)
            b = lerp(b, 0.157f, w * 0.75f)
        } else {
            // Metallic iron → grey (#888894)
            r = lerp(r, 0.533f, w * 0.60f)
            g = lerp(g, 0.533f, w * 0.60f)
            b = lerp(b, 0.580f, w * 0.60f)
        }
    }

    // Carbon shift: desaturate toward near-black graphite
    val carbon = surface.carbon.coerceIn(0f, 1f)
    if (carbon > 0.02f) {
        val w = prominence(carbon)
        val dark = (r * 0.299f + g * 0.587f + b * 0.114f) * 0.18f
        r = lerp(r, dark,          w * 0.88f)
        g = lerp(g, dark,          w * 0.88f)
        b = lerp(b, dark * 1.05f,  w * 0.88f)
    }

    // Sulfur shift. Threshold at 120 K: elemental sulfur (S8) stays yellow
    // down to cryogenic temperatures — Io at ~140 K reads yellow, not white.
    // Only genuinely SO₂-frost-dominated worlds (<120 K) get the pale path.
    val sulfur = surface.sulfur.coerceIn(0f, 1f)
    if (sulfur > 0.02f) {
        val w = prominence(sulfur)
        if (temp > 120f) {
            // Warm sulfur: bright yellow-orange (#D09010)
            r = lerp(r, 0.820f, w * 0.88f)
            g = lerp(g, 0.565f, w * 0.88f)
            b = lerp(b, 0.063f, w * 0.88f)
        } else {
            // Cryogenic sulfur frost: saturated cream-yellow (#E8DC60)
            r = lerp(r, 0.910f, w * 0.75f)
            g = lerp(g, 0.863f, w * 0.75f)
            b = lerp(b, 0.376f, w * 0.75f)
        }
    }

    // Tholin shift: orange-brown (#9C5830)
    val tholins = surface.tholins.coerceIn(0f, 1f)
    if (tholins > 0.02f) {
        val w = prominence(tholins)
        r = lerp(r, 0.612f, w * 0.62f)
        g = lerp(g, 0.345f, w * 0.62f)
        b = lerp(b, 0.188f, w * 0.62f)
    }

    // Temperature: overall brightness + hue
    val tempScale = when {
        temp > 1400f -> 0.18f
        temp > 800f  -> 0.35f
        temp > 400f  -> 0.60f
        temp > 200f  -> 0.85f
        temp > 100f  -> 0.78f
        else         -> 0.70f
    }
    r *= tempScale; g *= tempScale; b *= tempScale

    // Cold: shift residual color toward grey-blue
    if (temp < 200f) {
        val cold = ((200f - temp) / 200f).coerceIn(0f, 1f) * 0.45f
        r = lerp(r, 0.42f, cold); g = lerp(g, 0.47f, cold); b = lerp(b, 0.52f, cold)
    }

    // Water ice shift: cold worlds with high surface water fraction are ice worlds.
    // The whole terrain is frozen water — not just the polar caps — so shift the
    // base color toward ice white. Applied AFTER the brightness/cold-blue shifts
    // so the whiteness isn't darkened. Prevents Aquaria worlds from appearing as
    // brown silicate rock with an oversized polar cap band.
    val waterFrac = surface.water.coerceIn(0f, 1f)
    if (waterFrac > 0.25f && temp < 273f) {
        val iceStrength = ((waterFrac - 0.25f) / 0.45f).coerceIn(0f, 1f)
        r = lerp(r, 0.910f, iceStrength * 0.88f)  // #E8F0F4
        g = lerp(g, 0.941f, iceStrength * 0.88f)
        b = lerp(b, 0.957f, iceStrength * 0.88f)
    }

    // Low-density whitening: rocky worlds below Earth's density (~5.51 g/cm³)
    // carry disproportionately more H₂O in their bulk than dense silicate
    // worlds. Scales with both how far below Earth density the world sits
    // and its surface water fraction, pushing worlds like TRAPPIST-1 h
    // toward the icy white look implied by their bulk rather than reading
    // as dry brown rock. Stacks with the ice shift above for the coldest
    // water-rich planets.
    if (densityGCm3 != null && waterFrac > 0.05f) {
        val densityBelowEarth = ((EARTH_DENSITY - densityGCm3) / EARTH_DENSITY)
            .coerceIn(0.0, 1.0).toFloat()
        val whitenStrength = densityBelowEarth * waterFrac
        if (whitenStrength > 0.02f) {
            r = lerp(r, 0.910f, whitenStrength * 0.85f)
            g = lerp(g, 0.941f, whitenStrength * 0.85f)
            b = lerp(b, 0.957f, whitenStrength * 0.85f)
        }
    }

    // ── Per-planet hue rotation ──
    // Without this, all worlds sharing a composition look identical. ±25° of hue
    // and ±20% saturation (applied once to the base color, so every stop shifts
    // consistently) gives each planet a recognisably unique palette.
    val hsv = rgbToHsv(r, g, b)
    val hueShift = rng.nextFloat(-0.07f, 0.07f)               // ≈ ±25°
    val satMul   = 1f + rng.nextFloat(-0.20f, 0.20f)
    val newH = ((hsv[0] + hueShift) + 1f) % 1f
    val newS = (hsv[1] * satMul).coerceIn(0f, 1f)
    val rgb = hsvToRgb(newH, newS, hsv[2])
    r = rgb[0]; g = rgb[1]; b = rgb[2]

    // ── Build 6 elevation stops via brightness ramp ──
    // Stops 0→5: 0.18× → 1.65× of the base color
    val brightStops = floatArrayOf(0.18f, 0.42f, 0.68f, 1.00f, 1.32f, 1.65f)

    return FloatArray(18) { idx ->
        val stop    = idx / 3
        val channel = idx % 3
        val base    = when (channel) { 0 -> r; 1 -> g; else -> b }
        var v = (base * brightStops[stop]).coerceIn(0f, 1f)

        // Stop 5 (summits): partial desaturation toward white-grey rock dust / frost
        if (stop == 5) {
            val grey = ((r * brightStops[5]) * 0.299f +
                        (g * brightStops[5]) * 0.587f +
                        (b * brightStops[5]) * 0.114f).coerceIn(0.5f, 1f)
            v = lerp(v, grey, 0.55f)
        }

        // Small per-stop per-channel jitter ±4% adds textural variation between stops
        // without blurring the hue shift applied above.
        (v * (1f + rng.nextFloat(-0.04f, 0.04f))).coerceIn(0f, 1f)
    }
}

/** Earth's bulk density in g/cm³. Reference point for low-density whitening. */
private const val EARTH_DENSITY = 5.51

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/**
 * Blackbody emission colour × intensity for a rocky surface at [tempK],
 * returned as linear RGB. Silicate rock is a near-grey emitter, so the
 * colour follows the standard Planck locus approximation.
 *
 * Intensity ramps with (T - 700)^4 / NORM to match Stefan-Boltzmann
 * luminosity scaling, then plateaus at 3000 K when the surface would
 * be saturated-incandescent regardless of atmosphere.
 *
 * ~700 K → 0 (invisible IR only)
 * ~900 K → faint dim red glow
 * 1500 K → strong orange
 * 2500 K → bright yellow-white
 * 3000 K+ → saturated white-hot
 */
private fun computeThermalEmission(tempK: Float): FloatArray {
    if (tempK < 700f) return floatArrayOf(0f, 0f, 0f)

    // Temperature-driven RGB (Tanner Helland-style approximation, clamped).
    val t = (tempK / 100f).coerceIn(10f, 400f)
    val r = if (t <= 66f) 1f
            else (329.698727446f * (t - 60f).toDouble().pow(-0.1332047592)
                .toFloat() / 255f).coerceIn(0f, 1f)
    val g = if (t <= 66f)
                (99.4708025861f * kotlin.math.ln(t) - 161.1195681661f)
                    .coerceIn(0f, 255f) / 255f
            else (288.1221695283f * (t - 60f).toDouble().pow(-0.0755148492)
                .toFloat() / 255f).coerceIn(0f, 1f)
    val b = when {
        t >= 66f -> 1f
        t <= 19f -> 0f
        else -> ((138.5177312231f * kotlin.math.ln(t - 10f) - 305.0447927307f)
            .coerceIn(0f, 255f) / 255f)
    }

    // Intensity: (T-700)^4 / (2300)^4, so 3000 K → 1.0. Scaled by peak
    // visual brightness (~0.9) so it blends without fully blowing out.
    val excess = (tempK - 700f).coerceAtLeast(0f)
    val raw = (excess / 2300f).toDouble().pow(2.6).toFloat()  // gentler than T^4
    val intensity = (raw * 0.9f).coerceAtMost(1.0f)

    return floatArrayOf(r * intensity, g * intensity, b * intensity)
}

/**
 * Amplifies the domain-layer sea level for water-bearing worlds so oceans and
 * ice sheets read as wide expanses rather than narrow basins. Applies a 1.45×
 * gain with a 0.93 ceiling — leaves room for land to still show above even on
 * the most water-dominated profiles.
 */
private fun scaleWaterSeaLevel(raw: Float): Float =
    (raw * 1.65f).coerceIn(0f, 0.94f)

private fun rgbToHsv(r: Float, g: Float, b: Float): FloatArray {
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val d = max - min
    val v = max
    val s = if (max == 0f) 0f else d / max
    val h = if (d == 0f) 0f else when (max) {
        r -> ((g - b) / d + (if (g < b) 6f else 0f)) / 6f
        g -> ((b - r) / d + 2f) / 6f
        else -> ((r - g) / d + 4f) / 6f
    }
    return floatArrayOf(h, s, v)
}

private fun hsvToRgb(h: Float, s: Float, v: Float): FloatArray {
    val i = (h * 6f).toInt()
    val f = h * 6f - i
    val p = v * (1f - s)
    val q = v * (1f - f * s)
    val t = v * (1f - (1f - f) * s)
    return when (((i % 6) + 6) % 6) {
        0 -> floatArrayOf(v, t, p)
        1 -> floatArrayOf(q, v, p)
        2 -> floatArrayOf(p, v, t)
        3 -> floatArrayOf(p, q, v)
        4 -> floatArrayOf(t, p, v)
        else -> floatArrayOf(v, p, q)
    }
}

/** Picks a deterministic color from a palette using seed + salt. */
internal fun pickColor(palette: LongArray, seed: Long, salt: Int): Long {
    val index = ((seed xor (salt.toLong() * -7046029254386353131L)) ushr 32).toInt()
        .let { (it and 0x7FFFFFFF) % palette.size }
    return palette[index]
}

/**
 * Generates up to 150 explicit large craters with power-law size distribution.
 * Uniform sphere distribution ensures no polar/equatorial bias.
 */
private fun generateCraters(
    rng: DeterministicRandom,
    craterProfile: com.tadmor.domain.classification.visual.CraterProfile,
): List<CraterData> {
    if (craterProfile.density < 0.01f) return emptyList()
    // Heavily cratered worlds (Mercury-like, density ≈ 1.0) get up to 150 explicit
    // large craters so dead, ancient surfaces visibly read as such. Sqrt-scaling
    // keeps moderate densities meaningful — Mars-class lands around 106, Moon-
    // class around 130 — without making every airless body a densely-pockmarked
    // moon. The bake shader's matching cap was bumped from 60 to 150 to suit.
    val n = (craterProfile.density.toDouble().pow(0.5) * 150.0).roundToInt().coerceIn(0, 150)
    // Minimum crater radius floor: 30% of max. Ultra-tiny craters look like noise
    // against the base terrain resolution (1024×512 bake at typical planet size).
    // A flatter power law (1.4 vs the profile's ~2.5 exponent) gives more mid-size
    // and large craters — the dominant feature of heavily-cratered worlds.
    val maxR = craterProfile.maxCraterScale.coerceAtLeast(0.08f)
    val minR = maxR * 0.30f
    return List(n) {
        // Uniform on sphere: sample cos(lat) uniformly then convert
        val cosLat = rng.nextFloat(-1f, 1f)
        val lat = acos(cosLat.toDouble()).toFloat() - (PI / 2).toFloat()
        val lon = rng.nextFloat(0f, (2.0 * PI).toFloat())
        val cosLatF = cos(lat.toDouble()).toFloat()
        val sizeU = rng.nextFloat(0f, 1f)
        val radius = minR + (maxR - minR) * sizeU.pow(1.4f)
        val depth = (1f - craterProfile.degradation) * rng.nextFloat(0.6f, 1f)
        CraterData(
            x = cosLatF * cos(lon.toDouble()).toFloat(),
            y = sin(lat.toDouble()).toFloat(),
            z = cosLatF * sin(lon.toDouble()).toFloat(),
            radius = radius,
            depth = depth,
            degradation = craterProfile.degradation,
        )
    }
}

private fun estimateRadiusFromMass(massEarth: Double?, isGiant: Boolean): Double {
    if (massEarth == null) return if (isGiant) 11.2 else 1.0
    return if (isGiant) {
        // Gas giants: weak radius–mass relation
        11.2 * (massEarth / 317.8).pow(0.06)
    } else {
        // Rocky: R ∝ M^0.27 (Valencia et al.)
        massEarth.pow(0.27)
    }
}

