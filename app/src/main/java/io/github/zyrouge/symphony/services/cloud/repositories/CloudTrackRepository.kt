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
                        cloudFileId = track["cloud_file_id"]?.toString()?.trim('"') ?: "",
                        cloudPath = track["cloud_path"]?.toString()?.trim('"') ?: "",
                        relativePath = track["relative_path"]?.toString()?.trim('"') ?: "",
                        lastModified = track["last_modified"]?.toString()?.trim('"') ?: "",
                        provider = track["provider"]?.toString()?.trim('"') ?: "",
                        cloudFolderId = track["cloud_folder_id"]?.toString()?.trim('"') ?: "",
                        tags = CloudTrackMetadata.Tags(
                            title = tags?.get("title")?.toString()?.trim('"'),
                            album = tags?.get("album")?.toString()?.trim('"'),
                            artists = json.decodeFromJsonElement(tags?.get("artists") ?: JsonObject(emptyMap())),
                            composers = json.decodeFromJsonElement(tags?.get("composers") ?: JsonObject(emptyMap())),
                            albumArtists = json.decodeFromJsonElement(tags?.get("album_artists") ?: JsonObject(emptyMap())),
                            genres = json.decodeFromJsonElement(tags?.get("genres") ?: JsonObject(emptyMap())),
                            date = tags?.get("date")?.toString()?.trim('"'),
                            year = tags?.get("year")?.toString()?.trim('"')?.toIntOrNull(),
                            duration = tags?.get("duration")?.toString()?.trim('"')?.toIntOrNull() ?: 0,
                            trackNo = tags?.get("track_no")?.toString()?.trim('"')?.toIntOrNull(),
                            trackOf = tags?.get("track_of")?.toString()?.trim('"')?.toIntOrNull(),
                            diskNo = tags?.get("disk_no")?.toString()?.trim('"')?.toIntOrNull(),
                            diskOf = tags?.get("disk_of")?.toString()?.trim('"')?.toIntOrNull(),
                            bitrate = tags?.get("bitrate")?.toString()?.trim('"')?.toIntOrNull(),
                            samplingRate = tags?.get("sampling_rate")?.toString()?.trim('"')?.toIntOrNull(),
                            channels = tags?.get("channels")?.toString()?.trim('"')?.toIntOrNull(),
                            encoder = tags?.get("encoder")?.toString()?.trim('"'),
                        )
                    )
                } ?: emptyList()

            Result.success(tracks)
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to parse metadata JSON", e)
            Result.failure(e)
        }
    }

    suspend fun readCloudMetadataTracks(): Result<List<CloudTrack>> = withContext(Dispatchers.IO) {
        try {
            Logger.debug(TAG, "Starting to read cloud metadata tracks")
            
            // 1. Download metadata file
            val content = symphony.dropbox.downloadMetadataFile()
                ?: return@withContext Result.failure(Exception("Failed to download metadata file"))
            Logger.debug(TAG, "Downloaded metadata file")

            // 2. Parse metadata JSON
            val metadataResult = parseMetadataJson(content)
            if (metadataResult.isFailure) {
                return@withContext Result.failure(metadataResult.exceptionOrNull() 
                    ?: Exception("Failed to parse metadata"))
            }
            val metadataTracks = metadataResult.getOrNull()!!
            Logger.debug(TAG, "Parsed ${metadataTracks.size} tracks from metadata")

            // 3. Get all mappings
            val mappingIds = symphony.cloud.mapping.all.value
            if (mappingIds.isEmpty()) {
                Logger.warn(TAG, "No cloud folder mappings found")
                return@withContext Result.success(emptyList())
            }
            val mappings = mappingIds.mapNotNull { id -> symphony.cloud.mapping.get(id) }
            Logger.debug(TAG, "Found ${mappings.size} cloud folder mappings")

            // 4. Convert metadata to tracks with matching mappings
            val tracks = metadataTracks.mapNotNull { metadata ->
                // Find matching mapping based on cloud folder ID
                val mapping = mappings.find { mapping -> 
                    // Normalize paths by removing leading slashes and trimming before comparison
                    Logger.debug(TAG, "Checking mapping: ${mapping.cloudPath} against ${metadata.cloudPath}")
                    val normalizedMetadataPath = metadata.cloudPath.removePrefix("/").trim()
                    val normalizedMappingPath = mapping.cloudPath.removePrefix("/").trim()
                    normalizedMetadataPath.startsWith(normalizedMappingPath)
                } ?: run {
                    Logger.warn(TAG, "No mapping found for track: \"${metadata.cloudPath}\"")
                    return@mapNotNull null
                }

                try {
                    CloudTrack.fromMetadata(
                        cloudFileId = metadata.cloudFileId,
                        cloudPath = metadata.cloudPath,
                        provider = metadata.provider,
                        lastModified = parseTimestamp(metadata.lastModified),
                        mapping = mapping,
                        metadata = metadata
                    ).also {
                        Logger.debug(TAG, "Created track: id=${it.id}, title=${it.title}, local=${it.localPath}, localString=${it.localPathString}")
                    }
                } catch (e: Exception) {
                    Logger.error(TAG, "Failed to create track from metadata: ${metadata.cloudPath}", e)
                    null
                }
            }
            Logger.debug(TAG, "Created ${tracks.size} cloud tracks from metadata")

            Result.success(tracks)
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to read cloud metadata tracks", e)
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "CloudTrackRepository"
    }
} 