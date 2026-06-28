package com.tadmor.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CandidatePlanetDao {

    @Query("SELECT * FROM candidate_planets ORDER BY candidateId ASC")
    fun observeAll(): Flow<List<CandidatePlanetEntity>>

    @Query("SELECT * FROM candidate_planets WHERE hostId = :hostId ORDER BY candidateId ASC")
    fun observeByHostId(hostId: String): Flow<List<CandidatePlanetEntity>>

    @Query("SELECT COUNT(*) FROM candidate_planets")
    fun observeCount(): Flow<Int>

    @Upsert
    suspend fun upsertAll(candidates: List<CandidatePlanetEntity>)

    @Query("DELETE FROM candidate_planets WHERE source = :source")
    suspend fun deleteBySource(source: String)
}
