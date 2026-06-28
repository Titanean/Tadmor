package com.tadmor.domain.model

/**
 * User-configurable display preferences.
 */
data class UserSettings(
    val distanceUnit: DistanceUnit = DistanceUnit.PARSECS,
    val temperatureUnit: TemperatureUnit = TemperatureUnit.KELVIN,
    val starTemperatureUnit: TemperatureUnit = TemperatureUnit.KELVIN,
    val useTerra: Boolean = false,
    val useNeptune: Boolean = true,
    val useJupiter: Boolean = true,
    val useProperNames: Boolean = true,
    val useEstimates: Boolean = true,
    val showDataIndicator: Boolean = true,
    val accessibleMode: Boolean = false,
    val showStarfield: Boolean = true,
    val showHabitableZone: Boolean = true,
    val useEarthSymbol: Boolean = true,
    val useJupiterSymbol: Boolean = true,
    /**
     * When true, single-finger orbit drags act as if the user were
     * pushing the camera (drag right → camera rotates right around the
     * scene, scene appears to slide left). When false (default), drags
     * act as if the user were pushing the scene under a fixed camera —
     * the standard 3D-viewer convention used by Three.js OrbitControls,
     * Blender, etc.
     */
    val invertCameraControls: Boolean = false,
    /**
     * Whether neutron stars (including pulsars) visibly rotate in the
     * star globe view. Default on. Disabling gives a still rendering for
     * users sensitive to rapid motion or who want a clean reference shot.
     */
    val neutronStarRotation: Boolean = true,
    /**
     * Whether unconfirmed exoplanet candidates and confirmed false
     * positives are surfaced in the catalog and system pages. Default off
     * — the catalog reads as a "ground truth" experience until opted in.
     * Enabling reveals two extra sub-tabs (Candidates, False positives) in
     * the catalog and parallel sections on the star info page.
     *
     * Independent of data sync — candidate rows are always synced and
     * stored locally; this toggle only controls visibility.
     */
    val includeCandidates: Boolean = true,
    /**
     * Whether the background WorkManager job that fetches updated catalog
     * data from NASA's Exoplanet Archive is enqueued. Default on so the
     * catalog stays current without user action. When false the scheduler
     * cancels any pending periodic work.
     */
    val autoSyncEnabled: Boolean = true,
    /**
     * When true the periodic sync only runs on unmetered networks (Wi-Fi).
     * Default false because catalog updates are small (<5 MB) and most
     * users won't notice the cost on cellular. Independent of
     * [autoSyncEnabled] — only meaningful when auto-sync is on.
     */
    val autoSyncWifiOnly: Boolean = false,
    /**
     * Unix millis of the last successful catalog sync. 0 means "never
     * synced" — used by the pull-to-refresh UI to suppress the
     * "Last sync: …" label when there's no local cache yet. Updated by
     * `PlanetRepositoryImpl.sync()` after a successful run, both from the
     * background worker and the manual refresh path.
     */
    val lastSyncedAtMillis: Long = 0L,
    /**
     * Debug-only: when true, every successful catalog sync mutates each
     * bookmarked planet's stored snapshot with small random changes to a
     * couple of fields, so the diff display has something to render. The
     * mutation is reversible by re-bookmarking; only the snapshot is
     * touched, never the live catalog data.
     */
    val simulateBookmarkUpdates: Boolean = false,
)

enum class DistanceUnit(val label: String, val suffix: String) {
    PARSECS("Parsecs", "pc"),
    LIGHT_YEARS("Light-years", "ly"),
}

enum class TemperatureUnit(val label: String, val suffix: String) {
    KELVIN("Kelvin", "K"),
    CELSIUS("Celsius", "°C"),
    FAHRENHEIT("Fahrenheit", "°F"),
}
