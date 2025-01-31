package io.github.zyrouge.symphony.services.database.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.github.zyrouge.symphony.services.cloud.CloudTrack
import io.github.zyrouge.symphony.services.cloud.SyncStatus

@Dao
interface CloudTrackStore {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vararg track: CloudTrack): List<Long>

    @Update
    suspend fun update(vararg track: CloudTrack): Int

    @Query("DELETE FROM cloud_tracks WHERE id = :trackId")
    suspend fun delete(trackId: String): Int

    @Query("DELETE FROM cloud_tracks WHERE id IN (:trackIds)")
    suspend fun delete(trackIds: Collection<String>): Int

    @Query("DELETE FROM cloud_tracks")
    suspend fun clear(): Int

    @Query("SELECT * FROM cloud_tracks")
    suspend fun getAll(): List<CloudTrack>

    @Query("SELECT * FROM cloud_tracks WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CloudTrack?

    @Query("SELECT * FROM cloud_tracks WHERE cloudFileId = :cloudFileId")
    suspend fun getByCloudFileId(cloudFileId: String): CloudTrack?

    @Query("SELECT * FROM cloud_tracks WHERE cloudPath = :cloudPath LIMIT 1")
    suspend fun getByCloudPath(cloudPath: String): CloudTrack?

    @Query("SELECT * FROM cloud_tracks WHERE syncStatus = :status")
    suspend fun getByStatus(status: SyncStatus): List<CloudTrack>

    @Query("SELECT * FROM cloud_tracks WHERE syncStatus IN (:statuses)")
    suspend fun getByStatuses(statuses: List<SyncStatus>): List<CloudTrack>

    @Query("SELECT * FROM cloud_tracks WHERE isDownloaded = 1")
    suspend fun getDownloaded(): List<CloudTrack>
} 