package com.tadmor.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.tadmor.app.sync.SyncScheduler
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class TadmorApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var syncScheduler: SyncScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

        Timber.plant(Timber.DebugTree())

        // SyncScheduler observes user settings and enqueues / cancels the
        // periodic DataSyncWorker accordingly. Replaces the previous
        // unconditional `enqueueUniquePeriodicWork` so the user's auto-sync
        // and Wi-Fi-only toggles in settings actually take effect.
        syncScheduler.start()
    }
}
