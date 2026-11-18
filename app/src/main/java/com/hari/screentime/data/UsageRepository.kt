package com.hari.screentime.data

import android.annotation.SuppressLint
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate

/**
 * Permanent store of per-app totals by day and by hour. Android only keeps raw usage events for
 * about a week, so we snapshot them here regularly. Rows are kept for as long as the user chose
 * in Settings ([Settings.retentionMonths]).
 */
class UsageRepository private constructor(private val context: Context) {

    private val calculator = UsageCalculator(context)
    private val db = Helper(context).writableDatabase

    /** First day that is still kept, or null when history is kept forever. */
    fun retentionCutoff(today: LocalDate = LocalDate.now()): LocalDate? =
        Settings.retentionMonths(context).takeIf { it != Settings.FOREVER }?.let { today.minusMonths(it.toLong()) }

    /** Earliest day the app can show: the retention cutoff, or the oldest record when keeping forever. */
    fun earliestDate(today: LocalDate = LocalDate.now()): LocalDate =
        retentionCutoff(today) ?: minOf(oldestDate() ?: today, today.minusDays(FULL_SYNC_DAYS - 1))

    /** The first day with saved usage, or null before anything is saved. */
    fun firstRecordedDate(): LocalDate? = oldestDate()

    private fun oldestDate(): LocalDate? =
        db.rawQuery("SELECT MIN(date) FROM daily_usage", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) LocalDate.parse(c.getString(0)) else null
        }

    /**
     * Deletes apps whose total for a finished day is under the minimum set in Settings. Today is
     * never touched: its totals are still growing.
     */
    /** Returns how many app-days were deleted. */
    private fun dropMinorApps(today: LocalDate = LocalDate.now()): Int {
        val minMs = Settings.minDailyMillis(context)
        if (minMs <= 0) return 0
        val day = today.toString()
        val minor = "SELECT date, pkg FROM daily_usage WHERE date < ? AND millis < ?"
        delete("DELETE FROM hourly_usage WHERE (date, pkg) IN ($minor)", day, minMs)
        delete("DELETE FROM sessions WHERE (date, pkg) IN ($minor)", day, minMs)
        return delete("DELETE FROM daily_usage WHERE date < ? AND millis < ?", day, minMs)
    }

    /** Returns how many app-days were deleted for being older than the history setting. */
    private fun prune(): Int {
        val cutoff = (retentionCutoff() ?: return 0).toString()
        delete("DELETE FROM hourly_usage WHERE date < ?", cutoff)
        delete("DELETE FROM sessions WHERE date < ?", cutoff)
        return delete("DELETE FROM daily_usage WHERE date < ?", cutoff)
    }

    private fun delete(sql: String, vararg args: Any): Int = db.compileStatement(sql).use { st ->
        args.forEachIndexed { i, a ->
            if (a is Number) st.bindLong(i + 1, a.toLong()) else st.bindString(i + 1, a.toString())
        }
        st.executeUpdateDelete()
    }

    /** Deletes history older than the current retention setting at once and shrinks the file. */
    @Synchronized
    fun applyRetention() {
        val removed: Int
        db.beginTransaction()
        try {
            removed = prune()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        db.execSQL("VACUUM")
        AppLog.i("Storage", "History setting applied: deleted $removed app-days older than ${retentionCutoff() ?: "never"}")
    }

    /** What the database currently holds, for the Settings screen. */
    fun stats(): StorageStats {
        fun count(sql: String) = db.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        val bytes = listOf("", "-wal", "-journal", "-shm")
            .sumOf { context.getDatabasePath(DB_NAME + it).takeIf { f -> f.exists() }?.length() ?: 0L }
        return StorageStats(
            oldest = oldestDate(),
            days = count("SELECT COUNT(DISTINCT date) FROM daily_usage"),
            appDays = count("SELECT COUNT(*) FROM daily_usage"),
            sessions = count("SELECT COUNT(*) FROM sessions"),
            bytes = bytes,
        )
    }

    /**
     * Recomputes the last [days] days from the event log, saves them and prunes expired history.
     * Returns the freshly computed usage so callers can show today straight from the system.
     */
    @Synchronized
    fun sync(days: Long = FULL_SYNC_DAYS): UsageResult {
        val started = System.currentTimeMillis()
        val usage = calculator.compute(LocalDate.now().minusDays(days - 1))
        val dropped: Int
        val pruned: Int
        // Finished days are re-imported on every sync. Apps under the minimum daily use are not
        // written back for them, so they are not re-added and deleted again every 30 seconds.
        val today = LocalDate.now()
        val minMs = Settings.minDailyMillis(context)
        val skip = usage.daily.filter { it.date < today && it.millis < minMs }.mapTo(HashSet()) { it.date to it.pkg }
        db.beginTransaction()
        try {
            // Keep the larger value: older days may already be partly purged from the event log,
            // and a recompute must never shrink what we recorded while the data was complete.
            for (u in usage.daily) {
                if (u.date to u.pkg in skip) continue
                db.execSQL(
                    """INSERT INTO daily_usage(date, pkg, millis, sessions) VALUES(?, ?, ?, ?)
                       ON CONFLICT(date, pkg) DO UPDATE SET
                         millis = MAX(millis, excluded.millis),
                         sessions = MAX(sessions, excluded.sessions)""",
                    arrayOf(u.date.toString(), u.pkg, u.millis, u.sessions),
                )
            }
            for (h in usage.hourly) {
                if (h.date to h.pkg in skip) continue
                db.execSQL(
                    """INSERT INTO hourly_usage(date, hour, pkg, millis) VALUES(?, ?, ?, ?)
                       ON CONFLICT(date, hour, pkg) DO UPDATE SET millis = MAX(millis, excluded.millis)""",
                    arrayOf(h.date.toString(), h.hour, h.pkg, h.millis),
                )
            }
            // A session still running at the last sync ended at that sync; later syncs extend it.
            for (s in usage.sessions) {
                if (s.date to s.pkg in skip) continue
                db.execSQL(
                    """INSERT INTO sessions(pkg, start, end_time, millis, date) VALUES(?, ?, ?, ?, ?)
                       ON CONFLICT(pkg, start) DO UPDATE SET
                         end_time = MAX(end_time, excluded.end_time),
                         millis = MAX(millis, excluded.millis)""",
                    arrayOf(s.pkg, s.start, s.end, s.millis, s.date.toString()),
                )
            }
            // Deletes the apps saved during a day that ended under the minimum (normally once,
            // on the first sync after midnight).
            dropped = dropMinorApps(today)
            pruned = prune()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        AppLog.d(
            "Sync",
            "Saved $days days: ${usage.daily.size} app-days, ${usage.sessions.size} sessions " +
                "in ${System.currentTimeMillis() - started} ms",
        )
        if (dropped > 0) AppLog.i("Sync", "Deleted $dropped app-days under the minimum daily use")
        if (pruned > 0) AppLog.i("Sync", "Deleted $pruned app-days older than the history setting")
        return usage
    }

    /** All daily rows with date in [from]..[to] inclusive. */
    fun load(from: LocalDate, to: LocalDate): List<DailyUsage> {
        val rows = mutableListOf<DailyUsage>()
        db.rawQuery(
            "SELECT date, pkg, millis, sessions FROM daily_usage WHERE date BETWEEN ? AND ?",
            arrayOf(from.toString(), to.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                rows += DailyUsage(LocalDate.parse(c.getString(0)), c.getString(1), c.getLong(2), c.getInt(3))
            }
        }
        return rows
    }

    /** Total millis per hour of day (index 0..23) summed over [from]..[to], optionally for one app. */
    fun loadHourly(from: LocalDate, to: LocalDate, pkg: String? = null): List<Long> {
        val hours = LongArray(24)
        val sql = "SELECT hour, SUM(millis) FROM hourly_usage WHERE date BETWEEN ? AND ?" +
            (if (pkg != null) " AND pkg = ?" else "") + " GROUP BY hour"
        val args = listOfNotNull(from.toString(), to.toString(), pkg).toTypedArray()
        db.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) hours[c.getInt(0)] = c.getLong(1)
        }
        return hours.toList()
    }

    /** Every session of [pkg] that started on a day in [from]..[to], newest first. */
    fun loadSessions(from: LocalDate, to: LocalDate, pkg: String): List<UsageSession> {
        val rows = mutableListOf<UsageSession>()
        db.rawQuery(
            """SELECT date, start, end_time, millis FROM sessions
               WHERE pkg = ? AND date BETWEEN ? AND ? ORDER BY start DESC""",
            arrayOf(pkg, from.toString(), to.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                rows += UsageSession(LocalDate.parse(c.getString(0)), pkg, c.getLong(1), c.getLong(2), c.getLong(3))
            }
        }
        return rows
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, 3) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE daily_usage(
                     date TEXT NOT NULL,
                     pkg TEXT NOT NULL,
                     millis INTEGER NOT NULL,
                     sessions INTEGER NOT NULL,
                     PRIMARY KEY(date, pkg))"""
            )
            createHourly(db)
            createSessions(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // v2 added hourly totals; the next sync backfills them from the last ~7 days of events.
            if (oldVersion < 2) createHourly(db)
            // v3 added individual sessions; likewise backfilled from the event log on the next sync.
            if (oldVersion < 3) createSessions(db)
        }

        private fun createSessions(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE sessions(
                     pkg TEXT NOT NULL,
                     start INTEGER NOT NULL,
                     end_time INTEGER NOT NULL,
                     millis INTEGER NOT NULL,
                     date TEXT NOT NULL,
                     PRIMARY KEY(pkg, start))"""
            )
            db.execSQL("CREATE INDEX sessions_by_date ON sessions(date)")
        }

        private fun createHourly(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE hourly_usage(
                     date TEXT NOT NULL,
                     hour INTEGER NOT NULL,
                     pkg TEXT NOT NULL,
                     millis INTEGER NOT NULL,
                     PRIMARY KEY(date, hour, pkg))"""
            )
        }
    }

    companion object {
        /** Android keeps roughly this much raw event history; a full sync re-imports all of it. */
        const val FULL_SYNC_DAYS = 7L

        /** Frequent syncs only need today and yesterday (for sessions that span midnight). */
        const val QUICK_SYNC_DAYS = 2L

        private const val DB_NAME = "usage.db"

        // Holds only the application context, which lives as long as the process: not a leak.
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: UsageRepository? = null

        fun get(context: Context): UsageRepository =
            instance ?: synchronized(this) {
                instance ?: UsageRepository(context.applicationContext).also { instance = it }
            }
    }
}

/** Size and contents of the usage database. [appDays] counts one row per app per day. */
data class StorageStats(val oldest: LocalDate?, val days: Long, val appDays: Long, val sessions: Long, val bytes: Long)
