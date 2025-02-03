package io.github.zyrouge.symphony.services.cloud.repositories

import io.github.zyrouge.symphony.Symphony
import io.github.zyrouge.symphony.services.cloud.CloudFolderMapping
import io.github.zyrouge.symphony.services.cloud.CloudTrack
import io.github.zyrouge.symphony.utils.Logger
import io.github.zyrouge.symphony.utils.SimplePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import com.dropbox.core.v2.files.FileMetadata
import me.zyrouge.symphony.metaphony.AudioMetadataParser
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import android.net.Uri

class CloudMappingRepository(private val symphony: Symphony) {
    private val cache = ConcurrentHashMap<String, CloudFolderMapping>()
    private val _all = MutableStateFlow<List<String>>(emptyList())
    val all = _all.asStateFlow()
    private val _count = MutableStateFlow(0)
    val count = _count.asStateFlow()

    private fun emitCount() = _count.update {
        cache.size
    }

    private fun emitAll() = _all.update {
        cache.values.map { it.id }
    }

    suspend fun fetch() {
        try {
            val mappings = symphony.database.cloudMappings.getAll()
            cache.clear()
            mappings.forEach { mapping ->
                cache[mapping.id] = mapping
            }
            emitAll()
            emitCount()
        } catch (err: Exception) {
            Logger.error(TAG, "fetch failed", err)
        }
    }

    suspend fun add(localPath: String, cloudPath: String, provider: String, treeUri: Uri): Result<CloudFolderMapping> {
        try {
            // Check for duplicate paths
            val existingMappings = cache.values
            val hasDuplicateLocal = existingMappings.any { it.localPath == localPath }
            val hasDuplicateCloud = existingMappings.any { it.cloudPath == cloudPath }

            if (hasDuplicateLocal) {
                return Result.failure(Exception("A mapping for local path '$localPath' already exists"))
            }
            if (hasDuplicateCloud) {
                return Result.failure(Exception("A mapping for cloud path '$cloudPath' already exists"))
            }

            val mapping = CloudFolderMapping(
                id = CloudFolderMapping.generateId(localPath, cloudPath),
                localPath = localPath,
                cloudPath = cloudPath,
                cloudFolderId = cloudPath,  // Using cloudPath as cloudFolderId for now
                provider = provider,
                treeUri = treeUri
            )
            symphony.database.cloudMappings.insert(mapping)
            cache[mapping.id] = mapping
            emitAll()
            emitCount()
            return Result.success(mapping)
        } catch (err: Exception) {
            Logger.error(TAG, "add failed", err)
            return Result.failure(err)
        }
    }

    suspend fun remove(id: String) {
        symphony.database.cloudMappings.delete(id)
        cache.remove(id)
        emitAll()
        emitCount()
    }

    fun get(id: String) = cache[id]

    fun isPathMapped(path: SimplePath): Boolean {
        return cache.values.any { mapping -> 
            path.pathString.startsWith(mapping.localPath)
        }
    }

    fun getMappingForPath(path: SimplePath): CloudFolderMapping? {
        return cache.values.find { mapping ->
            path.pathString.replaceFirst(':','/').trimStart('/').startsWith(mapping.localPath.trimStart('/'))
        }
    }

    fun getCloudPath(path: SimplePath): String? {
        val mapping = getMappingForPath(path) ?: return null
        val relativePath = path.pathString.removePrefix(mapping.localPath)
        return "${mapping.cloudPath}/$relativePath".trimEnd('/')
    }

    fun reset() {
        cache.clear()
        _all.update {
            emptyList()
        }
        emitCount()
    }

    suspend fun scanForAudioTracks(mappingId: String): Result<List<CloudTrack>> = withContext(Dispatchers.IO) {
        try {
            Logger.debug(TAG, "Starting scan for mapping ID: $mappingId")
            val mapping = get(mappingId) ?: return@withContext Result.failure(
                Exception("No mapping found with ID: $mappingId")
            )
            Logger.debug(TAG, "Found mapping: local=${mapping.getLocalPathString()}, cloud=${mapping.getCloudPathString()}")

            when (mapping.provider.lowercase()) {
                "dropbox" -> {
                    Logger.debug(TAG, "Listing files recursively from Dropbox folder: ${mapping.cloudFolderId}")
                    val result = symphony.dropbox.listFolder(mapping.cloudFolderId, recursive = true)
                        .getOrNull()
                        ?: return@withContext Result.failure(
                            Exception("Failed to list files in cloud folder: ${mapping.cloudFolderId}")
                        )

                    Logger.debug(TAG, "Found ${result.entries.size} files in folder (including subfolders)")
                    val audioFiles = result.entries
                        .filterIsInstance<FileMetadata>()
                        .filter { entry ->
                            if (entry.pathDisplay == null) {
                                Logger.warn(TAG, "Skipping file with null pathDisplay: ${entry.name}")
                                return@filter false
                            }
                            val extension = entry.name.substringAfterLast('.', "").lowercase()
                            val isAudio = AudioMetadataParser.SUPPORTED_AUDIO_EXTENSIONS.contains(extension)
                            if (isAudio) {
                                val localPath = "${mapping.localPath}/${entry.pathDisplay!!.removePrefix(mapping.cloudPath)}"
                                Logger.debug(TAG, "Found audio file: cloud=${entry.pathDisplay}, local=$localPath")
                            }
                            isAudio
                        }
                    Logger.debug(TAG, "Filtered ${audioFiles.size} audio files")

                    val tracks = audioFiles.map { entry ->
                        val localPath = "${mapping.localPath}/${entry.pathDisplay!!.removePrefix(mapping.cloudPath)}"
                        CloudTrack.fromCloudFile(
                            cloudFileId = entry.id,
                            cloudPath = entry.pathDisplay!!,
                            provider = mapping.provider,
                            lastModified = entry.serverModified.time,
                            mapping = mapping,
                            size = entry.size
                        ).also {
                            Logger.debug(TAG, "Created track: id=${it.id}, title=${it.title}, local=${it.localPath}, localString=${it.localPathString}")
                        }
                    }
                    Logger.debug(TAG, "Created ${tracks.size} cloud tracks")

                    Result.success(tracks)
                }
                else -> {
                    Logger.error(TAG, "Unsupported cloud provider: ${mapping.provider}")
                    Result.failure(
                        Exception("Unsupported cloud provider: ${mapping.provider}")
                    )
                }
            }
        } catch (err: Exception) {
            Logger.error(TAG, "scanForAudioTracks failed", err)
            Result.failure(err)
        }
    }

    suspend fun scanAllMappingsForAudioTracks(): Result<List<CloudTrack>> = withContext(Dispatchers.IO) {
        try {
            Logger.debug(TAG, "Starting scan for all mappings")
            val mappingIds = all.value
            if (mappingIds.isEmpty()) {
                Logger.warn(TAG, "No mappings found to scan")
                return@withContext Result.success(emptyList())
            }
            Logger.debug(TAG, "Found ${mappingIds.size} mappings to scan")

            // Launch parallel scans for each mapping
            val results = mappingIds.map { mappingId ->
                async {
                    val mapping = get(mappingId)
                    if (mapping == null) {
                        Logger.warn(TAG, "Mapping not found: $mappingId")
                        emptyList()
                    } else {
                        scanForAudioTracks(mappingId)
                            .onSuccess { tracks ->
                                Logger.debug(TAG, "Successfully scanned mapping ${mapping.cloudPath}: found ${tracks.size} tracks")
                            }
                            .onFailure { error ->
                                Logger.error(TAG, "Failed to scan mapping ${mapping.cloudPath}", error)
                            }
                            .getOrDefault(emptyList())
                    }
                }
            }.awaitAll()

            // Combine all results
            val allTracks = results.flatten()
            Logger.debug(TAG, "Completed scanning all mappings. Total tracks found: ${allTracks.size}")

            Result.success(allTracks)
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to scan all mappings", e)
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "CloudMappingRepository"
    }
} 