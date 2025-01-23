package me.lazykern.symphony.cloudsync

import android.content.Context
import me.lazykern.symphony.cloudsync.auth.CloudAuthManager
import me.lazykern.symphony.cloudsync.model.CloudFile
import me.lazykern.symphony.cloudsync.model.CloudStorageAccount
import java.io.InputStream
import java.io.OutputStream

interface CloudStorageProvider {
    val providerId: String
    val providerName: String
    val authManager: CloudAuthManager

    suspend fun getAccount(): CloudStorageAccount?
    
    // File operations
    suspend fun listFiles(path: String): List<CloudFile>
    suspend fun getFile(path: String): CloudFile?
    suspend fun downloadFile(file: CloudFile, outputStream: OutputStream)
    suspend fun uploadFile(path: String, inputStream: InputStream): CloudFile
    suspend fun deleteFile(path: String)
    
    // Search and filtering
    suspend fun searchFiles(query: String): List<CloudFile>
    suspend fun getAudioFiles(path: String): List<CloudFile>
    
    // Sync operations
    suspend fun getChanges(cursor: String?): Pair<List<CloudFile>, String>
    suspend fun watchChanges(path: String, callback: (List<CloudFile>) -> Unit)
    suspend fun stopWatchingChanges()

    companion object {
        const val MIME_TYPE_AUDIO = "audio/*"
    }
} 