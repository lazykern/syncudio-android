package io.github.zyrouge.symphony.services.cloud.repositories

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import io.github.zyrouge.symphony.Symphony
import io.github.zyrouge.symphony.services.groove.Song
import io.github.zyrouge.symphony.utils.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDateTime
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

data class CloudTrack(
    val blake3_hash: String,
    val cloud_file_id: String,
    val cloud_path: String,
    val relative_path: String,
    val tags: CloudTrackTags,
    val last_modified: LocalDateTime,
    val last_sync: LocalDateTime,
    val provider: String,
    val cloud_folder_id: String,
)

data class CloudTrackTags(
    val title: String?,
    val album: String?,
    val artists: List<String>?,
    val composers: List<String>?,
    val album_artists: List<String>?,
    val genres: List<String>?,
    val date: String?,
    val year: Int?,
    val duration: Int?,
    val track_no: Int?,
    val track_of: Int?,
    val disk_no: Int?,
    val disk_of: Int?,
    val bitrate: Int?,
    val sampling_rate: Int?,
    val channels: Int?,
    val encoder: String?,
)

data class CloudTracksMetadata(
    val tracks: List<CloudTrack>,
    val last_updated: LocalDateTime,
    val version: String,
)

class CloudTrackRepository(private val symphony: Symphony) {
    private val cache = ConcurrentHashMap<String, CloudTrack>()
    private val _all = MutableStateFlow<List<String>>(emptyList())
    val all = _all.asStateFlow()
    private val _count = MutableStateFlow(0)
    val count = _count.asStateFlow()

    private fun emitCount() = _count.update {
        cache.size
    }

    private fun emitAll() = _all.update {
        cache.values.map { it.cloud_file_id }
    }

    suspend fun fetch() {
        Logger.debug(TAG, "Starting CloudTrackRepository fetch")
        try {
            // Try to read from cloud storage first
            Logger.debug(TAG, "Attempting to download metadata file")
            val metadataContent = symphony.dropbox.downloadMetadataFile()
            if (metadataContent != null) {
                Logger.debug(TAG, "Metadata file downloaded successfully, content length: ${metadataContent.length}")
                val gson = GsonBuilder()
                    .registerTypeAdapter(LocalDateTime::class.java, object : TypeAdapter<LocalDateTime>() {
                        @Override
                        override fun write(out: JsonWriter, value: LocalDateTime?) {
                            if (value == null) {
                                out.nullValue()
                            } else {
                                // Convert to UTC and include timezone
                                out.value(value.atOffset(ZoneOffset.UTC).toString())
                            }
                        }

                        @Override
                        override fun read(input: JsonReader): LocalDateTime? {
                            val dateStr = input.nextString()
                            return if (dateStr == null) null else {
                                // Parse as Instant (which handles UTC/Z timezone) then convert to LocalDateTime
                                Instant.parse(dateStr).atOffset(ZoneOffset.UTC).toLocalDateTime()
                            }
                        }
                    })
                    .create()
                
                val type = object : TypeToken<CloudTracksMetadata>() {}.type
                val metadata: CloudTracksMetadata = gson.fromJson(metadataContent, type)

                Logger.debug(TAG, "Parsed metadata: tracks count=${metadata.tracks.size}, last_updated=${metadata.last_updated}")
                
                cache.clear()
                metadata.tracks.forEach { track ->
                    cache[track.cloud_file_id] = track
                }
                emitAll()
                emitCount()
                Logger.debug(TAG, "Cache updated with ${cache.size} tracks")
            } else {
                Logger.debug(TAG, "No metadata file found or download failed")
            }
        } catch (err: Exception) {
            Logger.error(TAG, "fetch failed", err)
        }
    }

    fun get(cloudFileId: String) = cache[cloudFileId]

    fun getByCloudPath(cloudPath: String) = cache.values.find { it.cloud_path == cloudPath }

    fun getByBlake3Hash(hash: String) = cache.values.find { it.blake3_hash == hash }

    fun toSong(track: CloudTrack, uri: android.net.Uri): Song {
        return Song(
            id = symphony.groove.song.idGenerator.next(),
            title = track.tags.title ?: track.relative_path,
            album = track.tags.album,
            artists = track.tags.artists?.toSet() ?: emptySet(),
            composers = track.tags.composers?.toSet() ?: emptySet(),
            albumArtists = track.tags.album_artists?.toSet() ?: emptySet(),
            genres = track.tags.genres?.toSet() ?: emptySet(),
            trackNumber = track.tags.track_no,
            trackTotal = track.tags.track_of,
            discNumber = track.tags.disk_no,
            discTotal = track.tags.disk_of,
            date = null, // Convert from string if needed
            year = track.tags.year,
            duration = (track.tags.duration?.toLong() ?: 0) * 1000,
            bitrate = track.tags.bitrate?.toLong(),
            samplingRate = track.tags.sampling_rate?.toLong(),
            channels = track.tags.channels,
            encoder = track.tags.encoder,
            dateModified = track.last_modified.toEpochSecond(java.time.ZoneOffset.UTC) * 1000,
            size = 0, // Will be updated when file is downloaded
            coverFile = null, // Handle artwork separately
            uri = uri,
            path = track.relative_path,
            blake3Hash = track.blake3_hash,
            cloudFileId = track.cloud_file_id,
            cloudPath = track.cloud_path,
            provider = track.provider
        )
    }

    fun reset() {
        cache.clear()
        _all.update {
            emptyList()
        }
        emitCount()
    }

    companion object {
        private const val TAG = "CloudTrackRepository"
    }
} 