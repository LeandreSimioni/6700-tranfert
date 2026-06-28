package fr.simioni.a6700transfer

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }

    override fun onResume() {
        super.onResume()
        updateUi()
    }

    private fun updateUi() {
        val timestamp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_TRANSFER, -1L)

        val tvStatus = findViewById<TextView>(R.id.tv_status)
        val tvPermission = findViewById<TextView>(R.id.tv_permission_status)
        val btnSetDate = findViewById<Button>(R.id.btn_set_date)
        val btnPermission = findViewById<Button>(R.id.btn_permission)

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED)
                toRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (toRequest.isNotEmpty()) permissionLauncher.launch(toRequest.toTypedArray())
    }
}
