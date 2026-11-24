package se.dettmansfixeri.dukorforfort

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Musikknappar på framsidan: förra låten, spela/pausa och nästa låt.
 * Skickar vanliga mediaknappar till den app som spelar (Spotify, radio, poddar …),
 * precis som knapparna på ratten eller i hörlurarna. Kräver ingen behörighet.
 */
object Media {
    fun press(ctx: Context, code: Int) {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    fun playing(ctx: Context) = ctx.getSystemService(AudioManager::class.java)?.isMusicActive == true
}

@Composable
fun MediaControls(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var playing by remember { mutableStateOf(Media.playing(ctx)) }
    LaunchedEffect(Unit) { while (true) { playing = Media.playing(ctx); delay(1000) } }

    @Composable
    fun Knapp(ikon: Ikon, code: Int, size: Int = 64) {
        FilledTonalButton(
            onClick = { Media.press(ctx, code); if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) playing = !playing },
            shape = CircleShape,
            contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color.White.copy(alpha = 0.15f), contentColor = Color.White),
            modifier = Modifier.size(size.dp),
        ) { Canvas(Modifier.size((size * 0.36).dp)) { rita(ikon) } }
    }

    Row(modifier, horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Knapp(Ikon.FORRA, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        Knapp(if (playing) Ikon.PAUS else Ikon.SPELA, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 76)
        Knapp(Ikon.NASTA, KeyEvent.KEYCODE_MEDIA_NEXT)
    }
}

/** Ritade ikoner – tecken som ⏸ blir färgade emojier på många telefoner. */
enum class Ikon { FORRA, SPELA, PAUS, NASTA, STOPP }

/** En ritad ikon i texthöjd, t.ex. i en knapp. */
@Composable
fun IkonBild(ikon: Ikon, storlek: Int = 14) {
    val farg = LocalContentColor.current   // samma färg som knappens text
    Canvas(Modifier.size(storlek.dp)) { rita(ikon, farg) }
}

private fun DrawScope.rita(ikon: Ikon, farg: Color = Color.White) {
    val w = size.width
    val h = size.height
    val vit = farg
    // Triangel med bas vid x0 och spets vid x1.
    fun triangel(x0: Float, x1: Float) = drawPath(Path().apply { moveTo(x0, 0f); lineTo(x1, h / 2); lineTo(x0, h); close() }, vit)
    fun stapel(x: Float, b: Float) = drawRect(vit, Offset(x, 0f), Size(b, h))
    when (ikon) {
        Ikon.SPELA -> triangel(w * 0.15f, w * 0.95f)
        Ikon.PAUS -> { stapel(w * 0.15f, w * 0.25f); stapel(w * 0.6f, w * 0.25f) }
        Ikon.NASTA -> { triangel(0f, w * 0.45f); triangel(w * 0.42f, w * 0.87f); stapel(w * 0.87f, w * 0.13f) }
        Ikon.FORRA -> { stapel(0f, w * 0.13f); triangel(w * 0.58f, w * 0.13f); triangel(w, w * 0.55f) }
        Ikon.STOPP -> drawRect(vit, Offset(w * 0.1f, h * 0.1f), Size(w * 0.8f, h * 0.8f))
    }
}
