package io.github.zyrouge.symphony.services.radio

import io.github.zyrouge.symphony.Symphony
import io.github.zyrouge.symphony.utils.Logger
import kotlin.random.Random

class RadioShorty(private val symphony: Symphony) {
    companion object {
        private const val TAG = "RadioShorty"
    }

    fun playPause() {
        if (!symphony.radio.hasPlayer) {
            return
        }
        when {
            symphony.radio.isPlaying -> symphony.radio.pause()
            else -> symphony.radio.resume()
        }
    }

    fun seekFromCurrent(offsetSecs: Int) {
        if (!symphony.radio.hasPlayer) {
            return
        }
        symphony.radio.currentPlaybackPosition?.run {
            val to = (played + (offsetSecs * 1000)).coerceIn(0..total)
            symphony.radio.seek(to)
        }
    }

    fun previous(): Boolean {
        return when {
            !symphony.radio.hasPlayer -> false
            symphony.radio.currentPlaybackPosition!!.played <= 3000 && symphony.radio.canJumpToPrevious() -> {
                symphony.radio.jumpToPrevious()
                true
            }

            else -> {
                symphony.radio.seek(0)
                false
            }
        }
    }

    fun skip(): Boolean {
        return when {
            !symphony.radio.hasPlayer -> false
            symphony.radio.canJumpToNext() -> {
                symphony.radio.jumpToNext()
                true
            }

            else -> {
                symphony.radio.play(Radio.PlayOptions(index = 0, autostart = false))
                false
            }
        }
    }

    fun playQueue(
        songIds: List<String>,
        options: Radio.PlayOptions = Radio.PlayOptions(),
        shuffle: Boolean = false,
    ) {
        Logger.debug(TAG, "Playing queue - Songs count: ${songIds.size}, Shuffle: $shuffle, Options: $options")
        Logger.debug(TAG, "Song IDs in queue: ${songIds.joinToString(", ")}")
        songIds.forEachIndexed { index, songId ->
            val song = symphony.groove.song.get(songId)
            Logger.debug(TAG, "Song at index $index - ID: $songId, Title: ${song?.title}, CloudFileId: ${song?.cloudFileId}")
        }
        
        symphony.radio.stop(ended = false)
        if (songIds.isEmpty()) {
            return
        }
        val playIndex = if (shuffle) Random.nextInt(songIds.size) else options.index
        Logger.debug(TAG, "Selected play index: $playIndex")
        symphony.radio.queue.add(
            songIds,
            options = options.run {
                copy(index = playIndex)
            }
        )
        symphony.radio.queue.setShuffleMode(shuffle)
    }

    fun playQueue(
        songId: String,
        options: Radio.PlayOptions = Radio.PlayOptions(),
        shuffle: Boolean = false,
    ) = playQueue(listOf(songId), options = options, shuffle = shuffle)
}
