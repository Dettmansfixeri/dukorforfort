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
 *   31+            → siren utan paus (körkortsgränsen; 21+ på vägar med gräns 30 eller lägre)
 *
 * Tonläget: i stället för pip en jämn ton utan paus, med samma tonhöjd per km/h.
 * Förvarningen (lägre gräns längre fram) spelas som ett dubbelklick, "tick-tick".
 */
class Beeper {

    /**
     * Ett ljudmönster som upprepas var [periodMs]: [count] pip, [hz] tonhöjd, [ms] längd på varje pip.
     * 0 pip = siren. [tone] = jämn ton utan paus. [spacingMs] > 0 = pipen tätt efter varandra i början.
     */
    data class Pattern(
        val count: Int, val hz: Double, val ms: Int, val text: String,
        val tone: Boolean = false, val periodMs: Int = 1000, val spacingMs: Int = 0,
    )

    companion object {
        private const val RATE = 22050
        /** Volym medan rösten pratar: tyst, annars dränker pipet rösternas första stavelse. */
        private const val DUCK = 0f
        /** Pipets styrka. Rösterna är utjämnade till −16 LUFS och blev mycket svagare än ett pip för fullt (ca −10 dB). */
        private const val LEVEL = 0.3f
        private val SIREN = Pattern(0, 0.0, 0, "siren utan paus")

        /** Lägsta tonen (1 km/h över). Tonen stiger ett halvtonsteg för varje km/h, upp till [TOP_OVER]. */
        private const val BASE_HZ = 660.0
        private const val TOP_OVER = 24

        /** Tonhöjd för [over] km/h över: ett halvtonsteg högre för varje km/h (660 Hz → 2640 Hz vid 25+). */
        fun hz(over: Int): Double = BASE_HZ * Math.pow(2.0, (over.coerceIn(1, TOP_OVER + 1) - 1) / 12.0)

        /** Körkortsgränsen: 31 km/h över, eller 21 över på väg med gräns 30 eller lägre. */
        fun licenseOver(limit: Int?): Int = if (limit != null && limit <= 30) 21 else 31

        /**
         * Mönstret för [over] km/h över (inklusive marginal). [sirenAt] = när sirenen tar över,
         * räknat på samma sätt. [tone] = tonläget.
         */
        fun pattern(over: Int, sirenAt: Int = 31, tone: Boolean = false): Pattern? = when {
            over <= 0 -> null
            over >= sirenAt -> SIREN
            tone -> Pattern(1, Math.round(hz(over)).toDouble(), 0, "jämn ton", tone = true)
            over <= 2 -> Pattern(1, hz(over), 140, "ett pip i sekunden")
            over <= 4 -> Pattern(2, hz(over), 120, "två pip i sekunden")
            over <= 7 -> Pattern(3, hz(over), 100, "tre pip i sekunden")
            over <= 10 -> Pattern(4, hz(over), 90, "fyra pip i sekunden")
            over <= 15 -> Pattern(8, hz(over), 35, "tickande larm")
            else -> Pattern(12, hz(over), 28, "snabbare tickande larm")
        }

        /** Förvarningens dubbelklick, upprepat var [periodMs]. */
        fun click(periodMs: Int): Pattern = Pattern(2, 2000.0, 14, "tick-tick", periodMs = periodMs, spacingMs = 130)

        /** En sekund ljud för mönstret, 16 bit mono. */
        fun render(p: Pattern): ShortArray {
            val out = ShortArray(RATE * p.periodMs / 1000)
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
            if (p.tone) {
                // Heltal Hz på en sekund = hela svängningar, så slingan går runt utan klick.
                for (i in out.indices) out[i] = (0.6 * amp * sin(2 * PI * p.hz * i / RATE)).toInt().toShort()
                return out
            }
            val len = RATE * p.ms / 1000
            val fade = min(len / 4, RATE * 4 / 1000)   // mjuk början och slut, inget klick
            for (b in 0 until p.count) {
                val start = if (p.spacingMs > 0) RATE * b * p.spacingMs / 1000 else out.size * b / p.count
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
    /** Spelas pipet som larm (ingen röst)? Då full styrka, larmvolymen styr. */
    private var alarm = false
    /** Volym i procent av standard (null = inställningen). Provlyssningen i inställningarna sätter den. */
    var volume: Int? = null
    private val level get() =
        ((if (alarm) 1f else LEVEL) * (volume ?: SpeedTracker.settings.value.beepVolume) / 100f).coerceIn(0f, 1f)

    /** Tar in en ändrad volym direkt, även mitt i ett pip. */
    fun refresh() = duck(ducked)

    /** Byter till mönstret (null = tyst). Gör inget om samma mönster redan spelas. */
    fun play(p: Pattern?) {
        if (p == playing) return
        stop()
        if (p == null) return
        try {
            val pcm = render(p)
            // Med röst: samma ljudväg som rösten (navigering). Som larm gick pipet både till telefonen och bilens
            // Bluetooth, och när rösten sedan startade bytte Android väg och första stavelsen försvann.
            // Utan röst: som larm igen (larmvolymen, hörs även när mediavolymen är nere).
            alarm = SpeedTracker.settings.value.heckleOn == HeckleMode.OFF
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(if (alarm) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
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
            t.setVolume(if (ducked) DUCK else level)
            t.play()
            track = t
            playing = p
        } catch (e: Exception) {
            stop()
        }
    }

    /** Sänker volymen (t.ex. medan rösten pratar). */
    fun duck(on: Boolean) {
        ducked = on
        try { track?.setVolume(if (on) DUCK else level) } catch (e: Exception) { }
    }
    private var ducked = false

    fun stop() {
        try { track?.stop() } catch (e: Exception) { }
        track?.release()
        track = null
        playing = null
    }
}
