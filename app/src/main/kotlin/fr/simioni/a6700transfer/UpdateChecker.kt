package fr.simioni.a6700transfer

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import java.net.URL

object UpdateChecker {
    private const val VERSION_URL =
        "https://raw.githubusercontent.com/LeandreSimioni/6700-tranfert/main/version.properties"
    private const val APK_URL =
        "https://github.com/LeandreSimioni/6700-tranfert/releases/download/latest-build/6700-transfer-debug.apk"

    fun check(currentCode: Int): UpdateResult {
        return try {
            val text = URL(VERSION_URL).readText(Charsets.UTF_8)
            val remoteCode = text.lines()
                .firstOrNull { it.startsWith("versionCode=") }
                ?.removePrefix("versionCode=")
                ?.trim()?.toIntOrNull()
                ?: return UpdateResult.Error("versionCode introuvable dans $VERSION_URL")
            if (remoteCode <= currentCode) UpdateResult.UpToDate(currentCode)
            else UpdateResult.UpdateAvailable(remoteCode)
        } catch (e: Exception) {
            UpdateResult.Error("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun downloadApk(context: Context) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val req = DownloadManager.Request(Uri.parse(APK_URL))
            .setTitle("6700 Transfer mise à jour")
            .setDescription("Téléchargement en cours...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "6700-transfer.apk")
            .setMimeType("application/vnd.android.package-archive")
        dm.enqueue(req)
    }
}

sealed class UpdateResult {
    data class UpToDate(val code: Int) : UpdateResult()
    data class UpdateAvailable(val remoteCode: Int) : UpdateResult()
    data class Error(val message: String) : UpdateResult()
}
