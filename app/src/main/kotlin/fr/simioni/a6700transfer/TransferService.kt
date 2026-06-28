package fr.simioni.a6700transfer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.MediaScannerConnection
import android.mtp.MtpConstants
import android.mtp.MtpDevice
import android.mtp.MtpObjectInfo
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import java.io.File

class TransferService : Service() {

    companion object {
        const val EXTRA_USB_DEVICE = "usb_device"
        const val EXTRA_SINCE_TIMESTAMP = "since_timestamp"
        const val EXTRA_MODE = "mode"
        const val EXTRA_MOUNT_PATH = "mount_path"
        const val MODE_MTP = "mtp"
        const val MODE_MSC = "msc"
        private const val NOTIF_ID = 100
        private const val CHANNEL_ID = "transfer"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification(getString(R.string.notif_starting)))
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_MSC
        val sinceTimestamp = intent?.getLongExtra(EXTRA_SINCE_TIMESTAMP, -1L) ?: -1L
        if (sinceTimestamp == -1L) { stopSelf(); return START_NOT_STICKY }

        val mountPath = intent?.getStringExtra(EXTRA_MOUNT_PATH)
        if (mountPath == null) { stopSelf(); return START_NOT_STICKY }
        Thread { doMscTransfer(mountPath, sinceTimestamp) }.start()
        return START_NOT_STICKY
    }

    private fun log(msg: String) = TransferLog.add(this, msg)

    private fun doMscTransfer(mountPath: String, sinceTimestamp: Long) {
        try {
            updateNotification(getString(R.string.notif_scanning))
            log("[MSC] Scan dans $mountPath")
            File(mountPath).list()?.take(30)?.let { entries ->
                log("[MSC] Contenu racine: ${entries.joinToString(", ")}")
            } ?: log("[MSC] Racine inaccessible (permission manquante?)")
            val dcim = File(mountPath, "DCIM")
            if (!dcim.exists()) {
                log("[MSC] Pas de dossier DCIM dans $mountPath")
                updateNotification(getString(R.string.notif_no_photos))
                return
            }
            val files = mutableListOf<File>()
            collectMediaFiles(dcim, sinceTimestamp, files)
            log("[MSC] Scan termine: ${files.size} fichier(s) a copier")
            if (files.isEmpty()) {
                updateNotification(getString(R.string.notif_no_photos))
                return
            }
            files.sortBy { it.lastModified() }
            var count = 0
            var latestTs = sinceTimestamp
            for (f in files) {
                try {
                    saveMscFile(f)
                    if (f.lastModified() > latestTs) latestTs = f.lastModified()
                    count++
                    log("[MSC] OK: ${f.name}")
                    updateNotification(getString(R.string.notif_progress, count, files.size))
                } catch (e: Exception) {
                    log("[MSC] ERREUR ${f.name}: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
            getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putLong(MainActivity.KEY_LAST_TRANSFER, latestTs).apply()
            log("[MSC] Termine: $count fichier(s) copie(s)")
            updateNotification(getString(R.string.notif_done, count))
        } finally {
            stopSelf()
        }
    }

    private fun collectMediaFiles(dir: File, sinceTimestamp: Long, result: MutableList<File>) {
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (f.isDirectory) collectMediaFiles(f, sinceTimestamp, result)
            else if (f.lastModified() > sinceTimestamp && isMediaFile(f.name)) result.add(f)
        }
    }

    private fun isMediaFile(name: String): Boolean {
        val ext = name.lowercase().substringAfterLast('.', "")
        return ext in setOf("jpg", "jpeg", "arw", "raw", "mp4", "mov", "png", "heic")
    }

    private fun mimeFor(name: String): String = when (name.lowercase().substringAfterLast('.', "")) {
        "jpg", "jpeg" -> "image/jpeg"
        "arw" -> "image/x-sony-arw"
        "raw" -> "image/x-raw"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "png" -> "image/png"
        "heic" -> "image/heic"
        else -> "application/octet-stream"
    }

    private fun saveMscFile(src: File) {
        val displayName = "A6700_${src.name}"
        val mime = mimeFor(src.name)
        val isImage = mime.startsWith("image/")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (mime == "image/jpeg") {
                val processed = File(cacheDir, "proc_${src.name}")
                try {
                    ImageProcessor.process(src, processed)
                    insertToMediaStore(processed, displayName, mime, isImage)
                } finally { processed.delete() }
            } else {
                insertToMediaStore(src, displayName, mime, isImage)
            }
        } else {
            val destDir = (if (isImage)
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera")
            else
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            ).also { it.mkdirs() }
            val dest = File(destDir, displayName)
            if (mime == "image/jpeg") ImageProcessor.process(src, dest)
            else src.copyTo(dest, overwrite = true)
            MediaScannerConnection.scanFile(this, arrayOf(dest.absolutePath), null, null)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun insertToMediaStore(file: File, displayName: String, mime: String, isImage: Boolean) {
        val collection = if (isImage) MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val relPath = if (isImage) "${Environment.DIRECTORY_DCIM}/Camera" else Environment.DIRECTORY_MOVIES
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(collection, values)
            ?: throw Exception("MediaStore insert failed: $displayName")
        try {
            contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null); throw e
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, getString(R.string.transfer_channel_name),
                NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
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
