package me.lazykern.symphony.cloudsync.model

data class CloudFile(
    val id: String,
    val name: String,
    val path: String,
    val size: Long,
    val mimeType: String?,
    val lastModified: Long,
    val isDownloaded: Boolean = false,
    val downloadPath: String? = null,
    val provider: String,
    val metadata: Map<String, Any> = emptyMap()
) 