package se.dettmansfixeri.dukorforfort

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Rund hastighetsskylt ovanpå andra appar (t.ex. kartan).
 *  - För fort: skylten med gränsen och din fart i rött under, så länge du ligger över.
 *  - Förvarningen: när en lägre gräns närmar sig blinkar den kommande skylten i takt med tickandet,
 *    så länge förvarningen pågår.
 *  - När gränsen sänkts: den nya skylten syns stilla några sekunder och försvinner sedan.
 * Fingret går igenom den. Kräver behörigheten "Visa ovanpå andra appar".
 */
object SignOverlay {
    private const val SHOW_MS = 6_000L
    private const val SIZE_DP = 150
    private const val TOP_DP = 90
    private const val RED = 0xFFE53935.toInt()

    /** Sätts av MainActivity: syns appen själv behövs ingen skylt ovanpå. */
    @Volatile var appVisible = false

    private var view: View? = null
    private val handler = Handler(Looper.getMainLooper())
    /** Förvarningen som blinkar nu: gräns, takt och starttid (null = ingen). */
    private var ahead: Triple<Int, Int?, Long>? = null
    /** För fort: gränsen som visas och fältet med farten (null = visas inte). */
    private var overLimit: Int? = null
    private var speedText: TextView? = null

    fun allowed(ctx: Context) = Settings.canDrawOverlays(ctx)

    fun openSettings(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Gränsen har sänkts: visa den nya skylten stilla i några sekunder. */
    fun show(ctx: Context, limit: Int) {
        val app = ctx.applicationContext
        val wm = app.getSystemService(WindowManager::class.java) ?: return
        stopAll(wm)
        if (!attach(app, wm, limit, false)) return
        handler.postDelayed({ stopAll(wm) }, SHOW_MS)
    }

    /** För fort: skylten [limit] med farten [speed] i rött under. null = inte för fort (tar bort den). */
    fun over(ctx: Context, limit: Int?, speed: Int?) {
        val app = ctx.applicationContext
        val wm = app.getSystemService(WindowManager::class.java) ?: return
        if (limit == null || speed == null) { if (overLimit != null) stopAll(wm); return }
        if (overLimit == limit && view != null) { speedText?.text = "$speed"; return }
        stopAll(wm)
        if (!attach(app, wm, limit, true)) return
        overLimit = limit
        speedText?.text = "$speed"
    }

    /** Visas skylten med farten just nu? Då får förvarningen vänta. */
    val showingOver get() = overLimit != null

    /**
     * Förvarningen: [limit] = den lägre gränsen som kommer (null = slut). [blinkMs] = tickandets takt
     * (null = rösten sa till, då blinkar den lugnt), [since] = när tickandet började, så blinket går i takt med pipen.
     */
    fun ahead(ctx: Context, limit: Int?, blinkMs: Int?, since: Long) {
        val app = ctx.applicationContext
        val wm = app.getSystemService(WindowManager::class.java) ?: return
        if (limit == null) { if (ahead != null) stopAll(wm); return }
        val want = Triple(limit, blinkMs, since)
        if (want == ahead) return
        stopAll(wm)
        if (!attach(app, wm, limit, false)) return
        ahead = want
        val period = (blinkMs ?: CALM_BLINK_MS).toLong()
        val onMs = if (blinkMs == null) period / 2 else BLINK_ON_MS
        val tick = object : Runnable {
            override fun run() {
                val v = view ?: return
                val phase = (SystemClock.elapsedRealtime() - since).mod(period)
                val on = phase < onMs
                v.visibility = if (on) View.VISIBLE else View.INVISIBLE
                // Nästa byte: när den ska släckas, eller när nästa tick börjar.
                handler.postDelayed(this, (if (on) onMs - phase else period - phase).coerceAtLeast(10L))
            }
        }
        handler.post(tick)
    }

    fun hide(ctx: Context) { ctx.applicationContext.getSystemService(WindowManager::class.java)?.let { stopAll(it) } }

    /** Lägger skylten på skärmen, med ett fält för farten under om [withSpeed]. */
    private fun attach(app: Context, wm: WindowManager, limit: Int, withSpeed: Boolean): Boolean {
        if (!allowed(app)) return false
        val d = app.resources.displayMetrics.density
        val px = (SIZE_DP * d).toInt()
        val sign = ImageView(app).apply { setImageBitmap(SignPainter.signBitmap(app, limit, px)) }
        val root: View = if (!withSpeed) sign else LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(sign, LinearLayout.LayoutParams(px, px))
            val t = TextView(app).apply {
                setTextColor(RED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                val padH = (14 * d).toInt()
                setPadding(padH, 0, padH, 0)
                background = GradientDrawable().apply { setColor(0xE6000000.toInt()); cornerRadius = 16 * d }
            }
            addView(t, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = (6 * d).toInt() })
            speedText = t
        }
        val lp = WindowManager.LayoutParams(
            if (withSpeed) WindowManager.LayoutParams.WRAP_CONTENT else px,
            if (withSpeed) WindowManager.LayoutParams.WRAP_CONTENT else px,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (TOP_DP * d).toInt()
            // Android 12+ släpper bara igenom tryck genom rutor som är högst 80 % täckande.
            alpha = 0.8f
        }
        return try { wm.addView(root, lp); view = root; true } catch (e: Exception) { speedText = null; false }
    }

    private fun stopAll(wm: WindowManager) {
        handler.removeCallbacksAndMessages(null)
        ahead = null
        overLimit = null
        speedText = null
        view?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        view = null
    }
}
