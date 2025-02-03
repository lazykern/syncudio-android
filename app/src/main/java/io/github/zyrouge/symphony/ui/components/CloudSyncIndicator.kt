package io.github.zyrouge.symphony.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.ui.helpers.ViewContext

@Composable
fun CloudSyncIndicator(context: ViewContext) {
    val isUpdating by context.symphony.cloud.tracks.isUpdating.collectAsState()
    val isMetadataUpdateQueued by context.symphony.cloud.tracks.isMetadataUpdateQueued.collectAsState()
    val isVisible = isUpdating || isMetadataUpdateQueued

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(48.dp)
        ) {
            val infiniteTransition = rememberInfiniteTransition(label = "blink")
            val alpha by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = 0.3f,
                animationSpec = infiniteRepeatable(
                    animation = tween(500),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "blink"
            )

            Icon(
                imageVector = when {
                    isUpdating -> Icons.Default.CloudDownload
                    isMetadataUpdateQueued -> Icons.Default.CloudUpload
                    else -> Icons.Default.CloudSync
                },
                contentDescription = when {
                    isUpdating -> "Downloading cloud metadata"
                    isMetadataUpdateQueued -> "Uploading metadata to cloud"
                    else -> "Cloud sync"
                },
                modifier = Modifier
                    .size(24.dp)
                    .alpha(if (isUpdating || isMetadataUpdateQueued) alpha else 1f),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
} 