package fr.simioni.a6700transfer

import android.Manifest
import android.app.DatePickerDialog
import android.app.DownloadManager
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.storage.StorageManager
import android.provider.Settings
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "a6700_prefs"
        const val KEY_LAST_TRANSFER = "last_transfer_timestamp"
        const val KEY_SAF_URI_PREFIX = "saf_uri_"
        const val KEY_MAX_DIMENSION = "max_dimension"
        private const val SONY_VID = 0x054C
    }

    private var pendingScanAfterGrant = false
    private var activeDownloadId = -1L
    private val handler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { updateUi() }

    private val safLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            val volId = uri.lastPathSegment?.trimEnd(':') ?: "unknown"
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString("$KEY_SAF_URI_PREFIX$volId", uri.toString()).apply()
            TransferLog.add(this, "[MSC] Acces SAF accorde: $volId")
            refreshLogs()
            if (pendingScanAfterGrant) { pendingScanAfterGrant = false; startManualMscScan() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        TransferLog.add(this, "[App] Demarrage v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}) API=${Build.VERSION.SDK_INT}")

        findViewById<Button>(R.id.btn_copy_log).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("logs", TransferLog.get(this)))
            Toast.makeText(this, "Logs copiés ✓", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btn_clear_log).setOnClickListener { TransferLog.clear(this); refreshLogs() }
        findViewById<Button>(R.id.btn_scan_msc).setOnClickListener { startManualMscScan() }
        findViewById<Button>(R.id.btn_check_update).setOnClickListener { checkUpdate() }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rg = findViewById<RadioGroup>(R.id.rg_resize)
        val savedDim = prefs.getInt(KEY_MAX_DIMENSION, 4096)
        rg.check(when (savedDim) {
            0    -> R.id.rb_original
            3000 -> R.id.rb_3000
            else -> R.id.rb_4096
        })
        rg.setOnCheckedChangeListener { _, checkedId ->
            prefs.edit().putInt(KEY_MAX_DIMENSION, when (checkedId) {
                R.id.rb_original -> 0
                R.id.rb_3000    -> 3000
                else            -> 4096
            }).apply()
        }

        handleUsbIntent(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleUsbIntent(intent) }

    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device: UsbDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java) ?: return
        else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) ?: return
        if (device.vendorId != SONY_VID) return
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getLong(KEY_LAST_TRANSFER, -1L) == -1L) {
            Toast.makeText(this, "Sony détecté ! Définissez d'abord la date de départ.", Toast.LENGTH_LONG).show()
            return
        }
        TransferLog.add(this, "[USB] Sony detecte - surveillance MSC active")
        ContextCompat.startForegroundService(this, Intent(this, WatchdogService::class.java))
        Toast.makeText(this, "Sony détecté — sélectionnez MSC sur l'appareil", Toast.LENGTH_LONG).show()
        refreshLogs()
    }

    override fun onResume() {
        super.onResume()
        updateUi()
        refreshLogs()
        // Resume download progress polling if a download was active
        val downloadId = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong(UpdateChecker.PREF_DOWNLOAD_ID, -1L)
        if (downloadId != -1L && activeDownloadId == -1L) {
            val (_, _, status) = UpdateChecker.queryProgress(this, downloadId)
            if (status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING) {
                activeDownloadId = downloadId
                startProgressPolling()
            }
        }
    }

    override fun onPause() { super.onPause(); stopProgressPolling() }

    private fun requestAllPermissions() {
        // Step 1: MANAGE_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        // Step 2: Install unknown apps
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        // Step 3: Battery optimization
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                return
            }
        }
        // Step 4: Runtime permissions
        val toRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            toRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            toRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
            toRequest.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            toRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) toRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        val missing = toRequest.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            Toast.makeText(this, "Toutes les permissions sont accordées ✓", Toast.LENGTH_SHORT).show()
            updateUi()
        }
    }

    private fun startProgressPolling() {
        val tvUpdate = findViewById<TextView>(R.id.tv_update_status)
        val runnable = object : Runnable {
            override fun run() {
                val id = activeDownloadId
                if (id == -1L) return
                val (downloaded, total, status) = UpdateChecker.queryProgress(this@MainActivity, id)
                when (status) {
                    DownloadManager.STATUS_RUNNING -> {
                        val pct = if (total > 0) (downloaded * 100 / total).toInt() else 0
                        val dlMb = "%.1f".format(downloaded / 1_000_000f)
                        val totMb = if (total > 0) "%.1f".format(total / 1_000_000f) else "?"
                        tvUpdate.text = "Téléchargement $pct% ($dlMb / $totMb Mo)"
                        handler.postDelayed(this, 500)
                    }
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        tvUpdate.text = "✓ Téléchargement terminé — installation en cours..."
                        activeDownloadId = -1L
                    }
                    DownloadManager.STATUS_FAILED -> {
                        tvUpdate.text = "Erreur téléchargement — réessayez"
                        activeDownloadId = -1L
                    }
                    else -> handler.postDelayed(this, 1000)
                }
            }
        }
        progressRunnable = runnable
        handler.post(runnable)
    }

    private fun stopProgressPolling() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        progressRunnable = null
    }

    private fun refreshLogs() {
        findViewById<TextView>(R.id.tv_log).text = TransferLog.get(this).ifEmpty { "Aucun log pour l'instant." }
    }

    private fun updateUi() {
        val timestamp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong(KEY_LAST_TRANSFER, -1L)
        findViewById<TextView>(R.id.tv_version).text = "v${BuildConfig.VERSION_NAME}"
        findViewById<TextView>(R.id.tv_status).text = if (timestamp == -1L)
            getString(R.string.status_not_configured)
        else getString(R.string.status_last_transfer,
            SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(Date(timestamp)))
        findViewById<Button>(R.id.btn_set_date).setOnClickListener { showDateTimePicker() }
        val allOk = hasAllPermissions()
        findViewById<TextView>(R.id.tv_permission_status).text =
            if (allOk) getString(R.string.permissions_ok) else getString(R.string.permissions_missing)
        val btnPerm = findViewById<Button>(R.id.btn_permission)
        btnPerm.text = if (allOk) getString(R.string.btn_check_permissions) else getString(R.string.btn_grant_permissions)
        btnPerm.setOnClickListener { requestAllPermissions() }
    }

    private fun hasAllPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        return true
    }

    fun startManualMscScan() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val timestamp = prefs.getLong(KEY_LAST_TRANSFER, -1L)
        if (timestamp == -1L) { Toast.makeText(this, "Configurez d'abord la date de départ", Toast.LENGTH_SHORT).show(); return }
        val volumes = VolumeHelper.findRemovableVolumePaths(this)
        TransferLog.add(this, "[MSC] Volumes trouves: ${volumes.map { it.first }}")
        if (volumes.isEmpty()) {
            TransferLog.add(this, "[MSC] Aucun volume externe detecte")
            Toast.makeText(this, "Aucun volume externe détecté — branchez le Sony en mode MSC", Toast.LENGTH_LONG).show()
            refreshLogs(); return
        }
        val missingAccess = volumes.filter { (volId, _) -> prefs.getString("$KEY_SAF_URI_PREFIX$volId", null) == null }
        if (missingAccess.isNotEmpty()) {
            val (volId, path) = missingAccess.first()
            TransferLog.add(this, "[MSC] Demande acces SAF pour $volId")
            refreshLogs()
            Toast.makeText(this, "Sélectionnez le volume Sony puis \"Utiliser ce dossier\"", Toast.LENGTH_LONG).show()
            pendingScanAfterGrant = true
            launchSafPicker(path)
            return
        }
        val maxDim = prefs.getInt(KEY_MAX_DIMENSION, 4096)
        for ((volId, _) in volumes) {
            val safUriStr = prefs.getString("$KEY_SAF_URI_PREFIX$volId", null) ?: continue
            TransferLog.add(this, "[MSC] Scan SAF: $volId")
            ContextCompat.startForegroundService(this, Intent(this, TransferService::class.java).apply {
                putExtra(TransferService.EXTRA_SAF_URI, safUriStr)
                putExtra(TransferService.EXTRA_SINCE_TIMESTAMP, timestamp)
                putExtra(TransferService.EXTRA_MODE, TransferService.MODE_MSC)
                putExtra(TransferService.EXTRA_MAX_DIMENSION, maxDim)
                putExtra(TransferService.EXTRA_VOL_ID, volId)
            })
        }
        refreshLogs()
    }

    private fun launchSafPicker(volumePath: String) {
        val volId = volumePath.substringAfterLast("/")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val sm = getSystemService(StorageManager::class.java)
            val volume = sm.storageVolumes.firstOrNull { it.directory?.absolutePath?.contains(volId) == true }
            safLauncher.launch(volume?.createOpenDocumentTreeIntent() ?: Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
        } else safLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
    }

    private fun checkUpdate() {
        val tvUpdate = findViewById<TextView>(R.id.tv_update_status)
        tvUpdate.text = "Vérification en cours…"
        Thread {
            val result = UpdateChecker.check(BuildConfig.VERSION_CODE)
            runOnUiThread {
                when (result) {
                    is UpdateResult.UpToDate -> tvUpdate.text = "✓ v${BuildConfig.VERSION_NAME} est à jour"
                    is UpdateResult.UpdateAvailable -> {
                        tvUpdate.text = "Mise à jour disponible (build ${result.remoteCode}) — téléchargement..."
                        activeDownloadId = UpdateChecker.downloadApk(this)
                        startProgressPolling()
                    }
                    is UpdateResult.Error -> tvUpdate.text = "Erreur: ${result.message}"}
            }
        }.start()
    }

    private fun showDateTimePicker() {
        val cal = Calendar.getInstance()
        DatePickerDialog(this, { _, year, month, day ->
            TimePickerDialog(this, { _, hour, minute ->
                val ts = Calendar.getInstance().apply {
                    set(year, month, day, hour, minute, 0); set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putLong(KEY_LAST_TRANSFER, ts).apply()
                updateUi()
                Toast.makeText(this, R.string.date_saved, Toast.LENGTH_SHORT).show()
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }
}
