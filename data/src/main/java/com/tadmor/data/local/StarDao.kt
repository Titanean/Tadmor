package com.tadmor.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface StarDao {

    @Query("SELECT * FROM stars WHERE isPrimary = 1 ORDER BY hostname ASC")
    fun observeAll(): Flow<List<StarEntity>>

    @Query("SELECT * FROM stars WHERE hostname = :hostname LIMIT 1")
    fun observeByHostname(hostname: String): Flow<StarEntity?>

    @Query(
        "SELECT * FROM stars WHERE isPrimary = 1 AND (" +
        "hostname LIKE '%' || :query || '%' " +
        "OR hdName LIKE '%' || :query || '%' " +
        "OR hipName LIKE '%' || :query || '%' " +
        "OR ticId LIKE '%' || :query || '%') " +
        "ORDER BY hostname ASC"
    )
    fun searchByHostname(query: String): Flow<List<StarEntity>>

    @Query("SELECT * FROM stars WHERE isPrimary = 1 AND hostname IN (:hostnames) ORDER BY hostname ASC")
    fun findByHostnames(hostnames: List<String>): Flow<List<StarEntity>>

    @Query("SELECT * FROM stars WHERE syName = :syName AND isPrimary = 0")
    fun getCompanionStarsBySystemName(syName: String): Flow<List<StarEntity>>

    @Upsert
    suspend fun upsertAll(stars: List<StarEntity>)
}
