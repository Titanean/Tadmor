package com.tadmor.domain.model

/**
 * A user-saved planet (or candidate) with the parameter snapshot taken
 * at bookmark time. Diff against the current value is computed on-demand
 * — never stored on the entity — so the only writes are add / remove /
 * snapshot-replace-on-consume.
 */
data class Bookmark(
    /** Unified identifier — [Planet.name] for confirmed; [CandidatePlanet.displayName]
     *  ("TOI-1234.01" / "KOI-752.01") for candidates. */
    val planetKey: String,
    /** "PLANET" | "TOI" | "KOI" — disambiguates the live-data lookup at diff time. */
    val source: String,
    /** Epoch millis at the moment the user bookmarked. */
    val bookmarkedAt: Long,
    /** Parameters captured at bookmark time. Replaced with current values on
     *  consume (closing the planet info page). */
    val snapshot: PlanetSnapshot,
)
