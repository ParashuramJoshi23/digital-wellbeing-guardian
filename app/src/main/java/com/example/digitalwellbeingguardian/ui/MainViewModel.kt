package com.example.digitalwellbeingguardian.ui

import android.app.Application
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
            DailySummary(0, 0, 0)
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
}
