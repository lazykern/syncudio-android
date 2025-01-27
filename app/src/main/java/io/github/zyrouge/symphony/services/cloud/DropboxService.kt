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
import com.dropbox.core.v2.files.Metadata
import io.github.zyrouge.symphony.Symphony
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.io.FileOutputStream

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
        private const val DROPBOX_CLIENT_IDENTIFIER = "Syncudio Music Player"
        private const val CREDENTIAL_KEY = "credential"
        private const val TAG = "DropboxService"
    }

    data class DropboxListFolderResult(
        val entries: List<com.dropbox.core.v2.files.Metadata>,
        val hasMore: Boolean,
        val cursor: String?
    )

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

    suspend fun listFolder(
        path: String,
        recursive: Boolean = false
    ): Result<DropboxListFolderResult> = withContext(Dispatchers.IO) {
        try {
            val client = dropboxClient ?: return@withContext Result.failure(
                Exception("Dropbox client not initialized")
            )

            Log.d(TAG, "Listing folder: $path (recursive: $recursive)")
            val result = client.files()
                .listFolderBuilder(path)
                .withRecursive(recursive)
                .start()
            
            return@withContext Result.success(
                DropboxListFolderResult(
                    entries = result.entries,
                    hasMore = result.hasMore,
                    cursor = result.cursor
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list folder: $path", e)
            return@withContext Result.failure(e)
        }
    }

    suspend fun listFolderContinue(cursor: String): Result<DropboxListFolderResult> = withContext(Dispatchers.IO) {
        try {
            val client = dropboxClient ?: return@withContext Result.failure(
                Exception("Dropbox client not initialized")
            )

            Log.d(TAG, "Continuing folder listing with cursor: $cursor")
            val result = client.files().listFolderContinue(cursor)
            
            return@withContext Result.success(
                DropboxListFolderResult(
                    entries = result.entries,
                    hasMore = result.hasMore,
                    cursor = result.cursor
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to continue listing folder", e)
            return@withContext Result.failure(e)
        }
    }

    suspend fun downloadFile(
        dropboxPath: String,
        localPath: String,
        progressCallback: ((Long, Long) -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val client = dropboxClient ?: return@withContext Result.failure(
                Exception("Dropbox client not initialized")
            )

            // Get file metadata first to know the size
            Log.d(TAG, "Getting file metadata: $dropboxPath")
            val metadata = client.files().getMetadata(dropboxPath)
            if (metadata !is com.dropbox.core.v2.files.FileMetadata) {
                return@withContext Result.failure(Exception("Not a file: $dropboxPath"))
            }
            val fileSize = metadata.size

            Log.d(TAG, "Downloading file: $dropboxPath to $localPath (size: $fileSize bytes)")
            FileOutputStream(localPath).use { outputStream ->
                client.files().downloadBuilder(dropboxPath)
                    .start()
                    .download(outputStream) { processedBytes ->
                        progressCallback?.invoke(processedBytes, fileSize)
                    }
            }
            
            Log.d(TAG, "Successfully downloaded file: $dropboxPath")
            return@withContext Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download file: $dropboxPath", e)
            return@withContext Result.failure(e)
        }
    }

    suspend fun uploadFile(
        localPath: String,
        dropboxPath: String,
        progressCallback: ((Long, Long) -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val client = dropboxClient ?: return@withContext Result.failure(
                Exception("Dropbox client not initialized")
            )

            val file = java.io.File(localPath)
            if (!file.exists()) {
                return@withContext Result.failure(Exception("Local file does not exist: $localPath"))
            }

            Log.d(TAG, "Uploading file: $localPath to $dropboxPath (size: ${file.length()} bytes)")
            val inputStream = java.io.FileInputStream(file)
            
            inputStream.use { input ->
                if (file.length() > 150 * 1024 * 1024) { // Files larger than 150MB
                    // Use upload session for large files
                    val sessionId = client.files().uploadSessionStart()
                        .uploadAndFinish(input) { l -> progressCallback?.invoke(l, file.length()) }
                        .sessionId

                    val cursor = com.dropbox.core.v2.files.UploadSessionCursor(
                        sessionId,
                        file.length()
                    )

                    val commitInfo = com.dropbox.core.v2.files.CommitInfo.newBuilder(dropboxPath)
                        .withMode(com.dropbox.core.v2.files.WriteMode.OVERWRITE)
                        .build()

                    client.files().uploadSessionFinish(cursor, commitInfo)
                        .uploadAndFinish(input) { l -> progressCallback?.invoke(l, file.length()) }
                } else {
                    // Use simple upload for small files
                    client.files().uploadBuilder(dropboxPath)
                        .withMode(com.dropbox.core.v2.files.WriteMode.OVERWRITE)
                        .uploadAndFinish(input) { l -> progressCallback?.invoke(l, file.length()) }
                }
            }

            Log.d(TAG, "Successfully uploaded file: $dropboxPath")
            return@withContext Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload file: $dropboxPath", e)
            return@withContext Result.failure(e)
        }
    }
}
