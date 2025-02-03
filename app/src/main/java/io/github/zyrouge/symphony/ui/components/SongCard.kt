package io.github.zyrouge.symphony.ui.components

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import kotlinx.coroutines.launch
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.zyrouge.symphony.services.groove.Song
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import io.github.zyrouge.symphony.ui.view.AlbumArtistViewRoute
import io.github.zyrouge.symphony.ui.view.AlbumViewRoute
import io.github.zyrouge.symphony.ui.view.ArtistViewRoute
import io.github.zyrouge.symphony.utils.Logger
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.StrokeCap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SongCard(
    context: ViewContext,
    song: Song,
    highlighted: Boolean = false,
    autoHighlight: Boolean = true,
    disableHeartIcon: Boolean = false,
    leading: @Composable () -> Unit = {},
    thumbnailLabel: (@Composable () -> Unit)? = null,
    thumbnailLabelStyle: SongCardThumbnailLabelStyle = SongCardThumbnailLabelStyle.Default,
    trailingOptionsContent: (@Composable ColumnScope.(() -> Unit) -> Unit)? = null,
    onClick: () -> Unit,
) {
    val queue by context.symphony.radio.observatory.queue.collectAsState()
    val queueIndex by context.symphony.radio.observatory.queueIndex.collectAsState()
    val isCurrentPlaying by remember(autoHighlight, song, queue) {
        derivedStateOf { autoHighlight && song.id == queue.getOrNull(queueIndex) }
    }
    val favoriteSongIds by context.symphony.groove.playlist.favorites.collectAsState()
    val isFavorite by remember(favoriteSongIds, song) {
        derivedStateOf { favoriteSongIds.contains(song.id) }
    }
    val downloadProgress by context.symphony.cloud.tracks.downloadProgress.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val uris by context.symphony.groove.exposer.urisFlow.collectAsState()
    var showDownloadConfirmationDialog by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .let { modifier ->
                if (song.cloudFileId != null && uris[song.path] == null) {
                    modifier.alpha(0.5f)
                } else {
                    modifier
                }
            },
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        onClick = {
            if (song.cloudFileId != null) {
                // Check if the file exists locally
                val localFile = uris[song.path]
                Logger.debug("SongCard", "Clicked song - Path: ${song.path}, URI: ${song.uri}, Local file URI: $localFile")
                if (localFile != null) {
                    // File exists locally, play it
                    onClick()
                } else if (downloadProgress[song.cloudFileId] == null) {
                    // File doesn't exist and not currently downloading, show download dialog
                    showDownloadConfirmationDialog = true
                }
            } else {
                onClick()
            }
        }
    ) {
        Box(modifier = Modifier.padding(12.dp, 12.dp, 4.dp, 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                leading()
                Box {
                    AsyncImage(
                        song.createArtworkImageRequest(context.symphony).build(),
                        null,
                        modifier = Modifier
                            .size(45.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .let { modifier ->
                                if (song.cloudFileId != null) {
                                    val progress = downloadProgress[song.cloudFileId]
                                    if (progress != null) {
                                        modifier.background(Color.Black.copy(alpha = 0.5f))
                                    } else {
                                        modifier
                                    }
                                } else {
                                    modifier
                                }
                            },
                    )

                    // Show download progress overlay for cloud tracks
                    if (song.cloudFileId != null) {
                        val progress = downloadProgress[song.cloudFileId]
                        if (progress != null) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(45.dp)
                            ) {
                                CircularProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.size(35.dp),
                                    strokeWidth = 2.dp,
                                    strokeCap = StrokeCap.Round,
                                    color = Color.White
                                )
                            }
                        }
                    }

                    thumbnailLabel?.let { it ->
                        val backgroundColor = thumbnailLabelStyle.backgroundColor(MaterialTheme.colorScheme)
                        val contentColor = thumbnailLabelStyle.contentColor(MaterialTheme.colorScheme)

                        Box(
                            modifier = Modifier
                                .size(45.dp)
                                .background(backgroundColor)
                        ) {
                            ProvideTextStyle(
                                MaterialTheme.typography.labelSmall.copy(color = contentColor)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    it()
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        song.title,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = when {
                                highlighted || isCurrentPlaying -> MaterialTheme.colorScheme.primary
                                else -> LocalTextStyle.current.color
                            }
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (song.artists.isNotEmpty()) {
                        Text(
                            song.artists.joinToString(),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(15.dp))

                Row {
                    if (!disableHeartIcon && isFavorite) {
                        IconButton(
                            modifier = Modifier.offset(4.dp, 0.dp),
                            onClick = {
                                context.symphony.groove.playlist.unfavorite(song.id)
                            }
                        ) {
                            Icon(
                                Icons.Filled.Favorite,
                                null,
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }

                    var showOptionsMenu by remember { mutableStateOf(false) }
                    IconButton(
                        onClick = { showOptionsMenu = !showOptionsMenu }
                    ) {
                        Icon(
                            Icons.Filled.MoreVert,
                            null,
                            modifier = Modifier.size(24.dp),
                        )
                        SongDropdownMenu(
                            context,
                            song,
                            isFavorite = isFavorite,
                            trailingContent = trailingOptionsContent,
                            expanded = showOptionsMenu,
                            onDismissRequest = {
                                showOptionsMenu = false
                            }
                        )
                    }
                }
            }
        }
    }

    if (showDownloadConfirmationDialog) {
        DownloadConfirmationDialog(
            context = context,
            song = song,
            size = song.size,
            onDismissRequest = {
                showDownloadConfirmationDialog = false
            },
            onConfirm = {
                showDownloadConfirmationDialog = false
                isDownloading = true
                context.symphony.groove.coroutineScope.launch {
                    context.symphony.cloud.tracks.downloadTrack(song.cloudFileId!!)
                        .onSuccess {
                            isDownloading = false
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    context.symphony.applicationContext,
                                    "Download completed",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                        .onFailure { error ->
                            isDownloading = false
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    context.symphony.applicationContext,
                                    "Download failed: ${error.message}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                }
            }
        )
    }
}

@Composable
fun SongDropdownMenu(
    context: ViewContext,
    song: Song,
    isFavorite: Boolean,
    trailingContent: (@Composable ColumnScope.(() -> Unit) -> Unit)? = null,
    expanded: Boolean,
    onDismissRequest: () -> Unit,
) {
    var showInfoDialog by remember { mutableStateOf(false) }
    var showAddToPlaylistDialog by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var showDownloadConfirmationDialog by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // Check if song is available (downloaded)
    val isAvailable = song.let {
        (it.cloudFileId != null && context.symphony.groove.exposer.uris[it.path] != null) ||
        (it.cloudFileId == null && !it.uri.toString().isBlank())
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest
    ) {
        DropdownMenuItem(
            leadingIcon = {
                Icon(Icons.Filled.Favorite, null)
            },
            text = {
                Text(
                    if (isFavorite) context.symphony.t.Unfavorite
                    else context.symphony.t.Favorite
                )
            },
            onClick = {
                onDismissRequest()
                context.symphony.groove.playlist.run {
                    when {
                        isFavorite -> unfavorite(song.id)
                        else -> favorite(song.id)
                    }
                }
            }
        )
        if (isAvailable) {
            DropdownMenuItem(
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null)
                },
                text = {
                    Text(context.symphony.t.PlayNext)
                },
                onClick = {
                    onDismissRequest()
                    context.symphony.radio.queue.add(
                        song.id,
                        context.symphony.radio.queue.currentSongIndex + 1
                    )
                }
            )
            DropdownMenuItem(
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null)
                },
                text = {
                    Text(context.symphony.t.AddToQueue)
                },
                onClick = {
                    onDismissRequest()
                    context.symphony.radio.queue.add(song.id)
                }
            )
        }
        DropdownMenuItem(
            leadingIcon = {
                Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null)
            },
            text = {
                Text(context.symphony.t.AddToPlaylist)
            },
            onClick = {
                onDismissRequest()
                showAddToPlaylistDialog = true
            }
        )
        song.artists.forEach { artistName ->
            DropdownMenuItem(
                leadingIcon = {
                    Icon(Icons.Filled.Person, null)
                },
                text = {
                    Text("${context.symphony.t.ViewArtist}: $artistName")
                },
                onClick = {
                    onDismissRequest()
                    context.navController.navigate(ArtistViewRoute(artistName))
                }
            )
        }
        song.albumArtists.forEach { albumArtist ->
            DropdownMenuItem(
                leadingIcon = {
                    Icon(Icons.Filled.Person, null)
                },
                text = {
                    Text("${context.symphony.t.ViewAlbumArtist}: $albumArtist")
                },
                onClick = {
                    onDismissRequest()
                    context.navController.navigate(AlbumArtistViewRoute(albumArtist))
                }
            )
        }
        context.symphony.groove.album.getIdFromSong(song)?.let { albumId ->
            DropdownMenuItem(
                leadingIcon = {
                    Icon(Icons.Filled.Album, null)
                },
                text = {
                    Text(context.symphony.t.ViewAlbum)
                },
                onClick = {
                    onDismissRequest()
                    context.navController.navigate(AlbumViewRoute(albumId))
                }
            )
        }
        DropdownMenuItem(
            leadingIcon = {
                Icon(Icons.Filled.Share, null)
            },
            text = {
                Text(context.symphony.t.ShareSong)
            },
            onClick = {
                onDismissRequest()
                try {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        putExtra(Intent.EXTRA_STREAM, song.uri)
                        type = context.activity.contentResolver.getType(song.uri)
                    }
                    context.activity.startActivity(intent)
                } catch (err: Exception) {
                    Logger.error("SongCard", "share failed", err)
                    Toast.makeText(
                        context.activity,
                        context.symphony.t.ShareFailedX(err.localizedMessage ?: err.toString()),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        )
        // Add download option for cloud tracks
        if (song.cloudFileId != null) {
            DropdownMenuItem(
                leadingIcon = {
                    if (isDownloading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Filled.Download, contentDescription = "Download")
                    }
                },
                text = { Text("Download") },
                onClick = {
                    onDismissRequest()
                    showDownloadConfirmationDialog = true
                },
                enabled = !isDownloading
            )
        }

        DropdownMenuItem(
            leadingIcon = {
                Icon(Icons.Filled.Info, null)
            },
            text = {
                Text(context.symphony.t.Details)
            },
            onClick = {
                onDismissRequest()
                showInfoDialog = true
            }
        )
        trailingContent?.invoke(this, onDismissRequest)
    }

    if (showInfoDialog) {
        SongInformationDialog(
            context,
            song = song,
            onDismissRequest = {
                showInfoDialog = false
            }
        )
    }

    if (showAddToPlaylistDialog) {
        AddToPlaylistDialog(
            context,
            songIds = listOf(song.id),
            onDismissRequest = {
                showAddToPlaylistDialog = false
            }
        )
    }

    if (showDownloadConfirmationDialog) {
        DownloadConfirmationDialog(
            context = context,
            song = song,
            size = song.size,
            onDismissRequest = {
                showDownloadConfirmationDialog = false
            },
            onConfirm = {
                showDownloadConfirmationDialog = false
                isDownloading = true
                context.symphony.groove.coroutineScope.launch {
                    context.symphony.cloud.tracks.downloadTrack(song.cloudFileId!!)
                        .onSuccess {
                            isDownloading = false
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    context.symphony.applicationContext,
                                    "Download completed",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                        .onFailure { error ->
                            isDownloading = false
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    context.symphony.applicationContext,
                                    "Download failed: ${error.message}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                }
            }
        )
    }
}

enum class SongCardThumbnailLabelStyle {
    Default,
    Subtle,
}

private fun SongCardThumbnailLabelStyle.backgroundColor(colorScheme: ColorScheme) = when (this) {
    SongCardThumbnailLabelStyle.Default -> colorScheme.surfaceVariant
    SongCardThumbnailLabelStyle.Subtle -> colorScheme.surfaceVariant
}

private fun SongCardThumbnailLabelStyle.contentColor(colorScheme: ColorScheme) = when (this) {
    SongCardThumbnailLabelStyle.Default -> colorScheme.primary
    SongCardThumbnailLabelStyle.Subtle -> colorScheme.onSurfaceVariant
}
