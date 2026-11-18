package com.hari.screentime.ui

import android.icu.text.NumberFormat
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Dates, times and numbers are always shown the Indian way, whatever the phone's language is. */
val IndianLocale: Locale = Locale("en", "IN")

fun indianPattern(pattern: String): DateTimeFormatter = DateTimeFormatter.ofPattern(pattern, IndianLocale)

// android.icu groups digits the Indian way (1,00,000); java.text cannot.
private val CountFormat = NumberFormat.getIntegerInstance(IndianLocale)

/** A count with Indian digit grouping: 2627 -> "2,627", 100000 -> "1,00,000". */
fun formatCount(n: Number): String = synchronized(CountFormat) { CountFormat.format(n) }

private val HourFmt = indianPattern("h a")

/** An hour of the day as "12 am", "1 pm": the same style as session times. */
fun hourLabel(hour: Int): String = LocalTime.of(hour, 0).format(HourFmt)

/** Whole percentages of [values] that add up to exactly 100 (largest remainder method). */
fun roundedPercents(values: List<Long>): List<Int> {
    val total = values.sum()
    if (total <= 0) return values.map { 0 }
    val exact = values.map { it * 100.0 / total }
    val result = exact.map { it.toInt() }.toMutableList()
    var left = 100 - result.sum()
    for (i in exact.indices.sortedByDescending { exact[it] - result[it] }) {
        if (left == 0) break
        result[i]++
        left--
    }
    return result
}

fun formatDuration(millis: Long): String {
    val totalMin = millis / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        m > 0 -> "${m}m"
        millis >= 1_000 -> "${millis / 1_000}s"
        else -> "0m"
    }
}

/** Like [formatDuration] but keeps seconds, for individual sessions that are often under a minute. */
fun formatSessionDuration(millis: Long): String {
    val totalSec = millis / 1_000
    val h = totalSec / 3_600
    val m = (totalSec % 3_600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> if (m > 0) "${h}h ${m}m" else "${h}h"
        m > 0 -> if (s > 0) "${m}m ${s}s" else "${m}m"
        s > 0 -> "${s}s"
        else -> "<1s"
    }
}
