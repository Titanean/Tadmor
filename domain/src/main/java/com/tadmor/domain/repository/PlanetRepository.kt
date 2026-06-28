package com.tadmor.domain.repository

import com.tadmor.domain.model.CandidatePlanet
import com.tadmor.domain.model.Planet
import com.tadmor.domain.model.Star
import kotlinx.coroutines.flow.Flow

interface PlanetRepository {

    fun observeAllPlanets(): Flow<List<Planet>>

    fun observeAllStars(): Flow<List<Star>>

    fun observePlanetsByHostname(hostname: String): Flow<List<Planet>>

    fun observePlanetByName(name: String): Flow<Planet?>

    fun observeStarByHostname(hostname: String): Flow<Star?>

    fun searchStars(query: String): Flow<List<Star>>

    fun searchStarsByHostnames(hostnames: List<String>): Flow<List<Star>>

    fun observeCompanionStars(syName: String): Flow<List<Star>>

    /**
     * Returns all candidate-planet rows (TOI, eventually KOI) with a
     * placeholder hostname of "TIC <id>". Use cases reconcile against
     * the live stars table to swap in the real hostname when available.
     */
    fun observeAllCandidates(): Flow<List<CandidatePlanet>>

    /**
     * Fetch from TAP API, map to entities, upsert into Room.
     * Throws on network/parse failure — caller decides retry policy.
     */
    suspend fun sync()
}
