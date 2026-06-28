package fr.simioni.a6700transfer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

object NotifHelper {
    const val CHANNEL_TRANSFER = "transfer"
    const val CHANNEL_DONE = "transfer_done"

    const val NOTIF_TRANSFER = 100
    const val NOTIF_DONE = 101

    fun init(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = nm(context)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TRANSFER,
                context.getString(R.string.transfer_channel_name),
                NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE,
                context.getString(R.string.notif_channel_done),
                NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun showDone(context: Context, count: Int) {
        init(context)
        val notif = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notif_done, count))
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(context.getString(R.string.notif_done, count)))
            .setAutoCancel(true)
            .build()
        nm(context).notify(NOTIF_DONE, notif)
    }

    private fun nm(context: Context) =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}
