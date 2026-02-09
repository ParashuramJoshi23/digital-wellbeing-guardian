package com.example.digitalwellbeingguardian.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackedAppDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(apps: List<TrackedAppEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(app: TrackedAppEntity)

    @Query("UPDATE tracked_apps SET isTracked = :tracked WHERE packageName = :packageName")
    suspend fun setTracked(packageName: String, tracked: Boolean)

    @Query("SELECT * FROM tracked_apps ORDER BY displayName")
    fun observeAll(): Flow<List<TrackedAppEntity>>

    @Query("SELECT packageName FROM tracked_apps WHERE isTracked = 1")
    suspend fun activePackages(): List<String>
}
