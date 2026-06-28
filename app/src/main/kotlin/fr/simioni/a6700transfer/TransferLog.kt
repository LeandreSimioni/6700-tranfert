package fr.simioni.a6700transfer

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TransferLog {
    private const val PREFS = "transfer_log"
    private const val KEY_LOG = "log"
    private const val MAX_ENTRIES = 200
    private val fmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.FRANCE)

    fun add(context: Context, message: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getString(KEY_LOG, "") ?: ""
        val entry = "${fmt.format(Date())} $message"
        val lines = if (current.isEmpty()) mutableListOf() else current.lines().toMutableList()
        lines.add(0, entry)
        if (lines.size > MAX_ENTRIES) lines.subList(MAX_ENTRIES, lines.size).clear()
        prefs.edit().putString(KEY_LOG, lines.joinToString("\n")).apply()
    }

    fun get(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LOG, "") ?: ""
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_LOG).apply()
    }
}
