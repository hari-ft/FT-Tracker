package com.hari.screentime.data

import android.content.Context
import androidx.core.content.edit

/**
 * Records when the background saves actually ran. Android can't report vivo's auto-start switch,
 * but if it (or anything else) blocks the app, these times stop moving, which we can detect.
 * Only saves made by the background workers count; saves while the app is open prove nothing.
 */
object BackgroundHealth {

    /** The hourly save may be deferred a little; only flag it once it is clearly overdue. */
    const val HOURLY_GRACE_MS = 3 * 60 * 60 * 1000L

    /** The midnight cleanup should have run within the last day, plus some slack. */
    const val MIDNIGHT_GRACE_MS = 26 * 60 * 60 * 1000L

    private const val KEY_SINCE = "watching_since"
    private const val KEY_LAST_SAVE = "last_background_save"
    private const val KEY_LAST_MIDNIGHT = "last_midnight_cleanup"

    private fun prefs(context: Context) = context.getSharedPreferences("background_health", Context.MODE_PRIVATE)

    /** Starts the clock the first time the saves are scheduled, so a new install isn't flagged at once. */
    fun startWatching(context: Context) {
        val p = prefs(context)
        if (!p.contains(KEY_SINCE)) p.edit { putLong(KEY_SINCE, System.currentTimeMillis()) }
    }

    /** Called by a background worker after a successful save. */
    fun recordSave(context: Context, midnight: Boolean = false) {
        val now = System.currentTimeMillis()
        prefs(context).edit {
            putLong(KEY_LAST_SAVE, now)
            if (midnight) putLong(KEY_LAST_MIDNIGHT, now)
        }
    }

    fun watchingSince(context: Context): Long = prefs(context).getLong(KEY_SINCE, System.currentTimeMillis())

    fun lastSave(context: Context): Long? = prefs(context).getLong(KEY_LAST_SAVE, 0L).takeIf { it > 0 }

    fun lastMidnight(context: Context): Long? = prefs(context).getLong(KEY_LAST_MIDNIGHT, 0L).takeIf { it > 0 }
}
