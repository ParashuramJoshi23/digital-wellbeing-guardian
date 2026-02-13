package com.example.digitalwellbeingguardian.ui

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.digitalwellbeingguardian.data.AppDatabase
import com.example.digitalwellbeingguardian.data.DailySummary
import com.example.digitalwellbeingguardian.data.GuardianRepository
import com.example.digitalwellbeingguardian.data.SessionEntity
import com.example.digitalwellbeingguardian.data.TrackedAppEntity
import com.example.digitalwellbeingguardian.service.UsageMonitorService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repository: GuardianRepository

    val trackedApps: StateFlow<List<TrackedAppEntity>>
    val summary: StateFlow<DailySummary>
    val sessions: StateFlow<List<SessionEntity>>

    init {
        val db = AppDatabase.get(app)
        repository = GuardianRepository(db.sessionDao(), db.trackedAppDao())

        trackedApps = repository.trackedApps.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            emptyList()
        )

        summary = repository.observeSummaryFor(LocalDate.now()).stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            DailySummary(0, 0, 0, 0, 0)
        )

        sessions = repository.observeSessionsFor(LocalDate.now()).stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            emptyList()
        )

        viewModelScope.launch { repository.ensureDefaults() }
    }

    fun toggleTracked(packageName: String, tracked: Boolean) {
        viewModelScope.launch {
            repository.toggleTracked(packageName, tracked)
        }
    }

    fun addTrackedApp(packageName: String, label: String) {
        viewModelScope.launch {
            repository.addTrackedApp(packageName.trim(), label.trim())
        }
    }

    fun saveDefaultReason(reason: String) {
        getApplication<Application>()
            .getSharedPreferences(UsageMonitorService.PREFS, Application.MODE_PRIVATE)
            .edit()
            .putString(UsageMonitorService.KEY_REASON, reason.ifBlank { null })
            .apply()
    }

    fun loadDefaultReason(): String {
        return getApplication<Application>()
            .getSharedPreferences(UsageMonitorService.PREFS, Application.MODE_PRIVATE)
            .getString(UsageMonitorService.KEY_REASON, "")
            .orEmpty()
    }

    fun saveIntentConfig(targetPackage: String, windowsCsv: String, graceMinutes: Int) {
        getApplication<Application>()
            .getSharedPreferences(UsageMonitorService.PREFS, Application.MODE_PRIVATE)
            .edit()
            .putString(UsageMonitorService.KEY_INTENT_TARGET_PACKAGE, targetPackage.ifBlank { "com.twitter.android" })
            .putString(UsageMonitorService.KEY_CHECK_WINDOWS_CSV, windowsCsv.ifBlank { "11:30,16:30,21:00" })
            .putInt(UsageMonitorService.KEY_WINDOW_GRACE_MIN, graceMinutes.coerceIn(0, 120))
            .apply()
    }

    fun loadIntentTargetPackage(): String =
        getApplication<Application>()
            .getSharedPreferences(UsageMonitorService.PREFS, Application.MODE_PRIVATE)
            .getString(UsageMonitorService.KEY_INTENT_TARGET_PACKAGE, "com.twitter.android")
            .orEmpty()

    fun loadCheckWindowsCsv(): String =
        getApplication<Application>()
            .getSharedPreferences(UsageMonitorService.PREFS, Application.MODE_PRIVATE)
            .getString(UsageMonitorService.KEY_CHECK_WINDOWS_CSV, "11:30,16:30,21:00")
            .orEmpty()

    fun loadWindowGraceMinutes(): Int =
        getApplication<Application>()
            .getSharedPreferences(UsageMonitorService.PREFS, Application.MODE_PRIVATE)
            .getInt(UsageMonitorService.KEY_WINDOW_GRACE_MIN, 15)

    fun loadTodayDeferredCount(): Int {
        val key = UsageMonitorService.KEY_DAILY_DEFERRED_PREFIX + LocalDate.now().toString()
        return getApplication<Application>()
            .getSharedPreferences(UsageMonitorService.PREFS, Application.MODE_PRIVATE)
            .getInt(key, 0)
    }

    suspend fun exportLogs(): Uri {
        val app = getApplication<Application>()
        val sessions = repository.allSessions()
        val trackedApps = repository.allTrackedApps()

        val payload = JSONObject().apply {
            put("exportedAt", Instant.now().toString())
            put("app", "Digital Wellbeing Guardian")
            put("trackedApps", JSONArray().apply {
                trackedApps.forEach { tracked ->
                    put(JSONObject().apply {
                        put("packageName", tracked.packageName)
                        put("displayName", tracked.displayName)
                        put("isTracked", tracked.isTracked)
                    })
                }
            })
            put("sessions", JSONArray().apply {
                sessions.forEach { s ->
                    put(JSONObject().apply {
                        put("id", s.id)
                        put("packageName", s.packageName)
                        put("startTime", s.startTime)
                        put("endTime", s.endTime)
                        put("durationMs", s.durationMs)
                        put("thresholdCrossed", s.thresholdCrossed)
                        put("extensionMs", s.extensionMs)
                        put("interventionAction", s.interventionAction ?: JSONObject.NULL)
                        put("reason", s.reason ?: JSONObject.NULL)
                    })
                }
            })
        }

        val outFile = File(app.cacheDir, "guardian-logs-${System.currentTimeMillis()}.json")
        outFile.writeText(payload.toString(2))

        return FileProvider.getUriForFile(
            app,
            "${app.packageName}.fileprovider",
            outFile
        )
    }
}
