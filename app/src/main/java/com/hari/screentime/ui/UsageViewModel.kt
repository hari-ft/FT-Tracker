package com.hari.screentime.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hari.screentime.data.AppLog
import com.hari.screentime.data.DailyUsage
import com.hari.screentime.data.HourlyUsage
import com.hari.screentime.data.LogLevel
import com.hari.screentime.data.Settings
import com.hari.screentime.data.StorageStats
import com.hari.screentime.data.UsageRepository
import com.hari.screentime.data.UsageResult
import com.hari.screentime.data.UsageSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth

enum class ReportRange(val label: String) {
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    WEEK("7 days"),
    MONTH("30 days"),
    THIS_MONTH("This month"),
    CUSTOM("Custom");

    /** Dates for the fixed ranges; [CUSTOM] keeps whatever the user picked. */
    fun dates(today: LocalDate = LocalDate.now()): Pair<LocalDate, LocalDate>? = when (this) {
        TODAY -> today to today
        YESTERDAY -> today.minusDays(1) to today.minusDays(1)
        WEEK -> today.minusDays(6) to today
        MONTH -> today.minusDays(29) to today
        THIS_MONTH -> today.withDayOfMonth(1) to today
        CUSTOM -> null
    }
}

/** One line of the report table: an app's totals over the selected range. */
data class AppTotal(val app: AppInfo, val millis: Long, val sessions: Int, val share: Float, val percent: Int = 0)

data class DayTotal(val date: LocalDate, val millis: Long, val sessions: Int)

data class ReportState(
    val range: ReportRange = ReportRange.TODAY,
    val from: LocalDate = LocalDate.now(),
    val to: LocalDate = LocalDate.now(),
    val apps: List<AppTotal> = emptyList(),
    /** One entry per date in the range, including days with no usage. */
    val daily: List<DayTotal> = emptyList(),
    /** Millis per hour of day (0..23), summed over the range. */
    val hourly: List<Long> = List(24) { 0L },
    /** Set when this report covers a single app (the detail screen). */
    val app: AppInfo? = null,
    /** Detail screen only: each session in the range, newest first. */
    val sessions: List<UsageSession> = emptyList(),
    /** Detail screen only: the month shown in the calendar and the app's totals for its days. */
    val calendarMonth: YearMonth = YearMonth.from(to),
    val calendar: Map<LocalDate, DayTotal> = emptyMap(),
    /** Oldest day that can be picked, from the history setting. */
    val earliest: LocalDate = LocalDate.now().minusMonths(Settings.DEFAULT_RETENTION_MONTHS.toLong()),
    /** First day with any saved usage; days before it don't count towards averages. */
    val firstDataDay: LocalDate? = null,
    /** Apps used today but hidden for being under the minimum daily use. */
    val hiddenApps: Int = 0,
    val minDailyMinutes: Int = 0,
    val loading: Boolean = true,
) {
    val totalMillis: Long get() = apps.sumOf { it.millis }
    val totalSessions: Int get() = apps.sumOf { it.sessions }

    /** Days in the range that the app has data for, used for daily averages. */
    val dayCount: Int
        get() = daily.count { d -> firstDataDay == null || !d.date.isBefore(firstDataDay) }.coerceAtLeast(1)
}

data class SettingsState(
    val retentionMonths: Int,
    val minDailyMinutes: Int,
    val logLevel: LogLevel,
    val logRetentionDays: Int,
    val stats: StorageStats? = null,
    /** Number of days with a log file, and their total size. */
    val logDays: Int = 0,
    val logBytes: Long = 0,
)

/** The log viewer: which day is shown and its lines (oldest first). */
data class LogViewState(
    val days: List<LocalDate> = emptyList(),
    val day: LocalDate? = null,
    val lines: List<String> = emptyList(),
    val loading: Boolean = true,
)

class UsageViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = UsageRepository.get(app)
    private val appInfo = AppInfoCache(app)
    private val reloadLock = Mutex()

    private var range = ReportRange.TODAY
    private var from = LocalDate.now()
    private var to = LocalDate.now()
    private var selectedPkg: String? = null
    private var calendarMonth = YearMonth.now()
    private var fullSyncDone = false

    /** Usage computed from the system at the last refresh; today's numbers are taken from here. */
    @Volatile private var live: UsageResult? = null

    private val _today = MutableStateFlow(ReportState())
    val today: StateFlow<ReportState> = _today

    private val _report = MutableStateFlow(ReportState())
    val report: StateFlow<ReportState> = _report

    /** Non-null while the per-app detail screen is open. */
    private val _detail = MutableStateFlow<ReportState?>(null)
    val detail: StateFlow<ReportState?> = _detail

    private val _settings = MutableStateFlow(currentSettings())
    val settings: StateFlow<SettingsState> = _settings

    /** Non-null while the log viewer is open. */
    private val _log = MutableStateFlow<LogViewState?>(null)
    val log: StateFlow<LogViewState?> = _log

    private fun currentSettings(stats: StorageStats? = null): SettingsState {
        val app = getApplication<Application>()
        val days = AppLog.logDays()
        return SettingsState(
            Settings.retentionMonths(app), Settings.minDailyMinutes(app),
            Settings.logLevel(app), Settings.logRetentionDays(app),
            stats, days.size, AppLog.totalBytes(),
        )
    }

    /**
     * Runs [block], logging any failure instead of letting it close the app. Cancellation is
     * passed on: it is how coroutines stop, not an error.
     */
    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e("App", "$what failed", e)
        }
    }

    /** Pulls fresh events from Android, then reloads every screen. Cheap enough to call every few seconds. */
    suspend fun refresh() = guarded("Refresh") {
        val days = if (fullSyncDone) UsageRepository.QUICK_SYNC_DAYS else UsageRepository.FULL_SYNC_DAYS
        live = withContext(Dispatchers.IO) { repo.sync(days) }
        fullSyncDone = true
        reload()
        loadSettings()
    }

    private suspend fun loadSettings() {
        _settings.value = withContext(Dispatchers.IO) { currentSettings(repo.stats()) }
    }

    fun setLogLevel(level: LogLevel) {
        val app = getApplication<Application>()
        AppLog.i("Settings", "Log level: ${Settings.logLevel(app).label} -> ${level.label}")
        Settings.setLogLevel(app, level)
        AppLog.setLevel(level)
        _settings.value = _settings.value.copy(logLevel = level)
    }

    fun setLogRetention(days: Int) {
        val app = getApplication<Application>()
        AppLog.i("Settings", "Keep logs: ${Settings.logRetentionDays(app)} -> $days days")
        Settings.setLogRetentionDays(app, days)
        _settings.value = _settings.value.copy(logRetentionDays = days)
        AppLog.pruneAsync(days)
        viewModelScope.launch { guarded("Load settings") { loadSettings() } }
    }

    /** Opens the log viewer on [day], or the newest day with a log. */
    fun openLog(day: LocalDate? = null) {
        _log.value = (_log.value ?: LogViewState()).copy(loading = true)
        viewModelScope.launch {
            guarded("Read log") {
                val state = withContext(Dispatchers.IO) {
                    val days = AppLog.logDays()
                    val shown = day?.takeIf { it in days } ?: days.firstOrNull()
                    LogViewState(days, shown, shown?.let { AppLog.read(it) }.orEmpty(), loading = false)
                }
                if (_log.value != null) _log.value = state
            }
        }
    }

    fun closeLog() {
        _log.value = null
    }

    fun clearLogs() {
        AppLog.clear()
        _log.value = _log.value?.let { LogViewState(loading = false) }
        _settings.value = _settings.value.copy(logDays = 0, logBytes = 0)
    }

    /**
     * Saves the minimum daily use. A full sync then applies it to every finished day; lowering it
     * brings back the last ~7 days from Android's log, but older dropped apps are gone.
     */
    fun setMinDaily(minutes: Int) {
        val app = getApplication<Application>()
        AppLog.i("Settings", "Skip rarely used apps: ${Settings.minDailyMinutes(app)} -> $minutes min")
        _settings.value = _settings.value.copy(minDailyMinutes = minutes)
        viewModelScope.launch {
            guarded("Apply minimum daily use") {
                withContext(Dispatchers.IO) {
                    Settings.setMinDailyMinutes(app, minutes)
                    live = repo.sync(UsageRepository.FULL_SYNC_DAYS)
                }
                loadSettings()
                reload()
            }
        }
    }

    /** Saves the history setting; a shorter one deletes the older history straight away. */
    fun setRetention(months: Int) {
        val app = getApplication<Application>()
        AppLog.i("Settings", "Keep history: ${Settings.retentionMonths(app)} -> $months months (0 = forever)")
        _settings.value = _settings.value.copy(retentionMonths = months)
        viewModelScope.launch {
            guarded("Apply history setting") {
                withContext(Dispatchers.IO) {
                    Settings.setRetentionMonths(app, months)
                    repo.applyRetention()
                }
                loadSettings()
                reload()
            }
        }
    }

    fun setRange(range: ReportRange) {
        this.range = range
        range.dates()?.let { calendarMonth = YearMonth.from(it.second) }
        viewModelScope.launch { guarded("Load report") { reload() } }
    }

    fun setCustomRange(from: LocalDate, to: LocalDate) {
        range = ReportRange.CUSTOM
        this.from = from
        this.to = to
        calendarMonth = YearMonth.from(to)
        viewModelScope.launch { guarded("Load report") { reload() } }
    }

    /** Moves the detail screen's calendar by [months], within the history we keep. */
    fun shiftCalendar(months: Long) {
        viewModelScope.launch {
            guarded("Change calendar month") {
                val earliest = YearMonth.from(withContext(Dispatchers.IO) { repo.earliestDate() })
                calendarMonth = calendarMonth.plusMonths(months).coerceIn(earliest, YearMonth.now())
                reload()
            }
        }
    }

    fun openApp(pkg: String) {
        selectedPkg = pkg
        calendarMonth = YearMonth.from(range.dates()?.second ?: to)
        _detail.value = ReportState(range, from, to, app = appInfo[pkg], calendarMonth = calendarMonth)
        viewModelScope.launch { guarded("Load app details") { reload() } }
    }

    fun closeApp() {
        selectedPkg = null
        _detail.value = null
    }

    private suspend fun reload() = reloadLock.withLock {
        range.dates()?.let { (f, t) -> from = f; to = t }
        val r = range
        val f = from
        val t = to
        val pkg = selectedPkg
        val month = calendarMonth
        val now = LocalDate.now()
        val (todayState, reportState, detailState) = withContext(Dispatchers.IO) {
            Triple(
                build(ReportRange.TODAY, now, now, null),
                build(r, f, t, null),
                pkg?.let { build(r, f, t, it, month) },
            )
        }
        _today.value = todayState
        _report.value = reportState
        // The user may have closed the detail screen while we were loading.
        if (pkg != null && pkg == selectedPkg) _detail.value = detailState
    }

    /** Today's usage from the system, without apps still under the minimum daily use. */
    private class Today(
        val daily: List<DailyUsage>,
        val hourly: List<HourlyUsage>,
        val sessions: List<UsageSession>,
        val hidden: Int,
    )

    private fun liveToday(today: LocalDate): Today {
        val result = live ?: return Today(emptyList(), emptyList(), emptyList(), 0)
        val minMs = Settings.minDailyMillis(getApplication())
        // Hidden, not deleted: if the app passes the minimum later today it shows up again.
        val all = result.daily.filter { it.date == today }
        val daily = all.filter { it.millis >= minMs }
        val shown = daily.mapTo(HashSet()) { it.pkg }
        return Today(
            daily,
            result.hourly.filter { it.date == today && it.pkg in shown },
            result.sessions.filter { it.date == today && it.pkg in shown },
            hidden = all.count { it.millis in 1_000 until minMs },
        )
    }

    /** The part of [from]..[to] before today, which is read from the database; null if none. */
    private fun pastPart(from: LocalDate, to: LocalDate, today: LocalDate): Pair<LocalDate, LocalDate>? {
        val end = minOf(to, today.minusDays(1))
        return if (from.isAfter(end)) null else from to end
    }

    /**
     * Builds a report: days before today come from the database, today comes live from the
     * system. Apps under the minimum daily use are left out (past days no longer hold them).
     */
    private fun build(
        range: ReportRange,
        from: LocalDate,
        to: LocalDate,
        pkg: String?,
        month: YearMonth = YearMonth.from(to),
    ): ReportState {
        val today = LocalDate.now()
        val past = pastPart(from, to, today)
        val now = if (today in from..to) liveToday(today) else null
        fun DailyUsage.matches() = pkg == null || this.pkg == pkg

        val rows = (past?.let { (f, t) -> repo.load(f, t) }.orEmpty() + now?.daily.orEmpty()).filter { it.matches() }
        val hourly = LongArray(24)
        past?.let { (f, t) -> repo.loadHourly(f, t, pkg).forEachIndexed { h, ms -> hourly[h] += ms } }
        now?.hourly?.filter { pkg == null || it.pkg == pkg }?.forEach { hourly[it.hour] += it.millis }
        val sessions = pkg?.let { p ->
            now?.sessions.orEmpty().filter { it.pkg == p }.sortedByDescending { it.start } +
                past?.let { (f, t) -> repo.loadSessions(f, t, p) }.orEmpty()
        }.orEmpty()
        val calendar = pkg?.let { p ->
            val monthPast = pastPart(month.atDay(1), month.atEndOfMonth(), today)
            val monthToday = if (YearMonth.from(today) == month) (now ?: liveToday(today)).daily else emptyList()
            (monthPast?.let { (f, t) -> repo.load(f, t) }.orEmpty() + monthToday)
                .filter { it.pkg == p }
                .associate { it.date to DayTotal(it.date, it.millis, it.sessions) }
        }.orEmpty()

        val total = rows.sumOf { it.millis }.coerceAtLeast(1)
        val sorted = rows.groupBy { it.pkg }
            .map { (p, list) ->
                val ms = list.sumOf { it.millis }
                AppTotal(appInfo[p], ms, list.sumOf { it.sessions }, ms.toFloat() / total)
            }
            .filter { it.millis >= 1_000 }
            .sortedByDescending { it.millis }
        val percents = roundedPercents(sorted.map { it.millis })
        val apps = sorted.mapIndexed { i, a -> a.copy(percent = percents[i]) }
        val perDay = rows.groupBy { it.date }
        val daily = generateSequence(from) { it.plusDays(1) }
            .takeWhile { !it.isAfter(to) }
            .map { d ->
                val list = perDay[d].orEmpty()
                DayTotal(d, list.sumOf { it.millis }, list.sumOf { it.sessions })
            }
            .toList()
        return ReportState(
            range, from, to, apps, daily,
            hourly = hourly.toList(),
            app = pkg?.let { appInfo[it] },
            sessions = sessions,
            calendarMonth = month,
            calendar = calendar,
            earliest = repo.earliestDate(),
            firstDataDay = repo.firstRecordedDate(),
            hiddenApps = now?.hidden ?: 0,
            minDailyMinutes = Settings.minDailyMinutes(getApplication()),
            loading = false,
        )
    }
}
