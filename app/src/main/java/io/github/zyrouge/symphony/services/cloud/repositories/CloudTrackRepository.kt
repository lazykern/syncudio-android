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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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

    private val _isMetadataUpdateQueued = MutableStateFlow(false)
    val isMetadataUpdateQueued = _isMetadataUpdateQueued.asStateFlow()

    private var metadataUpdateDebounceJob: kotlinx.coroutines.Job? = null

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
                        
                        // First check if we already have a song with this path
                        val existingSong = symphony.database.songCache.getByPath(track.localPathString)
                        
                        // Parse the new file
                        val parsedSong = try {
                            Song.parse(path, documentFile, parseOptions)
                        } catch (e: Exception) {
                            Logger.error(TAG, "Failed to parse downloaded file metadata", e)
                            null
                        }

                        if (parsedSong != null) {
                            // Create updated song, preserving the ID if it exists
                            val updatedSong = parsedSong.copy(
                                id = existingSong?.id ?: parsedSong.id,
                                cloudFileId = track.cloudFileId,
                                cloudPath = track.cloudPath,
                                provider = track.provider,
                                uri = documentFile.uri,  // Set the URI to the downloaded file's URI
                                coverFile = parsedSong.coverFile  // Preserve the cover file from parsed song
                            )

                            Logger.debug(TAG, "Updating song with new URI: ${updatedSong.uri} and cover: ${updatedSong.coverFile}")

                            // Update the database and all caches
                            if (existingSong != null) {
                                Logger.debug(TAG, "Updating existing song in database and caches - ID: ${updatedSong.id}")
                                // If there's an existing cover file, remove it
                                existingSong.coverFile?.let { oldCoverFile ->
                                    symphony.database.artworkCache.get(oldCoverFile).delete()
                                }
                                
                                symphony.database.songCache.update(updatedSong)
                                // Update all necessary caches
                                symphony.groove.exposer.uris[path.pathString] = documentFile.uri
                                Logger.debug(TAG, "Calling onSong for existing song update")
                                symphony.groove.song.onSong(updatedSong) // This will update all caches
                                
                                // Queue metadata update instead of immediate update
                                Logger.debug(TAG, "Queuing cloud metadata update after song update")
                                queueMetadataUpdate()
                            } else {
                                Logger.debug(TAG, "Inserting new song into database and caches - ID: ${updatedSong.id}")
                                symphony.database.songCache.insert(updatedSong)
                                Logger.debug(TAG, "Calling onSong for new song")
                                symphony.groove.song.onSong(updatedSong)
                                
                                // Queue metadata update for new song
                                Logger.debug(TAG, "Queuing cloud metadata update after new song insertion")
                                queueMetadataUpdate()
                            }
                            
                            // Force refresh the MediaExposer for this file
                            Logger.debug(TAG, "Forcing MediaExposer refresh for file")
                            symphony.groove.exposer.scanSingleFile(documentFile, path)
                            
                            // Force artwork cache update
                            updatedSong.coverFile?.let { coverFile ->
                                Logger.debug(TAG, "Updating artwork cache for cover file: $coverFile")
                                symphony.groove.song.createArtworkImageRequest(updatedSong.id).build()
                            }
                            
                            Logger.debug(TAG, "Updated song metadata: ${updatedSong.title} (ID: ${updatedSong.id}, URI: ${updatedSong.uri})")
                        }
                    }

                    // Remove this track from cloud tracks since it's now local
                    delete(cloudFileId)
                    
                    Logger.debug(TAG, "File downloaded and processed: ${track.localPath}")
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

    suspend fun updateCloudMetadata() = withContext(Dispatchers.IO) {
        try {
            Logger.debug(TAG, "Starting cloud metadata update")
            startUpdate()

            // 1. Get all cloud songs from local database
            val cloudSongs = symphony.database.songCache.getCloudSongs()
            Logger.debug(TAG, "Found ${cloudSongs.size} cloud songs in local database")

            // 2. Try to download current metadata (may not exist on first use)
            val currentMetadata = try {
                val currentContent = symphony.dropbox.downloadMetadataFile()
                    ?: return@withContext Result.failure(Exception("Failed to download metadata file"))
                val currentMetadataResult = parseMetadataJson(currentContent)
                if (currentMetadataResult.isFailure) {
                    return@withContext Result.failure(currentMetadataResult.exceptionOrNull()
                        ?: Exception("Failed to parse metadata"))
                }
                currentMetadataResult.getOrNull()!!
            } catch (e: Exception) {
                // If file not found, start with empty metadata
                if (e.message?.contains("not_found") == true) {
                    Logger.debug(TAG, "No existing metadata file found, starting fresh")
                    emptyList()
                } else {
                    Logger.error(TAG, "Failed to download metadata", e)
                    return@withContext Result.failure(e)
                }
            }
            Logger.debug(TAG, "Working with ${currentMetadata.size} existing metadata tracks")

            // 3. Create lookup map of existing metadata
            val existingMetadataMap = currentMetadata.associateBy { it.cloudFileId }

            // 4. Create updated metadata entries
            val updatedMetadataTracks = cloudSongs.mapNotNull { song ->
                // Get existing metadata if any
                val existingMetadata = existingMetadataMap[song.cloudFileId]
                
                // If we don't have existing metadata, we need to find the mapping to construct the cloud path
                if (existingMetadata == null) {
                    val mapping = symphony.cloud.mapping.getMappingForPath(SimplePath(song.path)) 
                        ?: run {
                            Logger.warn(TAG, "No mapping found for song path: ${song.path}")
                            return@mapNotNull null
                        }
                    val relativePath = song.cloudPath?.removePrefix(mapping.cloudPath)?.trimStart('/') 
                        ?: run {
                            Logger.warn(TAG, "Song has no cloud path: ${song.path}")
                            return@mapNotNull null
                        }
                    
                    CloudTrackMetadata(
                        cloudFileId = song.cloudFileId!!,
                        cloudPath = song.cloudPath,
                        relativePath = relativePath,
                        lastModified = song.dateModified.toString(),
                        provider = "dropbox",  // Since we're using Dropbox as the cloud provider
                        cloudFolderId = mapping.cloudFolderId,
                        tags = CloudTrackMetadata.Tags(
                            title = song.title,
                            album = song.album,
                            artists = song.artists.toList(),
                            composers = song.composers.toList(),
                            albumArtists = song.albumArtists.toList(),
                            genres = song.genres.toList(),
                            date = song.date?.toString(),
                            year = song.year,
                            duration = (song.duration / 1000).toInt(), // Convert from ms to seconds
                            trackNo = song.trackNumber,
                            trackOf = song.trackTotal,
                            diskNo = song.discNumber,
                            diskOf = song.discTotal,
                            bitrate = song.bitrate?.toInt(),
                            samplingRate = song.samplingRate?.toInt(),
                            channels = song.channels,
                            encoder = song.encoder
                        )
                    )
                } else {
                    // Use existing metadata paths but update other fields
                    CloudTrackMetadata(
                        cloudFileId = song.cloudFileId!!,
                        cloudPath = existingMetadata.cloudPath,
                        relativePath = existingMetadata.relativePath,
                        lastModified = song.dateModified.toString(),
                        provider = existingMetadata.provider,
                        cloudFolderId = existingMetadata.cloudFolderId,
                        tags = CloudTrackMetadata.Tags(
                            title = song.title,
                            album = song.album,
                            artists = song.artists.toList(),
                            composers = song.composers.toList(),
                            albumArtists = song.albumArtists.toList(),
                            genres = song.genres.toList(),
                            date = song.date?.toString(),
                            year = song.year,
                            duration = (song.duration / 1000).toInt(), // Convert from ms to seconds
                            trackNo = song.trackNumber,
                            trackOf = song.trackTotal,
                            diskNo = song.discNumber,
                            diskOf = song.discTotal,
                            bitrate = song.bitrate?.toInt(),
                            samplingRate = song.samplingRate?.toInt(),
                            channels = song.channels,
                            encoder = song.encoder
                        )
                    )
                }
            }
            Logger.debug(TAG, "Created ${updatedMetadataTracks.size} updated metadata entries")

            // 5. Create final metadata JSON
            val metadataJson = buildJsonObject {
                putJsonArray("tracks") {
                    updatedMetadataTracks.forEach { metadata ->
                        add(buildJsonObject {
                            put("cloud_file_id", metadata.cloudFileId)
                            put("cloud_path", metadata.cloudPath)
                            put("relative_path", metadata.relativePath)
                            put("last_modified", metadata.lastModified)
                            put("provider", metadata.provider)
                            put("cloud_folder_id", metadata.cloudFolderId)
                            put("tags", buildJsonObject {
                                metadata.tags.title?.let { put("title", it) }
                                metadata.tags.album?.let { put("album", it) }
                                putJsonArray("artists") { metadata.tags.artists.forEach { add(it) } }
                                putJsonArray("composers") { metadata.tags.composers.forEach { add(it) } }
                                putJsonArray("album_artists") { metadata.tags.albumArtists.forEach { add(it) } }
                                putJsonArray("genres") { metadata.tags.genres.forEach { add(it) } }
                                metadata.tags.date?.let { put("date", it) }
                                metadata.tags.year?.let { put("year", it) }
                                put("duration", metadata.tags.duration)
                                metadata.tags.trackNo?.let { put("track_no", it) }
                                metadata.tags.trackOf?.let { put("track_of", it) }
                                metadata.tags.diskNo?.let { put("disk_no", it) }
                                metadata.tags.diskOf?.let { put("disk_of", it) }
                                metadata.tags.bitrate?.let { put("bitrate", it) }
                                metadata.tags.samplingRate?.let { put("sampling_rate", it) }
                                metadata.tags.channels?.let { put("channels", it) }
                                metadata.tags.encoder?.let { put("encoder", it) }
                            })
                        })
                    }
                }
            }

            // 6. Upload updated metadata
            symphony.dropbox.uploadMetadataFile(metadataJson.toString())
            Logger.debug(TAG, "Successfully uploaded updated metadata file")

            endUpdate()
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to update cloud metadata", e)
            endUpdate()
            Result.failure(e)
        }
    }

    internal suspend fun queueMetadataUpdate() {
        _isMetadataUpdateQueued.value = true
        metadataUpdateDebounceJob?.cancel()
        metadataUpdateDebounceJob = symphony.groove.coroutineScope.launch {
            delay(5000) // Wait 5 seconds for more potential updates
            updateCloudMetadata()
            _isMetadataUpdateQueued.value = false
        }
    }

    companion object {
        private const val TAG = "CloudTrackRepository"
    }
}
