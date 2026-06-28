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
            Intent.ACTION_MEDIA_MOUNTED         -> handleMscMount(context)
        }
    }

    private fun handleAttached(context: Context, intent: Intent) {
        val device = getDevice(intent) ?: return
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (usbManager.hasPermission(device)) {
            startMtpTransfer(context, device)
        } else {
            val permIntent = Intent(ACTION_USB_PERMISSION).apply { setPackage(context.packageName) }
            val pi = PendingIntent.getBroadcast(
                context, 0, permIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            usbManager.requestPermission(device, pi)
        }
    }

    private fun handlePermissionResult(context: Context, intent: Intent) {
        if (!intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) return
        val device = getDevice(intent) ?: return
        startMtpTransfer(context, device)
    }

    private fun handleMscMount(context: Context) {
        // Verifie qu'un Sony est bien branché (présent dans la liste USB même en MSC)
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val hasSony = usbManager.deviceList.values.any { it.vendorId == SONY_VENDOR_ID }
        if (!hasSony) return

        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val sinceTimestamp = prefs.getLong(MainActivity.KEY_LAST_TRANSFER, -1L)
        if (sinceTimestamp == -1L) return

        // Délai pour laisser le scanner media indexer le volume
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
