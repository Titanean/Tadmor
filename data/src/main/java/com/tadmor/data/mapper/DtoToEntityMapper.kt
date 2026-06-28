package com.tadmor.data.mapper

import com.tadmor.data.local.CandidatePlanetEntity
import com.tadmor.data.local.PlanetEntity
import com.tadmor.data.local.StarEntity
import com.tadmor.data.remote.CandidateDto
import com.tadmor.data.remote.StellarHostDto
import com.tadmor.data.remote.TapApiDto

/**
 * Maps flat TAP API rows into separate planet and star entities.
 * Deduplicates stars by hostname — takes the first non-null value
 * for each stellar field across all rows sharing the same host.
 */
object DtoToEntityMapper {

    /**
     * Maps a candidate DTO (TOI / KOI / K2) to its entity. Skips rows
     * without an intelligible disposition. Computes a [hostname] at
     * ingest:
     *   - TOI: "TIC <id>" placeholder; the use case may swap this for
     *     a real hostname when the TIC matches an existing Star.
     *   - KOI: derived from `kepler_name` (strip planet letter) when
     *     present, else "KIC <id>".
     *   - K2: pre-resolved hostname from the `hostname` column directly
     *     (e.g. "K2-3" or "EPIC 211089792").
     */
    fun mapCandidate(dto: CandidateDto): CandidatePlanetEntity? {
        com.tadmor.domain.model.Disposition.fromRaw(dto.disposition) ?: return null
        // Normalise source IDs to user-friendly form before prefixing.
        // KOI's `kepoi_name` is "K00752.01" — the leading K stands for Kepler
        // and the numeric is zero-padded to 5 digits. NASA's published
        // convention is "KOI-752.01" without the K and without padding.
        val normalisedSourceId = when (dto.source) {
            "KOI" -> normaliseKoiId(dto.sourceId)
            else -> dto.sourceId
        }
        // candidateId is the bookmark / display key. K2's `pl_name` is
        // already a fully-namespaced display name ("K2-3 b" / "EPIC X b")
        // — no source prefix needed. Other sources prefix to disambiguate.
        val candidateId = when (dto.source) {
            "K2" -> normalisedSourceId
            else -> "${dto.source}-$normalisedSourceId"
        }
        val hostname = when (dto.source) {
            "TOI" -> "TIC ${dto.hostId}"
            "KOI" -> dto.properHostname?.trim()
                ?.let { stripKeplerPlanetLetter(it) }
                ?: "KIC ${dto.hostId}"
            "K2" -> dto.properHostname?.trim() ?: dto.hostId
            else -> "TIC ${dto.hostId}"
        }
        return CandidatePlanetEntity(
            candidateId = candidateId,
            hostId = dto.hostId,
            hostname = hostname,
            disposition = dto.disposition!!.trim().uppercase(),
            radiusEarth = dto.plRade,
            orbitalPeriodDays = dto.plOrbper,
            eqTempK = dto.plEqt,
            insolationFlux = dto.plInsol,
            transitMidpointBJD = dto.plTranmid,
            hostTeffK = dto.stTeff,
            hostRadiusSolar = dto.stRad,
            hostDistancePc = dto.stDist,
            ra = dto.ra,
            dec = dto.dec,
            createdDate = dto.toiCreated,
            source = dto.source,
        )
    }

    /**
     * "Kepler-186 b" → "Kepler-186". Returns the input unchanged when no
     * trailing space-letter pattern is found (defensive — the column
     * occasionally carries odd values).
     */
    private fun stripKeplerPlanetLetter(name: String): String {
        // Trailing pattern: " <single lower-case letter>" at end of string
        val regex = Regex(" [a-z]$")
        return regex.replace(name, "")
    }

    /**
     * "K00752.01" → "752.01". Strips the leading K (an internal-archive
     * convention) and any leading zeros from the integer portion before
     * the dot. Standard NASA convention for KOI references is
     * `KOI-752.01`, not `KOI-K00752.01`.
     */
    private fun normaliseKoiId(rawKepoi: String): String {
        val trimmed = rawKepoi.trim().removePrefix("K").removePrefix("k")
        // Strip leading zeros from the integer portion only — preserve the
        // ".01" planet-index suffix as-is.
        val dot = trimmed.indexOf('.')
        return if (dot >= 0) {
            val intPart = trimmed.substring(0, dot).trimStart('0').ifEmpty { "0" }
            val rest = trimmed.substring(dot)
            "$intPart$rest"
        } else {
            trimmed.trimStart('0').ifEmpty { "0" }
        }
    }

    fun mapPlanet(dto: TapApiDto): PlanetEntity = PlanetEntity(
        plName = dto.plName,
        hostname = dto.hostname,
        massJupiter = dto.plBmassj,
        massJupiterLimit = dto.plBmassjlim,
        massEarth = dto.plBmasse,
        massEarthLimit = dto.plBmasselim,
        radiusEarth = dto.plRade,
        radiusEarthLimit = dto.plRadelim,
        semiMajorAxisAU = dto.plOrbsmax,
        semiMajorAxisLimit = dto.plOrbsmaxlim,
        orbitalPeriodDays = dto.plOrbper,
        orbitalPeriodLimit = dto.plOrbperlim,
        eccentricity = dto.plOrbeccen,
        eccentricityLimit = dto.plOrbeccenlim,
        inclination = dto.plOrbincl,
        inclinationLimit = dto.plOrbincllim,
        longOfPeriapsis = dto.plOrblper,
        longOfPeriapsisLimit = dto.plOrblperlim,
        eqTempK = dto.plEqt,
        eqTempKLimit = dto.plEqtlim,
        insolationFlux = dto.plInsol,
        insolationFluxLimit = dto.plInsollim,
        densityGCm3 = dto.plDens,
        densityLimit = dto.plDenslim,
        transitMidpointBJD = dto.plTranmid,
        timeOfPeriapsisBJD = dto.plOrbtper,
        discoveryMethod = dto.discoverymethod,
        discoveryYear = dto.discYear,
        discoveryPubDate = dto.discPubdate,
        cbFlag = dto.cbFlag == 1,
        syName = dto.syName,
    )

    /**
     * Groups DTOs by hostname. For each group, merges stellar fields
     * by taking the first non-null value found. Then fills in any remaining
     * gaps from the dedicated stellarhosts table data.
     */
    fun extractUniqueStars(
        dtos: List<TapApiDto>,
        stellarHosts: List<StellarHostDto> = emptyList(),
    ): List<StarEntity> {
        val hostMap = stellarHosts.associateBy { it.hostname }

        return dtos.groupBy { it.hostname }.map { (hostname, rows) ->
            val sh = hostMap[hostname]
            // For limit flags, use the limit from whichever source provided the value
            val teff = rows.firstNotNullOfOrNull { it.stTeff }
            val teffSource = if (teff != null) rows.first { it.stTeff != null } else null
            val rad = rows.firstNotNullOfOrNull { it.stRad }
            val radSource = if (rad != null) rows.first { it.stRad != null } else null
            val mass = rows.firstNotNullOfOrNull { it.stMass }
            val massSource = if (mass != null) rows.first { it.stMass != null } else null
            val lum = rows.firstNotNullOfOrNull { it.stLum }
            val lumSource = if (lum != null) rows.first { it.stLum != null } else null

            StarEntity(
                hostname = hostname,
                spectralType = rows.firstNotNullOfOrNull { it.stSpectype } ?: sh?.stSpectype,
                starTeffK = teff ?: sh?.stTeff,
                starTeffKLimit = teffSource?.stTefflim ?: if (teff == null) sh?.stTefflim else null,
                starRadiusSolar = rad ?: sh?.stRad,
                starRadiusLimit = radSource?.stRadlim ?: if (rad == null) sh?.stRadlim else null,
                starMassSolar = mass ?: sh?.stMass,
                starMassLimit = massSource?.stMasslim ?: if (mass == null) sh?.stMasslim else null,
                starLogLuminosity = lum ?: sh?.stLum,
                starLogLuminosityLimit = lumSource?.stLumlim ?: if (lum == null) sh?.stLumlim else null,
                rightAscensionDeg = rows.firstNotNullOfOrNull { it.ra } ?: sh?.ra,
                declinationDeg = rows.firstNotNullOfOrNull { it.dec } ?: sh?.dec,
                distancePc = rows.firstNotNullOfOrNull { it.syDist } ?: sh?.syDist,
                planetCount = rows.firstNotNullOfOrNull { it.syPnum } ?: sh?.syPnum,
                hdName = rows.firstNotNullOfOrNull { it.hdName },
                hipName = rows.firstNotNullOfOrNull { it.hipName },
                ticId = rows.firstNotNullOfOrNull { it.ticId },
                syName = rows.firstNotNullOfOrNull { it.syName } ?: sh?.syName,
                sySnum = rows.firstNotNullOfOrNull { it.sySnum } ?: sh?.sySnum,
                metallicity = sh?.stMet,
                metallicityLimit = sh?.stMetlim,
                age = sh?.stAge,
                ageLimit = sh?.stAgelim,
                logg = sh?.stLogg,
                loggLimit = sh?.stLogglim,
                rotationPeriodDays = sh?.stRotp,
            )
        }
    }

    /**
     * Identifies companion stars from stellarhosts that are NOT planet hosts.
     * Stars sharing a sy_name with a primary but whose hostname doesn't appear
     * in the primary star list are companion stars (e.g., "Kepler-16 B").
     */
    fun extractCompanionStars(
        primaryStars: List<StarEntity>,
        stellarHosts: List<StellarHostDto>,
    ): List<StarEntity> {
        val primaryHostnames = primaryStars.map { it.hostname }.toSet()
        // Build sy_name → primary star lookup for multi-star systems
        val syNameToPrimary = primaryStars
            .filter { it.sySnum != null && it.sySnum >= 2 && it.syName != null }
            .associateBy { it.syName!! }

        // Group all non-primary stars by system, then pick only the close binary
        // companion for each. In stellar naming: "X B" for simple binaries,
        // "X Ab" for hierarchical quadruples. Distant pairs (Ba, Bb) are excluded.
        return stellarHosts
            .filter { sh ->
                sh.hostname !in primaryHostnames &&
                    sh.syName != null &&
                    sh.syName in syNameToPrimary
            }
            .distinctBy { it.hostname }
            .groupBy { it.syName!! }
            .mapNotNull { (syName, companions) ->
                val primary = syNameToPrimary[syName]!!
                val pHost = primary.hostname
                // Pick close binary companion: "X B" or "X Ab", fallback to first
                val close = companions.find { it.hostname == "$pHost B" }
                    ?: companions.find { it.hostname == "$pHost Ab" }
                    ?: companions.firstOrNull()
                close?.let { sh ->
                    StarEntity(
                        hostname = sh.hostname,
                        spectralType = sh.stSpectype,
                        starTeffK = sh.stTeff,
                        starTeffKLimit = sh.stTefflim,
                        starRadiusSolar = sh.stRad,
                        starRadiusLimit = sh.stRadlim,
                        starMassSolar = sh.stMass,
                        starMassLimit = sh.stMasslim,
                        starLogLuminosity = sh.stLum,
                        starLogLuminosityLimit = sh.stLumlim,
                        rightAscensionDeg = sh.ra ?: primary.rightAscensionDeg,
                        declinationDeg = sh.dec ?: primary.declinationDeg,
                        distancePc = sh.syDist ?: primary.distancePc,
                        planetCount = 0, // Companions have no planets by hostname
                        syName = sh.syName,
                        sySnum = sh.sySnum,
                        isPrimary = false,
                        metallicity = sh.stMet,
                        metallicityLimit = sh.stMetlim,
                        age = sh.stAge,
                        ageLimit = sh.stAgelim,
                        logg = sh.stLogg,
                        loggLimit = sh.stLogglim,
                        rotationPeriodDays = sh.stRotp,
                    )
                }
            }
    }
}
