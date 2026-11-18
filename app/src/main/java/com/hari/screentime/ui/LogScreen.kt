package com.hari.screentime.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

private val LogDayFmt = indianPattern("EEE, d MMM")
private val WarnColor = Color(0xFFE08A00)

/** One day's log, newest lines at the bottom, coloured by level. Text can be selected and copied. */
@Composable
fun LogScreen(state: LogViewState, padding: PaddingValues, onDay: (LocalDate) -> Unit) {
    val listState = rememberLazyListState()
    // Start at the newest entries, which are at the end of the file.
    LaunchedEffect(state.day, state.lines.size) {
        if (state.lines.isNotEmpty()) listState.scrollToItem(state.lines.lastIndex)
    }
    Column(
        Modifier.fillMaxSize().padding(
            PaddingValues(16.dp, padding.calculateTopPadding() + 8.dp, 16.dp, padding.calculateBottomPadding() + 8.dp)
        )
    ) {
        if (state.days.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.days.forEach { d ->
                    FilterChip(
                        selected = d == state.day,
                        onClick = { onDay(d) },
                        label = { Text(if (d == LocalDate.now()) "Today" else d.format(LogDayFmt)) },
                    )
                }
            }
        }
        val note = when {
            state.loading -> "Loading…"
            state.days.isEmpty() -> "No log entries yet. Entries appear here as the app runs, at the level chosen in Settings."
            state.lines.size >= 3_000 -> "Showing the last 3,000 lines. Share the log for the full file."
            else -> null
        }
        if (note != null) {
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        SelectionContainer {
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                itemsIndexed(state.lines) { _, line ->
                    Text(
                        line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = colorFor(line),
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun colorFor(line: String): Color = when {
    "  ERROR  " in line || "  FATAL  " in line -> MaterialTheme.colorScheme.error
    // Stack trace lines that follow an error.
    line.startsWith("\t") || line.startsWith("Caused by") || line.startsWith("java.") ||
        line.startsWith("android.") || line.startsWith("kotlin") -> MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
    "  WARN   " in line -> WarnColor
    "  DEBUG  " in line -> MaterialTheme.colorScheme.outline
    else -> MaterialTheme.colorScheme.onSurface
}
