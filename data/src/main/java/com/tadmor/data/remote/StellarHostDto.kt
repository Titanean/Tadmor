package com.tadmor.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTO for the stellarhosts table — provides complete stellar data
 * that may be missing from pscomppars rows.
 */
@Serializable
data class StellarHostDto(
    @SerialName("hostname") val hostname: String,
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
    @SerialName("sy_name") val syName: String? = null,
    @SerialName("sy_snum") val sySnum: Int? = null,
    @SerialName("st_met") val stMet: Double? = null,
    @SerialName("st_metlim") val stMetlim: Int? = null,
    @SerialName("st_age") val stAge: Double? = null,
    @SerialName("st_agelim") val stAgelim: Int? = null,
    @SerialName("st_logg") val stLogg: Double? = null,
    @SerialName("st_logglim") val stLogglim: Int? = null,
    @SerialName("st_rotp") val stRotp: Double? = null,
)
