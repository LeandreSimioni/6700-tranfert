package fr.simioni.a6700transfer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class UsbStorageReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_MOUNTED) return

        val mountPath = intent.data?.path ?: return

        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val timestamp = prefs.getLong(MainActivity.KEY_LAST_TRANSFER, -1L)

        if (timestamp == -1L) {
            notifyConfigurationRequired(context)
            return
        }

        val serviceIntent = Intent(context, TransferService::class.java).apply {
            putExtra(TransferService.EXTRA_MOUNT_PATH, mountPath)
            putExtra(TransferService.EXTRA_SINCE_TIMESTAMP, timestamp)
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }

    private fun notifyConfigurationRequired(context: Context) {
        val channelId = "config_required"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                context.getString(R.string.config_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            )
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }

        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notif_open_app))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()

        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(1, notification)
    }
}
