package se.dettmansfixeri.dukorforfort

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

/**
 * Tyst läge: när man kör för fort tar appen över ljudet och spelar tystnad, så musiken pausas.
 * Så fort man är under gränsen släpper appen ljudet igen och musikappen fortsätter spela.
 * Spelas ingen musik när man går över piper appen i stället (se [Warner]).
 */
class MusicStopper(ctx: Context) {
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    // "Transient" = musikappen pausar och fortsätter själv när vi släpper.
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attrs)
        .setOnAudioFocusChangeListener { }
        .build()
    private var track: AudioTrack? = null
    var holding = false
        private set

    /** Spelar någon annan app musik just nu? (Pipet räknas inte – det går som larmljud.) */
    fun otherMusicPlaying() = !holding && audio?.isMusicActive == true

    fun hold(on: Boolean) {
        if (on == holding) return
        holding = on
        if (on) {
            audio?.requestAudioFocus(focus)
            track = silence()?.also { try { it.play() } catch (e: Exception) { } }
        } else {
            track?.let { try { it.stop() } catch (e: Exception) { }; it.release() }
            track = null
            audio?.abandonAudioFocusRequest(focus)
        }
    }

    /** En sekund tystnad som loopar, så appen verkligen "spelar" medan musiken är pausad. */
    private fun silence(): AudioTrack? = try {
        val rate = 8000
        val buf = ByteArray(rate * 2) // 16 bit mono, bara nollor
        AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(buf.size)
            .build().apply {
                write(buf, 0, buf.size)
                setLoopPoints(0, rate, -1)
            }
    } catch (e: Exception) { null }
}
