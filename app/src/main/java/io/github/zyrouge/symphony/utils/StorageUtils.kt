package io.github.zyrouge.symphony.utils

import android.content.Context
import android.os.StatFs
import java.io.File

object StorageUtils {
    data class StorageInfo(
        val totalSpace: Long,
        val freeSpace: Long,
        val usedSpace: Long
    ) {
        fun formatSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
            return String.format(
                "%.1f %s",
                bytes / Math.pow(1024.0, digitGroups.toDouble()),
                units[digitGroups]
            )
        }

        val formattedTotalSpace get() = formatSize(totalSpace)
        val formattedFreeSpace get() = formatSize(freeSpace)
        val formattedUsedSpace get() = formatSize(usedSpace)
    }

    fun getStorageInfo(context: Context): StorageInfo {
        val path = context.getExternalFilesDir(null)?.path ?: context.filesDir.path
        val stat = StatFs(path)
        val blockSize = stat.blockSizeLong
        val totalBlocks = stat.blockCountLong
        val availableBlocks = stat.availableBlocksLong
        
        val total = totalBlocks * blockSize
        val free = availableBlocks * blockSize
        val used = total - free

        return StorageInfo(total, free, used)
    }
} 