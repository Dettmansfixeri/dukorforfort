package se.dettmansfixeri.dukorforfort

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Spelar varningspipet som en slinga på en sekund, tätare ju mer för fort man kör.
 * Tonen stiger ett halvtonsteg för varje km/h över (660 Hz vid 1 km/h, en oktav högre vid 13).
 *
 *   1–2 km/h över  → ett pip i sekunden
 *   3–4            → två pip i sekunden
 *   5–7            → tre pip i sekunden
 *   8–10           → fyra pip i sekunden
 *   11–15          → tickande larm
 *   16–30          → snabbare tickande larm
 *   31+            → siren utan paus (körkortsgränsen)
 */
class Beeper {

    /** Ett pipmönster: [count] pip per sekund, [hz] tonhöjd, [ms] längd på varje pip. 0 pip = siren. */
    data class Pattern(val count: Int, val hz: Double, val ms: Int, val text: String)

    companion object {
        private const val RATE = 22050
        private val SIREN = Pattern(0, 0.0, 0, "siren utan paus")

        /** Lägsta tonen (1 km/h över). Tonen stiger ett halvtonsteg för varje km/h, upp till [TOP_OVER]. */
        private const val BASE_HZ = 660.0
        private const val TOP_OVER = 24

        /** Tonhöjd för [over] km/h över: ett halvtonsteg högre för varje km/h (660 Hz → 2640 Hz vid 25+). */
        fun hz(over: Int): Double = BASE_HZ * Math.pow(2.0, (over.coerceIn(1, TOP_OVER + 1) - 1) / 12.0)

        fun pattern(over: Int): Pattern? = when {
            over <= 0 -> null
            over <= 2 -> Pattern(1, hz(over), 140, "ett pip i sekunden")
            over <= 4 -> Pattern(2, hz(over), 120, "två pip i sekunden")
            over <= 7 -> Pattern(3, hz(over), 100, "tre pip i sekunden")
            over <= 10 -> Pattern(4, hz(over), 90, "fyra pip i sekunden")
            over <= 15 -> Pattern(8, hz(over), 35, "tickande larm")
            over <= 30 -> Pattern(12, hz(over), 28, "snabbare tickande larm")
            else -> SIREN
        }

        /** En sekund ljud för mönstret, 16 bit mono. */
        fun render(p: Pattern): ShortArray {
            val out = ShortArray(RATE)
            val amp = 0.8 * Short.MAX_VALUE
            if (p.count == 0) {
                // Siren: tonen glider 700 → 1500 → 700 Hz under en sekund.
                var phase = 0.0
                for (i in out.indices) {
                    val t = i.toDouble() / RATE
                    val hz = 1100.0 - 400.0 * kotlin.math.cos(2 * PI * t)
                    phase += 2 * PI * hz / RATE
                    out[i] = (amp * sin(phase)).toInt().toShort()
                }
                return out
            }
            val len = RATE * p.ms / 1000
            val fade = min(len / 4, RATE * 4 / 1000)   // mjuk början och slut, inget klick
            for (b in 0 until p.count) {
                val start = RATE * b / p.count
                for (k in 0 until len) {
                    val env = when {
                        k < fade -> k.toDouble() / fade
                        k > len - fade -> (len - k).toDouble() / fade
                        else -> 1.0
                    }
                    out[start + k] = (amp * env * sin(2 * PI * p.hz * k / RATE)).toInt().toShort()
                }
            }
            return out
        }
    }

    private var track: AudioTrack? = null
    private var playing: Pattern? = null

    /** Byter till mönstret (null = tyst). Gör inget om samma mönster redan spelas. */
    fun play(p: Pattern?) {
        if (p == playing) return
        stop()
        if (p == null) return
        try {
            val pcm = render(p)
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                )
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            t.write(pcm, 0, pcm.size)
            t.setLoopPoints(0, pcm.size, -1)
            t.play()
            track = t
            playing = p
        } catch (e: Exception) {
            stop()
        }
    }

    fun stop() {
        try { track?.stop() } catch (e: Exception) { }
        track?.release()
        track = null
        playing = null
    }
}
