package com.hari.screentime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hari.screentime.data.UsageSession
import com.hari.screentime.data.isContinuation
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.WeekFields

private val MonthFmt = indianPattern("MMMM yyyy")
private val DayFmt = indianPattern("EEE, d MMM")
private val TimeFmt = indianPattern("h:mm a")

/**
 * One month of an app's use: each day shows its time, shaded by how heavy it was relative to the
 * busiest day of the month. Tapping a day shows that day's report and sessions.
 */
@Composable
fun UsageCalendar(
    state: ReportState,
    onShiftMonth: (Long) -> Unit,
    onDayClick: (LocalDate) -> Unit,
) {
    val month = state.calendarMonth
    val today = LocalDate.now()
    val earliest = state.earliest
    val days = state.calendar
    val max = days.values.maxOfOrNull { it.millis }?.coerceAtLeast(1) ?: 1
    val monthTotal = days.values.sumOf { it.millis }
    val usedDays = days.values.count { it.millis > 0 }
    val firstDow = WeekFields.of(IndianLocale).firstDayOfWeek
    // Blank cells before day 1 so it lands under its weekday.
    val lead = (month.atDay(1).dayOfWeek.value - firstDow.value + 7) % 7
    val cells: List<LocalDate?> = List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
    val selectedDay = state.from.takeIf { it == state.to }
    val primary = MaterialTheme.colorScheme.primary

    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onShiftMonth(-1) }, enabled = month > YearMonth.from(earliest)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month")
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(month.format(MonthFmt), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    if (usedDays == 0) "Not used this month"
                    else "${formatDuration(monthTotal)} · used on ${formatCount(usedDays)} day${if (usedDays == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            IconButton(onClick = { onShiftMonth(1) }, enabled = month < YearMonth.from(today)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month")
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            repeat(7) { i ->
                Text(
                    firstDow.plus(i.toLong()).getDisplayName(TextStyle.NARROW, IndianLocale),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (i in 0 until 7) {
                    val date = week.getOrNull(i)
                    if (date == null) {
                        Spacer(Modifier.weight(1f))
                        continue
                    }
                    val ms = days[date]?.millis ?: 0L
                    val enabled = date in earliest..today
                    val ratio = ms.toFloat() / max
                    val fill = when {
                        ms > 0 -> primary.copy(alpha = 0.15f + 0.85f * ratio)
                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.6f else 0.25f)
                    }
                    val textColor = when {
                        !enabled -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        ratio > 0.55f -> Color.White
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    var cell = Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(10.dp)).background(fill)
                    if (date == selectedDay) cell = cell.border(2.dp, primary, RoundedCornerShape(10.dp))
                    if (enabled) cell = cell.clickable { onDayClick(date) }
                    Column(
                        cell.semantics { contentDescription = "${date.format(DayFmt)}: ${formatDuration(ms)}" }
                            .padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            "${date.dayOfMonth}",
                            fontSize = 12.sp,
                            fontWeight = if (date == today) FontWeight.ExtraBold else FontWeight.Medium,
                            color = textColor,
                        )
                        if (ms >= 60_000) {
                            Text(formatDuration(ms), fontSize = 9.sp, maxLines = 1, softWrap = false, color = textColor)
                        }
                    }
                }
            }
        }
        Text(
            "Tap a day to see its sessions",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

private data class LengthBucket(val label: String, val upTo: Long)

private val Buckets = listOf(
    LengthBucket("Under 1 min", 60_000L),
    LengthBucket("1 – 5 min", 5 * 60_000L),
    LengthBucket("5 – 15 min", 15 * 60_000L),
    LengthBucket("15 – 30 min", 30 * 60_000L),
    LengthBucket("30 min – 1 h", 60 * 60_000L),
    LengthBucket("Over 1 h", Long.MAX_VALUE),
)

/**
 * Session count, average and longest, then how the app's time splits across session lengths.
 * [opens] is the app's open count for the range; days saved before session details existed have
 * opens but no sessions, which is pointed out rather than shown as a mismatch.
 */
@Composable
fun SessionSummary(sessions: List<UsageSession>, opens: Int) {
    val zone = ZoneId.systemDefault()
    // A midnight continuation is part of an earlier session, not a new one.
    val started = sessions.count { !it.isContinuation(zone) }
    if (started == 0 && opens == 0) return
    val total = sessions.sumOf { it.millis }.coerceAtLeast(1)
    SectionCard("Sessions recorded", "Each time the app was opened, until you left it or locked the phone") {
        if (opens > started) {
            Text(
                "Some days in this range have totals but no session details (details are saved from version 1.2 on).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        if (sessions.isEmpty()) return@SectionCard
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MiniStat("Sessions", formatCount(started), Modifier.weight(1f))
            MiniStat("Average", formatSessionDuration(total / started.coerceAtLeast(1)), Modifier.weight(1f))
            MiniStat("Longest", formatSessionDuration(sessions.maxOf { it.millis }), Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))
        Text("Split by session length", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        var lower = 0L
        Buckets.forEach { b ->
            val inBucket = sessions.filter { it.millis >= lower && it.millis < b.upTo }
            lower = b.upTo
            if (inBucket.isEmpty()) return@forEach
            val ms = inBucket.sumOf { it.millis }
            Column(Modifier.padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(b.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${formatCount(inBucket.size)}×",
                        Modifier.width(48.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.End,
                    )
                    Text(
                        formatDuration(ms),
                        Modifier.width(72.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.End,
                    )
                }
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { ms.toFloat() / total },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    drawStopIndicator = {},
                )
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** One day's sessions, newest first, with the day's total in the header. */
@Composable
fun DaySessions(day: DayTotal, sessions: List<UsageSession>) {
    val zone = ZoneId.systemDefault()
    val count = if (sessions.isNotEmpty()) sessions.count { !it.isContinuation(zone) } else day.sessions
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(day.date.format(DayFmt), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "${formatDuration(day.millis)} · ${formatCount(count)} session${if (count == 1) "" else "s"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Spacer(Modifier.height(8.dp))
        if (sessions.isEmpty()) {
            Text(
                "Session details weren't recorded for this day. They are saved from this version of the app onward.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        sessions.forEachIndexed { i, s ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            val start = Instant.ofEpochMilli(s.start).atZone(zone)
            val end = Instant.ofEpochMilli(s.end).atZone(zone)
            // Midnight-crossing sessions are split per day; say so on both parts.
            val continues = end.toLocalDate() != start.toLocalDate()
            val endLabel = end.format(TimeFmt) + if (continues) " (continues next day)" else ""
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${start.format(TimeFmt)} – $endLabel", style = MaterialTheme.typography.bodyMedium)
                    if (s.isContinuation(zone)) {
                        Text(
                            "Continued from the previous day",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                Text(
                    formatSessionDuration(s.millis),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
