package io.github.zyrouge.symphony.services.cloud

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.zyrouge.symphony.services.groove.Song
import io.github.zyrouge.symphony.utils.SimplePath
import java.time.LocalDate

@Immutable
@Entity("cloud_tracks")
data class CloudTrack(
    @PrimaryKey
    val id: String,
    // Cloud info
    val cloudFileId: String,
    val cloudPath: String,
    val provider: String,
    val lastModified: Long,
    // Metadata
    val title: String,
    val album: String?,
    val artists: Set<String>,
    val composers: Set<String>,
    val albumArtists: Set<String>,
    val genres: Set<String>,
    val trackNumber: Int?,
    val trackTotal: Int?,
    val discNumber: Int?,
    val discTotal: Int?,
    val date: LocalDate?,
    val year: Int?,
    val duration: Long,
    val bitrate: Long?,
    val samplingRate: Long?,
    val channels: Int?,
    val encoder: String?,
    // File info
    val size: Long,
    // Cache status
    val localPath: String,
    val localPathString: String,
) {
    companion object {
        fun fromMetadata(
            cloudFileId: String,
            cloudPath: String,
            provider: String,
            lastModified: Long,
            mapping: CloudFolderMapping,
            metadata: CloudTrackMetadata
        ): CloudTrack {
            val date = metadata.tags.date?.let {
                try {
                    LocalDate.parse(it)
                } catch (e: Exception) {
                    null
                }
            }

            val relativePath = cloudPath.removePrefix(mapping.cloudPath).trimStart('/')
            val localPath = "${mapping.localPath}/$relativePath"
            val localPathString = SimplePath(localPath).pathString.replaceFirst("/", ":")

            return CloudTrack(
                id = generateId(cloudFileId),
                cloudFileId = cloudFileId,
                cloudPath = cloudPath,
                provider = provider,
                lastModified = lastModified,
                title = metadata.tags.title ?: "",
                album = metadata.tags.album,
                artists = metadata.tags.artists.toSet(),
                composers = metadata.tags.composers.toSet(),
                albumArtists = metadata.tags.albumArtists.toSet(),
                genres = metadata.tags.genres.toSet(),
                trackNumber = metadata.tags.trackNo,
                trackTotal = metadata.tags.trackOf,
                discNumber = metadata.tags.diskNo,
                discTotal = metadata.tags.diskOf,
                date = date,
                year = metadata.tags.year,
                duration = metadata.tags.duration.toLong() * 1000, // Convert to milliseconds
                bitrate = metadata.tags.bitrate?.toLong(),
                samplingRate = metadata.tags.samplingRate?.toLong(),
                channels = metadata.tags.channels,
                encoder = metadata.tags.encoder,
                size = 0, // Will be updated when file metadata is fetched
                localPath = localPath,
                localPathString = localPathString,
            )
        }

        fun fromCloudFile(
            cloudFileId: String,
            cloudPath: String,
            provider: String,
            lastModified: Long,
            mapping: CloudFolderMapping
        ): CloudTrack {
            val fileName = cloudPath.substringAfterLast('/')
            val title = fileName.substringBeforeLast('.', fileName)

            val relativePath = cloudPath.removePrefix(mapping.cloudPath).trimStart('/')
            val localPath = "${mapping.localPath}/$relativePath"
            val localPathString = SimplePath(localPath).pathString.replaceFirst("/", ":")

            return CloudTrack(
                id = generateId(cloudFileId),
                cloudFileId = cloudFileId,
                cloudPath = cloudPath,
                provider = provider,
                lastModified = lastModified,
                title = title,
                album = null,
                artists = emptySet(),
                composers = emptySet(),
                albumArtists = emptySet(),
                genres = emptySet(),
                trackNumber = null,
                trackTotal = null,
                discNumber = null,
                discTotal = null,
                date = null,
                year = null,
                duration = 0,
                bitrate = null,
                samplingRate = null,
                channels = null,
                encoder = null,
                size = 0,
                localPath = localPath,
                localPathString = localPathString,
            )
        }

        private fun generateId(cloudFileId: String) = cloudFileId.hashCode().toString()
    }
} 