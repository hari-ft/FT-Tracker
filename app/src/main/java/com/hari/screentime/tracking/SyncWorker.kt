package com.hari.screentime.tracking

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hari.screentime.data.AppLog
import com.hari.screentime.data.BackgroundHealth
import com.hari.screentime.data.Permissions
import com.hari.screentime.data.UsageRepository
import java.util.concurrent.TimeUnit

/**
 * Hourly save of Android's usage events into the app's history. Android itself records the events
 * continuously but keeps them only ~7 days, so this just has to run regularly; nothing needs to
 * stay running in between.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!Permissions.hasUsageAccess(applicationContext)) {
            AppLog.w(TAG, "Hourly save skipped: usage access is off")
            return Result.success()
        }
        val started = System.currentTimeMillis()
        return runCatching { UsageRepository.get(applicationContext).sync() }
            .fold(
                {
                    BackgroundHealth.recordSave(applicationContext)
                    AppLog.i(TAG, "Hourly save done in ${System.currentTimeMillis() - started} ms")
                    Result.success()
                },
                { error ->
                    AppLog.e(TAG, "Hourly save failed (attempt ${runAttemptCount + 1}); will retry", error)
                    Result.retry()
                },
            )
    }

    companion object {
        private const val TAG = "HourlySave"

        fun schedule(context: Context) {
            BackgroundHealth.startWatching(context)
            val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("usage-sync", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
