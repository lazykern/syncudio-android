package me.lazykern.symphony.cloudsync.auth

sealed class CloudAuthResult {
    data class Success(
        val accessToken: String,
        val accountId: String
    ) : CloudAuthResult()

    data class Error(
        val exception: Exception,
        val message: String = exception.message ?: "Unknown error"
    ) : CloudAuthResult()

    data object Cancelled : CloudAuthResult()
} 