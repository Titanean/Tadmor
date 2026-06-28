package com.tadmor.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for unconfirmed exoplanet candidates and confirmed false
 * positives, sourced from NASA's TOI/KOI tables.
 *
 * No foreign key to [StarEntity] — many candidate hosts (TIC- or KIC-only)
 * aren't in our `ps`-derived stars table.
 *
 * [hostId] is the source-native catalog ID (TIC for TOI, KIC for KOI),
 * used to reconcile candidates against existing stars at observe time.
 * [hostname] is the resolved display hostname computed at ingest:
 *   - TOI: "TIC <id>" placeholder (use case may swap for the matched
 *     primary's hostname when [hostId] matches an existing Star.ticId).
 *   - KOI: derived from `kepler_name` when present (e.g. "Kepler-186"),
 *     or "KIC <id>" otherwise.
 */
@Entity(
    tableName = "candidate_planets",
    indices = [
        Index("hostId"),
        Index("hostname"),
        Index("disposition"),
        Index("source"),
    ],
)
data class CandidatePlanetEntity(
    @PrimaryKey
    @ColumnInfo(name = "candidateId") val candidateId: String,
    @ColumnInfo(name = "hostId") val hostId: String,
    @ColumnInfo(name = "hostname") val hostname: String,
    @ColumnInfo(name = "disposition") val disposition: String,
    @ColumnInfo(name = "radiusEarth") val radiusEarth: Double?,
    @ColumnInfo(name = "orbitalPeriodDays") val orbitalPeriodDays: Double?,
    @ColumnInfo(name = "eqTempK") val eqTempK: Double?,
    @ColumnInfo(name = "insolationFlux") val insolationFlux: Double?,
    @ColumnInfo(name = "transitMidpointBJD") val transitMidpointBJD: Double?,
    @ColumnInfo(name = "hostTeffK") val hostTeffK: Double?,
    @ColumnInfo(name = "hostRadiusSolar") val hostRadiusSolar: Double?,
    @ColumnInfo(name = "hostDistancePc") val hostDistancePc: Double?,
    @ColumnInfo(name = "ra") val ra: Double?,
    @ColumnInfo(name = "dec") val dec: Double?,
    @ColumnInfo(name = "createdDate") val createdDate: String?,
    @ColumnInfo(name = "source") val source: String,
)
