package io.github.zyrouge.symphony.ui.view.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.services.cloud.DropboxAuthState
import io.github.zyrouge.symphony.ui.components.IconButtonPlaceholder
import io.github.zyrouge.symphony.ui.components.TopAppBarMinimalTitle
import io.github.zyrouge.symphony.ui.components.settings.SettingsSideHeading
import io.github.zyrouge.symphony.ui.components.settings.SettingsSimpleTile
import io.github.zyrouge.symphony.utils.ActivityUtils
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import kotlinx.coroutines.launch
import io.github.zyrouge.symphony.ui.components.DropboxFolderPickerDialog
import io.github.zyrouge.symphony.services.cloud.CloudFolderMapping
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudStorageSettingsView(context: ViewContext) {
    val scrollState = rememberScrollState()
    val authState by context.symphony.dropbox.authState.collectAsState()
    val mappings by context.symphony.cloud.mapping.all.collectAsState()
    var showAddMappingDialog by remember { mutableStateOf(false) }
    var mappingToDelete: CloudFolderMapping? by remember { mutableStateOf(null) }
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    TopAppBarMinimalTitle {
                        Text("${context.symphony.t.Settings} - ${context.symphony.t.CloudStorage}")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent
                ),
                navigationIcon = {
                    IconButton(onClick = { context.navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
                actions = {
                    IconButtonPlaceholder()
                },
            )
        },
        content = { contentPadding ->
            Box(
                modifier = Modifier
                    .padding(contentPadding)
                    .fillMaxSize()
            ) {
                Column(modifier = Modifier.verticalScroll(scrollState)) {
                    SettingsSideHeading("Dropbox")
                    when (authState) {
                        is DropboxAuthState.Unauthenticated -> {
                            SettingsSimpleTile(
                                icon = {
                                    Icon(Icons.Default.CloudOff, null)
                                },
                                title = {
                                    Text("Connect to Dropbox")
                                },
                                onClick = {
                                    context.symphony.dropbox.startAuthentication()
                                }
                            )
                        }
                        is DropboxAuthState.InProgress -> {
                            SettingsSimpleTile(
                                icon = {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                },
                                title = {
                                    Text("Connecting to Dropbox...")
                                }
                            )
                        }
                        is DropboxAuthState.Authenticated -> {
                            val account = (authState as DropboxAuthState.Authenticated).account
                            SettingsSimpleTile(
                                icon = {
                                    Icon(Icons.Default.CloudDone, null)
                                },
                                title = {
                                    Text(account.name.displayName)
                                },
                                subtitle = {
                                    Text(account.email)
                                }
                            )
                            HorizontalDivider()
                            SettingsSimpleTile(
                                icon = {
                                    Icon(Icons.AutoMirrored.Filled.Logout, null)
                                },
                                title = {
                                    Text("Disconnect")
                                },
                                onClick = {
                                    context.symphony.dropbox.logout()
                                }
                            )
                        }
                        is DropboxAuthState.Error -> {
                            val error = (authState as DropboxAuthState.Error).error
                            SettingsSimpleTile(
                                icon = {
                                    Icon(Icons.Default.Error, null)
                                },
                                title = {
                                    Text("Failed to connect to Dropbox")
                                },
                                subtitle = {
                                    Text(error.message ?: "Unknown error")
                                },
                                onClick = {
                                    context.symphony.dropbox.startAuthentication()
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    SettingsSideHeading(context.symphony.t.CloudMappings)
                    
                    if (mappings.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.CloudSync,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    context.symphony.t.DamnThisIsSoEmpty,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                FilledTonalButton(
                                    onClick = { showAddMappingDialog = true }
                                ) {
                                    Icon(Icons.Default.Add, null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(context.symphony.t.AddItem)
                                }
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            mappings.forEach { mappingId ->
                                context.symphony.cloud.mapping.get(mappingId)?.let { mapping ->
                                    CloudMappingCard(
                                        context = context,
                                        mapping = mapping,
                                        onDelete = {
                                            mappingToDelete = mapping
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                if (authState is DropboxAuthState.Authenticated) {
                    ExtendedFloatingActionButton(
                        onClick = { showAddMappingDialog = true },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                        icon = { Icon(Icons.Default.Add, null) },
                        text = { Text(context.symphony.t.AddItem) }
                    )
                }
            }
        }
    )

    // Add delete confirmation dialog
    mappingToDelete?.let { mapping ->
        AlertDialog(
            onDismissRequest = { mappingToDelete = null },
            icon = {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text(context.symphony.t.DeletePlaylist)
            },
            text = {
                Text(context.symphony.t.AreYouSureThatYouWantToDeleteThisPlaylist)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            context.symphony.cloud.mapping.remove(mapping.id)
                            mappingToDelete = null
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(context.symphony.t.Delete)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { mappingToDelete = null }
                ) {
                    Text(context.symphony.t.Cancel)
                }
            }
        )
    }

    if (showAddMappingDialog) {
        AddCloudMappingDialog(
            context = context,
            onDismiss = { showAddMappingDialog = false },
            onAdd = { localPath, cloudPath ->
                context.symphony.cloud.mapping.add(
                    localPath = localPath,
                    cloudPath = cloudPath,
                    provider = "dropbox"
                )
                showAddMappingDialog = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloudMappingCard(
    context: ViewContext,
    mapping: CloudFolderMapping,
    onDelete: () -> Unit,
) {
    var isScanning by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header with delete button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.CloudQueue,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Dropbox",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Scan button
                    IconButton(
                        onClick = {
                            coroutineScope.launch {
                                isScanning = true
                                error = null
                                context.symphony.cloud.mapping.scanForAudioTracks(mapping.id)
                                    .onSuccess { tracks ->
                                        // Read metadata tracks
                                        context.symphony.cloud.tracks.readCloudMetadataTracks()
                                            .onSuccess { metadataTracks ->
                                                context.symphony.cloud.tracks.insert(*metadataTracks.toTypedArray())
                                                error = null
                                            }
                                            .onFailure { err ->
                                                error = "Failed to read metadata: ${err.message}"
                                            }
                                    }
                                    .onFailure { err ->
                                        error = err.message
                                    }
                                isScanning = false
                            }
                        },
                        enabled = !isScanning
                    ) {
                        if (isScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Default.CloudSync,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    // Delete button
                    IconButton(
                        onClick = onDelete,
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Delete, null)
                    }
                }
            }

            // Paths
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Local Path
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        mapping.localPath,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // Cloud Path
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.CloudSync,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        mapping.cloudPath,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Error message
            error?.let { errorMessage ->
                Text(
                    errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddCloudMappingDialog(
    context: ViewContext,
    onDismiss: () -> Unit,
    onAdd: suspend (localPath: String, cloudPath: String) -> Unit
) {
    var localPath by remember { mutableStateOf("") }
    var cloudPath by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var showDropboxPicker by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    val pickFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { selectedUri ->
            // Make the URI persistable and readable
            ActivityUtils.makePersistableReadableUri(context.symphony.applicationContext, selectedUri)
            // Get the path from URI
            val path = selectedUri.path?.substringAfter("/tree/")?.replace(":", "/")
            path?.let { localPath = "/$it" }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(context.symphony.t.AddItem)
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Error message
                error?.let { errorMessage ->
                    Text(
                        errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                // Local Path Selection
                OutlinedCard(
                    onClick = {
                        pickFolderLauncher.launch(null)
                    }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            context.symphony.t.Path,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                localPath.ifEmpty { context.symphony.t.PickFolder },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Cloud Path Selection
                OutlinedCard(
                    onClick = {
                        showDropboxPicker = true
                    }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            "Dropbox Path",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.CloudQueue,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                cloudPath.ifEmpty { "Select Dropbox folder" },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (localPath.isNotEmpty() && cloudPath.isNotEmpty()) {
                        isProcessing = true
                        error = null
                        coroutineScope.launch {
                            try {
                                context.symphony.cloud.mapping.add(
                                    localPath = localPath,
                                    cloudPath = cloudPath,
                                    provider = "dropbox"
                                ).onSuccess {
                                    onDismiss()
                                }.onFailure { err ->
                                    error = err.message
                                    isProcessing = false
                                }
                            } catch (err: Exception) {
                                error = err.message ?: "Failed to add mapping"
                                isProcessing = false
                            }
                        }
                    }
                },
                enabled = !isProcessing && localPath.isNotEmpty() && cloudPath.isNotEmpty()
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(context.symphony.t.Done)
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isProcessing
            ) {
                Text(context.symphony.t.Cancel)
            }
        }
    )

    if (showDropboxPicker) {
        DropboxFolderPickerDialog(
            context = context,
            onDismissRequest = {
                showDropboxPicker = false
            },
            onSelect = { path ->
                cloudPath = path
                showDropboxPicker = false
            }
        )
    }
}