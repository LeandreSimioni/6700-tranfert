package fr.simioni.a6700transfer

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
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

            while (!Thread.currentThread().isInterrupted) {
                val timestamp = prefs.getLong(MainActivity.KEY_LAST_TRANSFER, -1L)
                if (timestamp == -1L) {
                    TransferLog.add(this, "[Watch] Date non configuree - arret")
                    stopSelf(); return@Thread
                }

                val volumes = VolumeHelper.findRemovableVolumes(this)
                TransferLog.add(this, "[Watch] Volumes detectes: $volumes")

                if (volumes.isNotEmpty()) {
                    val volId = volumes.first()
                    val safUri = prefs.getString("${MainActivity.KEY_SAF_URI_PREFIX}$volId", null)
                    if (safUri != null) {
                        val maxDim = prefs.getInt(MainActivity.KEY_MAX_DIMENSION, 4096)
                        TransferLog.add(this, "[Watch] Volume MSC $volId -> demarrage transfert")
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
                        TransferLog.add(this, "[Watch] Volume $volId detecte - SAF manquant, ouverture app")
                        showSafNeededNotification()
                        startActivity(Intent(this, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        })
                        stopSelf(); return@Thread
                    }
                }

                sleepMs(3_000)
            }
        }.also { it.start() }
    }

    private fun showSafNeededNotification() {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        try {
            NotificationManagerCompat.from(this).notify(
                NotifHelper.NOTIF_DONE,
                NotificationCompat.Builder(this, NotifHelper.CHANNEL_DONE)
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText("Volume Sony detecte - appuyez pour autoriser l'acces")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build()
            )
        } catch (_: SecurityException) {}
    }

    private fun sleepMs(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
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
