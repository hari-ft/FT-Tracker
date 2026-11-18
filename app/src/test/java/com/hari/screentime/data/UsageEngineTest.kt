package com.hari.screentime.data

import com.hari.screentime.data.UsageEngine.ACTIVITY_PAUSED
import com.hari.screentime.data.UsageEngine.ACTIVITY_RESUMED
import com.hari.screentime.data.UsageEngine.ACTIVITY_STOPPED
import com.hari.screentime.data.UsageEngine.DEVICE_SHUTDOWN
import com.hari.screentime.data.UsageEngine.KEYGUARD_HIDDEN
import com.hari.screentime.data.UsageEngine.KEYGUARD_SHOWN
import com.hari.screentime.data.UsageEngine.SCREEN_INTERACTIVE
import com.hari.screentime.data.UsageEngine.SCREEN_NON_INTERACTIVE
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

class UsageEngineTest {

    private val zone = ZoneOffset.UTC
    private val day = LocalDate.of(2026, 10, 29)
    private val insta = "com.instagram.android"
    private val fb = "com.facebook.katana"
    private val launcher = "com.android.launcher3"

    private fun t(time: String, date: LocalDate = day): Long =
        date.atTime(LocalTime.parse(time)).toInstant(zone).toEpochMilli()

    private fun ev(time: String, type: Int, pkg: String = "android", cls: String? = null, date: LocalDate = day) =
        RawEvent(t(time, date), type, pkg, cls)

    private fun resumed(time: String, pkg: String, cls: String = "$pkg.Main") = ev(time, ACTIVITY_RESUMED, pkg, cls)
    private fun paused(time: String, pkg: String, cls: String = "$pkg.Main") = ev(time, ACTIVITY_PAUSED, pkg, cls)
    private fun locked(time: String) = listOf(ev(time, SCREEN_NON_INTERACTIVE), ev(time, KEYGUARD_SHOWN))
    private fun unlocked(time: String) = listOf(ev(time, SCREEN_INTERACTIVE), ev(time, KEYGUARD_HIDDEN))

    private fun run(
        events: List<RawEvent>,
        now: String = "23:59:59",
        rangeStart: Long = t("00:00"),
        nowMillis: Long = t(now),
    ) = UsageEngine.compute(events.sortedBy { it.time }, setOf(launcher), rangeStart, nowMillis, zone)

    private fun UsageResult.minutes(pkg: String, date: LocalDate = day) =
        (daily.firstOrNull { it.pkg == pkg && it.date == date }?.millis ?: 0L) / 60_000.0

    private fun UsageResult.sessions(pkg: String) = daily.firstOrNull { it.pkg == pkg }?.sessions ?: 0

    @Test
    fun countsTimeWhileUnlockedAndOnScreen() {
        val r = run(unlocked("09:00") + resumed("09:00", insta) + paused("09:30", insta))
        assertEquals(30.0, r.minutes(insta), 0.0)
        assertEquals(1, r.sessions(insta))
    }

    @Test
    fun lockingWithAppOpenStopsTheClockUntilUnlock() {
        // Instagram stays the resumed activity throughout; only the lock state changes.
        val r = run(
            unlocked("09:00") + resumed("09:00", insta) +
                locked("09:10") + unlocked("09:50") +
                paused("10:00", insta)
        )
        assertEquals(20.0, r.minutes(insta), 0.0)
    }

    @Test
    fun keyguardShowingWithScreenOnIsNotCounted() {
        // Screen wakes (e.g. a notification) but the phone is never unlocked.
        val r = run(
            unlocked("09:00") + resumed("09:00", insta) + locked("09:05") +
                ev("09:20", SCREEN_INTERACTIVE) + ev("09:25", SCREEN_NON_INTERACTIVE)
        )
        assertEquals(5.0, r.minutes(insta), 0.0)
    }

    @Test
    fun screenOffWithoutKeyguardStopsTheClock() {
        // Phones with no lock screen only report screen on/off.
        val r = run(
            listOf(
                ev("09:00", SCREEN_INTERACTIVE), resumed("09:00", insta),
                ev("09:10", SCREEN_NON_INTERACTIVE), ev("09:40", SCREEN_INTERACTIVE),
                paused("09:45", insta),
            )
        )
        assertEquals(15.0, r.minutes(insta), 0.0)
    }

    @Test
    fun appOpenedWhileLockedIsNotCountedUntilUnlock() {
        // E.g. a call screen or alarm shown over the lock screen.
        val r = run(locked("08:00") + resumed("08:30", insta) + unlocked("08:40") + paused("08:50", insta))
        assertEquals(10.0, r.minutes(insta), 0.0)
    }

    @Test
    fun startingLockStateIsInferredFromFirstKeyguardEvent() {
        // The log window starts while the phone is already locked: the first keyguard event is HIDDEN.
        val r = run(listOf(resumed("07:00", insta), ev("07:30", KEYGUARD_HIDDEN), paused("07:40", insta)))
        assertEquals(10.0, r.minutes(insta), 0.0)
    }

    @Test
    fun lateStopOfPreviousActivityDoesNotStopTheClock() {
        val r = run(
            unlocked("09:00") +
                listOf(
                    resumed("09:00", insta, "Feed"),
                    paused("09:10", insta, "Feed"),
                    resumed("09:10", insta, "Reels"),
                    ev("09:11", ACTIVITY_STOPPED, insta, "Feed"),
                    paused("09:30", insta, "Reels"),
                )
        )
        assertEquals(30.0, r.minutes(insta), 0.0)
        assertEquals(1, r.sessions(insta))
    }

    @Test
    fun reopeningAfterAGapIsANewSession() {
        val r = run(
            unlocked("09:00") +
                resumed("09:00", insta) + paused("09:05", insta) +
                resumed("09:05", launcher) + paused("09:06", launcher) +
                resumed("09:06", insta) + paused("09:10", insta)
        )
        assertEquals(9.0, r.minutes(insta), 0.0)
        assertEquals(2, r.sessions(insta))
    }

    @Test
    fun unlockingBackIntoAnAppCountsAsAnOpen() {
        val r = run(unlocked("09:00") + resumed("09:00", insta) + locked("09:10") + unlocked("10:00") + paused("10:05", insta))
        assertEquals(2, r.sessions(insta))
    }

    @Test
    fun launcherAndIgnoredAppsAreNotCounted() {
        val r = run(unlocked("09:00") + resumed("09:00", launcher) + paused("09:30", launcher))
        assertEquals(0.0, r.minutes(launcher), 0.0)
    }

    @Test
    fun switchingAppsCreditsEachApp() {
        val r = run(
            unlocked("09:00") +
                resumed("09:00", insta) + paused("09:20", insta) +
                resumed("09:20", fb) + paused("09:45", fb)
        )
        assertEquals(20.0, r.minutes(insta), 0.0)
        assertEquals(25.0, r.minutes(fb), 0.0)
    }

    @Test
    fun usageAcrossMidnightIsSplitByDayAndHour() {
        val next = day.plusDays(1)
        val events = listOf(
            ev("23:00", SCREEN_INTERACTIVE), ev("23:00", KEYGUARD_HIDDEN),
            ev("23:40", ACTIVITY_RESUMED, insta, "$insta.Main"),
            ev("00:15", ACTIVITY_PAUSED, insta, "$insta.Main", date = next),
        )
        val r = run(events, nowMillis = t("01:00", next))
        assertEquals(20.0, r.minutes(insta, day), 0.0)
        assertEquals(15.0, r.minutes(insta, next), 0.0)
        val h23 = r.hourly.single { it.date == day && it.hour == 23 }
        val h0 = r.hourly.single { it.date == next && it.hour == 0 }
        assertEquals(20 * 60_000L, h23.millis)
        assertEquals(15 * 60_000L, h0.millis)
    }

    @Test
    fun hourlyBucketsSumToDailyTotal() {
        val r = run(unlocked("09:30") + resumed("09:30", insta) + paused("11:10", insta))
        assertEquals(listOf(9 to 30L, 10 to 60L, 11 to 10L), r.hourly.sortedBy { it.hour }.map { it.hour to it.millis / 60_000 })
        assertEquals(100.0, r.minutes(insta), 0.0)
    }

    @Test
    fun timeBeforeRangeStartIsExcluded() {
        val r = run(unlocked("08:00") + resumed("08:00", insta) + paused("10:00", insta), rangeStart = t("09:00"))
        assertEquals(60.0, r.minutes(insta), 0.0)
        assertEquals(0, r.sessions(insta)) // the open happened before the range
    }

    @Test
    fun appStillOpenIsCountedUpToNow() {
        val r = run(unlocked("09:00") + resumed("09:00", insta), now = "09:12")
        assertEquals(12.0, r.minutes(insta), 0.0)
    }

    private fun UsageResult.spans(pkg: String) =
        sessions.filter { it.pkg == pkg }.sortedBy { it.start }
            .map { Triple(it.start, it.end, it.millis / 60_000) }

    @Test
    fun sessionsRecordStartEndAndCountedTime() {
        val r = run(
            unlocked("09:00") +
                resumed("09:00", insta) + paused("09:05", insta) +
                resumed("09:05", launcher) + paused("09:06", launcher) +
                resumed("09:06", insta) + paused("09:10", insta)
        )
        assertEquals(
            listOf(Triple(t("09:00"), t("09:05"), 5L), Triple(t("09:06"), t("09:10"), 4L)),
            r.spans(insta),
        )
        // The list agrees with the per-day open count.
        assertEquals(r.sessions(insta), r.sessions.count { it.pkg == insta })
    }

    @Test
    fun activityHopsStayOneSession() {
        val r = run(
            unlocked("09:00") +
                listOf(
                    resumed("09:00", insta, "Feed"), paused("09:10", insta, "Feed"),
                    resumed("09:10:02", insta, "Reels"), paused("09:30", insta, "Reels"),
                )
        )
        val s = r.sessions.single { it.pkg == insta }
        assertEquals(t("09:00"), s.start)
        assertEquals(t("09:30"), s.end)
        assertEquals(30 * 60_000L - 2_000L, s.millis) // the 2 s hop is not counted
    }

    @Test
    fun lockingSplitsSessions() {
        val r = run(unlocked("09:00") + resumed("09:00", insta) + locked("09:10") + unlocked("10:00") + paused("10:05", insta))
        assertEquals(
            listOf(Triple(t("09:00"), t("09:10"), 10L), Triple(t("10:00"), t("10:05"), 5L)),
            r.spans(insta),
        )
    }

    @Test
    fun openSessionEndsAtNow() {
        val r = run(unlocked("09:00") + resumed("09:00", insta), now = "09:12")
        assertEquals(listOf(Triple(t("09:00"), t("09:12"), 12L)), r.spans(insta))
    }

    @Test
    fun sessionOpenedBeforeRangeIsNotListed() {
        val r = run(unlocked("08:00") + resumed("08:00", insta) + paused("10:00", insta), rangeStart = t("09:00"))
        assertEquals(emptyList<Any>(), r.spans(insta))
    }

    @Test
    fun sessionAcrossMidnightIsSplitIntoOnePartPerDay() {
        val next = day.plusDays(1)
        val events = listOf(
            ev("23:00", SCREEN_INTERACTIVE), ev("23:00", KEYGUARD_HIDDEN),
            ev("23:40", ACTIVITY_RESUMED, insta, "$insta.Main"),
            ev("00:15", ACTIVITY_PAUSED, insta, "$insta.Main", date = next),
        )
        val r = run(events, nowMillis = t("01:00", next))
        val parts = r.sessions.filter { it.pkg == insta }.sortedBy { it.start }
        assertEquals(2, parts.size)
        assertEquals(day, parts[0].date)
        assertEquals(t("23:40") to t("00:00", next), parts[0].start to parts[0].end)
        assertEquals(20 * 60_000L, parts[0].millis)
        assertEquals(next, parts[1].date)
        assertEquals(t("00:00", next) to t("00:15", next), parts[1].start to parts[1].end)
        assertEquals(15 * 60_000L, parts[1].millis)
        assertEquals(false, parts[0].isContinuation(zone))
        assertEquals(true, parts[1].isContinuation(zone))
        // Still one open, counted on the day it started.
        assertEquals(1, r.daily.single { it.pkg == insta && it.date == day }.sessions)
        assertEquals(0, r.daily.single { it.pkg == insta && it.date == next }.sessions)
    }

    @Test
    fun shutdownEndsTheSessionAndBootStartsLocked() {
        val r = run(
            unlocked("09:00") + resumed("09:00", insta) + ev("09:10", DEVICE_SHUTDOWN) +
                resumed("09:20", insta) + paused("09:30", insta)
        )
        assertEquals(10.0, r.minutes(insta), 0.0)
    }
}
