package fr.simioni.a6700transfer

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TransferLog {
    private const val KEY = "transfer_log"
    private const val MAX = 20
    private val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE)

    fun add(context: Context, msg: String) {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY, "") ?: ""
        val lines = existing.split("\n").filter { it.isNotBlank() }.toMutableList()
        lines.add(0, "${fmt.format(Date())} — $msg")
        if (lines.size > MAX) lines.subList(MAX, lines.size).clear()
        prefs.edit().putString(KEY, lines.joinToString("\n")).apply()
    }

    fun get(context: Context): String =
        context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY, "") ?: ""
}
