package com.hari.screentime.data

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

/** How much the app log records. Each level includes the ones above it. */
enum class LogLevel(val label: String, val tag: String) {
    OFF("Off", ""),
    ERROR("Errors only", "ERROR"),
    WARN("Warnings and errors", "WARN"),
    INFO("Info (normal)", "INFO"),
    DEBUG("Debug (everything)", "DEBUG"),
}

/**
 * The application log: one text file per day in the app's private storage (files/logs), also
 * mirrored to Logcat. Files older than the retention set in Settings are deleted. Nothing leaves
 * the phone unless the user shares a file.
 */
object AppLog {

    private const val DIR = "logs"

    /** Stops a runaway loop from filling the phone; only errors are written past this size. */
    private const val MAX_FILE_BYTES = 5L * 1024 * 1024

    private val Indian = Locale("en", "IN")
    private val LineTime = DateTimeFormatter.ofPattern("dd-MM-yyyy hh:mm:ss.SSS a", Indian)

    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "app-log").apply { isDaemon = true } }

    @Volatile private var dir: File? = null
    @Volatile private var level = LogLevel.INFO

    /** Called once from Application.onCreate. */
    fun init(context: Context) {
        val app = context.applicationContext
        dir = File(app.filesDir, DIR).apply { mkdirs() }
        level = Settings.logLevel(app)
        installCrashHandler()
        writer.execute { prune(Settings.logRetentionDays(app)) }
    }

    fun setLevel(newLevel: LogLevel) {
        level = newLevel
    }

    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = log(LogLevel.WARN, tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = log(LogLevel.ERROR, tag, message, error)

    private fun enabled(msgLevel: LogLevel) = level != LogLevel.OFF && msgLevel.ordinal <= level.ordinal

    private fun log(msgLevel: LogLevel, tag: String, message: String, error: Throwable?) {
        if (!enabled(msgLevel)) return
        when (msgLevel) {
            LogLevel.ERROR -> Log.e("FT-$tag", message, error)
            LogLevel.WARN -> Log.w("FT-$tag", message, error)
            LogLevel.INFO -> Log.i("FT-$tag", message)
            else -> Log.d("FT-$tag", message)
        }
        // Formatted now so the time is when it happened, not when the writer gets to it.
        val line = format(LocalDateTime.now(), msgLevel.tag, tag, message, error)
        writer.execute { append(LocalDate.now(), line, msgLevel) }
    }

    private fun format(time: LocalDateTime, levelTag: String, tag: String, message: String, error: Throwable?): String =
        buildString {
            append(time.format(LineTime)).append("  ").append(levelTag.padEnd(5)).append("  [").append(tag).append("] ")
            append(message)
            if (error != null) append('\n').append(stackTrace(error).trimEnd())
            append('\n')
        }

    private fun stackTrace(t: Throwable) = StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString()

    private fun fileFor(day: LocalDate): File? = dir?.let { File(it, "app-$day.log") }

    private fun append(day: LocalDate, line: String, msgLevel: LogLevel) {
        val file = fileFor(day) ?: return
        if (file.length() > MAX_FILE_BYTES && msgLevel != LogLevel.ERROR) return
        runCatching { file.appendText(line) }
    }

    /** Writes the crash straight away, before the process dies, then lets Android handle it. */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (level != LogLevel.OFF) {
                val line = format(LocalDateTime.now(), "FATAL", "Crash", "App crashed on thread ${thread.name}", error)
                runCatching { fileFor(LocalDate.now())?.appendText(line) }
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Deletes log files older than [days] days (today counts as day 1). */
    fun prune(days: Int) {
        val cutoff = LocalDate.now().minusDays(days.toLong() - 1)
        logDays().filter { it.isBefore(cutoff) }.forEach { fileFor(it)?.delete() }
    }

    /** Applies a new retention setting on the writer thread, after anything still being written. */
    fun pruneAsync(days: Int) = writer.execute { prune(days) }

    /** Days that have a log file, newest first. */
    fun logDays(): List<LocalDate> =
        dir?.listFiles().orEmpty()
            .mapNotNull { f -> f.name.removePrefix("app-").removeSuffix(".log").let { runCatching { LocalDate.parse(it) }.getOrNull() } }
            .sortedDescending()

    fun file(day: LocalDate): File? = fileFor(day)?.takeIf { it.exists() }

    /** The last [maxLines] lines of a day's log. */
    fun read(day: LocalDate, maxLines: Int = 3_000): List<String> =
        file(day)?.let { f -> runCatching { f.readLines().takeLast(maxLines) }.getOrNull() }.orEmpty()

    fun totalBytes(): Long = dir?.listFiles().orEmpty().sumOf { it.length() }

    fun clear() = writer.execute { dir?.listFiles().orEmpty().forEach { it.delete() } }

    /** Basic facts worth having at the top of every session's log. */
    fun deviceSummary(versionName: String): String =
        "FT-Tracker $versionName on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
}
