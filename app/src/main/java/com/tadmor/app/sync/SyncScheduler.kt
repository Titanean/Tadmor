package com.tadmor.app.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.tadmor.data.sync.DataSyncWorker
import com.tadmor.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Observes `autoSyncEnabled` / `autoSyncWifiOnly` from settings and keeps
 * the periodic [DataSyncWorker] in sync with them.
 *
 * - `autoSyncEnabled = false` → cancel any pending work.
 * - `autoSyncEnabled = true` → enqueue (or update) the periodic request with
 *   the network constraint derived from `autoSyncWifiOnly`.
 *
 * Uses [ExistingPeriodicWorkPolicy.UPDATE] so flipping Wi-Fi-only on/off
 * applies on the next interval without needing to cancel-then-enqueue.
 * The schedule is the original 24h interval with a 6h flex window —
 * WorkManager picks an opportunistic moment within that window.
 */
@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Starts the long-lived observation. Called once from
     * `TadmorApplication.onCreate`. Emits only on actual relevant changes
     * via `distinctUntilChanged`; one-shot updates to `lastSyncedAtMillis`
     * (which we also store in the same DataStore) don't re-enqueue.
     */
    fun start() {
        settingsRepository.observeSettings()
            .map { AutoSyncConfig(it.autoSyncEnabled, it.autoSyncWifiOnly) }
            .distinctUntilChanged()
            .onEach { apply(it) }
            .launchIn(scope)
    }

    private fun apply(config: AutoSyncConfig) {
        val wm = WorkManager.getInstance(context)
        if (!config.enabled) {
            wm.cancelUniqueWork(DataSyncWorker.WORK_NAME)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (config.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
            )
            .build()
        val request = PeriodicWorkRequestBuilder<DataSyncWorker>(
            repeatInterval = 24,
            repeatIntervalTimeUnit = TimeUnit.HOURS,
            flexTimeInterval = 6,
            flexTimeIntervalUnit = TimeUnit.HOURS,
        )
            .setConstraints(constraints)
            .build()
        wm.enqueueUniquePeriodicWork(
            DataSyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /**
     * App-launch catch-up sync. WorkManager's periodic schedule can drop
     * cycles when the OS aggressively kills background work (battery
     * optimization, Doze, OEM customisations), and a first-launch user has
     * no schedule at all yet. So on every fresh app open we check: if
     * auto-sync is on and the last successful sync is older than
     * [staleAfterMillis] (or has never happened), fire a one-time
     * [DataSyncWorker] to top the cache up.
     *
     * Uses a separate unique work name with [ExistingWorkPolicy.KEEP] so
     * rapid re-launches collapse into a single in-flight top-up rather
     * than queuing multiple syncs.
     */
    suspend fun topUpIfStale(
        staleAfterMillis: Long = TimeUnit.HOURS.toMillis(22),
    ) {
        val settings = settingsRepository.observeSettings().first()
        if (!settings.autoSyncEnabled) return
        val sinceLastSync = System.currentTimeMillis() - settings.lastSyncedAtMillis
        // lastSyncedAtMillis = 0 (first launch) makes sinceLastSync huge, so the
        // threshold check naturally triggers a sync for users with empty caches.
        if (sinceLastSync < staleAfterMillis) return

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (settings.autoSyncWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
            )
            .build()
        val request = OneTimeWorkRequestBuilder<DataSyncWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            TOPUP_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private data class AutoSyncConfig(val enabled: Boolean, val wifiOnly: Boolean)

    companion object {
        /** Distinct from [DataSyncWorker.WORK_NAME] so the catch-up doesn't
         *  collide with the 24h periodic schedule. */
        const val TOPUP_WORK_NAME = "tadmor_data_sync_topup"
    }
}
