package com.tadmor.data.remote

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.TlsVersion
import timber.log.Timber
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TapApiService @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val client = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectionSpecs(listOf(
            ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                .tlsVersions(TlsVersion.TLS_1_2)
                .build(),
            ConnectionSpec.CLEARTEXT,
        ))
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAP_BASE_URL =
            "https://exoplanetarchive.ipac.caltech.edu/TAP/sync"

        private const val PLANET_COLUMNS =
            "pl_name, hostname, " +
            "pl_bmassj, pl_bmassjlim, pl_bmasse, pl_bmasselim, " +
            "pl_rade, pl_radelim, " +
            "pl_orbsmax, pl_orbsmaxlim, pl_orbper, pl_orbperlim, " +
            "pl_orbeccen, pl_orbeccenlim, pl_orbincl, pl_orbincllim, " +
            "pl_orblper, pl_orblperlim, " +
            "pl_eqt, pl_eqtlim, pl_insol, pl_insollim, " +
            "pl_dens, pl_denslim, " +
            "pl_tranmid, pl_orbtper, " +
            "discoverymethod, disc_year, disc_pubdate, " +
            "st_spectype, st_teff, st_tefflim, st_rad, st_radlim, " +
            "st_mass, st_masslim, st_lum, st_lumlim, " +
            "ra, dec, sy_dist, sy_pnum, " +
            "hd_name, hip_name, tic_id, " +
            "cb_flag, sy_snum, sy_name"

        private const val STELLAR_COLUMNS =
            "hostname, st_spectype, " +
            "st_teff, st_tefflim, st_rad, st_radlim, " +
            "st_mass, st_masslim, st_lum, st_lumlim, " +
            "st_met, st_metlim, st_age, st_agelim, st_logg, st_logglim, " +
            "st_rotp, " +
            "ra, dec, sy_dist, sy_pnum, " +
            "sy_name, sy_snum"

        private const val TOI_COLUMNS =
            "toi, tid, tfopwg_disp, " +
            "pl_rade, pl_orbper, pl_eqt, pl_insol, pl_tranmid, " +
            "st_teff, st_rad, st_dist, " +
            "ra, dec, toi_created"

        private const val KOI_COLUMNS =
            "kepoi_name, kepid, koi_disposition, kepler_name, " +
            "koi_prad, koi_period, koi_teq, koi_insol, koi_time0bk, " +
            "koi_steff, koi_srad, " +
            "ra, dec"

        // K2 Planets and Candidates table (`k2pandc`) — covers the K2 mission
        // (2014–2018) extended after Kepler's reaction-wheel failure. Mix of
        // confirmed K2-named planets (which dedup against pscomppars) and
        // candidates with EPIC-style host names. Columns picked to match
        // the shape of TOI/KOI so the same DTO + mapper handles all three.
        private const val K2_COLUMNS =
            "pl_name, hostname, disposition, " +
            "pl_rade, pl_orbper, pl_eqt, pl_insol, pl_tranmid, " +
            "st_teff, st_rad, sy_dist, " +
            "ra, dec"
    }

    /**
     * Fetches all planets from pscomppars in CSV format (~1.4 MB).
     * CSV is ~75% smaller than JSON, staying under the BoringSSL TLS threshold.
     */
    suspend fun fetchAllPlanets(): List<TapApiDto> = withContext(Dispatchers.IO) {
        val query = "SELECT $PLANET_COLUMNS FROM pscomppars"
        Timber.d("Fetching all planets (CSV)...")
        val file = downloadCsv(query, "tap_planets.csv")
        val rows = parsePlanetCsv(file)
        file.delete()
        Timber.d("Parsed ${rows.size} planet rows")
        rows
    }

    /**
     * Fetches all stellar hosts in CSV format (~3.5 MB).
     * If the single request fails (TLS bug on some devices), falls back
     * to fetching in smaller hostname-range chunks.
     */
    suspend fun fetchStellarHosts(): List<StellarHostDto> = withContext(Dispatchers.IO) {
        try {
            Timber.d("Fetching all stellar hosts (CSV)...")
            val file = downloadCsv(
                "SELECT $STELLAR_COLUMNS FROM stellarhosts",
                "tap_stars.csv",
            )
            val rows = parseStellarCsv(file)
            file.delete()
            Timber.d("Parsed ${rows.size} stellar host rows")
            return@withContext rows
        } catch (e: Exception) {
            Timber.w(e, "Single stellar fetch failed, falling back to chunked fetch")
        }

        // Chunked fallback — each piece under 900 KB
        val allRows = mutableListOf<StellarHostDto>()
        val chunks = listOf(
            "hostname < 'HD'" to "tap_stars_1.csv",
            "hostname >= 'HD' AND hostname < 'K'" to "tap_stars_2.csv",
            "hostname >= 'K' AND hostname < 'Kepler-15'" to "tap_stars_3.csv",
            "hostname >= 'Kepler-15' AND hostname < 'Kepler-2'" to "tap_stars_4.csv",
            "hostname >= 'Kepler-2' AND hostname < 'Kepler-5'" to "tap_stars_5.csv",
            "hostname >= 'Kepler-5' AND hostname < 'L'" to "tap_stars_6.csv",
            "hostname >= 'L'" to "tap_stars_7.csv",
        )
        for ((where, fileName) in chunks) {
            val query = "SELECT $STELLAR_COLUMNS FROM stellarhosts WHERE $where"
            Timber.d("Fetching stellar hosts chunk ($fileName)...")
            val file = downloadCsv(query, fileName)
            val rows = parseStellarCsv(file)
            file.delete()
            allRows.addAll(rows)
            Timber.d("Parsed ${rows.size} rows from $fileName")
        }
        Timber.d("Total stellar hosts (chunked): ${allRows.size}")
        allRows
    }

    /**
     * Fetches all Kepler Objects of Interest (KOI). The Kepler mission
     * ended in 2018 and the table is essentially frozen — primary value
     * is the false-positive archive (~half of all KOI rows). Reuses the
     * same [CandidateDto] shape as TOI; the [CandidateDto.source] flag
     * disambiguates downstream.
     */
    /**
     * Fetches K2 Planets and Candidates from the `k2pandc` table. The K2
     * mission ran 2014–2018 after Kepler's reaction-wheel failure; the
     * table mixes confirmed K2-named planets (which dedup against
     * pscomppars at sync time) with candidates that still carry their
     * EPIC-style host names. Roughly 1,000 rows — small payload.
     */
    suspend fun fetchK2(): List<CandidateDto> = withContext(Dispatchers.IO) {
        val query = "SELECT $K2_COLUMNS FROM k2pandc"
        Timber.d("Fetching all K2 candidates (CSV)...")
        val file = downloadCsv(query, "tap_k2.csv")
        val rows = parseK2Csv(file)
        file.delete()
        Timber.d("Parsed ${rows.size} K2 rows")
        rows
    }

    private fun parseK2Csv(file: File): List<CandidateDto> {
        val rows = mutableListOf<CandidateDto>()
        BufferedReader(InputStreamReader(file.inputStream(), Charsets.UTF_8)).use { reader ->
            reader.readLine() // skip header
            reader.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val f = parseCsvLine(line)
                if (f.size < 13) return@forEachLine
                val plName = f[0].unquote()
                val hostname = f[1].unquote()
                if (plName.isEmpty() || hostname.isEmpty()) return@forEachLine
                rows.add(
                    CandidateDto(
                        sourceId = plName,                       // pl_name e.g. "K2-3 b" or "EPIC 201367065 b"
                        hostId = hostname,                       // hostname IS the host catalog ID for K2
                        disposition = f[2].unquoteOrNull(),      // disposition (CONFIRMED/CANDIDATE/REFUTED/FALSE POSITIVE)
                        properHostname = f[1].unquoteOrNull(),   // hostname is already the resolved host
                        plRade = f[3].toDoubleOrNull(),
                        plOrbper = f[4].toDoubleOrNull(),
                        plEqt = f[5].toDoubleOrNull(),
                        plInsol = f[6].toDoubleOrNull(),
                        plTranmid = f[7].toDoubleOrNull(),
                        stTeff = f[8].toDoubleOrNull(),
                        stRad = f[9].toDoubleOrNull(),
                        stDist = f[10].toDoubleOrNull(),         // sy_dist
                        ra = f[11].toDoubleOrNull(),
                        dec = f[12].toDoubleOrNull(),
                        toiCreated = null,
                        source = "K2",
                    ),
                )
            }
        }
        return rows
    }

    suspend fun fetchKoi(): List<CandidateDto> = withContext(Dispatchers.IO) {
        // Use the `cumulative` KOI table rather than `q1_q17_dr25_koi` —
        // cumulative is the live current-state catalog that NASA recommends
        // for general use, including dispositions updated post-DR25 and
        // any KOIs added/revised after the final pipeline release.
        val query = "SELECT $KOI_COLUMNS FROM cumulative"
        Timber.d("Fetching all KOI candidates (CSV)...")
        val file = downloadCsv(query, "tap_koi.csv")
        val rows = parseKoiCsv(file)
        file.delete()
        Timber.d("Parsed ${rows.size} KOI rows")
        rows
    }

    private fun parseKoiCsv(file: File): List<CandidateDto> {
        val rows = mutableListOf<CandidateDto>()
        BufferedReader(InputStreamReader(file.inputStream(), Charsets.UTF_8)).use { reader ->
            reader.readLine() // skip header
            reader.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val f = parseCsvLine(line)
                if (f.size < 13) return@forEachLine
                val kepoi = f[0].unquote()
                val kepid = f[1].unquote()
                if (kepoi.isEmpty() || kepid.isEmpty()) return@forEachLine
                rows.add(
                    CandidateDto(
                        sourceId = kepoi,                       // kepoi_name e.g. K00752.01
                        hostId = kepid,                         // bare numeric KIC
                        disposition = f[2].unquoteOrNull(),     // koi_disposition
                        properHostname = f[3].unquoteOrNull(),  // kepler_name e.g. "Kepler-186 b"
                        plRade = f[4].toDoubleOrNull(),         // koi_prad
                        plOrbper = f[5].toDoubleOrNull(),       // koi_period
                        plEqt = f[6].toDoubleOrNull(),          // koi_teq
                        plInsol = f[7].toDoubleOrNull(),        // koi_insol
                        plTranmid = f[8].toDoubleOrNull(),      // koi_time0bk
                        stTeff = f[9].toDoubleOrNull(),         // koi_steff
                        stRad = f[10].toDoubleOrNull(),         // koi_srad
                        stDist = null,                           // KOI table has no distance column
                        ra = f[11].toDoubleOrNull(),
                        dec = f[12].toDoubleOrNull(),
                        toiCreated = null,                       // KOI is frozen — no created date
                        source = "KOI",
                    ),
                )
            }
        }
        return rows
    }

    /**
     * Fetches all TESS Objects of Interest (TOI) candidates and false
     * positives in CSV format. Roughly 7,500 rows; payload typically
     * under 1 MB. The disposition column [tfopwg_disp] drives the user-
     * facing CANDIDATE / FALSE_POSITIVE / CONFIRMED bucketing.
     */
    suspend fun fetchToi(): List<CandidateDto> = withContext(Dispatchers.IO) {
        val query = "SELECT $TOI_COLUMNS FROM toi"
        Timber.d("Fetching all TOI candidates (CSV)...")
        val file = downloadCsv(query, "tap_toi.csv")
        val rows = parseToiCsv(file)
        file.delete()
        Timber.d("Parsed ${rows.size} TOI rows")
        rows
    }

    private fun parseToiCsv(file: File): List<CandidateDto> {
        val rows = mutableListOf<CandidateDto>()
        BufferedReader(InputStreamReader(file.inputStream(), Charsets.UTF_8)).use { reader ->
            reader.readLine() // skip header
            reader.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val f = parseCsvLine(line)
                if (f.size < 14) return@forEachLine
                val toi = f[0].unquote()
                val tid = f[1].unquote()
                if (toi.isEmpty() || tid.isEmpty()) return@forEachLine
                rows.add(
                    CandidateDto(
                        sourceId = toi,
                        hostId = tid,
                        disposition = f[2].unquoteOrNull(),
                        plRade = f[3].toDoubleOrNull(),
                        plOrbper = f[4].toDoubleOrNull(),
                        plEqt = f[5].toDoubleOrNull(),
                        plInsol = f[6].toDoubleOrNull(),
                        plTranmid = f[7].toDoubleOrNull(),
                        stTeff = f[8].toDoubleOrNull(),
                        stRad = f[9].toDoubleOrNull(),
                        stDist = f[10].toDoubleOrNull(),
                        ra = f[11].toDoubleOrNull(),
                        dec = f[12].toDoubleOrNull(),
                        toiCreated = f[13].unquoteOrNull(),
                        source = "TOI",
                    ),
                )
            }
        }
        return rows
    }

    private fun downloadCsv(query: String, tempFileName: String): File {
        val url = TAP_BASE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("format", "csv")
            .build()

        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            error("TAP API returned HTTP ${response.code}")
        }

        val body = response.body ?: error("Empty response body")
        val tempFile = File(context.cacheDir, tempFileName)

        tempFile.outputStream().buffered().use { out ->
            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytes = 0L
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    out.write(buffer, 0, bytesRead)
                    totalBytes += bytesRead
                }
                Timber.d("Downloaded ${totalBytes / 1024} KB -> $tempFileName")
            }
        }

        return tempFile
    }

    // --- CSV parsing ---

    private fun parsePlanetCsv(file: File): List<TapApiDto> {
        val rows = mutableListOf<TapApiDto>()
        BufferedReader(InputStreamReader(file.inputStream(), Charsets.UTF_8)).use { reader ->
            reader.readLine() // skip header
            reader.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val f = parseCsvLine(line)
                if (f.size < 48) return@forEachLine

                rows.add(
                    TapApiDto(
                        plName = f[0].unquote(),          // 0
                        hostname = f[1].unquote(),         // 1
                        plBmassj = f[2].toDoubleOrNull(),  // 2
                        plBmassjlim = f[3].toIntOrNull(),  // 3
                        plBmasse = f[4].toDoubleOrNull(),  // 4
                        plBmasselim = f[5].toIntOrNull(),  // 5
                        plRade = f[6].toDoubleOrNull(),    // 6
                        plRadelim = f[7].toIntOrNull(),    // 7
                        plOrbsmax = f[8].toDoubleOrNull(), // 8
                        plOrbsmaxlim = f[9].toIntOrNull(), // 9
                        plOrbper = f[10].toDoubleOrNull(), // 10
                        plOrbperlim = f[11].toIntOrNull(), // 11
                        plOrbeccen = f[12].toDoubleOrNull(), // 12
                        plOrbeccenlim = f[13].toIntOrNull(), // 13
                        plOrbincl = f[14].toDoubleOrNull(),  // 14
                        plOrbincllim = f[15].toIntOrNull(),  // 15
                        plOrblper = f[16].toDoubleOrNull(),  // 16
                        plOrblperlim = f[17].toIntOrNull(),  // 17
                        plEqt = f[18].toDoubleOrNull(),    // 18
                        plEqtlim = f[19].toIntOrNull(),    // 19
                        plInsol = f[20].toDoubleOrNull(),  // 20
                        plInsollim = f[21].toIntOrNull(),  // 21
                        plDens = f[22].toDoubleOrNull(),   // 22
                        plDenslim = f[23].toIntOrNull(),   // 23
                        plTranmid = f[24].toDoubleOrNull(), // 24
                        plOrbtper = f[25].toDoubleOrNull(), // 25
                        discoverymethod = f[26].unquoteOrNull(), // 26
                        discYear = f[27].toIntOrNull(),    // 27
                        discPubdate = f[28].unquoteOrNull(), // 28 — added for date-precise sort
                        stSpectype = f[29].unquoteOrNull(), // 29
                        stTeff = f[30].toDoubleOrNull(),   // 30
                        stTefflim = f[31].toIntOrNull(),   // 31
                        stRad = f[32].toDoubleOrNull(),    // 32
                        stRadlim = f[33].toIntOrNull(),    // 33
                        stMass = f[34].toDoubleOrNull(),   // 34
                        stMasslim = f[35].toIntOrNull(),   // 35
                        stLum = f[36].toDoubleOrNull(),    // 36
                        stLumlim = f[37].toIntOrNull(),    // 37
                        ra = f[38].toDoubleOrNull(),       // 38
                        dec = f[39].toDoubleOrNull(),      // 39
                        syDist = f[40].toDoubleOrNull(),   // 40
                        syPnum = f[41].toIntOrNull(),      // 41
                        hdName = f[42].unquoteOrNull(),    // 42
                        hipName = f[43].unquoteOrNull(),   // 43
                        ticId = f[44].unquoteOrNull(),     // 44
                        cbFlag = f[45].toIntOrNull(),      // 45
                        sySnum = f[46].toIntOrNull(),      // 46
                        syName = f[47].unquoteOrNull(),    // 47
                    ),
                )
            }
        }
        return rows
    }

    private fun parseStellarCsv(file: File): List<StellarHostDto> {
        val rows = mutableListOf<StellarHostDto>()
        BufferedReader(InputStreamReader(file.inputStream(), Charsets.UTF_8)).use { reader ->
            reader.readLine() // skip header
            reader.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val f = parseCsvLine(line)
                if (f.size < 23) return@forEachLine
                rows.add(
                    StellarHostDto(
                        hostname = f[0].unquote(),         // 0
                        stSpectype = f[1].unquoteOrNull(), // 1
                        stTeff = f[2].toDoubleOrNull(),    // 2
                        stTefflim = f[3].toIntOrNull(),    // 3
                        stRad = f[4].toDoubleOrNull(),     // 4
                        stRadlim = f[5].toIntOrNull(),     // 5
                        stMass = f[6].toDoubleOrNull(),    // 6
                        stMasslim = f[7].toIntOrNull(),    // 7
                        stLum = f[8].toDoubleOrNull(),     // 8
                        stLumlim = f[9].toIntOrNull(),     // 9
                        stMet = f[10].toDoubleOrNull(),    // 10
                        stMetlim = f[11].toIntOrNull(),    // 11
                        stAge = f[12].toDoubleOrNull(),    // 12
                        stAgelim = f[13].toIntOrNull(),    // 13
                        stLogg = f[14].toDoubleOrNull(),   // 14
                        stLogglim = f[15].toIntOrNull(),   // 15
                        stRotp = f[16].toDoubleOrNull(),   // 16
                        ra = f[17].toDoubleOrNull(),       // 17
                        dec = f[18].toDoubleOrNull(),      // 18
                        syDist = f[19].toDoubleOrNull(),   // 19
                        syPnum = f[20].toIntOrNull(),      // 20
                        syName = f[21].unquoteOrNull(),    // 21
                        sySnum = f[22].toIntOrNull(),      // 22
                    ),
                )
            }
        }
        return rows
    }

    /**
     * Parses a CSV line handling quoted fields (which may contain commas).
     */
    private fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && !inQuotes -> inQuotes = true
                c == '"' && inQuotes -> {
                    if (i + 1 < line.length && line[i + 1] == '"') {
                        current.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                }
                c == ',' && !inQuotes -> {
                    fields.add(current.toString())
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        fields.add(current.toString())
        return fields
    }

    private fun String.unquote(): String = trim().removeSurrounding("\"")

    private fun String.unquoteOrNull(): String? {
        val trimmed = trim()
        if (trimmed.isEmpty()) return null
        return trimmed.removeSurrounding("\"").ifEmpty { null }
    }
}
