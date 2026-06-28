package com.tadmor.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import com.tadmor.domain.model.DistanceUnit
import com.tadmor.domain.model.TemperatureUnit
import com.tadmor.domain.model.UserSettings
import com.tadmor.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : SettingsRepository {

    companion object {
        private val KEY_DISTANCE = stringPreferencesKey("distance_unit")
        private val KEY_TEMPERATURE = stringPreferencesKey("temperature_unit")
        private val KEY_STAR_TEMPERATURE = stringPreferencesKey("star_temperature_unit")
        private val KEY_USE_TERRA = booleanPreferencesKey("use_terra")
        private val KEY_USE_NEPTUNE = booleanPreferencesKey("use_neptune")
        private val KEY_USE_JUPITER = booleanPreferencesKey("use_jupiter")
        private val KEY_USE_PROPER_NAMES = booleanPreferencesKey("use_proper_names")
        private val KEY_USE_ESTIMATES = booleanPreferencesKey("use_estimates")
        private val KEY_SHOW_DATA_INDICATOR = booleanPreferencesKey("show_data_indicator")
        private val KEY_ACCESSIBLE_MODE = booleanPreferencesKey("accessible_mode")
        private val KEY_SHOW_STARFIELD = booleanPreferencesKey("show_starfield")
        private val KEY_SHOW_HABITABLE_ZONE = booleanPreferencesKey("show_habitable_zone")
        private val KEY_USE_EARTH_SYMBOL = booleanPreferencesKey("use_earth_symbol")
        private val KEY_USE_JUPITER_SYMBOL = booleanPreferencesKey("use_jupiter_symbol")
        private val KEY_INVERT_CAMERA_CONTROLS = booleanPreferencesKey("invert_camera_controls")
        private val KEY_NEUTRON_STAR_ROTATION = booleanPreferencesKey("neutron_star_rotation")
        private val KEY_INCLUDE_CANDIDATES = booleanPreferencesKey("include_candidates")
        private val KEY_SIMULATE_BOOKMARK_UPDATES = booleanPreferencesKey("simulate_bookmark_updates")
        private val KEY_AUTO_SYNC_ENABLED = booleanPreferencesKey("auto_sync_enabled")
        private val KEY_AUTO_SYNC_WIFI_ONLY = booleanPreferencesKey("auto_sync_wifi_only")
        private val KEY_LAST_SYNCED_AT_MILLIS = longPreferencesKey("last_synced_at_millis")
    }

    override fun observeSettings(): Flow<UserSettings> =
        context.dataStore.data.map { prefs ->
            UserSettings(
                distanceUnit = prefs[KEY_DISTANCE]?.let {
                    runCatching { DistanceUnit.valueOf(it) }.getOrNull()
                } ?: DistanceUnit.PARSECS,
                temperatureUnit = prefs[KEY_TEMPERATURE]?.let {
                    runCatching { TemperatureUnit.valueOf(it) }.getOrNull()
                } ?: TemperatureUnit.KELVIN,
                starTemperatureUnit = prefs[KEY_STAR_TEMPERATURE]?.let {
                    runCatching { TemperatureUnit.valueOf(it) }.getOrNull()
                } ?: TemperatureUnit.KELVIN,
                useTerra = prefs[KEY_USE_TERRA] ?: false,
                useNeptune = prefs[KEY_USE_NEPTUNE] ?: true,
                useJupiter = prefs[KEY_USE_JUPITER] ?: true,
                useProperNames = prefs[KEY_USE_PROPER_NAMES] ?: true,
                useEstimates = prefs[KEY_USE_ESTIMATES] ?: true,
                showDataIndicator = prefs[KEY_SHOW_DATA_INDICATOR] ?: true,
                // UI mode toggle was removed from the settings screen — the
                // implementation still works but isn't ready for production.
                // Force false here so any user with a previously-stored true
                // gets reset, and so the only path back to accessible mode is
                // to re-add the toggle when we revisit it.
                accessibleMode = false,
                showStarfield = prefs[KEY_SHOW_STARFIELD] ?: true,
                showHabitableZone = prefs[KEY_SHOW_HABITABLE_ZONE] ?: true,
                useEarthSymbol = prefs[KEY_USE_EARTH_SYMBOL] ?: true,
                useJupiterSymbol = prefs[KEY_USE_JUPITER_SYMBOL] ?: true,
                invertCameraControls = prefs[KEY_INVERT_CAMERA_CONTROLS] ?: false,
                neutronStarRotation = prefs[KEY_NEUTRON_STAR_ROTATION] ?: true,
                includeCandidates = prefs[KEY_INCLUDE_CANDIDATES] ?: true,
                // Debug-only bookmark-update simulation. Toggle was removed from
                // the settings screen — force false so the debug behaviour
                // never runs in production builds.
                simulateBookmarkUpdates = false,
                autoSyncEnabled = prefs[KEY_AUTO_SYNC_ENABLED] ?: true,
                autoSyncWifiOnly = prefs[KEY_AUTO_SYNC_WIFI_ONLY] ?: false,
                lastSyncedAtMillis = prefs[KEY_LAST_SYNCED_AT_MILLIS] ?: 0L,
            )
        }

    override suspend fun updateDistanceUnit(unit: DistanceUnit) {
        context.dataStore.edit { it[KEY_DISTANCE] = unit.name }
    }

    override suspend fun updateTemperatureUnit(unit: TemperatureUnit) {
        context.dataStore.edit { it[KEY_TEMPERATURE] = unit.name }
    }

    override suspend fun updateStarTemperatureUnit(unit: TemperatureUnit) {
        context.dataStore.edit { it[KEY_STAR_TEMPERATURE] = unit.name }
    }

    override suspend fun updateUseTerra(enabled: Boolean) {
        context.dataStore.edit { it[KEY_USE_TERRA] = enabled }
    }

    override suspend fun updateUseNeptune(enabled: Boolean) {
        context.dataStore.edit { it[KEY_USE_NEPTUNE] = enabled }
    }

    override suspend fun updateUseJupiter(enabled: Boolean) {
        context.dataStore.edit { it[KEY_USE_JUPITER] = enabled }
    }

    override suspend fun updateUseProperNames(enabled: Boolean) {
        context.dataStore.edit { it[KEY_USE_PROPER_NAMES] = enabled }
    }

    override suspend fun updateUseEstimates(enabled: Boolean) {
        context.dataStore.edit { it[KEY_USE_ESTIMATES] = enabled }
    }

    override suspend fun updateShowDataIndicator(enabled: Boolean) {
        context.dataStore.edit { it[KEY_SHOW_DATA_INDICATOR] = enabled }
    }

    override suspend fun updateAccessibleMode(enabled: Boolean) {
        context.dataStore.edit { it[KEY_ACCESSIBLE_MODE] = enabled }
    }

    override suspend fun updateShowStarfield(enabled: Boolean) {
        context.dataStore.edit { it[KEY_SHOW_STARFIELD] = enabled }
    }

    override suspend fun updateShowHabitableZone(enabled: Boolean) {
        context.dataStore.edit { it[KEY_SHOW_HABITABLE_ZONE] = enabled }
    }

    override suspend fun updateUseEarthSymbol(enabled: Boolean) {
        context.dataStore.edit { it[KEY_USE_EARTH_SYMBOL] = enabled }
    }

    override suspend fun updateUseJupiterSymbol(enabled: Boolean) {
        context.dataStore.edit { it[KEY_USE_JUPITER_SYMBOL] = enabled }
    }

    override suspend fun updateInvertCameraControls(enabled: Boolean) {
        context.dataStore.edit { it[KEY_INVERT_CAMERA_CONTROLS] = enabled }
    }

    override suspend fun updateNeutronStarRotation(enabled: Boolean) {
        context.dataStore.edit { it[KEY_NEUTRON_STAR_ROTATION] = enabled }
    }

    override suspend fun updateIncludeCandidates(enabled: Boolean) {
        context.dataStore.edit { it[KEY_INCLUDE_CANDIDATES] = enabled }
    }

    override suspend fun updateSimulateBookmarkUpdates(enabled: Boolean) {
        context.dataStore.edit { it[KEY_SIMULATE_BOOKMARK_UPDATES] = enabled }
    }

    override suspend fun updateAutoSyncEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_SYNC_ENABLED] = enabled }
    }

    override suspend fun updateAutoSyncWifiOnly(enabled: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_SYNC_WIFI_ONLY] = enabled }
    }

    override suspend fun updateLastSyncedAtMillis(epochMillis: Long) {
        context.dataStore.edit { it[KEY_LAST_SYNCED_AT_MILLIS] = epochMillis }
    }
}
