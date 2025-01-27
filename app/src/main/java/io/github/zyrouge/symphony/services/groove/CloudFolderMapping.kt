package io.github.zyrouge.symphony.services.groove

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.zyrouge.symphony.Symphony

@Immutable
@Entity("cloud_folder_mappings")
data class CloudFolderMapping(
    @PrimaryKey
    val id: String,
    val localPath: String,
    val cloudPath: String,
    val provider: String,
    val lastSync: Long,
) {
    companion object {
        fun generateId(localPath: String, cloudPath: String) = "$localPath:$cloudPath".hashCode().toString()
    }
}