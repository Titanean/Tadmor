package com.tadmor.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [PlanetEntity::class, StarEntity::class, CandidatePlanetEntity::class],
    version = 12,
    exportSchema = true,
)
abstract class TadmorDatabase : RoomDatabase() {
    abstract fun planetDao(): PlanetDao
    abstract fun starDao(): StarDao
    abstract fun candidatePlanetDao(): CandidatePlanetDao
}
