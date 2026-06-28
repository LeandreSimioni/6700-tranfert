package fr.simioni.a6700transfer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentUris
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
        const val MODE_MTP = "mtp"
        const val MODE_MSC = "msc"
        const val ACTION_TRANSFER_BROADCAST = "fr.simioni.a6700transfer.TRANSFER_UPDATE"
        const val EXTRA_BROADCAST_MSG = "msg"
        const val EXTRA_BROADCAST_DONE = "done"
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
        val sinceTimestamp = intent?.getLongExtra(EXTRA_SINCE_TIMESTAMP, -1L) ?: -1L
        if (sinceTimestamp == -1L) { stopSelf(); return START_NOT_STICKY }

        when (intent?.getStringExtra(EXTRA_MODE)) {
            MODE_MSC -> Thread { doMscTransfer(sinceTimestamp) }.start()
            else -> {
                val device: UsbDevice? =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        intent?.getParcelableExtra(EXTRA_USB_DEVICE, UsbDevice::class.java)
                    else
                        intent?.getParcelableExtra(EXTRA_USB_DEVICE)
                if (device == null) { stopSelf(); return START_NOT_STICKY }

                val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
                val connection = usbManager.openDevice(device) ?: run {
                    notifyAndLog(getString(R.string.notif_usb_error), done = true)
                    stopSelf(); return START_NOT_STICKY
                }
                val mtpDevice = MtpDevice(device)
                if (!mtpDevice.open(connection)) {
                    connection.close()
                    notifyAndLog(getString(R.string.notif_mtp_error), done = true)
                    stopSelf(); return START_NOT_STICKY
                }
                Thread { doMtpTransfer(mtpDevice, sinceTimestamp) }.start()
            }
        }
        return START_NOT_STICKY
    }

    // ── MTP ──────────────────────────────────────────────────────────────────

    private fun doMtpTransfer(mtpDevice: MtpDevice, sinceTimestamp: Long) {
        try {
            val storageIds: IntArray = mtpDevice.storageIds ?: run {
                notifyAndLog(getString(R.string.notif_no_storage), done = true); return
            }
            if (storageIds.isEmpty()) {
                notifyAndLog(getString(R.string.notif_no_storage), done = true); return
            }

            broadcast(getString(R.string.notif_scanning))
            val queue = mutableListOf<MtpObjectInfo>()
            for (storageId in storageIds) collectJpegs(mtpDevice, storageId, 0, sinceTimestamp, queue)
            queue.sortBy { it.dateCreated }

            if (queue.isEmpty()) {
                notifyAndLog(getString(R.string.notif_no_photos), done = true); return
            }

            var latest = sinceTimestamp
            var count = 0
            for (info in queue) {
                val temp = File(cacheDir, "mtp_${info.objectHandle}.jpg")
                try {
                    if (mtpDevice.importFile(info.objectHandle, temp.absolutePath)) {
                        processAndSave(temp, info.name ?: "IMG_${info.objectHandle}.jpg")
                        val date = ImageProcessor.getExifDate(temp).takeIf { it > 0 } ?: (info.dateCreated * 1000L)
                        if (date > latest) latest = date
                        count++
                        val msg = getString(R.string.notif_progress, count, queue.size)
                        updateNotification(msg)
                        broadcast(msg)
                    }
                } catch (_: Exception) {
                } finally { temp.delete() }
            }

            getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putLong(MainActivity.KEY_LAST_TRANSFER, latest).apply()
            notifyAndLog(getString(R.string.notif_done, count), done = true)
        } finally {
            mtpDevice.close()
            stopSelf()
        }
    }

    private fun collectJpegs(
        device: MtpDevice, storageId: Int, parentHandle: Int,
        sinceTimestamp: Long, result: MutableList<MtpObjectInfo>
    ) {
        val handles: IntArray = device.getObjectHandles(storageId, 0, parentHandle) ?: return
        for (handle in handles) {
            val info = device.getObjectInfo(handle) ?: continue
            when (info.format) {
                MtpConstants.FORMAT_ASSOCIATION ->
                    collectJpegs(device, storageId, handle, sinceTimestamp, result)
                MtpConstants.FORMAT_EXIF_JPEG ->
                    if (info.dateCreated * 1000L > sinceTimestamp) result.add(info)
            }
        }
    }

    // ── MSC ──────────────────────────────────────────────────────────────────

    private fun doMscTransfer(sinceTimestamp: Long) {
        try {
            broadcast(getString(R.string.notif_scanning))
            val count = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                copyViaMscMediaStore(sinceTimestamp)
            else
                copyViaMscDirect(sinceTimestamp)

            if (count == 0) {
                notifyAndLog(getString(R.string.notif_no_photos), done = true)
            } else {
                notifyAndLog(getString(R.string.notif_done, count), done = true)
            }
        } finally {
            stopSelf()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun copyViaMscMediaStore(sinceTimestamp: Long): Int {
        var count = 0
        var latest = sinceTimestamp
        val volumeNames = MediaStore.getExternalVolumeNames(this)
        for (volumeName in volumeNames) {
            if (volumeName == MediaStore.VOLUME_EXTERNAL_PRIMARY) continue
            val contentUri = MediaStore.Images.Media.getContentUri(volumeName)
            val cursor = contentResolver.query(
                contentUri,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_TAKEN),
                "${MediaStore.Images.Media.DATE_TAKEN} > ?",
                arrayOf(sinceTimestamp.toString()),
                "${MediaStore.Images.Media.DATE_TAKEN} ASC"
            ) ?: continue
            cursor.use {
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val total = cursor.count
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: continue
                    val dateTaken = cursor.getLong(dateCol)
                    val fileUri = ContentUris.withAppendedId(contentUri, id)
                    try {
                        val temp = File(cacheDir, "msc_$name")
                        contentResolver.openInputStream(fileUri)?.use { input ->
                            temp.outputStream().use { input.copyTo(it) }
                        }
                        processAndSave(temp, name)
                        temp.delete()
                        if (dateTaken > latest) latest = dateTaken
                        count++
                        val msg = getString(R.string.notif_progress, count, total)
                        updateNotification(msg)
                        broadcast(msg)
                    } catch (_: Exception) {}
                }
            }
        }
        if (latest > sinceTimestamp) {
            getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putLong(MainActivity.KEY_LAST_TRANSFER, latest).apply()
        }
        return count
    }

    private fun copyViaMscDirect(sinceTimestamp: Long): Int {
        var count = 0
        var latest = sinceTimestamp
        // Volumes externes : getExternalFilesDirs remonte jusqu'à la racine du volume
        getExternalFilesDirs(null).filterNotNull().forEach { appDir ->
            var root = appDir
            repeat(4) { root = root.parentFile ?: return@forEach }
            if (root.absolutePath == Environment.getExternalStorageDirectory().absolutePath) return@forEach
            val dcim = File(root, "DCIM")
            if (!dcim.exists()) return@forEach
            dcim.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in listOf("jpg", "jpeg") }
                .sortedBy { it.lastModified() }
                .forEach { file ->
                    if (file.lastModified() > sinceTimestamp) {
                        try {
                            processAndSave(file, file.name)
                            if (file.lastModified() > latest) latest = file.lastModified()
                            count++
                        } catch (_: Exception) {}
                    }
                }
        }
        if (latest > sinceTimestamp) {
            getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putLong(MainActivity.KEY_LAST_TRANSFER, latest).apply()
        }
        return count
    }

    // ── Commun ───────────────────────────────────────────────────────────────

    private fun processAndSave(src: File, originalName: String) {
        val outName = "A6700_$originalName"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val processed = File(cacheDir, "proc_$originalName")
            try {
                ImageProcessor.process(src, processed)
                insertIntoMediaStore(processed, outName)
            } finally { processed.delete() }
        } else {
            val destDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera"
            ).also { it.mkdirs() }
            val dest = File(destDir, outName)
            ImageProcessor.process(src, dest)
            MediaScannerConnection.scanFile(this, arrayOf(dest.absolutePath), null, null)
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
            ?: throw Exception("MediaStore insert failed")
        try {
            contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }
    }

    private fun notifyAndLog(msg: String, done: Boolean = false) {
        updateNotification(msg)
        TransferLog.add(this, msg)
        broadcast(msg, done)
    }

    private fun broadcast(msg: String, done: Boolean = false) {
        sendBroadcast(Intent(ACTION_TRANSFER_BROADCAST).apply {
            setPackage(packageName)
            putExtra(EXTRA_BROADCAST_MSG, msg)
            putExtra(EXTRA_BROADCAST_DONE, done)
        })
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(NotificationChannel(
                    CHANNEL_ID, getString(R.string.transfer_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ))
        }
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text).setOngoing(true).build()

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))
    }
}
