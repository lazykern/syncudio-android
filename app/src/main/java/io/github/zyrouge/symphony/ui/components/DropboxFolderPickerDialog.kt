package io.github.zyrouge.symphony.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dropbox.core.v2.files.FolderMetadata
import io.github.zyrouge.symphony.services.cloud.DropboxService
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropboxFolderPickerDialog(
    context: ViewContext,
    onDismissRequest: () -> Unit,
    onSelect: (path: String) -> Unit,
) {
    var currentPath by remember { mutableStateOf("") }
    var currentPathParts by remember { mutableStateOf(listOf<String>()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<Exception?>(null) }
    var folders by remember { mutableStateOf<List<FolderMetadata>>(emptyList()) }
    val coroutineScope = rememberCoroutineScope()

    fun loadFolder(path: String, pathParts: List<String>? = null) {
        isLoading = true
        error = null
        coroutineScope.launch {
            context.symphony.dropbox.listFolder(path)
                .onSuccess { result ->
                    folders = result.entries
                        .filterIsInstance<FolderMetadata>()
                    currentPath = path
                    currentPathParts = pathParts ?: if (path.isEmpty()) {
                        listOf("")
                    } else {
                        path.split("/")
                    }
                }
                .onFailure { err ->
                    error = err as Exception
                }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        loadFolder("", listOf(""))
    }

    ScaffoldDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(context.symphony.t.PickFolder)
        },
        contentHeight = ScaffoldDialogDefaults.PreferredMaxHeight,
        content = {
            Column(modifier = Modifier.fillMaxSize()) {
                // Breadcrumb navigation
                val pathScrollState = rememberScrollState()

                Row(
                    modifier = Modifier
                        .horizontalScroll(pathScrollState)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.CloudQueue,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    currentPathParts.forEachIndexed { index, part ->
                        val isLast = index == currentPathParts.lastIndex
                        val displayText = when {
                            part.isEmpty() -> "Dropbox"
                            else -> part
                        }
                        Text(
                            text = displayText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isLast) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable(enabled = !isLast) {
                                    val newPathParts = currentPathParts.take(index + 1)
                                    val newPath = newPathParts.joinToString("/")
                                    loadFolder(newPath, newPathParts)
                                }
                                .padding(4.dp)
                        )
                        if (!isLast) {
                            Text(
                                "/",
                                modifier = Modifier
                                    .padding(horizontal = 4.dp)
                                    .alpha(0.5f)
                            )
                        }
                    }
                }

                HorizontalDivider()

                // Content area with loading, error, or folder list
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = 8.dp)
                ) {
                    when {
                        isLoading -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                        error != null -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.Center)
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Error,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Text(
                                    error?.message ?: "Unknown error",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.error
                                )
                                TextButton(
                                    onClick = { loadFolder(currentPath) }
                                ) {
                                    Text(context.symphony.t.Reset)
                                }
                            }
                        }
                        folders.isEmpty() -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                SubtleCaptionText(context.symphony.t.NoFoldersFound)
                            }
                        }
                        else -> {
                            LazyColumn {
                                if (currentPath.isNotEmpty()) {
                                    item {
                                        ListItem(
                                            headlineContent = {
                                                Text("..")
                                            },
                                            leadingContent = {
                                                Icon(Icons.Default.FolderOpen, null)
                                            },
                                            modifier = Modifier.clickable {
                                                val newPathParts = currentPathParts.dropLast(1)
                                                val newPath = newPathParts.joinToString("/")
                                                loadFolder(newPath, newPathParts)
                                            }
                                        )
                                    }
                                }
                                items(folders) { folder ->
                                    ListItem(
                                        headlineContent = {
                                            Text(
                                                folder.name,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        },
                                        leadingContent = {
                                            Icon(Icons.Default.Folder, null)
                                        },
                                        modifier = Modifier.clickable {
                                            val newPathParts = currentPathParts + folder.name
                                            loadFolder(folder.pathDisplay!!, newPathParts)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        actions = {
            TextButton(
                onClick = onDismissRequest
            ) {
                Text(context.symphony.t.Cancel)
            }
            TextButton(
                onClick = {
                    onSelect(currentPath)
                    onDismissRequest()
                }
            ) {
                Text(context.symphony.t.Done)
            }
        }
    )
} 
