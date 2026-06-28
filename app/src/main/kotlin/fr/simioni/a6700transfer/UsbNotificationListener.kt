package fr.simioni.a6700transfer

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat

/**
 * Intercepte la notification systeme "Cle USB Sony" qu'Android affiche
 * quand le Sony monte en mode MSC. Demarre le WatchdogService immediatement.
 */
class UsbNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val title = sbn.notification.extras.getString(Notification.EXTRA_TITLE) ?: return
        val text = sbn.notification.extras.getString(Notification.EXTRA_TEXT) ?: ""

        // Filtre: notification systeme contenant Sony et USB/stockage
        val isSonyUsb = (sbn.packageName == "android" || sbn.packageName == "com.android.systemui")
            && (title.contains("Sony", ignoreCase = true)
                || text.contains("Sony", ignoreCase = true)
                || (title.contains("USB", ignoreCase = true) && text.contains("photo", ignoreCase = true)))

        if (!isSonyUsb) return

        val ctx = applicationContext
        val prefs = ctx.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getLong(MainActivity.KEY_LAST_TRANSFER, -1L) == -1L) {
            TransferLog.add(ctx, "[NLS] Cle USB Sony detectee - date non configuree")
            return
        }
        TransferLog.add(ctx, "[NLS] Cle USB Sony detectee (pkg=${sbn.packageName} titre=$title) - demarrage watchdog")
        ContextCompat.startForegroundService(
            ctx,
            Intent(ctx, WatchdogService::class.java)
        )
    }
}
