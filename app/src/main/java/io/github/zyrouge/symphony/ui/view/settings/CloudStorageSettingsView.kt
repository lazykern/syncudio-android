package io.github.zyrouge.symphony.ui.view.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.zyrouge.symphony.services.cloud.DropboxAuthState
import io.github.zyrouge.symphony.ui.components.IconButtonPlaceholder
import io.github.zyrouge.symphony.ui.components.TopAppBarMinimalTitle
import io.github.zyrouge.symphony.ui.components.settings.SettingsSideHeading
import io.github.zyrouge.symphony.ui.components.settings.SettingsSimpleTile
import io.github.zyrouge.symphony.ui.helpers.ViewContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudStorageSettingsView(context: ViewContext) {
    val scrollState = rememberScrollState()
    val authState by context.symphony.dropbox.authState.collectAsState()

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
                }
            }
        }
    )
}
