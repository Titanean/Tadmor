package com.tadmor.domain.classification.visual

/**
 * Base color palettes and color utilities for the visual profile engine.
 * Colors are stored as ARGB Long values (0xAARRGGBB).
 */
object ColorPalettes {

    // --- Silicate palettes (felsic=light, basaltic=dark) ---
    val SILICATE_LIGHT = longArrayOf(0xFFC8BEB0, 0xFFB5A898, 0xFFD4C9BA, 0xFFA89A8C)
    val SILICATE_DARK = longArrayOf(0xFF6B6058, 0xFF7A6F65, 0xFF584E46, 0xFF8C8078)

    // --- Iron ---
    val IRON_METALLIC = longArrayOf(0xFF8C8C90, 0xFFA0A0A5, 0xFF707078, 0xFF9898A0)
    val IRON_OXIDE = longArrayOf(0xFFC45C3A, 0xFFB04828, 0xFFD06840, 0xFF983C20)

    // --- Water ---
    // Water ice is neutral white with only a hint of cool tint — Earth and
    // Mars polar caps read as off-white, not blue. The bluer shades that
    // used to live here are now the NITROGEN_ICE palette below; pure N2 ice
    // is genuinely transparent/cold-blue (Pluto's pinkish appearance comes
    // from tholin chemistry layered over the N2, not the ice itself).
    val WATER_ICE = longArrayOf(0xFFF2F4F4, 0xFFE8ECEC, 0xFFFAFAFA, 0xFFDDE0E0)
    val WATER_LIQUID = longArrayOf(0xFF1E3B75, 0xFF1A3568, 0xFF233F7E, 0xFF1B3870)

    // --- Sulfur ---
    val SULFUR_WARM = longArrayOf(0xFFE8D040, 0xFFD0B830, 0xFFF0E050, 0xFFC0A020)
    val SULFUR_FROST = longArrayOf(0xFFF0F0E8, 0xFFE8E8D8, 0xFFF8F8F0, 0xFFE0E0D0)

    // --- Carbon ---
    val CARBON_ORGANIC = longArrayOf(0xFF3C3028, 0xFF4A3C30, 0xFF302820, 0xFF584838)
    val CARBON_GRAPHITE = longArrayOf(0xFF484848, 0xFF383838, 0xFF585858, 0xFF303030)

    // --- Nitrogen ice (cool blue-white) ---
    // Pure N2 ice scatters short wavelengths, giving it a subtle cool cast.
    // These values were previously WATER_ICE; they're more visually distinct
    // from the neutral water-ice palette and cleanly signal "exotic ice".
    val NITROGEN_ICE = longArrayOf(0xFFE8F0F8, 0xFFD0E0F0, 0xFFF0F4F8, 0xFFC0D4E8)

    // --- Methane ice (blue-tinted) ---
    val METHANE_ICE = longArrayOf(0xFFD0D8E8, 0xFFC0D0E0, 0xFFD8E0F0, 0xFFB8C8D8)

    // --- Ammonia ice (white) ---
    val AMMONIA_ICE = longArrayOf(0xFFF0F0F0, 0xFFE4E4E4, 0xFFF8F8F8, 0xFFD8D8D8)

    // --- Tholin deposits (reddish-brown) ---
    val THOLIN = longArrayOf(0xFF9C5830, 0xFF884828, 0xFFB06838, 0xFF7C3C20)

    // --- Lava ---
    val LAVA = longArrayOf(0xFFFF4400, 0xFFE83800, 0xFFFF6020, 0xFFCC2800)

    // --- Ring palettes ---
    val ICE_RING = longArrayOf(0xFFE6E6E5, 0xFFDADADC, 0xFFE4E2E0, 0xFFD4D6D8)
    val ROCK_RING = longArrayOf(0xFFC8C0B8, 0xFFBCB6AE, 0xFFD0CAC2, 0xFFB0AAA2)

    // --- Gas giant palette variants per Sudarsky class ---
    // Each type has multiple variants so planets of the same class look distinct.
    // Slots: [0]=Band A (main), [1]=Band B (secondary), [2]=Dark accent, [3]=Storm, [4]=fallback pole

    // AMMONIA: Jupiter/Saturn cold range — 7 variants
    val GAS_AMMONIA_VARIANTS = arrayOf(
        // Red Jupiter: classic cream + red-brown belts
        longArrayOf(0xFFE8C888, 0xFFC89060, 0xFF8C4028, 0xFFCC7040, 0xFFF0E4D0),
        // Pale Saturn: all-cream, no red dominance
        longArrayOf(0xFFF0E8D0, 0xFFE0D4B8, 0xFFD4C8A8, 0xFFE8DCC8, 0xFFF8F4EE),
        // Dark Belt: deep red-brown dominant, moody
        longArrayOf(0xFF703018, 0xFF8C4028, 0xFF502010, 0xFF904030, 0xFFC07848),
        // Green Phosphine: yellow-green shift (phosphine photochemistry)
        longArrayOf(0xFFD0C860, 0xFFB8A840, 0xFF808030, 0xFFC8B020, 0xFFE8E090),
        // Ochre Blaze: orange-dominant, warm fire tones
        longArrayOf(0xFFE89040, 0xFFD07020, 0xFFB85020, 0xFFE8A060, 0xFFF0C080),
        // Frost Cream: very light, pale cream with delicate highlights
        longArrayOf(0xFFF8F0DC, 0xFFE8DCB8, 0xFFC4A878, 0xFFE0C8A0, 0xFFFAF6E8),
        // Coffee Belted: deep coffee-and-cream contrast
        longArrayOf(0xFFC8A878, 0xFF8C6840, 0xFF402418, 0xFF604030, 0xFFE0C898),
    )

    // WATER: warm water-cloud giant — 6 variants
    val GAS_WATER_VARIANTS = arrayOf(
        // Cloud Blue: cool blue-white (current)
        longArrayOf(0xFFD0D8E8, 0xFFB0B8D0, 0xFF9098B8, 0xFFE0E8F0, 0xFF8090A8),
        // Pearl Storm: near-white high clouds, very pale
        longArrayOf(0xFFE8EEF4, 0xFFD8E4EE, 0xFFC0D0E0, 0xFFE0ECF4, 0xFFB0C4D8),
        // Storm Grey: darker, more turbulent steel-blue
        longArrayOf(0xFFA0B0C4, 0xFF8898A8, 0xFF70869A, 0xFF606C78, 0xFFB4C2D0),
        // Warm Haze: brownish-grey tint from cloud chemistry
        longArrayOf(0xFFD4CEBC, 0xFFC0BAA8, 0xFFB0A898, 0xFFD8D0B8, 0xFF989080),
        // Aquamarine Cloud: blue-green tint, fresh sea
        longArrayOf(0xFFB0DCD8, 0xFF98C8C4, 0xFF689894, 0xFFC0E4DC, 0xFF80B0AC),
        // Tempest Indigo: deep, brooding stormfront
        longArrayOf(0xFF6878A0, 0xFF586890, 0xFF384068, 0xFF788CB0, 0xFF485080),
    )

    // CLEAR: near-cloudless hot Jupiter — 5 variants
    val GAS_CLEAR_VARIANTS = arrayOf(
        // Cobalt Haze: blue-grey (current)
        longArrayOf(0xFF7888A8, 0xFF6878A0, 0xFF8898B8, 0xFF586888, 0xFF9AAAC0),
        // Deep Sapphire: darker, more saturated blue
        longArrayOf(0xFF5060A0, 0xFF405098, 0xFF6070B0, 0xFF303868, 0xFF7080C0),
        // Pale Cerulean: washed-out, hazy pale blue
        longArrayOf(0xFFA8B8D0, 0xFF98A8C4, 0xFFB8C8DA, 0xFF88A0B8, 0xFFC8D4E0),
        // Steel Slate: cooler, more metallic grey-blue
        longArrayOf(0xFF809098, 0xFF707880, 0xFF606870, 0xFF8898A0, 0xFF98A8B0),
        // Ocean Teal: blue-green tint, deeper hue
        longArrayOf(0xFF488898, 0xFF387888, 0xFF286878, 0xFF58A0B0, 0xFF68B0BC),
    )

    // ALKALI: hot Jupiter alkali metal clouds — 5 variants
    val GAS_ALKALI_VARIANTS = arrayOf(
        // Hot Orange: saturated orange-red-brown (current)
        longArrayOf(0xFFD07848, 0xFFB85830, 0xFFE09058, 0xFF983828, 0xFFF0A870),
        // Bronze Gold: yellow-orange, amber dominant
        longArrayOf(0xFFD09840, 0xFFB88020, 0xFFE0B050, 0xFF987808, 0xFFF0C870),
        // Crimson Deep: red dominant, intense heat
        longArrayOf(0xFFB84038, 0xFF903030, 0xFFC85048, 0xFF782028, 0xFFD87868),
        // Sodium Amber: lighter Na-flame yellow on warm cloud deck
        longArrayOf(0xFFE0A848, 0xFFC88830, 0xFFF0C068, 0xFFB07020, 0xFFFCD888),
        // Magma Pink: hot magenta-pink, fire-and-rose tones
        longArrayOf(0xFFD06878, 0xFFB04860, 0xFFE08898, 0xFF883048, 0xFFE898A8),
    )

    // SILICATE: extremely hot iron-silicate cloud giants — 5 variants
    val GAS_SILICATE_VARIANTS = arrayOf(
        // Dark Iron: deep brown-maroon (current)
        longArrayOf(0xFF684838, 0xFF503028, 0xFF785848, 0xFF402018, 0xFF886858),
        // Rust Deep: reddish-orange iron oxide
        longArrayOf(0xFF784038, 0xFF602828, 0xFF885048, 0xFF481818, 0xFFA07068),
        // Purple Iron: violet-tinged silicate clouds
        longArrayOf(0xFF584050, 0xFF403040, 0xFF685860, 0xFF302030, 0xFF786870),
        // Charcoal Ash: nearly-black basaltic dust
        longArrayOf(0xFF483828, 0xFF302820, 0xFF584838, 0xFF201810, 0xFF685848),
        // Blood Ember: bright red-iron, fresh from the magma
        longArrayOf(0xFF883830, 0xFF682020, 0xFF984038, 0xFF481818, 0xFFA86050),
    )

    // ICE GIANT: Neptune/Uranus range — 5 variants
    // Neptune baseline: R:72 G:120 B:200 — B clearly dominant
    val GAS_ICE_GIANT_VARIANTS = arrayOf(
        // Neptune Blue: deep cobalt (current)
        longArrayOf(0xFF4878C8, 0xFF3060B0, 0xFF5888D8, 0xFF2050A0, 0xFF6898E0),
        // Uranus Teal: blue-green (Uranus real color)
        longArrayOf(0xFF5098A8, 0xFF408898, 0xFF60A8B8, 0xFF307888, 0xFF70B8C8),
        // Deep Indigo: purple-blue, moody
        longArrayOf(0xFF4058B8, 0xFF303888, 0xFF5068C8, 0xFF202868, 0xFF6078D0),
        // Frost Cyan: pale icy cyan-blue, fresh nitrogen feel
        longArrayOf(0xFF80B0D0, 0xFF6898C0, 0xFF98C0E0, 0xFF5080A8, 0xFFA8C8E8),
        // Midnight Methane: very deep, almost-black saturated blue
        longArrayOf(0xFF283878, 0xFF1A2858, 0xFF384888, 0xFF101838, 0xFF485898),
    )

    // SUB-NEPTUNE: transitional ice/gas — 5 variants
    val GAS_SUB_NEPTUNE_VARIANTS = arrayOf(
        // Slate Blue: muted blue-grey (current)
        longArrayOf(0xFF5878B0, 0xFF4868A0, 0xFF6888C0, 0xFF385898, 0xFF78A0C8),
        // Misty Grey: pale atmospheric haze, washed out
        longArrayOf(0xFF90A0B0, 0xFF808898, 0xFFA0B0C0, 0xFF707888, 0xFFB0C0D0),
        // Warm Slate: slight purple-brown tinge from hazes
        longArrayOf(0xFF807088, 0xFF706078, 0xFF908098, 0xFF605068, 0xFFA090A8),
        // Pale Sky: lighter sub-Neptune, soft pastel blue
        longArrayOf(0xFFA8C0D8, 0xFF98B0C8, 0xFFB8D0E0, 0xFF8898B0, 0xFFC8D8E8),
        // Dusky Lavender: subtle violet tinge in upper haze
        longArrayOf(0xFF7878A0, 0xFF606888, 0xFF8888B0, 0xFF505868, 0xFF9898C0),
    )

    // THOLIN: photochemical haze over cold ice giant — 5 variants
    val GAS_THOLIN_VARIANTS = arrayOf(
        // Purple Haze: violet dominant (current)
        longArrayOf(0xFF8060C0, 0xFF6848A8, 0xFF9870D0, 0xFF503090, 0xFFB090D8),
        // Titan Brown: orange-brown tholin like Titan
        longArrayOf(0xFFB87040, 0xFF985028, 0xFFD09060, 0xFF803020, 0xFFD8B090),
        // Violet Deep: deep indigo-violet
        longArrayOf(0xFF6040C8, 0xFF4830A0, 0xFF7050D8, 0xFF302080, 0xFF9070E0),
        // Smoky Sepia: brown-grey, weathered photochemistry
        longArrayOf(0xFF887058, 0xFF705848, 0xFF986048, 0xFF583828, 0xFFA88868),
        // Plum Royal: deep magenta-purple, rich and saturated
        longArrayOf(0xFF7050A8, 0xFF583890, 0xFF8868B8, 0xFF402870, 0xFFA078C8),
    )

    // HELIUM NEPTUNE: H-stripped, He-dominated, CO₂-rich atmosphere, no
    // methane absorption → pearlescent white/light-grey bands. 5 variants
    // span pure pearlescent → warm-white → cool-grey → champagne → silver.
    val GAS_HELIUM_NEPTUNE_VARIANTS = arrayOf(
        // Pearlescent: near-pure white with subtle gradient
        longArrayOf(0xFFE8E8E8, 0xFFD0D0D0, 0xFFF0F0F0, 0xFFC0C0C0, 0xFFF8F8F8),
        // Warm White: very faint cream tint from minor metal absorption
        longArrayOf(0xFFEAE4DE, 0xFFD4CEC8, 0xFFF2ECE6, 0xFFC0BAB4, 0xFFF8F2EC),
        // Cool Grey: bluish-grey, hint of remaining Rayleigh
        longArrayOf(0xFFD8DCE0, 0xFFC0C4C8, 0xFFE8ECF0, 0xFFB0B4B8, 0xFFF0F4F8),
        // Champagne: subtle gold-tan from trace photochemistry
        longArrayOf(0xFFEEE8DC, 0xFFD8D2C4, 0xFFF6F0E4, 0xFFC8C2B4, 0xFFFCF8EC),
        // Silver Frost: cool metallic, faintly polished
        longArrayOf(0xFFD0D4D8, 0xFFB8BCC0, 0xFFE0E4E8, 0xFFA0A4A8, 0xFFE8ECF0),
    )

    // SILICATE NEPTUNE: ultra-hot Neptune whose dayside vaporises silicate
    // species that recondense as reflective clouds on the cooler limb. The
    // band texture reads as deep red / rust / dusty-pink because alkali
    // photochemistry gives the deck a Na/K-flame warmth on top of the
    // basaltic ash-grey cloud particulates. 5 variants for variety.
    val GAS_SILICATE_NEPTUNE_VARIANTS = arrayOf(
        // Rusty Red: iron-oxide warmth dominant
        longArrayOf(0xFFB85040, 0xFF983828, 0xFFC86850, 0xFF783028, 0xFFD88068),
        // Pink Salmon: alkali-flame warmth on lighter cloud deck
        longArrayOf(0xFFC07060, 0xFFA05848, 0xFFD08878, 0xFF884838, 0xFFE09888),
        // Dusty Maroon: darker, more iron-rich
        longArrayOf(0xFFA04838, 0xFF803020, 0xFFB05848, 0xFF682820, 0xFFC07060),
        // Ember Glow: brighter orange-red, fresh from the magma
        longArrayOf(0xFFD08048, 0xFFB06030, 0xFFE08858, 0xFF904028, 0xFFF0A878),
        // Coal Ash: very dark, near-black with subtle warmth
        longArrayOf(0xFF583028, 0xFF402020, 0xFF684030, 0xFF281010, 0xFF785040),
    )

    // --- Brown dwarf band palette (T dwarfs and below) ---
    // Strict two-color alternation: deep purple lanes + bright salmon highlights.
    // Bake shader interpolates across 5 slots, so PSPSP gives alternating bands.
    // Salmon luminance is high enough that the star shader's emissive mask fires
    // on those slots, giving the planet a glowing salmon-banded appearance.
    val BD_T_VARIANTS = arrayOf(
        longArrayOf(0xFF1F0529, 0xFF4A0E2E, 0xFF1F0529, 0xFF4A0E2E, 0xFF1F0529),
        longArrayOf(0xFF1F0529, 0xFF4A0E2E, 0xFF1F0529, 0xFF4A0E2E, 0xFF1F0529),
        longArrayOf(0xFF290A33, 0xFF5C1430, 0xFF290A33, 0xFF5C1430, 0xFF290A33),
    )

    // --- Star tint colors by spectral type ---
    val TINT_O_B = 0xFFCCDDFF.toLong()
    val TINT_A = 0xFFE0E8FF.toLong()
    val TINT_F = 0xFFF0F0FF.toLong()
    val TINT_G = 0xFFFFF8F0.toLong()
    val TINT_K = 0xFFFFE8D0.toLong()
    val TINT_M = 0xFFFFD0B0.toLong()

    fun starTint(teffK: Double?): Long = when {
        teffK == null -> TINT_G
        teffK > 10000 -> TINT_O_B
        teffK > 7500 -> TINT_A
        teffK > 6000 -> TINT_F
        teffK > 5200 -> TINT_G
        teffK > 3700 -> TINT_K
        else -> TINT_M
    }

    fun gasGiantPaletteVariants(type: GasGiantType): Array<LongArray> = when (type) {
        GasGiantType.AMMONIA         -> GAS_AMMONIA_VARIANTS
        GasGiantType.WATER           -> GAS_WATER_VARIANTS
        GasGiantType.CLEAR           -> GAS_CLEAR_VARIANTS
        GasGiantType.ALKALI          -> GAS_ALKALI_VARIANTS
        GasGiantType.SILICATE        -> GAS_SILICATE_VARIANTS
        GasGiantType.ICE_GIANT       -> GAS_ICE_GIANT_VARIANTS
        GasGiantType.SUB_NEPTUNE     -> GAS_SUB_NEPTUNE_VARIANTS
        GasGiantType.THOLIN          -> GAS_THOLIN_VARIANTS
        GasGiantType.HELIUM_NEPTUNE  -> GAS_HELIUM_NEPTUNE_VARIANTS
        GasGiantType.SILICATE_NEPTUNE -> GAS_SILICATE_NEPTUNE_VARIANTS
    }

    // ── Contrasting pole color palettes ──
    // Used for ~30% of planets where the pole differs strongly from band colors
    val POLE_JUPITER_BLUE = longArrayOf(0xFF7090B0, 0xFF6080A0, 0xFF80A0C0)  // Jupiter-like blue-grey poles
    val POLE_SATURN_GREEN = longArrayOf(0xFF90A870, 0xFF809860, 0xFFA0B880)  // Saturn hexagon green
    val POLE_ICE_WHITE    = longArrayOf(0xFFD8E0E8, 0xFFE0E8F0, 0xFFCCD4DC)  // Icy white caps
    val POLE_AURORA_TEAL  = longArrayOf(0xFF508888, 0xFF407878, 0xFF609898)  // Auroral teal

    // --- Color utility functions ---

    fun shiftHue(color: Long, degrees: Float): Long {
        val a = ((color shr 24) and 0xFF).toInt()
        val r = ((color shr 16) and 0xFF).toInt()
        val g = ((color shr 8) and 0xFF).toInt()
        val b = (color and 0xFF).toInt()
        val hsv = rgbToHsv(r, g, b)
        hsv[0] = (hsv[0] + degrees) % 360f
        if (hsv[0] < 0f) hsv[0] += 360f
        val rgb = hsvToRgb(hsv[0], hsv[1], hsv[2])
        return packArgb(a, rgb[0], rgb[1], rgb[2])
    }

    fun adjustSaturation(color: Long, factor: Float): Long {
        val a = ((color shr 24) and 0xFF).toInt()
        val r = ((color shr 16) and 0xFF).toInt()
        val g = ((color shr 8) and 0xFF).toInt()
        val b = (color and 0xFF).toInt()
        val hsv = rgbToHsv(r, g, b)
        hsv[1] = (hsv[1] * factor).coerceIn(0f, 1f)
        val rgb = hsvToRgb(hsv[0], hsv[1], hsv[2])
        return packArgb(a, rgb[0], rgb[1], rgb[2])
    }

    fun adjustBrightness(color: Long, factor: Float): Long {
        val a = ((color shr 24) and 0xFF).toInt()
        val r = ((color shr 16) and 0xFF).toInt()
        val g = ((color shr 8) and 0xFF).toInt()
        val b = (color and 0xFF).toInt()
        val hsv = rgbToHsv(r, g, b)
        hsv[2] = (hsv[2] * factor).coerceIn(0f, 1f)
        val rgb = hsvToRgb(hsv[0], hsv[1], hsv[2])
        return packArgb(a, rgb[0], rgb[1], rgb[2])
    }

    fun interpolateColor(a: Long, b: Long, t: Float): Long {
        val t1 = t.coerceIn(0f, 1f)
        val t0 = 1f - t1
        return packArgb(
            (((a shr 24) and 0xFF) * t0 + ((b shr 24) and 0xFF) * t1).toInt(),
            (((a shr 16) and 0xFF) * t0 + ((b shr 16) and 0xFF) * t1).toInt(),
            (((a shr 8) and 0xFF) * t0 + ((b shr 8) and 0xFF) * t1).toInt(),
            ((a and 0xFF) * t0 + (b and 0xFF) * t1).toInt(),
        )
    }

    private fun packArgb(a: Int, r: Int, g: Int, b: Int): Long =
        ((a.coerceIn(0, 255).toLong()) shl 24) or
            ((r.coerceIn(0, 255).toLong()) shl 16) or
            ((g.coerceIn(0, 255).toLong()) shl 8) or
            b.coerceIn(0, 255).toLong()

    private fun rgbToHsv(r: Int, g: Int, b: Int): FloatArray {
        val rf = r / 255f; val gf = g / 255f; val bf = b / 255f
        val max = maxOf(rf, gf, bf); val min = minOf(rf, gf, bf)
        val d = max - min
        val h = when {
            d == 0f -> 0f
            max == rf -> ((gf - bf) / d % 6 + 6) % 6 * 60f
            max == gf -> ((bf - rf) / d + 2) * 60f
            else -> ((rf - gf) / d + 4) * 60f
        }
        val s = if (max == 0f) 0f else d / max
        return floatArrayOf(h, s, max)
    }

    private fun hsvToRgb(h: Float, s: Float, v: Float): IntArray {
        val c = v * s; val x = c * (1 - kotlin.math.abs((h / 60f) % 2 - 1))
        val m = v - c
        val (r1, g1, b1) = when {
            h < 60 -> Triple(c, x, 0f)
            h < 120 -> Triple(x, c, 0f)
            h < 180 -> Triple(0f, c, x)
            h < 240 -> Triple(0f, x, c)
            h < 300 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return intArrayOf(
            ((r1 + m) * 255).toInt(),
            ((g1 + m) * 255).toInt(),
            ((b1 + m) * 255).toInt(),
        )
    }
}
