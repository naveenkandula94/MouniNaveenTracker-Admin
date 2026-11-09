package com.mouninaveen.admin

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Local troubleshooting messages. Never logs audio content or private call numbers. */
internal object AdminDiagnostics {
    private const val KEY = "admin_recent_events"
    fun record(context: Context, kind: String, message: String) {
        val prefs = context.getSharedPreferences("admin_diagnostics", Context.MODE_PRIVATE)
        val time = SimpleDateFormat("dd MMM HH:mm:ss", Locale.getDefault()).format(Date())
        val safe = message.replace(Regex("[\\r\\n]+"), " ")
            .replace(Regex("[+]?[0-9]{8,15}"), "[redacted]")
            .take(220)
        val recent = (listOf("$time | ${kind.take(20)} | $safe") +
            prefs.getString(KEY, "").orEmpty().lines().filter { it.isNotBlank() }).take(80)
        prefs.edit().putString(KEY, recent.joinToString("\n")).apply()
    }
    fun read(context: Context): String = context.getSharedPreferences("admin_diagnostics", Context.MODE_PRIVATE)
        .getString(KEY, "").orEmpty().ifBlank { "No diagnostic events." }
    fun clear(context: Context) =
        context.getSharedPreferences("admin_diagnostics", Context.MODE_PRIVATE).edit().remove(KEY).apply()
}
