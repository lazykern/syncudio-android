package io.github.zyrouge.symphony.services.cloud

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.zyrouge.symphony.services.groove.Song
import java.time.LocalDate
import java.time.OffsetDateTime

enum class SyncStatus {
    /**
     * Track exists only locally
     */
    LOCAL_ONLY,
    
    /**
     * Track exists only in cloud
     */
    CLOUD_ONLY,
    
    /**
     * Track exists in both places and is in sync
     */
    SYNCED,
    
    /**
     * Track exists in both places but has conflicts
     */
    CONFLICT,
    
    /**
     * Track is currently being synced
     */
    SYNCING
}

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
    val lastSync: Long,
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
    val isDownloaded: Boolean,
    val localPath: String?,
    val localUri: Uri?,
    val needsMetadataUpdate: Boolean,
    val syncStatus: SyncStatus,
) {
    companion object {
        fun generateId(cloudFileId: String) = cloudFileId.hashCode().toString()

        /**
         * Creates a basic cloud track with minimal information
         */
        fun createBasic(
            cloudFileId: String,
            cloudPath: String,
            provider: String,
            lastModified: Long,
            size: Long,
            syncStatus: SyncStatus = SyncStatus.CLOUD_ONLY,
        ): CloudTrack {
            val fileName = cloudPath.substringAfterLast('/')
            return CloudTrack(
                id = generateId(cloudFileId),
                cloudFileId = cloudFileId,
                cloudPath = cloudPath,
                provider = provider,
                lastModified = lastModified,
                lastSync = System.currentTimeMillis(),
                title = fileName,
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
                size = size,
                isDownloaded = false,
                localPath = null,
                localUri = null,
                needsMetadataUpdate = true,
                syncStatus = syncStatus
            )
        }

        fun fromMetadata(
            cloudFileId: String,
            cloudPath: String,
            provider: String,
            lastModified: Long,
            metadata: CloudTrackMetadata
        ): CloudTrack {
            val date = metadata.tags.date?.let {
                try {
                    // Parse ISO-8601 timestamp and extract the date part
                    OffsetDateTime.parse(it).toLocalDate()
                } catch (e: Exception) {
                    try {
                        // Fallback to just date format
                        LocalDate.parse(it)
                    } catch (e: Exception) {
                        null
                    }
                }
            }

            return CloudTrack(
                id = generateId(cloudFileId),
                cloudFileId = cloudFileId,
                cloudPath = cloudPath,
                provider = provider,
                lastModified = lastModified,
                lastSync = System.currentTimeMillis(),
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
                isDownloaded = false,
                localPath = null,
                localUri = null,
                needsMetadataUpdate = false,
                syncStatus = SyncStatus.CLOUD_ONLY
            )
        }
    }

    fun toSong() = Song(
        id = id,
        title = title,
        album = album,
        artists = artists,
        composers = composers,
        albumArtists = albumArtists,
        genres = genres,
        trackNumber = trackNumber,
        trackTotal = trackTotal,
        discNumber = discNumber,
        discTotal = discTotal,
        date = date,
        year = year,
        duration = duration,
        bitrate = bitrate,
        samplingRate = samplingRate,
        channels = channels,
        encoder = encoder,
        dateModified = lastModified,
        size = size,
        coverFile = null,
        uri = localUri ?: Uri.parse("cloud://$provider/$cloudFileId"),
        path = localPath ?: cloudPath,
        cloudFileId = cloudFileId,
        cloudPath = cloudPath,
        provider = provider,
    )
} 