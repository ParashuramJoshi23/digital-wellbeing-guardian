package com.example.digitalwellbeingguardian.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tracked_apps")
data class TrackedAppEntity(
    @PrimaryKey val packageName: String,
    val displayName: String,
    val isTracked: Boolean = true
)
