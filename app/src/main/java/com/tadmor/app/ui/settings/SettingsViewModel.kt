package com.tadmor.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tadmor.domain.model.DistanceUnit
import com.tadmor.domain.model.TemperatureUnit
import com.tadmor.domain.model.UserSettings
import com.tadmor.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<UserSettings> = settingsRepository.observeSettings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserSettings())

    fun setDistanceUnit(unit: DistanceUnit) {
        viewModelScope.launch { settingsRepository.updateDistanceUnit(unit) }
    }

    fun setTemperatureUnit(unit: TemperatureUnit) {
        viewModelScope.launch { settingsRepository.updateTemperatureUnit(unit) }
    }

    fun setStarTemperatureUnit(unit: TemperatureUnit) {
        viewModelScope.launch { settingsRepository.updateStarTemperatureUnit(unit) }
    }

    fun setUseTerra(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateUseTerra(enabled) }
    }

    fun setUseNeptune(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateUseNeptune(enabled) }
    }

    fun setUseJupiter(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateUseJupiter(enabled) }
    }

    fun setUseProperNames(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateUseProperNames(enabled) }
    }

    fun setUseEstimates(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateUseEstimates(enabled) }
    }

    fun setShowDataIndicator(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateShowDataIndicator(enabled) }
    }

    fun setUseEarthSymbol(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateUseEarthSymbol(enabled) }
    }

    fun setUseJupiterSymbol(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateUseJupiterSymbol(enabled) }
    }

    fun setInvertCameraControls(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateInvertCameraControls(enabled) }
    }

    fun setNeutronStarRotation(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateNeutronStarRotation(enabled) }
    }

    fun setIncludeCandidates(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateIncludeCandidates(enabled) }
    }

    fun setAutoSyncEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateAutoSyncEnabled(enabled) }
    }

    fun setAutoSyncWifiOnly(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateAutoSyncWifiOnly(enabled) }
    }
}
