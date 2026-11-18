package com.hari.screentime

import android.app.Application
import com.hari.screentime.data.AppLog
import com.hari.screentime.tracking.MidnightCleanupWorker
import com.hari.screentime.tracking.SyncWorker

class ScreenTimeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        AppLog.i("App", "Started: ${AppLog.deviceSummary(version)}")
        SyncWorker.schedule(this)
        MidnightCleanupWorker.schedule(this)
    }
}
