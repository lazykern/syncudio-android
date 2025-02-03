package io.github.zyrouge.symphony.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.services.groove.Song
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import io.github.zyrouge.symphony.utils.StorageUtils
import kotlinx.coroutines.launch

@Composable
fun DownloadConfirmationDialog(
    context: ViewContext,
    song: Song,
    size: Long,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    val storageInfo = remember { StorageUtils.getStorageInfo(context.symphony.applicationContext) }
    val coroutineScope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("Download Song") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Do you want to download this song?")
                Text("Title: ${song.title}")
                Text("Size: ${StorageUtils.StorageInfo(0, 0, 0).formatSize(size)}")
                Text("Available storage: ${storageInfo.formattedFreeSpace}")
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (size <= storageInfo.freeSpace) {
                        onConfirm()
                    }
                },
                enabled = size <= storageInfo.freeSpace
            ) {
                Text("Download")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(context.symphony.t.Cancel)
            }
        }
    )
} 