package com.tadmor.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for the stars table.
 * Schema per SPEC.md Section 4.4: primary key hostname,
 * indices on spectralType and distancePc.
 */
@Entity(
    tableName = "stars",
    indices = [
        Index("spectralType"),
        Index("distancePc"),
    ]
)
data class StarEntity(
    @PrimaryKey
    @ColumnInfo(name = "hostname") val hostname: String,
    @ColumnInfo(name = "spectralType") val spectralType: String?,
    @ColumnInfo(name = "starTeffK") val starTeffK: Double?,
    @ColumnInfo(name = "starTeffKLimit") val starTeffKLimit: Int?,
    @ColumnInfo(name = "starRadiusSolar") val starRadiusSolar: Double?,
    @ColumnInfo(name = "starRadiusLimit") val starRadiusLimit: Int?,
    @ColumnInfo(name = "starMassSolar") val starMassSolar: Double?,
    @ColumnInfo(name = "starMassLimit") val starMassLimit: Int?,
    @ColumnInfo(name = "starLogLuminosity") val starLogLuminosity: Double?,
    @ColumnInfo(name = "starLogLuminosityLimit") val starLogLuminosityLimit: Int?,
    @ColumnInfo(name = "rightAscensionDeg") val rightAscensionDeg: Double?,
    @ColumnInfo(name = "declinationDeg") val declinationDeg: Double?,
    @ColumnInfo(name = "distancePc") val distancePc: Double?,
    @ColumnInfo(name = "planetCount") val planetCount: Int?,
    @ColumnInfo(name = "hdName") val hdName: String? = null,
    @ColumnInfo(name = "hipName") val hipName: String? = null,
    @ColumnInfo(name = "ticId") val ticId: String? = null,
    @ColumnInfo(name = "syName") val syName: String? = null,
    @ColumnInfo(name = "sySnum") val sySnum: Int? = null,
    @ColumnInfo(name = "isPrimary", defaultValue = "1") val isPrimary: Boolean = true,
    @ColumnInfo(name = "metallicity") val metallicity: Double? = null,
    @ColumnInfo(name = "metallicityLimit") val metallicityLimit: Int? = null,
    @ColumnInfo(name = "age") val age: Double? = null,
    @ColumnInfo(name = "ageLimit") val ageLimit: Int? = null,
    @ColumnInfo(name = "logg") val logg: Double? = null,
    @ColumnInfo(name = "loggLimit") val loggLimit: Int? = null,
    @ColumnInfo(name = "rotationPeriodDays") val rotationPeriodDays: Double? = null,
)
