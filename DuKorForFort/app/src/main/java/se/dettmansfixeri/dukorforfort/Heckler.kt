package se.dettmansfixeri.dukorforfort

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class HeckleMode(val label: String) {
    OFF("Av"), NICE("Snäll"), MEAN("Elak");

    companion object {
        fun fromName(n: String?) = entries.firstOrNull { it.name == n } ?: NICE
    }
}

/**
 * Fakta som räknas ut för just din fart och gräns.
 * Antar 1 s reaktionstid och inbromsning 7 m/s² (torr asfalt, bra bromsar).
 */
object SpeedFacts {
    private const val REACTION_S = 1.0
    private const val DECEL = 7.0

    fun stopDistance(kmh: Int): Double {
        val v = kmh / 3.6
        return v * REACTION_S + v * v / (2 * DECEL)
    }

    /** Hur fort man kör (km/h) när man når punkten där man hade stannat om man hållit [limit]. */
    fun impactKmh(speed: Int, limit: Int): Int {
        val v = speed / 3.6
        val braking = stopDistance(limit) - v * REACTION_S
        if (braking <= 0) return speed
        val v2 = v * v - 2 * DECEL * braking
        return if (v2 <= 0) 0 else (sqrt(v2) * 3.6).roundToInt()
    }

    /** Sekunder man sparar per mil (10 km). */
    fun secondsSavedPerMil(speed: Int, limit: Int): Int =
        (36000.0 / limit - 36000.0 / speed).roundToInt()

    fun extraStopMeters(speed: Int, limit: Int): Int =
        (stopDistance(speed) - stopDistance(limit)).roundToInt()

    /** Hur mycket större luftmotståndet är, i procent. */
    fun extraDragPercent(speed: Int, limit: Int): Int =
        ((speed.toDouble() / limit).let { it * it } * 100 - 100).roundToInt()
}

/**
 * Pratar med föraren när man kör för fort: förolämpningar blandat med uträknade fakta,
 * grövre för varje gång. Första efter 5 s över gränsen, sedan 5 s efter att förra meningen är klar.
 */
/** [engine] = talmotorns paketnamn (null = telefonens standard), [initialVoice] = vald röst (null = bästa svenska). */
class Heckler(ctx: Context, val engine: String? = null, initialVoice: String? = null) {

    companion object {
        const val SAMPLE = "Där du hade stannat i 70 kör du nu på i 28 kilometer i timmen. Grattis, du är officiellt en fartdåre."

        fun isSwedish(v: Voice) = v.locale.language == "sv"
        fun needsNetwork(v: Voice) = v.isNetworkConnectionRequired ||
                TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS in v.features.orEmpty()
        fun notInstalled(v: Voice) = TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in v.features.orEmpty()

        fun qualityLabel(q: Int) = when {
            q >= Voice.QUALITY_VERY_HIGH -> "Mycket hög"
            q >= Voice.QUALITY_HIGH -> "Hög"
            q >= Voice.QUALITY_NORMAL -> "Normal"
            q >= Voice.QUALITY_LOW -> "Låg"
            else -> "Mycket låg"
        }

        /** Bästa svenska rösten som finns i telefonen och inte kräver internet. */
        fun bestSwedish(all: List<Voice>): Voice? = all
            .filter { isSwedish(it) && !needsNetwork(it) && !notInstalled(it) }
            .maxWithOrNull(compareBy<Voice>({ it.quality }, { if (it.locale.country == "SE") 1 else 0 }))

        private const val FIRST_DELAY = 5000L
        private const val GAP = 5000L

        private val MEAN = listOf(
            "Du kör för fort, idiot.",
            "Hallå? Skylten säger {L}.",
            "Är gaspedalen fastlimmad, eller?",
            "Grattis. Du är officiellt en fartdåre.",
            "Jag är bara en app, och jag skäms ändå å dina vägnar.",
            "Fortsätt så, så får du snart träffa polisen.",
            "Tror du att skyltarna är förslag?",
        )
        private val NICE = listOf(
            "Du kör lite för fort.",
            "Skylten säger {L}, bara så du vet.",
            "Kanske dags att släppa lite på gasen?",
            "Det vore snällt om du saktade ner nu.",
            "Jag tjatar bara för att jag bryr mig om dig.",
            "Snälla, sakta ner lite.",
            "Det är inte bråttom. Du kommer fram ändå.",
        )

        /** Uträknade fakta. Returnerar null om faktat inte blir vettigt i just den farten. */
        private fun fact(i: Int, mode: HeckleMode, v: Int, l: Int): String? {
            val mean = mode == HeckleMode.MEAN
            return when (i % 4) {
                0 -> SpeedFacts.impactKmh(v, l).takeIf { it >= 5 }?.let {
                    "Där du hade stannat i $l kör du nu på i $it kilometer i timmen."
                }
                1 -> SpeedFacts.secondsSavedPerMil(v, l).takeIf { it >= 1 }?.let {
                    if (mean) "Du sparar $it sekunder per mil. Var det värt det?"
                    else "Du sparar bara $it sekunder per mil."
                }
                2 -> SpeedFacts.extraStopMeters(v, l).takeIf { it >= 1 }?.let {
                    if (mean) "Din stoppsträcka är $it meter längre än den borde. Hoppas ingen går över vägen."
                    else "Din stoppsträcka är $it meter längre än i $l."
                }
                else -> SpeedFacts.extraDragPercent(v, l).takeIf { it >= 2 }?.let {
                    if (mean) "Luftmotståndet är $it procent större. Plånboken gråter."
                    else "Luftmotståndet är $it procent större, och det kostar bränsle."
                }
            }
        }

        /** Mening nummer [n] i en följd: förolämpning, fakta, förolämpning, fakta … */
        fun line(n: Int, mode: HeckleMode, speed: Int, limit: Int): String {
            val insults = if (mode == HeckleMode.MEAN) MEAN else NICE
            val insult = { k: Int ->
                // De första i ordning, sedan runt bland de grövre.
                val idx = if (k < insults.size) k else 2 + (k - insults.size) % (insults.size - 2)
                insults[idx].replace("{L}", limit.toString())
            }
            if (n % 2 == 1) fact(n / 2, mode, speed, limit)?.let { return it }
            return insult(n / 2 + n % 2)
        }

        fun closing(mode: HeckleMode) =
            if (mode == HeckleMode.MEAN) "Tack. Var det så svårt?" else "Tack, nu kör du lagligt igen."
    }

    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs).build()

    private val _ready = MutableStateFlow<Boolean?>(null)
    /** null = startar, true = svensk röst finns, false = ingen svensk röst. */
    val ready: StateFlow<Boolean?> = _ready

    @Volatile private var speaking = false
    @Volatile private var lastDone = 0L
    @Volatile private var restoreVoice = false
    private var voiceName: String? = initialVoice

    private val tts: TextToSpeech = TextToSpeech(ctx.applicationContext, { status ->
        _ready.value = status == TextToSpeech.SUCCESS && setup()
    }, engine)

    private fun setup(): Boolean {
        val r = tts.setLanguage(Locale("sv", "SE"))
        tts.setAudioAttributes(attrs)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { speaking = true }
            override fun onDone(id: String?) = finished()
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) = finished()
        })
        applyVoice()
        val swedish = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        return swedish || voiceName != null
    }

    // ---------- Röster ----------

    /** Alla talmotorer i telefonen (Google, Samsung …). */
    fun engines(): List<TextToSpeech.EngineInfo> = try { tts.engines } catch (e: Exception) { emptyList() }

    /** Alla röster i den här motorn. */
    fun voices(): List<Voice> = try { tts.voices?.toList().orEmpty() } catch (e: Exception) { emptyList() }

    /** Rösten som faktiskt används just nu. */
    fun currentVoice(): Voice? = try { tts.voice } catch (e: Exception) { null }

    fun setVoiceName(name: String?) {
        voiceName = name
        if (_ready.value == true) applyVoice()
    }

    /** Vald röst, annars den bästa svenska som inte kräver internet. */
    private fun applyVoice() {
        val all = voices()
        val pick = all.firstOrNull { it.name == voiceName } ?: bestSwedish(all)
        if (pick != null) try { tts.voice = pick } catch (e: Exception) { }
    }

    /** Provar en röst utan att välja den. Den valda rösten används igen efteråt. */
    fun tryVoice(v: Voice, text: String) {
        if (_ready.value == null) return
        try { tts.voice = v } catch (e: Exception) { return }
        restoreVoice = true
        speaking = true
        audio.requestAudioFocus(focus)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "prov-${System.nanoTime()}")
    }

    private fun finished() {
        speaking = false
        lastDone = System.currentTimeMillis()
        if (restoreVoice) { restoreVoice = false; applyVoice() }
        audio.abandonAudioFocusRequest(focus)
    }

    // Senaste läget, uppdateras från bevakningen.
    private var tooFast = false
    private var speed = 0
    private var limit = 0
    private var mode = HeckleMode.NICE
    private var overSince = 0L
    private var count = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        scope.launch {
            while (isActive) { tick(); delay(500) }
        }
    }

    fun update(tooFast: Boolean, speed: Int?, limit: Int?, mode: HeckleMode) {
        this.mode = mode
        if (tooFast && speed != null && limit != null) {
            if (!this.tooFast) { overSince = System.currentTimeMillis(); count = 0 }
            this.speed = speed
            this.limit = limit
            this.tooFast = true
        } else if (this.tooFast) {
            this.tooFast = false
            if (count > 0 && mode != HeckleMode.OFF) say(closing(mode), interrupt = true)
            count = 0
        }
    }

    /** Glömmer läget utan att säga något (t.ex. när testläget stängs). */
    fun reset() {
        tooFast = false
        count = 0
        tts.stop()
        finished()
    }

    private fun tick() {
        if (!tooFast || mode == HeckleMode.OFF || _ready.value != true || speaking) return
        val now = System.currentTimeMillis()
        if (now - overSince < FIRST_DELAY) return
        if (count > 0 && now - lastDone < GAP) return
        say(line(count, mode, speed, limit))
        count++
    }

    fun say(text: String, interrupt: Boolean = false) {
        if (_ready.value != true) return
        speaking = true
        audio.requestAudioFocus(focus)
        tts.speak(text, if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "dkff-${System.nanoTime()}")
    }

    fun release() {
        scope.cancel()
        tts.stop()
        tts.shutdown()
        audio.abandonAudioFocusRequest(focus)
    }
}
