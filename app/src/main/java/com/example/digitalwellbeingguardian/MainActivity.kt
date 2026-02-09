package com.example.digitalwellbeingguardian

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.digitalwellbeingguardian.data.SessionEntity
import com.example.digitalwellbeingguardian.data.TrackedAppEntity
import com.example.digitalwellbeingguardian.service.UsageMonitorService
import com.example.digitalwellbeingguardian.ui.MainViewModel
import com.example.digitalwellbeingguardian.util.formatDuration
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybeRequestNotificationPermission()

        setContent {
            MaterialTheme {
                GuardianApp(viewModel)
            }
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

data class InstalledAppInfo(
    val packageName: String,
    val appName: String
)

data class LiveStatus(
    val serviceRunning: Boolean,
    val activePackage: String?,
    val activeStartMs: Long,
    val lastPollMs: Long
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GuardianApp(viewModel: MainViewModel) {
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val trackedApps by viewModel.trackedApps.collectAsStateWithLifecycle()

    var tabIndex by remember { mutableStateOf(0) }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()

            val liveStatus by produceState(initialValue = readLiveStatus(context), context) {
                while (true) {
                    value = readLiveStatus(context)
                    delay(1000)
                }
            }

            val usagePermission = hasUsageStatsPermission(context)
            val notificationsGranted = hasNotificationPermission(context)

            PermissionAndStatusCard(
                usagePermission = usagePermission,
                notificationsGranted = notificationsGranted,
                liveStatus = liveStatus
            )

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (!usagePermission) {
                        Toast.makeText(context, "Grant usage access first", Toast.LENGTH_SHORT).show()
                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    } else {
                        UsageMonitorService.start(context)
                        Toast.makeText(context, "Monitoring started", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text("Start Monitoring")
                }
                Button(onClick = {
                    UsageMonitorService.stop(context)
                    Toast.makeText(context, "Monitoring stopped", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Stop")
                }
                Button(onClick = {
                    scope.launch {
                        runCatching { viewModel.exportLogs() }
                            .onSuccess { uri ->
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/json"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "Export usage logs"))
                            }
                            .onFailure {
                                Toast.makeText(context, "Failed to export logs", Toast.LENGTH_SHORT).show()
                            }
                    }
                }) {
                    Text("Export Logs")
                }
            }

            Spacer(Modifier.height(12.dp))

            TabRow(selectedTabIndex = tabIndex) {
                Tab(selected = tabIndex == 0, onClick = { tabIndex = 0 }, text = { Text("Today") })
                Tab(selected = tabIndex == 1, onClick = { tabIndex = 1 }, text = { Text("Tracked Apps") })
            }

            Spacer(Modifier.height(12.dp))

            Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                when (tabIndex) {
                    0 -> SummaryTab(
                        totalTracked = formatDuration(summary.totalTrackedMs),
                        longestSession = formatDuration(summary.longestSessionMs),
                        overThreshold = summary.sessionsOverThreshold,
                        nearRunaway = summary.nearRunawaySessions,
                        microCheckScore = summary.microCheckScore,
                        sessions = sessions,
                        liveStatus = liveStatus
                    )

                    else -> TrackedAppsTab(
                        apps = trackedApps,
                        onToggle = viewModel::toggleTracked,
                        onAdd = viewModel::addTrackedApp,
                        initialReason = viewModel.loadDefaultReason(),
                        onReasonSave = viewModel::saveDefaultReason
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionAndStatusCard(
    usagePermission: Boolean,
    notificationsGranted: Boolean,
    liveStatus: LiveStatus
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Status", style = MaterialTheme.typography.titleMedium)
            Text("Usage access: ${if (usagePermission) "Granted" else "Missing"}")
            Text("Notifications: ${if (notificationsGranted) "Granted" else "Missing"}")
            Text("Monitor service: ${if (liveStatus.serviceRunning) "Running" else "Stopped"}")
            if (liveStatus.serviceRunning && liveStatus.activePackage != null && liveStatus.activeStartMs > 0) {
                val elapsed = (System.currentTimeMillis() - liveStatus.activeStartMs).coerceAtLeast(0)
                Text("Live session: ${liveStatus.activePackage} • ${formatDuration(elapsed)}")
            }
        }
    }
}

@Composable
private fun SummaryTab(
    totalTracked: String,
    longestSession: String,
    overThreshold: Int,
    nearRunaway: Int,
    microCheckScore: Int,
    sessions: List<SessionEntity>,
    liveStatus: LiveStatus
) {
    val formatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Total tracked usage: $totalTracked")
            Text("Longest session: $longestSession")
            Text("Sessions >20 min: $overThreshold")
            Text("Near-runaway (10–20 min): $nearRunaway")
            Text("Micro-check score: $microCheckScore")
            if (liveStatus.serviceRunning && liveStatus.activePackage != null && liveStatus.activeStartMs > 0) {
                val elapsed = (System.currentTimeMillis() - liveStatus.activeStartMs).coerceAtLeast(0)
                Text("Now tracking: ${liveStatus.activePackage} (${formatDuration(elapsed)})")
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    Text("Today's sessions", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(sessions) { session ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(session.packageName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${formatter.format(Date(session.startTime))} - ${formatter.format(Date(session.endTime))} • ${formatDuration(session.durationMs)}"
                    )
                    if (session.thresholdCrossed) {
                        Text("Crossed threshold")
                    }
                    if (session.interventionAction != null) {
                        Text("Intervention: ${session.interventionAction}")
                    }
                    if (!session.reason.isNullOrBlank()) {
                        Text("Reason: ${session.reason}")
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackedAppsTab(
    apps: List<TrackedAppEntity>,
    onToggle: (String, Boolean) -> Unit,
    onAdd: (String, String) -> Unit,
    initialReason: String,
    onReasonSave: (String) -> Unit
) {
    val context = LocalContext.current

    var packageName by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var reason by remember(initialReason) { mutableStateOf(initialReason) }

    var installedApps by remember { mutableStateOf<List<InstalledAppInfo>>(emptyList()) }
    var appSearch by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        installedApps = loadInstalledApps(context)
    }

    val filteredInstalled = remember(installedApps, appSearch) {
        if (appSearch.isBlank()) installedApps.take(40)
        else installedApps.filter {
            it.appName.contains(appSearch, ignoreCase = true) ||
                it.packageName.contains(appSearch, ignoreCase = true)
        }.take(60)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Intervention default reason (optional)")
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("e.g. finishing a match") }
                    )
                    Button(onClick = { onReasonSave(reason) }) { Text("Save reason") }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Add custom app")
                    OutlinedTextField(
                        value = packageName,
                        onValueChange = { packageName = it },
                        label = { Text("Package name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text("Display name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(onClick = {
                        if (packageName.isNotBlank()) {
                            onAdd(packageName, label)
                            packageName = ""
                            label = ""
                        }
                    }) {
                        Text("Add + Track")
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Find installed apps")
                    OutlinedTextField(
                        value = appSearch,
                        onValueChange = { appSearch = it },
                        label = { Text("Search app name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Tap Add to move app into Tracked apps")
                }
            }
        }

        items(filteredInstalled, key = { it.packageName }) { app ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(app.appName)
                        Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = { onAdd(app.packageName, app.appName) }) {
                        Text("Add")
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            Text("Tracked apps", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
        }

        items(apps, key = { it.packageName }) { app ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(app.displayName)
                        Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = { onToggle(app.packageName, !app.isTracked) }) {
                        Text(if (app.isTracked) "Tracked" else "Enable")
                    }
                }
            }
        }
    }
}

private fun hasUsageStatsPermission(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = appOps.checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        android.os.Process.myUid(),
        context.packageName
    )
    return mode == AppOpsManager.MODE_ALLOWED
}

private fun hasNotificationPermission(context: Context): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
}

private fun readLiveStatus(context: Context): LiveStatus {
    val prefs = context.getSharedPreferences(UsageMonitorService.PREFS, Context.MODE_PRIVATE)
    return LiveStatus(
        serviceRunning = prefs.getBoolean(UsageMonitorService.KEY_SERVICE_RUNNING, false),
        activePackage = prefs.getString(UsageMonitorService.KEY_ACTIVE_PACKAGE, null),
        activeStartMs = prefs.getLong(UsageMonitorService.KEY_ACTIVE_START_MS, 0L),
        lastPollMs = prefs.getLong(UsageMonitorService.KEY_LAST_POLL_MS, 0L)
    )
}

private fun loadInstalledApps(context: Context): List<InstalledAppInfo> {
    val pm = context.packageManager
    val launchIntent = Intent(Intent.ACTION_MAIN, null).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
    }

    return pm.queryIntentActivities(launchIntent, 0)
        .map {
            InstalledAppInfo(
                packageName = it.activityInfo.packageName,
                appName = it.loadLabel(pm).toString()
            )
        }
        .distinctBy { it.packageName }
        .sortedBy { it.appName.lowercase(Locale.getDefault()) }
}
