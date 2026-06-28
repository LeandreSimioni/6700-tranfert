package fr.simioni.a6700transfer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
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
    private const val CHANNEL_ID = "update"
    private const val NOTIF_CHECKING = 301
    private const val NOTIF_READY = 302

    fun check(context: Context) {
        Thread {
            try {
                val remote = fetchRemoteVersionCode()
                if (remote > BuildConfig.VERSION_CODE) {
                    downloadAndNotify(context, remote)
                }
            } catch (_: Exception) {}
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

    private fun downloadAndNotify(context: Context, remoteVersion: Int) {
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

        createChannel(context)

        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pi = PendingIntent.getActivity(
            context, 0, installIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.update_title))
            .setContentText(context.getString(R.string.update_text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_READY, notif)
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.update_channel),
                NotificationManager.IMPORTANCE_HIGH
            )
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }
}
