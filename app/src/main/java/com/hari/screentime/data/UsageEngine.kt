package com.hari.screentime.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** A usage event, decoupled from android.app.usage so the engine runs in plain JVM unit tests. */
data class RawEvent(val time: Long, val type: Int, val pkg: String, val cls: String? = null)

/** Foreground time and session count for one app on one day. */
data class DailyUsage(val date: LocalDate, val pkg: String, val millis: Long, val sessions: Int)

/** Foreground time for one app within one clock hour (0..23) of one day. */
data class HourlyUsage(val date: LocalDate, val hour: Int, val pkg: String, val millis: Long)

/**
 * One continuous use of an app: from opening it until leaving it. [millis] is the time actually
 * counted inside [start]..[end]; it is shorter than the span when brief activity hops were merged.
 * A session that runs past midnight is stored as one part per day, so each day holds only its own
 * time; a part that starts exactly at midnight is the continuation ([isContinuation]). The open is
 * counted in [DailyUsage.sessions] only on the day the session started.
 */
data class UsageSession(val date: LocalDate, val pkg: String, val start: Long, val end: Long, val millis: Long)

data class UsageResult(
    val daily: List<DailyUsage>,
    val hourly: List<HourlyUsage>,
    val sessions: List<UsageSession> = emptyList(),
)

/**
 * Turns the raw event stream into per-app usage.
 *
 * Time is only counted while ALL of these hold:
 *  - an activity of the app is resumed (it is on screen),
 *  - the screen is interactive (on),
 *  - the keyguard is not showing (the phone is unlocked).
 *
 * So if Instagram is open and the phone is locked, the clock stops at KEYGUARD_SHOWN /
 * SCREEN_NON_INTERACTIVE and only restarts after the user unlocks again.
 */
object UsageEngine {

    // Values of android.app.usage.UsageEvents.Event constants.
    const val ACTIVITY_RESUMED = 1
    const val ACTIVITY_PAUSED = 2
    const val SCREEN_INTERACTIVE = 15
    const val SCREEN_NON_INTERACTIVE = 16
    const val KEYGUARD_SHOWN = 17
    const val KEYGUARD_HIDDEN = 18
    const val ACTIVITY_STOPPED = 23
    const val DEVICE_SHUTDOWN = 26
    const val DEVICE_STARTUP = 27

    val RELEVANT_TYPES = setOf(
        ACTIVITY_RESUMED, ACTIVITY_PAUSED, ACTIVITY_STOPPED, SCREEN_INTERACTIVE, SCREEN_NON_INTERACTIVE,
        KEYGUARD_SHOWN, KEYGUARD_HIDDEN, DEVICE_SHUTDOWN, DEVICE_STARTUP,
    )

    /** Activity hops inside one app briefly pause it; re-entry within this gap is the same session. */
    const val SESSION_GAP_MS = 5_000L

    /**
     * @param events time-ordered events, starting some time before [rangeStart] so the state
     *   at [rangeStart] is known.
     * @param rangeStart only time and sessions at or after this instant are reported.
     * @param now open segments are closed at this instant.
     */
    fun compute(
        events: List<RawEvent>,
        ignored: Set<String>,
        rangeStart: Long,
        now: Long,
        zone: ZoneId,
    ): UsageResult {
        val hourly = HashMap<Triple<LocalDate, Int, String>, Long>()
        val sessions = HashMap<Pair<LocalDate, String>, Int>()

        // Infer the starting lock/screen state from the first event of each kind: if the first
        // keyguard event is HIDDEN, the phone must have been locked before it.
        var keyguardShown = events.firstOrNull { it.type == KEYGUARD_SHOWN || it.type == KEYGUARD_HIDDEN }
            ?.let { it.type == KEYGUARD_HIDDEN } ?: false
        var screenOn = events.firstOrNull { it.type == SCREEN_INTERACTIVE || it.type == SCREEN_NON_INTERACTIVE }
            ?.let { it.type == SCREEN_NON_INTERACTIVE } ?: true
        var foregroundPkg: String? = null
        var foregroundActivity: String? = null
        var segmentStart = events.firstOrNull()?.time ?: rangeStart
        var lastActivePkg: String? = null
        var lastActiveEnd = 0L

        // The session being built: its end is null while the app is still active.
        class OpenSession(val pkg: String, val start: Long) {
            var end: Long? = null
            val millisByDay = HashMap<LocalDate, Long>()
        }
        val sessionList = ArrayList<UsageSession>()
        var current: OpenSession? = null

        fun dayStart(day: LocalDate) = day.atStartOfDay(zone).toInstant().toEpochMilli()

        // Credits counted time to the session, split by day so midnight-crossing sessions divide cleanly.
        fun credit(s: OpenSession, from: Long, to: Long) {
            var a = from
            while (a < to) {
                val day = Instant.ofEpochMilli(a).atZone(zone).toLocalDate()
                val b = minOf(to, dayStart(day.plusDays(1)))
                s.millisByDay.merge(day, b - a, Long::plus)
                a = b
            }
        }

        // Emits the session, one part per day it covers.
        fun finish(s: OpenSession?) {
            if (s == null) return
            val end = s.end ?: now
            var day = Instant.ofEpochMilli(s.start).atZone(zone).toLocalDate()
            var partStart = s.start
            while (true) {
                val partEnd = minOf(end, dayStart(day.plusDays(1)))
                sessionList += UsageSession(day, s.pkg, partStart, partEnd, s.millisByDay[day] ?: 0L)
                if (partEnd >= end) break
                partStart = partEnd
                day = day.plusDays(1)
            }
        }

        fun activePkg(): String? =
            if (screenOn && !keyguardShown) foregroundPkg?.takeIf { it !in ignored } else null

        fun addTime(pkg: String, start: Long, end: Long) {
            var s = maxOf(start, rangeStart)
            while (s < end) {
                val at = Instant.ofEpochMilli(s).atZone(zone)
                val hourEnd = at.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()
                val e = minOf(end, hourEnd)
                hourly.merge(Triple(at.toLocalDate(), at.hour, pkg), e - s, Long::plus)
                s = e
            }
        }

        // Every state change closes the running segment, credits it, and opens a new one.
        fun transition(t: Long, change: () -> Unit) {
            val before = activePkg()
            if (before != null && t > segmentStart) {
                addTime(before, segmentStart, t)
                current?.takeIf { it.pkg == before }?.let { credit(it, maxOf(segmentStart, it.start), t) }
            }
            change()
            val after = activePkg()
            if (before != null && before != after) {
                lastActivePkg = before
                lastActiveEnd = t
                current?.takeIf { it.pkg == before }?.end = t
            }
            if (after != null && after != before) {
                val isContinuation = after == lastActivePkg && t - lastActiveEnd < SESSION_GAP_MS
                if (isContinuation) {
                    current?.takeIf { it.pkg == after }?.end = null
                } else {
                    finish(current)
                    // Sessions opened before the range are not reported (their day is outside it).
                    current = if (t >= rangeStart) OpenSession(after, t) else null
                    if (t >= rangeStart) {
                        val day = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
                        sessions.merge(day to after, 1, Int::plus)
                    }
                }
            }
            segmentStart = t
        }

        for (e in events) {
            val t = e.time
            when (e.type) {
                ACTIVITY_RESUMED ->
                    transition(t) { foregroundPkg = e.pkg; foregroundActivity = e.cls }
                // Match on the activity, not just the package: an app's previous screen can report
                // PAUSED/STOPPED after its next screen has already resumed.
                ACTIVITY_PAUSED, ACTIVITY_STOPPED ->
                    if (e.pkg == foregroundPkg && e.cls == foregroundActivity) {
                        transition(t) { foregroundPkg = null; foregroundActivity = null }
                    }
                SCREEN_INTERACTIVE -> transition(t) { screenOn = true }
                SCREEN_NON_INTERACTIVE -> transition(t) { screenOn = false }
                KEYGUARD_SHOWN -> transition(t) { keyguardShown = true }
                KEYGUARD_HIDDEN -> transition(t) { keyguardShown = false }
                DEVICE_SHUTDOWN, DEVICE_STARTUP ->
                    transition(t) { foregroundPkg = null; foregroundActivity = null; keyguardShown = true }
            }
        }
        // Credit whatever is still running up to now.
        transition(now) {}
        finish(current)

        val dailyMillis = HashMap<Pair<LocalDate, String>, Long>()
        hourly.forEach { (k, ms) -> dailyMillis.merge(k.first to k.third, ms, Long::plus) }
        val daily = (dailyMillis.keys + sessions.keys).map { key ->
            DailyUsage(key.first, key.second, dailyMillis[key] ?: 0L, sessions[key] ?: 0)
        }
        return UsageResult(
            daily,
            hourly.map { (k, ms) -> HourlyUsage(k.first, k.second, k.third, ms) },
            sessionList,
        )
    }
}

/** True for the part of a session that carries on from the previous day (it starts at midnight). */
fun UsageSession.isContinuation(zone: java.time.ZoneId): Boolean =
    start == date.atStartOfDay(zone).toInstant().toEpochMilli()
