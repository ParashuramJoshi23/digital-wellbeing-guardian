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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GuardianApp(viewModel: MainViewModel) {
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val trackedApps by viewModel.trackedApps.collectAsStateWithLifecycle()

    var tabIndex by remember { mutableStateOf(0) }

    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            val context = LocalContext.current

            if (!hasUsageStatsPermission(context)) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Usage access required for monitoring.")
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = {
                            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        }) {
                            Text("Grant Usage Access")
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (!hasUsageStatsPermission(context)) {
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
                        sessions = sessions
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
private fun SummaryTab(
    totalTracked: String,
    longestSession: String,
    overThreshold: Int,
    sessions: List<SessionEntity>
) {
    val formatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Total tracked usage: $totalTracked")
            Text("Longest session: $longestSession")
            Text("Sessions >20 min: $overThreshold")
        }
    }

    Spacer(Modifier.height(12.dp))
    Text("Today\'s sessions", style = MaterialTheme.typography.titleMedium)
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
    var packageName by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var reason by remember(initialReason) { mutableStateOf(initialReason) }

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

    Spacer(Modifier.height(12.dp))

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

    Spacer(Modifier.height(12.dp))
    Text("Tracked apps", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(apps) { app ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(app.displayName)
                        Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                    }
                    Checkbox(
                        checked = app.isTracked,
                        onCheckedChange = { onToggle(app.packageName, it) }
                    )
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
