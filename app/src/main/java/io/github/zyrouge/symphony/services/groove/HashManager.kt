package io.github.zyrouge.symphony.services.groove

import io.github.zyrouge.symphony.Symphony
import io.github.zyrouge.symphony.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class HashManager(private val symphony: Symphony) {
    private val hashingDispatcher = Dispatchers.IO.limitedParallelism(2)
    private val _isComputingHashes = MutableStateFlow(false)
    val isComputingHashes = _isComputingHashes.asStateFlow()

    suspend fun computeMissingHashes() {
        if (_isComputingHashes.value) return
        _isComputingHashes.value = true
        try {
            withContext(hashingDispatcher) {
                val songs = symphony.database.songCache.entriesPathMapped().values
                val songsWithoutHash = songs.filter { it.blake3Hash == null }
                
                // Process songs in smaller batches to avoid memory pressure
                songsWithoutHash.chunked(5).forEach { batch ->
                    batch.forEach { song ->
                        try {
                            val hash = song.computeBlake3Hash(symphony)
                            if (hash != null) {
                                symphony.database.songCache.update(song.copy(blake3Hash = hash))
                                Logger.debug("HashManager", "Computed BLAKE3 hash for ${song.filename}")
                            }
                        } catch (err: Exception) {
                            Logger.warn("HashManager", "Failed to compute BLAKE3 hash for ${song.filename}", err)
                        }
                    }
                    // Add a small delay between batches to let GC catch up
                    kotlinx.coroutines.delay(100)
                }
            }
        } finally {
            _isComputingHashes.value = false
        }
    }
} 