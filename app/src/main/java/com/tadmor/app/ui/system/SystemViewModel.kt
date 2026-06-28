package com.tadmor.app.ui.system

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tadmor.domain.model.Disposition
import com.tadmor.domain.model.PlanetDiff
import com.tadmor.domain.model.Star
import com.tadmor.domain.model.SystemDetail
import com.tadmor.domain.model.SystemPlanetEntry
import com.tadmor.domain.model.UserSettings
import com.tadmor.domain.model.computeDiff
import com.tadmor.domain.model.toSnapshot
import com.tadmor.domain.repository.BookmarkRepository
import com.tadmor.domain.repository.SettingsRepository
import com.tadmor.domain.usecase.ObserveSystemDetailUseCase
import com.tadmor.domain.usecase.SearchStarsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SystemMode { SEARCH, DETAIL, PLANET }

data class SystemUiState(
    val mode: SystemMode = SystemMode.SEARCH,
    val searchQuery: String = "",
    val searchResults: List<Star> = emptyList(),
    val systemDetail: SystemDetail? = null,
    val selectedPlanetName: String? = null,
    val fromCatalog: Boolean = false,
    val settings: UserSettings = UserSettings(),
    val orbitalState: OrbitalState? = null,
    val starGlobeMode: GlobeMode = GlobeMode.HALF,
    /** Set of bookmarked planet keys; used by the planet info page to
     *  render its bookmark toggle state and by the System page candidate
     *  cards to mirror the catalog's bookmark UI. */
    val bookmarkedKeys: Set<String> = emptySet(),
    /** Diff for the currently-selected planet, when bookmarked AND its
     *  current values differ from the stored snapshot. Null otherwise. */
    val selectedPlanetDiff: PlanetDiff? = null,
    /** Per-planet update counts keyed on planet name, used by the system
     *  page planet cards to render the gold left edge + "n updated" text.
     *  Mirrors `CatalogUiState.diffsByKey` but scoped to the current
     *  system's planets/candidates/false positives. */
    val updateCountsByKey: Map<String, Int> = emptyMap(),
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SystemViewModel @Inject constructor(
    private val searchStarsUseCase: SearchStarsUseCase,
    private val observeSystemDetailUseCase: ObserveSystemDetailUseCase,
    settingsRepository: SettingsRepository,
    private val bookmarkRepository: BookmarkRepository,
) : ViewModel() {

    private val mode = MutableStateFlow(SystemMode.SEARCH)
    private val searchQuery = MutableStateFlow("")
    private val selectedHostname = MutableStateFlow<String?>(null)

    /**
     * The current navigation target hostname — updates the instant a user
     * taps a star (search result, orbital tooltip, etc.), well before the
     * downstream `systemDetail` flow has loaded the system. Exposed so
     * `SystemScreen` can key its star-globe reveal animation on the
     * target rather than on the loaded system; without this, the animation
     * key lags behind the navigation and the previous star's fully-revealed
     * Animatable is returned, producing a stale-flash before the fade.
     */
    val targetHostname: StateFlow<String?> = selectedHostname.asStateFlow()
    private val selectedPlanetName = MutableStateFlow<String?>(null)
    private val fromExternalTab = MutableStateFlow(false)
    private val skippedStarDetail = MutableStateFlow(false)
    /** True when user navigated PLANET → DETAIL via GUI back button. Hardware back
     *  from DETAIL should return to PLANET rather than leaving the tab. */
    private val cameToDetailFromPlanet = MutableStateFlow(false)
    private val starGlobeMode = MutableStateFlow(GlobeMode.HALF)
    /** The planet to restore when hardware back pops DETAIL → PLANET. Set once by
     *  the first GUI back from PLANET; preserved across subsequent planet selections. */
    private var lastPlanetName: String? = null
    /** The planet name from the original external entry (loadFromCatalog). Used to
     *  restore skippedStarDetail when hardware back returns to the entry planet. */
    private var entryPlanetName: String? = null

    private val searchResults = searchQuery
        .debounce(300)
        .flatMapLatest { query ->
            if (query.isBlank()) flowOf(emptyList())
            else searchStarsUseCase(query)
        }

    private val systemDetail = selectedHostname
        .flatMapLatest { hostname ->
            if (hostname == null) flowOf(null)
            else observeSystemDetailUseCase(hostname)
        }

    private val bookmarkedKeys = bookmarkRepository.observeAll()
        .map { list -> list.map { it.planetKey }.toSet() }

    /**
     * Per-bookmark snapshot lookup for the currently-displayed system.
     * Shape: (planetKey → stored snapshot). The diff for the selected
     * planet is computed against the matching live entry below.
     */
    private val bookmarkSnapshotsByKey = bookmarkRepository.observeAll()
        .map { list -> list.associate { it.planetKey to it.snapshot } }

    val uiState: StateFlow<SystemUiState> = combine(
        mode,
        searchQuery,
        searchResults,
        combine(systemDetail, selectedPlanetName, fromExternalTab, skippedStarDetail, starGlobeMode, ::NavState),
        combine(
            settingsRepository.observeSettings(),
            bookmarkedKeys,
            bookmarkSnapshotsByKey,
        ) { settings, keys, snapshots -> SettingsAndBookmarks(settings, keys, snapshots) },
    ) { mode, query, results, nav, sab ->
        val settings = sab.settings
        val keys = sab.keys
        val selectedDiff = computeSelectedPlanetDiff(nav, sab.snapshots)
        val updateCountsByKey = computeUpdateCountsByKey(nav, sab.snapshots)
        SystemUiState(
            mode = mode,
            searchQuery = query,
            searchResults = results,
            systemDetail = nav.system,
            selectedPlanetName = nav.planet,
            fromCatalog = nav.skippedStarDetail,
            settings = settings,
            orbitalState = nav.system?.let { buildOrbitalState(it, settings) },
            starGlobeMode = nav.starGlobeMode,
            bookmarkedKeys = keys,
            selectedPlanetDiff = selectedDiff,
            updateCountsByKey = updateCountsByKey,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SystemUiState())

    /**
     * Builds a `planetKey → updateCount` map across every planet/candidate
     * in the current system that has a non-empty diff against its
     * bookmark snapshot. Drives the system-page card badges (gold left
     * edge + "n updated") with the same semantics as the catalog.
     */
    private fun computeUpdateCountsByKey(
        nav: NavState,
        snapshotsByKey: Map<String, com.tadmor.domain.model.PlanetSnapshot>,
    ): Map<String, Int> {
        if (snapshotsByKey.isEmpty()) return emptyMap()
        val detail = nav.system ?: return emptyMap()
        val all = detail.planets + detail.candidates + detail.falsePositives
        return all.mapNotNull { entry ->
            val key = entry.planet.name
            val snapshot = snapshotsByKey[key] ?: return@mapNotNull null
            val current = entry.planet.toSnapshot().copy(disposition = entry.disposition.name)
            val diff = computeDiff(key, snapshot, current)
            if (diff.hasUpdates) key to diff.updateCount else null
        }.toMap()
    }

    private fun computeSelectedPlanetDiff(
        nav: NavState,
        snapshotsByKey: Map<String, com.tadmor.domain.model.PlanetSnapshot>,
    ): PlanetDiff? {
        val planetName = nav.planet ?: return null
        val snapshot = snapshotsByKey[planetName] ?: return null
        val detail = nav.system ?: return null
        // Look across all three buckets — bookmarks span confirmed + candidates + FPs.
        val entry: SystemPlanetEntry = detail.planets.find { it.planet.name == planetName }
            ?: detail.candidates.find { it.planet.name == planetName }
            ?: detail.falsePositives.find { it.planet.name == planetName }
            ?: return null
        val current = entry.planet.toSnapshot().copy(disposition = entry.disposition.name)
        val diff = computeDiff(planetName, snapshot, current)
        return if (diff.hasUpdates) diff else null
    }

    private data class SettingsAndBookmarks(
        val settings: UserSettings,
        val keys: Set<String>,
        val snapshots: Map<String, com.tadmor.domain.model.PlanetSnapshot>,
    )

    fun onSearchQueryChange(query: String) {
        searchQuery.value = query
    }

    fun onStarSelected(hostname: String) {
        selectedHostname.value = hostname
        selectedPlanetName.value = null
        fromExternalTab.value = false
        skippedStarDetail.value = false
        cameToDetailFromPlanet.value = false
        lastPlanetName = null
        entryPlanetName = null
        starGlobeMode.value = GlobeMode.HALF
        mode.value = SystemMode.DETAIL
    }

    fun onPlanetSelected(name: String) {
        // Tapping a different planet card while already on PLANET (A → B)
        // counts as "leaving A's planet info page" — consume A's diff.
        consumeOldPlanetIfChanged(newPlanetName = name)
        selectedPlanetName.value = name
        // User has now visited DETAIL, so they didn't skip it — hardware back
        // from PLANET should return to DETAIL, not the origin tab.
        skippedStarDetail.value = false
        // Don't clear cameToDetailFromPlanet or lastPlanetName — those track
        // the DETAIL page's back-to-planet link, which survives planet selection.
        mode.value = SystemMode.PLANET
    }

    /** Navigate from catalog or star map cross-link. */
    fun loadFromCatalog(hostname: String, planetName: String?) {
        selectedHostname.value = hostname
        selectedPlanetName.value = planetName
        fromExternalTab.value = true
        // skippedStarDetail: "View in System" or "View planet" jumps directly to planet,
        // skipping star detail. Hardware back from planet should return to origin tab.
        skippedStarDetail.value = planetName != null
        cameToDetailFromPlanet.value = false
        lastPlanetName = null
        entryPlanetName = planetName
        starGlobeMode.value = GlobeMode.HALF
        mode.value = if (planetName != null) SystemMode.PLANET else SystemMode.DETAIL
    }

    /** GUI "< Star Name" button from planet page → system detail. Sets flag so
     *  hardware back from DETAIL knows to return to the entry planet. Only the
     *  first GUI back sets [lastPlanetName] — subsequent ones preserve the original. */
    fun onGuiBackFromPlanet() {
        consumeOldPlanetIfChanged(newPlanetName = null)
        if (lastPlanetName == null) {
            lastPlanetName = selectedPlanetName.value
        }
        cameToDetailFromPlanet.value = true
        selectedPlanetName.value = null
        mode.value = SystemMode.DETAIL
    }

    /** Hardware back from planet page → system detail. Does NOT set the
     *  cameToDetailFromPlanet flag — only the GUI back button creates a
     *  return-to-planet link. */
    fun onBackFromPlanet() {
        consumeOldPlanetIfChanged(newPlanetName = null)
        selectedPlanetName.value = null
        mode.value = SystemMode.DETAIL
    }

    /** Hardware back from DETAIL when user previously came from PLANET via GUI back.
     *  Restores the entry planet. If that planet was the original external entry,
     *  restores skippedStarDetail so the next hardware back exits the tab. */
    fun onHardwareBackFromDetail(): Boolean {
        if (cameToDetailFromPlanet.value && lastPlanetName != null) {
            val restoringPlanet = lastPlanetName
            cameToDetailFromPlanet.value = false
            selectedPlanetName.value = restoringPlanet
            lastPlanetName = null
            // If restoring the original entry planet, re-enable the exit-to-origin
            // behavior so hardware back from this planet leaves the tab.
            if (restoringPlanet == entryPlanetName) {
                skippedStarDetail.value = true
            }
            mode.value = SystemMode.PLANET
            return true
        }
        return false
    }

    /** GUI "< Back" button from system detail. Returns true if handled internally,
     *  false if should leave tab. Always resets to search regardless of how we got here. */
    fun onBackFromDetail(): Boolean {
        return if (fromExternalTab.value) {
            resetToSearch()
            false // not handled — caller should navigate back to origin tab
        } else {
            resetToSearch()
            true // handled — user searched within System tab, just go to search
        }
    }

    fun resetToSearch() {
        // Consume any pending diff on the planet we're leaving — only when
        // we were ACTUALLY on a planet info page (PLANET mode). DETAIL→SEARCH
        // has no planet to consume.
        if (mode.value == SystemMode.PLANET) {
            consumeOldPlanetIfChanged(newPlanetName = null)
        }
        mode.value = SystemMode.SEARCH
        selectedHostname.value = null
        selectedPlanetName.value = null
        fromExternalTab.value = false
        skippedStarDetail.value = false
        cameToDetailFromPlanet.value = false
        lastPlanetName = null
        entryPlanetName = null
        starGlobeMode.value = GlobeMode.HALF
    }

    /**
     * Replaces the bookmarked planet's stored snapshot with the current
     * value, clearing the diff. Called when [selectedPlanetName] changes
     * away from a bookmarked planet (back press, switching planets within
     * the same system, leaving the System tab via reset). Tab switches
     * and process death do NOT trigger consumption — those aren't user-
     * intentional "I'm done looking" actions.
     */
    private fun consumeOldPlanetIfChanged(newPlanetName: String?) {
        val old = selectedPlanetName.value ?: return
        if (old == newPlanetName) return
        // Capture snapshot data SYNCHRONOUSLY from the latest UI state, BEFORE
        // any state mutations the caller is about to perform (e.g.
        // resetToSearch() nulls selectedHostname, which would make
        // systemDetail.first() return null by the time the coroutine runs —
        // the previous async approach raced and lost on hardware-back paths
        // that nuke selectedHostname). We then launch a coroutine that only
        // does the DB write with the already-captured values.
        val state = uiState.value
        if (old !in state.bookmarkedKeys) return
        val detail = state.systemDetail ?: return
        val entry: SystemPlanetEntry = detail.planets.find { it.planet.name == old }
            ?: detail.candidates.find { it.planet.name == old }
            ?: detail.falsePositives.find { it.planet.name == old }
            ?: return
        val current = entry.planet.toSnapshot().copy(disposition = entry.disposition.name)
        viewModelScope.launch {
            bookmarkRepository.consumeUpdates(old, current)
        }
    }

    /**
     * Toggle the bookmark for the currently-selected planet. Used by the
     * planet info page's slab-header bookmark button.
     */
    fun onBookmarkSelectedPlanetToggle() {
        val planetName = selectedPlanetName.value ?: return
        val state = uiState.value
        val isCurrentlyBookmarked = planetName in state.bookmarkedKeys
        viewModelScope.launch {
            if (isCurrentlyBookmarked) {
                bookmarkRepository.remove(planetName)
            } else {
                val detail = state.systemDetail ?: return@launch
                val entry: SystemPlanetEntry = detail.planets.find { it.planet.name == planetName }
                    ?: detail.candidates.find { it.planet.name == planetName }
                    ?: detail.falsePositives.find { it.planet.name == planetName }
                    ?: return@launch
                val source = when (entry.disposition) {
                    Disposition.CONFIRMED -> "PLANET"
                    Disposition.CANDIDATE, Disposition.FALSE_POSITIVE -> when {
                        planetName.startsWith("TOI-") -> "TOI"
                        planetName.startsWith("KOI-") -> "KOI"
                        else -> "K2"
                    }
                }
                val snapshot = entry.planet.toSnapshot().copy(disposition = entry.disposition.name)
                bookmarkRepository.add(planetName, source, snapshot)
            }
        }
    }

    private data class NavState(
        val system: SystemDetail?,
        val planet: String?,
        val fromExternalTab: Boolean,
        val skippedStarDetail: Boolean,
        val starGlobeMode: GlobeMode,
    )

    fun onStarGlobeModeChange(mode: GlobeMode) {
        starGlobeMode.value = mode
    }
}
