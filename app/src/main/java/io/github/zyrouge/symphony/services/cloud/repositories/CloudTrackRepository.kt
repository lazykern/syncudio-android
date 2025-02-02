package io.github.zyrouge.symphony.services.cloud.repositories

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import io.github.zyrouge.symphony.Symphony
import io.github.zyrouge.symphony.services.cloud.CloudTrack
import io.github.zyrouge.symphony.services.cloud.CloudTrackMetadata
import io.github.zyrouge.symphony.services.groove.Groove
import io.github.zyrouge.symphony.services.groove.MediaExposer
import io.github.zyrouge.symphony.services.groove.Song
import io.github.zyrouge.symphony.utils.DocumentFileX
import io.github.zyrouge.symphony.utils.Logger
import io.github.zyrouge.symphony.utils.SimplePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgress = _downloadProgress.asStateFlow()

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

    suspend fun scanAndIntegrateCloudTracks(): Result<List<CloudTrack>> = withContext(Dispatchers.IO) {
        try {
            Logger.debug(TAG, "Starting cloud tracks integration")
            startUpdate()

            // 1. Scan all mappings for tracks
            val scannedTracksResult = symphony.cloud.mapping.scanAllMappingsForAudioTracks()
            if (scannedTracksResult.isFailure) {
                return@withContext Result.failure(
                    scannedTracksResult.exceptionOrNull() ?: Exception("Failed to scan mappings")
                )
            }
            val scannedTracks = scannedTracksResult.getOrNull()!!
            Logger.debug(TAG, "Found ${scannedTracks.size} tracks from direct scan")

            // 2. Read metadata tracks
            val metadataTracksResult = readCloudMetadataTracks()
            if (metadataTracksResult.isFailure) {
                return@withContext Result.failure(
                    metadataTracksResult.exceptionOrNull() ?: Exception("Failed to read metadata")
                )
            }
            val metadataTracks = metadataTracksResult.getOrNull()!!
            Logger.debug(TAG, "Found ${metadataTracks.size} tracks from metadata")

            // 3. Create lookup maps
            val scannedTracksMap = scannedTracks.associateBy { it.cloudFileId }
            val metadataTracksMap = metadataTracks.associateBy { it.cloudFileId }

            // 4. Integrate tracks
            val integratedTracks = mutableListOf<CloudTrack>()

            // Process tracks found in both sources
            val commonIds = scannedTracksMap.keys.intersect(metadataTracksMap.keys)
            Logger.debug(TAG, "Found ${commonIds.size} tracks in both sources")
            
            commonIds.forEach { cloudFileId ->
                val scannedTrack = scannedTracksMap[cloudFileId]!!
                val metadataTrack = metadataTracksMap[cloudFileId]!!
                // Use metadata for rich info, but keep scan info for file status
                integratedTracks.add(metadataTrack.copy(
                    lastModified = scannedTrack.lastModified,
                    size = scannedTrack.size
                ))
            }

            // Process tracks only in scan
            val scanOnlyIds = scannedTracksMap.keys - metadataTracksMap.keys
            Logger.debug(TAG, "Found ${scanOnlyIds.size} tracks only in scan")
            scanOnlyIds.forEach { cloudFileId ->
                val track = scannedTracksMap[cloudFileId]!!
                // TODO: Mark these tracks as needing metadata update
                integratedTracks.add(track)
            }

            // Process tracks only in metadata
            val metadataOnlyIds = metadataTracksMap.keys - scannedTracksMap.keys
            Logger.debug(TAG, "Found ${metadataOnlyIds.size} tracks only in metadata")
            metadataOnlyIds.forEach { cloudFileId ->
                val track = metadataTracksMap[cloudFileId]!!
                // TODO: Mark these tracks as needing verification
                integratedTracks.add(track)
            }

            Logger.debug(TAG, "Integrated total of ${integratedTracks.size} tracks")

            // 5. Update database
            clear() // Clear existing tracks
            insert(*integratedTracks.toTypedArray())

            // 6. Integrate with songs
            symphony.groove.song.integrateCloudTracks(integratedTracks)
            
            endUpdate()
            Result.success(integratedTracks)
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to integrate cloud tracks", e)
            endUpdate()
            Result.failure(e)
        }
    }

    suspend fun downloadTrack(cloudFileId: String) = withContext(Dispatchers.IO) {
        try {
            Logger.debug(TAG, "Starting download for track $cloudFileId")
            val track = getByCloudFileId(cloudFileId) ?: return@withContext Result.failure(
                Exception("Track not found: $cloudFileId")
            )

            // Get the mapping for this track
            val mappingId = symphony.cloud.mapping.all.value.find { mappingId ->
                val mapping = symphony.cloud.mapping.get(mappingId)
                track.cloudPath.startsWith(mapping?.cloudPath ?: "")
            } ?: return@withContext Result.failure(Exception("No mapping found for track"))

            val mapping = symphony.cloud.mapping.get(mappingId)!!
            if (mapping.treeUri == null) {
                return@withContext Result.failure(Exception("No tree URI available for mapping"))
            }

            // Get the document file for the root folder
            val rootFolder = DocumentFile.fromTreeUri(symphony.applicationContext, mapping.treeUri)
                ?: return@withContext Result.failure(Exception("Could not access folder"))

            // Create parent directories
            val relativePath = track.localPath.removePrefix(mapping.localPath).trim('/')
            val pathParts = relativePath.split('/')
            var currentFolder = rootFolder

            // Create all parent directories
            for (i in 0 until pathParts.size - 1) {
                val folderName = pathParts[i]
                currentFolder = currentFolder.findFile(folderName)
                    ?: currentFolder.createDirectory(folderName)
                    ?: return@withContext Result.failure(Exception("Failed to create directory: $folderName"))
            }

            // Create or get the file
            val fileName = pathParts.last()
            val file = currentFolder.findFile(fileName) ?: currentFolder.createFile(
                "audio/${fileName.substringAfterLast('.')}",
                fileName
            ) ?: return@withContext Result.failure(Exception("Failed to create file: $fileName"))

            // Download the file
            symphony.applicationContext.contentResolver.openOutputStream(file.uri)?.use { outputStream ->
                symphony.dropbox.downloadFile(
                    dropboxPath = track.cloudPath,
                    outputStream = outputStream,
                    progressCallback = { processed, total -> 
                        _downloadProgress.update { current ->
                            current + (cloudFileId to (processed.toFloat() / total))
                        }
                    }
                ).onSuccess {
                    Logger.debug(TAG, "Download completed for track $cloudFileId")
                    _downloadProgress.update { it - cloudFileId }
                    
                    // Create a new Song with metadata from the downloaded file
                    val documentFile = DocumentFileX.fromSingleUri(symphony.applicationContext, file.uri)
                    if (documentFile != null) {
                        val path = SimplePath(track.localPathString)
                        val parseOptions = Song.ParseOptions.create(symphony)
                        val song = try {
                            Song.parse(path, documentFile, parseOptions)
                        } catch (e: Exception) {
                            Logger.error(TAG, "Failed to parse downloaded file metadata", e)
                            null
                        }

                        if (song != null) {
                            // Update the song with cloud information
                            val updatedSong = song.copy(
                                cloudFileId = track.cloudFileId,
                                cloudPath = track.cloudPath,
                                provider = track.provider
                            )

                            // Remove existing song from cache if it exists
                            symphony.database.songCache.getByPath(track.localPathString)?.let { existingSong ->
                                symphony.database.songCache.delete(existingSong.id)
                                // Remove from explorer
                                symphony.groove.song.explorer.removeChildFile(SimplePath(existingSong.path))
                            }

                            // Update the database
                            symphony.database.songCache.insert(updatedSong)
                            // Notify the song repository
                            symphony.groove.song.onSong(updatedSong)
                            Logger.debug(TAG, "Updated song metadata: ${updatedSong.title}")
                        }
                    }

                    // Reset state before scanning
                    symphony.groove.exposer.reset()  // Reset media exposer state
                    symphony.groove.song.reset()     // Reset song repository state
                    
                    // Trigger a full rescan to pick up the downloaded file
                    symphony.groove.fetch(Groove.FetchOptions())
                    Logger.debug(TAG, "File downloaded and media scan triggered: ${track.localPath}")
                    return@withContext Result.success(Unit)
                }.onFailure { error ->
                    Logger.error(TAG, "Download failed for track $cloudFileId", error)
                    _downloadProgress.update { it - cloudFileId }
                    file.delete() // Clean up partial download
                    return@withContext Result.failure(error)
                }
            } ?: return@withContext Result.failure(Exception("Could not open output stream"))

            return@withContext Result.failure(Exception("Unknown error occurred"))
        } catch (e: Exception) {
            Logger.error(TAG, "Unexpected error downloading track $cloudFileId", e)
            _downloadProgress.update { it - cloudFileId }
            return@withContext Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "CloudTrackRepository"
    }
}
