package se.dettmansfixeri.dukorforfort

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
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
 * Går man upp ett steg vibrerar den direkt. Pipet kommer högst var 5:e sekund.
 */
class Warner(ctx: Context) {

    companion object {
        const val CONTINUOUS = 6
        private const val PULSE_ON = 90L
        private const val PULSE_OFF = 110L
        private const val BEEP_INTERVAL = 5000L

        /** Mellanrum mellan varje surr för ett visst steg. */
        fun intervalMs(level: Int): Long = when (level) {
            1 -> 5000L
            2 -> 4000L
            3 -> 3000L
            4 -> 2000L
            else -> 1000L
        }.coerceAtLeast(level.coerceAtMost(5) * (PULSE_ON + PULSE_OFF) + 150L)

        /** Beskrivning för testsidan. */
        fun describe(over: Int): String = when {
            over <= 0 -> "Ingen varning"
            over >= CONTINUOUS -> "$over km/h över → surrar hela tiden"
            else -> "$over km/h över → $over ${if (over == 1) "puls" else "pulser"} var ${seconds(intervalMs(over))} s"
        }

        private fun seconds(ms: Long): String =
            if (ms % 1000 == 0L) (ms / 1000).toString() else String.format("%.1f", ms / 1000.0).replace('.', ',')
    }

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    private var tone: ToneGenerator? = try { ToneGenerator(AudioManager.STREAM_ALARM, 90) } catch (e: Exception) { null }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()

    private var job: Job? = null
    private var level = 0
    private var vibrateOn = true
    private var settings = Settings()
    private var lastBeep = 0L

    fun update(over: Int, s: Settings) {
        settings = s
        val newLevel = over.coerceIn(0, CONTINUOUS)
        if (newLevel == level && s.vibrate == vibrateOn) return
        val rising = newLevel > level
        level = newLevel
        vibrateOn = s.vibrate
        job?.cancel()
        vibrator?.cancel()
        if (newLevel == 0) return
        job = scope.launch { loop(newLevel, immediate = rising) }
    }

    private suspend fun loop(lvl: Int, immediate: Boolean) {
        if (!immediate) delay(intervalMs(lvl))
        if (lvl >= CONTINUOUS) {
            if (settings.vibrate) vibrateContinuous()
            while (true) { beepIfDue(); delay(1000) }
        }
        while (true) {
            beepIfDue()
            if (settings.vibrate) pulses(lvl)
            delay(intervalMs(lvl))
        }
    }

    private fun beepIfDue() {
        if (!settings.beep) return
        val now = System.currentTimeMillis()
        if (now - lastBeep >= BEEP_INTERVAL) {
            lastBeep = now
            tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 400)
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
        vibrator?.cancel()
        scope.cancel()
        tone?.release()
        tone = null
        level = 0
    }
}
