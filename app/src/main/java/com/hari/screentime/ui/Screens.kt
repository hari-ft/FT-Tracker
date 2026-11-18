package com.hari.screentime.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private val DateFmt = indianPattern("d MMM yyyy")

@Composable
fun PermissionScreen(onOpenSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Allow usage access", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(
            "FT-Tracker needs Usage access to see which apps are on screen and when the phone " +
                "is locked. Data stays on this phone.\n\nTap below, find \"FT-Tracker\" " +
                "in the list and turn it on.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onOpenSettings) { Text("Open settings") }
    }
}

@Composable
fun SectionCard(title: String? = null, subtitle: String? = null, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
                Spacer(Modifier.height(12.dp))
            }
            content()
        }
    }
}

@Composable
fun AppIcon(app: AppInfo, size: Int = 36) {
    if (app.icon != null) {
        Image(app.icon, null, Modifier.size(size.dp).clip(RoundedCornerShape(10.dp)))
    } else {
        Box(
            Modifier.size(size.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) { Text(app.label.take(1).uppercase()) }
    }
}

/** Shown on Today while a permission is missing; the full list is in Settings > Background saving. */
@Composable
private fun MissingPermissionsCard(count: Int, onFix: () -> Unit) {
    SectionCard(
        "Background saving may be blocked",
        "$count permission${if (count == 1) " is" else "s are"} not allowed",
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "History may stop saving while the app is closed.",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onFix) { Text("Fix") }
        }
    }
}

private fun listPadding(padding: PaddingValues) =
    PaddingValues(16.dp, padding.calculateTopPadding() + 8.dp, 16.dp, padding.calculateBottomPadding() + 16.dp)

/** Today's total in a hero header, then each app with a bar relative to the most-used one. */
@Composable
fun TodayScreen(
    state: ReportState,
    padding: PaddingValues,
    missingPermissions: Int,
    onFixPermissions: () -> Unit,
    onAppClick: (String) -> Unit,
) {
    val top = state.apps.firstOrNull()?.millis ?: 1L
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = listPadding(padding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF5B7BFF), Color(0xFF2F45E6))))
                    .padding(24.dp),
            ) {
                Column {
                    Text("Screen time today", color = Color.White.copy(alpha = 0.85f))
                    Text(
                        formatDuration(state.totalMillis),
                        color = Color.White, fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.displaySmall,
                    )
                    Text(
                        "${formatCount(state.totalSessions)} app opens · counted only while unlocked",
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (missingPermissions > 0) {
            item { MissingPermissionsCard(missingPermissions, onFixPermissions) }
        }
        if (state.apps.isEmpty() && !state.loading) {
            item { if (state.hiddenApps > 0) HiddenAppsNote(state) else EmptyNote() }
        }
        items(state.apps, key = { it.app.pkg }) { a ->
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable { onAppClick(a.app.pkg) },
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(a.app, 40)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row {
                            Text(a.app.label, Modifier.weight(1f), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(formatDuration(a.millis), fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { a.millis.toFloat() / top },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            drawStopIndicator = {},
                        )
                    }
                }
            }
        }
        if (state.apps.isNotEmpty() && state.hiddenApps > 0) {
            item {
                Text(
                    hiddenText(state),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
    }
}

private fun hiddenText(state: ReportState): String {
    val n = state.hiddenApps
    return "${formatCount(n)} app${if (n == 1) "" else "s"} used less than ${state.minDailyMinutes} min today " +
        "${if (n == 1) "is" else "are"} hidden (Settings > Skip rarely used apps)."
}

@Composable
private fun HiddenAppsNote(state: ReportState) {
    SectionCard {
        Text(
            "No app has reached ${state.minDailyMinutes} min yet. " + hiddenText(state),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Fixed range chips plus a "Custom" chip that opens the date picker, and the active dates. */
@Composable
private fun RangeSelector(state: ReportState, onRange: (ReportRange) -> Unit, onCustom: () -> Unit) {
    val scroll = rememberScrollState()
    // Keep the selected chip visible: the later chips sit off-screen when the row is scrolled to the start.
    LaunchedEffect(state.range, scroll.maxValue) {
        scroll.scrollTo(if (state.range.ordinal >= ReportRange.MONTH.ordinal) scroll.maxValue else 0)
    }
    Column {
        Row(Modifier.horizontalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReportRange.entries.forEach { r ->
                FilterChip(
                    selected = state.range == r,
                    onClick = { if (r == ReportRange.CUSTOM) onCustom() else onRange(r) },
                    label = { Text(if (r == ReportRange.CUSTOM) "Custom…" else r.label) },
                )
            }
        }
        Text(
            if (state.from == state.to) state.from.format(DateFmt)
            else "${state.from.format(DateFmt)} – ${state.to.format(DateFmt)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 4.dp, start = 4.dp),
        )
    }
}

private fun LazyListScope.summaryAndCharts(state: ReportState, color: Color) {
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("Total", formatDuration(state.totalMillis), Modifier.weight(1f))
            StatTile("Daily avg", formatDuration(state.totalMillis / state.dayCount), Modifier.weight(1f))
            StatTile("App opens", formatCount(state.totalSessions), Modifier.weight(1f))
        }
    }
    if (state.daily.size > 1) {
        item {
            SectionCard("Daily usage") { DailyBarChart(state.daily, color, averageOver = state.dayCount) }
        }
    }
    item {
        val peak = state.hourly.withIndex().maxByOrNull { it.value }?.takeIf { it.value > 0 }
        val subtitle = buildString {
            if (state.daily.size > 1) append("Total for each hour across ${formatCount(state.daily.size)} days")
            if (peak != null) {
                if (isNotEmpty()) append(" · ")
                append("Busiest: ${hourLabel(peak.index)}")
            }
        }.ifEmpty { null }
        SectionCard(if (state.daily.size > 1) "Time of day" else "Hourly usage", subtitle) {
            HourlyBarChart(state.hourly, color)
        }
    }
}

@Composable
fun ReportScreen(
    state: ReportState,
    padding: PaddingValues,
    onRange: (ReportRange) -> Unit,
    onCustom: () -> Unit,
    onAppClick: (String) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = listPadding(padding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { RangeSelector(state, onRange, onCustom) }
        if (state.apps.isEmpty() && !state.loading) {
            item { if (state.hiddenApps > 0) HiddenAppsNote(state) else EmptyNote() }
            return@LazyColumn
        }
        summaryAndCharts(state, ChartColors[0])
        item {
            SectionCard("Share of time") { ShareDonut(state.apps, state.totalMillis, Modifier.fillMaxWidth()) }
        }
        item {
            SectionCard("App usage table", "Tap an app for its details") {
                TableHeader("App", "Opens", "Share")
                state.apps.forEachIndexed { i, a ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    AppTableRow(i + 1, a) { onAppClick(a.app.pkg) }
                }
            }
        }
    }
}

/** Everything about one app over the selected range: totals, charts, a calendar and every session. */
@Composable
fun AppDetailScreen(
    state: ReportState,
    padding: PaddingValues,
    onRange: (ReportRange) -> Unit,
    onCustom: () -> Unit,
    onShiftMonth: (Long) -> Unit,
    onDayClick: (LocalDate) -> Unit,
) {
    val app = state.app ?: return
    val usedDays = state.daily.filter { it.millis > 0 || it.sessions > 0 }.reversed()
    val sessionsByDay = state.sessions.groupBy { it.date }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = listPadding(padding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(app, 56)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(app.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(app.pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
        item { RangeSelector(state, onRange, onCustom) }
        if (state.loading) return@LazyColumn
        summaryAndCharts(state, ChartColors[0])
        item { UsageCalendar(state, onShiftMonth, onDayClick) }
        item { SessionSummary(state.sessions, opens = state.totalSessions) }
        if (usedDays.isEmpty()) {
            item {
                SectionCard {
                    Text("Not used in this period.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(usedDays, key = { it.date.toString() }) { d ->
            DaySessions(d, sessionsByDay[d.date].orEmpty())
        }
    }
}

/** Start/end date picker limited to the period we keep history for. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateRangeDialog(
    initialFrom: LocalDate,
    initialTo: LocalDate,
    earliest: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate, LocalDate) -> Unit,
) {
    val today = LocalDate.now()
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initialFrom.toUtcMillis(),
        initialSelectedEndDateMillis = initialTo.toUtcMillis(),
        yearRange = earliest.year..today.year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis.toUtcDate() in earliest..today
            override fun isSelectableYear(year: Int) = year in earliest.year..today.year
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedStartDateMillis != null,
                onClick = {
                    val start = state.selectedStartDateMillis?.toUtcDate() ?: return@TextButton
                    onConfirm(start, state.selectedEndDateMillis?.toUtcDate() ?: start)
                },
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DateRangePicker(
            state = state,
            modifier = Modifier.weight(1f),
            title = { Text("Select dates", Modifier.padding(start = 24.dp, top = 16.dp)) },
        )
    }
}

// The date picker works in UTC midnight millis.
private fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
private fun Long.toUtcDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier) {
    Card(
        modifier, shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun TableHeader(first: String, opens: String, share: String?) {
    val style = MaterialTheme.typography.labelSmall
    val color = MaterialTheme.colorScheme.outline
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        if (share != null) Text("#", Modifier.width(24.dp), style = style, color = color)
        Text(first, Modifier.weight(1f), style = style, color = color)
        Text("Time", Modifier.width(64.dp), style = style, color = color, textAlign = TextAlign.End)
        Text(opens, Modifier.width(48.dp), style = style, color = color, textAlign = TextAlign.End)
        if (share != null) Text(share, Modifier.width(48.dp), style = style, color = color, textAlign = TextAlign.End)
    }
}

@Composable
private fun AppTableRow(rank: Int, a: AppTotal, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$rank", Modifier.width(24.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(a.app, 24)
            Spacer(Modifier.width(8.dp))
            Text(a.app.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(formatDuration(a.millis), Modifier.width(64.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
        Text(formatCount(a.sessions), Modifier.width(48.dp), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
        Text("${a.percent}%", Modifier.width(48.dp), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
    }
}

@Composable
private fun EmptyNote() {
    SectionCard {
        Text(
            "No usage recorded for this period. History is kept for the period chosen in Settings, " +
                "starting from when usage access was granted (the 7 days before that are imported automatically).",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
