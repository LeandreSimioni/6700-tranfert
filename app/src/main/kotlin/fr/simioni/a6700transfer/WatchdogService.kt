package fr.simioni.a6700transfer

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
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
                if (volumes.isNotEmpty()) {
                    val volId = volumes.first()
                    val safUriStr = prefs.getString("${MainActivity.KEY_SAF_URI_PREFIX}$volId", null)

                    when {
                        safUriStr == null -> {
                            // No SAF URI stored at all - open app for first-time grant
                            TransferLog.add(this, "[Watch] Volume $volId detecte - SAF jamais accorde, ouverture app")
                            showActionNotification("Volume Sony detecte - ouvrez l'app pour autoriser l'acces")
                            startActivity(Intent(this, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            })
                            stopSelf(); return@Thread
                        }
                        !isSafUriValid(safUriStr) -> {
                            // Permission expired - clear it and re-ask
                            TransferLog.add(this, "[Watch] Permission SAF expiree pour $volId - effacement et re-autorisation")
                            prefs.edit().remove("${MainActivity.KEY_SAF_URI_PREFIX}$volId").apply()
                            showActionNotification("Acces Sony expire - ouvrez l'app pour re-autoriser")
                            startActivity(Intent(this, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            })
                            stopSelf(); return@Thread
                        }
                        else -> {
                            val maxDim = prefs.getInt(MainActivity.KEY_MAX_DIMENSION, 4096)
                            TransferLog.add(this, "[Watch] Volume MSC $volId OK -> demarrage transfert")
                            ContextCompat.startForegroundService(
                                this,
                                Intent(this, TransferService::class.java).apply {
                                    putExtra(TransferService.EXTRA_SAF_URI, safUriStr)
                                    putExtra(TransferService.EXTRA_SINCE_TIMESTAMP, timestamp)
                                    putExtra(TransferService.EXTRA_MODE, TransferService.MODE_MSC)
                                    putExtra(TransferService.EXTRA_MAX_DIMENSION, maxDim)
                                    putExtra(TransferService.EXTRA_VOL_ID, volId)
                                }
                            )
                            stopSelf(); return@Thread
                        }
                    }
                }

                sleepMs(3_000)
            }
        }.also { it.start() }
    }

    private fun isSafUriValid(uriStr: String): Boolean {
        return try {
            val uri = Uri.parse(uriStr)
            contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        } catch (_: Exception) { false }
    }

    private fun showActionNotification(text: String) {
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
                    .setContentText(text)
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
