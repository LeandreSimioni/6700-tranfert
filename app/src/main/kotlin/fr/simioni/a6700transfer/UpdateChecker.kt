package fr.simioni.a6700transfer

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import java.net.URL

object UpdateChecker {
    private const val VERSION_URL =
        "https://raw.githubusercontent.com/LeandreSimioni/6700-tranfert/main/version.properties"
    const val APK_URL =
        "https://github.com/LeandreSimioni/6700-tranfert/releases/download/latest-build/6700-transfer-debug.apk"
    const val PREF_DOWNLOAD_ID = "update_download_id"
    const val APK_FILENAME = "6700-transfer-update.apk"

    fun check(currentCode: Int): UpdateResult {
        return try {
            val text = URL(VERSION_URL).readText(Charsets.UTF_8)
            val remoteCode = text.lines()
                .firstOrNull { it.startsWith("versionCode=") }
                ?.removePrefix("versionCode=")
                ?.trim()?.toIntOrNull()
                ?: return UpdateResult.Error("versionCode introuvable")
            if (remoteCode <= currentCode) UpdateResult.UpToDate(currentCode)
            else UpdateResult.UpdateAvailable(remoteCode)
        } catch (e: Exception) {
            UpdateResult.Error("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun downloadApk(context: Context): Long {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        // Cancel any previous download
        val oldId = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(PREF_DOWNLOAD_ID, -1L)
        if (oldId != -1L) dm.remove(oldId)

        val req = DownloadManager.Request(Uri.parse(APK_URL))
            .setTitle("6700 Transfer — mise à jour")
            .setDescription("Téléchargement en cours...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, APK_FILENAME)
            .setMimeType("application/vnd.android.package-archive")
        val downloadId = dm.enqueue(req)
        context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putLong(PREF_DOWNLOAD_ID, downloadId).apply()
        return downloadId
    }

    /** Returns (downloaded bytes, total bytes, status) */
    fun queryProgress(context: Context, downloadId: Long): Triple<Long, Long, Int> {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = dm.query(DownloadManager.Query().setFilterById(downloadId))
        return if (cursor != null && cursor.moveToFirst()) {
            val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            cursor.close()
            Triple(downloaded, total, status)
        } else {
            cursor?.close()
            Triple(0L, 0L, DownloadManager.STATUS_FAILED)
        }
    }
}

sealed class UpdateResult {
    data class UpToDate(val code: Int) : UpdateResult()
    data class UpdateAvailable(val remoteCode: Int) : UpdateResult()
    data class Error(val message: String) : UpdateResult()
}
