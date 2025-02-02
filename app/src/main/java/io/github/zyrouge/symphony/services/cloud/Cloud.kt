package io.github.zyrouge.symphony.services.cloud

import io.github.zyrouge.symphony.Symphony
import io.github.zyrouge.symphony.services.cloud.repositories.CloudMappingRepository
import io.github.zyrouge.symphony.services.cloud.repositories.CloudTrackRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

    suspend fun ready() = readyDeferred.await()

    private suspend fun fetch() {
        Logger.debug(TAG, "Starting cloud fetch")
        coroutineScope.launch {
            awaitAll(
                async { mapping.fetch() },
                async { tracks.fetch() },
            )
        }.join()
        Logger.debug(TAG, "Completed cloud fetch")
    }

    private suspend fun reset() {
        Logger.debug(TAG, "Starting cloud reset")
        coroutineScope.launch {
            awaitAll(
                async { mapping.reset() },
                async { tracks.clear() },
            )
        }.join()
        Logger.debug(TAG, "Completed cloud reset")
    }

    data class FetchOptions(
        val resetInMemoryCache: Boolean = false,
        val resetPersistentCache: Boolean = false,
        val scanAfterFetch: Boolean = true,
    )

    fun fetch(options: FetchOptions) {
        Logger.debug(TAG, "fetch called with options: $options")
        coroutineScope.launch {
            if (options.resetInMemoryCache) {
                reset()
            }
            if (options.resetPersistentCache) {
                // Clear any persistent cache if needed
            }
            fetch()
            
            // Scan and integrate cloud tracks after fetching if requested
            if (options.scanAfterFetch) {
                tracks.scanAndIntegrateCloudTracks()
            }
        }
    }

    override fun onSymphonyReady() {
        Logger.debug(TAG, "onSymphonyReady called")
        coroutineScope.launch {
            // Initial fetch without scanning since Groove will trigger it
            fetch(FetchOptions(scanAfterFetch = false))
            readyDeferred.complete(true)
        }
    }

    companion object {
        private const val TAG = "Cloud"
    }
} 