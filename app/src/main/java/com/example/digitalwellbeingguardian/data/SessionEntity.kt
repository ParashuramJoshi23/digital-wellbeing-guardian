package com.example.digitalwellbeingguardian.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val startTime: Long,
    val endTime: Long,
    val durationMs: Long,
    val thresholdCrossed: Boolean,
    val extensionMs: Long = 0,
    val interventionAction: String? = null,
    val reason: String? = null
)
