package se.dettmansfixeri.dukorforfort

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView

/**
 * Rund hastighetsskylt ovanpå andra appar (t.ex. kartan) när gränsen sänks.
 * Syns några sekunder och försvinner sedan av sig själv. Fingret går igenom den.
 * Kräver behörigheten "Visa ovanpå andra appar".
 */
object SignOverlay {
    private const val SHOW_MS = 6_000L
    private const val SIZE_DP = 150
    private const val TOP_DP = 90

    /** Sätts av MainActivity: syns appen själv behövs ingen skylt ovanpå. */
    @Volatile var appVisible = false

    private var view: View? = null
    private val handler = Handler(Looper.getMainLooper())

    fun allowed(ctx: Context) = Settings.canDrawOverlays(ctx)

    fun openSettings(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun show(ctx: Context, limit: Int) {
        val app = ctx.applicationContext
        if (!allowed(app)) return
        val wm = app.getSystemService(WindowManager::class.java) ?: return
        hide(wm)
        val d = app.resources.displayMetrics.density
        val px = (SIZE_DP * d).toInt()
        val iv = ImageView(app).apply { setImageBitmap(SignPainter.signBitmap(app, limit, px)) }
        val lp = WindowManager.LayoutParams(
            px, px,
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
        try { wm.addView(iv, lp); view = iv } catch (e: Exception) { return }
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ hide(wm) }, SHOW_MS)
    }

    fun hide(ctx: Context) { ctx.applicationContext.getSystemService(WindowManager::class.java)?.let { hide(it) } }

    private fun hide(wm: WindowManager) {
        view?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        view = null
    }
}
