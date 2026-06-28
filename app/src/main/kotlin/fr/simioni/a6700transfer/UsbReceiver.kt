package fr.simioni.a6700transfer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class UsbReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_USB_PERMISSION = "fr.simioni.a6700transfer.USB_PERMISSION"
        private const val SONY_VID = 0x054C
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> handleDeviceAttached(context, intent)
            ACTION_USB_PERMISSION -> handlePermissionResult(context, intent)
        }
    }

    private fun handleDeviceAttached(context: Context, intent: Intent) {
        val device = getDevice(intent) ?: return
        if (device.vendorId != SONY_VID) return
        TransferLog.add(context, "[USB] Appareil detecte: ${device.productName} (PID=${device.productId.toString(16)})")
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (usbManager.hasPermission(device)) {
            TransferLog.add(context, "[USB] Permission deja accordee")
            startMtpTransfer(context, device)
        } else {
            val permIntent = Intent(ACTION_USB_PERMISSION).apply { setPackage(context.packageName) }
            // FLAG_MUTABLE obligatoire : Android doit pouvoir ecrire EXTRA_PERMISSION_GRANTED=true
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            else
                PendingIntent.FLAG_UPDATE_CURRENT
            val pi = PendingIntent.getBroadcast(context, device.deviceId, permIntent, flags)
            usbManager.requestPermission(device, pi)
            TransferLog.add(context, "[USB] Demande de permission envoyee")
        }
    }

    private fun handlePermissionResult(context: Context, intent: Intent) {
        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
        val device = getDevice(intent)
        TransferLog.add(context, "[USB] Reponse permission: granted=$granted device=${device?.productName}")
        if (!granted || device == null) return
        startMtpTransfer(context, device)
    }

    private fun startMtpTransfer(context: Context, device: UsbDevice) {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val timestamp = prefs.getLong(MainActivity.KEY_LAST_TRANSFER, -1L)
        if (timestamp == -1L) {
            TransferLog.add(context, "[USB] Date non configuree - ouvrir l'app")
            notifyConfigurationRequired(context)
            return
        }
        TransferLog.add(context, "[MTP] Demarrage transfert MTP")
        val serviceIntent = Intent(context, TransferService::class.java).apply {
            putExtra(TransferService.EXTRA_USB_DEVICE, device)
            putExtra(TransferService.EXTRA_SINCE_TIMESTAMP, timestamp)
            putExtra(TransferService.EXTRA_MODE, TransferService.MODE_MTP)
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }

    private fun notifyConfigurationRequired(context: Context) {
        val channelId = "config_required"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, context.getString(R.string.config_channel_name),
                NotificationManager.IMPORTANCE_HIGH)
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
        val immutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pi = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), immutable
        )
        val notif = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notif_open_app))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(1, notif)
    }

    @Suppress("DEPRECATION")
    private fun getDevice(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }
}
