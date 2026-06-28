package fr.simioni.a6700transfer

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.storage.StorageManager
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
    }

    private var pendingScanAfterGrant = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { updateUi() }

    private val safLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            val volId = uri.lastPathSegment?.trimEnd(':') ?: "unknown"
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString("$KEY_SAF_URI_PREFIX$volId", uri.toString()).apply()
            TransferLog.add(this, "[MSC] Acces SAF accorde: $volId")
            refreshLogs()
            if (pendingScanAfterGrant) {
                pendingScanAfterGrant = false
                startManualMscScan()
            }
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

        // Resize option
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rg = findViewById<RadioGroup>(R.id.rg_resize)
        val savedDim = prefs.getInt(KEY_MAX_DIMENSION, 4096)
        rg.check(when (savedDim) {
            0    -> R.id.rb_original
            3000 -> R.id.rb_3000
            else -> R.id.rb_4096
        })
        rg.setOnCheckedChangeListener { _, checkedId ->
            val dim = when (checkedId) {
                R.id.rb_original -> 0
                R.id.rb_3000    -> 3000
                else            -> 4096
            }
            prefs.edit().putInt(KEY_MAX_DIMENSION, dim).apply()
        }
    }

    override fun onResume() { super.onResume(); updateUi(); refreshLogs() }

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
        val tvPerm = findViewById<TextView>(R.id.tv_permission_status)
        val btnPerm = findViewById<Button>(R.id.btn_permission)
        if (hasAllPermissions()) {
            tvPerm.text = getString(R.string.permissions_ok)
            btnPerm.text = getString(R.string.btn_check_permissions)
        } else {
            tvPerm.text = getString(R.string.permissions_missing)
            btnPerm.text = getString(R.string.btn_grant_permissions)
        }
        btnPerm.setOnClickListener { checkPermissions() }
    }

    private fun hasAllPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return false
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) return false
        }
        return true
    }

    private fun checkPermissions() {
        val toRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                toRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                toRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (toRequest.isNotEmpty()) permissionLauncher.launch(toRequest.toTypedArray())
    }

    private fun startManualMscScan() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val timestamp = prefs.getLong(KEY_LAST_TRANSFER, -1L)
        if (timestamp == -1L) { Toast.makeText(this, "Configurez d'abord la date de départ", Toast.LENGTH_SHORT).show(); return }

        val paths = findRemovableVolumePaths()
        if (paths.isEmpty()) {
            TransferLog.add(this, "[MSC] Aucun volume externe detecte")
            Toast.makeText(this, "Aucun volume externe détecté — branchez le Sony en mode MSC", Toast.LENGTH_LONG).show()
            refreshLogs(); return
        }

        val missingAccess = paths.filter { path ->
            val volId = path.substringAfterLast("/")
            prefs.getString("$KEY_SAF_URI_PREFIX$volId", null) == null
        }
        if (missingAccess.isNotEmpty()) {
            val path = missingAccess.first()
            val volId = path.substringAfterLast("/")
            TransferLog.add(this, "[MSC] Demande acces SAF pour $volId")
            refreshLogs()
            Toast.makeText(this, "Sélectionnez le volume Sony puis appuyez sur \"Utiliser ce dossier\"", Toast.LENGTH_LONG).show()
            pendingScanAfterGrant = true
            launchSafPicker(path)
            return
        }

        val maxDim = prefs.getInt(KEY_MAX_DIMENSION, 4096)
        for (path in paths) {
            val volId = path.substringAfterLast("/")
            val safUriStr = prefs.getString("$KEY_SAF_URI_PREFIX$volId", null) ?: continue
            TransferLog.add(this, "[MSC] Scan SAF: $path")
            ContextCompat.startForegroundService(this, Intent(this, TransferService::class.java).apply {
                putExtra(TransferService.EXTRA_SAF_URI, safUriStr)
                putExtra(TransferService.EXTRA_SINCE_TIMESTAMP, timestamp)
                putExtra(TransferService.EXTRA_MODE, TransferService.MODE_MSC)
                putExtra(TransferService.EXTRA_MAX_DIMENSION, maxDim)
            })
        }
        refreshLogs()
        Toast.makeText(this, "Scan MSC démarré — voir les logs", Toast.LENGTH_SHORT).show()
    }

    private fun launchSafPicker(volumePath: String) {
        val volId = volumePath.substringAfterLast("/")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val sm = getSystemService(StorageManager::class.java)
            val volume = sm.storageVolumes.firstOrNull { vol ->
                vol.directory?.absolutePath?.contains(volId) == true
            }
            val pickerIntent = volume?.createOpenDocumentTreeIntent()
                ?: Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            safLauncher.launch(pickerIntent)
        } else {
            safLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
        }
    }

    private fun findRemovableVolumePaths(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val sm = getSystemService(StorageManager::class.java)
            sm.storageVolumes
                .filter { !it.isPrimary && it.isRemovable }
                .mapNotNull { it.directory?.absolutePath?.replace("/mnt/media_rw/", "/storage/") }
        } else {
            getExternalFilesDirs(null).drop(1).mapNotNull { dir ->
                var f: java.io.File? = dir
                repeat(4) { f = f?.parentFile }
                f?.absolutePath?.takeIf { !it.startsWith("/storage/emulated") }
            }
        }
    }

    private fun checkUpdate() {
        val tvUpdate = findViewById<TextView>(R.id.tv_update_status)
        tvUpdate.text = "Vérification en cours…"
        Thread {
            val result = UpdateChecker.check(BuildConfig.VERSION_CODE)
            runOnUiThread {
                tvUpdate.text = when (result) {
                    is UpdateResult.UpToDate -> "✓ v${BuildConfig.VERSION_NAME} est à jour"
                    is UpdateResult.UpdateAvailable -> { UpdateChecker.downloadApk(this); "Mise à jour (build ${result.remoteCode}) — téléchargement lancé" }
                    is UpdateResult.Error -> "Erreur: ${result.message}"
                }
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
