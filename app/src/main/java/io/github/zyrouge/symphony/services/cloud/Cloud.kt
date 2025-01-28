package io.github.zyrouge.symphony.services.cloud

import io.github.zyrouge.symphony.Symphony
import io.github.zyrouge.symphony.services.cloud.repositories.CloudMappingRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

class Cloud(private val symphony: Symphony) : Symphony.Hooks {
    enum class Kind {
        DROPBOX,
        // Future providers...
    }

    val coroutineScope = CoroutineScope(Dispatchers.Default)
    var readyDeferred = CompletableDeferred<Boolean>()

    // Repositories
    val mapping = CloudMappingRepository(symphony)

    suspend fun ready() = readyDeferred.await()

    private suspend fun fetch() {
        coroutineScope.launch {
            awaitAll(
                async { mapping.fetch() },
            )
        }.join()
    }

    private suspend fun reset() {
        coroutineScope.launch {
            awaitAll(
                async { mapping.reset() },
            )
        }.join()
    }

    data class FetchOptions(
        val resetInMemoryCache: Boolean = false,
        val resetPersistentCache: Boolean = false,
    )

    fun fetch(options: FetchOptions) {
        coroutineScope.launch {
            if (options.resetInMemoryCache) {
                reset()
            }
            if (options.resetPersistentCache) {
                // Clear any persistent cache if needed
            }
            fetch()
        }
    }

    override fun onSymphonyReady() {
        coroutineScope.launch {
            fetch()
            readyDeferred.complete(true)
        }
    }
} 