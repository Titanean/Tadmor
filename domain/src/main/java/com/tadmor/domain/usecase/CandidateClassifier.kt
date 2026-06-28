package com.tadmor.domain.usecase

import com.tadmor.domain.classification.PlanetClassifier
import com.tadmor.domain.classification.visual.VisualProfileEngine
import com.tadmor.domain.model.CandidatePlanet
import com.tadmor.domain.model.CatalogEntry
import com.tadmor.domain.model.Planet
import com.tadmor.domain.model.Star
import kotlin.math.abs

/**
 * Shared per-candidate classification + dedup logic. Lives at the use-case
 * layer so [ObserveCandidatesUseCase] (full catalog) and
 * [ObserveSystemDetailUseCase] (single-system filter) can produce the same
 * shape of [CatalogEntry] without duplicating the work.
 *
 * Splitting this out also makes the perf cost explicit: the catalog use
 * case invokes [classify] for every candidate (~17,000 rows), while the
 * system detail use case invokes it only for the small subset whose
 * resolved hostname matches the requested system. Re-running the full
 * 17,000-candidate classification on every system-detail subscription
 * caused a ~500ms lag on every Catalog → Planet and Star Map → Orbital
 * navigation; running it for ~5 candidates is invisible.
 */
object CandidateClassifier {

    /**
     * Builds the per-emission lookup data structures used by [resolveStar]
     * and [shouldDedup]. Computed once per emission of the upstream flows
     * and threaded into per-candidate calls.
     */
    data class Index(
        val starsByTic: Map<String, Star>,
        val starsByHostname: Map<String, Star>,
        /** Per-host list of confirmed-planet orbital periods (days). The
         *  per-host group is typically 1–10 entries, so a linear-scan
         *  proximity check is essentially free per candidate. */
        val confirmedPeriodsByHost: Map<String, List<Double>>,
    )

    fun buildIndex(stars: List<Star>, planets: List<Planet>): Index {
        val starsByTic: Map<String, Star> = stars.mapNotNull { s ->
            val numeric = s.ticId?.removePrefix("TIC ")?.trim()
            if (numeric.isNullOrEmpty()) null else numeric to s
        }.toMap()
        val starsByHostname: Map<String, Star> = stars.associateBy { it.hostname }
        val confirmedPeriodsByHost: Map<String, List<Double>> = planets
            .filter { it.orbitalPeriodDays != null }
            .groupBy { it.hostname }
            .mapValues { (_, ps) -> ps.mapNotNull { it.orbitalPeriodDays } }
        return Index(starsByTic, starsByHostname, confirmedPeriodsByHost)
    }

    /**
     * Resolves the candidate to its real host star where possible.
     * - TOI: TIC numeric → match against [Star.ticId].
     * - KOI: hostname (computed at ingest from `kepler_name` when present)
     *   → direct match in [starsByHostname].
     * - K2: hostname is pre-resolved from the `k2pandc.hostname` column —
     *   direct match.
     */
    fun resolveStar(candidate: CandidatePlanet, index: Index): Star? = when (candidate.source) {
        "TOI" -> index.starsByTic[candidate.hostId]
        "KOI", "K2" -> index.starsByHostname[candidate.hostname]
        else -> null
    }

    /**
     * Returns true when a candidate row duplicates a confirmed planet at
     * the same host (matched by orbital period within ±1% relative
     * tolerance). Disposition is intentionally NOT checked: TFOPWG flags
     * (PC, KP, CP, APC, FP) don't always update when a body is confirmed
     * elsewhere — TOI-2104.02 is reported as a planet candidate even
     * though its period (5.9 d) matches the confirmed TOI-2104 b in `ps`.
     * The (hostname, period within tolerance) signature is reliable
     * enough on its own.
     *
     * The ±1% relative tolerance replaces the prior fixed ±10ms band,
     * which got pathologically tight for long-period planets (~0.003%
     * for a 365-day orbit) and missed legitimate matches when the two
     * archives reduced the same transit slightly differently.
     */
    fun shouldDedup(
        candidate: CandidatePlanet,
        resolvedHostname: String,
        index: Index,
    ): Boolean {
        val period = candidate.orbitalPeriodDays ?: return false
        val periodsAtHost = index.confirmedPeriodsByHost[resolvedHostname] ?: return false
        val tolerance = period * 0.01
        return periodsAtHost.any { abs(it - period) < tolerance }
    }

    /**
     * Builds a [CatalogEntry] for a single candidate. Synthesises a
     * stand-in [Star] from the candidate's per-row stellar fields when no
     * real host match was found. The classification + visual profile run
     * uses the existing pipelines so candidate cards render identically
     * to confirmed planet cards.
     */
    fun classify(
        candidate: CandidatePlanet,
        matchedStar: Star?,
        resolvedHostname: String,
    ): CatalogEntry {
        val star = matchedStar ?: Star(
            hostname = resolvedHostname,
            spectralType = null,
            teffK = candidate.hostTeffK,
            teffKLimit = null,
            radiusSolar = candidate.hostRadiusSolar,
            radiusSolarLimit = null,
            massSolar = null,
            massSolarLimit = null,
            logLuminosity = null,
            logLuminosityLimit = null,
            rightAscensionDeg = candidate.ra,
            declinationDeg = candidate.dec,
            distancePc = candidate.hostDistancePc,
            planetCount = null,
            ticId = if (candidate.source == "TOI") "TIC ${candidate.hostId}" else null,
        )
        val planet = candidate.toPlanet().copy(hostname = resolvedHostname)
        val classification = PlanetClassifier.classify(planet, star)
        val context = ObserveSystemDetailUseCase.buildSystemContext(
            star = star,
            isCircumbinary = false,
            planetCount = 1,
            innerSmaAU = null,
            outerSmaAU = null,
        )
        return CatalogEntry(
            planet = planet,
            star = star,
            classification = classification,
            dataCompleteness = computeCompleteness(planet),
            visualProfile = VisualProfileEngine.generate(planet, classification, context),
            disposition = candidate.disposition,
        )
    }

    private fun computeCompleteness(planet: Planet): Int {
        var count = 0
        if (planet.massEarth != null || planet.massJupiter != null) count++
        if (planet.radiusEarth != null) count++
        if (planet.eqTempK != null) count++
        if (planet.densityGCm3 != null) count++
        if (planet.orbitalPeriodDays != null) count++
        if (planet.eccentricity != null) count++
        return count
    }
}
