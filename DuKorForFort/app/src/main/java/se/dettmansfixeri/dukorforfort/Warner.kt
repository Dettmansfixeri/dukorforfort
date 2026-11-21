package se.dettmansfixeri.dukorforfort

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Varnar med vibration och pip, i steg efter hur många km/h över gränsen (inkl. marginal) man ligger:
 *
 *   1 km/h över  → 1 kort puls, var 5:e sekund
 *   2 km/h över  → 2 korta, var 4:e sekund
 *   3 km/h över  → 3 korta, var 3:e sekund
 *   4 km/h över  → 4 korta, var 2:a sekund
 *   5 km/h över  → 5 korta, varje sekund
 *   6+ km/h över → surrar hela tiden
 *
 * Går man upp ett steg vibrerar den direkt. Pipet följer tabellen i [Beeper].
 *
 * Mot GPS-skakningar (±1 km/h) startar varningen först när man legat över i [GRACE_MS]
 * (ungefär två GPS-punkter i rad).
 */
class Warner(ctx: Context) {

    companion object {
        const val CONTINUOUS = 6
        private const val PULSE_ON = 90L
        private const val PULSE_OFF = 110L
        const val GRACE_MS = 1500L

        /** Mellanrum mellan varje surr för ett visst steg. */
        fun intervalMs(level: Int): Long = when (level) {
            1 -> 5000L
            2 -> 4000L
            3 -> 3000L
            4 -> 2000L
            else -> 1000L
        }.coerceAtLeast(level.coerceAtMost(5) * (PULSE_ON + PULSE_OFF) + 150L)

        /** Beskrivning för testsidan. */
        fun describe(over: Int): String {
            if (over <= 0) return "Ingen varning"
            val vib = if (over >= CONTINUOUS) "surrar hela tiden"
            else "$over ${if (over == 1) "puls" else "pulser"} var ${seconds(intervalMs(over))} s"
            return "$over km/h över → pip: ${Beeper.pattern(over)?.text}, vibration: $vib"
        }

        private fun seconds(ms: Long): String =
            if (ms % 1000 == 0L) (ms / 1000).toString() else String.format("%.1f", ms / 1000.0).replace('.', ',')
    }

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    private val beeper = Beeper()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()

    private var job: Job? = null
    private var graceJob: Job? = null
    private var level = 0
    private var vibrateOn = true
    private var settings = Settings()
    private var overSince = 0L      // när man först kom över gränsen (0 = inte över)
    private var latestOver = 0

    fun update(over: Int, s: Settings) {
        settings = s
        latestOver = over.coerceAtLeast(0)
        if (latestOver == 0) {
            overSince = 0L
            graceJob?.cancel(); graceJob = null
            apply(0)
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (overSince == 0L) overSince = now
        val wait = GRACE_MS - (now - overSince)
        if (wait > 0) {
            if (graceJob == null) graceJob = scope.launch { delay(wait); graceJob = null; apply(latestOver) }
            return
        }
        apply(latestOver)
    }

    private fun apply(over: Int) {
        beeper.play(if (settings.beep) Beeper.pattern(over) else null)
        val newLevel = over.coerceIn(0, CONTINUOUS)
        if (newLevel == level && settings.vibrate == vibrateOn) return
        val rising = newLevel > level
        level = newLevel
        vibrateOn = settings.vibrate
        job?.cancel()
        vibrator?.cancel()
        if (newLevel == 0 || !settings.vibrate) return
        job = scope.launch { loop(newLevel, immediate = rising) }
    }

    private suspend fun loop(lvl: Int, immediate: Boolean) {
        if (!immediate) delay(intervalMs(lvl))
        if (lvl >= CONTINUOUS) {
            vibrateContinuous()
            return
        }
        while (true) {
            pulses(lvl)
            delay(intervalMs(lvl))
        }
    }

    private fun pulses(n: Int) {
        val timings = LongArray(n * 2) { if (it % 2 == 0) (if (it == 0) 0L else PULSE_OFF) else PULSE_ON }
        val amps = IntArray(n * 2) { if (it % 2 == 0) 0 else 255 }
        vibrate(timings, amps, -1)
    }

    private fun vibrateContinuous() = vibrate(longArrayOf(0, 600, 80), intArrayOf(0, 255, 0), 1)

    private fun vibrate(timings: LongArray, amps: IntArray, repeat: Int) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val effect = if (v.hasAmplitudeControl()) VibrationEffect.createWaveform(timings, amps, repeat)
        else VibrationEffect.createWaveform(timings, repeat)
        @Suppress("DEPRECATION")
        v.vibrate(effect, attrs)
    }

    fun release() {
        job?.cancel()
        graceJob?.cancel()
        vibrator?.cancel()
        scope.cancel()
        beeper.stop()
        level = 0
    }
}
