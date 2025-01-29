package io.github.zyrouge.symphony.services.cloud

import io.github.zyrouge.symphony.Symphony
import io.github.zyrouge.symphony.services.cloud.repositories.CloudMappingRepository
import io.github.zyrouge.symphony.services.cloud.repositories.CloudTrackRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import io.github.zyrouge.symphony.utils.Logger

class Cloud(private val symphony: Symphony) : Symphony.Hooks {
    enum class Kind {
        DROPBOX,
        // Future providers...
    }

    val coroutineScope = CoroutineScope(Dispatchers.Default)
    var readyDeferred = CompletableDeferred<Boolean>()

    // Repositories
    val mapping = CloudMappingRepository(symphony)
    val tracks = CloudTrackRepository(symphony)

    private val _syncProgress = MutableStateFlow<SyncProgress>(SyncProgress())
    val syncProgress = _syncProgress.asStateFlow()

    suspend fun ready() = readyDeferred.await()

    private suspend fun fetch() {
        Logger.debug(TAG, "Starting cloud fetch")
        _syncProgress.value = SyncProgress(phase = SyncPhase.SCANNING_FOLDERS)
        
        coroutineScope.launch {
            try {
                // First fetch mappings
                mapping.fetch()
                
                // Then sync tracks if we have any mappings
                if (mapping.count.value > 0) {
                    tracks.syncMetadata()
                }
            } catch (e: Exception) {
                Logger.error(TAG, "fetch failed", e)
                _syncProgress.value = _syncProgress.value.copy(phase = SyncPhase.ERROR)
            }
        }.join()
        
        Logger.debug(TAG, "Completed cloud fetch")
        _syncProgress.value = _syncProgress.value.copy(phase = SyncPhase.COMPLETED)
    }

    private suspend fun reset() {
        Logger.debug(TAG, "Starting cloud reset")
        coroutineScope.launch {
            awaitAll(
                async { mapping.reset() },
                async { tracks.clear() }
            )
        }.join()
        Logger.debug(TAG, "Completed cloud reset")
    }

    data class FetchOptions(
        val resetInMemoryCache: Boolean = false,
        val resetPersistentCache: Boolean = false,
    )

    fun fetch(options: FetchOptions) {
        Logger.debug(TAG, "fetch called with options: $options")
        coroutineScope.launch {
            try {
                if (options.resetInMemoryCache) {
                    reset()
                }
                if (options.resetPersistentCache) {
                    // Clear any persistent cache if needed
                }
                fetch()
            } catch (e: Exception) {
                Logger.error(TAG, "fetch failed", e)
                _syncProgress.value = _syncProgress.value.copy(phase = SyncPhase.ERROR)
            }
        }
    }

    override fun onSymphonyReady() {
        Logger.debug(TAG, "onSymphonyReady called")
        coroutineScope.launch {
            fetch()
            readyDeferred.complete(true)
        }
    }

    companion object {
        private const val TAG = "Cloud"
    }
} 