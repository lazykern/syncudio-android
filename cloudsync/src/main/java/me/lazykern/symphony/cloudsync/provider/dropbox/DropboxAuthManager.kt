package me.lazykern.symphony.cloudsync.provider.dropbox

import android.content.Context
import android.content.Intent
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.android.Auth
import com.dropbox.core.oauth.DbxCredential
import com.dropbox.core.v2.DbxClientV2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.lazykern.symphony.cloudsync.auth.CloudAuthManager
import me.lazykern.symphony.cloudsync.auth.CloudAuthResult

class DropboxAuthManager(
    private val context: Context,
    private val appKey: String,
) : CloudAuthManager {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
        
    private val encryptedPrefs = EncryptedSharedPreferences.create(
        context,
        "dropbox_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val requestConfig = DbxRequestConfig
        .newBuilder("Symphony/1.0")
        .withAutoRetryEnabled()
        .build()

    companion object {
        private const val PREF_ACCESS_TOKEN = "dropbox_access_token"
        private const val PREF_REFRESH_TOKEN = "dropbox_refresh_token"
        private const val PREF_ACCOUNT_ID = "dropbox_account_id"
    }

    override suspend fun startAuth(context: Context): Intent {
        // Using PKCE OAuth2 flow via the official Dropbox app or browser
        Auth.startOAuth2PKCE(
            context,
            appKey,
            null  // Scopes are not required for PKCE flow
        )
        // This is a dummy intent as Dropbox handles the UI
        return Intent()
    }

    override suspend fun handleAuthResponse(intent: Intent): CloudAuthResult = withContext(Dispatchers.IO) {
        val credential = Auth.getDbxCredential()
        return@withContext when {
            credential != null -> {
                saveCredentials(credential)
                CloudAuthResult.Success(
                    accessToken = credential.accessToken,
                    accountId = encryptedPrefs.getString(PREF_ACCOUNT_ID, "") ?: ""
                )
            }
            else -> CloudAuthResult.Cancelled
        }
    }

    override suspend fun logout() = withContext(Dispatchers.IO) {
        encryptedPrefs.edit()
            .remove(PREF_ACCESS_TOKEN)
            .remove(PREF_REFRESH_TOKEN)
            .remove(PREF_ACCOUNT_ID)
            .apply()
    }

    override suspend fun isAuthenticated(): Boolean = withContext(Dispatchers.IO) {
        getAccessToken() != null
    }

    override suspend fun getAccessToken(): String? = withContext(Dispatchers.IO) {
        encryptedPrefs.getString(PREF_ACCESS_TOKEN, null)
    }

    override suspend fun refreshToken(): CloudAuthResult = withContext(Dispatchers.IO) {
        val refreshToken = encryptedPrefs.getString(PREF_REFRESH_TOKEN, null)
        if (refreshToken == null) {
            return@withContext CloudAuthResult.Error(Exception("No refresh token available"))
        }

        try {
            val client = DbxClientV2(requestConfig, getAccessToken())
            client.auth().tokenRevoke()
            // Token revoked successfully, start new auth
            return@withContext CloudAuthResult.Error(Exception("Token expired, please authenticate again"))
        } catch (e: Exception) {
            CloudAuthResult.Error(e)
        }
    }

    private fun saveCredentials(credential: DbxCredential) {
        encryptedPrefs.edit()
            .putString(PREF_ACCESS_TOKEN, credential.accessToken)
            .putString(PREF_REFRESH_TOKEN, credential.refreshToken)
            .putString(PREF_ACCOUNT_ID, credential.accessToken.split(".").firstOrNull() ?: "")
            .apply()
    }
} 