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
 * Röstpaketet: de godkända Gemini-klippen (Opus i WebM) som laddas ner från webbsidan.
 * Röstprov på GitHub bygger rostpaket.zip (klippen + manifest.json) och rostpaket.json (version).
 */
object VoicePack {
    private const val BASE = "https://dukorforfort.dettmansfixeri.se/rostprov/"
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

    private fun dir(ctx: Context) = File(ctx.filesDir, "roster")

    /** Klippen för exakt den här frasen (tom lista om den inte finns inspelad). */
    fun clips(text: String): List<File> = byText[text].orEmpty()

    fun has(text: String) = byText.containsKey(text)

    /** Läser in paketet som redan finns i telefonen. */
    fun load(ctx: Context) {
        val d = dir(ctx)
        val m = File(d, "manifest.json")
        if (!m.exists()) { byText = emptyMap(); if (_state.value !is State.Downloading) _state.value = State.None; return }
        try {
            val json = JSONObject(m.readText())
            val arr = json.getJSONArray("klipp")
            val map = HashMap<String, MutableList<File>>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val f = File(d, o.getString("fil"))
                if (f.exists()) map.getOrPut(o.getString("fras")) { ArrayList() } += f
            }
            byText = map
            _state.value = State.Ready(map.values.sumOf { it.size }, json.optString("version"))
        } catch (e: Exception) {
            byText = emptyMap()
            _state.value = State.Failed("Röstpaketet gick inte att läsa")
        }
    }

    /** Hämtar nytt röstpaket om det finns. Utan nät händer ingenting (det gamla finns kvar). */
    suspend fun sync(ctx: Context) = withContext(Dispatchers.IO) {
        if (_state.value is State.Downloading) return@withContext
        load(ctx)
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val remote = try {
            JSONObject(open(BASE + "rostpaket.json").use { it.readBytes().toString(Charsets.UTF_8) })
        } catch (e: Exception) { return@withContext }
        val version = remote.optString("version")
        val size = remote.optLong("size", 0)
        if (version.isEmpty() || (version == prefs.getString("version", null) && _state.value is State.Ready)) return@withContext
        _state.value = State.Downloading(0f)
        val tmp = File(ctx.cacheDir, "rostpaket-ny").apply { deleteRecursively(); mkdirs() }
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
            if (!File(tmp, "manifest.json").exists()) throw IllegalStateException("manifest.json saknas")
            val d = dir(ctx)
            d.deleteRecursively()
            if (!tmp.renameTo(d)) { tmp.copyRecursively(d, overwrite = true); tmp.deleteRecursively() }
            prefs.edit().putString("version", version).apply()
            load(ctx)
        } catch (e: Exception) {
            tmp.deleteRecursively()
            load(ctx)
            if (_state.value !is State.Ready) _state.value = State.Failed("Röstpaketet kunde inte laddas ner")
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

    /** Steget (km/h över) som fraserna är inspelade för: närmast under eller lika med [over]. */
    fun step(over: Int, limit: Int): Int? =
        (if (limit <= 30) STEPS.filter { it <= 20 } else STEPS).lastOrNull { it <= over }

    /** Körkortet ryker (busskort): 21 över på väg med gräns 30 eller lägre, annars 31 över. */
    fun license(over: Int, limit: Int) = over >= Beeper.licenseOver(limit)

    /** Ordningsboten (kr) för [over] km/h över på väg med gränsen [limit]. */
    fun fine(over: Int, limit: Int): Int = if (limit <= 50) when {
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
        SpeedFacts.impactKmh(s, limit).takeIf { it >= 5 }?.let { out += "Där du hade stannat i $limit kör du nu på i $it kilometer i timmen." }
        SpeedFacts.secondsSavedPerMil(s, limit).takeIf { it >= 5 }?.let { out += "Du sparar $it sekunder per mil jämfört med om du hållit hastigheten. Är det värt det?" }
        SpeedFacts.extraStopMeters(s, limit).takeIf { it >= 2 }?.let { out += "Din stoppsträcka är $it meter längre än om du hållit hastigheten. Hoppas ingen går över vägen." }
        SpeedFacts.extraDragPercent(s, limit).takeIf { it >= 5 }?.let { out += "Nu har du ökat luftmotståndet med $it procent jämfört med om du hållit hastigheten. Plånboken gråter." }
        return out
    }

    val INSULTS = listOf(
        "Du kör för fort, IDIOT!",
        "Är gaspedalen fastlimmad, eller?",
        "Grattis. Du är officiellt en fartdåre.",
        "Jag är bara en app, MEN jag skäms ändå å dina vägnar.",
        "Fortsätt så, så får du snart träffa polisen.",
        "Tror du att skyltarna är förslag?",
    )
    const val CYCLE = "Utmärkt! Fortkörning kan leda till bättre hälsa… DÅ DU MÅSTE CYKLA!"
    const val THANKS = "Tack. Var det så svårt?"
}

