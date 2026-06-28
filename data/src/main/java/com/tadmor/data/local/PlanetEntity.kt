package com.tadmor.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for the planets table.
 * Schema per SPEC.md Section 4.4: primary key pl_name, nullable numeric columns,
 * foreign key to stars, indices on hostname/discoveryMethod/discoveryYear/eqTempK.
 */
@Entity(
    tableName = "planets",
    foreignKeys = [
        ForeignKey(
            entity = StarEntity::class,
            parentColumns = ["hostname"],
            childColumns = ["hostname"],
            onDelete = ForeignKey.CASCADE,
            deferred = true,
        )
    ],
    indices = [
        Index("hostname"),
        Index("discoveryMethod"),
        Index("discoveryYear"),
        Index("eqTempK"),
    ]
)
data class PlanetEntity(
    @PrimaryKey
    @ColumnInfo(name = "pl_name") val plName: String,
    @ColumnInfo(name = "hostname") val hostname: String,
    @ColumnInfo(name = "massJupiter") val massJupiter: Double?,
    @ColumnInfo(name = "massJupiterLimit") val massJupiterLimit: Int?,
    @ColumnInfo(name = "massEarth") val massEarth: Double?,
    @ColumnInfo(name = "massEarthLimit") val massEarthLimit: Int?,
    @ColumnInfo(name = "radiusEarth") val radiusEarth: Double?,
    @ColumnInfo(name = "radiusEarthLimit") val radiusEarthLimit: Int?,
    @ColumnInfo(name = "semiMajorAxisAU") val semiMajorAxisAU: Double?,
    @ColumnInfo(name = "semiMajorAxisLimit") val semiMajorAxisLimit: Int?,
    @ColumnInfo(name = "orbitalPeriodDays") val orbitalPeriodDays: Double?,
    @ColumnInfo(name = "orbitalPeriodLimit") val orbitalPeriodLimit: Int?,
    @ColumnInfo(name = "eccentricity") val eccentricity: Double?,
    @ColumnInfo(name = "eccentricityLimit") val eccentricityLimit: Int?,
    @ColumnInfo(name = "inclination") val inclination: Double?,
    @ColumnInfo(name = "inclinationLimit") val inclinationLimit: Int?,
    @ColumnInfo(name = "longOfPeriapsis") val longOfPeriapsis: Double?,
    @ColumnInfo(name = "longOfPeriapsisLimit") val longOfPeriapsisLimit: Int?,
    @ColumnInfo(name = "eqTempK") val eqTempK: Double?,
    @ColumnInfo(name = "eqTempKLimit") val eqTempKLimit: Int?,
    @ColumnInfo(name = "insolationFlux") val insolationFlux: Double?,
    @ColumnInfo(name = "insolationFluxLimit") val insolationFluxLimit: Int?,
    @ColumnInfo(name = "densityGCm3") val densityGCm3: Double?,
    @ColumnInfo(name = "densityLimit") val densityLimit: Int?,
    @ColumnInfo(name = "transitMidpointBJD") val transitMidpointBJD: Double? = null,
    @ColumnInfo(name = "timeOfPeriapsisBJD") val timeOfPeriapsisBJD: Double? = null,
    @ColumnInfo(name = "discoveryMethod") val discoveryMethod: String?,
    @ColumnInfo(name = "discoveryYear") val discoveryYear: Int?,
    @ColumnInfo(name = "discoveryPubDate") val discoveryPubDate: String? = null,
    @ColumnInfo(name = "cbFlag", defaultValue = "0") val cbFlag: Boolean = false,
    @ColumnInfo(name = "syName") val syName: String? = null,
)
