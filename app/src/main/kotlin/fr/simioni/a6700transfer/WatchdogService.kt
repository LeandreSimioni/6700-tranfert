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
            TransferLog.add(this, "[Watch] Sony detecte - tentative dans 10s")

            // Wait 10s for Sony to mount MSC volume
            sleepMs(10_000)

            val maxAttempts = 6
            var delay = 20_000L

            for (attempt in 1..maxAttempts) {
                if (Thread.currentThread().isInterrupted) return@Thread

                val timestamp = prefs.getLong(MainActivity.KEY_LAST_TRANSFER, -1L)
                if (timestamp == -1L) {
                    TransferLog.add(this, "[Watch] Date non configuree - arret")
                    stopSelf(); return@Thread
                }

                val volumes = removableVolumes()
                if (volumes.isNotEmpty()) {
                    TransferLog.add(this, "[Watch] Volume detecte: ${volumes.map { it.first }}")
                    for ((volId, _) in volumes) {
                        val safUri = prefs.getString("${MainActivity.KEY_SAF_URI_PREFIX}$volId", null)
                        if (safUri != null) {
                            val maxDim = prefs.getInt(MainActivity.KEY_MAX_DIMENSION, 4096)
                            TransferLog.add(this, "[Watch] Demarrage transfert pour $volId")
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
                            TransferLog.add(this, "[Watch] SAF manquant pour $volId - ouvrir l'app")
                            stopSelf(); return@Thread
                        }
                    }
                } else {
                    TransferLog.add(this, "[Watch] Tentative $attempt/$maxAttempts - volume MSC pas encore pret, retry dans ${delay/1000}s")
                    sleepMs(delay)
                    delay = minOf(delay + 20_000L, 60_000L)
                }
            }

            TransferLog.add(this, "[Watch] Timeout - aucun volume MSC detecte")
            stopSelf()
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
