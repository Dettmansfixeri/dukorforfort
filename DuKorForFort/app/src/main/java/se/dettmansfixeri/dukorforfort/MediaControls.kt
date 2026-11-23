package se.dettmansfixeri.dukorforfort

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    fun Knapp(text: String, code: Int, size: Int = 64) {
        FilledTonalButton(
            onClick = { Media.press(ctx, code); if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) playing = !playing },
            shape = CircleShape,
            contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color.White.copy(alpha = 0.15f), contentColor = Color.White),
            modifier = Modifier.size(size.dp),
        ) { Text(text, fontSize = (size / 2.6).sp) }
    }

    Row(modifier, horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Knapp("⏮", KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        Knapp(if (playing) "⏸" else "▶", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 76)
        Knapp("⏭", KeyEvent.KEYCODE_MEDIA_NEXT)
    }
}
