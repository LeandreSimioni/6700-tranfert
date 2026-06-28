package fr.simioni.a6700transfer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import java.io.File

class TransferService : Service() {

    companion object {
        const val EXTRA_USB_DEVICE = "usb_device"
        const val EXTRA_SINCE_TIMESTAMP = "since_timestamp"
        const val EXTRA_MODE = "mode"
        const val EXTRA_MOUNT_PATH = "mount_path"
        const val EXTRA_SAF_URI = "saf_uri"
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sinceTimestamp = intent?.getLongExtra(EXTRA_SINCE_TIMESTAMP, -1L) ?: -1L
        if (sinceTimestamp == -1L) { stopSelf(); return START_NOT_STICKY }

        val safUriStr = intent?.getStringExtra(EXTRA_SAF_URI)
        val mountPath = intent?.getStringExtra(EXTRA_MOUNT_PATH)

        when {
            safUriStr != null -> Thread { doSafTransfer(Uri.parse(safUriStr), sinceTimestamp) }.start()
            mountPath != null -> Thread { doMscTransfer(mountPath, sinceTimestamp) }.start()
            else -> { stopSelf(); return START_NOT_STICKY }
        }
        return START_NOT_STICKY
    }

    private fun log(msg: String) = TransferLog.add(this, msg)

    // SAF-based transfer using DocumentFile
    private fun doSafTransfer(rootUri: Uri, sinceTimestamp: Long) {
        try {
            updateNotification(getString(R.string.notif_scanning))
            val rootDoc = DocumentFile.fromTreeUri(this, rootUri)
            if (rootDoc == null || !rootDoc.canRead()) {
                log("[MSC] SAF: volume inaccessible (permission expiree? re-accorder dans l'app)")
                updateNotification(getString(R.string.notif_no_storage))
                return
            }
            log("[MSC] SAF: volume ouvert OK - ${rootDoc.name}")
            val dcim = rootDoc.findFile("DCIM")
            if (dcim == null || !dcim.isDirectory) {
                log("[MSC] SAF: pas de dossier DCIM")
                updateNotification(getString(R.string.notif_no_photos))
                return
            }
            val files = mutableListOf<DocumentFile>()
            collectDocumentFiles(dcim, sinceTimestamp, files)
            log("[MSC] SAF: ${files.size} fichier(s) a copier")
            if (files.isEmpty()) { updateNotification(getString(R.string.notif_no_photos)); return }
            files.sortBy { it.lastModified() }
            var count = 0
            var latestTs = sinceTimestamp
            for (f in files) {
                try {
                    saveSafFile(f)
                    val ts = f.lastModified()
                    if (ts > latestTs) latestTs = ts
                    count++
                    log("[MSC] OK: ${f.name}")
                    updateNotification(getString(R.string.notif_progress, count, files.size))
                } catch (e: Exception) {
                    log("[MSC] ERREUR ${f.name}: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
            if (latestTs > sinceTimestamp) {
                getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putLong(MainActivity.KEY_LAST_TRANSFER, latestTs).apply()
            }
            log("[MSC] Termine: $count fichier(s) copie(s)")
            updateNotification(getString(R.string.notif_done, count))
        } finally { stopSelf() }
    }

    private fun collectDocumentFiles(dir: DocumentFile, sinceTimestamp: Long, result: MutableList<DocumentFile>) {
        for (child in dir.listFiles()) {
            if (child.isDirectory) collectDocumentFiles(child, sinceTimestamp, result)
            else {
                val name = child.name ?: continue
                if (!isMediaFile(name)) continue
                val ts = child.lastModified()
                // include if ts unknown (0) or newer than sinceTimestamp
                if (ts == 0L || ts > sinceTimestamp) result.add(child)
            }
        }
    }

    private fun saveSafFile(doc: DocumentFile) {
        val name = doc.name ?: throw Exception("nom de fichier null")
        val displayName = "A6700_$name"
        val mime = mimeFor(name)
        val isImage = mime.startsWith("image/")
        val inputStream = contentResolver.openInputStream(doc.uri)
            ?: throw Exception("impossible d'ouvrir $name")
        // Copy to temp file first (needed for ImageProcessor and legacy path)
        val temp = File(cacheDir, "saf_$name")
        try {
            inputStream.use { it.copyTo(temp.outputStream()) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val toInsert = if (mime == "image/jpeg") {
                    val processed = File(cacheDir, "proc_$name")
                    ImageProcessor.process(temp, processed)
                    processed
                } else temp
                try { insertToMediaStore(toInsert, displayName, mime, isImage) }
                finally { if (toInsert != temp) toInsert.delete() }
            } else {
                val destDir = (if (isImage)
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera")
                else
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                ).also { it.mkdirs() }
                val dest = File(destDir, displayName)
                if (mime == "image/jpeg") ImageProcessor.process(temp, dest)
                else temp.copyTo(dest, overwrite = true)
                MediaScannerConnection.scanFile(this, arrayOf(dest.absolutePath), null, null)
            }
        } finally { temp.delete() }
    }

    // File-based transfer (fallback)
    private fun doMscTransfer(mountPath: String, sinceTimestamp: Long) {
        try {
            updateNotification(getString(R.string.notif_scanning))
            log("[MSC] Scan dans $mountPath")
            File(mountPath).list()?.take(20)?.let { log("[MSC] Racine: ${it.joinToString(", ")}") }
                ?: log("[MSC] Racine inaccessible (permission manquante?)")
            val dcim = File(mountPath, "DCIM")
            if (!dcim.exists()) { log("[MSC] Pas de dossier DCIM dans $mountPath"); updateNotification(getString(R.string.notif_no_photos)); return }
            val files = mutableListOf<File>()
            collectMediaFiles(dcim, sinceTimestamp, files)
            log("[MSC] ${files.size} fichier(s) a copier")
            if (files.isEmpty()) { updateNotification(getString(R.string.notif_no_photos)); return }
            files.sortBy { it.lastModified() }
            var count = 0; var latestTs = sinceTimestamp
            for (f in files) {
                try {
                    saveMscFile(f)
                    if (f.lastModified() > latestTs) latestTs = f.lastModified()
                    count++; log("[MSC] OK: ${f.name}")
                    updateNotification(getString(R.string.notif_progress, count, files.size))
                } catch (e: Exception) { log("[MSC] ERREUR ${f.name}: ${e.javaClass.simpleName}: ${e.message}") }
            }
            getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putLong(MainActivity.KEY_LAST_TRANSFER, latestTs).apply()
            log("[MSC] Termine: $count fichier(s)"); updateNotification(getString(R.string.notif_done, count))
        } finally { stopSelf() }
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
                try { ImageProcessor.process(src, processed); insertToMediaStore(processed, displayName, mime, isImage) }
                finally { processed.delete() }
            } else insertToMediaStore(src, displayName, mime, isImage)
        } else {
            val destDir = (if (isImage)
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera")
            else Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            ).also { it.mkdirs() }
            val dest = File(destDir, displayName)
            if (mime == "image/jpeg") ImageProcessor.process(src, dest) else src.copyTo(dest, overwrite = true)
            MediaScannerConnection.scanFile(this, arrayOf(dest.absolutePath), null, null)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun insertToMediaStore(file: File, displayName: String, mime: String, isImage: Boolean) {
        val collection = if (isImage) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val relPath = if (isImage) "${Environment.DIRECTORY_DCIM}/Camera" else Environment.DIRECTORY_MOVIES
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(collection, values) ?: throw Exception("MediaStore insert failed")
        try {
            contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } catch (e: Exception) { contentResolver.delete(uri, null, null); throw e }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, getString(R.string.transfer_channel_name), NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }
    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_upload).setContentTitle(getString(R.string.app_name))
        .setContentText(text).setOngoing(true).build()
    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification(text))
    }
}
