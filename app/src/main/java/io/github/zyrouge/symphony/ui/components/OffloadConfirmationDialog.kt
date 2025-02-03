package io.github.zyrouge.symphony.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.services.groove.Song
import io.github.zyrouge.symphony.ui.helpers.ViewContext

@Composable
fun SongInformation(song: Song, size: Long) {
    Column {
        Text("Title: ${song.title}")
        if (song.artists.isNotEmpty()) {
            Text("Artists: ${song.artists.joinToString()}")
        }
        Text("Size: ${formatFileSize(size)}")
    }
}

private fun formatFileSize(size: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = size.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.size - 1) {
        value /= 1024
        unitIndex++
    }
    return "%.2f %s".format(value, units[unitIndex])
}

@Composable
fun OffloadConfirmationDialog(
    context: ViewContext,
    song: Song,
    size: Long,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("Offload") },
        text = {
            Column {
                Text("Are you sure you want to remove the local copy of this song?")
                Spacer(modifier = Modifier.height(8.dp))
                SongInformation(song, size)
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm
            ) {
                Text("Offload")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest
            ) {
                Text(context.symphony.t.Cancel)
            }
        }
    )
}
