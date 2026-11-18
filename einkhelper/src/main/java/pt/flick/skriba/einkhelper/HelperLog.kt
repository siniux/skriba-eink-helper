package pt.flick.skriba.einkhelper

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registo para suporte (num ficheiro). So grava com a opcao "Guardar registo" ligada no ecra da app, exceto as
 * mensagens importantes (force = true, ex.: a app fechou com erro). Limitado a ~200 KB.
 */
object HelperLog {
    private const val PREFS = "helper"
    private const val KEY_ON = "log_on"
    private const val MAX_BYTES = 200 * 1024L
    private fun file(ctx: Context) = File(ctx.filesDir, "registo.txt")
    private val clock = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var enabledCache: Boolean? = null

    fun isEnabled(ctx: Context): Boolean = enabledCache ?: try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)
    } catch (_: Throwable) { false }.also { enabledCache = it }

    fun setEnabled(ctx: Context, on: Boolean) {
        enabledCache = on
        try { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply() } catch (_: Throwable) { }
    }

    @Synchronized
    fun add(ctx: Context, msg: String, force: Boolean = false) {
        if (!force && !isEnabled(ctx)) return
        try {
            val f = file(ctx)
            if (f.length() > MAX_BYTES) {
                val keep = f.readText().takeLast((MAX_BYTES / 2).toInt())
                f.writeText(keep)
            }
            f.appendText("${clock.format(Date())}  $msg\n")
        } catch (_: Throwable) { }
    }

    fun read(ctx: Context): String = try { file(ctx).readText() } catch (_: Throwable) { "" }

    fun clear(ctx: Context) { try { file(ctx).delete() } catch (_: Throwable) { } }
}
