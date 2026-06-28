package com.tadmor.data.repository

import androidx.room.withTransaction
import com.tadmor.data.local.CandidatePlanetDao
import com.tadmor.data.local.PlanetDao
import com.tadmor.data.local.StarDao
import com.tadmor.data.local.TadmorDatabase
import com.tadmor.data.mapper.DtoToEntityMapper
import com.tadmor.data.mapper.EntityToDomainMapper
import com.tadmor.data.remote.TapApiService
import com.tadmor.domain.model.CandidatePlanet
import com.tadmor.domain.model.Planet
import com.tadmor.domain.model.Star
import com.tadmor.domain.repository.PlanetRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlanetRepositoryImpl @Inject constructor(
    private val apiService: TapApiService,
    private val database: TadmorDatabase,
    private val planetDao: PlanetDao,
    private val starDao: StarDao,
    private val candidateDao: CandidatePlanetDao,
) : PlanetRepository {

    override fun observeAllPlanets(): Flow<List<Planet>> =
        planetDao.observeAll().map(EntityToDomainMapper::mapPlanets)

    override fun observeAllStars(): Flow<List<Star>> =
        starDao.observeAll().map(EntityToDomainMapper::mapStars)

    override fun observePlanetsByHostname(hostname: String): Flow<List<Planet>> =
        planetDao.observeByHostname(hostname).map(EntityToDomainMapper::mapPlanets)

    override fun observePlanetByName(name: String): Flow<Planet?> =
        planetDao.observeByName(name).map { it?.let(EntityToDomainMapper::mapPlanet) }

    override fun observeStarByHostname(hostname: String): Flow<Star?> =
        starDao.observeByHostname(hostname).map { it?.let(EntityToDomainMapper::mapStar) }

    override fun searchStars(query: String): Flow<List<Star>> =
        starDao.searchByHostname(query).map(EntityToDomainMapper::mapStars)

    override fun searchStarsByHostnames(hostnames: List<String>): Flow<List<Star>> =
        starDao.findByHostnames(hostnames).map(EntityToDomainMapper::mapStars)

    override fun observeCompanionStars(syName: String): Flow<List<Star>> =
        starDao.getCompanionStarsBySystemName(syName).map(EntityToDomainMapper::mapStars)

    override fun observeAllCandidates(): Flow<List<CandidatePlanet>> =
        candidateDao.observeAll().map { entities ->
            entities.mapNotNull(EntityToDomainMapper::mapCandidate)
        }

    /**
     * Full sync: fetch all rows from TAP API, map, upsert in a single transaction.
     * Stars are upserted first to satisfy foreign key constraint.
     */
    override suspend fun sync() {
        Timber.d("Starting TAP API sync...")
        val dtos = apiService.fetchAllPlanets()
        Timber.d("Fetched ${dtos.size} planet rows from TAP API")

        val stellarHosts = try {
            apiService.fetchStellarHosts().also {
                Timber.d("Fetched ${it.size} stellar host rows")
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to fetch stellar hosts, using planet-embedded star data only")
            emptyList()
        }

        val stars = DtoToEntityMapper.extractUniqueStars(dtos, stellarHosts)
        // Drop DTO rows with a corrupted plName before any of them reach
        // the database. NASA TAP designations cap out around 30 chars in
        // practice; the only entries hitting this filter are catalog
        // duplicates whose name field somehow came back as a multi-kB
        // run of garbage bytes (observed: a duplicate TOI-663 b with a
        // 266 k-char name). Without this filter the next sync would
        // overwrite the row we just sanitised on the read side.
        val rawCount = dtos.size
        val planets = dtos
            .filter { dto ->
                val len = dto.plName.length
                if (len > MAX_INGEST_PLANET_NAME_LEN) {
                    Timber.w(
                        "Drop catalog row: planet name is $len chars " +
                        "(host '${dto.hostname}', first 40 chars: " +
                        "'${dto.plName.take(40)}')"
                    )
                    false
                } else true
            }
            .map(DtoToEntityMapper::mapPlanet)
        if (planets.size < rawCount) {
            Timber.w("Dropped ${rawCount - planets.size} corrupt planet rows " +
                "out of $rawCount during sync")
        }

        // Override sy_pnum (system-wide total) with actual per-hostname planet count
        val planetCountByHost = planets.groupBy { it.hostname }.mapValues { it.value.size }
        val starsWithCounts = stars.map { star ->
            star.copy(planetCount = planetCountByHost[star.hostname] ?: 0)
        }

        val companionStars = DtoToEntityMapper.extractCompanionStars(starsWithCounts, stellarHosts)

        // Candidate planets (TOI + KOI + K2): fetched independently per
        // source so one source's failure doesn't block the others. Always
        // synced — the user-facing toggle controls display, not ingestion.
        val toiCandidates = try {
            apiService.fetchToi().also { Timber.d("Fetched ${it.size} TOI rows") }
        } catch (e: Exception) {
            Timber.w(e, "Failed to fetch TOI candidates, leaving TOI rows unchanged")
            null
        }
        val koiCandidates = try {
            apiService.fetchKoi().also { Timber.d("Fetched ${it.size} KOI rows") }
        } catch (e: Exception) {
            Timber.w(e, "Failed to fetch KOI candidates, leaving KOI rows unchanged")
            null
        }
        val k2Candidates = try {
            apiService.fetchK2().also { Timber.d("Fetched ${it.size} K2 rows") }
        } catch (e: Exception) {
            Timber.w(e, "Failed to fetch K2 candidates, leaving K2 rows unchanged")
            null
        }

        // Batch inserts to reduce memory pressure
        database.withTransaction {
            // Evict any locally-cached rows whose plName has gone corrupt
            // (multi-kB garbage strings observed in TAP duplicates). The
            // ingest filter above stops fresh corrupt rows from being
            // upserted — this deletes ones already in the DB from prior
            // syncs so users don't have to wait for those rows to be
            // overwritten organically.
            val deletedCorrupt = planetDao.deleteRowsWithCorruptName(MAX_INGEST_PLANET_NAME_LEN)
            if (deletedCorrupt > 0) {
                Timber.w("Evicted $deletedCorrupt corrupt-name planet rows from local DB")
            }
            starsWithCounts.chunked(500).forEach { batch -> starDao.upsertAll(batch) }
            companionStars.chunked(500).forEach { batch -> starDao.upsertAll(batch) }
            planets.chunked(500).forEach { batch -> planetDao.upsertAll(batch) }
            // Replace each candidate source wholesale — dispositions can flip
            // (PC → FP, CANDIDATE → CONFIRMED) and the per-source row counts
            // are small enough that delete-then-upsert is cheap and avoids
            // stale rows when upstream removes a candidate from its list.
            if (toiCandidates != null) {
                val mapped = toiCandidates.mapNotNull(DtoToEntityMapper::mapCandidate)
                candidateDao.deleteBySource("TOI")
                mapped.chunked(500).forEach { batch -> candidateDao.upsertAll(batch) }
                Timber.d("Sync complete: ${mapped.size} TOI candidates upserted")
            }
            if (koiCandidates != null) {
                val mapped = koiCandidates.mapNotNull(DtoToEntityMapper::mapCandidate)
                candidateDao.deleteBySource("KOI")
                mapped.chunked(500).forEach { batch -> candidateDao.upsertAll(batch) }
                Timber.d("Sync complete: ${mapped.size} KOI candidates upserted")
            }
            if (k2Candidates != null) {
                val mapped = k2Candidates.mapNotNull(DtoToEntityMapper::mapCandidate)
                candidateDao.deleteBySource("K2")
                mapped.chunked(500).forEach { batch -> candidateDao.upsertAll(batch) }
                Timber.d("Sync complete: ${mapped.size} K2 candidates upserted")
            }
        }

        Timber.d("Sync complete: ${stars.size} stars, ${companionStars.size} companions, ${planets.size} planets upserted")
    }

    private companion object {
        /** Maximum planet-name length accepted from the TAP catalogue. Real
         *  designations fit in ~30 chars; anything beyond ~64 is the
         *  multi-kB garbage we saw on duplicate rows. Set generously so
         *  legitimate long designations (BD/HD/HIP variants with extra
         *  suffixes) still pass. */
        const val MAX_INGEST_PLANET_NAME_LEN = 128
    }
}
