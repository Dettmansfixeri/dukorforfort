package se.dettmansfixeri.dukorforfort

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Händelseloggen bakom knappen "Markera": appen minns hela tiden de senaste sekunderna
 * (position, fart, gräns, väg, förvarning och vilka röstklipp som spelades). Trycker man Markera
 * sparas 15 s före och 15 s efter i en textfil på telefonen. Filerna lämnar bara telefonen
 * om man själv trycker Dela.
 */
object EventLog {
    private const val BEFORE_MS = 15_000L
    private const val AFTER_MS = 15_000L
    private const val KEEP_MS = 40_000L

    private class Row(val t: Long, val text: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val rows = ArrayDeque<Row>()
    private var started = false
    private lateinit var dir: File

    private val _files = MutableStateFlow<List<File>>(emptyList())
    /** Sparade markeringar, nyast först. */
    val files: StateFlow<List<File>> = _files

    /** Sekunder kvar tills markeringen sparas (0 = ingen markering pågår). */
    private val _countdown = MutableStateFlow(0)
    val countdown: StateFlow<Int> = _countdown

    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val fileName = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")

    fun start(ctx: Context) {
        if (started) return
        started = true
        dir = File(ctx.applicationContext.filesDir, "markeringar").apply { mkdirs() }
        refresh()
        scope.launch {
            SpeedTracker.state.collect { s ->
                val d = SpeedTracker.debug.value
                add(listOfNotNull(
                    "fart ${s.speedKmh ?: "?"}",
                    "gräns ${s.limitKmh ?: "?"}" + (if (s.signedKmh != null && s.signedKmh != s.limitKmh) " (skyltat ${s.signedKmh})" else ""),
                    s.reason,
                    if (s.tooFast) "FÖR FORT" else null,
                    if (s.limitStale) "gammal gräns" else null,
                    if (s.manual) "påhittad gräns" else null,
                    s.aheadKmh?.let { "förvarning $it" + (s.aheadBlinkMs?.let { ms -> " (tickar $ms ms)" } ?: "") },
                    d.road,
                    if (d.lat != null && d.lon != null) "GPS %.6f,%.6f".format(java.util.Locale.ROOT, d.lat, d.lon) +
                        (d.accuracy?.let { " ±${it.toInt()} m" } ?: "") else null,
                    s.guess,
                    s.status,
                ).joinToString(" · "))
            }
        }
        scope.launch {
            VoicePack.lastPlay.collect { if (it != null) add("RÖST: $it") }
        }
    }

    private fun add(text: String) {
        val now = SystemClock.elapsedRealtime()
        rows.addLast(Row(now, text))
        // Under en pågående markering behövs allt från 15 s före trycket.
        val keep = if (markAt > 0) markAt - BEFORE_MS else now - KEEP_MS
        while (rows.isNotEmpty() && rows.first().t < keep) rows.removeFirst()
    }

    private var markAt = 0L

    /** Knappen Markera. Ett nytt tryck under en pågående markering räknas inte. */
    fun mark(note: String? = null) {
        if (!started || markAt > 0) return
        val at = SystemClock.elapsedRealtime()
        markAt = at
        val wall = LocalDateTime.now()
        add("=== MARKERAD ===" + (note?.let { " $it" } ?: ""))
        scope.launch {
            for (left in (AFTER_MS / 1000).toInt() downTo 1) { _countdown.value = left; delay(1000) }
            _countdown.value = 0
            save(at, wall)
            markAt = 0
        }
    }

    private fun save(at: Long, wall: LocalDateTime) {
        val s = SpeedTracker.settings.value
        val head = buildString {
            appendLine("Du kör för fort, IDIOT! – markering ${wall.toLocalDate()} ${wall.toLocalTime().withNano(0)}")
            appendLine("App v${BuildConfig.VERSION_NAME} · ${s.vehicle.label}${if (s.trailer) " + släp" else ""} · marginal ${s.margin} · röst ${s.heckleOn.label}")
            appendLine("Tid = sekunder från trycket (−15 … +15).")
            appendLine()
        }
        val body = rows.filter { it.t in (at - BEFORE_MS)..(at + AFTER_MS) }.joinToString("\n") { r ->
            val sec = (r.t - at) / 1000.0
            val wallT = wall.plusNanos((r.t - at) * 1_000_000)
            "%+6.1f s  %s  %s".format(java.util.Locale.ROOT, sec, clock.format(wallT), r.text)
        }
        File(dir, "${fileName.format(wall)}.txt").writeText(head + body + "\n")
        refresh()
    }

    // ---------- Skylt här ----------
    private const val SIGN_CHECK_MS = 10_000L
    private val _signs = MutableStateFlow(0)
    /** Antal sparade skyltar i skyltar.csv. */
    val signs: StateFlow<Int> = _signs
    val signFile get() = File(dir, "skyltar.csv")

    /**
     * Knappen "Skylt här": man trycker när man passerar en skylt. Sparar platsen, färdriktningen och
     * vad datan säger om gränsen nu och 10 s senare (så syns det om datans gränsbyte ligger på fel ställe).
     * Gör också en vanlig markering (15 s före/efter) om ingen pågår.
     */
    fun sign() {
        if (!started) return
        val s = SpeedTracker.state.value
        val d = SpeedTracker.debug.value
        if (d.lat == null || d.lon == null) return
        val wall = LocalDateTime.now()
        val before = s.limitKmh
        add("=== SKYLT HÄR === gräns i datan: ${before ?: "?"}")
        if (markAt == 0L) mark("(skylt)")
        scope.launch {
            delay(SIGN_CHECK_MS)
            val after = SpeedTracker.state.value.limitKmh
            val f = signFile
            if (!f.exists()) f.writeText("tid;lat;lon;riktning;fart;noggrannhet_m;gräns_i_datan;gräns_i_datan_10s_senare;väg\n")
            f.appendText(listOf(
                wall.withNano(0).toString(),
                "%.6f".format(java.util.Locale.ROOT, d.lat), "%.6f".format(java.util.Locale.ROOT, d.lon),
                d.bearing?.toInt()?.toString() ?: "", s.speedKmh?.toString() ?: "", d.accuracy?.toInt()?.toString() ?: "",
                before?.toString() ?: "", after?.toString() ?: "", (d.road ?: "").replace(';', ','),
            ).joinToString(";") + "\n")
            refresh()
        }
    }

    private fun refresh() {
        _signs.value = if (signFile.exists()) (signFile.readLines().size - 1).coerceAtLeast(0) else 0
        _files.value = (dir.listFiles { f -> f.name.endsWith(".txt") }?.toList() ?: emptyList()).sortedByDescending { it.name }
    }

    fun delete(f: File) { f.delete(); refresh() }

    // ---------- Skicka till servern ----------
    private const val UPLOAD_URL = "https://dukorforfort.dettmansfixeri.se/markering.php"
    private val _upload = MutableStateFlow<String?>(null)
    /** Hur det gick senast med "Skicka till servern" (null = inte skickat). */
    val uploadStatus: StateFlow<String?> = _upload

    /**
     * Skickar alla markeringar och skyltar.csv till den egna servern (mapp utanför webbroten).
     * Bara när man själv trycker på knappen. [onWrongCode] anropas om servern säger fel kodord.
     */
    fun upload(kod: String, onWrongCode: () -> Unit) {
        if (!started) return
        val list = _files.value + listOfNotNull(signFile.takeIf { it.exists() })
        if (list.isEmpty()) { _upload.value = "Inget att skicka"; return }
        _upload.value = "Skickar…"
        scope.launch {
            val sent = kotlinx.coroutines.withContext(Dispatchers.IO) {
                var ok = 0
                for (f in list) {
                    when (post(kod, f.name, f.readText())) {
                        200 -> ok++
                        403 -> return@withContext -1
                    }
                }
                ok
            }
            if (sent < 0) { _upload.value = "Fel kodord – försök igen"; onWrongCode() }
            else _upload.value = "Skickat $sent av ${list.size}" + if (sent < list.size) " (ingen kontakt med servern?)" else " ✓"
        }
    }

    private fun post(kod: String, namn: String, text: String): Int = try {
        val c = java.net.URL(UPLOAD_URL).openConnection() as java.net.HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        val body = org.json.JSONObject().put("kod", kod).put("namn", namn).put("text", text).toString()
        c.outputStream.use { it.write(body.toByteArray()) }
        c.responseCode.also { c.disconnect() }
    } catch (e: Exception) { 0 }

    /** Dela-menyn i Android med en eller flera markeringar (positionerna lämnar telefonen först här). */
    fun share(ctx: Context, list: List<File>) {
        if (list.isEmpty()) return
        val uris = ArrayList(list.map { FileProvider.getUriForFile(ctx, "${ctx.packageName}.filer", it) })
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        send.type = if (list.all { it.name.endsWith(".csv") }) "text/csv" else "text/plain"
        send.putExtra(Intent.EXTRA_SUBJECT, "Markeringar från Du kör för fort")
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Dela markeringar").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
