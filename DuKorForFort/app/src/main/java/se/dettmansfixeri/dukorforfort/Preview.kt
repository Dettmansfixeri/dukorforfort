package se.dettmansfixeri.dukorforfort

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Provlyssning i inställningarna: hur varningsljudet låter, och tyst läge. */
object Preview {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var beeper: Beeper? = null
    private var music: MusicStopper? = null

    /**
     * Spelar [mode] som om man låg 2, 5 och sedan 10 km/h över (så man hör hur det ökar).
     * Tyst läge: spelas musik pausas den i några sekunder, annars hörs pipet som då används.
     * [volume] = pipets volym i procent (null = sparad inställning). [steps] = vilka km/h över som spelas.
     */
    fun sound(ctx: Context, mode: SoundMode, volume: Int? = null, output: BeepOutput? = null, steps: List<Int> = listOf(2, 5, 10)) {
        stop()
        job = scope.launch {
            if (mode == SoundMode.SILENT) {
                val m = MusicStopper(ctx.applicationContext)
                if (m.otherMusicPlaying()) {
                    music = m
                    m.hold(true)
                    delay(4000)
                    stop()
                    return@launch
                }
            }
            val b = Beeper().also { beeper = it; it.volume = volume; it.output = output }
            for (over in steps) {
                b.play(Beeper.pattern(over, tone = mode == SoundMode.TONE))
                delay(1800)
            }
            stop()
        }
    }

    fun stop() {
        job?.cancel(); job = null
        beeper?.stop(); beeper = null
        music?.hold(false); music = null
    }
}
