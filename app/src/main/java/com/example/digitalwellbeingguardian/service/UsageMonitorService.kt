package com.example.digitalwellbeingguardian.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.digitalwellbeingguardian.MainActivity
import com.example.digitalwellbeingguardian.R
import com.example.digitalwellbeingguardian.data.AppDatabase
import com.example.digitalwellbeingguardian.data.GuardianRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class UsageMonitorService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var repository: GuardianRepository
    private lateinit var usageStatsManager: UsageStatsManager

    private var pollingJob: Job? = null
    private var screenOn = true
    private var lastPollTime = System.currentTimeMillis() - POLL_MS

    private var activePackage: String? = null
    private var activeStartTime: Long = 0L
    private var activeExtensionMs: Long = 0L
    private var thresholdNotified = false
    private var pendingInterventionAction: String? = null
    private var pendingReason: String? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> screenOn = true
                Intent.ACTION_SCREEN_OFF -> screenOn = false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val db = AppDatabase.get(this)
        repository = GuardianRepository(db.sessionDao(), db.trackedAppDao())
        createChannels()
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_EXTEND_5 -> handleExtend(intent.getStringExtra(EXTRA_PACKAGE))
            ACTION_STOP_NOW -> handleStopNow(intent.getStringExtra(EXTRA_PACKAGE))
            ACTION_ALLOW_ONCE -> handleAllowOnce(intent.getStringExtra(EXTRA_PACKAGE))
            ACTION_MARK_DEFER -> handleMarkDefer(intent.getStringExtra(EXTRA_PACKAGE))
            ACTION_STOP_SERVICE -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        saveLiveStatus(isRunning = true)
        startForeground(ONGOING_NOTIFICATION_ID, ongoingNotification())
        if (pollingJob?.isActive != true) {
            pollingJob = serviceScope.launch {
                repository.ensureDefaults()
                while (isActive) {
                    if (screenOn) pollUsage()
                    delay(POLL_MS)
                }
            }
        }
        return START_STICKY
    }

    private suspend fun pollUsage() {
        val now = System.currentTimeMillis()
        val trackedPackages = repository.trackedPackageNames().toSet()
        if (trackedPackages.isEmpty()) {
            lastPollTime = now
            return
        }

        val events = usageStatsManager.queryEvents(lastPollTime, now)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            if (!trackedPackages.contains(pkg)) continue
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    if (activePackage != null && activePackage != pkg) {
                        completeSession(event.timeStamp)
                    }
                    activePackage = pkg
                    activeStartTime = event.timeStamp
                    activeExtensionMs = 0L
                    thresholdNotified = false
                    pendingInterventionAction = null
                    pendingReason = null

                    if (shouldPromptIntentGate(pkg, event.timeStamp)) {
                        showIntentGateNotification(pkg)
                    }
                }

                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    if (activePackage == pkg) {
                        completeSession(event.timeStamp)
                    }
                }
            }
        }

        val pkg = activePackage
        if (pkg != null) {
            val duration = now - activeStartTime
            val threshold = BASE_THRESHOLD_MS + activeExtensionMs
            if (duration >= threshold && !thresholdNotified) {
                thresholdNotified = true
                showInterventionNotification(pkg, duration)
            }
        }

        lastPollTime = now
        saveLiveStatus(
            isRunning = true,
            activePackage = activePackage,
            activeStartTime = activeStartTime,
            lastPollTime = lastPollTime
        )
        getSystemService(NotificationManager::class.java).notify(ONGOING_NOTIFICATION_ID, ongoingNotification())
    }

    private suspend fun completeSession(endTime: Long) {
        val pkg = activePackage ?: return
        repository.saveSession(
            packageName = pkg,
            startTime = activeStartTime,
            endTime = endTime,
            extensionMs = activeExtensionMs,
            interventionAction = pendingInterventionAction,
            reason = pendingReason
        )
        activePackage = null
        activeStartTime = 0L
        activeExtensionMs = 0L
        thresholdNotified = false
        pendingInterventionAction = null
        pendingReason = null
    }

    private fun handleExtend(targetPackage: String?) {
        if (targetPackage != null && targetPackage == activePackage) {
            activeExtensionMs += EXTENSION_MS
            thresholdNotified = false
            pendingInterventionAction = "EXTEND_5_MIN"
            pendingReason = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_REASON, null)
            getSystemService(NotificationManager::class.java).cancel(INTERVENTION_NOTIFICATION_ID)
        }
    }

    private fun handleStopNow(targetPackage: String?) {
        if (targetPackage != null && targetPackage == activePackage) {
            pendingInterventionAction = "STOP_NOW"
            serviceScope.launch {
                completeSession(System.currentTimeMillis())
            }
            getSystemService(NotificationManager::class.java).cancel(INTERVENTION_NOTIFICATION_ID)
        }
    }

    private fun shouldPromptIntentGate(packageName: String, nowMs: Long): Boolean {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val target = prefs.getString(KEY_INTENT_TARGET_PACKAGE, "com.twitter.android")
        if (packageName != target) return false

        val allowUntil = prefs.getLong(KEY_ALLOW_UNTIL_MS, 0L)
        if (allowUntil >= nowMs) return false

        val graceMinutes = prefs.getInt(KEY_WINDOW_GRACE_MIN, 15).coerceAtLeast(0)
        val windowsCsv = prefs.getString(KEY_CHECK_WINDOWS_CSV, "11:30,16:30,21:00").orEmpty()
        if (isWithinAllowedWindow(nowMs, windowsCsv, graceMinutes)) return false

        incrementDailyCounter(KEY_DAILY_INTENT_PROMPTS_PREFIX)
        return true
    }

    private fun isWithinAllowedWindow(nowMs: Long, windowsCsv: String, graceMinutes: Int): Boolean {
        val now = LocalTime.now()
        val formatter = DateTimeFormatter.ofPattern("H:mm")
        val grace = graceMinutes * 60L
        return windowsCsv.split(',')
            .mapNotNull { raw ->
                runCatching { LocalTime.parse(raw.trim(), formatter) }.getOrNull()
            }
            .any { target ->
                val diff = kotlin.math.abs(java.time.Duration.between(target, now).seconds)
                diff <= grace
            }
    }

    private fun incrementDailyCounter(prefix: String) {
        val key = prefix + java.time.LocalDate.now().toString()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val current = prefs.getInt(key, 0)
        prefs.edit().putInt(key, current + 1).apply()
    }

    private fun handleAllowOnce(targetPackage: String?) {
        if (targetPackage == null || targetPackage != activePackage) return
        val until = System.currentTimeMillis() + 5 * 60 * 1000L
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putLong(KEY_ALLOW_UNTIL_MS, until).apply()
        pendingInterventionAction = "ALLOW_ONCE"
        getSystemService(NotificationManager::class.java).cancel(INTENT_NOTIFICATION_ID)
    }

    private fun handleMarkDefer(targetPackage: String?) {
        if (targetPackage == null || targetPackage != activePackage) return
        pendingInterventionAction = "DEFERRED_URGE"
        incrementDailyCounter(KEY_DAILY_DEFERRED_PREFIX)
        getSystemService(NotificationManager::class.java).cancel(INTENT_NOTIFICATION_ID)
    }

    private fun showIntentGateNotification(packageName: String) {
        val allowIntent = PendingIntent.getService(
            this,
            203,
            Intent(this, UsageMonitorService::class.java).apply {
                action = ACTION_ALLOW_ONCE
                putExtra(EXTRA_PACKAGE, packageName)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val deferIntent = PendingIntent.getService(
            this,
            204,
            Intent(this, UsageMonitorService::class.java).apply {
                action = ACTION_MARK_DEFER
                putExtra(EXTRA_PACKAGE, packageName)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_INTERVENTION)
            .setSmallIcon(R.drawable.ic_stat_guardian)
            .setContentTitle("Intent check")
            .setContentText("Outside your check windows for $packageName")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "Defer", deferIntent)
            .addAction(0, "Allow 5 min", allowIntent)
            .setAutoCancel(true)
            .build()

        getSystemService(NotificationManager::class.java)
            .notify(INTENT_NOTIFICATION_ID, notification)
    }

    private fun ongoingNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            101,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val text = if (!screenOn) {
            "Monitoring paused while screen is off"
        } else {
            activePackage?.let { "Tracking session: $it" } ?: "Monitoring tracked apps every 15s"
        }

        return NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_guardian)
            .setContentTitle("Digital Wellbeing Guardian")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()
    }

    private fun showInterventionNotification(packageName: String, durationMs: Long) {
        val stopIntent = PendingIntent.getService(
            this,
            201,
            Intent(this, UsageMonitorService::class.java).apply {
                action = ACTION_STOP_NOW
                putExtra(EXTRA_PACKAGE, packageName)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val extendIntent = PendingIntent.getService(
            this,
            202,
            Intent(this, UsageMonitorService::class.java).apply {
                action = ACTION_EXTEND_5
                putExtra(EXTRA_PACKAGE, packageName)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val mins = durationMs / 60000
        val notification = NotificationCompat.Builder(this, CHANNEL_INTERVENTION)
            .setSmallIcon(R.drawable.ic_stat_guardian)
            .setContentTitle("Runaway session detected")
            .setContentText("$packageName active for $mins min")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "Stop now", stopIntent)
            .addAction(0, "Extend 5 min", extendIntent)
            .setAutoCancel(true)
            .build()

        getSystemService(NotificationManager::class.java)
            .notify(INTERVENTION_NOTIFICATION_ID, notification)
    }

    private fun saveLiveStatus(
        isRunning: Boolean,
        activePackage: String? = this.activePackage,
        activeStartTime: Long = this.activeStartTime,
        lastPollTime: Long = this.lastPollTime
    ) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_SERVICE_RUNNING, isRunning)
            .putString(KEY_ACTIVE_PACKAGE, activePackage)
            .putLong(KEY_ACTIVE_START_MS, activeStartTime)
            .putLong(KEY_LAST_POLL_MS, lastPollTime)
            .apply()
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        val ongoing = NotificationChannel(
            CHANNEL_ONGOING,
            "Monitoring",
            NotificationManager.IMPORTANCE_LOW
        )

        val intervention = NotificationChannel(
            CHANNEL_INTERVENTION,
            "Interventions",
            NotificationManager.IMPORTANCE_HIGH
        )

        manager.createNotificationChannel(ongoing)
        manager.createNotificationChannel(intervention)
    }

    override fun onDestroy() {
        super.onDestroy()
        saveLiveStatus(isRunning = false, activePackage = null, activeStartTime = 0L)
        unregisterReceiver(screenReceiver)
        pollingJob?.cancel()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val POLL_MS = 15_000L
        private const val BASE_THRESHOLD_MS = 20 * 60 * 1000L
        private const val EXTENSION_MS = 5 * 60 * 1000L

        private const val CHANNEL_ONGOING = "guardian_ongoing"
        private const val CHANNEL_INTERVENTION = "guardian_intervention"
        private const val ONGOING_NOTIFICATION_ID = 3001
        private const val INTERVENTION_NOTIFICATION_ID = 3002
        private const val INTENT_NOTIFICATION_ID = 3003

        const val ACTION_STOP_NOW = "guardian.action.STOP_NOW"
        const val ACTION_EXTEND_5 = "guardian.action.EXTEND_5"
        const val ACTION_ALLOW_ONCE = "guardian.action.ALLOW_ONCE"
        const val ACTION_MARK_DEFER = "guardian.action.MARK_DEFER"
        const val ACTION_STOP_SERVICE = "guardian.action.STOP_SERVICE"
        const val EXTRA_PACKAGE = "extra.package"

        const val PREFS = "guardian_prefs"
        const val KEY_REASON = "extension_reason"
        const val KEY_SERVICE_RUNNING = "service_running"
        const val KEY_ACTIVE_PACKAGE = "active_package"
        const val KEY_ACTIVE_START_MS = "active_start_ms"
        const val KEY_LAST_POLL_MS = "last_poll_ms"
        const val KEY_INTENT_TARGET_PACKAGE = "intent_target_package"
        const val KEY_CHECK_WINDOWS_CSV = "check_windows_csv"
        const val KEY_WINDOW_GRACE_MIN = "window_grace_minutes"
        const val KEY_ALLOW_UNTIL_MS = "allow_until_ms"
        const val KEY_DAILY_DEFERRED_PREFIX = "daily_deferred_"
        const val KEY_DAILY_INTENT_PROMPTS_PREFIX = "daily_intent_prompts_"

        fun start(context: Context) {
            val intent = Intent(context, UsageMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, UsageMonitorService::class.java).apply {
                    action = ACTION_STOP_SERVICE
                }
            )
        }
    }
}
