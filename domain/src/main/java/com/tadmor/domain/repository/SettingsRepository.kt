package com.tadmor.domain.repository

import com.tadmor.domain.model.UserSettings
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    fun observeSettings(): Flow<UserSettings>
    suspend fun updateDistanceUnit(unit: com.tadmor.domain.model.DistanceUnit)
    suspend fun updateTemperatureUnit(unit: com.tadmor.domain.model.TemperatureUnit)
    suspend fun updateStarTemperatureUnit(unit: com.tadmor.domain.model.TemperatureUnit)
    suspend fun updateUseTerra(enabled: Boolean)
    suspend fun updateUseNeptune(enabled: Boolean)
    suspend fun updateUseJupiter(enabled: Boolean)
    suspend fun updateUseProperNames(enabled: Boolean)
    suspend fun updateUseEstimates(enabled: Boolean)
    suspend fun updateShowDataIndicator(enabled: Boolean)
    suspend fun updateAccessibleMode(enabled: Boolean)
    suspend fun updateShowStarfield(enabled: Boolean)
    suspend fun updateShowHabitableZone(enabled: Boolean)
    suspend fun updateUseEarthSymbol(enabled: Boolean)
    suspend fun updateUseJupiterSymbol(enabled: Boolean)
    suspend fun updateInvertCameraControls(enabled: Boolean)
    suspend fun updateNeutronStarRotation(enabled: Boolean)
    suspend fun updateIncludeCandidates(enabled: Boolean)
    suspend fun updateSimulateBookmarkUpdates(enabled: Boolean)
    suspend fun updateAutoSyncEnabled(enabled: Boolean)
    suspend fun updateAutoSyncWifiOnly(enabled: Boolean)
    suspend fun updateLastSyncedAtMillis(epochMillis: Long)
}
