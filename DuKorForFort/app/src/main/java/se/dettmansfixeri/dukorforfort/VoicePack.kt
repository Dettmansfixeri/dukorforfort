package se.dettmansfixeri.dukorforfort

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Röstpaketet: de godkända inspelade klippen (Opus i WebM) som laddas ner från GitHub.
 * Röstprov på GitHub bygger rostpaket.zip (klippen + manifest.json) och rostpaket.json (version).
 */
object VoicePack {
    /** Röstprov lägger paketet som release på GitHub (tagg "rostpaket"). */
    private const val BASE = "https://github.com/Dettmansfixeri/dukorforfort/releases/download/rostpaket/"
    private const val PREFS = "rostpaket"

    sealed interface State {
        data object None : State
        data class Downloading(val progress: Float) : State
        data class Ready(val clips: Int, val version: String) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.None)
    val state: StateFlow<State> = _state

    /** Vad rösten senast spelade eller försökte spela (visas i testläget). */
    val lastPlay = MutableStateFlow<String?>(null)

    /** Fras → klippfiler (flera röster). */
    @Volatile private var byText: Map<String, List<File>> = emptyMap()

    /** Grupp (från Röstprov) → fraser. Gruppnamnet styr när fraserna används, se [Heckler]. */
    @Volatile var groups: Map<String, List<String>> = emptyMap()
        private set

    private fun dir(ctx: Context) = File(ctx.filesDir, "roster")

    /** Klippen för exakt den här frasen (tom lista om den inte finns inspelad). */
    fun clips(text: String): List<File> = byText[text].orEmpty()

    fun has(text: String) = byText.containsKey(text)

    /** Klippfil → röstnamn (från manifestet). */
    @Volatile private var voices: Map<String, String> = emptyMap()
    fun voiceOf(f: File): String? = voices[f.path]

    /** Läser in paketet som redan finns i telefonen. */
    fun load(ctx: Context) {
        val d = dir(ctx)
        val m = File(d, "manifest.json")
        // Stängdes appen mitt i ett byte ligger det förra paketet kvar som reserv: lägg tillbaka det.
        val old = File(ctx.filesDir, "roster-gammal")
        if (!m.exists() && File(old, "manifest.json").exists()) { d.deleteRecursively(); old.renameTo(d) }
        if (!m.exists()) { byText = emptyMap(); if (_state.value !is State.Downloading) _state.value = State.None; return }
        try {
            val json = JSONObject(m.readText())
            val arr = json.getJSONArray("klipp")
            val map = HashMap<String, MutableList<File>>()
            val rost = HashMap<String, String>()
            val grp = LinkedHashMap<String, LinkedHashSet<String>>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val f = File(d, o.getString("fil"))
                if (!f.exists()) continue
                map.getOrPut(o.getString("fras")) { ArrayList() } += f
                o.optString("rost").takeIf { it.isNotEmpty() }?.let { rost[f.path] = it }
                grp.getOrPut(o.optString("grupp")) { LinkedHashSet() } += o.getString("fras")
            }
            byText = map
            voices = rost
            groups = grp.mapValues { it.value.toList() }
            _state.value = State.Ready(map.values.sumOf { it.size }, json.optString("version"))
        } catch (e: Exception) {
            byText = emptyMap()
            _state.value = State.Failed("Röstpaketet gick inte att läsa")
        }
    }

    /** Bara en hämtning åt gången (tas innan första nätverksanropet). */
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Hämtar nytt röstpaket om det finns. Som med vägdatan: det nya packas upp för sig och kontrolleras
     * (manifestet går att läsa, alla klipp finns) och byts in först när allt är klart. Går något fel
     * finns det gamla paketet kvar och fungerar. Utan nät händer ingenting.
     */
    suspend fun sync(ctx: Context) = withContext(Dispatchers.IO) {
        if (!busy.compareAndSet(false, true)) return@withContext
        try { syncLocked(ctx) } finally { busy.set(false) }
    }

    private fun syncLocked(ctx: Context) {
        load(ctx)
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val remote = try {
            JSONObject(open(BASE + "rostpaket.json").use { it.readBytes().toString(Charsets.UTF_8) })
        } catch (e: Exception) { return }
        val version = remote.optString("version")
        val size = remote.optLong("size", 0)
        if (version.isEmpty() || (version == prefs.getString("version", null) && _state.value is State.Ready)) return
        // Samma regel som hastighetsdatan: wifi, eller mobildata om det är tillåtet i inställningarna.
        if (!DataManager.mayDownloadNow(ctx)) return
        val before = _state.value
        _state.value = State.Downloading(0f)
        // Samma filsystem som det riktiga paketet, så att bytet blir ett enkelt namnbyte.
        val tmp = File(ctx.filesDir, "roster-ny").apply { deleteRecursively(); mkdirs() }
        val old = File(ctx.filesDir, "roster-gammal")
        try {
            var read = 0L
            open(BASE + "rostpaket.zip").use { raw ->
                val counting = object : java.io.FilterInputStream(raw) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val n = super.read(b, off, len)
                        if (n > 0) { read += n; if (size > 0) _state.value = State.Downloading((read.toFloat() / size).coerceIn(0f, 1f)) }
                        return n
                    }
                }
                ZipInputStream(counting.buffered()).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        val name = File(e.name).name          // bara filnamn – inga mappar utanför
                        if (e.isDirectory || name.isEmpty()) continue
                        File(tmp, name).outputStream().use { zip.copyTo(it) }
                    }
                }
            }
            check(tmp)
            // Byt: nuvarande → gammal, ny → nuvarande. Misslyckas det läggs det gamla tillbaka.
            val d = dir(ctx)
            old.deleteRecursively()
            if (d.exists() && !d.renameTo(old)) throw IllegalStateException("kunde inte flytta det gamla paketet")
            if (!tmp.renameTo(d)) {
                if (old.exists()) old.renameTo(d)
                throw IllegalStateException("kunde inte byta in det nya paketet")
            }
            old.deleteRecursively()
            prefs.edit().putString("version", version).apply()
            load(ctx)
        } catch (e: Exception) {
            tmp.deleteRecursively()
            load(ctx)
            if (_state.value !is State.Ready) _state.value =
                if (before is State.Ready) before else State.Failed("Röstpaketet kunde inte laddas ner")
        }
    }

    /** Manifestet ska gå att läsa och varje klipp det nämner ska finnas (och inte vara tomt). */
    private fun check(dir: File) {
        val json = JSONObject(File(dir, "manifest.json").readText())
        val arr = json.getJSONArray("klipp")
        for (i in 0 until arr.length()) {
            val f = File(dir, File(arr.getJSONObject(i).getString("fil")).name)
            if (!f.isFile || f.length() == 0L) throw IllegalStateException("klipp saknas: ${f.name}")
        }
    }

    private fun open(url: String): java.io.InputStream {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "DuKorForFort-Android")
        c.setRequestProperty("Cache-Control", "no-cache")
        if (c.responseCode !in 200..299) { c.disconnect(); throw IllegalStateException("HTTP ${c.responseCode}") }
        return c.inputStream
    }
}

/**
 * Vad rösten ska säga, med exakt samma text som klippen spelades in med
 * (verktyg/fraser-nya.txt och bötesfraserna). Siffrorna räknas med [SpeedFacts].
 */
object ClipLines {
    val LIMITS = listOf(10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120)
    private val STEPS = listOf(1, 2, 3, 5, 7, 10, 15, 20, 30)
    /** A-traktor/EPA över 30 km/h: räknas som bil, inte fortkörning. */
    fun epaText(nice: Boolean) =
        if (nice) "Över trettio räknas din EPA som en bil, och då behöver du B-körkort."
        else "Över trettio räknas din EPA som en bil. Utan B-körkort är det olovlig körning."

    /**
     * Förvarningen om lägre gräns [kmh]. Den sägs när det är dags att släppa gasen, så tidpunkten
     * följer redan farten – därför behövs inget avstånd i frasen.
     */
    fun aheadText(kmh: Int): String = "Nu blir det ${AHEAD_WORDS[kmh] ?: kmh}."

    /** Utskrivet i ord så att rösten säger "hundra", inte "etthundra". */
    private val AHEAD_WORDS = mapOf(10 to "tio", 20 to "tjugo", 30 to "trettio", 40 to "fyrtio", 50 to "femtio",
        60 to "sextio", 70 to "sjuttio", 80 to "åttio", 90 to "nittio", 100 to "hundra", 110 to "hundratio", 120 to "hundratjugo")

    /** Beröm när man hållit gränsen en stund (snäll eller syrlig). */
    fun praise(nice: Boolean): List<String> =
        if (nice) listOf("Snyggt. Du har hållit hastigheten en bra stund nu.", "Bra jobbat, fortsätt så.", "Bra jobbat, lugnt och fint.")
        else listOf("Du har hållit hastigheten en lång stund. Jag är nästan imponerad.", "Kolla, du kan ju hålla hastigheten. Vem hade trott det?")

    /** Krockfarten när man tvärnitar där man hade stannat om man hållit gränsen. Följs av [DRY]. */
    fun impactText(kmh: Int): String =
        "Behöver du tvärnita nu så krockar du i $kmh kilometer i timmen, där du istället hade hunnit stanna om du hållit hastigheten."
    fun isImpact(text: String) = text.startsWith("Behöver du tvärnita")

    /** GPS-beskeden. Sägs alltid, oavsett ljud- och röstinställning (gruppen "GPS" i fraslistan). */
    const val GPS_MISSING = "GPS-signal saknas."
    const val GPS_LOST = "GPS-signal förlorad."
    const val GPS_BACK = "GPS-signal återställd."

    /** Eget klipp som spelas direkt efter krockfarten (uträkningen gäller torr asfalt och bra däck). */
    const val DRY = "Och det är vid torrt väglag."

    /** Steget (km/h över) som fraserna är inspelade för: närmast under eller lika med [over]. */
    fun step(over: Int, limit: Int): Int? =
        (if (limit <= 30) STEPS.filter { it <= 20 } else STEPS).lastOrNull { it <= over }

    /** Körkortet ryker (busskort): 21 över på väg med gräns 30 eller lägre, annars 31 över. */
    fun license(over: Int, limit: Int) = over >= Beeper.licenseOver(limit)

    /** Ordningsboten (kr) för [over] km/h över på väg med gränsen [limit]. */
    /** Böterna (kr) för bil. Gångfart (5 i datan): alltid 2 000 kr, oavsett fart (SFS 2024:1084, 8 kap. p. 7.1). */
    fun fine(over: Int, limit: Int): Int = if (limit == SpeedTracker.GANGFART) 2000 else if (limit <= 50) when {
        over <= 10 -> 2000; over <= 15 -> 2400; over <= 20 -> 2800; over <= 25 -> 3200; over <= 30 -> 3600; else -> 4000
    } else when {
        over <= 10 -> 1500; over <= 15 -> 2000; over <= 20 -> 2400; over <= 25 -> 2800; over <= 30 -> 3200; over <= 35 -> 3600; else -> 4000
    }

    private val WORDS = mapOf(
        1500 to "ett tusen femhundra", 2000 to "två tusen", 2400 to "två tusen fyrahundra", 2800 to "två tusen åttahundra",
        3200 to "tre tusen tvåhundra", 3600 to "tre tusen sexhundra", 4000 to "fyra tusen",
    )

    fun fineText(over: Int, limit: Int): String =
        "Det här kostar dig ${WORDS[fine(over, limit)]} kronor i böter." +
            if (license(over, limit)) " Plus busskort i minst två månader." else ""

    fun signText(limit: Int) = "Hallå? Skylten säger $limit."

    /** De uträknade fraserna för gränsen och steget (bara de som inte blir löjligt små). */
    fun facts(over: Int, limit: Int): List<String> {
        if (limit !in LIMITS) return emptyList()
        val st = step(over, limit) ?: return emptyList()
        val s = limit + st
        val out = ArrayList<String>()
        SpeedFacts.impactKmh(s, limit).takeIf { it >= 5 }?.let { out += impactText(it) }
        // Sparade sekunder och luftmotstånd säger inget på 10–30-vägar – bara på snabbare vägar.
        if (limit > 30) SpeedFacts.secondsSavedPerMil(s, limit).takeIf { it >= 5 }?.let { out += "Du sparar $it sekunder per mil jämfört med om du hållit hastigheten. Är det värt det?" }
        if (limit > 30) SpeedFacts.extraDragPercent(s, limit).takeIf { it >= 5 }?.let { out += "Nu har du ökat luftmotståndet med $it procent jämfört med om du hållit hastigheten. Plånboken gråter." }
        return out
    }



    // ---------- Högar: hur grov en kommentar är ----------
    // Hög 1 sägs direkt, hög 2 när man är en bit över eller legat över ett tag, hög 3 först långt över eller länge.
    // Allt som inte står här (och Skylten-fraserna) är hög 1. Matchas utan versaler och skiljetecken.

    internal val PILE_2 = listOf(
        "Tror du att skyltarna är förslag?",
        "Fortsätt så, så får du snart träffa polisen.",
        "Jag är bara en app, MEN jag skäms ändå å dina vägnar.",
        "Jag tjatar bara för att jag bryr mig om dig.",
        "Det här är ingen motorväg, även om du verkar tro det.",
        "Du kör som om du ägde gatan. Det gör du inte.",
        "Lugna ner dig, racerföraren.",
        "Hoppas du hinner bromsa för katten.",
        "Nästa farthinder kommer att göra ont. Bra.",
        "Kör du alltid så här genom folks trädgårdar?",
        "Vet dina föräldrar att du kör så här?... De skäms.... Det vet jag.",
        "Du är inte sen. Du är bara en idiot.",
    )
    internal val PILE_3 = listOf(
        "Är gaspedalen fastlimmad, eller?",
        "Grattis. Du är officiellt en fartdåre.",
        "Det här är en gata, inte Le Mans.",
        "Skolbarn hinner inte undan i hundra.",
        "I den här farten blir en fotgängare en olycka, inte en incident.",
        "Grattis, du är officiellt trafikens största jävla pucko.",
        "Ditt körkort borde ha legat kvar i kartongen med flingorna, för det var där du hittade det.",
        "Det är så här folk dör. Meeeen... Kör du.",
        "Jag hoppas du har bra försäkring. Och en bra advokat.",
    )

    private fun key(text: String) = text.lowercase().filter { it.isLetterOrDigit() }
    private val PILES: Map<String, Int> = PILE_2.associate { key(it) to 2 } + PILE_3.associate { key(it) to 3 }

    /** Vilken hög frasen hör till (1–3). */
    fun pile(text: String): Int = PILES[key(text)] ?: 1

    /** Den grövsta hög som får sägas: hög 2 från 6 km/h över eller 10 s, hög 3 från 16 km/h över eller 30 s. */
    fun maxPile(over: Int, overMs: Long): Int = when {
        over >= 16 || overMs >= 30_000L -> 3
        over >= 6 || overMs >= 10_000L -> 2
        else -> 1
    }

    /** Hur ofta frasen ska väljas: den grövsta tillåtna högen 3 gånger så ofta som lägre högar, otillåtna aldrig. */
    fun pileWeight(text: String, maxPile: Int): Int {
        val p = pile(text)
        return when { p > maxPile -> 0; p == maxPile -> 3; else -> 1 }
    }

    /** Slumpar en text där vikten avgör hur ofta (vikt 3 = tre gånger så ofta som vikt 1). */
    fun pickWeighted(items: List<Pair<String, Int>>, rnd: kotlin.random.Random = kotlin.random.Random): String? {
        val total = items.sumOf { it.second.coerceAtLeast(0) }
        if (total <= 0) return null
        var r = rnd.nextInt(total)
        for ((t, w) in items) { if (w <= 0) continue; if (r < w) return t; r -= w }
        return null
    }

    /** Fraser ur röstpaketets grupper som passar läget (se gruppreglerna i Heckler). */
    /** "Äh, glöm det. Bra." – när man saktar ner mitt i en kommentar. Grupperna "Glöm det", "Snäll glöm det" och "Brutal glöm det". */
    fun forget(groups: Map<String, List<String>>, mode: HeckleMode): List<String> {
        fun of(prefix: String?) = groups.filterKeys { g ->
            val gl = g.lowercase()
            gl.endsWith("glöm det") && (if (prefix == null) !gl.startsWith("snäll") && !gl.startsWith("brutal") else gl.startsWith(prefix))
        }.values.flatten()
        return when (mode) {
            HeckleMode.NICE -> of("snäll")
            HeckleMode.BRUTAL -> of("brutal").filter { VoicePack.has(it) }.ifEmpty { of(null) }   // Brutal: egna, annars elaka
            else -> of(null)
        }
    }

    /** Brutal-gruppernas fraser ("Brutal" och "Brutal tack"). */
    fun brutal(groups: Map<String, List<String>>, closing: Boolean): List<String> =
        groups.filterKeys { it.lowercase().startsWith("brutal") && ("tack" in it.lowercase()) == closing && !it.lowercase().endsWith("glöm det") }.values.flatten()

    /** [brutal] = nivån Brutal: även Brutal-grupperna (annars hoppas de över). */
    fun fromGroups(groups: Map<String, List<String>>, nice: Boolean, closing: Boolean, over: Int, limit: Int, brutal: Boolean = false): List<String> {
        val license = license(over, limit)
        val siffra = Regex("\\b$limit\\b")
        return buildList {
            for ((g, fraser) in groups) {
                val gl = g.lowercase()
                if (gl.startsWith("snäll") != nice) continue
                if (gl.startsWith("brutal") && !brutal) continue
                if (gl == "böter" || gl.endsWith("-väg") || gl.startsWith("förvarning") || gl.startsWith("gps") || gl.endsWith("epa") || gl.startsWith("torrt") || gl == "krockfart" || gl.endsWith("beröm") || gl.endsWith("glöm det")) continue
                if (("tack" in gl) != closing) continue
                if ("körkort" in gl && !license) continue
                if ("tätort" in gl && limit > 50) continue
                if ("skylten" in gl) addAll(fraser.filter { siffra.containsMatchIn(it) }) else addAll(fraser)
            }
        }
    }
}
