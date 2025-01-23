package me.lazykern.symphony.cloudsync.auth

import android.content.Context
import android.content.Intent

interface CloudAuthManager {
    suspend fun startAuth(context: Context): Intent
    suspend fun handleAuthResponse(intent: Intent): CloudAuthResult
    suspend fun logout()
    suspend fun isAuthenticated(): Boolean
    suspend fun getAccessToken(): String?
    suspend fun refreshToken(): CloudAuthResult
} 