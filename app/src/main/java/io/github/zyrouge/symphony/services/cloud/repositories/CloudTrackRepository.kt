package io.github.zyrouge.symphony.services.cloud.repositories

import android.net.Uri
import io.github.zyrouge.symphony.Symphony
import io.github.zyrouge.symphony.services.cloud.CloudTrack
import io.github.zyrouge.symphony.services.cloud.CloudTrackMetadata
import io.github.zyrouge.symphony.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

class CloudTrackRepository(private val symphony: Symphony) {
    private val _tracks = MutableStateFlow<List<CloudTrack>>(emptyList())
    val tracks = _tracks.asStateFlow()

    private val _isUpdating = MutableStateFlow(false)
    val isUpdating = _isUpdating.asStateFlow()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private fun parseTimestamp(timestamp: String): Long {
        return try {
            // Try parsing as ISO-8601 timestamp
            Instant.parse(timestamp).toEpochMilli()
        } catch (e: DateTimeParseException) {
            try {
                // Fallback to RFC-3339 format
                val formatter = DateTimeFormatter.RFC_1123_DATE_TIME
                formatter.parse(timestamp, Instant::from).toEpochMilli()
            } catch (e: DateTimeParseException) {
                Logger.warn(TAG, "Failed to parse timestamp: $timestamp")
                System.currentTimeMillis() // Use current time as fallback
            }
        }
    }

    suspend fun fetch() = withContext(Dispatchers.IO) {
        _tracks.value = symphony.database.cloudTrackCache.getAll()
    }

    suspend fun insert(vararg tracks: CloudTrack) = withContext(Dispatchers.IO) {
        symphony.database.cloudTrackCache.insert(*tracks)
        fetch()
    }

    suspend fun update(vararg tracks: CloudTrack) = withContext(Dispatchers.IO) {
        symphony.database.cloudTrackCache.update(*tracks)
        fetch()
    }

    suspend fun delete(trackId: String) = withContext(Dispatchers.IO) {
        symphony.database.cloudTrackCache.delete(trackId)
        fetch()
    }

    suspend fun delete(trackIds: Collection<String>) = withContext(Dispatchers.IO) {
        symphony.database.cloudTrackCache.delete(trackIds)
        fetch()
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        symphony.database.cloudTrackCache.clear()
        fetch()
    }

    suspend fun getByCloudFileId(cloudFileId: String): CloudTrack? = withContext(Dispatchers.IO) {
        symphony.database.cloudTrackCache.getByCloudFileId(cloudFileId)
    }

    suspend fun getDownloaded(): List<CloudTrack> = withContext(Dispatchers.IO) {
        symphony.database.cloudTrackCache.getDownloaded()
    }

    suspend fun updateDownloadStatus(
        track: CloudTrack,
        isDownloaded: Boolean,
        localPath: String? = null,
        localUri: Uri? = null,
    ) = withContext(Dispatchers.IO) {
        update(
            track.copy(
                isDownloaded = isDownloaded,
                localPath = localPath,
                localUri = localUri,
            )
        )
    }

    suspend fun updateFromMetadata(
        cloudFileId: String,
        cloudPath: String,
        provider: String,
        lastModified: Long,
        metadata: CloudTrackMetadata,
    ) = withContext(Dispatchers.IO) {
        val track = CloudTrack.fromMetadata(
            cloudFileId = cloudFileId,
            cloudPath = cloudPath,
            provider = provider,
            lastModified = lastModified,
            metadata = metadata,
        )
        insert(track)
        track
    }

    suspend fun startUpdate() {
        _isUpdating.value = true
    }

    suspend fun endUpdate() {
        _isUpdating.value = false
    }

    suspend fun parseMetadataJson(content: String): Result<List<CloudTrackMetadata>> = withContext(Dispatchers.IO) {
        try {
            val jsonObject = json.parseToJsonElement(content).jsonObject
            val tracks = jsonObject["tracks"]?.jsonArray
                ?.map { trackElement ->
                    val track = trackElement.jsonObject
                    val tags = track["tags"]?.jsonObject

                    CloudTrackMetadata(
                        blake3Hash = track["blake3_hash"]?.toString() ?: "",
                        cloudFileId = track["cloud_file_id"]?.toString() ?: "",
                        cloudPath = track["cloud_path"]?.toString() ?: "",
                        relativePath = track["relative_path"]?.toString() ?: "",
                        lastModified = track["last_modified"]?.toString() ?: "",
                        lastSync = track["last_sync"]?.toString() ?: "",
                        provider = track["provider"]?.toString() ?: "",
                        cloudFolderId = track["cloud_folder_id"]?.toString() ?: "",
                        tags = CloudTrackMetadata.Tags(
                            title = tags?.get("title")?.toString(),
                            album = tags?.get("album")?.toString(),
                            artists = json.decodeFromJsonElement(tags?.get("artists") ?: JsonObject(emptyMap())),
                            composers = json.decodeFromJsonElement(tags?.get("composers") ?: JsonObject(emptyMap())),
                            albumArtists = json.decodeFromJsonElement(tags?.get("album_artists") ?: JsonObject(emptyMap())),
                            genres = json.decodeFromJsonElement(tags?.get("genres") ?: JsonObject(emptyMap())),
                            date = tags?.get("date")?.toString(),
                            year = tags?.get("year")?.toString()?.toIntOrNull(),
                            duration = tags?.get("duration")?.toString()?.toIntOrNull() ?: 0,
                            trackNo = tags?.get("track_no")?.toString()?.toIntOrNull(),
                            trackOf = tags?.get("track_of")?.toString()?.toIntOrNull(),
                            diskNo = tags?.get("disk_no")?.toString()?.toIntOrNull(),
                            diskOf = tags?.get("disk_of")?.toString()?.toIntOrNull(),
                            bitrate = tags?.get("bitrate")?.toString()?.toIntOrNull(),
                            samplingRate = tags?.get("sampling_rate")?.toString()?.toIntOrNull(),
                            channels = tags?.get("channels")?.toString()?.toIntOrNull(),
                            encoder = tags?.get("encoder")?.toString(),
                        )
                    )
                } ?: emptyList()

            Result.success(tracks)
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to parse metadata JSON", e)
            Result.failure(e)
        }
    }

    suspend fun syncMetadata() = withContext(Dispatchers.IO) {
        try {
            startUpdate()
            val content = symphony.dropbox.downloadMetadataFile()
            if (content == null) {
                Logger.debug(TAG, "No metadata file found")
                return@withContext Result.failure(Exception("No metadata file found"))
            }

            val metadataResult = parseMetadataJson(content)
            if (metadataResult.isFailure) {
                return@withContext metadataResult
            }

            val tracks = metadataResult.getOrNull() ?: emptyList()
            Logger.debug(TAG, "Parsed ${tracks.size} tracks from metadata")

            // Clear existing tracks and insert new ones
            clear()
            tracks.forEach { metadata ->
                val lastModified = parseTimestamp(metadata.lastModified)
                
                updateFromMetadata(
                    cloudFileId = metadata.cloudFileId,
                    cloudPath = metadata.cloudPath,
                    provider = metadata.provider,
                    lastModified = lastModified,
                    metadata = metadata,
                )
            }

            Result.success(tracks)
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to sync metadata", e)
            Result.failure(e)
        } finally {
            endUpdate()
        }
    }

    companion object {
        private const val TAG = "CloudTrackRepository"
    }
} 