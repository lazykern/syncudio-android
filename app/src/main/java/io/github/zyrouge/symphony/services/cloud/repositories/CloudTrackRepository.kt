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
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import me.zyrouge.symphony.metaphony.AudioMetadataParser
import android.os.ParcelFileDescriptor

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
            var totalFiles = 0
            
            // First pass: count total files
            mappings.forEach { mapping ->
                val result = symphony.dropbox.listFolder(mapping.cloudPath, recursive = true)
                if (result.isSuccess) {
                    totalFiles += result.getOrNull()?.entries?.size ?: 0
                }
            }
            
            updateProgress(current = 0, total = totalFiles)
            var processedFiles = 0

            // Second pass: process files
            mappings.forEach { mapping ->
                updateProgress(folder = mapping.cloudPath)
                val result = symphony.dropbox.listFolder(mapping.cloudPath, recursive = true)
                
                if (result.isSuccess) {
                    result.getOrNull()?.entries?.forEach { entry ->
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

    companion object {
        private const val TAG = "CloudTrackRepository"
    }
}