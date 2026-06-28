package fr.simioni.a6700transfer

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {

    private const val VERSION_URL =
        "https://raw.githubusercontent.com/LeandreSimioni/6700-tranfert/main/version.properties"
    private const val APK_URL =
        "https://github.com/LeandreSimioni/6700-tranfert/releases/download/latest-build/6700-transfer-debug.apk"

    /** Verification silencieuse au lancement — installe via notification si mise a jour dispo */
    fun check(context: Context) {
        Thread {
            try {
                val remote = fetchRemoteVersionCode()
                if (remote > BuildConfig.VERSION_CODE) {
                    val apk = download(context, remote)
                    launchInstall(context, apk)
                }
            } catch (_: Exception) {}
        }.start()
    }

    /**
     * Verification manuelle depuis le bouton UI.
     * [onStatus] est appele sur le main thread avec un texte d'etat.
     * [onInstall] est appele quand l'APK est pret — l'appelant lance l'install.
     */
    fun checkManual(
        context: Context,
        onStatus: (String) -> Unit,
        onInstall: (Intent) -> Unit
    ) {
        val ui = Handler(Looper.getMainLooper())
        Thread {
            ui.post { onStatus(context.getString(R.string.update_checking)) }
            try {
                val remote = fetchRemoteVersionCode()
                if (remote <= BuildConfig.VERSION_CODE) {
                    ui.post { onStatus(context.getString(R.string.update_up_to_date, BuildConfig.VERSION_NAME)) }
                    return@Thread
                }
                ui.post { onStatus(context.getString(R.string.update_downloading)) }
                val apk = download(context, remote)
                val intent = buildInstallIntent(context, apk)
                ui.post {
                    onStatus(context.getString(R.string.update_ready))
                    onInstall(intent)
                }
            } catch (_: Exception) {
                ui.post { onStatus(context.getString(R.string.update_error)) }
            }
        }.start()
    }

    private fun fetchRemoteVersionCode(): Int {
        val conn = URL(VERSION_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 5_000
        return try {
            conn.inputStream.bufferedReader().readLines()
                .firstOrNull { it.startsWith("versionCode=") }
                ?.removePrefix("versionCode=")?.trim()?.toIntOrNull() ?: 0
        } finally {
            conn.disconnect()
        }
    }

    private fun download(context: Context, remoteVersion: Int): File {
        val apkFile = File(context.getExternalFilesDir(null), "6700-update-v$remoteVersion.apk")
        val conn = URL(APK_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 120_000
        try {
            conn.inputStream.use { input ->
                FileOutputStream(apkFile).use { input.copyTo(it) }
            }
        } finally {
            conn.disconnect()
        }
        return apkFile
    }

    private fun buildInstallIntent(context: Context, apkFile: File): Intent {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apkFile
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    fun launchInstall(context: Context, apkFile: File) {
        context.startActivity(buildInstallIntent(context, apkFile))
    }
}
