package com.tadmor.app.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tadmor.app.ui.components.FilterState
import com.tadmor.app.ui.components.SortOption
import com.tadmor.domain.classification.extractLuminosityClass
import com.tadmor.domain.model.Bookmark
import com.tadmor.domain.model.CatalogEntry
import com.tadmor.domain.model.Disposition
import com.tadmor.domain.model.PlanetDiff
import com.tadmor.domain.model.ProperNames
import com.tadmor.domain.model.Star
import com.tadmor.domain.model.UserSettings
import com.tadmor.domain.model.computeDiff
import com.tadmor.domain.model.isPulsar
import com.tadmor.domain.model.toSnapshot
import com.tadmor.domain.repository.BookmarkRepository
import com.tadmor.domain.repository.PlanetRepository
import com.tadmor.domain.repository.SettingsRepository
import com.tadmor.domain.usecase.ObserveCandidatesUseCase
import com.tadmor.domain.usecase.ObserveCatalogUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class CatalogUiState(
    val entries: List<CatalogEntry> = emptyList(),
    val totalCount: Int = 0,
    val filteredCount: Int = 0,
    val confirmedCount: Int = 0,
    val candidateCount: Int = 0,
    val falsePositiveCount: Int = 0,
    val searchQuery: String = "",
    val filterState: FilterState = FilterState(),
    val sortOption: SortOption = SortOption.DISCOVERY_DATE,
    val sortAscending: Boolean = false,
    val expandedPlanetName: String? = null,
    val isRefreshing: Boolean = false,
    val syncError: String? = null,
    val settings: UserSettings = UserSettings(),
    val activeTab: CatalogTab = CatalogTab.CONFIRMED,
    /** Set of bookmarked planet keys, used by the cards to render the
     *  bookmark toggle in the expanded panel as filled or hollow. */
    val bookmarkedKeys: Set<String> = emptySet(),
    /** Whether the "Saved only" filter is active. Session-only state —
     *  matches the rest of [FilterState], doesn't persist across launches. */
    val savedOnlyActive: Boolean = false,
    /** Per-bookmark diff against current catalog state, keyed by planetKey.
     *  Only entries with [PlanetDiff.hasUpdates] true appear here — used by
     *  the catalog card "n updated" indicator and the gold left edge. */
    val diffsByKey: Map<String, PlanetDiff> = emptyMap(),
    /** Total bookmarks-with-updates count, shown as a red badge on the
     *  bookmark filter button in the catalog header. */
    val unreadUpdatesCount: Int = 0,
)

@HiltViewModel
class CatalogViewModel @Inject constructor(
    observeCatalog: ObserveCatalogUseCase,
    observeCandidates: ObserveCandidatesUseCase,
    private val settingsRepository: SettingsRepository,
    private val repository: PlanetRepository,
    private val bookmarkRepository: BookmarkRepository,
) : ViewModel() {

    private val settings = settingsRepository.observeSettings()

    private val searchQuery = MutableStateFlow("")
    private val filterState = MutableStateFlow(FilterState())
    private val sortOption = MutableStateFlow(SortOption.DISCOVERY_DATE)
    private val sortAscending = MutableStateFlow(false)
    private val expandedPlanetName = MutableStateFlow<String?>(null)
    private val isRefreshing = MutableStateFlow(false)
    private val syncError = MutableStateFlow<String?>(null)
    private val activeTab = MutableStateFlow(CatalogTab.CONFIRMED)
    private val savedOnlyActive = MutableStateFlow(false)

    /** All bookmarks. Used to derive [bookmarkedKeys] for the toggle
     *  state UI and to compute per-bookmark diffs against current data. */
    private val bookmarks: Flow<List<Bookmark>> = bookmarkRepository.observeAll()

    /** Bookmarked planet keys (planet/candidate names). Used by both the
     *  saved-only filter and the card-level bookmark toggle UI. */
    private val bookmarkedKeys: Flow<Set<String>> = bookmarks
        .map { it.map { bm -> bm.planetKey }.toSet() }

    private val confirmedAndCandidates: Flow<Pair<List<CatalogEntry>, List<CatalogEntry>>> = combine(
        observeCatalog(),
        observeCandidates(),
    ) { confirmed, candidates -> confirmed to candidates }

    private val tabState: Flow<TabState> = combine(
        activeTab,
        savedOnlyActive,
        bookmarkedKeys,
        bookmarks,
    ) { tab, savedOnly, keys, bookmarkList -> TabState(tab, savedOnly, keys, bookmarkList) }

    val uiState: StateFlow<CatalogUiState> = combine(
        confirmedAndCandidates,
        searchQuery,
        combine(filterState, settings) { f, s -> f to s },
        combine(sortOption, sortAscending, expandedPlanetName, isRefreshing, ::CombinedControls),
        tabState,
    ) { (confirmedEntries, candidateEntries), query, filtersAndSettings, controls, tab ->
        val (filters, userSettings) = filtersAndSettings
        val savedOnly = tab.savedOnly
        val keys = tab.keys

        // Honour the master toggle: when off, the tabs and the candidate
        // data are fully invisible — even if a stale `activeTab` value
        // points to one of the hidden tabs, fall back to CONFIRMED.
        val effectiveTab = if (userSettings.includeCandidates) tab.tab else CatalogTab.CONFIRMED

        // Partition the candidate flow into the two non-confirmed buckets
        // once per emission rather than re-filtering on every UI access.
        val candidateOnly = candidateEntries.filter { it.disposition == Disposition.CANDIDATE }
        val falsePositiveOnly = candidateEntries.filter { it.disposition == Disposition.FALSE_POSITIVE }

        // Per-bookmark diff against current catalog state. Builds a
        // single map keyed on planetKey containing only entries with
        // pending updates (empty diffs are dropped). Drives both the
        // catalog card "n updated" badges and the bookmark filter
        // button's unread count.
        val entriesByKey: Map<String, CatalogEntry> = (confirmedEntries + candidateEntries)
            .associateBy { it.planet.name }
        val diffsByKey: Map<String, PlanetDiff> = tab.bookmarks
            .mapNotNull { bookmark ->
                val current = entriesByKey[bookmark.planetKey]?.toSnapshot()
                    ?: return@mapNotNull null
                val diff = computeDiff(bookmark.planetKey, bookmark.snapshot, current)
                if (diff.hasUpdates) bookmark.planetKey to diff else null
            }
            .toMap()

        val sourceList = when (effectiveTab) {
            CatalogTab.CONFIRMED -> confirmedEntries
            CatalogTab.CANDIDATES -> candidateOnly
            CatalogTab.FALSE_POSITIVES -> falsePositiveOnly
        }
        val totalCount = sourceList.size

        val filtered = sourceList
            .let { list -> if (savedOnly) list.filter { it.planet.name in keys } else list }
            .let { list -> if (query.isBlank()) list else list.filter { matchesSearch(it, query) } }
            .let { list -> applyFilters(list, filters, userSettings.useEstimates) }
            .let { list -> applySort(list, controls.sort, controls.ascending, userSettings.useEstimates) }

        CatalogUiState(
            entries = filtered,
            totalCount = totalCount,
            filteredCount = filtered.size,
            confirmedCount = confirmedEntries.size,
            candidateCount = candidateOnly.size,
            falsePositiveCount = falsePositiveOnly.size,
            searchQuery = query,
            filterState = filters,
            sortOption = controls.sort,
            sortAscending = controls.ascending,
            expandedPlanetName = controls.expanded,
            isRefreshing = controls.refreshing,
            settings = userSettings,
            activeTab = effectiveTab,
            bookmarkedKeys = keys,
            savedOnlyActive = savedOnly,
            diffsByKey = diffsByKey,
            unreadUpdatesCount = diffsByKey.size,
        )
    }.combine(syncError) { state, error ->
        state.copy(syncError = error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CatalogUiState())

    fun onSearchQueryChange(query: String) { searchQuery.value = query }
    fun onFilterChange(state: FilterState) { filterState.value = state }
    fun onSortChange(option: SortOption, ascending: Boolean) {
        sortOption.value = option
        sortAscending.value = ascending
    }
    fun onPlanetClick(name: String) {
        expandedPlanetName.value = if (expandedPlanetName.value == name) null else name
    }

    fun onTabChange(tab: CatalogTab) {
        activeTab.value = tab
        // Collapse any expanded card — its key may not exist in the new tab.
        expandedPlanetName.value = null
    }

    fun onSavedOnlyToggle() {
        savedOnlyActive.value = !savedOnlyActive.value
    }

    /**
     * Toggles the bookmark for the planet with [planetKey]. The matching
     * [CatalogEntry] is sourced from the live UI state so the snapshot
     * captures the current values (a fresh `add` always replaces the
     * stored snapshot via Upsert; a `remove` simply deletes the row).
     */
    fun onBookmarkToggle(planetKey: String) {
        val state = uiState.value
        val isCurrentlyBookmarked = planetKey in state.bookmarkedKeys
        viewModelScope.launch {
            if (isCurrentlyBookmarked) {
                bookmarkRepository.remove(planetKey)
            } else {
                val entry = findEntry(planetKey) ?: return@launch
                val source = when (entry.disposition) {
                    Disposition.CONFIRMED -> "PLANET"
                    Disposition.CANDIDATE, Disposition.FALSE_POSITIVE -> when {
                        entry.planet.name.startsWith("TOI-") -> "TOI"
                        entry.planet.name.startsWith("KOI-") -> "KOI"
                        else -> "K2"
                    }
                }
                bookmarkRepository.add(
                    planetKey = planetKey,
                    source = source,
                    snapshot = entry.toSnapshot(),
                )
            }
        }
    }

    private fun findEntry(planetKey: String): CatalogEntry? {
        // Search the active source list first; bookmark toggle from a
        // collapsed/expanded card always operates on a visible entry.
        return uiState.value.entries.firstOrNull { it.planet.name == planetKey }
    }

    fun onRefresh() {
        viewModelScope.launch {
            isRefreshing.value = true
            syncError.value = null
            try {
                repository.sync()
                settingsRepository.updateLastSyncedAtMillis(System.currentTimeMillis())
                // Debug-only: re-mutate stored snapshots after sync so the
                // diff display has something to render. Real catalog data
                // is untouched. Toggle lives in settings → DEBUG section.
                if (settingsRepository.observeSettings().first().simulateBookmarkUpdates) {
                    bookmarkRepository.simulateUpdates()
                }
            } catch (e: Exception) {
                Timber.e(e, "Manual sync failed")
                syncError.value = "Sync failed: ${e.message}"
            } finally {
                isRefreshing.value = false
            }
        }
    }

    fun onDismissSyncError() {
        syncError.value = null
    }

    // --- Private helpers ---

    private fun matchesSearch(entry: CatalogEntry, query: String): Boolean {
        val q = query.lowercase()
        return entry.planet.name.lowercase().contains(q) ||
            entry.planet.hostname.lowercase().contains(q) ||
            entry.star?.hdName?.lowercase()?.contains(q) == true ||
            entry.star?.hipName?.lowercase()?.contains(q) == true ||
            entry.star?.ticId?.lowercase()?.contains(q) == true ||
            ProperNames.forPlanet(entry.planet.name)?.lowercase()?.contains(q) == true ||
            ProperNames.forStar(entry.planet.hostname)?.lowercase()?.contains(q) == true
    }

    private fun applyFilters(list: List<CatalogEntry>, filters: FilterState, useEstimates: Boolean): List<CatalogEntry> {
        var result = list
        if (filters.minPlanets > 0) {
            // Build hostname → planet count map from the full (unfiltered) list
            val hostPlanetCount = list.groupBy { it.planet.hostname }
                .mapValues { it.value.size }
            result = result.filter { entry ->
                (hostPlanetCount[entry.planet.hostname] ?: 0) >= filters.minPlanets
            }
        }
        if (filters.compositions.isNotEmpty()) {
            result = result.filter { entry ->
                filters.compositions.any {
                    it.equals(entry.classification.compositionClass.label, ignoreCase = true)
                }
            }
        }
        if (filters.massPrefixes.isNotEmpty()) {
            val wantStandard = "Standard" in filters.massPrefixes
            val otherPrefixes = filters.massPrefixes - "Standard"
            result = result.filter { entry ->
                val mp = entry.classification.massPrefix
                when {
                    mp == null -> false
                    wantStandard && mp.label.isEmpty() -> true
                    otherPrefixes.any { it.equals(mp.label, ignoreCase = true) } -> true
                    else -> false
                }
            }
        }
        if (filters.temperatures.isNotEmpty()) {
            result = result.filter { entry ->
                // Skip estimated temperature classes when estimates are disabled
                if (!useEstimates && entry.classification.temperatureEstimated) return@filter false
                entry.classification.temperatureClass?.let { tc ->
                    filters.temperatures.any { it.equals(tc.label, ignoreCase = true) }
                } ?: false
            }
        }
        if (filters.spectralClasses.isNotEmpty()) {
            result = result.filter { entry ->
                val starClass = classifyStarSpectral(entry.star, useEstimates)
                starClass in filters.spectralClasses
            }
        }
        if (filters.luminosityClasses.isNotEmpty()) {
            result = result.filter { entry ->
                val lumClass = extractLuminosityClass(entry.star?.spectralType)
                lumClass != null && lumClass in filters.luminosityClasses
            }
        }
        if (filters.discoveryMethods.isNotEmpty()) {
            result = result.filter { entry ->
                val method = entry.planet.discoveryMethod ?: return@filter false
                filters.discoveryMethods.any { matchesDiscoveryMethod(it, method) }
            }
        }
        if (filters.dataFields.isNotEmpty()) {
            result = result.filter { entry ->
                val p = entry.planet
                val c = entry.classification
                filters.dataFields.all { field ->
                    when (field) {
                        "Mass" -> p.massEarth != null || p.massJupiter != null
                        "Radius" -> p.radiusEarth != null
                        "Temperature" -> p.eqTempK != null
                        "Period" -> p.orbitalPeriodDays != null
                        "Density" -> p.densityGCm3 != null
                        "Eccentricity" -> p.eccentricity != null
                        else -> true
                    }
                }
            }
        }
        return result
    }

    /**
     * Classifies a star into a filter-friendly spectral category.
     * Returns one of: O, B, A, F, G, K, M, L, T, Q, D, Other.
     * Q = pulsars / neutron stars (synthetic). D = white dwarfs.
     */
    private fun classifyStarSpectral(star: Star?, useEstimates: Boolean = true): String {
        if (star?.isPulsar() == true) return "Q"
        val spType = star?.spectralType?.trim()?.uppercase()
        if (spType != null) {
            // White dwarfs: spectral types starting with D (DA, DB, DC, etc.)
            if (spType.startsWith("D") && spType.length >= 2 && spType[1] in "ABCOZQ") return "D"
            // Standard OBAFGKM + sub-stellar L, T, Y dwarfs
            val first = spType.firstOrNull()
            if (first != null && first in "OBAFGKMLTY") return first.toString()
        }
        // No spectral type — try to infer from Teff (only if estimates enabled)
        if (!useEstimates) return "Other"
        val teff = star?.teffK
        if (teff != null) {
            return when {
                teff >= 30000 -> "O"
                teff >= 10000 -> "B"
                teff >= 7500 -> "A"
                teff >= 6000 -> "F"
                teff >= 5200 -> "G"
                teff >= 3700 -> "K"
                teff >= 2400 -> "M"
                teff >= 1300 -> "L"
                teff >= 300 -> "T"
                else -> "Y"
            }
        }
        return "Other"
    }

    private fun matchesDiscoveryMethod(filterLabel: String, apiMethod: String): Boolean {
        val m = apiMethod.lowercase()
        return when (filterLabel.lowercase()) {
            "transit" -> m == "transit" || m.contains("transit timing")
            "radial velocity" -> m.contains("radial velocity")
            "direct imaging" -> m.contains("imaging")
            "microlensing" -> m.contains("microlensing")
            "timing" -> m.contains("timing") || m.contains("pulsar")
            else -> m.contains(filterLabel.lowercase())
        }
    }

    private fun applySort(
        list: List<CatalogEntry>,
        sort: SortOption,
        ascending: Boolean,
        useEstimates: Boolean = true,
    ): List<CatalogEntry> {
        // Nulls always sort to the bottom regardless of sort direction.
        fun <T : Comparable<T>> selector(extract: (CatalogEntry) -> T?): Comparator<CatalogEntry> {
            val valueComparator = if (ascending) naturalOrder<T>() else reverseOrder<T>()
            return Comparator { a, b ->
                val va = extract(a)
                val vb = extract(b)
                when {
                    va == null && vb == null -> 0
                    va == null -> 1   // null always last
                    vb == null -> -1
                    else -> valueComparator.compare(va, vb)
                }
            }
        }

        // Name sort needs its own fast path because the comparator is called
        // O(n log n) times and each call would otherwise allocate a fresh
        // lowercased copy of the planet name. A single catalog entry with a
        // pathologically long name string (e.g. corrupt sync data) then
        // triggers a gigabyte-scale allocation storm during the sort —
        // observed as a 20 s freeze with ~400 k major page faults from
        // swap thrash. Precomputing the lowercase key once per entry bounds
        // total allocation to O(n × name length) and keeps the comparator
        // doing pure String comparisons. Names over a sanity limit are
        // truncated to a sort key (display path still caps separately).
        if (sort == SortOption.NAME) {
            val withKey = list.map { entry ->
                val name = entry.planet.name
                val key = if (name.length > MAX_SORT_NAME_LEN) {
                    timber.log.Timber.w(
                        "Catalog: planet '${name.take(40)}…' has " +
                        "abnormally long name (${name.length} chars) — truncated for sort"
                    )
                    name.take(MAX_SORT_NAME_LEN).lowercase()
                } else {
                    name.lowercase()
                }
                entry to key
            }
            val sorted = if (ascending) {
                withKey.sortedBy { it.second }
            } else {
                withKey.sortedByDescending { it.second }
            }
            return sorted.map { it.first }
        }

        val comparator: Comparator<CatalogEntry> = when (sort) {
            // Sort by full publication date when available; falls back to
            // year-only entries via Planet.discoveryDateSortKey() so they
            // still slot in correctly. Default direction is descending so
            // the newest planets sit at the top.
            SortOption.DISCOVERY_DATE -> selector { it.planet.discoveryDateSortKey() }
            SortOption.NAME -> error("handled above")
            SortOption.MASS -> selector { it.planet.massEarth }
            SortOption.RADIUS -> selector { it.planet.radiusEarth }
            SortOption.TEMPERATURE -> selector {
                it.planet.eqTempK ?: if (useEstimates) it.classification.estimatedEqTempK else null
            }
            SortOption.DISTANCE -> selector { it.star?.distancePc }
            SortOption.PERIOD -> selector { it.planet.orbitalPeriodDays }
            SortOption.PLANET_COUNT -> selector { it.star?.planetCount }
        }
        return list.sortedWith(comparator)
    }

    private companion object {
        /** Anything longer than this is data corruption — TAP catalog names
         *  cap out around 30 characters in practice. */
        const val MAX_SORT_NAME_LEN = 256
    }

    private data class CombinedControls(
        val sort: SortOption,
        val ascending: Boolean,
        val expanded: String?,
        val refreshing: Boolean,
    )

    private data class TabState(
        val tab: CatalogTab,
        val savedOnly: Boolean,
        val keys: Set<String>,
        val bookmarks: List<Bookmark>,
    )
}
