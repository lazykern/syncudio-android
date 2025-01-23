package me.lazykern.symphony.cloudsync.provider.dropbox

import android.content.Context
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.v2.DbxClientV2
import me.lazykern.symphony.cloudsync.auth.CloudAuthManager
import me.lazykern.symphony.cloudsync.model.CloudFile
import me.lazykern.symphony.cloudsync.model.CloudStorageAccount
import me.lazykern.symphony.cloudsync.model.CloudStorageQuota
import me.lazykern.symphony.cloudsync.provider.base.BaseCloudProvider
import java.io.InputStream
import java.io.OutputStream

class DropboxProvider(
    context: Context,
    appKey: String
) : BaseCloudProvider() {
    override val providerId: String = "dropbox"
    override val providerName: String = "Dropbox"
    override val authManager: CloudAuthManager = DropboxAuthManager(context, appKey)

    private val requestConfig = DbxRequestConfig
        .newBuilder("Symphony/1.0")
        .withAutoRetryEnabled()
        .build()

    private suspend fun getClient(): DbxClientV2? {
        val accessToken = authManager.getAccessToken() ?: return null
        return DbxClientV2(requestConfig, accessToken)
    }

    override suspend fun getAccount(): CloudStorageAccount? {
        val client = getClient() ?: return null
        return try {
            val account = client.users().currentAccount
            val space = client.users().spaceUsage
            CloudStorageAccount(
                id = account.accountId,
                name = account.name.displayName,
                email = account.email,
                provider = providerId,
                quota = CloudStorageQuota(
                    total = space.allocation.individualValue?.allocated ?: 0,
                    used = space.used
                )
            )
        } catch (e: Exception) {
            null
        }
    }

    // Placeholder implementations - we'll implement these later
    override suspend fun listFiles(path: String): List<CloudFile> = emptyList()
    override suspend fun getFile(path: String): CloudFile? = null
    override suspend fun downloadFile(file: CloudFile, outputStream: OutputStream) {}
    override suspend fun uploadFile(path: String, inputStream: InputStream): CloudFile {
        throw UnsupportedOperationException("Not implemented yet")
    }
    override suspend fun deleteFile(path: String) {}
    override suspend fun searchFiles(query: String): List<CloudFile> = emptyList()
    override suspend fun getChanges(cursor: String?): Pair<List<CloudFile>, String> = Pair(emptyList(), "")
    override suspend fun watchChanges(path: String, callback: (List<CloudFile>) -> Unit) {}
} 