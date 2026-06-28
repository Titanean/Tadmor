package com.tadmor.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTO matching the TAP API JSON response for the pscomppars table.
 * Field names are the exact TAP column names from SPEC.md Section 4.2.
 * All fields nullable — the API returns null for missing values.
 */
@Serializable
data class TapApiDto(
    // Planet parameters (SPEC.md Section 4.3.1)
    @SerialName("pl_name") val plName: String,
    @SerialName("hostname") val hostname: String,
    @SerialName("pl_bmassj") val plBmassj: Double? = null,
    @SerialName("pl_bmassjlim") val plBmassjlim: Int? = null,
    @SerialName("pl_bmasse") val plBmasse: Double? = null,
    @SerialName("pl_bmasselim") val plBmasselim: Int? = null,
    @SerialName("pl_rade") val plRade: Double? = null,
    @SerialName("pl_radelim") val plRadelim: Int? = null,
    @SerialName("pl_orbsmax") val plOrbsmax: Double? = null,
    @SerialName("pl_orbsmaxlim") val plOrbsmaxlim: Int? = null,
    @SerialName("pl_orbper") val plOrbper: Double? = null,
    @SerialName("pl_orbperlim") val plOrbperlim: Int? = null,
    @SerialName("pl_orbeccen") val plOrbeccen: Double? = null,
    @SerialName("pl_orbeccenlim") val plOrbeccenlim: Int? = null,
    @SerialName("pl_orbincl") val plOrbincl: Double? = null,
    @SerialName("pl_orbincllim") val plOrbincllim: Int? = null,
    @SerialName("pl_orblper") val plOrblper: Double? = null,
    @SerialName("pl_orblperlim") val plOrblperlim: Int? = null,
    @SerialName("pl_eqt") val plEqt: Double? = null,
    @SerialName("pl_eqtlim") val plEqtlim: Int? = null,
    @SerialName("pl_insol") val plInsol: Double? = null,
    @SerialName("pl_insollim") val plInsollim: Int? = null,
    @SerialName("pl_dens") val plDens: Double? = null,
    @SerialName("pl_denslim") val plDenslim: Int? = null,
    @SerialName("pl_tranmid") val plTranmid: Double? = null,
    @SerialName("pl_orbtper") val plOrbtper: Double? = null,
    @SerialName("discoverymethod") val discoverymethod: String? = null,
    @SerialName("disc_year") val discYear: Int? = null,
    // Discovery reference publication date — ISO YYYY-MM-DD when fully known,
    // YYYY-MM when only the month is, occasionally YYYY for older entries.
    // Used for newest-first sort granularity beyond what `discYear` alone allows.
    @SerialName("disc_pubdate") val discPubdate: String? = null,

    // Stellar parameters (SPEC.md Section 4.3.2)
    @SerialName("st_spectype") val stSpectype: String? = null,
    @SerialName("st_teff") val stTeff: Double? = null,
    @SerialName("st_tefflim") val stTefflim: Int? = null,
    @SerialName("st_rad") val stRad: Double? = null,
    @SerialName("st_radlim") val stRadlim: Int? = null,
    @SerialName("st_mass") val stMass: Double? = null,
    @SerialName("st_masslim") val stMasslim: Int? = null,
    @SerialName("st_lum") val stLum: Double? = null,
    @SerialName("st_lumlim") val stLumlim: Int? = null,
    @SerialName("ra") val ra: Double? = null,
    @SerialName("dec") val dec: Double? = null,
    @SerialName("sy_dist") val syDist: Double? = null,
    @SerialName("sy_pnum") val syPnum: Int? = null,

    // Alternate identifiers
    @SerialName("hd_name") val hdName: String? = null,
    @SerialName("hip_name") val hipName: String? = null,
    @SerialName("tic_id") val ticId: String? = null,

    // System/binary parameters
    @SerialName("cb_flag") val cbFlag: Int? = null,
    @SerialName("sy_snum") val sySnum: Int? = null,
    @SerialName("sy_name") val syName: String? = null,
)
