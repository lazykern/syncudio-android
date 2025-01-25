package io.github.zyrouge.symphony.services.cloud

import com.dropbox.core.v2.users.FullAccount

sealed class DropboxAuthState {
    object Unauthenticated : DropboxAuthState()
    object InProgress : DropboxAuthState()
    data class Authenticated(val account: FullAccount) : DropboxAuthState()
    data class Error(val error: Exception) : DropboxAuthState()
} 