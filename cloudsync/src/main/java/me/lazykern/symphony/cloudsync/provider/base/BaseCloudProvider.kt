package me.lazykern.symphony.cloudsync.provider.base

import me.lazykern.symphony.cloudsync.CloudStorageProvider
import me.lazykern.symphony.cloudsync.model.CloudFile

abstract class BaseCloudProvider : CloudStorageProvider {
    protected var changeWatchJob: kotlinx.coroutines.Job? = null

    override suspend fun getAudioFiles(path: String): List<CloudFile> {
        return listFiles(path).filter { 
            it.mimeType?.startsWith("audio/") == true 
        }
    }

    override suspend fun stopWatchingChanges() {
        changeWatchJob?.cancel()
        changeWatchJob = null
    }

    protected fun isAudioFile(mimeType: String?): Boolean {
        return mimeType?.startsWith("audio/") == true
    }
} 