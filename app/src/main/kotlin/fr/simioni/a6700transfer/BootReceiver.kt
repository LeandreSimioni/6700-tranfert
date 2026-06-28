package fr.simioni.a6700transfer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Redeclenche la surveillance apres redemarrage du telephone.
 * Le NotificationListenerService est relance automatiquement par Android.
 * Ce receiver sert juste a logger le boot pour le diagnostic.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON") return
        TransferLog.add(context, "[Boot] Telephone redémarre - surveillance active")
    }
}
