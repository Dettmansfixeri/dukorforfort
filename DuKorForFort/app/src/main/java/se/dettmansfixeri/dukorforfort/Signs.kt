package se.dettmansfixeri.dukorforfort

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat

/*
 * Svenska vägmärken ritade efter förlaga, i en koordinatyta på 100 enheter i bredd
 * (samma mått som SVG:erna på webbsidan):
 *  - C31 Hastighetsbegränsning: röd ring ut till kanten, gul platta (r 38.4), svarta Tratex-siffror.
 *  - Tilläggstavla: röd kant med rundade hörn, gul botten, svart Tratex-text.
 */
object SignPainter {
    private const val SIGN_RED = 0xFFDC0028.toInt()
    private const val PLATE_RED = 0xFFE8202E.toInt()
    private const val SIGN_YELLOW = 0xFFFFC800.toInt()

    fun tratex(ctx: Context): Typeface? = try { ResourcesCompat.getFont(ctx, R.font.tratex_svart) } catch (e: Exception) { null }

    fun drawSpeedSign(c: Canvas, size: Float, text: String, tf: Typeface?, textColor: Int = android.graphics.Color.BLACK) {
        val u = size / 100f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = SIGN_RED; c.drawCircle(50f * u, 50f * u, 50f * u, p)
        p.color = SIGN_YELLOW; c.drawCircle(50f * u, 50f * u, 38.4f * u, p)
        val tp = signTextPaint(u, text, tf).apply { color = textColor }
        // Baslinjen läggs så att siffrornas höjd (≈0.8 em i Tratex) blir centrerad.
        c.drawText(text, 50f * u, (50f + 0.4f * tp.textSize / u) * u, tp)
    }

    private fun signTextPaint(u: Float, text: String, tf: Typeface?) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = tf
        textAlign = Paint.Align.CENTER
        // Skyltsiffrorna är smalare än Tratex normalbredd (scaleX 0.766).
        // Tresiffriga (100–120) görs lite lägre så de ryms i den gula plattan.
        val threeDigits = text.length >= 3
        textSize = (if (threeDigits) 48f else 56.8f) * u
        val maxWidth = if (threeDigits) 60f else 66f
        textScaleX = minOf(0.766f, maxWidth * u / measureText(text))
    }

    /** Var på skylten (x-led, pixlar) sista siffran sitter – den gömda nollan. */
    fun signLastCharRange(size: Float, text: String, tf: Typeface?): ClosedFloatingPointRange<Float> {
        val u = size / 100f
        val p = signTextPaint(u, text, tf)
        val right = 50f * u + p.measureText(text) / 2f
        val last = p.measureText(text.takeLast(1))
        return (right - last - 4f * u)..(right + 6f * u)
    }

    private fun platePaint(u: Float, text: String, tf: Typeface?) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = tf
        color = android.graphics.Color.BLACK
        textAlign = Paint.Align.CENTER
        textSize = 24.5f * u
        val w = measureText(text)
        if (w > 88f * u) textScaleX = 88f * u / w
    }

    fun drawPlate(c: Canvas, width: Float, text: String, tf: Typeface?) {
        val u = width / 100f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = PLATE_RED; c.drawRoundRect(RectF(0f, 0f, 100f * u, 40f * u), 4.5f * u, 4.5f * u, p)
        p.color = SIGN_YELLOW; c.drawRoundRect(RectF(2.6f * u, 2.6f * u, 97.4f * u, 37.4f * u), 2.6f * u, 2.6f * u, p)
        c.drawText(text, 50f * u, 30.1f * u, platePaint(u, text, tf))
    }

    /** Var på tavlan (x-led, pixlar) sista bokstaven sitter – det gömda "O:et" i IDIOT. */
    fun lastCharRange(width: Float, text: String, tf: Typeface?): ClosedFloatingPointRange<Float> {
        val u = width / 100f
        val p = platePaint(u, text, tf)
        val right = 50f * u + p.measureText(text) / 2f
        val last = p.measureText(text.takeLast(1))
        return (right - last - 8f * u)..width
    }

    /** Skylten som bild, till notisen. */
    fun signBitmap(ctx: Context, limit: Int?, px: Int): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        drawSpeedSign(Canvas(bmp), px.toFloat(), limit?.toString() ?: "?", tratex(ctx))
        return bmp
    }
}

@Composable
private fun rememberTratex(): Typeface? {
    val ctx = LocalContext.current
    return remember { SignPainter.tratex(ctx) }
}

/** Fem tryck med högst 2 s mellanrum inom [hit] anropar [onSecret]. */
@Composable
private fun secretTaps(key: Any?, onSecret: (() -> Unit)?, hit: (x: Float, width: Float) -> Boolean): Modifier {
    if (onSecret == null) return Modifier
    val taps = remember { ArrayDeque<Long>() }
    return Modifier.pointerInput(key) {
        detectTapGestures { pos ->
            if (hit(pos.x, size.width.toFloat())) {
                val now = SystemClock.uptimeMillis()
                if (taps.isNotEmpty() && now - taps.last() > 2000) taps.clear()
                taps.addLast(now)
                if (taps.size >= 5) { taps.clear(); onSecret() }
            }
        }
    }
}

/** Hastighetsskylten. Fem tryck på sista siffran (nollan) anropar [onSecret]. */
@Composable
fun SpeedSign(limit: Int?, stale: Boolean, size: Dp = 190.dp, onSecret: (() -> Unit)? = null) {
    val tf = rememberTratex()
    val text = limit?.toString() ?: "?"
    val tap = secretTaps(text, onSecret) { x, w -> x in SignPainter.signLastCharRange(w, text, tf) }
    androidx.compose.foundation.Canvas(Modifier.size(size).then(tap)) {
        SignPainter.drawSpeedSign(
            drawContext.canvas.nativeCanvas, this.size.width, limit?.toString() ?: "?", tf,
            if (stale) android.graphics.Color.GRAY else android.graphics.Color.BLACK
        )
    }
}

/** Tilläggstavlan. Fem snabba tryck på sista bokstaven anropar [onSecret]. */
@Composable
fun UnderSign(text: String, width: Dp = 190.dp, onSecret: (() -> Unit)? = null) {
    val tf = rememberTratex()
    val mod = Modifier.width(width).height(width * 0.4f)
        .then(secretTaps(text, onSecret) { x, w -> x in SignPainter.lastCharRange(w, text, tf) })
    androidx.compose.foundation.Canvas(mod) {
        SignPainter.drawPlate(drawContext.canvas.nativeCanvas, this.size.width, text, tf)
    }
}
