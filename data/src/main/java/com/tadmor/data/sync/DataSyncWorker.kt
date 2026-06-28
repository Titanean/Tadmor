package com.tadmor.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.tadmor.domain.repository.BookmarkRepository
import com.tadmor.domain.repository.PlanetRepository
import com.tadmor.domain.repository.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import timber.log.Timber

/**
 * WorkManager worker for periodic background sync.
 * Per SPEC.md Section 4.5: network required, 24h interval, 6h flex.
 */
@HiltWorker
class DataSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: PlanetRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val settingsRepository: SettingsRepository,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            repository.sync()
            settingsRepository.updateLastSyncedAtMillis(System.currentTimeMillis())
            // Debug-only post-sync mutation. Honours the same setting as
            // the manual-refresh path so behaviour is identical between
            // pull-to-refresh and the periodic background sync.
            if (settingsRepository.observeSettings().first().simulateBookmarkUpdates) {
                bookmarkRepository.simulateUpdates()
            }
            Timber.d("DataSyncWorker completed successfully")
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "DataSyncWorker failed")
            val errorData = Data.Builder()
                .putString(KEY_ERROR_MESSAGE, e.message ?: "Unknown error")
                .build()
            if (runAttemptCount < 3) Result.retry() else Result.failure(errorData)
        }
    }

    companion object {
        const val WORK_NAME = "tadmor_data_sync"
        const val KEY_ERROR_MESSAGE = "error_message"
    }
}
