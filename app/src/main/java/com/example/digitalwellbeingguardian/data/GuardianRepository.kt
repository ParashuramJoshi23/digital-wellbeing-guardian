package com.example.digitalwellbeingguardian.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId

private const val RUNAWAY_THRESHOLD_MS = 20 * 60 * 1000L

data class DailySummary(
    val totalTrackedMs: Long,
    val longestSessionMs: Long,
    val sessionsOverThreshold: Int
)

class GuardianRepository(
    private val sessionDao: SessionDao,
    private val trackedAppDao: TrackedAppDao
) {
    val trackedApps: Flow<List<TrackedAppEntity>> = trackedAppDao.observeAll()

    suspend fun ensureDefaults() {
        trackedAppDao.insertAll(
            listOf(
                TrackedAppEntity("com.google.android.youtube", "YouTube"),
                TrackedAppEntity("com.supercell.clashofclans", "Clash of Clans"),
                TrackedAppEntity("com.twitter.android", "X"),
                TrackedAppEntity("com.whatsapp", "WhatsApp")
            )
        )
    }

    suspend fun saveSession(
        packageName: String,
        startTime: Long,
        endTime: Long,
        extensionMs: Long,
        interventionAction: String?,
        reason: String?
    ) {
        val duration = (endTime - startTime).coerceAtLeast(0)
        sessionDao.insert(
            SessionEntity(
                packageName = packageName,
                startTime = startTime,
                endTime = endTime,
                durationMs = duration,
                thresholdCrossed = duration > RUNAWAY_THRESHOLD_MS,
                extensionMs = extensionMs,
                interventionAction = interventionAction,
                reason = reason
            )
        )
    }

    suspend fun toggleTracked(packageName: String, tracked: Boolean) {
        trackedAppDao.setTracked(packageName, tracked)
    }

    suspend fun addTrackedApp(packageName: String, displayName: String) {
        trackedAppDao.upsert(
            TrackedAppEntity(
                packageName = packageName,
                displayName = displayName.ifBlank { packageName },
                isTracked = true
            )
        )
    }

    suspend fun trackedPackageNames(): List<String> = trackedAppDao.activePackages()

    fun observeSummaryFor(date: LocalDate): Flow<DailySummary> {
        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return sessionDao.sessionsForDay(start, end).map { sessions ->
            DailySummary(
                totalTrackedMs = sessions.sumOf { it.durationMs },
                longestSessionMs = sessions.maxOfOrNull { it.durationMs } ?: 0L,
                sessionsOverThreshold = sessions.count { it.durationMs > RUNAWAY_THRESHOLD_MS }
            )
        }
    }

    fun observeSessionsFor(date: LocalDate): Flow<List<SessionEntity>> {
        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return sessionDao.sessionsForDay(start, end)
    }
}
