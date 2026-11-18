package com.hari.screentime.data

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.time.LocalDate
import java.time.ZoneId

/** Reads Android's usage event log and feeds it to [UsageEngine]. */
class UsageCalculator(private val context: Context) {

    private val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    /** Computes usage for every day from [from] to today (inclusive), up to [nowMillis]. */
    fun compute(from: LocalDate, nowMillis: Long = System.currentTimeMillis()): UsageResult {
        val zone = ZoneId.systemDefault()
        val rangeStart = from.atStartOfDay(zone).toInstant().toEpochMilli()
        // Look back a few hours so we know the lock/foreground state at the start of the range.
        val raw = usm.queryEvents(rangeStart - LOOKBACK_MS, nowMillis)
        val events = ArrayList<RawEvent>()
        val e = UsageEvents.Event()
        while (raw.hasNextEvent()) {
            raw.getNextEvent(e)
            if (e.eventType in UsageEngine.RELEVANT_TYPES) {
                events += RawEvent(e.timeStamp, e.eventType, e.packageName, e.className)
            }
        }
        return UsageEngine.compute(events, ignoredPackages(), rangeStart, nowMillis, zone)
    }

    /** Home screens, System UI and this app itself are not "usage" of an app. */
    private fun ignoredPackages(): Set<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val launchers = context.packageManager
            .queryIntentActivities(home, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }
        return launchers.toSet() + setOf(context.packageName, "com.android.systemui", "android")
    }

    companion object {
        private const val LOOKBACK_MS = 6 * 60 * 60 * 1000L
    }
}
