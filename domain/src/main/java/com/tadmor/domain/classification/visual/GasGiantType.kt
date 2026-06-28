package com.tadmor.domain.classification.visual

/**
 * Sudarsky gas giant classification based on equilibrium temperature.
 */
enum class GasGiantType(val label: String) {
    AMMONIA("Ammonia Clouds"),
    WATER("Water Clouds"),
    CLEAR("Cloudless"),
    ALKALI("Alkali Metal"),
    SILICATE("Silicate Clouds"),
    ICE_GIANT("Ice Giant"),
    SUB_NEPTUNE("Sub-Neptune"),
    // Thin tholin haze over methane/H2 atmosphere → purple tint.
    // Physically appropriate for cold/frigid ice giants and sub-Neptunes
    // with moderate methane abundance around K/M dwarf hosts (UV-driven photochemistry).
    THOLIN("Tholin Haze"),
    // Hot Neptune that has lost its hydrogen envelope to atmospheric escape,
    // leaving a He-dominated atmosphere enriched in CO₂ and depleted in
    // methane. Without methane's red-light absorption the planet reads as
    // pearlescent white-grey rather than the methane-blue of a normal ice
    // giant. Real-world example: GJ 436 b (Teff ≈ 712 K).
    HELIUM_NEPTUNE("Helium Neptune"),
    // Ultra-hot Neptune (T ≳ 1500 K) whose dayside is hot enough to vaporise
    // silicate species; on the cooler limb / nightside they recondense as
    // reflective silicate clouds (MgSiO₃, Mg₂SiO₄, Fe). Atmosphere reads as
    // near-white from the bright silicate deck with reddish dayside tint.
    // Real-world example: LTT 9779 b (Teff ≈ 2000 K).
    SILICATE_NEPTUNE("Silicate Neptune"),
}
