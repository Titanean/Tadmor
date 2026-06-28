package com.tadmor.domain.repository

import com.tadmor.domain.model.Bookmark
import com.tadmor.domain.model.PlanetSnapshot
import kotlinx.coroutines.flow.Flow

/**
 * User-data persistence for bookmarked planets and candidates. Backed by
 * a separate Room DB ([com.tadmor.data.local.BookmarksDatabase]) so it
 * survives the catalog DB's destructive-migration cycle.
 */
interface BookmarkRepository {
    fun observeAll(): Flow<List<Bookmark>>

    suspend fun add(planetKey: String, source: String, snapshot: PlanetSnapshot)

    suspend fun remove(planetKey: String)

    /**
     * Replace the bookmark's stored snapshot with [currentSnapshot]. Called
     * when the user closes the planet info page — clears the diff that
     * the snapshot/current comparison was producing.
     */
    suspend fun consumeUpdates(planetKey: String, currentSnapshot: PlanetSnapshot)

    /**
     * Debug-only: mutates each bookmark's stored snapshot with small
     * random changes so the diff display has something to render. Touches
     * only the snapshot (in the bookmarks DB), never the live catalog
     * data — reversible by re-bookmarking. Triggered from sync sites
     * when the user has the "Simulate bookmark updates" debug toggle on.
     */
    suspend fun simulateUpdates()
}
