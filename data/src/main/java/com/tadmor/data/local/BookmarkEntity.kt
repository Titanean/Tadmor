package com.tadmor.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for a bookmarked planet/candidate. Lives in a separate
 * [BookmarksDatabase] (not the catalog DB) because bookmarks are *user
 * data* and must survive catalog schema changes.
 *
 * [planetKey] is unified across sources — [com.tadmor.domain.model.Planet.name]
 * for confirmed planets, [com.tadmor.domain.model.CandidatePlanet.displayName]
 * ("TOI-1234.01" / "KOI-752.01") for candidates. These namespaces don't
 * collide in NASA's catalogs.
 */
@Entity(
    tableName = "bookmarks",
    indices = [Index("source")],
)
data class BookmarkEntity(
    @PrimaryKey
    @ColumnInfo(name = "planetKey") val planetKey: String,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "bookmarkedAt") val bookmarkedAt: Long,
    /** kotlinx-serialised [com.tadmor.domain.model.PlanetSnapshot]. */
    @ColumnInfo(name = "snapshotJson") val snapshotJson: String,
)
