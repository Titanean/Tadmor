package com.tadmor.data.remote

/**
 * DTO for a row in NASA's TOI or KOI candidate tables. Field names are
 * generic across both sources — the [source] discriminator selects which
 * raw column maps to each field at parse time.
 *
 * - TOI: [sourceId] = `toi`, [hostId] = `tid` (TIC), [disposition] =
 *   `tfopwg_disp`, [properHostname] always null.
 * - KOI: [sourceId] = `kepoi_name` (e.g. K00752.01), [hostId] = `kepid`
 *   (KIC), [disposition] = `koi_disposition`, [properHostname] =
 *   `kepler_name` when the candidate has been promoted (e.g.
 *   "Kepler-186 b").
 */
data class CandidateDto(
    /** Source-native primary identifier. */
    val sourceId: String,
    /** Source-native host identifier (TIC for TOI, KIC for KOI). */
    val hostId: String,
    /** Raw disposition string (TFOPWG flag for TOI, koi_disposition for KOI). */
    val disposition: String?,
    /**
     * Kepler proper name when the body has been promoted to a confirmed
     * Kepler-XXX b (KOI only; null otherwise). Used to derive a real
     * hostname for hostname matching against `ps`-derived stars.
     */
    val properHostname: String? = null,
    val plRade: Double? = null,
    val plOrbper: Double? = null,
    val plEqt: Double? = null,
    val plInsol: Double? = null,
    val plTranmid: Double? = null,
    val stTeff: Double? = null,
    val stRad: Double? = null,
    val stDist: Double? = null,
    val ra: Double? = null,
    val dec: Double? = null,
    val toiCreated: String? = null,
    /** "TOI" or "KOI". */
    val source: String,
)
