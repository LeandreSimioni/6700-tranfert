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
        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent?.getParcelableExtra(EXTRA_USB_DEVICE, UsbDevice::class.java)
        else
            intent?.getParcelableExtra(EXTRA_USB_DEVICE)

        val sinceTimestamp = intent?.getLongExtra(EXTRA_SINCE_TIMESTAMP, -1L) ?: -1L
        if (device == null || sinceTimestamp == -1L) { stopSelf(); return START_NOT_STICKY }

        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val connection = usbManager.openDevice(device) ?: run {
            updateNotification(getString(R.string.notif_usb_error))
            stopSelf(); return START_NOT_STICKY
        }

        val mtpDevice = MtpDevice(device)
        if (!mtpDevice.open(connection)) {
            connection.close()
            updateNotification(getString(R.string.notif_mtp_error))
            stopSelf(); return START_NOT_STICKY
        }

        Thread { doTransfer(mtpDevice, sinceTimestamp) }.start()
        return START_NOT_STICKY
    }

    private fun doTransfer(mtpDevice: MtpDevice, sinceTimestamp: Long) {
        try {
            val storageIds = mtpDevice.storageIds
            if (storageIds.isNullOrEmpty()) {
                updateNotification(getString(R.string.notif_no_storage))
                return
            }

            updateNotification(getString(R.string.notif_scanning))
            val queue = mutableListOf<MtpObjectInfo>()
            for (storageId in storageIds) {
                collectJpegs(mtpDevice, storageId, 0, sinceTimestamp, queue)
            }
            queue.sortBy { it.dateCreated }

            if (queue.isEmpty()) {
                updateNotification(getString(R.string.notif_no_photos))
                return
            }

            var latestTimestamp = sinceTimestamp
            var count = 0

            for (info in queue) {
                val temp = File(cacheDir, "mtp_${info.objectHandle}.jpg")
                try {
                    if (mtpDevice.importFile(info.objectHandle, temp.absolutePath)) {
                        processAndSave(temp, info.name ?: "IMG_${info.objectHandle}.jpg")
                        val date = ImageProcessor.getExifDate(temp).takeIf { it > 0 }
                            ?: (info.dateCreated * 1000L)
                        if (date > latestTimestamp) latestTimestamp = date
                        count++
                        updateNotification(getString(R.string.notif_progress, count, queue.size))
                    }
                } catch (_: Exception) {
                } finally {
                    temp.delete()
                }
            }

            getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putLong(MainActivity.KEY_LAST_TRANSFER, latestTimestamp).apply()

            updateNotification(getString(R.string.notif_done, count))
        } finally {
            mtpDevice.close()
            stopSelf()
        }
    }

    private fun collectJpegs(
        device: MtpDevice, storageId: Int, parentHandle: Int,
        sinceTimestamp: Long, result: MutableList<MtpObjectInfo>
    ) {
        val handles = device.getObjectHandles(storageId, 0, parentHandle) ?: return
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

    private fun processAndSave(src: File, originalName: String) {
        val outName = "A6700_$originalName"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val processed = File(cacheDir, "proc_$originalName")
            try {
                ImageProcessor.process(src, processed)
                insertIntoMediaStore(processed, outName)
            } finally {
                processed.delete()
            }
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
