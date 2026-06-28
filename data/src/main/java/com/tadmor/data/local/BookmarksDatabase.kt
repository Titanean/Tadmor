package com.tadmor.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Separate Room database for user bookmark data. Distinct from
 * [TadmorDatabase] (the catalog DB) so the catalog can keep its
 * `fallbackToDestructiveMigration` policy — bookmarks are user data
 * and use proper migrations from v1 onwards.
 */
@Database(
    entities = [BookmarkEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class BookmarksDatabase : RoomDatabase() {
    abstract fun bookmarkDao(): BookmarkDao
}
