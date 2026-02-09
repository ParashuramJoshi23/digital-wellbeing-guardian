package com.example.digitalwellbeingguardian.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId

private const val RUNAWAY_THRESHOLD_MS = 20 * 60 * 1000L
private const val NEAR_RUNAWAY_LOWER_MS = 10 * 60 * 1000L
private const val MICRO_CHECK_MS = 60 * 1000L
private const val BURST_GAP_MS = 5 * 60 * 1000L

data class DailySummary(
    val totalTrackedMs: Long,
    val longestSessionMs: Long,
    val sessionsOverThreshold: Int,
    val nearRunawaySessions: Int,
    val microCheckScore: Int
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

    suspend fun allSessions(): List<SessionEntity> = sessionDao.allSessions()

    suspend fun allTrackedApps(): List<TrackedAppEntity> = trackedAppDao.allApps()

    fun observeSummaryFor(date: LocalDate): Flow<DailySummary> {
        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return sessionDao.sessionsForDay(start, end).map { sessions ->
            val microChecks = sessions.count { it.durationMs in 1..MICRO_CHECK_MS }

            val sorted = sessions.sortedBy { it.startTime }
            val burstReentries = sorted.zipWithNext().count { (a, b) ->
                b.packageName == a.packageName && (b.startTime - a.endTime) in 0..BURST_GAP_MS
            }

            DailySummary(
                totalTrackedMs = sessions.sumOf { it.durationMs },
                longestSessionMs = sessions.maxOfOrNull { it.durationMs } ?: 0L,
                sessionsOverThreshold = sessions.count { it.durationMs > RUNAWAY_THRESHOLD_MS },
                nearRunawaySessions = sessions.count { it.durationMs in NEAR_RUNAWAY_LOWER_MS until RUNAWAY_THRESHOLD_MS },
                microCheckScore = microChecks + burstReentries
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
