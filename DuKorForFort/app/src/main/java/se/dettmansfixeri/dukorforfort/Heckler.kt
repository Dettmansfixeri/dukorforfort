package se.dettmansfixeri.dukorforfort

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
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
import java.io.File
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
        val SAMPLE = ClipLines.impactText(28) + " " + ClipLines.DRY

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
        /** Gemini-röster: första böterna efter så här länge över, minst så här tyst emellan, och var 100:e meter. */
        private const val CLIP_FIRST_DELAY = 2000L
        private const val CLIP_GAP = 3000L
        private const val CLIP_EVERY_M = 100.0
        /** Böterna sägs först från så här många km/h över gränsen (under det bara kommentarer). */
        const val FINE_FROM = 2
        /** Samma böter upprepas bara var så här många gånger – annars kommentarer. */
        private const val FINE_EVERY = 4
        private const val GAP = 5000L
        /** Beröm: efter så här lång tid inom gränsen, sedan högst så här ofta, och bara i minst så här hög fart. */
        private const val PRAISE_AFTER = 5 * 60_000L
        private const val PRAISE_EVERY = 15 * 60_000L
        private const val PRAISE_MIN_KMH = 30

        private val MEAN = listOf(
            "Du kör för fort, idiot.",
            "Hallå? Skylten säger {L}.",
            "Är gaspedalen fastlimmad, eller?",
            "Grattis. Du är officiellt en fartdåre.",
            "Jag är bara en app, MEN jag skäms ändå å dina vägnar.",
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
                    ClipLines.impactText(it) + " " + ClipLines.DRY
                }
                1 -> SpeedFacts.secondsSavedPerMil(v, l).takeIf { it >= 1 }?.let {
                    if (mean) "Du sparar $it sekunder per mil jämfört med om du hållit hastigheten. Är det värt det?"
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
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
        set(v) { if (field != v) { field = v; scope.launch { onSpeaking?.invoke(v) } } }

    /** Anropas när rösten börjar/slutar prata (pipet sänks under tiden). */
    var onSpeaking: ((Boolean) -> Unit)? = null

    /**
     * Testfunktionen Gemini-röster: inspelade klipp i stället för telefonens talsyntes.
     * Då säger appen bara det som finns inspelat – talsyntesen används inte alls.
     */
    @Volatile var useClips = false
    private var player: MediaPlayer? = null
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

    init {
        scope.launch {
            while (isActive) { tick(); delay(500) }
        }
    }

    fun update(tooFast: Boolean, speed: Int?, limit: Int?, mode: HeckleMode) {
        this.mode = mode
        // Beröm: räkna hur länge man hållit gränsen (nollställs när man kör för fort).
        if (tooFast) goodSince = 0L
        else if (speed != null && limit != null) {
            if (goodSince == 0L) goodSince = System.currentTimeMillis()
            goodSpeed = speed
        }
        if (tooFast && speed != null && limit != null) {
            if (!this.tooFast) { overSince = System.currentTimeMillis(); count = 0 }
            this.speed = speed
            this.limit = limit
            this.tooFast = true
        } else if (this.tooFast) {
            this.tooFast = false
            // Ingen "tack" när det bara är skylten som höjts – föraren har inte saktat ner.
            val limitRaised = limit != null && limit > this.limit
            if (count > 0 && mode != HeckleMode.OFF && !limitRaised) {
                if (useClips) closingClip()?.let { playClip(it, interrupt = true) }
                else say(closing(mode), interrupt = true)
            }
            count = 0
        }
    }

    /** Glömmer läget utan att säga något (t.ex. när testläget stängs). */
    fun reset() {
        tooFast = false
        count = 0
        tts.stop()
        stopClip()
        finished()
    }

    // ---------- Beröm ----------
    private var goodSince = 0L   // sedan när man hållit gränsen (0 = kör för fort / okänt)
    private var goodSpeed = 0
    private var lastPraise = 0L

    /** Berömmer när man hållit gränsen en stund: första gången efter [PRAISE_AFTER], sedan högst var [PRAISE_EVERY]. */
    private fun praiseTick() {
        if (mode == HeckleMode.OFF || tooFast || speaking || goodSince == 0L || goodSpeed < PRAISE_MIN_KMH) return
        val now = System.currentTimeMillis()
        val due = if (lastPraise < goodSince) goodSince + PRAISE_AFTER else lastPraise + PRAISE_EVERY
        if (now < due) return
        lastPraise = now   // även om det inte gick att säga – försök inte igen varje halvsekund
        val lines = ClipLines.praise(mode == HeckleMode.NICE)
        if (useClips) lines.filter { VoicePack.has(it) }.randomOrNull()?.let { playClip(it) }
        else if (_ready.value == true) say(lines.random())
    }

    private fun tick() {
        praiseTick()
        if (useClips) { clipTick(); return }
        if (!tooFast || mode == HeckleMode.OFF || _ready.value != true || speaking) return
        val now = System.currentTimeMillis()
        if (now - overSince < FIRST_DELAY) return
        if (count > 0 && now - lastDone < GAP) return
        say(if (epa && count % FINE_EVERY == 0) ClipLines.epaText(mode == HeckleMode.NICE) else line(count, mode, speed, limit))
        count++
    }

    /**
     * Säger ett besked (t.ex. förvarningen om lägre gräns), oavsett snäll/elak/av.
     * Inspelat klipp om rösterna är på, annars talsyntes. Falskt om det inte gick (då tickar det i stället).
     */
    fun announce(text: String): Boolean {
        if (useClips) return playClip(text)
        if (_ready.value != true || speaking) return false
        say(text)
        return true
    }

    fun say(text: String, interrupt: Boolean = false) {
        if (_ready.value != true) return
        speaking = true
        audio.requestAudioFocus(focus)
        tts.speak(text, if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "dkff-${System.nanoTime()}")
    }

    // ---------- Gemini-röster (klipp) ----------

    private var meters = 0.0           // körd sträcka sedan förra repliken
    private var lastBand = -1          // senaste bötesbelopp som sagts
    private var lastTick = 0L
    private val recent = ArrayDeque<String>()
    /** A-traktor/EPA som kör över 30 (sätts av SpeedTracker före [update]). */
    @Volatile var epa = false
    private var lastFile: File? = null
    /** Klipp som spelas direkt efter det som spelas nu (torrt väglag efter krockfarten). */
    private var followUp: String? = null

    /**
     * Böterna sägs direkt (efter 2 s över) och så fort beloppet går upp. Sedan var 100:e meter,
     * växelvis en kommentar och böterna, alltid minst 3 s tyst emellan.
     */
    /** Spelar ett slumpat bötesklipp direkt (knappen Provlyssna). */
    fun previewClip() { playClip(ClipLines.fineText(5, 50), interrupt = true) }

    /**
     * Provlyssna rösten i inställningarna: en slumpad kommentar för [m] (som om man kör 55 på en 50-väg).
     * [clips] = de inspelade rösterna (testfunktion), annars telefonens talsyntes.
     */
    fun preview(m: HeckleMode, clips: Boolean) {
        if (m == HeckleMode.OFF) return
        if (clips) {
            val l = ClipLines.fromGroups(VoicePack.groups, m == HeckleMode.NICE, closing = false, over = 5, limit = 50)
            val text = (l.filter { VoicePack.has(it) } + ClipLines.fineText(5, 50)).random()
            if (playClip(text, interrupt = true)) return
        }
        say(line(2 * (0..6).random(), m, 55, 50), interrupt = true)
    }

    private fun clipTick() {
        val now = System.currentTimeMillis()
        val dt = if (lastTick == 0L) 0.0 else (now - lastTick) / 1000.0
        lastTick = now
        if (!tooFast || mode == HeckleMode.OFF) { meters = 0.0; lastBand = -1; return }
        meters += speed / 3.6 * dt
        if (speaking || now - overSince < CLIP_FIRST_DELAY || now - lastDone < CLIP_GAP) return
        val over = (speed - limit).coerceAtLeast(1)
        // A-traktor/EPA över 30: ingen ordningsbot – fordonet räknas som bil (olovlig körning). Sägs i stället för böterna.
        val fineLine = if (epa) ClipLines.epaText(mode == HeckleMode.NICE) else ClipLines.fineText(over, limit)
        val fine = if (epa) 100_000 else ClipLines.fine(over, limit) + (if (ClipLines.license(over, limit)) 1 else 0)
        val fineOk = epa || over >= FINE_FROM
        val wantFine = fineOk && (lastBand < 0 || fine > lastBand)
        if (!wantFine && count > 0 && meters < CLIP_EVERY_M) return
        val text = if (wantFine || (fineOk && count % FINE_EVERY == 0)) fineLine else comment(over)
        if (text == null || !playClip(text)) {
            // Kommentaren finns inte inspelad: säg böterna i stället (men inte under FINE_FROM).
            if (!fineOk || !playClip(fineLine)) return
        } else if (ClipLines.isImpact(text) && VoicePack.has(ClipLines.DRY)) followUp = ClipLines.DRY
        if (wantFine || text == fineLine) lastBand = fine
        meters = 0.0
        count++
    }

    /**
     * Kommentarer ur röstpaketet, valda efter gruppnamnet (så nya kommentarer bara kräver ett nytt röstpaket):
     *   "Snäll …" = snäll nivå, annars elak · "tätort" = gräns 10–50 · "körkort" = vid körkortsgränsen
     *   "tack" = när man saktat ner · "skylten" = bara frasen med rätt gräns
     *   "Böter" och "NN-väg" sägs bara när de räknats ut (böterna / [ClipLines.facts]).
     */
    private fun groupPhrases(nice: Boolean, closing: Boolean, over: Int) =
        ClipLines.fromGroups(VoicePack.groups, nice, closing, over, limit)

    private fun closingClip(): String? = groupPhrases(mode == HeckleMode.NICE, closing = true, over = 1).randomOrNull()

    /** En slumpad kommentar som finns inspelad (inte samma som nyss). Elak nivå får också uträknade fakta. */
    private fun comment(over: Int): String? {
        val all = groupPhrases(mode == HeckleMode.NICE, closing = false, over = over) +
            (if (mode == HeckleMode.MEAN) ClipLines.facts(over, limit).filter { VoicePack.has(it) } else emptyList())
        val nyss = recent.takeLast((all.size - 1).coerceIn(0, 6)) // aldrig samma två gånger i rad om det finns fler
        val fresh = all.filter { it !in nyss }.ifEmpty { all }
        val pick = fresh.randomOrNull() ?: return null
        recent.addLast(pick)
        while (recent.size > 6) recent.removeFirst()
        return pick
    }

    /** Spelar ett slumpat klipp för [text]. Falskt om frasen inte finns inspelad. */
    private fun playClip(text: String, interrupt: Boolean = false, voice: String? = null): Boolean {
        val all = VoicePack.clips(text)
        // Samma röst som förra klippet om det önskas, annars inte exakt samma inspelning två gånger i rad.
        val f: File = (voice?.let { v -> all.filter { VoicePack.voiceOf(it) == v } }.orEmpty()
            .ifEmpty { all.filter { it != lastFile } }.ifEmpty { all }).randomOrNull() ?: run { VoicePack.lastPlay.value = "Inget klipp för: $text"; return false }
        if (interrupt) { followUp = null; stopClip() } else if (player != null) return false
        return try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(attrs)
            java.io.FileInputStream(f).use { mp.setDataSource(it.fd) }
            mp.setOnCompletionListener {
                stopClip()
                val next = followUp; followUp = null
                if (next == null || !playClip(next, voice = VoicePack.voiceOf(f))) finished()
            }
            mp.setOnErrorListener { _, w, x -> VoicePack.lastPlay.value = "Uppspelningsfel $w/$x: ${f.name}"; followUp = null; stopClip(); finished(); true }
            mp.prepare()
            speaking = true
            audio.requestAudioFocus(focus)
            player = mp
            lastFile = f
            mp.start()
            VoicePack.lastPlay.value = "Spelar ${f.name}: $text"
            true
        } catch (e: Exception) {
            VoicePack.lastPlay.value = "Fel: ${e.javaClass.simpleName} ${e.message} (${f.name})"
            stopClip()
            false
        }
    }

    private fun stopClip() {
        player?.let { try { it.stop() } catch (e: Exception) { }; it.release() }
        player = null
    }

    fun release() {
        followUp = null
        stopClip()
        scope.cancel()
        tts.stop()
        tts.shutdown()
        audio.abandonAudioFocusRequest(focus)
    }
}
