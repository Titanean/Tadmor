package com.tadmor.data.mapper

import com.tadmor.data.local.CandidatePlanetEntity
import com.tadmor.data.local.PlanetEntity
import com.tadmor.data.local.StarEntity
import com.tadmor.domain.model.CandidatePlanet
import com.tadmor.domain.model.Disposition
import com.tadmor.domain.model.Planet
import com.tadmor.domain.model.Star

object EntityToDomainMapper {

    /**
     * Maximum planet-name length on output. NASA TAP catalogue designations
     * cap out around 30 characters in practice; anything longer than this
     * is a data-corruption case (observed: a 266 k-char run of garbage
     * bytes stored against an otherwise-valid planet row, which made the
     * UI thrash gigabytes of allocations during sort / text layout).
     * Sanitised on the way out of the data layer so every consumer
     * downstream — classifier, visual pipeline, catalog UI, system
     * detail — sees a sensible string regardless of what got into the
     * database.
     */
    private const val MAX_PLANET_NAME_LEN = 64

    private fun sanitizePlanetName(raw: String): String {
        if (raw.length <= MAX_PLANET_NAME_LEN) return raw
        timber.log.Timber.w(
            "EntityToDomainMapper: planet name truncated from " +
            "${raw.length} chars to $MAX_PLANET_NAME_LEN — likely catalog " +
            "corruption (first 40 chars: '${raw.take(40)}')"
        )
        return raw.take(MAX_PLANET_NAME_LEN)
    }

    fun mapPlanet(entity: PlanetEntity): Planet = Planet(
        name = sanitizePlanetName(entity.plName),
        hostname = entity.hostname,
        massJupiter = entity.massJupiter,
        massJupiterLimit = entity.massJupiterLimit,
        massEarth = entity.massEarth,
        massEarthLimit = entity.massEarthLimit,
        radiusEarth = entity.radiusEarth,
        radiusEarthLimit = entity.radiusEarthLimit,
        semiMajorAxisAU = entity.semiMajorAxisAU,
        semiMajorAxisLimit = entity.semiMajorAxisLimit,
        orbitalPeriodDays = entity.orbitalPeriodDays,
        orbitalPeriodLimit = entity.orbitalPeriodLimit,
        eccentricity = entity.eccentricity,
        eccentricityLimit = entity.eccentricityLimit,
        inclination = entity.inclination,
        inclinationLimit = entity.inclinationLimit,
        longOfPeriapsis = entity.longOfPeriapsis,
        longOfPeriapsisLimit = entity.longOfPeriapsisLimit,
        eqTempK = entity.eqTempK,
        eqTempKLimit = entity.eqTempKLimit,
        insolationFlux = entity.insolationFlux,
        insolationFluxLimit = entity.insolationFluxLimit,
        densityGCm3 = entity.densityGCm3,
        densityLimit = entity.densityLimit,
        transitMidpointBJD = entity.transitMidpointBJD,
        timeOfPeriapsisBJD = entity.timeOfPeriapsisBJD,
        discoveryMethod = entity.discoveryMethod,
        discoveryYear = entity.discoveryYear,
        discoveryPubDate = entity.discoveryPubDate,
        cbFlag = entity.cbFlag,
        syName = entity.syName,
    )

    fun mapStar(entity: StarEntity): Star = Star(
        hostname = entity.hostname,
        spectralType = entity.spectralType,
        teffK = entity.starTeffK,
        teffKLimit = entity.starTeffKLimit,
        radiusSolar = entity.starRadiusSolar,
        radiusSolarLimit = entity.starRadiusLimit,
        massSolar = entity.starMassSolar,
        massSolarLimit = entity.starMassLimit,
        logLuminosity = entity.starLogLuminosity,
        logLuminosityLimit = entity.starLogLuminosityLimit,
        rightAscensionDeg = entity.rightAscensionDeg,
        declinationDeg = entity.declinationDeg,
        distancePc = entity.distancePc,
        planetCount = entity.planetCount,
        hdName = entity.hdName,
        hipName = entity.hipName,
        ticId = entity.ticId,
        syName = entity.syName,
        sySnum = entity.sySnum,
        isPrimary = entity.isPrimary,
        metallicity = entity.metallicity,
        metallicityLimit = entity.metallicityLimit,
        age = entity.age,
        ageLimit = entity.ageLimit,
        logg = entity.logg,
        loggLimit = entity.loggLimit,
        rotationPeriodDays = entity.rotationPeriodDays,
    )

    fun mapPlanets(entities: List<PlanetEntity>): List<Planet> = entities.map(::mapPlanet)

    fun mapStars(entities: List<StarEntity>): List<Star> = entities.map(::mapStar)

    /**
     * Maps a candidate entity to a domain [CandidatePlanet]. Returns null
     * for rows with an unparseable disposition (defensive — these should
     * have been filtered at ingestion).
     */
    fun mapCandidate(entity: CandidatePlanetEntity): CandidatePlanet? {
        val disposition = Disposition.fromRaw(entity.disposition) ?: return null
        // K2 candidateIds are unprefixed (already-namespaced "K2-3 b" /
        // "EPIC X b"); TOI / KOI carry their source prefix.
        val sourceId = if (entity.source == "K2") {
            entity.candidateId
        } else {
            entity.candidateId.removePrefix("${entity.source}-")
        }
        return CandidatePlanet(
            sourceId = sourceId,
            hostId = entity.hostId,
            disposition = disposition,
            hostname = entity.hostname,
            radiusEarth = entity.radiusEarth,
            orbitalPeriodDays = entity.orbitalPeriodDays,
            eqTempK = entity.eqTempK,
            insolationFlux = entity.insolationFlux,
            transitMidpointBJD = entity.transitMidpointBJD,
            hostTeffK = entity.hostTeffK,
            hostRadiusSolar = entity.hostRadiusSolar,
            hostDistancePc = entity.hostDistancePc,
            ra = entity.ra,
            dec = entity.dec,
            createdDate = entity.createdDate,
            source = entity.source,
        )
    }
}
