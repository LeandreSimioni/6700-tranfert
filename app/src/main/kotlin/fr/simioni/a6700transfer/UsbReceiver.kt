package fr.simioni.a6700transfer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

class UsbReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_USB_PERMISSION = "fr.simioni.a6700transfer.USB_PERMISSION"
        private const val SONY_VENDOR_ID = 0x054C
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> handleAttached(context, intent)
            ACTION_USB_PERMISSION               -> handlePermissionResult(context, intent)
            Intent.ACTION_MEDIA_MOUNTED         -> handleMscMount(context, intent)
        }
    }

    private fun handleAttached(context: Context, intent: Intent) {
        val device = getDevice(intent) ?: run {
            TransferLog.add(context, "[USB] Appareil attache mais impossible de lire le device")
            return
        }
        val vid = device.vendorId
        val pid = device.productId
        val name = device.productName ?: device.deviceName
        TransferLog.add(context, "[USB] Attache: $name  VID=0x${vid.toString(16).uppercase()}  PID=0x${pid.toString(16).uppercase()}")

        if (vid != SONY_VENDOR_ID) {
            TransferLog.add(context, "[USB] Non-Sony ignore (VID attendu: 0x054C)")
            return
        }

        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val sinceTimestamp = prefs.getLong(MainActivity.KEY_LAST_TRANSFER, -1L)
        if (sinceTimestamp == -1L) {
            TransferLog.add(context, "[MTP] Date de depart non configuree - ouvrez l'app et definissez une date")
            return
        }

        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (usbManager.hasPermission(device)) {
            TransferLog.add(context, "[MTP] Permission deja accordee - lancement transfert")
            startMtpTransfer(context, device)
        } else {
            TransferLog.add(context, "[MTP] Demande de permission USB...")
            val permIntent = Intent(ACTION_USB_PERMISSION).apply { setPackage(context.packageName) }
            val pi = PendingIntent.getBroadcast(
                context, 0, permIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            usbManager.requestPermission(device, pi)
        }
    }

    private fun handlePermissionResult(context: Context, intent: Intent) {
        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
        val device = getDevice(intent)
        if (!granted) {
            TransferLog.add(context, "[MTP] Permission REFUSEE par l'utilisateur")
            return
        }
        if (device == null) {
            TransferLog.add(context, "[MTP] Permission accordee mais device null")
            return
        }
        TransferLog.add(context, "[MTP] Permission accordee - lancement transfert")
        startMtpTransfer(context, device)
    }

    private fun handleMscMount(context: Context, intent: Intent) {
        val mountPath = intent.data?.path ?: "?"
        TransferLog.add(context, "[MSC] Volume monte: $mountPath")

        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val devices = usbManager.deviceList
        if (devices.isEmpty()) {
            TransferLog.add(context, "[MSC] Aucun peripherique USB detecte - ignore")
            return
        }
        val sonyDevice = devices.values.firstOrNull { it.vendorId == SONY_VENDOR_ID }
        if (sonyDevice == null) {
            val list = devices.values.joinToString { "VID=0x${it.vendorId.toString(16).uppercase()}" }
            TransferLog.add(context, "[MSC] Pas de Sony en USB ($list) - ignore")
            return
        }
        TransferLog.add(context, "[MSC] Sony detecte en USB: ${sonyDevice.productName ?: sonyDevice.deviceName}")

        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val sinceTimestamp = prefs.getLong(MainActivity.KEY_LAST_TRANSFER, -1L)
        if (sinceTimestamp == -1L) {
            TransferLog.add(context, "[MSC] Date de depart non configuree - ignoree")
            return
        }

        TransferLog.add(context, "[MSC] Lancement transfert dans 3s (attente scan media)...")
        Handler(Looper.getMainLooper()).postDelayed({
            val serviceIntent = Intent(context, TransferService::class.java).apply {
                putExtra(TransferService.EXTRA_SINCE_TIMESTAMP, sinceTimestamp)
                putExtra(TransferService.EXTRA_MODE, TransferService.MODE_MSC)
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        }, 3000L)
    }

    private fun startMtpTransfer(context: Context, device: UsbDevice) {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val sinceTimestamp = prefs.getLong(MainActivity.KEY_LAST_TRANSFER, -1L)
        if (sinceTimestamp == -1L) return

        val serviceIntent = Intent(context, TransferService::class.java).apply {
            putExtra(TransferService.EXTRA_USB_DEVICE, device)
            putExtra(TransferService.EXTRA_SINCE_TIMESTAMP, sinceTimestamp)
            putExtra(TransferService.EXTRA_MODE, TransferService.MODE_MTP)
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }

    @Suppress("DEPRECATION")
    private fun getDevice(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
}
