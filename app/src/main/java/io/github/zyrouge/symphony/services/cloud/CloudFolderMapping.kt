package io.github.zyrouge.symphony.services.cloud

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.zyrouge.symphony.Symphony
import io.github.zyrouge.symphony.utils.SimplePath

@Immutable
@Entity("cloud_folder_mappings")
data class CloudFolderMapping(
    @PrimaryKey
    val id: String,
    val localPath: String,
    val cloudPath: String,
    val cloudFolderId: String,
    val provider: String,
) {
    val localSimplePath get() = SimplePath(localPath)
    val cloudSimplePath get() = SimplePath(cloudPath)

    fun getLocalPathString() = localSimplePath.pathString
    fun getCloudPathString() = cloudSimplePath.pathString

    companion object {
        fun generateId(localPath: String, cloudPath: String) = "${localPath}:${cloudPath}".hashCode().toString()
    }
} 