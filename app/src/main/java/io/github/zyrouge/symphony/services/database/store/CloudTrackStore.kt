package io.github.zyrouge.symphony.services.database.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.github.zyrouge.symphony.services.cloud.CloudTrack

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

    @Query("SELECT * FROM cloud_tracks WHERE cloudFileId = :cloudFileId")
    suspend fun getByCloudFileId(cloudFileId: String): CloudTrack?
} 