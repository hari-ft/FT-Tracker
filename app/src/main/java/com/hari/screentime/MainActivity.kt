package com.hari.screentime

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.core.content.FileProvider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.hari.screentime.data.AppLog
import com.hari.screentime.data.AppPermissions
import com.hari.screentime.data.PermissionId
import com.hari.screentime.data.PermissionState
import com.hari.screentime.data.PermissionStatus
import com.hari.screentime.data.Permissions
import com.hari.screentime.ui.AppDetailScreen
import com.hari.screentime.ui.DateRangeDialog
import com.hari.screentime.ui.PermissionScreen
import com.hari.screentime.ui.ReportRange
import com.hari.screentime.ui.ReportScreen
import com.hari.screentime.ui.ScreenTimeTheme
import com.hari.screentime.ui.SettingsScreen
import com.hari.screentime.ui.LogScreen
import com.hari.screentime.ui.TodayScreen
import com.hari.screentime.ui.UsageViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val vm: UsageViewModel by viewModels()
    private var hasAccess by mutableStateOf(false)
    private var permissions by mutableStateOf(emptyList<PermissionStatus>())

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ScreenTimeTheme {
                if (!hasAccess) {
                    Scaffold { padding ->
                        Box(Modifier.padding(padding)) { PermissionScreen { openPermission(PermissionId.USAGE_ACCESS) } }
                    }
                    return@ScreenTimeTheme
                }
                val today by vm.today.collectAsStateWithLifecycle()
                val report by vm.report.collectAsStateWithLifecycle()
                val detail by vm.detail.collectAsStateWithLifecycle()
                val settings by vm.settings.collectAsStateWithLifecycle()
                val log by vm.log.collectAsStateWithLifecycle()
                var confirmClearLog by rememberSaveable { mutableStateOf(false) }
                var tab by rememberSaveable { mutableIntStateOf(0) }
                var pickingDates by rememberSaveable { mutableStateOf(false) }
                val scope = rememberCoroutineScope()

                // Live updates: re-read usage every few seconds while the app is on screen.
                val lifecycle = LocalLifecycleOwner.current.lifecycle
                LaunchedEffect(lifecycle) {
                    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                        while (true) {
                            vm.refresh()
                            delay(LIVE_REFRESH_MS)
                        }
                    }
                }

                val showingDetail = tab == 1 && detail != null
                val showingLog = tab == 2 && log != null
                BackHandler(enabled = showingDetail) { vm.closeApp() }
                BackHandler(enabled = showingLog) { vm.closeLog() }

                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    when {
                                        showingDetail -> detail?.app?.label ?: ""
                                        showingLog -> "App log"
                                        tab == 0 -> "FT-Tracker"
                                        tab == 2 -> "Settings"
                                        else -> "Usage Report"
                                    },
                                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            },
                            navigationIcon = {
                                if (showingDetail || showingLog) {
                                    IconButton(onClick = { if (showingLog) vm.closeLog() else vm.closeApp() }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                                    }
                                }
                            },
                            actions = {
                                if (showingLog) {
                                    val day = log?.day
                                    IconButton(onClick = { day?.let(::shareLog) }, enabled = day != null) {
                                        Icon(Icons.Filled.Share, "Share log")
                                    }
                                    IconButton(onClick = { confirmClearLog = true }, enabled = log?.days?.isNotEmpty() == true) {
                                        Icon(Icons.Filled.Delete, "Clear logs")
                                    }
                                    IconButton(onClick = { vm.openLog(day) }) {
                                        Icon(Icons.Filled.Refresh, "Reload log")
                                    }
                                } else {
                                    IconButton(onClick = { scope.launch { vm.refresh() } }) {
                                        Icon(Icons.Filled.Refresh, "Refresh")
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                        )
                    },
                    bottomBar = {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                            NavigationBarItem(
                                selected = tab == 0,
                                onClick = { tab = 0 },
                                icon = { Icon(Icons.Filled.Home, null) },
                                label = { Text("Today") },
                            )
                            NavigationBarItem(
                                selected = tab == 1,
                                onClick = { if (tab == 1) vm.closeApp() else tab = 1 },
                                icon = { Icon(Icons.Filled.DateRange, null) },
                                label = { Text("Report") },
                            )
                            NavigationBarItem(
                                selected = tab == 2,
                                onClick = { if (tab == 2) vm.closeLog() else tab = 2 },
                                icon = { Icon(Icons.Filled.Settings, null) },
                                label = { Text("Settings") },
                            )
                        }
                    },
                ) { padding ->
                    val current = detail
                    val logState = log
                    when {
                        tab == 2 && logState != null -> LogScreen(logState, padding, onDay = vm::openLog)
                        tab == 2 -> SettingsScreen(
                            settings, padding, versionName, permissions,
                            onRetention = vm::setRetention,
                            onMinDaily = vm::setMinDaily,
                            onOpenPermission = ::openPermission,
                            onLogLevel = vm::setLogLevel,
                            onLogRetention = vm::setLogRetention,
                            onViewLog = { vm.openLog() },
                        )
                        tab == 0 -> TodayScreen(
                            today, padding,
                            missingPermissions = permissions.count { it.state == PermissionState.NOT_ALLOWED },
                            onFixPermissions = { tab = 2 },
                            onAppClick = { pkg ->
                                vm.setRange(ReportRange.TODAY)
                                vm.openApp(pkg)
                                tab = 1
                            },
                        )
                        current != null -> AppDetailScreen(
                            current, padding, vm::setRange,
                            onCustom = { pickingDates = true },
                            onShiftMonth = vm::shiftCalendar,
                            onDayClick = { day -> vm.setCustomRange(day, day) },
                        )
                        else -> ReportScreen(report, padding, vm::setRange, { pickingDates = true }, vm::openApp)
                    }
                }

                if (confirmClearLog) {
                    AlertDialog(
                        onDismissRequest = { confirmClearLog = false },
                        title = { Text("Clear all logs?") },
                        text = { Text("Every saved log file will be deleted. Usage history is not affected.") },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmClearLog = false
                                vm.clearLogs()
                            }) { Text("Clear") }
                        },
                        dismissButton = { TextButton(onClick = { confirmClearLog = false }) { Text("Cancel") } },
                    )
                }

                if (pickingDates) {
                    val shown = detail ?: report
                    DateRangeDialog(
                        initialFrom = shown.from,
                        initialTo = shown.to,
                        earliest = shown.earliest,
                        onDismiss = { pickingDates = false },
                        onConfirm = { from, to ->
                            pickingDates = false
                            vm.setCustomRange(from, to)
                        },
                    )
                }
            }
        }
    }

    private val versionName: String by lazy { packageManager.getPackageInfo(packageName, 0).versionName.orEmpty() }

    override fun onResume() {
        super.onResume()
        hasAccess = Permissions.hasUsageAccess(this)
        // Re-checked on every return, so a permission granted in system settings shows at once.
        val previous = permissions
        permissions = AppPermissions.check(this)
        logPermissionChanges(previous, permissions)
    }

    /** Logs the full list once, then only permissions whose state changed. */
    private fun logPermissionChanges(before: List<PermissionStatus>, after: List<PermissionStatus>) {
        if (before.isEmpty()) {
            AppLog.d("Permissions", after.joinToString { "${it.title}: ${it.stateLabel ?: it.state}" })
            after.filter { it.state == PermissionState.NOT_ALLOWED }
                .forEach { AppLog.w("Permissions", "${it.title} is not allowed") }
            return
        }
        val old = before.associateBy { it.id }
        after.forEach { p ->
            val was = old[p.id]?.state
            if (was != null && was != p.state) AppLog.i("Permissions", "${p.title}: $was -> ${p.state}")
        }
    }

    /** Shares one day's log file through the system share sheet (email, chat, Drive...). */
    private fun shareLog(day: LocalDate) {
        val file = AppLog.file(day) ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.logs", file)
        val label = day.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale("en", "IN")))
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "FT-Tracker log, $label")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        AppLog.i("Log", "Sharing log for $label")
        startActivity(Intent.createChooser(send, "Share log"))
    }

    /** Opens the best available system screen for [id], falling back to the app's info page. */
    private fun openPermission(id: PermissionId) {
        for (intent in AppPermissions.settingsIntents(this, id)) {
            try {
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
                AppLog.d("Permissions", "No screen for ${intent.action}; trying the next one")
                // Not on this phone; try the next screen.
            } catch (e: SecurityException) {
                AppLog.w("Permissions", "Screen ${intent.action} is protected; trying the next one", e)
                // Protected by the phone maker; try the next screen.
            }
        }
    }

    companion object {
        private const val LIVE_REFRESH_MS = 30_000L
    }
}
