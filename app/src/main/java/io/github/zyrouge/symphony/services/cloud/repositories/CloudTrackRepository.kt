package io.github.zyrouge.symphony.services.cloud.repositories

import android.net.Uri
import com.dropbox.core.v2.files.FileMetadata
import com.dropbox.core.v2.files.Metadata
import io.github.zyrouge.symphony.Symphony
import io.github.zyrouge.symphony.services.cloud.CloudTrack
import io.github.zyrouge.symphony.services.cloud.CloudTrackMetadata
import io.github.zyrouge.symphony.services.cloud.SyncPhase
import io.github.zyrouge.symphony.services.cloud.SyncProgress
import io.github.zyrouge.symphony.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.encodeToString
import java.io.File
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import me.zyrouge.symphony.metaphony.AudioMetadataParser
import android.os.ParcelFileDescriptor
import io.github.zyrouge.symphony.services.cloud.SyncStatus
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull

class CloudTrackRepository(private val symphony: Symphony) {
    private val _tracks = MutableStateFlow<List<CloudTrack>>(emptyList())
    val tracks = _tracks.asStateFlow()

    private val _isUpdating = MutableStateFlow(false)
    val isUpdating = _isUpdating.asStateFlow()

    private val _syncProgress = MutableStateFlow(SyncProgress())
    val syncProgress = _syncProgress.asStateFlow()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private fun parseTimestamp(timestamp: String): Long {
        return try {
            OffsetDateTime.parse(timestamp, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli()
        } catch (e: Exception) {
            Logger.warn(TAG, "Failed to parse timestamp: $timestamp")
            System.currentTimeMillis() // Use current time as fallback
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

    private suspend fun updateProgress(
        current: Int? = null,
        total: Int? = null,
        phase: SyncPhase? = null,
        folder: String? = null
    ) {
        _syncProgress.value = _syncProgress.value.copy(
            current = current ?: _syncProgress.value.current,
            total = total ?: _syncProgress.value.total,
            phase = phase ?: _syncProgress.value.phase,
            currentFolder = folder ?: _syncProgress.value.currentFolder
        )
    }

    suspend fun scanCloudFolders() = withContext(Dispatchers.IO) {
        try {
            updateProgress(phase = SyncPhase.SCANNING_FOLDERS)
            val mappings = symphony.database.cloudMappings.getAll()
            var processedFiles = 0
            
            mappings.forEach { mapping ->
                updateProgress(folder = mapping.cloudPath)
                val result = symphony.dropbox.listFolder(mapping.cloudPath, recursive = true)
                
                if (result.isSuccess) {
                    val entries = result.getOrNull()?.entries ?: emptyList()
                    // Update total count as we discover files
                    updateProgress(total = entries.size)
                    
                    entries.forEach { entry ->
                        if (entry is FileMetadata) {
                            val cloudPath = entry.pathDisplay ?: entry.pathLower ?: ""
                            if (cloudPath.endsWith(".mp3") || cloudPath.endsWith(".flac") || cloudPath.endsWith(".m4a")) {
                                createBasicCloudTrack(
                                    cloudFileId = entry.id,
                                    cloudPath = cloudPath,
                                    provider = "dropbox",
                                    lastModified = entry.serverModified?.time ?: System.currentTimeMillis(),
                                    size = entry.size
                                )
                            }
                        }
                        processedFiles++
                        updateProgress(current = processedFiles)
                    }
                }
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to scan cloud folders", e)
            updateProgress(phase = SyncPhase.ERROR)
            Result.failure(e)
        }
    }

    private suspend fun createBasicCloudTrack(
        cloudFileId: String,
        cloudPath: String,
        provider: String,
        lastModified: Long,
        size: Long
    ) {
        val fileName = cloudPath.substringAfterLast('/')
        val track = CloudTrack(
            id = cloudFileId.hashCode().toString(),  // Same ID generation as CloudTrack.generateId
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
            blake3Hash = "",  // Will be computed when downloaded
            isDownloaded = false,
            localPath = null,
            localUri = null,
            needsMetadataUpdate = true  // Mark as needing metadata update
        )

        // Check if track exists locally using path mapping
        val localPath = getLocalPathFromCloudPath(cloudPath)
        if (localPath != null) {
            // Get local song using SongRepository's public methods
            val localSong = symphony.groove.song.all.value
                .mapNotNull { symphony.groove.song.get(it) }
                .firstOrNull { it.path == localPath }

            if (localSong != null) {
                // Update with local metadata
                val updatedTrack = track.copy(
                    title = localSong.title,
                    album = localSong.album,
                    artists = localSong.artists,
                    composers = localSong.composers,
                    albumArtists = localSong.albumArtists,
                    genres = localSong.genres,
                    trackNumber = localSong.trackNumber,
                    trackTotal = localSong.trackTotal,
                    discNumber = localSong.discNumber,
                    discTotal = localSong.discTotal,
                    date = localSong.date,
                    year = localSong.year,
                    duration = localSong.duration,
                    bitrate = localSong.bitrate,
                    samplingRate = localSong.samplingRate,
                    channels = localSong.channels,
                    encoder = localSong.encoder,
                    blake3Hash = localSong.blake3Hash ?: "",
                    isDownloaded = true,
                    localPath = localPath,
                    localUri = localSong.uri,
                    needsMetadataUpdate = false  // Already has metadata
                )
                insert(updatedTrack)
                return
            }
        }

        // Only insert if doesn't exist
        if (symphony.database.cloudTrackCache.getByCloudFileId(cloudFileId) == null) {
            insert(track)
        }
    }

    private suspend fun getLocalPathFromCloudPath(cloudPath: String): String? {
        val mappings = symphony.database.cloudMappings.getAll()
        for (mapping in mappings) {
            if (cloudPath.startsWith(mapping.cloudPath)) {
                val relativePath = cloudPath.removePrefix(mapping.cloudPath)
                return mapping.localPath + relativePath
            }
        }
        return null
    }

    suspend fun getTracksNeedingMetadataUpdate(): List<CloudTrack> = withContext(Dispatchers.IO) {
        symphony.database.cloudTrackCache.getAll().filter { it.needsMetadataUpdate }
    }

    suspend fun updateMetadataForDownloadedTrack(track: CloudTrack) = withContext(Dispatchers.IO) {
        if (!track.isDownloaded || track.localPath == null) return@withContext

        try {
            // Read metadata from local file
            val file = File(track.localPath)
            val fd = ParcelFileDescriptor.dup(file.inputStream().fd).getFd()
            val metadata = AudioMetadataParser.parse(file.absolutePath, fd)
            
            if (metadata != null) {
                // Update cloud track with new metadata
                val updatedTrack = track.copy(
                    needsMetadataUpdate = false,
                    // Update other metadata fields based on AudioMetadataParser result
                    title = metadata.title ?: track.title,
                    album = metadata.album,
                    artists = metadata.artists,
                    composers = metadata.composers,
                    albumArtists = metadata.albumArtists,
                    genres = metadata.genres,
                    trackNumber = metadata.trackNumber,
                    trackTotal = metadata.trackTotal,
                    discNumber = metadata.discNumber,
                    discTotal = metadata.discTotal,
                    date = metadata.date,
                    year = metadata.date?.year,
                    duration = metadata.lengthInSeconds?.toLong()?.times(1000) ?: 0, // Convert to milliseconds
                    bitrate = metadata.bitrate?.toLong(),
                    samplingRate = metadata.sampleRate?.toLong(),
                    channels = metadata.channels,
                    encoder = metadata.encoding
                )
                update(updatedTrack)
            }
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to update metadata for track: ${track.cloudPath}", e)
        }
    }

    suspend fun syncMetadata() = withContext(Dispatchers.IO) {
        try {
            startUpdate()
            
            // First scan cloud folders
            val scanResult = scanCloudFolders()
            if (scanResult.isFailure) {
                return@withContext scanResult
            }

            // Then process metadata
            updateProgress(phase = SyncPhase.DOWNLOADING_METADATA)
            
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

            updateProgress(
                current = 0,
                total = tracks.size,
                phase = SyncPhase.UPDATING_DATABASE
            )

            // Get existing tracks for conflict resolution
            val existingTracks = symphony.database.cloudTrackCache.getAll()
            var current = 0

            tracks.forEach { metadata ->
                val lastModified = parseTimestamp(metadata.lastModified)
                val existing = existingTracks.find { it.cloudFileId == metadata.cloudFileId }

                if (existing != null) {
                    // Last-modified conflict resolution
                    if (lastModified > existing.lastModified) {
                        updateFromMetadata(
                            cloudFileId = metadata.cloudFileId,
                            cloudPath = metadata.cloudPath,
                            provider = metadata.provider,
                            lastModified = lastModified,
                            metadata = metadata,
                        )
                    }
                } else {
                    updateFromMetadata(
                        cloudFileId = metadata.cloudFileId,
                        cloudPath = metadata.cloudPath,
                        provider = metadata.provider,
                        lastModified = lastModified,
                        metadata = metadata,
                    )
                }

                current++
                updateProgress(current = current)
            }

            updateProgress(phase = SyncPhase.COMPLETED)
            Result.success(tracks)
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to sync metadata", e)
            updateProgress(phase = SyncPhase.ERROR)
            Result.failure(e)
        } finally {
            endUpdate()
        }
    }

    suspend fun generateMetadataJson(): String = withContext(Dispatchers.IO) {
        val tracks = symphony.database.cloudTrackCache.getAll()
        val metadataTracks = tracks.map { track ->
            buildJsonObject {
                put("blake3_hash", JsonPrimitive(track.blake3Hash))
                put("cloud_file_id", JsonPrimitive(track.cloudFileId))
                put("cloud_path", JsonPrimitive(track.cloudPath))
                put("relative_path", JsonPrimitive(track.cloudPath.removePrefix("/")))
                put("last_modified", JsonPrimitive(OffsetDateTime.now().toString()))
                put("last_sync", JsonPrimitive(OffsetDateTime.now().toString()))
                put("provider", JsonPrimitive(track.provider))
                put("cloud_folder_id", JsonPrimitive(""))  // Not used currently
                putJsonObject("tags") {
                    put("title", JsonPrimitive(track.title))
                    put("album", track.album?.let { JsonPrimitive(it) } ?: JsonNull)
                    putJsonArray("artists") { track.artists.forEach { add(JsonPrimitive(it)) } }
                    putJsonArray("composers") { track.composers.forEach { add(JsonPrimitive(it)) } }
                    putJsonArray("album_artists") { track.albumArtists.forEach { add(JsonPrimitive(it)) } }
                    putJsonArray("genres") { track.genres.forEach { add(JsonPrimitive(it)) } }
                    put("date", track.date?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                    put("year", track.year?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("duration", JsonPrimitive((track.duration / 1000).toInt())) // Convert from ms to seconds
                    put("track_no", track.trackNumber?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("track_of", track.trackTotal?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("disk_no", track.discNumber?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("disk_of", track.discTotal?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("bitrate", track.bitrate?.toInt()?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("sampling_rate", track.samplingRate?.toInt()?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("channels", track.channels?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("encoder", track.encoder?.let { JsonPrimitive(it) } ?: JsonNull)
                }
            }
        }
        
        return@withContext buildJsonObject {
            putJsonArray("tracks") {
                metadataTracks.forEach { add(it) }
            }
        }.toString()
    }

    suspend fun uploadMetadata() = withContext(Dispatchers.IO) {
        try {
            updateProgress(phase = SyncPhase.UPLOADING_METADATA)
            val content = generateMetadataJson()
            val result = symphony.dropbox.uploadMetadataFile(content)
            
            if (!result) {
                Logger.error(TAG, "Failed to upload metadata file")
                updateProgress(phase = SyncPhase.ERROR)
                return@withContext Result.failure(Exception("Failed to upload metadata file"))
            }

            updateProgress(phase = SyncPhase.COMPLETED)
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to upload metadata", e)
            updateProgress(phase = SyncPhase.ERROR)
            Result.failure(e)
        }
    }

    /**
     * Matches a local track with its cloud counterpart based on path mapping and hash
     */
    private suspend fun findMatchingCloudTrack(localPath: String, localHash: String): CloudTrack? {
        // First try to find by path mapping
        val cloudPath = getCloudPathFromLocalPath(localPath) ?: return null
        var track = symphony.database.cloudTrackCache.getByCloudPath(cloudPath)
        
        if (track != null) {
            // If found by path, verify hash
            if (track.blake3Hash == localHash) {
                return track
            }
        }
        
        // If not found by path or hash mismatch, try to find by hash
        track = symphony.database.cloudTrackCache.getByBlake3Hash(localHash)
        return track
    }

    /**
     * Gets the cloud path corresponding to a local path using folder mappings
     */
    private suspend fun getCloudPathFromLocalPath(localPath: String): String? {
        val mappings = symphony.database.cloudMappings.getAll()
        for (mapping in mappings) {
            if (localPath.startsWith(mapping.localPath)) {
                val relativePath = localPath.removePrefix(mapping.localPath)
                return mapping.cloudPath + relativePath
            }
        }
        return null
    }

    /**
     * Updates the sync status of a track based on its local and cloud state
     */
    private suspend fun updateTrackSyncStatus(
        track: CloudTrack,
        localPath: String? = null,
        localHash: String? = null
    ) {
        val updatedTrack = when {
            // Track exists only in cloud
            localPath == null -> track.copy(syncStatus = SyncStatus.CLOUD_ONLY)
            
            // Track exists in both places
            localHash != null -> {
                when {
                    // Hashes match - track is synced
                    localHash == track.blake3Hash -> track.copy(syncStatus = SyncStatus.SYNCED)
                    
                    // Hashes don't match - conflict
                    else -> track.copy(syncStatus = SyncStatus.CONFLICT)
                }
            }
            
            // Track exists locally but hash not computed
            else -> track.copy(syncStatus = SyncStatus.CONFLICT)
        }
        
        if (updatedTrack.syncStatus != track.syncStatus) {
            update(updatedTrack)
        }
    }

    /**
     * Syncs a local track with the cloud
     */
    suspend fun syncLocalTrack(localPath: String, localHash: String) = withContext(Dispatchers.IO) {
        try {
            // Find matching cloud track
            val cloudTrack = findMatchingCloudTrack(localPath, localHash)
            
            if (cloudTrack != null) {
                // Update sync status
                updateTrackSyncStatus(cloudTrack, localPath, localHash)
                Result.success(cloudTrack)
            } else {
                // Track only exists locally
                val cloudPath = getCloudPathFromLocalPath(localPath) ?: return@withContext Result.failure(
                    Exception("No matching cloud folder mapping found for $localPath")
                )
                
                // Create new cloud track
                val track = CloudTrack.createBasic(
                    cloudFileId = "", // Will be set after upload
                    cloudPath = cloudPath,
                    provider = "dropbox",
                    lastModified = System.currentTimeMillis(),
                    size = File(localPath).length(),
                    blake3Hash = localHash,
                    syncStatus = SyncStatus.LOCAL_ONLY
                )
                
                insert(track)
                Result.success(track)
            }
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to sync local track: $localPath", e)
            Result.failure(e)
        }
    }

    /**
     * Main sync method that handles the synchronization process
     * This method:
     * 1. Scans local and cloud tracks
     * 2. Matches tracks and updates sync status
     * 3. Handles conflicts
     * Note: This does not handle downloads, as per requirements
     */
    suspend fun syncTracks() = withContext(Dispatchers.IO) {
        try {
            startUpdate()
            updateProgress(phase = SyncPhase.SCANNING_FOLDERS)
            
            // First scan cloud folders to get latest state
            val scanResult = scanCloudFolders()
            if (scanResult.isFailure) {
                return@withContext scanResult
            }

            // Get all mapped local tracks
            val mappings = symphony.database.cloudMappings.getAll()
            val localTracks = mutableListOf<Pair<String, String>>() // path, hash pairs
            
            mappings.forEach { mapping ->
                val localFolder = File(mapping.localPath)
                if (localFolder.exists() && localFolder.isDirectory) {
                    localFolder.walk().forEach { file ->
                        if (file.isFile && (file.extension == "mp3" || file.extension == "flac" || file.extension == "m4a")) {
                            val hash = symphony.groove.hashManager.computeBlake3Hash(file.absolutePath)
                            if (hash != null) {
                                localTracks.add(Pair(file.absolutePath, hash))
                            }
                        }
                    }
                }
            }

            updateProgress(
                current = 0,
                total = localTracks.size,
                phase = SyncPhase.UPDATING_DATABASE
            )

            // Process each local track
            var current = 0
            localTracks.forEach { (path, hash) ->
                syncLocalTrack(path, hash)
                current++
                updateProgress(current = current)
            }

            // Update status of cloud-only tracks
            val cloudTracks = symphony.database.cloudTrackCache.getAll()
            cloudTracks.forEach { track ->
                val localPath = getLocalPathFromCloudPath(track.cloudPath)
                if (localPath == null || !File(localPath).exists()) {
                    updateTrackSyncStatus(track)
                }
            }

            // Upload metadata
            updateProgress(phase = SyncPhase.UPLOADING_METADATA)
            val uploadResult = uploadMetadata()
            if (uploadResult.isFailure) {
                return@withContext uploadResult
            }

            updateProgress(phase = SyncPhase.COMPLETED)
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to sync tracks", e)
            updateProgress(phase = SyncPhase.ERROR)
            Result.failure(e)
        } finally {
            endUpdate()
        }
    }

    companion object {
        private const val TAG = "CloudTrackRepository"
    }
}