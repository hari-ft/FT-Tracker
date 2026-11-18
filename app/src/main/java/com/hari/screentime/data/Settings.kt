package com.hari.screentime.data

import android.content.Context
import androidx.core.content.edit

/** User preferences, kept in a private SharedPreferences file. */
object Settings {
    /** Retention value meaning history is never deleted. */
    const val FOREVER = 0
    const val DEFAULT_RETENTION_MONTHS = 6

    /** Choices offered in Settings, shortest first. */
    val RETENTION_OPTIONS = listOf(1, 3, 6, 12, 24, FOREVER)

    /** Minimum daily total, in minutes, for an app to be kept for a finished day; 0 keeps all. */
    const val DEFAULT_MIN_DAILY_MINUTES = 2
    val MIN_DAILY_OPTIONS = listOf(0, 1, 2, 5, 10)

    /** App log: how much it records, and for how many days files are kept. */
    val DEFAULT_LOG_LEVEL = LogLevel.INFO
    const val DEFAULT_LOG_RETENTION_DAYS = 7
    val LOG_RETENTION_OPTIONS = listOf(1, 3, 7, 14, 30)

    private const val KEY_RETENTION = "retention_months"
    private const val KEY_LOG_LEVEL = "log_level"
    private const val KEY_LOG_RETENTION = "log_retention_days"
    private const val KEY_MIN_DAILY = "min_daily_minutes"

    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** How many months of history to keep, or [FOREVER]. */
    fun retentionMonths(context: Context): Int = prefs(context).getInt(KEY_RETENTION, DEFAULT_RETENTION_MONTHS)

    fun setRetentionMonths(context: Context, months: Int) = prefs(context).edit { putInt(KEY_RETENTION, months) }

    fun minDailyMinutes(context: Context): Int = prefs(context).getInt(KEY_MIN_DAILY, DEFAULT_MIN_DAILY_MINUTES)

    /**
     * The "Skip rarely used apps" setting in milliseconds: apps whose daily total is below this are
     * hidden today and deleted at midnight. 0 when the setting is Off. Everything reads it from here.
     */
    fun minDailyMillis(context: Context): Long = minDailyMinutes(context) * 60_000L

    fun setMinDailyMinutes(context: Context, minutes: Int) = prefs(context).edit { putInt(KEY_MIN_DAILY, minutes) }

    fun minDailyLabel(minutes: Int): String = if (minutes == 0) "Off (keep every app)" else "Under $minutes min"

    fun logLevel(context: Context): LogLevel =
        prefs(context).getString(KEY_LOG_LEVEL, null)
            ?.let { name -> LogLevel.entries.firstOrNull { it.name == name } } ?: DEFAULT_LOG_LEVEL

    fun setLogLevel(context: Context, level: LogLevel) = prefs(context).edit { putString(KEY_LOG_LEVEL, level.name) }

    fun logRetentionDays(context: Context): Int = prefs(context).getInt(KEY_LOG_RETENTION, DEFAULT_LOG_RETENTION_DAYS)

    fun setLogRetentionDays(context: Context, days: Int) = prefs(context).edit { putInt(KEY_LOG_RETENTION, days) }

    fun logRetentionLabel(days: Int): String = if (days == 1) "1 day (today only)" else "$days days"

    fun retentionLabel(months: Int): String = when (months) {
        FOREVER -> "Forever"
        1 -> "1 month"
        12 -> "1 year"
        24 -> "2 years"
        else -> "$months months"
    }

    /** Orders retention choices by how much they keep, with [FOREVER] keeping the most. */
    fun retentionRank(months: Int): Int = if (months == FOREVER) Int.MAX_VALUE else months
}
