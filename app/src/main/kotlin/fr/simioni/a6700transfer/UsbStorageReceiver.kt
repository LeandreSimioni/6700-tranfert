package fr.simioni.a6700transfer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class UsbStorageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_MOUNTED) return
        val rawPath = intent.data?.path ?: return
        val mountPath = rawPath.replace("/mnt/media_rw/", "/storage/")
        if (mountPath.startsWith("/storage/emulated/")) return

        val volId = mountPath.substringAfterLast("/")
        TransferLog.add(context, "[MSC] Volume monte: $mountPath (id=$volId)")

        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val timestamp = prefs.getLong(MainActivity.KEY_LAST_TRANSFER, -1L)
        if (timestamp == -1L) {
            TransferLog.add(context, "[MSC] Date non configuree - ouvrir l'app")
            return
        }
        val safUriStr = prefs.getString("${MainActivity.KEY_SAF_URI_PREFIX}$volId", null)
        if (safUriStr == null) {
            TransferLog.add(context, "[MSC] Acces SAF manquant pour $volId - ouvrir l'app et taper Scan MSC")
            return
        }
        NotifHelper.cancelDetected(context)
        TransferLog.add(context, "[MSC] Demarrage transfert auto SAF")
        ContextCompat.startForegroundService(context, Intent(context, TransferService::class.java).apply {
            putExtra(TransferService.EXTRA_SAF_URI, safUriStr)
            putExtra(TransferService.EXTRA_SINCE_TIMESTAMP, timestamp)
            putExtra(TransferService.EXTRA_MODE, TransferService.MODE_MSC)
        })
    }
}
