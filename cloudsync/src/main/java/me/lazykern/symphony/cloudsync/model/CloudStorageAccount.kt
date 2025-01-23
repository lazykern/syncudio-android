package me.lazykern.symphony.cloudsync.model

data class CloudStorageAccount(
    val id: String,
    val name: String,
    val email: String?,
    val provider: String,
    val quota: CloudStorageQuota? = null,
    val metadata: Map<String, Any> = emptyMap()
)

data class CloudStorageQuota(
    val total: Long,
    val used: Long,
    val available: Long = total - used
) 