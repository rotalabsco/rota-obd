package co.rotalabs.obd.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import co.rotalabs.obd.R
import kotlin.math.abs

/**
 * Quadrinho por cima dos outros apps (Maps, música) com o status da leitura e os valores escolhidos.
 *
 * Feito com Views simples, sem Compose, porque a janela fica fora da Activity. Arraste para mudar de
 * lugar; toque para voltar ao Rota.OBD. Precisa da permissão "sobrepor a outros apps".
 */
class FloatingPanel(
    private val context: Context,
    private val onMoved: (x: Int, y: Int) -> Unit,
    private val onTap: () -> Unit,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var status: TextView? = null
    private val rows = mutableListOf<Pair<TextView, TextView>>()
    private val mono: Typeface = runCatching { ResourcesCompat.getFont(context, R.font.geist_mono) }.getOrNull() ?: Typeface.MONOSPACE

    val visible: Boolean get() = root != null

    fun allowed(): Boolean = Settings.canDrawOverlays(context)

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()

    @SuppressLint("ClickableViewAccessibility")
    fun show(x: Int, y: Int) {
        if (root != null || !allowed()) return
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            minimumWidth = dp(200)
            setPadding(dp(14), dp(10), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(0xEE121416.toInt())
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), 0xFF3A4248.toInt())
            }
        }
        status = TextView(context).apply {
            textSize = 13f
            typeface = mono
            setTextColor(0xFFA9AEB2.toInt())
        }
        box.addView(status)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
        box.setOnTouchListener(DragListener(lp))
        try {
            wm.addView(box, lp)
            root = box
            params = lp
        } catch (e: Exception) {
            Log.w("FloatingPanel", "Não foi possível mostrar o painel: ${e.message}")
            status = null
        }
    }

    fun update(statusText: String, recording: Boolean, lines: List<Pair<String, String>>) {
        val box = root ?: return
        status?.text = statusText
        status?.setTextColor(if (recording) 0xFFF28A2E.toInt() else 0xFFA9AEB2.toInt())
        while (rows.size < lines.size) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, 0)
            }
            val label = TextView(context).apply {
                textSize = 14f
                setTextColor(0xFFA9AEB2.toInt())
            }
            val value = TextView(context).apply {
                textSize = 22f
                typeface = Typeface.create(mono, Typeface.BOLD)
                setTextColor(0xFFF2F1EE.toInt())
                gravity = Gravity.END
            }
            row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(value, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(16) })
            box.addView(row)
            rows += label to value
        }
        rows.forEachIndexed { i, (label, value) ->
            val line = lines.getOrNull(i)
            (label.parent as View).visibility = if (line == null) View.GONE else View.VISIBLE
            if (line != null) {
                label.text = line.first
                value.text = line.second
            }
        }
    }

    fun hide() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        params = null
        status = null
        rows.clear()
    }

    /** Arrasta a janela; um toque sem arrastar abre o app. */
    private inner class DragListener(private val lp: WindowManager.LayoutParams) : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var dragging = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = e.rawX
                    touchY = e.rawY
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchX
                    val dy = e.rawY - touchY
                    if (!dragging && (abs(dx) > dp(8) || abs(dy) > dp(8))) dragging = true
                    if (dragging) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                }
                MotionEvent.ACTION_UP -> if (dragging) onMoved(lp.x, lp.y) else onTap()
            }
            return true
        }
    }
}
