package com.tadmor.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {

    @Query("SELECT * FROM bookmarks ORDER BY bookmarkedAt DESC")
    fun observeAll(): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE planetKey = :planetKey LIMIT 1")
    suspend fun findByKey(planetKey: String): BookmarkEntity?

    @Upsert
    suspend fun upsert(entity: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE planetKey = :planetKey")
    suspend fun deleteByKey(planetKey: String)
}
