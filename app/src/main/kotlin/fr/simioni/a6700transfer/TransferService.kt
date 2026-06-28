package fr.simioni.a6700transfer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import java.io.File

class TransferService : Service() {

    companion object {
        const val EXTRA_MOUNT_PATH = "mount_path"
        const val EXTRA_SINCE_TIMESTAMP = "since_timestamp"
        private const val NOTIF_ID = 100
        private const val CHANNEL_ID = "transfer"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification(getString(R.string.notif_starting)))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mountPath = intent?.getStringExtra(EXTRA_MOUNT_PATH)
        val sinceTimestamp = intent?.getLongExtra(EXTRA_SINCE_TIMESTAMP, -1L) ?: -1L
        if (mountPath == null || sinceTimestamp == -1L) {
            stopSelf(); return START_NOT_STICKY
        }
        Thread { doTransfer(mountPath, sinceTimestamp) }.start()
        return START_NOT_STICKY
    }

    private fun doTransfer(mountPath: String, sinceTimestamp: Long) {
        val dcimDir = File(mountPath, "DCIM")
        if (!dcimDir.exists()) {
            updateNotification(getString(R.string.notif_no_dcim))
            stopSelf(); return
        }

        val queue = dcimDir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in listOf("jpg", "jpeg") }
            .filter { ImageProcessor.getExifDate(it) > sinceTimestamp }
            .sortedBy { ImageProcessor.getExifDate(it) }
            .toList()

        if (queue.isEmpty()) {
            updateNotification(getString(R.string.notif_no_photos))
            stopSelf(); return
        }

        var latestTimestamp = sinceTimestamp
        var count = 0

        for (file in queue) {
            try {
                processAndSave(file)
                val exifDate = ImageProcessor.getExifDate(file)
                if (exifDate > latestTimestamp) latestTimestamp = exifDate
                count++
                updateNotification(
                    getString(R.string.notif_progress, count, queue.size)
                )
            } catch (_: Exception) {
                // fichier ignoré, on continue
            }
        }

        getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putLong(MainActivity.KEY_LAST_TRANSFER, latestTimestamp).apply()

        updateNotification(getString(R.string.notif_done, count))
        stopSelf()
    }

    private fun processAndSave(srcFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val temp = File(cacheDir, "transfer_${srcFile.name}")
            try {
                ImageProcessor.process(srcFile, temp)
                insertIntoMediaStore(temp, "A6700_${srcFile.name}")
            } finally {
                temp.delete()
            }
        } else {
            val destDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                "Camera"
            ).also { it.mkdirs() }
            val destFile = File(destDir, "A6700_${srcFile.name}")
            ImageProcessor.process(srcFile, destFile)
            MediaScannerConnection.scanFile(this, arrayOf(destFile.absolutePath), null, null)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun insertIntoMediaStore(file: File, displayName: String) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Camera")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw Exception("MediaStore insert failed for $displayName")
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.transfer_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))
    }
}
