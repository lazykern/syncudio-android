package io.github.zyrouge.symphony.services.groove.repositories

import android.net.Uri
import androidx.core.net.toUri
import io.github.zyrouge.symphony.Symphony
import io.github.zyrouge.symphony.services.cloud.CloudTrack
import io.github.zyrouge.symphony.services.groove.Song
import io.github.zyrouge.symphony.ui.helpers.Assets
import io.github.zyrouge.symphony.ui.helpers.createHandyImageRequest
import io.github.zyrouge.symphony.utils.FuzzySearchOption
import io.github.zyrouge.symphony.utils.FuzzySearcher
import io.github.zyrouge.symphony.utils.KeyGenerator
import io.github.zyrouge.symphony.utils.Logger
import io.github.zyrouge.symphony.utils.SimpleFileSystem
import io.github.zyrouge.symphony.utils.SimplePath
import io.github.zyrouge.symphony.utils.joinToStringIfNotEmpty
import io.github.zyrouge.symphony.utils.withCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

class SongRepository(private val symphony: Symphony) {
    enum class SortBy {
        CUSTOM,
        TITLE,
        ARTIST,
        ALBUM,
        DURATION,
        DATE_MODIFIED,
        COMPOSER,
        ALBUM_ARTIST,
        YEAR,
        FILENAME,
        TRACK_NUMBER,
        LAST_PLAYED,
    }

    private val cache = ConcurrentHashMap<String, Song>()
    internal val pathCache = ConcurrentHashMap<String, String>()
    internal val idGenerator = KeyGenerator.TimeIncremental()
    private val searcher = FuzzySearcher<String>(
        options = listOf(
            FuzzySearchOption({ v -> get(v)?.title?.let { compareString(it) } }, 3),
            FuzzySearchOption({ v -> get(v)?.filename?.let { compareString(it) } }, 2),
            FuzzySearchOption({ v -> get(v)?.artists?.let { compareCollection(it) } }),
            FuzzySearchOption({ v -> get(v)?.album?.let { compareString(it) } })
        )
    )

    val isUpdating get() = symphony.groove.exposer.isUpdating
    private val _all = MutableStateFlow<List<String>>(emptyList())
    val all = _all.asStateFlow()
    private val _count = MutableStateFlow(0)
    val count = _count.asStateFlow()
    private val _id = MutableStateFlow(System.currentTimeMillis())
    val id = _id.asStateFlow()
    var explorer = SimpleFileSystem.Folder()

    private fun emitCount() = _count.update { cache.size }

    private fun emitIds() = _id.update {
        System.currentTimeMillis()
    }

    internal fun onSong(song: Song) {
        Logger.debug("SongRepository", "onSong called for song: ${song.id} (${song.title}) - Path: ${song.path}")
        Logger.debug("SongRepository", "Current cache state - Size: ${cache.size}, All songs: ${_all.value.size}")
        
        // Check if song already exists
        val existingInCache = cache[song.id]
        val existingInAll = _all.value.contains(song.id)
        Logger.debug("SongRepository", "Existing state - In cache: ${existingInCache != null}, In all: $existingInAll")

        // Remove existing file from explorer if it exists
        explorer.removeChildFile(SimplePath(song.path))
        
        // Update caches
        cache[song.id] = song
        pathCache[song.path] = song.id
        
        // Add new file to explorer
        explorer.addChildFile(SimplePath(song.path)).data = song.id
        
        emitIds()
        _all.update { current ->
            if (!current.contains(song.id)) {
                Logger.debug("SongRepository", "Adding song ${song.id} to _all list")
                current + song.id
            } else {
                Logger.debug("SongRepository", "Song ${song.id} already in _all list")
                current
            }
        }
        emitCount()
        Logger.debug("SongRepository", "After update - Cache size: ${cache.size}, All songs: ${_all.value.size}")
    }

    fun reset() {
        cache.clear()
        pathCache.clear()
        explorer = SimpleFileSystem.Folder()
        emitIds()
        _all.update {
            emptyList()
        }
        emitCount()
    }

    fun search(songIds: List<String>, terms: String, limit: Int = 7) = searcher
        .search(terms, songIds, maxLength = limit)

    fun sort(songIds: List<String>, by: SortBy, reverse: Boolean): List<String> {
        val sensitive = symphony.settings.caseSensitiveSorting.value
        val sorted = when (by) {
            SortBy.CUSTOM -> songIds
            SortBy.TITLE -> songIds.sortedBy { get(it)?.title?.withCase(sensitive) }
            SortBy.ARTIST -> songIds.sortedBy { get(it)?.artists?.joinToStringIfNotEmpty(sensitive) }
            SortBy.ALBUM -> songIds.sortedBy { get(it)?.album?.withCase(sensitive) }
            SortBy.DURATION -> songIds.sortedBy { get(it)?.duration }
            SortBy.DATE_MODIFIED -> songIds.sortedBy { get(it)?.dateModified }
            SortBy.COMPOSER -> songIds.sortedBy {
                get(it)?.composers?.joinToStringIfNotEmpty(sensitive)
            }
            SortBy.ALBUM_ARTIST -> songIds.sortedBy {
                get(it)?.albumArtists?.joinToStringIfNotEmpty(sensitive)
            }
            SortBy.YEAR -> songIds.sortedBy { get(it)?.year }
            SortBy.FILENAME -> songIds.sortedBy { get(it)?.filename?.withCase(sensitive) }
            SortBy.TRACK_NUMBER -> songIds.sortedWith(
                compareBy({ get(it)?.discNumber }, { get(it)?.trackNumber }),
            )
            SortBy.LAST_PLAYED -> songIds.sortedBy { get(it)?.lastPlayed }
        }
        return if (reverse) sorted.reversed() else sorted
    }

    fun count() = cache.size
    fun ids() = cache.keys.toList()
    fun values() = cache.values.toList()

    fun get(id: String) = cache[id]
    fun get(ids: List<String>) = ids.mapNotNull { get(it) }

    fun getArtworkUri(songId: String): Uri = get(songId)?.coverFile
        ?.let { symphony.database.artworkCache.get(it) }?.toUri()
        ?: getDefaultArtworkUri()

    fun getDefaultArtworkUri() = Assets.getPlaceholderUri(symphony)

    fun createArtworkImageRequest(songId: String) = createHandyImageRequest(
        symphony.applicationContext,
        image = getArtworkUri(songId),
        fallback = Assets.getPlaceholderId(symphony),
    )

    suspend fun getLyrics(song: Song): String? {
        try {
            val lrcPath = SimplePath(song.path).let {
                it.parent?.join(it.nameWithoutExtension + ".lrc")?.pathString
            }
            symphony.groove.exposer.uris[lrcPath]?.let { uri ->
                symphony.applicationContext.contentResolver.openInputStream(uri)?.use {
                    return String(it.readBytes())
                }
            }
            return symphony.database.lyricsCache.get(song.id)
        } catch (err: Exception) {
            Logger.error("LyricsRepository", "fetch lyrics failed", err)
        }
        return null
    }

    suspend fun integrateCloudTracks(cloudTracks: List<CloudTrack>) {
        Logger.debug(TAG, "Starting cloud tracks integration")
        
        // Get all songs and create a lookup map by path
        val songs = symphony.database.songCache.entriesPathMapped()
        Logger.debug(TAG, "Found ${songs.size} songs in database")

        // Track stats
        var updatedCount = 0
        var addedCount = 0

        // Process each cloud track
        cloudTracks.forEach { cloudTrack ->
            // Look up song by localPathString (which matches Song's path format)
            val song = songs[cloudTrack.localPathString]
            if (song != null) {
                // Update song with cloud information
                val updatedSong = song.copy(
                    cloudFileId = cloudTrack.cloudFileId,
                    cloudPath = cloudTrack.cloudPath,
                    provider = cloudTrack.provider
                )
                symphony.database.songCache.update(updatedSong)
                updatedCount++
                Logger.debug(TAG, "Updated song with cloud info: ${song.path}")
            } else {
                // Create a new Song entry for the cloud track
                val newSong = Song(
                    id = idGenerator.next(),
                    title = cloudTrack.title,
                    album = cloudTrack.album,
                    artists = cloudTrack.artists,
                    composers = cloudTrack.composers,
                    albumArtists = cloudTrack.albumArtists,
                    genres = cloudTrack.genres,
                    trackNumber = cloudTrack.trackNumber,
                    trackTotal = cloudTrack.trackTotal,
                    discNumber = cloudTrack.discNumber,
                    discTotal = cloudTrack.discTotal,
                    date = cloudTrack.date,
                    year = cloudTrack.year,
                    duration = cloudTrack.duration,
                    bitrate = cloudTrack.bitrate,
                    samplingRate = cloudTrack.samplingRate,
                    channels = cloudTrack.channels,
                    encoder = cloudTrack.encoder,
                    dateModified = cloudTrack.lastModified,
                    size = cloudTrack.size,
                    coverFile = null, // Cloud tracks don't have local cover files yet
                    uri = Uri.EMPTY, // Cloud tracks don't have local URIs
                    path = cloudTrack.localPathString,
                    cloudFileId = cloudTrack.cloudFileId,
                    cloudPath = cloudTrack.cloudPath,
                    provider = cloudTrack.provider
                )
                symphony.database.songCache.insert(newSong)
                onSong(newSong) // Add to cache and emit updates
                addedCount++
                Logger.debug(TAG, "Added new song from cloud track: ${cloudTrack.localPathString}")
            }
        }

        Logger.debug(TAG, "Cloud tracks integration complete - Updated: $updatedCount, Added: $addedCount")
    }

    suspend fun updateLastPlayed(songId: String) {
        Logger.debug(TAG, "Updating last played time for song: $songId")
        get(songId)?.let { song ->
            val updatedSong = song.copy(lastPlayed = System.currentTimeMillis())
            symphony.database.songCache.update(updatedSong)
            onSong(updatedSong)
            Logger.debug(TAG, "Updated last played time for song: ${song.title}")
        }
    }

    companion object {
        private const val TAG = "SongRepository"
    }
}
