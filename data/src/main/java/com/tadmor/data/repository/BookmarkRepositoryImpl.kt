package com.tadmor.data.repository

import com.tadmor.data.local.BookmarkDao
import com.tadmor.data.local.BookmarkEntity
import com.tadmor.domain.model.Bookmark
import com.tadmor.domain.model.PlanetSnapshot
import com.tadmor.domain.repository.BookmarkRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

@Singleton
class BookmarkRepositoryImpl @Inject constructor(
    private val dao: BookmarkDao,
) : BookmarkRepository {

    private val json = Json {
        ignoreUnknownKeys = true       // forward-compat: tolerate older schemas missing new fields
        encodeDefaults = true
    }

    override fun observeAll(): Flow<List<Bookmark>> =
        dao.observeAll().map { rows -> rows.mapNotNull(::toDomain) }

    override suspend fun add(planetKey: String, source: String, snapshot: PlanetSnapshot) {
        dao.upsert(
            BookmarkEntity(
                planetKey = planetKey,
                source = source,
                bookmarkedAt = System.currentTimeMillis(),
                snapshotJson = json.encodeToString(PlanetSnapshot.serializer(), snapshot),
            ),
        )
    }

    override suspend fun remove(planetKey: String) {
        dao.deleteByKey(planetKey)
    }

    override suspend fun consumeUpdates(planetKey: String, currentSnapshot: PlanetSnapshot) {
        val existing = dao.findByKey(planetKey) ?: return
        dao.upsert(
            existing.copy(
                snapshotJson = json.encodeToString(PlanetSnapshot.serializer(), currentSnapshot),
            ),
        )
    }

    override suspend fun simulateUpdates() {
        val rng = Random.Default
        val rows = dao.observeAll().first()
        rows.forEach { entity ->
            val snapshot = runCatching {
                json.decodeFromString(PlanetSnapshot.serializer(), entity.snapshotJson)
            }.getOrNull() ?: return@forEach
            val mutated = mutateSnapshot(snapshot, rng)
            if (mutated == snapshot) return@forEach
            dao.upsert(
                entity.copy(
                    snapshotJson = json.encodeToString(PlanetSnapshot.serializer(), mutated),
                ),
            )
        }
    }

    /**
     * Picks 1–2 fields from the snapshot and modifies them so a diff
     * appears against the live catalog. Numeric fields are scaled by a
     * factor in [0.85, 1.15] to look like a parameter refinement;
     * disposition flips to a different valid value to drive the
     * disposition-change banner. Null fields are skipped (no fake "data
     * added" scenarios to keep the simulation believable).
     */
    private fun mutateSnapshot(snap: PlanetSnapshot, rng: Random): PlanetSnapshot {
        // Build the list of mutators that have something to mutate (non-null
        // source field for numeric ones; disposition is always present).
        val mutators = mutableListOf<(PlanetSnapshot) -> PlanetSnapshot>()
        snap.massEarth?.let { mutators += { it.copy(massEarth = jitter(snap.massEarth, rng)) } }
        snap.radiusEarth?.let { mutators += { it.copy(radiusEarth = jitter(snap.radiusEarth, rng)) } }
        snap.densityGCm3?.let { mutators += { it.copy(densityGCm3 = jitter(snap.densityGCm3, rng)) } }
        snap.eqTempK?.let { mutators += { it.copy(eqTempK = jitter(snap.eqTempK, rng)) } }
        snap.insolationFlux?.let { mutators += { it.copy(insolationFlux = jitter(snap.insolationFlux, rng)) } }
        snap.semiMajorAxisAU?.let { mutators += { it.copy(semiMajorAxisAU = jitter(snap.semiMajorAxisAU, rng)) } }
        snap.orbitalPeriodDays?.let { mutators += { it.copy(orbitalPeriodDays = jitter(snap.orbitalPeriodDays, rng)) } }
        snap.eccentricity?.let { mutators += { it.copy(eccentricity = jitter(snap.eccentricity, rng)?.coerceIn(0.0, 0.99)) } }
        // Disposition flip — always available, drives the disposition banner.
        // Lower probability so it doesn't fire on every bookmark.
        if (rng.nextFloat() < 0.30f) {
            mutators += { it.copy(disposition = flipDisposition(snap.disposition)) }
        }
        if (mutators.isEmpty()) return snap

        // Pick 1–2 fields. Two fields keep the diff feel "real" without
        // making every parameter change at once.
        val pickCount = rng.nextInt(1, 3.coerceAtMost(mutators.size + 1))
        return mutators.shuffled(rng).take(pickCount).fold(snap) { acc, m -> m(acc) }
    }

    private fun jitter(value: Double?, rng: Random): Double? {
        if (value == null) return null
        val factor = rng.nextDouble(0.85, 1.15)
        return value * factor
    }

    private fun flipDisposition(current: String): String = when (current) {
        "CONFIRMED" -> "CANDIDATE"
        "CANDIDATE" -> "CONFIRMED"
        "FALSE_POSITIVE" -> "CANDIDATE"
        else -> "CANDIDATE"
    }

    private fun toDomain(entity: BookmarkEntity): Bookmark? {
        val snapshot = runCatching {
            json.decodeFromString(PlanetSnapshot.serializer(), entity.snapshotJson)
        }.getOrNull() ?: return null
        return Bookmark(
            planetKey = entity.planetKey,
            source = entity.source,
            bookmarkedAt = entity.bookmarkedAt,
            snapshot = snapshot,
        )
    }
}
