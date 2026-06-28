package fr.simioni.a6700transfer

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
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class TransferService : Service() {

    companion object {
        const val EXTRA_SINCE_TIMESTAMP = "since_timestamp"
        const val EXTRA_MODE = "mode"
        const val EXTRA_MOUNT_PATH = "mount_path"
        const val EXTRA_SAF_URI = "saf_uri"
        const val EXTRA_MAX_DIMENSION = "max_dimension"
        const val EXTRA_VOL_ID = "vol_id"
        const val MODE_MSC = "msc"
        const val ACTION_STATUS = "fr.simioni.a6700transfer.TRANSFER_STATUS"
        const val EXTRA_STATUS_TEXT = "status_text"
    }

    private data class DocEntry(val doc: DocumentFile, val name: String)

    private var maxDimension = 4096
    private var totalFiles = 0
    private var doneFiles = 0
    private var skippedFiles = 0
    private var volId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotifHelper.init(this)
        startForeground(NotifHelper.NOTIF_TRANSFER,
            buildProgressNotif(getString(R.string.notif_starting), 0, 0))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sinceTimestamp = intent?.getLongExtra(EXTRA_SINCE_TIMESTAMP, -1L) ?: -1L
        if (sinceTimestamp == -1L) { stopSelf(); return START_NOT_STICKY }
        maxDimension = intent?.getIntExtra(EXTRA_MAX_DIMENSION, 4096) ?: 4096
        volId = intent?.getStringExtra(EXTRA_VOL_ID)
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

    private fun broadcastStatus(text: String) {
        sendBroadcast(Intent(ACTION_STATUS).putExtra(EXTRA_STATUS_TEXT, text))
    }

    private fun readExifDate(uri: Uri): Long {
        return try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val dateStr = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                    ?: return@use 0L
                SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(dateStr)?.time ?: 0L
            } ?: 0L
        } catch (_: Exception) { 0L }
    }

    private fun alreadyExists(displayName: String, isImage: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val collection = if (isImage) MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                         else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val cursor = contentResolver.query(collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(displayName), null)
        val exists = (cursor?.count ?: 0) > 0
        cursor?.close()
        return exists
    }

    private fun doSafTransfer(rootUri: Uri, sinceTimestamp: Long) {
        try {
            broadcastStatus("Scan en cours...")
            updateProgress(getString(R.string.notif_scanning), 0, 0)
            val rootDoc = DocumentFile.fromTreeUri(this, rootUri)
            if (rootDoc == null || !rootDoc.canRead()) {
                val id = volId
                if (id != null) {
                    getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().remove("${MainActivity.KEY_SAF_URI_PREFIX}$id").apply()
                    startActivity(Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    })
                }
                log("[MSC] SAF: volume inaccessible - permission expiree, re-ouvrir l'app")
                broadcastStatus("Erreur: volume inaccessible — ouvrez l'app")
                updateProgress(getString(R.string.notif_no_storage), 0, 0)
                return
            }
            log("[MSC] SAF: volume ouvert - ${rootDoc.name}")
            val dcim = rootDoc.findFile("DCIM")
            if (dcim == null || !dcim.isDirectory) {
                log("[MSC] SAF: pas de dossier DCIM")
                broadcastStatus("Pas de dossier DCIM sur le Sony")
                updateProgress(getString(R.string.notif_no_photos), 0, 0)
                return
            }
            val entries = mutableListOf<DocEntry>()
            collectDocumentFiles(dcim, sinceTimestamp, entries)
            log("[MSC] ${entries.size} fichier(s) apres filtre date")
            broadcastStatus("${entries.size} photo(s) a transferer...")
            if (entries.isEmpty()) {
                // Mise a jour timestamp meme si rien a copier (evite de rescanner au prochain branchement)
                getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putLong(MainActivity.KEY_LAST_TRANSFER, System.currentTimeMillis()).apply()
                broadcastStatus("Aucune nouvelle photo")
                updateProgress(getString(R.string.notif_no_photos), 0, 0)
                return
            }
            totalFiles = entries.size; doneFiles = 0; skippedFiles = 0
            updateProgress(getString(R.string.notif_progress, 0, totalFiles), 0, totalFiles)
            for ((doc, name) in entries) {
                val displayName = "A6700_$name"
                val mime = mimeFor(name)
                if (alreadyExists(displayName, mime.startsWith("image/"))) {
                    skippedFiles++; log("[MSC] SKIP (deja copie): $name")
                    totalFiles = maxOf(0, totalFiles - 1); continue
                }
                try {
                    broadcastStatus("Copie $name ($doneFiles/$totalFiles)")
                    saveSafFile(doc, name); doneFiles++
                    log("[MSC] OK: $name")
                    updateProgress(getString(R.string.notif_progress, doneFiles, totalFiles), doneFiles, totalFiles)
                } catch (e: Exception) {
                    log("[MSC] ERREUR $name: ${e.message}")
                }
            }
            getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putLong(MainActivity.KEY_LAST_TRANSFER, System.currentTimeMillis()).apply()
            val summary = "Termine: $doneFiles copie(s), $skippedFiles deja present(s)"
            log("[MSC] $summary")
            broadcastStatus(summary)
            NotifHelper.showDone(this, doneFiles)
        } finally { stopSelf() }
    }

    private fun collectDocumentFiles(dir: DocumentFile, sinceTimestamp: Long, result: MutableList<DocEntry>) {
        for (child in dir.listFiles()) {
            if (child.isDirectory) { collectDocumentFiles(child, sinceTimestamp, result); continue }
            val name = child.name ?: continue
            if (!isMediaFile(name)) continue
            val ext = name.lowercase().substringAfterLast('.', "")
            val ts = when (ext) {
                "jpg", "jpeg", "arw", "raw" -> {
                    val exifTs = readExifDate(child.uri)
                    // Fallback to lastModified if EXIF unreadable
                    if (exifTs > 0L) exifTs else child.lastModified()
                }
                else -> child.lastModified()
            }
            when {
                ts == 0L -> {
                    // Can't determine date at all — include the file to avoid missing new photos
                    log("[MSC] Inclus (date illisible): $name")
                    result.add(DocEntry(child, name))
                }
                ts > sinceTimestamp -> result.add(DocEntry(child, name))
                // else: photo prise avant la coupure, on ignore
            }
        }
    }

    private fun saveSafFile(doc: DocumentFile, name: String) {
        val displayName = "A6700_$name"
        val mime = mimeFor(name)
        val isImage = mime.startsWith("image/")
        val temp = File(cacheDir, "saf_$name")
        try {
            contentResolver.openInputStream(doc.uri)?.use { it.copyTo(temp.outputStream()) }
                ?: throw Exception("impossible d'ouvrir $name")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val toInsert = if (mime == "image/jpeg" && maxDimension > 0) {
                    val processed = File(cacheDir, "proc_$name")
                    ImageProcessor.process(temp, processed, maxDimension); processed
                } else temp
                try { insertToMediaStore(toInsert, displayName, mime, isImage) }
                finally { if (toInsert != temp) toInsert.delete() }
            } else {
                val destDir = (if (isImage)
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera")
                else Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                ).also { it.mkdirs() }
                val dest = File(destDir, displayName)
                if (dest.exists()) return
                if (mime == "image/jpeg" && maxDimension > 0) ImageProcessor.process(temp, dest, maxDimension)
                else temp.copyTo(dest, overwrite = false)
                MediaScannerConnection.scanFile(this, arrayOf(dest.absolutePath), null, null)
            }
        } finally { temp.delete() }
    }

    private fun doMscTransfer(mountPath: String, sinceTimestamp: Long) {
        try {
            broadcastStatus("Scan dans $mountPath...")
            updateProgress(getString(R.string.notif_scanning), 0, 0)
            log("[MSC] Scan dans $mountPath")
            val dcim = File(mountPath, "DCIM")
            if (!dcim.exists()) { log("[MSC] Pas de DCIM"); broadcastStatus("Pas de DCIM"); updateProgress(getString(R.string.notif_no_photos), 0, 0); return }
            val files = mutableListOf<File>()
            collectMediaFiles(dcim, sinceTimestamp, files)
            log("[MSC] ${files.size} fichier(s) a copier")
            broadcastStatus("${files.size} photo(s) a transferer...")
            if (files.isEmpty()) {
                getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putLong(MainActivity.KEY_LAST_TRANSFER, System.currentTimeMillis()).apply()
                broadcastStatus("Aucune nouvelle photo")
                updateProgress(getString(R.string.notif_no_photos), 0, 0); return
            }
            totalFiles = files.size; doneFiles = 0; skippedFiles = 0
            updateProgress(getString(R.string.notif_progress, 0, totalFiles), 0, totalFiles)
            for (f in files) {
                val displayName = "A6700_${f.name}"
                val mime = mimeFor(f.name)
                if (alreadyExists(displayName, mime.startsWith("image/"))) {
                    skippedFiles++; log("[MSC] SKIP: ${f.name}")
                    totalFiles = maxOf(0, totalFiles - 1); continue
                }
                try {
                    broadcastStatus("Copie ${f.name} ($doneFiles/$totalFiles)")
                    saveMscFile(f); doneFiles++
                    log("[MSC] OK: ${f.name}")
                    updateProgress(getString(R.string.notif_progress, doneFiles, totalFiles), doneFiles, totalFiles)
                } catch (e: Exception) { log("[MSC] ERREUR ${f.name}: ${e.message}") }
            }
            getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putLong(MainActivity.KEY_LAST_TRANSFER, System.currentTimeMillis()).apply()
            val summary = "Termine: $doneFiles copie(s), $skippedFiles deja present(s)"
            log("[MSC] $summary")
            broadcastStatus(summary)
            NotifHelper.showDone(this, doneFiles)
        } finally { stopSelf() }
    }

    private fun collectMediaFiles(dir: File, sinceTimestamp: Long, result: MutableList<File>) {
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (f.isDirectory) { collectMediaFiles(f, sinceTimestamp, result); continue }
            if (isMediaFile(f.name) && f.lastModified() > sinceTimestamp) result.add(f)
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
            if (mime == "image/jpeg" && maxDimension > 0) {
                val processed = File(cacheDir, "proc_${src.name}")
                try { ImageProcessor.process(src, processed, maxDimension); insertToMediaStore(processed, displayName, mime, isImage) }
                finally { processed.delete() }
            } else insertToMediaStore(src, displayName, mime, isImage)
        } else {
            val destDir = (if (isImage)
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera")
            else Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            ).also { it.mkdirs() }
            val dest = File(destDir, displayName)
            if (dest.exists()) return
            if (mime == "image/jpeg" && maxDimension > 0) ImageProcessor.process(src, dest, maxDimension)
            else src.copyTo(dest, overwrite = false)
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

    private fun buildProgressNotif(text: String, progress: Int, max: Int): android.app.Notification {
        val builder = NotificationCompat.Builder(this, NotifHelper.CHANNEL_TRANSFER)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
        if (max > 0) { builder.setProgress(max, progress, false); builder.setSubText("$progress / $max") }
        else builder.setProgress(0, 0, true)
        return builder.build()
    }

    private fun updateProgress(text: String, progress: Int, max: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NotifHelper.NOTIF_TRANSFER, buildProgressNotif(text, progress, max))
    }
}
