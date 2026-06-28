package fr.simioni.a6700transfer

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
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
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { updateUi() }

    private val transferReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val msg = intent.getStringExtra(TransferService.EXTRA_BROADCAST_MSG) ?: return
            val done = intent.getBooleanExtra(TransferService.EXTRA_BROADCAST_DONE, false)
            val cardTransfer = findViewById<View>(R.id.card_transfer)
            val tvProgress = findViewById<TextView>(R.id.tv_transfer_progress)
            val tvLog = findViewById<TextView>(R.id.tv_log)
            if (done) {
                cardTransfer.visibility = View.GONE
                updateUi()
            } else {
                cardTransfer.visibility = View.VISIBLE
                tvProgress.text = msg
            }
            // Mise a jour log en temps reel
            val log = TransferLog.get(this@MainActivity)
            tvLog.text = if (log.isBlank()) getString(R.string.log_empty) else log
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        supportActionBar?.subtitle = "v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"
        TransferLog.add(this, "[App] Demarrage v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})  API=${Build.VERSION.SDK_INT}")
        UpdateChecker.check(this)
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(TransferService.ACTION_TRANSFER_BROADCAST)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(transferReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(transferReceiver, filter)
        }
        updateUi()
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(transferReceiver) } catch (_: Exception) {}
    }

    private fun updateUi() {
        val timestamp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_TRANSFER, -1L)

        val tvStatus = findViewById<TextView>(R.id.tv_status)
        val tvPermission = findViewById<TextView>(R.id.tv_permission_status)
        val btnSetDate = findViewById<Button>(R.id.btn_set_date)
        val btnPermission = findViewById<Button>(R.id.btn_permission)
        val tvLog = findViewById<TextView>(R.id.tv_log)
        val btnUpdate = findViewById<Button>(R.id.btn_update)
        val tvUpdateStatus = findViewById<TextView>(R.id.tv_update_status)
        val btnCopyLog = findViewById<Button>(R.id.btn_copy_log)
        val btnClearLog = findViewById<Button>(R.id.btn_clear_log)

        tvStatus.text = if (timestamp == -1L) {
            getString(R.string.status_not_configured)
        } else {
            getString(R.string.status_last_transfer,
                SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(Date(timestamp)))
        }

        btnSetDate.setOnClickListener { showDateTimePicker() }

        if (hasAllPermissions()) {
            tvPermission.text = getString(R.string.permissions_ok)
            btnPermission.text = getString(R.string.btn_check_permissions)
            btnPermission.setOnClickListener { checkPermissions() }
        } else {
            tvPermission.text = getString(R.string.permissions_missing)
            btnPermission.text = getString(R.string.btn_grant_permissions)
            btnPermission.setOnClickListener { checkPermissions() }
        }

        val log = TransferLog.get(this)
        tvLog.text = if (log.isBlank()) getString(R.string.log_empty) else log

        btnCopyLog.setOnClickListener {
            val content = TransferLog.get(this)
            val clip = ClipData.newPlainText("logs", content.ifBlank { "(vide)" })
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
            Toast.makeText(this, getString(R.string.log_copied), Toast.LENGTH_SHORT).show()
        }

        btnClearLog.setOnClickListener {
            TransferLog.clear(this)
            tvLog.text = getString(R.string.log_empty)
            Toast.makeText(this, getString(R.string.log_cleared), Toast.LENGTH_SHORT).show()
        }

        btnUpdate.setOnClickListener {
            btnUpdate.isEnabled = false
            UpdateChecker.checkManual(
                this,
                onStatus = { msg -> tvUpdateStatus.text = msg },
                onInstall = { intent ->
                    btnUpdate.isEnabled = true
                    startActivity(intent)
                }
            )
            tvUpdateStatus.postDelayed({ btnUpdate.isEnabled = true }, 15_000)
        }
    }

    private fun showDateTimePicker() {
        val cal = Calendar.getInstance()
        DatePickerDialog(
            this,
            { _, year, month, day ->
                TimePickerDialog(
                    this,
                    { _, hour, minute ->
                        val ts = Calendar.getInstance().apply {
                            set(year, month, day, hour, minute, 0)
                            set(Calendar.MILLISECOND, 0)
                        }.timeInMillis
                        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().putLong(KEY_LAST_TRANSFER, ts).apply()
                        TransferLog.add(this, "[Config] Date de depart definie: ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(Date(ts))}")
                        updateUi()
                        Toast.makeText(this, R.string.date_saved, Toast.LENGTH_SHORT).show()
                    },
                    cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true
                ).show()
            },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun hasAllPermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) return false
        }
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.Q..Build.VERSION_CODES.S_V2) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return false
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED) return false
        }
        return true
    }

    private fun checkPermissions() {
        val toRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED)
                toRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.Q..Build.VERSION_CODES.S_V2) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED)
                toRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED)
                toRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED)
                toRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
        }
        if (toRequest.isNotEmpty()) permissionLauncher.launch(toRequest.toTypedArray())
    }
}
