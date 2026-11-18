package com.hari.screentime.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import com.hari.screentime.data.AppLog

/**
 * Keeps the background saves scheduled: after a reboot or app update, and moves the midnight
 * cleanup when the time zone or clock changes. All of these except QUICKBOOT_POWERON are protected
 * broadcasts only the system can send; that one is harmless, as it only re-schedules what is kept.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED -> {
                AppLog.i("Boot", "Clock or time zone changed (${intent.action}); moving the midnight cleanup")
                MidnightCleanupWorker.schedule(context, ExistingWorkPolicy.REPLACE)
            }
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            -> {
                AppLog.i("Boot", "Rescheduling background saves after ${intent.action}")
                SyncWorker.schedule(context)
                MidnightCleanupWorker.schedule(context)
            }
        }
    }
}
