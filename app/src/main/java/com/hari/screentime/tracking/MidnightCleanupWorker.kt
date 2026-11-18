package com.hari.screentime.tracking

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hari.screentime.data.AppLog
import com.hari.screentime.data.BackgroundHealth
import com.hari.screentime.data.Settings
import com.hari.screentime.data.Permissions
import com.hari.screentime.data.UsageRepository
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Runs at 12:00 AM every day: saves the day that just ended in full, then drops apps that were
 * used less than the minimum set in Settings (the save applies that rule to finished days).
 * Android may hold it back a few minutes while the phone is idle.
 */
class MidnightCleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        AppLog.i(TAG, "Midnight cleanup started")
        if (Permissions.hasUsageAccess(applicationContext)) {
            runCatching { UsageRepository.get(applicationContext).sync() }
                .onSuccess {
                    BackgroundHealth.recordSave(applicationContext, midnight = true)
                    AppLog.i(TAG, "Midnight cleanup done")
                }
                .onFailure { AppLog.e(TAG, "Midnight cleanup failed", it) }
        } else {
            AppLog.w(TAG, "Midnight cleanup skipped: usage access is off")
        }
        AppLog.prune(Settings.logRetentionDays(applicationContext))
        // Queue tomorrow's run behind this one; the delay is recomputed so it stays on midnight.
        schedule(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }

    companion object {
        private const val NAME = "midnight-cleanup"
        private const val TAG = "Midnight"

        /** Schedules the next midnight run; [policy] KEEP leaves an already queued run alone. */
        fun schedule(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
            val now = ZonedDateTime.now()
            val midnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
            val request = OneTimeWorkRequestBuilder<MidnightCleanupWorker>()
                .setInitialDelay(Duration.between(now, midnight).toMillis(), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, policy, request)
            AppLog.d(TAG, "Next midnight cleanup scheduled for $midnight ($policy)")
        }
    }
}
