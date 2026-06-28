package com.tadmor.domain.classification.visual

/**
 * Classification of a planet's cloud condensate, set by [AtmosphereOpticsDeriver]
 * on each cloud-formation branch. Used by the renderer to gate behaviours
 * that only make physical sense for specific cloud chemistries — e.g. the
 * substellar-aligned bullseye cloud pattern on tidally-locked planets is
 * an Earth-like wet-convection feature, so it only fires for [WATER]
 * clouds. Everything else (silicate decks, Venus sulfuric, dry haze,
 * Titan methane, NH₃ ice, Mars CO₂ ice) inherits the standard zonal
 * sampling regardless of tidal lock.
 */
enum class CloudType {
    NONE,        // no cloud deck
    WATER,       // H₂O liquid/ice clouds in the temperate regime (Earth, TRAPPIST-1 e/f)
    STEAM,       // hot opaque H₂O vapour deck (post-runaway, T > 380 K)
    SULFURIC,    // Venus-class H₂SO₄ aerosol deck under thick hot CO₂
    SILICATE,    // lava-world MgSiO₃ + Fe particulates condensing on the cool side
    HAZE,        // dry photochemical / volcanic haze (post-runaway dehydrated greenhouse)
    METHANE,     // Titan-like CH₄ haze
    AMMONIA,     // NH₃ ice
    CO2_ICE,     // Mars-like high-altitude CO₂ ice clouds
}
