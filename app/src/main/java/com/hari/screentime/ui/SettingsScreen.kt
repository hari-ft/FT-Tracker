package com.hari.screentime.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.graphics.Color
import com.hari.screentime.data.LogLevel
import com.hari.screentime.data.PermissionId
import com.hari.screentime.data.PermissionState
import com.hari.screentime.data.PermissionStatus
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hari.screentime.data.Settings
import java.time.LocalDate
import java.util.Locale

private val SettingsDateFmt = indianPattern("d MMM yyyy")

@Composable
fun SettingsScreen(
    state: SettingsState,
    padding: PaddingValues,
    versionName: String,
    permissions: List<PermissionStatus>,
    onRetention: (Int) -> Unit,
    onMinDaily: (Int) -> Unit,
    onOpenPermission: (PermissionId) -> Unit,
    onLogLevel: (LogLevel) -> Unit,
    onLogRetention: (Int) -> Unit,
    onViewLog: () -> Unit,
) {
    // A shorter period than the current one deletes data, so it is confirmed first.
    var pendingRetention by rememberSaveable { mutableStateOf<Int?>(null) }
    var pendingMinDaily by rememberSaveable { mutableStateOf<Int?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(
            PaddingValues(16.dp, padding.calculateTopPadding() + 8.dp, 16.dp, padding.calculateBottomPadding() + 16.dp)
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("Keep history", "How long usage is stored on this phone") {
            Column(Modifier.selectableGroup()) {
                Settings.RETENTION_OPTIONS.forEach { months ->
                    OptionRow(
                        label = Settings.retentionLabel(months),
                        selected = months == state.retentionMonths,
                        isDefault = months == Settings.DEFAULT_RETENTION_MONTHS,
                    ) {
                        val shrinking = Settings.retentionRank(months) < Settings.retentionRank(state.retentionMonths)
                        if (shrinking) pendingRetention = months else onRetention(months)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (state.retentionMonths == Settings.FOREVER) "Nothing is ever deleted."
                else "Usage older than ${Settings.retentionLabel(state.retentionMonths)} is deleted automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("Skip rarely used apps", "Every day at 12:00 AM, apps used less than this are deleted") {
            Column(Modifier.selectableGroup()) {
                Settings.MIN_DAILY_OPTIONS.forEach { minutes ->
                    val selected = minutes == state.minDailyMinutes
                    OptionRow(
                        label = Settings.minDailyLabel(minutes),
                        selected = selected,
                        isDefault = minutes == Settings.DEFAULT_MIN_DAILY_MINUTES,
                    ) {
                        // A higher minimum deletes more, so it is confirmed first.
                        if (minutes > state.minDailyMinutes) pendingMinDaily = minutes else onMinDaily(minutes)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (state.minDailyMinutes == 0) "Every app is kept, however briefly it was used."
                else "Counted per app per day. Today is checked after midnight, once the day is over.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(
            "Storage",
            "Saved in a private database on this phone and included in your phone's encrypted Google " +
                "backup, so history survives a reinstall or a new phone. Never shared with anyone.",
        ) {
            val stats = state.stats
            StorageRow("Database size", stats?.let { formatBytes(it.bytes) } ?: "…")
            StorageRow("Oldest record", stats?.oldest?.format(SettingsDateFmt) ?: if (stats == null) "…" else "None yet")
            StorageRow("Days recorded", stats?.days?.let(::formatCount) ?: "…")
            StorageRow("App-day records", stats?.appDays?.let(::formatCount) ?: "…")
            StorageRow("Sessions saved", stats?.sessions?.let(::formatCount) ?: "…", last = true)
        }

        SectionCard(
            "Background saving",
            "Permissions needed to save history every hour and at midnight, even when the app is closed",
        ) {
            val missing = permissions.count { it.state == PermissionState.NOT_ALLOWED }
            Text(
                if (missing == 0) "Everything needed is allowed."
                else "$missing item${if (missing == 1) " needs" else "s need"} attention. Tap Allow to open its setting.",
                style = MaterialTheme.typography.bodySmall,
                color = if (missing == 0) AllowedColor else MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(4.dp))
            permissions.forEachIndexed { i, p ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                PermissionRow(p) { onOpenPermission(p.id) }
            }
        }

        SectionCard("App log", "A record of what the app does, to find problems. Stored only on this phone and never backed up") {
            Text("Log level", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Column(Modifier.selectableGroup()) {
                LogLevel.entries.forEach { level ->
                    OptionRow(
                        label = level.label,
                        selected = level == state.logLevel,
                        isDefault = level == Settings.DEFAULT_LOG_LEVEL,
                    ) { onLogLevel(level) }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Keep logs for", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Column(Modifier.selectableGroup()) {
                Settings.LOG_RETENTION_OPTIONS.forEach { days ->
                    OptionRow(
                        label = Settings.logRetentionLabel(days),
                        selected = days == state.logRetentionDays,
                        isDefault = days == Settings.DEFAULT_LOG_RETENTION_DAYS,
                    ) { onLogRetention(days) }
                }
            }
            Spacer(Modifier.height(8.dp))
            StorageRow(
                "Log files",
                if (state.logDays == 0) "None" else "${formatCount(state.logDays)} day${if (state.logDays == 1) "" else "s"} · ${formatBytes(state.logBytes)}",
                last = true,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onViewLog, modifier = Modifier.fillMaxWidth()) { Text("View log") }
        }

        SectionCard("About") {
            StorageRow("Version", versionName, last = true)
        }
    }

    pendingMinDaily?.let { minutes ->
        AlertDialog(
            onDismissRequest = { pendingMinDaily = null },
            title = { Text("Skip apps under $minutes min?") },
            text = {
                Text(
                    "Apps used less than $minutes min on a day will be deleted from all past days now. " +
                        "Only the last 7 days can be brought back by lowering this again."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingMinDaily = null
                    onMinDaily(minutes)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingMinDaily = null }) { Text("Cancel") } },
        )
    }

    pendingRetention?.let { months ->
        val cutoff = LocalDate.now().minusMonths(months.toLong())
        AlertDialog(
            onDismissRequest = { pendingRetention = null },
            title = { Text("Keep ${Settings.retentionLabel(months)}?") },
            text = {
                Text(
                    "Usage from before ${cutoff.format(SettingsDateFmt)} will be deleted now. " +
                        "This can't be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRetention = null
                    onRetention(months)
                }) { Text("Delete older history") }
            },
            dismissButton = { TextButton(onClick = { pendingRetention = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun OptionRow(label: String, selected: Boolean, isDefault: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .selectable(selected, role = Role.RadioButton) { if (!selected) onSelect() }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
        if (isDefault) {
            Text("  · default", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun StorageRow(label: String, value: String, last: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
    if (!last) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}

private val AllowedColor = Color(0xFF2E9E5B)

/** One permission: status icon, name, its state, why it is needed, and a button when it can be changed. */
@Composable
private fun PermissionRow(p: PermissionStatus, onOpen: () -> Unit) {
    val (icon, tint, defaultText) = when (p.state) {
        PermissionState.ALLOWED -> Triple(Icons.Filled.CheckCircle, AllowedColor, "Allowed")
        PermissionState.NOT_ALLOWED -> Triple(Icons.Filled.Warning, MaterialTheme.colorScheme.error, "Not allowed")
        PermissionState.CANNOT_CHECK -> Triple(Icons.Filled.Info, MaterialTheme.colorScheme.outline, "Can't be checked")
    }
    val stateText = p.stateLabel ?: defaultText
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = stateText, tint = tint, modifier = Modifier.padding(top = 2.dp).size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(stateText, style = MaterialTheme.typography.labelMedium, color = tint, fontWeight = FontWeight.SemiBold)
            Text(
                p.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            p.state == PermissionState.NOT_ALLOWED && p.canOpenSettings -> {
                Spacer(Modifier.width(8.dp))
                Button(onClick = onOpen) { Text("Allow") }
            }
            p.state == PermissionState.CANNOT_CHECK && p.canOpenSettings -> {
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onOpen) { Text("Check") }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1_024 -> "${bytes / 1_024} KB"
    else -> "$bytes B"
}
