package io.github.zyrouge.symphony.services.cloud.repositories

import io.github.zyrouge.symphony.Symphony
import io.github.zyrouge.symphony.services.cloud.Cloud
import io.github.zyrouge.symphony.services.cloud.CloudFolderMapping
import io.github.zyrouge.symphony.utils.Logger
import io.github.zyrouge.symphony.utils.SimplePath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

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

    suspend fun add(localPath: String, cloudPath: String, provider: String): Result<CloudFolderMapping> {
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
                provider = provider
            )
            symphony.database.cloudMappings.insert(mapping)
            cache[mapping.id] = mapping
            emitAll()
            emitCount()

            // Trigger cloud sync after adding new mapping
            symphony.cloud.fetch(Cloud.FetchOptions())

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
            path.pathString.startsWith(mapping.localPath)
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

    companion object {
        private const val TAG = "CloudMappingRepository"
    }
} 