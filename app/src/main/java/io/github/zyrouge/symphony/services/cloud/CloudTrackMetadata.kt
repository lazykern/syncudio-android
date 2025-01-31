package io.github.zyrouge.symphony.services.cloud

data class CloudTrackMetadata(
    val tags: Tags,
    val cloudFileId: String,
    val cloudPath: String,
    val relativePath: String,
    val lastModified: String,
    val lastSync: String,
    val provider: String,
    val cloudFolderId: String,
) {
    data class Tags(
        val title: String?,
        val album: String?,
        val artists: List<String>,
        val composers: List<String>,
        val albumArtists: List<String>,
        val genres: List<String>,
        val date: String?,
        val year: Int?,
        val duration: Int,
        val trackNo: Int?,
        val trackOf: Int?,
        val diskNo: Int?,
        val diskOf: Int?,
        val bitrate: Int?,
        val samplingRate: Int?,
        val channels: Int?,
        val encoder: String?,
    )
} 