package io.github.zyrouge.symphony.services.cloud

import androidx.compose.runtime.Immutable

@Immutable
data class SyncProgress(
    val total: Int = 0,
    val current: Int = 0,
    val phase: SyncPhase = SyncPhase.IDLE,
    val currentFolder: String? = null
)

enum class SyncPhase {
    IDLE,
    SCANNING_FOLDERS,
    DOWNLOADING_METADATA,
    UPDATING_DATABASE,
    COMPLETED,
    ERROR
} 