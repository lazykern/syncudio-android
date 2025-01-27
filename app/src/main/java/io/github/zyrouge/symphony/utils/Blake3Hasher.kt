package io.github.zyrouge.symphony.utils

import io.lktk.NativeBLAKE3
import java.io.File
import java.io.FileInputStream

object Blake3Hasher {
    fun hashFile(file: File): String {
        return FileInputStream(file).use { fis ->
            val hasher = NativeBLAKE3()
            hasher.initDefault()
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                if (bytesRead == buffer.size) {
                    hasher.update(buffer)
                } else {
                    hasher.update(buffer.copyOfRange(0, bytesRead))
                }
            }
            val hash = hasher.getOutput()
            hasher.close()
            hash.joinToString("") { "%02x".format(it) }
        }
    }

    fun hashFileAsync(file: File, onComplete: (String) -> Unit) {
        Thread {
            try {
                val hash = hashFile(file)
                onComplete(hash)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }
} 