package io.github.zyrouge.symphony.services.radio

import io.github.zyrouge.symphony.Symphony
import io.github.zyrouge.symphony.utils.concurrentListOf
import io.github.zyrouge.symphony.utils.Logger

class RadioQueue(private val symphony: Symphony) {
    companion object {
        private const val TAG = "RadioQueue"
    }

    enum class LoopMode {
        None,
        Queue,
        Song;

        companion object {
            val values = enumValues<LoopMode>()
        }
    }

    val originalQueue = concurrentListOf<String>()
    val currentQueue = concurrentListOf<String>()

    var currentSongIndex = -1
        internal set(value) {
            field = value
            symphony.radio.onUpdate.dispatch(Radio.Events.Queue.IndexChanged)
        }

    var currentShuffleMode = false
        private set(value) {
            field = value
            symphony.radio.onUpdate.dispatch(Radio.Events.QueueOption.ShuffleModeChanged)
        }

    var currentLoopMode = LoopMode.None
        private set(value) {
            field = value
            symphony.radio.onUpdate.dispatch(Radio.Events.QueueOption.LoopModeChanged)
        }

    val currentSongId: String?
        get() = getSongIdAt(currentSongIndex)

    fun hasSongAt(index: Int) = index > -1 && index < currentQueue.size
    fun getSongIdAt(index: Int) = if (hasSongAt(index)) currentQueue[index] else null

    fun reset() {
        originalQueue.clear()
        currentQueue.clear()
        currentSongIndex = -1
        symphony.radio.onUpdate.dispatch(Radio.Events.Queue.Cleared)
    }

    fun add(
        songIds: List<String>,
        index: Int? = null,
        options: Radio.PlayOptions = Radio.PlayOptions(),
    ) {
        // Create a map of original indices to songs for available tracks
        val availableSongs = mutableListOf<String>()
        val originalToFilteredIndex = mutableMapOf<Int, Int>()
        
        songIds.forEachIndexed { originalIndex, songId ->
            val song = symphony.groove.song.get(songId)
            Logger.debug(TAG, "Checking song availability - ID: $songId, Song: ${song?.title}, CloudFileId: ${song?.cloudFileId}, Path: ${song?.path}, URI: ${song?.uri}")
            
            if (song != null && (
                // For cloud songs, check if we have a local URI
                (song.cloudFileId != null && symphony.groove.exposer.uris[song.path] != null) ||
                // For local songs, check main URI
                (song.cloudFileId == null && !song.uri.toString().isBlank())
            )) {
                originalToFilteredIndex[originalIndex] = availableSongs.size
                availableSongs.add(songId)
            }
        }

        Logger.debug(TAG, "Adding songs to queue - Original count: ${songIds.size}, Available count: ${availableSongs.size}, Index mapping: $originalToFilteredIndex")
        if (availableSongs.isEmpty()) return

        // Map the requested play index to the filtered index
        val targetPlayIndex = options.index?.let { requestedIndex ->
            // If a specific index was requested in the play options, map it to the filtered index
            originalToFilteredIndex[requestedIndex]
        }

        // If a specific insert index was requested, map it to the filtered index
        val targetInsertIndex = index?.let { originalIndex ->
            originalToFilteredIndex[originalIndex] ?: 0
        }

        targetInsertIndex?.let {
            Logger.debug(TAG, "Adding songs at index $it - Current index: $currentSongIndex")
            originalQueue.addAll(it, availableSongs)
            currentQueue.addAll(it, availableSongs)
            if (it <= currentSongIndex) {
                currentSongIndex += availableSongs.size
            }
        } ?: run {
            Logger.debug(TAG, "Adding songs at end - Current index: $currentSongIndex")
            originalQueue.addAll(availableSongs)
            currentQueue.addAll(availableSongs)
        }

        // Update the play options with the mapped index if one was provided
        val updatedOptions = if (targetPlayIndex != null) {
            options.copy(index = targetPlayIndex)
        } else {
            options
        }
        
        afterAdd(updatedOptions)
    }

    fun add(
        songId: String,
        index: Int? = null,
        options: Radio.PlayOptions = Radio.PlayOptions(),
    ) = add(listOf(songId), index, options)

    private fun afterAdd(options: Radio.PlayOptions) {
        if (!symphony.radio.hasPlayer) {
            symphony.radio.play(options)
        }
        symphony.radio.onUpdate.dispatch(Radio.Events.Queue.Modified)
    }

    fun remove(index: Int) {
        originalQueue.removeAt(index)
        currentQueue.removeAt(index)
        symphony.radio.onUpdate.dispatch(Radio.Events.Queue.Modified)
        if (currentSongIndex == index) {
            symphony.radio.play(Radio.PlayOptions(index = currentSongIndex))
        } else if (index < currentSongIndex) {
            currentSongIndex--
        }
    }

    fun remove(indices: List<Int>) {
        var deflection = 0
        var currentSongRemoved = false
        val sortedIndices = indices.sortedDescending()
        for (i in sortedIndices) {
            val index = i - deflection
            originalQueue.removeAt(index)
            currentQueue.removeAt(index)
            when {
                i < currentSongIndex -> deflection++
                i == currentSongIndex -> currentSongRemoved = true
            }
        }
        currentSongIndex -= deflection
        symphony.radio.onUpdate.dispatch(Radio.Events.Queue.Modified)
        if (currentSongRemoved) {
            symphony.radio.play(Radio.PlayOptions(index = currentSongIndex))
        }
    }

    fun setLoopMode(loopMode: LoopMode) {
        currentLoopMode = loopMode
    }

    fun toggleLoopMode() {
        val next = (currentLoopMode.ordinal + 1) % LoopMode.values.size
        setLoopMode(LoopMode.values[next])
    }

    fun toggleShuffleMode() = setShuffleMode(!currentShuffleMode)

    fun setShuffleMode(to: Boolean) {
        currentShuffleMode = to
        if (currentQueue.isNotEmpty()) {
            val currentSongId = getSongIdAt(currentSongIndex) ?: getSongIdAt(0)!!
            currentSongIndex = if (currentShuffleMode) {
                val newQueue = originalQueue.toMutableList()
                newQueue.removeAt(currentSongIndex)
                newQueue.shuffle()
                newQueue.add(0, currentSongId)
                currentQueue.clear()
                currentQueue.addAll(newQueue)
                0
            } else {
                currentQueue.clear()
                currentQueue.addAll(originalQueue)
                originalQueue.indexOfFirst { it == currentSongId }
            }
        }
        symphony.radio.onUpdate.dispatch(Radio.Events.Queue.Modified)
    }

    fun isEmpty() = originalQueue.isEmpty()

    data class Serialized(
        val currentSongIndex: Int,
        val playedDuration: Long,
        val originalQueue: List<String>,
        val currentQueue: List<String>,
        val shuffled: Boolean,
    ) {
        fun serialize() =
            listOf(
                currentSongIndex.toString(),
                playedDuration.toString(),
                originalQueue.joinToString(","),
                currentQueue.joinToString(","),
                shuffled.toString(),
            ).joinToString(";")

        companion object {
            fun create(queue: RadioQueue, playbackPosition: RadioPlayer.PlaybackPosition) =
                Serialized(
                    currentSongIndex = queue.currentSongIndex,
                    playedDuration = playbackPosition.played,
                    originalQueue = queue.originalQueue.toList(),
                    currentQueue = queue.currentQueue.toList(),
                    shuffled = queue.currentShuffleMode,
                )

            fun parse(data: String): Serialized? {
                try {
                    val semi = data.split(";")
                    return Serialized(
                        currentSongIndex = semi[0].toInt(),
                        playedDuration = semi[1].toLong(),
                        originalQueue = semi[2].split(","),
                        currentQueue = semi[3].split(","),
                        shuffled = semi[4].toBoolean(),
                    )
                } catch (_: Exception) {
                }
                return null
            }
        }
    }

    fun restore(serialized: Serialized) {
        if (serialized.originalQueue.isNotEmpty()) {
            symphony.radio.stop(ended = false)
            originalQueue.clear()
            originalQueue.addAll(serialized.originalQueue)
            currentQueue.clear()
            currentQueue.addAll(serialized.currentQueue)
            symphony.radio.onUpdate.dispatch(Radio.Events.Queue.Modified)
            currentShuffleMode = serialized.shuffled
            afterAdd(
                Radio.PlayOptions(
                    index = serialized.currentSongIndex,
                    autostart = false,
                    startPosition = serialized.playedDuration,
                )
            )
        }
    }
}
