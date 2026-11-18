package com.hari.screentime.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val ChartColors = listOf(
    Color(0xFF415FFF), Color(0xFF00B8A9), Color(0xFFFF8A3D),
    Color(0xFFB45CFF), Color(0xFFFF4D6D), Color(0xFF9AA3B5),
)

/**
 * Vertical bars with optional labels under them. [labels] may contain blanks to thin them out.
 * Shows the max on the left and, when [showAverage] is set, a dashed average line.
 */
@Composable
fun BarChart(
    values: List<Long>,
    labels: List<String>,
    color: Color,
    description: String,
    modifier: Modifier = Modifier,
    showAverage: Boolean = true,
    /** Days the average is taken over; days before any data was recorded are left out. */
    averageOver: Int = values.size,
) {
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(60_000L)
    val avg = values.sum() / averageOver.coerceAtLeast(1)
    val track = MaterialTheme.colorScheme.surfaceVariant
    val guide = MaterialTheme.colorScheme.outline

    Column(modifier.semantics { contentDescription = description }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Max ${formatDuration(max)}", style = MaterialTheme.typography.labelSmall, color = guide)
            if (showAverage) {
                Text("Avg ${formatDuration(avg)}", style = MaterialTheme.typography.labelSmall, color = guide)
            }
        }
        Spacer(Modifier.height(6.dp))
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            val slot = size.width / values.size
            val barW = (slot * 0.6f).coerceAtMost(28.dp.toPx())
            val r = CornerRadius(barW / 2.5f)
            values.forEachIndexed { i, ms ->
                val x = i * slot + (slot - barW) / 2
                drawRoundRect(track, Offset(x, 0f), Size(barW, size.height), r)
                val h = size.height * ms / max
                if (h > 0) drawRoundRect(color, Offset(x, size.height - h), Size(barW, h), r)
            }
            if (showAverage) {
                val avgY = size.height - size.height * avg / max
                drawLine(
                    guide, Offset(0f, avgY), Offset(size.width, avgY), 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            labels.forEach { label ->
                Text(
                    label,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Visible,
                    softWrap = false,
                )
            }
        }
    }
}

/** One bar per day; labels thinned out for long ranges so they stay readable. */
@Composable
fun DailyBarChart(daily: List<DayTotal>, color: Color, averageOver: Int, modifier: Modifier = Modifier) {
    val step = if (daily.size <= 7) 1 else (daily.size + 6) / 7
    val fmt = indianPattern(if (daily.size <= 7) "EEE" else "d MMM")
    BarChart(
        values = daily.map { it.millis },
        labels = daily.mapIndexed { i, d -> if (i % step == 0) d.date.format(fmt) else "" },
        color = color,
        description = "Daily usage chart: " + daily.joinToString { "${it.date}: ${formatDuration(it.millis)}" },
        modifier = modifier,
        averageOver = averageOver,
    )
}

/** 24 bars, one per hour of the day (midnight to midnight). */
@Composable
fun HourlyBarChart(hourly: List<Long>, color: Color, modifier: Modifier = Modifier) {
    BarChart(
        values = hourly,
        labels = List(24) { h ->
            if (h % 6 == 0) hourLabel(h) else ""
        },
        color = color,
        description = "Hourly usage chart: " + hourly.withIndex()
            .filter { it.value > 0 }
            .joinToString { "${hourLabel(it.index)}: ${formatDuration(it.value)}" },
        modifier = modifier,
        showAverage = false,
    )
}

/** Donut of the top apps' share of time, with "Others" grouping the rest. */
@Composable
fun ShareDonut(apps: List<AppTotal>, totalMillis: Long, modifier: Modifier = Modifier) {
    val top = apps.take(5)
    val othersMs = apps.drop(5).sumOf { it.millis }
    val slices = top.map { it.app.label to it.millis } +
        if (othersMs > 0) listOf("Others" to othersMs) else emptyList()
    val sum = slices.sumOf { it.second }.coerceAtLeast(1)
    val percents = roundedPercents(slices.map { it.second })
    val surface = MaterialTheme.colorScheme.surface

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(140.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(140.dp)) {
                val stroke = 22.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                var start = -90f
                slices.forEachIndexed { i, (_, ms) ->
                    val sweep = 360f * ms / sum
                    drawArc(ChartColors[i % ChartColors.size], start, sweep, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                    // Thin gap between slices.
                    if (slices.size > 1) drawArc(surface, start + sweep - 0.8f, 0.8f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                    start += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(formatDuration(totalMillis), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text("total", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            slices.forEachIndexed { i, (label, ms) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(ChartColors[i % ChartColors.size], CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${percents[i]}%", style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
    }
}
