package com.tadmor.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PlanetDao {

    @Query("SELECT * FROM planets ORDER BY pl_name ASC")
    fun observeAll(): Flow<List<PlanetEntity>>

    @Query("SELECT * FROM planets WHERE hostname = :hostname ORDER BY pl_name ASC")
    fun observeByHostname(hostname: String): Flow<List<PlanetEntity>>

    @Query("SELECT * FROM planets WHERE pl_name = :name LIMIT 1")
    fun observeByName(name: String): Flow<PlanetEntity?>

    @Query("SELECT COUNT(*) FROM planets")
    fun observeCount(): Flow<Int>

    @Upsert
    suspend fun upsertAll(planets: List<PlanetEntity>)

    /** Removes rows whose `pl_name` exceeds [maxLen] characters — used to
     *  clean up the corrupted multi-kB-name rows we've observed coming
     *  from the TAP catalogue. Run on every sync so newly-corrupt rows
     *  get evicted as soon as the next sync sees them. */
    @Query("DELETE FROM planets WHERE LENGTH(pl_name) > :maxLen")
    suspend fun deleteRowsWithCorruptName(maxLen: Int): Int
}
