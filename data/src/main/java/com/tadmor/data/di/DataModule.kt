package com.tadmor.data.di

import android.content.Context
import androidx.room.Room
import com.tadmor.data.local.BookmarkDao
import com.tadmor.data.local.BookmarksDatabase
import com.tadmor.data.local.CandidatePlanetDao
import com.tadmor.data.local.PlanetDao
import com.tadmor.data.local.StarDao
import com.tadmor.data.local.TadmorDatabase
import com.tadmor.data.repository.BookmarkRepositoryImpl
import com.tadmor.data.repository.PlanetRepositoryImpl
import com.tadmor.data.repository.SettingsRepositoryImpl
import com.tadmor.domain.repository.BookmarkRepository
import com.tadmor.domain.repository.PlanetRepository
import com.tadmor.domain.repository.SettingsRepository
import com.tadmor.domain.usecase.ObserveCandidatesUseCase
import com.tadmor.domain.usecase.ObserveCatalogUseCase
import com.tadmor.domain.usecase.ObserveStarMapUseCase
import com.tadmor.domain.usecase.ObserveSystemDetailUseCase
import com.tadmor.domain.usecase.SearchStarsUseCase
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds
    @Singleton
    abstract fun bindPlanetRepository(impl: PlanetRepositoryImpl): PlanetRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindBookmarkRepository(impl: BookmarkRepositoryImpl): BookmarkRepository

    companion object {

        @Provides
        @Singleton
        fun provideDatabase(@ApplicationContext context: Context): TadmorDatabase {
            return Room.databaseBuilder(
                context,
                TadmorDatabase::class.java,
                "tadmor.db",
            ).fallbackToDestructiveMigration(dropAllTables = true).build()
        }

        @Provides
        @Singleton
        fun provideBookmarksDatabase(@ApplicationContext context: Context): BookmarksDatabase {
            // No fallbackToDestructiveMigration — bookmarks are user data,
            // protected by proper migrations from v1 onwards.
            return Room.databaseBuilder(
                context,
                BookmarksDatabase::class.java,
                "bookmarks.db",
            ).build()
        }

        @Provides
        fun provideBookmarkDao(db: BookmarksDatabase): BookmarkDao = db.bookmarkDao()

        @Provides
        fun providePlanetDao(db: TadmorDatabase): PlanetDao = db.planetDao()

        @Provides
        fun provideStarDao(db: TadmorDatabase): StarDao = db.starDao()

        @Provides
        fun provideCandidatePlanetDao(db: TadmorDatabase): CandidatePlanetDao =
            db.candidatePlanetDao()

        @Provides
        @Singleton
        fun provideObserveCatalogUseCase(repository: PlanetRepository): ObserveCatalogUseCase =
            ObserveCatalogUseCase(repository)

        @Provides
        @Singleton
        fun provideSearchStarsUseCase(repository: PlanetRepository): SearchStarsUseCase =
            SearchStarsUseCase(repository)

        @Provides
        @Singleton
        fun provideObserveSystemDetailUseCase(
            repository: PlanetRepository,
        ): ObserveSystemDetailUseCase =
            ObserveSystemDetailUseCase(repository)

        @Provides
        @Singleton
        fun provideObserveStarMapUseCase(repository: PlanetRepository): ObserveStarMapUseCase =
            ObserveStarMapUseCase(repository)

        @Provides
        @Singleton
        fun provideObserveCandidatesUseCase(repository: PlanetRepository): ObserveCandidatesUseCase =
            ObserveCandidatesUseCase(repository)
    }
}
