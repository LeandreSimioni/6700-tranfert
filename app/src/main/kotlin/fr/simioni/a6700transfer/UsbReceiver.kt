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
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> handleDeviceAttached(context, intent)
            ACTION_USB_PERMISSION -> handlePermissionResult(context, intent)
        }
    }

    private fun handleDeviceAttached(context: Context, intent: Intent) {
        val device = getDevice(intent) ?: return
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (usbManager.hasPermission(device)) {
            startTransfer(context, device)
        } else {
            val permIntent = Intent(ACTION_USB_PERMISSION).apply {
                setPackage(context.packageName)
            }
            val pi = PendingIntent.getBroadcast(
                context, 0, permIntent, PendingIntent.FLAG_IMMUTABLE
            )
            usbManager.requestPermission(device, pi)
        }
    }

    private fun handlePermissionResult(context: Context, intent: Intent) {
        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
        if (!granted) return
        val device = getDevice(intent) ?: return
        startTransfer(context, device)
    }

    private fun startTransfer(context: Context, device: UsbDevice) {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val timestamp = prefs.getLong(MainActivity.KEY_LAST_TRANSFER, -1L)
        if (timestamp == -1L) {
            notifyConfigurationRequired(context)
            return
        }
        val serviceIntent = Intent(context, TransferService::class.java).apply {
            putExtra(TransferService.EXTRA_USB_DEVICE, device)
            putExtra(TransferService.EXTRA_SINCE_TIMESTAMP, timestamp)
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
        val pi = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
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
