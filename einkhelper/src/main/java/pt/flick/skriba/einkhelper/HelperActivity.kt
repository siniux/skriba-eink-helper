package pt.flick.skriba.einkhelper

import android.app.Activity
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Ecra da app auxiliar. Nao e preciso abri-lo para usar: a Skriba liga-se sozinha ao servico (InkBridgeService)
 * quando abre uma nota. Aqui so se ve o estado, se abre a Skriba e se guarda/partilha o registo para suporte.
 */
class HelperActivity : Activity() {
    private lateinit var statusView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reportLastExit()

        // Mesmo aspeto da Skriba em e-ink: fundo branco, cartoes com contorno preto, botao em capsula, sem sombras.
        val d = resources.displayMetrics.density
        fun dp(v: Float) = (v * d).toInt()
        fun rounded(fill: Int, stroke: Int?, radiusDp: Float) = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radiusDp * d
            if (stroke != null) setStroke(dp(1.5f), stroke)
        }
        val black = Color.BLACK
        val grey = Color.rgb(0x55, 0x55, 0x55)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(28f), dp(24f), dp(24f))
            setBackgroundColor(Color.WHITE)
        }

        // Cabecalho: icone + nome
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
        }, LinearLayout.LayoutParams(dp(52f), dp(52f)))
        header.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(black)
            setPadding(dp(14f), 0, 0, 0)
        })
        root.addView(header)

        root.addView(TextView(this).apply {
            text = getString(R.string.helper_intro)
            textSize = 15f
            setTextColor(grey)
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(16f), 0, dp(20f))
        })

        // Estado: uma linha so (a que interessa)
        statusView = TextView(this).apply {
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(black)
            setPadding(dp(18f), dp(16f), dp(18f), dp(16f))
            background = rounded(Color.WHITE, black, 16f)
        }
        root.addView(statusView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Botao principal (capsula preta, como os botoes ativos da Skriba em e-ink)
        root.addView(TextView(this).apply {
            text = getString(R.string.helper_open_skriba)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(black, null, 26f)
            isClickable = true
            setOnClickListener { openSkriba() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52f)).apply { topMargin = dp(16f) })

        // Suporte: escondido ate se tocar (registo para o suporte)
        val support = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(16f), dp(8f), dp(16f), dp(12f))
            background = rounded(Color.WHITE, black, 16f)
        }
        support.addView(CheckBox(this).apply {
            text = getString(R.string.helper_log_toggle)
            textSize = 14f
            setTextColor(black)
            isChecked = HelperLog.isEnabled(this@HelperActivity)
            setOnCheckedChangeListener { _, on -> HelperLog.setEnabled(this@HelperActivity, on) }
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun outlineButton(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(black)
            gravity = Gravity.CENTER
            setPadding(dp(16f), 0, dp(16f), 0)
            background = rounded(Color.WHITE, black, 22f)
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(outlineButton(getString(R.string.helper_log_share)) { shareLog() },
            LinearLayout.LayoutParams(0, dp(44f), 1f).apply { marginEnd = dp(8f) })
        row.addView(outlineButton(getString(R.string.helper_log_clear)) { HelperLog.clear(this@HelperActivity) },
            LinearLayout.LayoutParams(0, dp(44f), 1f))
        support.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })

        val supportToggle = TextView(this).apply {
            text = getString(R.string.helper_support_title) + "  ▸"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(black)
            setPadding(dp(4f), dp(14f), dp(4f), dp(14f))
            isClickable = true
            setOnClickListener {
                val show = support.visibility != View.VISIBLE
                support.visibility = if (show) View.VISIBLE else View.GONE
                text = getString(R.string.helper_support_title) + if (show) "  ▾" else "  ▸"
            }
        }
        root.addView(supportToggle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20f) })
        root.addView(support, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        root.addView(TextView(this).apply {
            text = getString(R.string.helper_version, versionName())
            textSize = 12f
            setTextColor(grey)
            setPadding(dp(4f), dp(20f), 0, 0)
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private fun skribaInstalled(): Boolean = try {
        packageManager.getPackageInfo(InkBridgeService.SKRIBA_PACKAGE, 0); true
    } catch (_: Throwable) { false }

    private fun deviceSupported(): Boolean = try {
        Class.forName("android.os.enote.ENoteSetting"); true
    } catch (_: Throwable) { false }

    /** Uma so linha: o problema, se houver; senao, se a caneta rapida esta ativa ou a espera de uma nota. */
    private fun updateStatus() {
        statusView.text = when {
            !deviceSupported() -> getString(R.string.helper_device_no)
            !skribaInstalled() -> getString(R.string.helper_skriba_no)
            InkBridgeService.skribaConnected -> getString(R.string.helper_connected)
            else -> getString(R.string.helper_waiting)
        }
    }

    private fun openSkriba() {
        val i = packageManager.getLaunchIntentForPackage(InkBridgeService.SKRIBA_PACKAGE) ?: return
        startActivity(i)
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Throwable) { "?" }

    private fun shareLog() {
        val header = "${getString(R.string.app_name)} ${versionName()} | ${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n\n"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.app_name) + " - log")
            putExtra(Intent.EXTRA_TEXT, header + HelperLog.read(this@HelperActivity).ifBlank { "(vazio / empty)" })
        }
        startActivity(Intent.createChooser(send, getString(R.string.helper_log_share)))
    }

    /** Se esta app fechou com erro da ultima vez, fica no registo (sempre, mesmo com o registo desligado). */
    private fun reportLastExit() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val am = getSystemService(ActivityManager::class.java) ?: return
            val prefs = getSharedPreferences("helper", MODE_PRIVATE)
            val lastSeen = prefs.getLong("last_exit", 0L)
            val exits = am.getHistoricalProcessExitReasons(null, 0, 5)
            if (exits.isEmpty()) return
            prefs.edit().putLong("last_exit", exits.maxOf { it.timestamp }).apply()
            val bad = setOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR)
            val e = exits.filter { it.timestamp > lastSeen && it.reason in bad }.maxByOrNull { it.timestamp } ?: return
            HelperLog.add(this, "!!! A app fechou: motivo ${e.reason} (4=crash, 5=nativo, 6=ANR), sinal ${e.status}, ${e.description}", force = true)
        } catch (_: Throwable) { }
    }
}
