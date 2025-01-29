package io.github.zyrouge.symphony.services.cloud

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.zyrouge.symphony.services.groove.Song
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
    val blake3Hash: String,
    // Cache status
    val isDownloaded: Boolean = false,
    val localPath: String? = null,
    val localUri: Uri? = null,
) {
    companion object {
        fun fromMetadata(
            cloudFileId: String,
            cloudPath: String,
            provider: String,
            lastModified: Long,
            metadata: CloudTrackMetadata
        ): CloudTrack {
            val date = metadata.tags.date?.let {
                try {
                    LocalDate.parse(it)
                } catch (e: Exception) {
                    null
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
                blake3Hash = metadata.blake3Hash,
            )
        }

        private fun generateId(cloudFileId: String) = cloudFileId.hashCode().toString()
    }

    fun toSong(): Song {
        return Song(
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
            size = 0,
            coverFile = null, // Handle artwork separately
            uri = localUri ?: Uri.parse("cloud://$provider/$cloudFileId"),
            path = localPath ?: cloudPath,
            blake3Hash = blake3Hash,
            cloudFileId = cloudFileId,
            cloudPath = cloudPath,
            provider = provider
        )
    }
} 