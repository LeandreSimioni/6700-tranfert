package fr.simioni.a6700transfer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class UsbStorageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_MOUNTED) return
        val mountPath = intent.data?.path ?: return
        if (mountPath.startsWith("/storage/emulated/")) return

        TransferLog.add(context, "[MSC] Volume monte: $mountPath")

        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val timestamp = prefs.getLong(MainActivity.KEY_LAST_TRANSFER, -1L)
        if (timestamp == -1L) {
            TransferLog.add(context, "[MSC] Date non configuree - ouvrir l'app")
            return
        }

        val serviceIntent = Intent(context, TransferService::class.java).apply {
            putExtra(TransferService.EXTRA_MOUNT_PATH, mountPath)
            putExtra(TransferService.EXTRA_SINCE_TIMESTAMP, timestamp)
            putExtra(TransferService.EXTRA_MODE, TransferService.MODE_MSC)
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
