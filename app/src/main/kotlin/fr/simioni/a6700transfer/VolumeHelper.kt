package fr.simioni.a6700transfer

import android.content.Context
import android.os.Build
import android.os.storage.StorageManager

object VolumeHelper {

    /**
     * Returns list of volIds (e.g. "4A21-0000") for removable USB volumes.
     * Uses getExternalFilesDirs as primary method (works on all APIs),
     * falls back to StorageManager for extra coverage.
     */
    fun findRemovableVolumes(context: Context): List<String> {
        val ids = mutableSetOf<String>()

        // Method 1: getExternalFilesDirs - most reliable for USB OTG
        context.getExternalFilesDirs(null)
            .drop(1) // skip primary internal storage
            .filterNotNull()
            .forEach { dir ->
                // Path: /storage/XXXX-XXXX/Android/data/pkg/files
                val parts = dir.absolutePath.split("/")
                if (parts.size > 2) {
                    val volId = parts[2] // e.g. "4A21-0000"
                    if (volId != "emulated" && volId.isNotEmpty()) {
                        ids.add(volId)
                    }
                }
            }

        // Method 2: StorageManager (Android 11+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val sm = context.getSystemService(StorageManager::class.java)
            sm.storageVolumes
                .filter { !it.isPrimary && it.isRemovable }
                .mapNotNull { vol ->
                    vol.directory?.absolutePath?.substringAfterLast("/")
                }
                .filter { it.isNotEmpty() }
                .forEach { ids.add(it) }
        }

        return ids.toList()
    }

    fun findRemovableVolumePaths(context: Context): List<Pair<String, String>> {
        return findRemovableVolumes(context).map { volId ->
            Pair(volId, "/storage/$volId")
        }
    }
}
