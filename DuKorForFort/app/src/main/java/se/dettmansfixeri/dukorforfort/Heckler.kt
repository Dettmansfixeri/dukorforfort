package se.dettmansfixeri.dukorforfort

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class HeckleMode(val label: String) {
    OFF("Av"), NICE("Snäll"), MEAN("Elak"), BRUTAL("Brutal");

    /** Elak eller Brutal: elaka fraser, uträknade fakta och elakt beröm. */
    val harsh get() = this == MEAN || this == BRUTAL

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
 * Pratar med föraren när man kör för fort, med inspelade röster ur röstpaketet:
 * böterna, kommentarer och uträknade fakta. Bara det som finns inspelat sägs. Telefonens talsyntes används inte.
 */
class Heckler(ctx: Context) {

    companion object {
        /** Första böterna efter så här länge över, minst så här tyst emellan, och var 100:e meter. */
        private const val CLIP_FIRST_DELAY = 2000L
        private const val CLIP_GAP = 3000L
        private const val CLIP_EVERY_M = 100.0
        /** Böterna sägs först från så här många km/h över gränsen (under det bara kommentarer). */
        const val FINE_FROM = 2
        /** Samma böter upprepas bara var så här många gånger – annars kommentarer. */
        private const val FINE_EVERY = 4
        /** Beröm: efter så här lång tid inom gränsen, sedan högst så här ofta, och bara i minst så här hög fart. */
        private const val PRAISE_AFTER = 5 * 60_000L
        private const val PRAISE_EVERY = 15 * 60_000L
        private const val PRAISE_MIN_KMH = 30
        /** Avslutningen ("Tack, var det så svårt?" o.d.) sägs bara om man kört minst så här långt för fort. */
        private const val CLOSING_MIN_M = 500.0
        /** Brutal: rösten släpper dig inte – kortare tystnad och tätare kommentarer. */
        private const val BRUTAL_PACE = 0.6
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs).build()

    @Volatile private var speaking = false
        set(v) { if (field != v) { field = v; scope.launch { onSpeaking?.invoke(v) } } }

    /** Anropas när rösten börjar/slutar prata (pipet sänks under tiden). */
    var onSpeaking: ((Boolean) -> Unit)? = null

    private var player: MediaPlayer? = null
    @Volatile private var lastDone = 0L

    private fun finished() {
        speaking = false
        lastDone = System.currentTimeMillis()
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
        // Beröm: räkna hur länge man KÖRT inom gränsen (stillastående och krypfart räknas inte,
        // nollställs när man kör för fort). Uppehåll i uppdateringarna räknas högst 5 s.
        val now = System.currentTimeMillis()
        if (tooFast) { goodMs = 0L; praisedAt = 0L }
        else if (speed != null && limit != null) {
            if (speed >= PRAISE_MIN_KMH && goodTick != 0L) goodMs += (now - goodTick).coerceIn(0L, 5000L)
            goodSpeed = speed
        }
        goodTick = now
        if (tooFast && speed != null && limit != null) {
            if (!this.tooFast) { overSince = System.currentTimeMillis(); count = 0; overMeters = 0.0; pendingClosing = null }
            this.speed = speed
            this.limit = limit
            this.tooFast = true
        } else if (this.tooFast) {
            this.tooFast = false
            // Ingen "tack" när det bara är skylten som höjts – föraren har inte saktat ner.
            val limitRaised = limit != null && limit > this.limit
            if (count > 0 && mode != HeckleMode.OFF && !limitRaised) {
                // Mitt i en kommentar: avbryt med "Äh, glöm det. Bra." i samma röst – det låter med flit.
                val forget = if (heckling) ClipLines.forget(VoicePack.groups, mode).filter { VoicePack.has(it) }.randomOrNull() else null
                val voice = lastFile?.let { VoicePack.voiceOf(it) }
                if (forget != null) playClip(forget, interrupt = true, voice = voice)
                // Annars: avbryt inte det rösten säger nu – säg avslutningen när den pratat klart.
                // Bara efter en längre sträcka för fort, inte när man rättat till sig direkt.
                else if (overMeters >= CLOSING_MIN_M) closingClip()?.let { if (player != null) pendingClosing = it else playClip(it) }
            }
            count = 0
        }
    }

    /** Glömmer läget utan att säga något (t.ex. när testläget stängs). [hush] = tystna direkt. */
    fun reset(hush: Boolean = true) {
        tooFast = false
        count = 0
        pendingClosing = null
        if (!hush) return    // det som sägs nu får pratas klart
        followUp = null
        stopClip()
        finished()
    }

    // ---------- Beröm ----------
    private var goodMs = 0L      // körtid (ms) inom gränsen i minst PRAISE_MIN_KMH sedan man senast körde för fort
    private var goodTick = 0L
    private var goodSpeed = 0
    private var praisedAt = 0L   // goodMs vid senaste berömmet (0 = inget beröm sedan man körde för fort)

    /** Berömmer när man kört inom gränsen en stund: första gången efter [PRAISE_AFTER] körtid, sedan högst var [PRAISE_EVERY]. */
    private fun praiseTick() {
        if (mode == HeckleMode.OFF || tooFast || speaking || goodSpeed < PRAISE_MIN_KMH) return
        val due = if (praisedAt == 0L) PRAISE_AFTER else praisedAt + PRAISE_EVERY
        if (goodMs < due) return
        praisedAt = goodMs   // även om det inte gick att säga – försök inte igen varje halvsekund
        ClipLines.praise(mode == HeckleMode.NICE).filter { VoicePack.has(it) }.randomOrNull()?.let { playClip(it) }
    }

    private fun tick() {
        praiseTick()
        clipTick()
    }

    /**
     * Säger ett besked (t.ex. förvarningen om lägre gräns), oavsett snäll/elak/av.
     * Falskt om det inte gick – klippet saknas eller rösten pratar redan (då tickar det i stället).
     */
    fun announce(text: String): Boolean = playClip(text)

    /** Provlyssna rösten i inställningarna: en slumpad kommentar för [m] (som om man kör 55 på en 50-väg). Falskt om inget klipp finns. */
    fun preview(m: HeckleMode, volume: Int? = null): Boolean {
        if (m == HeckleMode.OFF) return previewClip(volume)
        val l = ClipLines.fromGroups(VoicePack.groups, m == HeckleMode.NICE, closing = false, over = 5, limit = 50, brutal = m == HeckleMode.BRUTAL)
        val text = (l.filter { VoicePack.has(it) } + ClipLines.fineText(5, 50)).random()
        return playClip(text, interrupt = true, volume = volume)
    }

    // ---------- Klippen ----------

    private var meters = 0.0           // körd sträcka sedan förra repliken
    private var overMeters = 0.0       // körd sträcka för fort den här gången
    private var lastBand = -1          // senaste bötesbelopp som sagts
    private var lastTick = 0L
    private val recent = ArrayDeque<String>()
    /** A-traktor/EPA som kör över 30 (sätts av SpeedTracker före [update]). */
    @Volatile var epa = false
    private var lastFile: File? = null
    /** Klipp som spelas direkt efter det som spelas nu (torrt väglag efter krockfarten). */
    private var followUp: String? = null
    /** Avslutning ("tack" o.d.) som sägs när klippet som spelas nu är slut. */
    private var pendingClosing: String? = null
    /** Klippet som spelas nu är en kommentar eller böterna (inte beröm, förvarning eller avslutning). */
    private var heckling = false

    /** Spelar ett slumpat bötesklipp direkt (knappen Provlyssna). */
    fun previewClip(volume: Int? = null) = playClip(ClipLines.fineText(5, 50), interrupt = true, volume = volume)

    /**
     * Böterna sägs direkt (efter 2 s över) och så fort beloppet går upp. Sedan var 100:e meter,
     * växelvis en kommentar och böterna, alltid minst 3 s tyst emellan.
     */
    private fun clipTick() {
        val now = System.currentTimeMillis()
        val dt = if (lastTick == 0L) 0.0 else (now - lastTick) / 1000.0
        lastTick = now
        if (!tooFast || mode == HeckleMode.OFF) { meters = 0.0; lastBand = -1; return }
        meters += speed / 3.6 * dt
        overMeters += speed / 3.6 * dt
        val pace = if (mode == HeckleMode.BRUTAL) BRUTAL_PACE else 1.0
        if (speaking || now - overSince < CLIP_FIRST_DELAY || now - lastDone < CLIP_GAP * pace) return
        val over = (speed - limit).coerceAtLeast(1)
        // A-traktor/EPA över 30: ingen ordningsbot – fordonet räknas som bil (olovlig körning). Sägs i stället för böterna.
        val fineLine = if (epa) ClipLines.epaText(mode == HeckleMode.NICE) else ClipLines.fineText(over, limit)
        val fine = if (epa) 100_000 else ClipLines.fine(over, limit) + (if (ClipLines.license(over, limit)) 1 else 0)
        val fineOk = epa || over >= FINE_FROM
        val wantFine = fineOk && (lastBand < 0 || fine > lastBand)
        if (!wantFine && count > 0 && meters < CLIP_EVERY_M * pace) return
        val text = if (wantFine || (fineOk && count % FINE_EVERY == 0)) fineLine else comment(over)
        if (text == null || !playClip(text)) {
            // Kommentaren finns inte inspelad: säg böterna i stället (men inte under FINE_FROM).
            if (!fineOk || !playClip(fineLine)) return
        } else if (ClipLines.isImpact(text) && VoicePack.has(ClipLines.DRY)) followUp = ClipLines.DRY
        heckling = true
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
        ClipLines.fromGroups(VoicePack.groups, nice, closing, over, limit, brutal = mode == HeckleMode.BRUTAL)

    /** Bara Brutal-gruppernas fraser (som finns inspelade). */
    private fun brutalPhrases(closing: Boolean) =
        ClipLines.brutal(VoicePack.groups, closing).filter { VoicePack.has(it) }

    private fun closingClip(): String? =
        (if (mode == HeckleMode.BRUTAL) brutalPhrases(closing = true).randomOrNull() else null)
            ?: groupPhrases(mode == HeckleMode.NICE, closing = true, over = 1).filterNot { it in ClipLines.brutal(VoicePack.groups, true) }.randomOrNull()

    /**
     * En slumpad kommentar som finns inspelad (inte samma som nyss). Elak nivå får också uträknade fakta.
     * Kommentarerna följer hur långt över och hur länge man legat över (högarna i [ClipLines.pile]):
     * de grövsta som passar väljs oftast. Uträknade fakta väljs lika ofta som dem.
     */
    private fun comment(over: Int): String? {
        val maxPile = ClipLines.maxPile(over, System.currentTimeMillis() - overSince)
        val facts = if (mode.harsh) ClipLines.facts(over, limit).filter { VoicePack.has(it) } else emptyList()
        val phrases = groupPhrases(mode == HeckleMode.NICE, closing = false, over = over) +
            // Brutal: de egna förolämpningarna dubbelt så ofta som de vanliga elaka.
            (if (mode == HeckleMode.BRUTAL) brutalPhrases(closing = false) else emptyList())
        val weighted = phrases.map { it to ClipLines.pileWeight(it, maxPile) }.filter { it.second > 0 } +
            facts.map { it to 3 }
        // Finns inget som passar (t.ex. bara grova inspelade): hellre en grövre än ingen alls.
        val all = weighted.ifEmpty { phrases.map { it to 1 } }
        val nyss = recent.takeLast((all.size - 1).coerceIn(0, 6)) // aldrig samma två gånger i rad om det finns fler
        val fresh = all.filter { it.first !in nyss }.ifEmpty { all }
        val pick = ClipLines.pickWeighted(fresh) ?: return null
        recent.addLast(pick)
        while (recent.size > 6) recent.removeFirst()
        return pick
    }

    /** Spelar ett slumpat klipp för [text]. Falskt om frasen inte finns inspelad. */
    private fun playClip(text: String, interrupt: Boolean = false, voice: String? = null, volume: Int? = null): Boolean {
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
                if (next != null && playClip(next, voice = VoicePack.voiceOf(f))) return@setOnCompletionListener
                // Avslutningen som väntade på att rösten skulle prata klart.
                val closing = pendingClosing; pendingClosing = null
                if (closing == null || tooFast || !playClip(closing)) finished()
            }
            mp.setOnErrorListener { _, w, x -> VoicePack.lastPlay.value = "Uppspelningsfel $w/$x: ${f.name}"; followUp = null; stopClip(); finished(); true }
            mp.prepare()
            setVolume(mp, volume ?: SpeedTracker.settings.value.voiceVolume)
            speaking = true
            onSpeaking?.invoke(true)   // tysta pipet direkt, innan rösten börjar (settern gör det först en stund senare)
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

    private var enhancer: LoudnessEnhancer? = null

    /**
     * Röstens volym i procent av standard. Under 100 sänks klippet; över 100 förstärks det
     * (med en inbyggd begränsare så att det inte spricker). Telefonens volym ändras inte.
     */
    private fun setVolume(mp: MediaPlayer, pct: Int) {
        val v = pct.coerceIn(SpeedTracker.VOICE_VOLUME)
        if (v <= 100) { mp.setVolume(v / 100f, v / 100f); return }
        try {
            enhancer = LoudnessEnhancer(mp.audioSessionId).apply {
                setTargetGain((2000 * kotlin.math.log10(v / 100.0)).toInt())   // millibel
                enabled = true
            }
        } catch (e: Exception) { }
    }

    private fun stopClip() {
        heckling = false
        enhancer?.release(); enhancer = null
        player?.let { try { it.stop() } catch (e: Exception) { }; it.release() }
        player = null
    }

    fun release() {
        followUp = null
        stopClip()
        scope.cancel()
        audio.abandonAudioFocusRequest(focus)
    }
}
