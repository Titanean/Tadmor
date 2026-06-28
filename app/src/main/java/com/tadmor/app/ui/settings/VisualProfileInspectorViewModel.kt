package com.tadmor.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tadmor.domain.classification.PlanetClassifier
import com.tadmor.domain.classification.visual.SystemContext
import com.tadmor.domain.classification.visual.VisualProfile
import com.tadmor.domain.classification.visual.VisualProfileEngine
import com.tadmor.domain.model.Planet
import com.tadmor.domain.model.Star
import com.tadmor.domain.repository.PlanetRepository
import com.tadmor.domain.usecase.ObserveSystemDetailUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class InspectorUiState(
    val query: String = "",
    val searchResults: List<Planet> = emptyList(),
    val selectedPlanet: Planet? = null,
    val selectedStar: Star? = null,
    val selectedProfile: VisualProfile? = null,
    val selectedClassLabel: String? = null,
)

@HiltViewModel
class VisualProfileInspectorViewModel @Inject constructor(
    private val repository: PlanetRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val selectedPlanetName = MutableStateFlow<String?>(null)

    private val allPlanets = repository.observeAllPlanets()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    private val allStars = repository.observeAllStars()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<InspectorUiState> = combine(
        query,
        allPlanets,
        allStars,
        selectedPlanetName,
    ) { q, planets, stars, selectedName ->
        val starMap = stars.associateBy { it.hostname }
        val results = if (q.length >= 2) {
            val lower = q.lowercase()
            planets.filter { it.name.lowercase().contains(lower) }.take(50)
        } else emptyList()

        if (selectedName != null) {
            val planet = planets.find { it.name == selectedName }
            if (planet != null) {
                val star = starMap[planet.hostname]
                val classification = PlanetClassifier.classify(planet, star)
                val siblings = planets.filter { it.hostname == planet.hostname }
                val smaValues = siblings.mapNotNull { it.semiMajorAxisAU }
                val context = if (star != null) {
                    ObserveSystemDetailUseCase.buildSystemContext(
                        star,
                        isCircumbinary = siblings.any { it.cbFlag },
                        planetCount = siblings.size,
                        innerSmaAU = smaValues.minOrNull(),
                        outerSmaAU = smaValues.maxOrNull(),
                    )
                } else {
                    ObserveSystemDetailUseCase.buildSystemContext(
                        star = Star(
                            hostname = planet.hostname,
                            spectralType = null, teffK = null, teffKLimit = null,
                            radiusSolar = null, radiusSolarLimit = null,
                            massSolar = null, massSolarLimit = null,
                            logLuminosity = null, logLuminosityLimit = null,
                            rightAscensionDeg = null, declinationDeg = null,
                            distancePc = null, planetCount = null,
                        ),
                        isCircumbinary = false,
                        planetCount = siblings.size,
                        innerSmaAU = smaValues.minOrNull(),
                        outerSmaAU = smaValues.maxOrNull(),
                    )
                }
                val profile = VisualProfileEngine.generate(planet, classification, context)
                InspectorUiState(
                    query = q,
                    searchResults = results,
                    selectedPlanet = planet,
                    selectedStar = star,
                    selectedProfile = profile,
                    selectedClassLabel = classification.fullLabel,
                )
            } else {
                InspectorUiState(query = q, searchResults = results)
            }
        } else {
            InspectorUiState(query = q, searchResults = results)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), InspectorUiState())

    fun onQueryChange(q: String) {
        query.value = q
        if (q.isEmpty()) selectedPlanetName.value = null
    }

    fun selectPlanet(name: String) {
        selectedPlanetName.value = name
    }

    fun clearSelection() {
        selectedPlanetName.value = null
    }
}
