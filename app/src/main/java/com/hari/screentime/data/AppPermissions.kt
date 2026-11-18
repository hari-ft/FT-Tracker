package com.hari.screentime.data

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings

/** Everything the app needs from the system to record usage and keep saving it in the background. */
enum class PermissionId {
    USAGE_ACCESS,
    UNRESTRICTED_BATTERY,
    BACKGROUND_ACTIVITY,
    KEEP_WHEN_UNUSED,
    AUTO_START,
    START_AT_BOOT,
    SEE_INSTALLED_APPS,
}

enum class PermissionState {
    ALLOWED,
    NOT_ALLOWED,

    /** The system offers no way to read it (vivo's auto-start switch). */
    CANNOT_CHECK,
}

data class PermissionStatus(
    val id: PermissionId,
    val title: String,
    val description: String,
    val state: PermissionState,
    /** Whether there is a settings screen the user can open to change it. */
    val canOpenSettings: Boolean,
    /** Replaces the default "Allowed" / "Not allowed" wording when set. */
    val stateLabel: String? = null,
)

object AppPermissions {

    /** Current state of every permission, in the order they are listed in Settings. */
    fun check(context: Context): List<PermissionStatus> {
        val pkg = context.packageName
        val list = mutableListOf(
            PermissionStatus(
                PermissionId.USAGE_ACCESS,
                "Usage access",
                "Reads which app is on screen and when the phone is locked",
                state(Permissions.hasUsageAccess(context)),
                canOpenSettings = true,
            ),
            PermissionStatus(
                PermissionId.UNRESTRICTED_BATTERY,
                "Unrestricted battery use",
                "Stops battery saving from delaying the hourly and midnight saves",
                state(context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(pkg)),
                canOpenSettings = true,
            ),
            PermissionStatus(
                PermissionId.BACKGROUND_ACTIVITY,
                "Background activity",
                "Lets the saves run while the app is closed",
                state(!context.getSystemService(ActivityManager::class.java).isBackgroundRestricted),
                canOpenSettings = true,
            ),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            list += PermissionStatus(
                PermissionId.KEEP_WHEN_UNUSED,
                "Keep running if unused",
                "Stops Android pausing the app and removing its access when it isn't opened for a while",
                state(context.packageManager.isAutoRevokeWhitelisted),
                canOpenSettings = true,
            )
        }
        list += autoStartStatus(context)
        list += PermissionStatus(
            PermissionId.START_AT_BOOT,
            "Start after restart",
            "Schedules the saves again after the phone restarts. Granted when the app is installed",
            state(granted(context, Manifest.permission.RECEIVE_BOOT_COMPLETED)),
            canOpenSettings = false,
        )
        // The permission only exists from Android 11; older versions show every app without it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            list += PermissionStatus(
                PermissionId.SEE_INSTALLED_APPS,
                "See installed apps",
                "Shows app names and icons in reports. Granted when the app is installed",
                state(granted(context, Manifest.permission.QUERY_ALL_PACKAGES)),
                canOpenSettings = false,
            )
        }
        return list
    }

    /**
     * Screens that change [id], best first; the caller opens the first one that exists. The app's
     * own info page is always last, because every phone has it.
     */
    @SuppressLint("BatteryLife")
    fun settingsIntents(context: Context, id: PermissionId): List<Intent> {
        val pkgUri = Uri.parse("package:${context.packageName}")
        val appInfo = Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
        val first = when (id) {
            PermissionId.USAGE_ACCESS -> listOf(Intent(AndroidSettings.ACTION_USAGE_ACCESS_SETTINGS))
            PermissionId.UNRESTRICTED_BATTERY -> listOf(
                Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri),
                Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            )
            PermissionId.KEEP_WHEN_UNUSED ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    listOf(Intent(Intent.ACTION_AUTO_REVOKE_PERMISSIONS, pkgUri))
                } else {
                    emptyList()
                }
            // Background restriction and vivo's auto-start / background switches live on the app's page.
            PermissionId.BACKGROUND_ACTIVITY, PermissionId.AUTO_START -> emptyList()
            PermissionId.START_AT_BOOT, PermissionId.SEE_INSTALLED_APPS -> emptyList()
        }
        return first + appInfo
    }

    /**
     * vivo's auto-start switch can't be read, so this judges it by its effect: whether the
     * background saves have actually been running.
     */
    private fun autoStartStatus(context: Context, now: Long = System.currentTimeMillis()): PermissionStatus {
        // Without usage access the saves skip on purpose, which says nothing about vivo.
        if (!Permissions.hasUsageAccess(context)) {
            return PermissionStatus(
                PermissionId.AUTO_START,
                "Auto-start / background saving (vivo)",
                "Usage access is off, so background saves are skipped. Allow usage access above; " +
                    "this check resumes after the next save.",
                PermissionState.CANNOT_CHECK,
                canOpenSettings = false,
                stateLabel = "Waiting for usage access",
            )
        }
        val lastSave = BackgroundHealth.lastSave(context)
        val lastMidnight = BackgroundHealth.lastMidnight(context)
        val watched = now - BackgroundHealth.watchingSince(context)
        val details = buildString {
            append("Last background save: ").append(lastSave?.let { ago(now - it) } ?: "not yet")
            append(" · Last midnight cleanup: ").append(lastMidnight?.let { ago(now - it) } ?: "not yet")
        }
        val hourlyLate = (lastSave?.let { now - it } ?: watched) > BackgroundHealth.HOURLY_GRACE_MS
        val midnightLate = (lastMidnight?.let { now - it } ?: watched) > BackgroundHealth.MIDNIGHT_GRACE_MS
        val (state, label, why) = when {
            hourlyLate || midnightLate -> Triple(
                PermissionState.NOT_ALLOWED,
                "Not working",
                "Background saves have stopped, so vivo is probably blocking the app. Open the app's settings " +
                    "and turn on Auto-start / Allow background activity.",
            )
            lastSave == null -> Triple(
                PermissionState.CANNOT_CHECK,
                "Waiting for the first background save",
                "vivo's auto-start switch can't be read directly, so this is judged by whether the hourly " +
                    "save runs while the app is closed.",
            )
            else -> Triple(
                PermissionState.ALLOWED,
                "Working",
                "Background saves are running, so nothing is blocking the app.",
            )
        }
        return PermissionStatus(
            PermissionId.AUTO_START,
            "Auto-start / background saving (vivo)",
            "$why\n$details",
            state,
            canOpenSettings = true,
            stateLabel = label,
        )
    }

    private fun ago(ms: Long): String {
        val min = ms / 60_000
        return when {
            min < 1 -> "just now"
            min < 60 -> "$min min ago"
            min < 48 * 60 -> "${min / 60} h ${min % 60} min ago"
            else -> "${min / (24 * 60)} days ago"
        }
    }

    private fun state(allowed: Boolean) = if (allowed) PermissionState.ALLOWED else PermissionState.NOT_ALLOWED

    private fun granted(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
