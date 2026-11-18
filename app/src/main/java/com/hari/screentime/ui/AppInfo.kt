package com.hari.screentime.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

data class AppInfo(val pkg: String, val label: String, val icon: ImageBitmap?)

/** Caches app names and icons; falls back to the package name for uninstalled apps. */
class AppInfoCache(context: Context) {
    private val pm = context.packageManager
    private val cache = HashMap<String, AppInfo>()

    @Synchronized
    operator fun get(pkg: String): AppInfo = cache.getOrPut(pkg) {
        try {
            val ai = pm.getApplicationInfo(pkg, 0)
            AppInfo(
                pkg,
                pm.getApplicationLabel(ai).toString(),
                pm.getApplicationIcon(ai).toBitmap(96, 96).asImageBitmap(),
            )
        } catch (_: PackageManager.NameNotFoundException) {
            AppInfo(pkg, pkg.substringAfterLast('.'), null)
        }
    }
}
