package io.github.zyrouge.symphony.services.cloud

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.viewModelScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.android.Auth
import com.dropbox.core.oauth.DbxCredential
import com.dropbox.core.v2.DbxClientV2
import io.github.zyrouge.symphony.Symphony
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

class DropboxService(private val symphony: Symphony) : Symphony.Hooks {
    private val applicationContext: Context get() = symphony.applicationContext
    private val sharedPreferences: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            applicationContext,
            "dropbox-credentials",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val _authState = MutableStateFlow<DropboxAuthState>(DropboxAuthState.Unauthenticated)
    val authState = _authState.asStateFlow()

    private var dropboxClient: DbxClientV2? = null
    private var isAwaitingAuthResult = false

    companion object {
        private const val DROPBOX_APP_KEY = "jgibk23zkucv2ec"
        private const val DROPBOX_CLIENT_IDENTIFIER = "Symphony Music Player"
        private const val CREDENTIAL_KEY = "credential"
        private const val TAG = "DropboxService"
    }

    override fun onSymphonyActivityReady() {
        super.onSymphonyActivityReady()
        Log.d(TAG, "onSymphonyActivityReady called")
        handleAuthenticationResult()
    }

    override fun onSymphonyReady() {
        super.onSymphonyReady()
        restorePreviousSession()
    }

    private fun restorePreviousSession() {
        Log.d(TAG, "Attempting to restore previous session")
        val credential = readCredential()
        if (credential != null) {
            Log.d(TAG, "Found stored credentials, initializing client")
            initializeClient(credential)
            fetchAccountInfo()
        } else {
            Log.d(TAG, "No stored credentials found")
        }
    }

    fun startAuthentication() {
        if (isAwaitingAuthResult) {
            Log.d(TAG, "Authentication already in progress")
            return
        }
        Log.d(TAG, "Starting authentication")
        _authState.value = DropboxAuthState.InProgress
        isAwaitingAuthResult = true

        try {
            val requestConfig = DbxRequestConfig(DROPBOX_CLIENT_IDENTIFIER)
            val scopes = listOf(
                "account_info.read",
                "files.content.write",
                "files.content.read"
            )
            Auth.startOAuth2PKCE(applicationContext, DROPBOX_APP_KEY, requestConfig, scopes)
            Log.d(TAG, "OAuth flow started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start OAuth flow", e)
            isAwaitingAuthResult = false
            _authState.value = DropboxAuthState.Error(e)
        }
    }

    private fun handleAuthenticationResult() {
        if (!isAwaitingAuthResult) {
            Log.d(TAG, "No pending authentication result")
            return
        }

        Log.d(TAG, "Handling authentication result")
        val authDbxCredential = Auth.getDbxCredential()
        isAwaitingAuthResult = false

        if (authDbxCredential != null) {
            Log.d(TAG, "Authentication successful, got credential")
            storeCredential(authDbxCredential)
            initializeClient(authDbxCredential)
            fetchAccountInfo()
        } else {
            Log.e(TAG, "Authentication failed: no credential returned")
            _authState.value = DropboxAuthState.Unauthenticated
        }
    }

    private fun storeCredential(credential: DbxCredential) {
        try {
            Log.d(TAG, "Storing credential")
            sharedPreferences.edit().apply {
                putString(CREDENTIAL_KEY, DbxCredential.Writer.writeToString(credential))
            }.apply()
            Log.d(TAG, "Credential stored successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to store credential", e)
        }
    }

    private fun readCredential(): DbxCredential? {
        val serializedCredential = sharedPreferences.getString(CREDENTIAL_KEY, null)
        return try {
            DbxCredential.Reader.readFully(serializedCredential)
        } catch (e: Exception) {
            null
        }
    }

    private fun initializeClient(credential: DbxCredential) {
        try {
            Log.d(TAG, "Initializing Dropbox client")
            dropboxClient = DbxClientV2(
                DbxRequestConfig(DROPBOX_CLIENT_IDENTIFIER),
                credential
            )
            Log.d(TAG, "Dropbox client initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Dropbox client", e)
            _authState.value = DropboxAuthState.Error(e)
        }
    }

    private fun fetchAccountInfo() {
        val client = dropboxClient ?: run {
            Log.e(TAG, "Dropbox client not initialized")
            _authState.value = DropboxAuthState.Error(Exception("Dropbox client not initialized"))
            return
        }

        symphony.viewModelScope.launch {
            try {
                var retryCount = 0
                var lastException: Exception? = null

                while (retryCount < 3) {
                    try {
                        Log.d(TAG, "Fetching account info, attempt ${retryCount + 1}")
                        val account = withContext(Dispatchers.IO) {
                            client.users().currentAccount
                        }
                        Log.d(TAG, "Successfully fetched account info")
                        _authState.value = DropboxAuthState.Authenticated(account)
                        return@launch
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to fetch account info: ${e.message}")
                        lastException = e
                        retryCount++
                        delay(1000L * retryCount)
                    }
                }

                Log.e(TAG, "All retry attempts failed")
                _authState.value = DropboxAuthState.Error(lastException ?: Exception("Failed to fetch account info"))
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error: ${e.message}")
                _authState.value = DropboxAuthState.Error(e)
            }
        }
    }

    fun logout() {
        Log.d(TAG, "Logging out")
        dropboxClient = null
        sharedPreferences.edit().remove(CREDENTIAL_KEY).apply()
        _authState.value = DropboxAuthState.Unauthenticated
    }
} 
