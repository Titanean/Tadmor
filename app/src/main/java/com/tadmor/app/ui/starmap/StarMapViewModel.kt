package com.tadmor.app.ui.starmap

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tadmor.app.gl.CameraController
import com.tadmor.app.gl.GLBridge
import com.tadmor.app.gl.RayCaster
import com.tadmor.app.ui.theme.TeffColor
import com.tadmor.domain.classification.CompositionClass
import com.tadmor.domain.classification.extractLuminosityClass
import com.tadmor.domain.classification.PlanetClassifier
import com.tadmor.app.ui.system.OrbitalState
import com.tadmor.app.ui.system.buildOrbitalState
import com.tadmor.domain.model.DistanceUnit
import com.tadmor.domain.model.Planet
import com.tadmor.domain.model.ProperNames
import com.tadmor.domain.model.Star
import com.tadmor.domain.model.effectiveSpectralType
import com.tadmor.domain.model.isPulsar
import com.tadmor.domain.repository.PlanetRepository
import com.tadmor.domain.repository.SettingsRepository
import com.tadmor.domain.usecase.ObserveStarMapUseCase
import com.tadmor.domain.usecase.ObserveSystemDetailUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class SelectedStarInfo(
    val hostname: String,
    val spectralType: String?,
    val isEstimatedSpectral: Boolean = false,
    val distancePc: Double?,
    val planetCount: Int?,
    val colorRgb: FloatArray,
    val isSol: Boolean = false,
    val worldPos: FloatArray = floatArrayOf(0f, 0f, 0f),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SelectedStarInfo) return false
        return hostname == other.hostname
    }

    override fun hashCode(): Int = hostname.hashCode()
}

data class StarSearchResult(
    val hostname: String,
    val properName: String?,
    val spectralType: String?,
    val spectralClass: String?,
    val distancePc: Double?,
    val planetCount: Int?,
)

enum class StarMapMode { MAP, ORBITAL }

/**
 * Pre-computed starfield for the orbital view background.
 * Directions are pre-rotated and scaled to a sphere radius of [STARFIELD_RADIUS].
 */
data class StarfieldData(
    val positions: FloatArray,  // x,y,z per star — pre-rotated unit dirs × radius
    val colors: FloatArray,     // r,g,b per star — dimmed by distance
    val sizes: FloatArray,      // point size per star
    val count: Int,
    val solPosition: FloatArray, // Sol x,y,z on the sphere
    val solColor: FloatArray,
    val solSize: Float,
) {
    companion object {
        const val STARFIELD_RADIUS = 30f

        /** Sol: G2V, 5778 K, 1.0 R☉ — same pipeline as every other star. */
        private val SOL_TEFF_COLOR = TeffColor.fromTeff(5778.0)
        val SOL_COLOR = floatArrayOf(SOL_TEFF_COLOR.red, SOL_TEFF_COLOR.green, SOL_TEFF_COLOR.blue)
        val SOL_SIZE = (2.5f + kotlin.math.ln(1.0f + 1.0f) * 2.0f).coerceIn(2.0f, 14.0f)
    }
}

data class StarMapUiState(
    val mode: StarMapMode = StarMapMode.MAP,
    val starCount: Int = 0,
    val filteredStarCount: Int = 0,
    val selectedStar: SelectedStarInfo? = null,
    val distanceUnit: DistanceUnit = DistanceUnit.PARSECS,
    val useEstimates: Boolean = true,
    val useProperNames: Boolean = false,
    val useTerra: Boolean = true,
    val useNeptune: Boolean = true,
    val useJupiter: Boolean = true,
    val searchQuery: TextFieldValue = TextFieldValue(),
    val searchResults: List<StarSearchResult> = emptyList(),
    val isSearchOpen: Boolean = false,
    val filterState: StarMapFilterState = StarMapFilterState(),
    val orbitalState: OrbitalState? = null,
    val orbitalStarDisplayName: String = "",
    val orbitalHostname: String = "",
    val showStarfield: Boolean = true,
    val showHabitableZone: Boolean = true,
    val invertCameraControls: Boolean = false,
)

@HiltViewModel
class StarMapViewModel @Inject constructor(
    private val observeStarMapUseCase: ObserveStarMapUseCase,
    private val planetRepository: PlanetRepository,
    private val settingsRepository: SettingsRepository,
    private val observeSystemDetailUseCase: ObserveSystemDetailUseCase,
) : ViewModel() {

    val bridge = GLBridge(StarMapParams())

    private val selectedStar = MutableStateFlow<SelectedStarInfo?>(null)
    private val starCount = MutableStateFlow(0)
    private val filteredStarCount = MutableStateFlow(0)
    private val searchQuery = MutableStateFlow(TextFieldValue())
    private val isSearchOpen = MutableStateFlow(false)
    private val filterState = MutableStateFlow(StarMapFilterState())

    // Orbital view state
    private val mode = MutableStateFlow(StarMapMode.MAP)
    private val orbitalState = MutableStateFlow<OrbitalState?>(null)
    private val orbitalStarDisplayName = MutableStateFlow("")
    private val orbitalHostname = MutableStateFlow("")
    private var orbitalLoadJob: Job? = null
    val starfieldData = MutableStateFlow<StarfieldData?>(null)

    // Full star data (unfiltered) — used for search
    private var allStars: List<Star> = emptyList()
    private var allPositions = FloatArray(0)
    private var allColors = FloatArray(0)
    private var allSizes = FloatArray(0)

    // Filtered star data — used for tap handling (GL rendering uses all stars
    // with a per-vertex alpha mask so filter changes can animate).
    private var stars: List<Star> = emptyList()
    private var positions = FloatArray(0)
    private var colors = FloatArray(0)
    private var sizes = FloatArray(0)
    private var bufferVersion = 0
    private var filterVersion = 0

    // Planet composition classes per star hostname
    private var starCompositions: Map<String, Set<CompositionClass>> = emptyMap()

    val uiState: StateFlow<StarMapUiState> = combine(
        combine(starCount, filteredStarCount, selectedStar) { count, filtered, star ->
            Triple(count, filtered, star)
        },
        settingsRepository.observeSettings(),
        combine(searchQuery, isSearchOpen) { q, open -> q to open },
        combine(filterState, mode, orbitalState, orbitalStarDisplayName, orbitalHostname, ::OrbitalCombined),
    ) { (count, filtered, star), settings, (query, searchOpen), orbital ->
        val results = if (searchOpen && query.text.length >= 2) {
            searchStars(query.text)
        } else {
            emptyList()
        }
        StarMapUiState(
            mode = orbital.mode,
            starCount = count,
            filteredStarCount = filtered,
            selectedStar = star,
            distanceUnit = settings.distanceUnit,
            useEstimates = settings.useEstimates,
            useProperNames = settings.useProperNames,
            useTerra = settings.useTerra,
            useNeptune = settings.useNeptune,
            useJupiter = settings.useJupiter,
            searchQuery = query,
            searchResults = results,
            isSearchOpen = searchOpen,
            filterState = orbital.filters,
            orbitalState = orbital.orbitalState,
            orbitalStarDisplayName = orbital.starDisplayName,
            orbitalHostname = orbital.hostname,
            showStarfield = settings.showStarfield,
            showHabitableZone = settings.showHabitableZone,
            invertCameraControls = settings.invertCameraControls,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StarMapUiState())

    init {
        // Observe stars and planets, build buffers
        viewModelScope.launch {
            combine(
                observeStarMapUseCase(),
                planetRepository.observeAllPlanets(),
            ) { starList, planets -> starList to planets }
                .collect { (starList, planets) ->
                    buildCompositionMap(starList, planets)
                    buildFullBuffers(starList)
                    applyFilters()
                }
        }

        // React to filter changes
        viewModelScope.launch {
            filterState.collect {
                if (allStars.isNotEmpty()) {
                    applyFilters()
                }
            }
        }
    }

    private fun buildCompositionMap(starList: List<Star>, planets: List<Planet>) {
        val starMap = starList.associateBy { it.hostname }
        starCompositions = planets.groupBy { it.hostname }
            .mapValues { (hostname, planetList) ->
                val star = starMap[hostname]
                planetList.mapNotNull { planet ->
                    PlanetClassifier.classify(planet, star).compositionClass
                }.toSet()
            }
    }

    private suspend fun buildFullBuffers(starList: List<Star>) {
        withContext(Dispatchers.Default) {
            val count = starList.size
            val pos = FloatArray(count * 3)
            val col = FloatArray(count * 3)
            val sz = FloatArray(count)

            for (i in starList.indices) {
                val star = starList[i]
                val raDeg = star.rightAscensionDeg!!
                val decDeg = star.declinationDeg!!
                val distPc = star.distancePc!!

                // Linear distance: divide by 30 for large spread
                val d = (distPc / 30.0).toFloat()

                // RA/Dec → Cartesian (RA in hours-equivalent radians, Dec in radians)
                val raRad = raDeg * DEG_TO_RAD
                val decRad = decDeg * DEG_TO_RAD
                val cosD = cos(decRad).toFloat()
                val sinD = sin(decRad).toFloat()
                val cosR = cos(raRad).toFloat()
                val sinR = sin(raRad).toFloat()

                pos[i * 3] = d * cosD * cosR
                pos[i * 3 + 1] = d * sinD
                pos[i * 3 + 2] = d * cosD * sinR

                // Color from Teff / spectral class (pulsars resolved to Q → harsh blue)
                val color = TeffColor.forStar(star.teffK, star.effectiveSpectralType())
                if (color != null) {
                    col[i * 3] = color.red
                    col[i * 3 + 1] = color.green
                    col[i * 3 + 2] = color.blue
                } else {
                    // Muted blue-gray fallback — matches textTertiary (#6B7A8F)
                    col[i * 3] = 0.42f
                    col[i * 3 + 1] = 0.478f
                    col[i * 3 + 2] = 0.561f
                }

                // Point size from stellar radius (log-scaled for visual range)
                val r = star.radiusSolar ?: 1.0
                sz[i] = (2.5f + ln(1.0 + r).toFloat() * 2.0f).coerceIn(2.0f, 14.0f)
            }

            allStars = starList
            allPositions = pos
            allColors = col
            allSizes = sz
            starCount.value = count
            // New star set → renderer must rebuild its static VBO. Filter fade
            // state also resets (see renderer: bufferVersion change re-snaps
            // currentAlpha so stars don't fade in from 0 on first load).
            bufferVersion++
        }
    }

    private suspend fun applyFilters() {
        withContext(Dispatchers.Default) {
            val filters = filterState.value
            val hasFilters = filters.activeCount > 0

            // Full-size alpha mask parallel to allStars. Filtered-out stars get
            // 0 and will fade out in the renderer; matching stars get 1.
            val alpha = FloatArray(allStars.size)

            if (!hasFilters) {
                stars = allStars
                positions = allPositions
                colors = allColors
                sizes = allSizes
                for (i in allStars.indices) alpha[i] = 1f
                filteredStarCount.value = allStars.size
            } else {
                val indices = allStars.indices.filter { i ->
                    matchesFilter(allStars[i], filters)
                }
                val count = indices.size
                val pos = FloatArray(count * 3)
                val col = FloatArray(count * 3)
                val sz = FloatArray(count)
                val filteredStars = ArrayList<Star>(count)

                for ((j, i) in indices.withIndex()) {
                    filteredStars.add(allStars[i])
                    pos[j * 3] = allPositions[i * 3]
                    pos[j * 3 + 1] = allPositions[i * 3 + 1]
                    pos[j * 3 + 2] = allPositions[i * 3 + 2]
                    col[j * 3] = allColors[i * 3]
                    col[j * 3 + 1] = allColors[i * 3 + 1]
                    col[j * 3 + 2] = allColors[i * 3 + 2]
                    sz[j] = allSizes[i]
                    alpha[i] = 1f
                }

                stars = filteredStars
                positions = pos
                colors = col
                sizes = sz
                filteredStarCount.value = count
            }

            val solVisible = isSolVisible()

            filterVersion++
            // Send the FULL star set to the renderer every time — filter
            // changes only update targetAlpha, letting the fade animate in
            // the renderer instead of popping on VBO rebuild.
            bridge.post(
                StarMapParams(
                    positions = allPositions,
                    colors = allColors,
                    sizes = allSizes,
                    targetAlpha = alpha,
                    count = allStars.size,
                    showSol = solVisible,
                    bufferVersion = bufferVersion,
                    filterVersion = filterVersion,
                )
            )
        }
    }

    private fun matchesFilter(star: Star, filters: StarMapFilterState): Boolean {
        // Spectral class filter (OR within group)
        if (filters.spectralClasses.isNotEmpty()) {
            val starClass = if (star.isPulsar()) "Q"
                else spectralClassLetter(star.spectralType)
                    ?: estimateSpectralFromTeff(star.teffK)
            if (starClass == null || starClass !in filters.spectralClasses) return false
        }

        // Luminosity class filter (OR within group)
        if (filters.luminosityClasses.isNotEmpty()) {
            val lumClass = extractLuminosityClass(star.spectralType)
            if (lumClass == null || lumClass !in filters.luminosityClasses) return false
        }

        // Planet count filter (minimum)
        if (filters.minPlanets > 0) {
            val count = star.planetCount ?: 0
            if (count < filters.minPlanets) return false
        }

        // Planet class filter (OR within group)
        if (filters.planetClasses.isNotEmpty()) {
            val compositions = starCompositions[star.hostname] ?: emptySet()
            val matches = filters.planetClasses.any { className ->
                when (className) {
                    "Terrestrial" -> CompositionClass.TERRA in compositions
                    "Neptune" -> CompositionClass.NEPTUNE in compositions
                    "Jupiter" -> CompositionClass.JUPITER in compositions
                    else -> false
                }
            }
            if (!matches) return false
        }

        return true
    }

    fun onStarTapped(
        screenX: Float,
        screenY: Float,
        viewportWidth: Int,
        viewportHeight: Int,
        cameraController: CameraController,
    ) {
        val index = if (positions.isNotEmpty()) {
            RayCaster.pickStar(
                screenX = screenX,
                screenY = screenY,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                viewMatrix = cameraController.viewMatrix,
                projectionMatrix = cameraController.projectionMatrix,
                starPositions = positions,
                starCount = stars.size,
            )
        } else -1

        // Also check Sol at origin (0,0,0) — only if Sol passes current filters
        val solVisible = isSolVisible()
        val solScreenPos = if (solVisible) RayCaster.projectToScreen(
            worldX = 0f, worldY = 0f, worldZ = 0f,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            viewMatrix = cameraController.viewMatrix,
            projectionMatrix = cameraController.projectionMatrix,
        ) else null
        val solDist2 = if (solScreenPos != null) {
            val dx = solScreenPos.x - screenX
            val dy = solScreenPos.y - screenY
            dx * dx + dy * dy
        } else Float.MAX_VALUE

        // Check if a catalog star was hit, and its screen distance
        val starDist2 = if (index >= 0) {
            val starScreen = RayCaster.projectToScreen(
                worldX = positions[index * 3],
                worldY = positions[index * 3 + 1],
                worldZ = positions[index * 3 + 2],
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                viewMatrix = cameraController.viewMatrix,
                projectionMatrix = cameraController.projectionMatrix,
            )
            if (starScreen != null) {
                val dx = starScreen.x - screenX
                val dy = starScreen.y - screenY
                dx * dx + dy * dy
            } else Float.MAX_VALUE
        } else Float.MAX_VALUE

        val thresholdPx = 40f
        val solTapped = solDist2 < thresholdPx * thresholdPx && solDist2 < starDist2

        if (solTapped) {
            selectedStar.value = SelectedStarInfo(
                hostname = "Sol",
                spectralType = "G2V",
                distancePc = null,
                planetCount = 8,
                colorRgb = floatArrayOf(1.0f, 0.95f, 0.8f),
                isSol = true,
                worldPos = floatArrayOf(0f, 0f, 0f),
            )
        } else if (index >= 0) {
            val star = stars[index]
            val color = floatArrayOf(
                colors[index * 3],
                colors[index * 3 + 1],
                colors[index * 3 + 2],
            )
            val catalogSpectral = resolveSpectralType(star.spectralType)
            val isEstimated: Boolean
            val displaySpectral: String?
            if (catalogSpectral != null) {
                displaySpectral = catalogSpectral
                isEstimated = false
            } else if (star.isPulsar()) {
                displaySpectral = "Q"
                isEstimated = false
            } else {
                displaySpectral = estimateSpectralFromTeff(star.teffK)
                isEstimated = displaySpectral != null
            }

            selectedStar.value = SelectedStarInfo(
                hostname = star.hostname,
                spectralType = displaySpectral,
                isEstimatedSpectral = isEstimated,
                distancePc = star.distancePc!!,
                planetCount = star.planetCount,
                colorRgb = color,
                worldPos = floatArrayOf(
                    positions[index * 3],
                    positions[index * 3 + 1],
                    positions[index * 3 + 2],
                ),
            )
        } else {
            onDismissTooltip()
        }
    }

    fun onDismissTooltip() {
        selectedStar.value = null
    }

    /** Returns true if a star is currently selected (tooltip is open). */
    fun hasSelectedStar(): Boolean = selectedStar.value != null

    /** Returns true if the search panel is open. */
    fun isSearchOpen(): Boolean = isSearchOpen.value

    fun onSearchQueryChanged(query: TextFieldValue) {
        searchQuery.value = query
    }

    fun onToggleSearch() {
        isSearchOpen.value = !isSearchOpen.value
    }

    /** Collapse search panel, preserving query and scroll state. */
    fun onCollapseSearch() {
        isSearchOpen.value = false
    }

    /** Close search and clear query. */
    fun onCloseSearch() {
        isSearchOpen.value = false
        searchQuery.value = TextFieldValue()
    }

    /** Update filter state — triggers buffer rebuild via filterState collector. */
    fun onFilterChange(state: StarMapFilterState) {
        filterState.value = state
    }

    /** Select a star by hostname from search results. Uses allStars so filtered-out stars are still selectable. */
    fun onSearchResultSelected(hostname: String) {
        val index = allStars.indexOfFirst { it.hostname == hostname }
        if (index >= 0) {
            val star = allStars[index]
            val color = floatArrayOf(
                allColors[index * 3],
                allColors[index * 3 + 1],
                allColors[index * 3 + 2],
            )
            val catalogSpectral = resolveSpectralType(star.spectralType)
            val isEstimated: Boolean
            val displaySpectral: String?
            if (catalogSpectral != null) {
                displaySpectral = catalogSpectral
                isEstimated = false
            } else if (star.isPulsar()) {
                displaySpectral = "Q"
                isEstimated = false
            } else {
                displaySpectral = estimateSpectralFromTeff(star.teffK)
                isEstimated = displaySpectral != null
            }

            selectedStar.value = SelectedStarInfo(
                hostname = star.hostname,
                spectralType = displaySpectral,
                isEstimatedSpectral = isEstimated,
                distancePc = star.distancePc!!,
                planetCount = star.planetCount,
                colorRgb = color,
                worldPos = floatArrayOf(
                    allPositions[index * 3],
                    allPositions[index * 3 + 1],
                    allPositions[index * 3 + 2],
                ),
            )
        }
        onCloseSearch()
    }

    private fun searchStars(query: String): List<StarSearchResult> {
        val q = query.lowercase()
        return allStars
            .filter { star ->
                star.hostname.lowercase().contains(q) ||
                    star.hdName?.lowercase()?.contains(q) == true ||
                    star.hipName?.lowercase()?.contains(q) == true ||
                    star.ticId?.lowercase()?.contains(q) == true ||
                    ProperNames.forStar(star.hostname)?.lowercase()?.contains(q) == true
            }
            .take(20)
            .map { star ->
                val catalogSpectral = resolveSpectralType(star.spectralType)
                val pulsarOverride = if (star.isPulsar()) "Q" else null
                val classLetter = spectralClassLetter(star.spectralType)
                    ?: pulsarOverride
                    ?: estimateSpectralFromTeff(star.teffK)
                StarSearchResult(
                    hostname = star.hostname,
                    properName = ProperNames.forStar(star.hostname),
                    spectralType = catalogSpectral ?: pulsarOverride ?: estimateSpectralFromTeff(star.teffK),
                    spectralClass = classLetter,
                    distancePc = star.distancePc,
                    planetCount = star.planetCount,
                )
            }
    }

    /** Check if Sol passes the current star map filters. Sol: G2V, 8 planets, Terrestrial + Jupiter. */
    private fun isSolVisible(): Boolean {
        val filters = filterState.value
        if (filters.activeCount == 0) return true
        if (filters.spectralClasses.isNotEmpty() && "G" !in filters.spectralClasses) return false
        if (filters.luminosityClasses.isNotEmpty() && "V" !in filters.luminosityClasses) return false
        if (filters.minPlanets > 8) return false
        if (filters.planetClasses.isNotEmpty() && filters.planetClasses.none {
                it in setOf("Terrestrial", "Jupiter")
            }) return false
        return true
    }

    /**
     * Start loading orbital data for [hostname] without switching mode yet.
     * Lets the UI run a transition animation while data loads in the background;
     * call [commitOrbital] once the animation completes to flip mode to ORBITAL.
     */
    fun prepareOrbitalLoad(hostname: String) {
        orbitalHostname.value = hostname
        // Keep selectedStar — tooltip reappears when returning to map mode

        // Build background starfield for the orbital view
        viewModelScope.launch { buildStarfieldForSystem(hostname) }

        // Load system detail and build orbital state
        orbitalLoadJob?.cancel()
        orbitalLoadJob = viewModelScope.launch {
            val settings = settingsRepository.observeSettings().first()
            val properName = ProperNames.forStar(hostname)
            orbitalStarDisplayName.value = if (settings.useProperNames && properName != null) {
                properName
            } else {
                hostname
            }

            observeSystemDetailUseCase(hostname).collect { detail ->
                if (detail != null) {
                    val currentSettings = settingsRepository.observeSettings().first()
                    orbitalState.value = buildOrbitalState(detail, currentSettings)
                }
            }
        }
    }

    /** Flip mode to ORBITAL. Safe to call while orbital data is still loading. */
    fun commitOrbital() {
        mode.value = StarMapMode.ORBITAL
    }

    /** Enter orbital view for a star system in one step (no transition animation). */
    fun onViewSystem(hostname: String) {
        prepareOrbitalLoad(hostname)
        commitOrbital()
    }

    fun onToggleStarfield() {
        viewModelScope.launch {
            val current = settingsRepository.observeSettings().first().showStarfield
            settingsRepository.updateShowStarfield(!current)
        }
    }

    fun onToggleHabitableZone() {
        viewModelScope.launch {
            val current = settingsRepository.observeSettings().first().showHabitableZone
            settingsRepository.updateShowHabitableZone(!current)
        }
    }

    /** Return to star map from orbital view. */
    fun onBackFromOrbital() {
        orbitalLoadJob?.cancel()
        orbitalLoadJob = null
        mode.value = StarMapMode.MAP
        orbitalState.value = null
        orbitalStarDisplayName.value = ""
        orbitalHostname.value = ""
        starfieldData.value = null
    }

    /**
     * Builds a background starfield for the orbital view: all catalog stars
     * positioned relative to [hostname], projected onto a sphere, with
     * distance-based dimming and system orientation rotation applied.
     */
    private suspend fun buildStarfieldForSystem(hostname: String) {
        // Get the system's orbital inclination (use first measured, non-limit-flagged value)
        val planets = planetRepository.observePlanetsByHostname(hostname).first()
        val systemInclDeg: Double? = planets
            .filter { it.inclinationLimit == null || it.inclinationLimit == 0 }
            .mapNotNull { it.inclination }
            .firstOrNull()

        withContext(Dispatchers.Default) {
            val starList = allStars
            val aPos = allPositions
            val aCol = allColors
            val aSz = allSizes
            if (starList.isEmpty()) return@withContext

            // Find current system's star
            val idx = starList.indexOfFirst { it.hostname == hostname }
            if (idx < 0) return@withContext
            val star = starList[idx]

            if (star.rightAscensionDeg == null || star.declinationDeg == null ||
                star.distancePc == null) return@withContext

            val R = StarfieldData.STARFIELD_RADIUS
            // Use GL-scaled positions for direction (same direction, just / 30)
            val glScale = 30.0f // allPositions uses distPc / 30
            val glCx = aPos[idx * 3]
            val glCy = aPos[idx * 3 + 1]
            val glCz = aPos[idx * 3 + 2]

            // Build orientation rotation:
            // Step 1: Rotate starfield so Sol sits at the correct elevation above
            //   the orbital plane (XZ). Elevation = 90° - inclination.
            //   Three cases for inclination:
            //     a) Measured → use catalog value
            //     b) Transit planet, no measurement → assume ~90° with ±5° jitter
            //     c) Non-transit, no measurement → deterministic random 15–75° elevation
            // Step 2: Apply a deterministic random Y rotation (unknown position angle).
            val a00: Float; val a01: Float; val a02: Float
            val a10: Float; val a11: Float; val a12: Float
            val a20: Float; val a21: Float; val a22: Float

            // Determine effective inclination for starfield alignment:
            //  - Measured inclination → use it directly
            //  - Transit planet but no measured inclination → ~90° (Sol near orbital plane, ±5°)
            //  - Non-transit / no inclination → deterministic random (avoid edge-on)
            val hasTransit = planets.any {
                it.discoveryMethod.equals("Transit", ignoreCase = true)
            }
            val effectiveInclDeg: Double = if (systemInclDeg != null) {
                systemInclDeg
            } else if (hasTransit) {
                // Transit geometry implies ~90°; add small deterministic jitter ±5°
                val jitterHash = abs(hostname.hashCode().toLong() * 31 + 7)
                val jitter = (jitterHash % 1000) / 100.0 - 5.0 // -5.0 to +4.99°
                90.0 + jitter
            } else {
                // Random elevation 15–75° → inclination 15–75° (avoids edge-on and top-down)
                val elevHash = abs(hostname.hashCode().toLong() * 31 + 5)
                val elevDeg = 15.0 + (elevHash % 6000) / 100.0 // 15.00–74.99°
                90.0 - elevDeg
            }

            // Sol's raw direction from this system:
            var rawSolDx = -glCx; var rawSolDy = -glCy; var rawSolDz = -glCz
            val rawSolLen = sqrt((rawSolDx * rawSolDx + rawSolDy * rawSolDy + rawSolDz * rawSolDz).toDouble()).toFloat()
            if (rawSolLen > 0) { rawSolDx /= rawSolLen; rawSolDy /= rawSolLen; rawSolDz /= rawSolLen }

            val solXZ = sqrt((rawSolDx * rawSolDx + rawSolDz * rawSolDz).toDouble()).toFloat()
            val currentElev = kotlin.math.atan2(rawSolDy.toDouble(), solXZ.toDouble())
            val targetElev = (90.0 - effectiveInclDeg) * DEG_TO_RAD
            val alignAngle = targetElev - currentElev

            // Rotation axis: perpendicular to Sol's XZ projection, in the XZ plane
            val axLen = if (solXZ > 0.001f) solXZ else 1f
            val axX = (rawSolDz / axLen)
            val axZ = (-rawSolDx / axLen)

            // Rodrigues rotation around axis (axX, 0, axZ)
            val cosA = cos(alignAngle).toFloat(); val sinA = sin(alignAngle).toFloat()
            val ux = axX; val uz = axZ
            val omc = 1f - cosA
            a00 = cosA + ux * ux * omc;  a01 = uz * sinA;  a02 = ux * uz * omc
            a10 = -uz * sinA;             a11 = cosA;       a12 = ux * sinA
            a20 = ux * uz * omc;          a21 = -ux * sinA; a22 = cosA + uz * uz * omc

            // Deterministic random Y rotation (unknown position angle on sky)
            val paHash = abs(hostname.hashCode().toLong() * 31 + 3)
            val paDeg = (paHash % 36000) / 100.0
            val paRad = paDeg * DEG_TO_RAD
            val cosP = cos(paRad).toFloat(); val sinP = sin(paRad).toFloat()

            // Apparent flux for each star as seen from this system:
            // F = L / d²  where L in solar luminosities, d in parsecs.
            // Luminosity: prefer 10^logL, else R²·(T/5778)⁴, else 1.0
            val T_SUN = 5778.0
            fun luminosity(s: Star): Double {
                val logL = s.logLuminosity
                if (logL != null) return 10.0.pow(logL)
                val r = s.radiusSolar ?: 1.0
                val t = s.teffK ?: T_SUN
                return r * r * (t / T_SUN).pow(4.0)
            }

            // First pass: compute apparent flux per star
            // F = L / d² — absolute, no normalization between stars
            val fluxes = DoubleArray(starList.size)
            for (i in starList.indices) {
                if (i == idx) continue
                val dx = aPos[i * 3] - glCx
                val dy = aPos[i * 3 + 1] - glCy
                val dz = aPos[i * 3 + 2] - glCz
                val d2 = (dx * dx + dy * dy + dz * dz).toDouble() * (glScale * glScale) // pc²
                if (d2 > 0.0001) {
                    fluxes[i] = luminosity(starList[i]) / d2
                }
            }
            // Sol flux from this system
            val solD2 = (glCx * glCx + glCy * glCy + glCz * glCz).toDouble() * (glScale * glScale)
            val solFlux = if (solD2 > 0.0001) 1.0 / solD2 else 0.0 // Sol L = 1.0

            // Absolute apparent magnitude → brightness.
            // Reference: 1 solar luminosity at 10 pc (absolute magnitude of the Sun ≈ +4.83).
            // flux_ref = 1.0 / (10²) = 0.01. A star with this flux gets brightness 1.0.
            // Dimmer stars fade via: brightness = (flux / flux_ref)^gamma, clamped [floor, 1].
            val FLUX_REF = 0.01 // 1 L☉ at 10 pc
            val GAMMA = 0.3
            val MIN_BRIGHT = 0.15f
            fun brightness(flux: Double): Float {
                if (flux <= 0.0) return MIN_BRIGHT
                return (flux / FLUX_REF).pow(GAMMA).toFloat().coerceIn(MIN_BRIGHT, 1f)
            }

            val outCount = starList.size - 1
            val sfPos = FloatArray(outCount * 3)
            val sfCol = FloatArray(outCount * 3)
            val sfSz = FloatArray(outCount)

            var j = 0
            for (i in starList.indices) {
                if (i == idx) continue

                var dx = aPos[i * 3] - glCx
                var dy = aPos[i * 3 + 1] - glCy
                var dz = aPos[i * 3 + 2] - glCz
                val glDist = sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
                if (glDist < 0.0001f) { sfSz[j] = 0f; j++; continue }

                // Normalise to unit direction
                dx /= glDist; dy /= glDist; dz /= glDist

                // Apply alignment rotation (Sol → XZ plane), then Y rotation (PA)
                val ax = a00 * dx + a01 * dy + a02 * dz
                val ay = a10 * dx + a11 * dy + a12 * dz
                val az = a20 * dx + a21 * dy + a22 * dz
                val x2 = ax * cosP + az * sinP
                val y2 = ay
                val z2 = -ax * sinP + az * cosP

                sfPos[j * 3] = x2 * R
                sfPos[j * 3 + 1] = y2 * R
                sfPos[j * 3 + 2] = z2 * R

                // Apparent-flux dimming on color and size
                val b = brightness(fluxes[i])
                sfCol[j * 3] = aCol[i * 3] * b
                sfCol[j * 3 + 1] = aCol[i * 3 + 1] * b
                sfCol[j * 3 + 2] = aCol[i * 3 + 2] * b

                sfSz[j] = aSz[i] * 2.5f * b
                j++
            }

            // Sol: compute direction then apply same alignment + PA rotations
            var solDx = -glCx; var solDy = -glCy; var solDz = -glCz
            val solGlDist = sqrt((solDx * solDx + solDy * solDy + solDz * solDz).toDouble()).toFloat()
            if (solGlDist > 0) { solDx /= solGlDist; solDy /= solGlDist; solDz /= solGlDist }
            val sax = a00 * solDx + a01 * solDy + a02 * solDz
            val say = a10 * solDx + a11 * solDy + a12 * solDz
            val saz = a20 * solDx + a21 * solDy + a22 * solDz
            val sx2 = sax * cosP + saz * sinP
            val sy2 = say
            val sz22 = -sax * sinP + saz * cosP

            // Sol dimmed by the same apparent-flux pipeline
            val solB = brightness(solFlux)
            val solBaseColor = StarfieldData.SOL_COLOR
            starfieldData.value = StarfieldData(
                positions = sfPos,
                colors = sfCol,
                sizes = sfSz,
                count = j,
                solPosition = floatArrayOf(sx2 * R, sy2 * R, sz22 * R),
                solColor = floatArrayOf(
                    solBaseColor[0] * solB,
                    solBaseColor[1] * solB,
                    solBaseColor[2] * solB,
                ),
                solSize = StarfieldData.SOL_SIZE * 2.5f * solB,
            )
        }
    }

    fun setVisible(visible: Boolean) {
        bridge.post(bridge.consume().copy(isVisible = visible))
    }

    /** Cleans up catalog spectral type for display, keeping the full classification. */
    private fun resolveSpectralType(spectralType: String?): String? {
        val sp = spectralType?.trim() ?: return null
        val upper = sp.uppercase()
        if (upper.startsWith("D") && upper.length >= 2 && upper[1] in "ABCOZQ") return "D"
        val first = upper.firstOrNull()
        if (first != null && first in "OBAFGKMLTY") return sp
        return null
    }

    /** Extracts just the single-letter spectral class for color lookup. */
    private fun spectralClassLetter(spectralType: String?): String? {
        val sp = spectralType?.trim()?.uppercase() ?: return null
        if (sp.startsWith("D") && sp.length >= 2 && sp[1] in "ABCOZQ") return "D"
        val first = sp.firstOrNull()
        if (first != null && first in "OBAFGKMLTY") return first.toString()
        return null
    }

    /** Estimates spectral class from effective temperature. */
    private fun estimateSpectralFromTeff(teffK: Double?): String? {
        if (teffK == null) return null
        return when {
            teffK >= 30000 -> "O"
            teffK >= 10000 -> "B"
            teffK >= 7500 -> "A"
            teffK >= 6000 -> "F"
            teffK >= 5200 -> "G"
            teffK >= 3700 -> "K"
            teffK >= 2400 -> "M"
            teffK >= 1300 -> "L"
            teffK >= 300 -> "T"
            else -> "Y"
        }
    }

    private data class OrbitalCombined(
        val filters: StarMapFilterState,
        val mode: StarMapMode,
        val orbitalState: OrbitalState?,
        val starDisplayName: String,
        val hostname: String,
    )

    companion object {
        private const val DEG_TO_RAD = (PI / 180.0)
    }
}
