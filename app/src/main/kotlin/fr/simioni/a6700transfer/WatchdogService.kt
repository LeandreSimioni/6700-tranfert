package fr.simioni.a6700transfer

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.storage.StorageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class WatchdogService : Service() {

    private var watchThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotifHelper.init(this)
        startForeground(
            NotifHelper.NOTIF_WATCHDOG,
            buildNotif(getString(R.string.notif_watchdog_waiting))
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (watchThread?.isAlive == true) return START_STICKY
        startWatching()
        return START_STICKY
    }

    private fun startWatching() {
        watchThread = Thread {
            val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            TransferLog.add(this, "[Watch] Sony detecte - attente du volume MSC...")

            // Poll every 3 seconds until volume appears (no timeout)
            while (!Thread.currentThread().isInterrupted) {
                val timestamp = prefs.getLong(MainActivity.KEY_LAST_TRANSFER, -1L)
                if (timestamp == -1L) {
                    TransferLog.add(this, "[Watch] Date non configuree - arret")
                    stopSelf(); return@Thread
                }

                val volumes = removableVolumes()
                if (volumes.isNotEmpty()) {
                    for ((volId, _) in volumes) {
                        val safUri = prefs.getString("${MainActivity.KEY_SAF_URI_PREFIX}$volId", null)
                        if (safUri != null) {
                            val maxDim = prefs.getInt(MainActivity.KEY_MAX_DIMENSION, 4096)
                            TransferLog.add(this, "[Watch] Volume MSC detecte: $volId - demarrage transfert")
                            ContextCompat.startForegroundService(
                                this,
                                Intent(this, TransferService::class.java).apply {
                                    putExtra(TransferService.EXTRA_SAF_URI, safUri)
                                    putExtra(TransferService.EXTRA_SINCE_TIMESTAMP, timestamp)
                                    putExtra(TransferService.EXTRA_MODE, TransferService.MODE_MSC)
                                    putExtra(TransferService.EXTRA_MAX_DIMENSION, maxDim)
                                }
                            )
                            stopSelf(); return@Thread
                        } else {
                            TransferLog.add(this, "[Watch] Volume trouve ($volId) mais pas d'acces SAF - ouvrir l'app et utiliser Scan MSC")
                            stopSelf(); return@Thread
                        }
                    }
                }

                sleepMs(3_000)
            }
        }.also { it.start() }
    }

    private fun sleepMs(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    private fun removableVolumes(): List<Pair<String, String>> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val sm = getSystemService(StorageManager::class.java)
        return sm.storageVolumes
            .filter { !it.isPrimary && it.isRemovable }
            .mapNotNull { vol ->
                val path = vol.directory?.absolutePath?.replace("/mnt/media_rw/", "/storage/")
                    ?: return@mapNotNull null
                Pair(path.substringAfterLast("/"), path)
            }
    }

    private fun buildNotif(text: String) = NotificationCompat.Builder(this, NotifHelper.CHANNEL_TRANSFER)
        .setSmallIcon(android.R.drawable.ic_menu_camera)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(text)
        .setOngoing(true)
        .build()

    override fun onDestroy() {
        super.onDestroy()
        watchThread?.interrupt()
    }
}
