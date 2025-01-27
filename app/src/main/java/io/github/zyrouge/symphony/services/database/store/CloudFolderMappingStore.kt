package io.github.zyrouge.symphony.services.database.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.zyrouge.symphony.services.groove.CloudFolderMapping

@Dao
interface CloudFolderMappingStore {
    @Query("SELECT * FROM cloud_folder_mappings")
    suspend fun getAll(): List<CloudFolderMapping>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(mapping: CloudFolderMapping)

    @Query("DELETE FROM cloud_folder_mappings WHERE id = :id")
    suspend fun delete(id: String)
} 