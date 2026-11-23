package se.dettmansfixeri.dukorforfort

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Hastighetsdatan i telefonen: laddas ner från GitHub-releasen och kontrolleras
 * mot version.json (version, storlek och SHA-256).
 */
object DataManager {

    private const val BASE = "https://github.com/Dettmansfixeri/dukorforfort/releases/latest/download/"
    private const val SUPPORTED_FORMAT = 1
    private const val PREFS = "data"
    private const val KEY_VERSION = "version"
    private const val KEY_LAST_CHECK = "senaste_kontroll"
    private const val KEY_MOBILE = "mobildata"
    /** Ungefärlig storlek innan version.json är läst (visas i frågan om mobildata). */
    const val APPROX_SIZE = 51L * 1024 * 1024
    const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    class Remote(val version: String, val file: String, val size: Long, val sha256: String, val unpacked: Long)

    sealed interface State {
        data object Missing : State
        data class Ready(val version: String) : State
        /** [read] och [total] i byte (total 0 = okänd). */
        data class Downloading(val progress: Float, val read: Long = 0, val total: Long = 0) : State
        data class Failed(val message: String, val hasData: Boolean) : State
    }

    private val _state = MutableStateFlow<State>(State.Missing)
    val state: StateFlow<State> = _state

    /** Satt när en nyare version finns på GitHub. */
    private val _update = MutableStateFlow<Remote?>(null)
    val update: StateFlow<Remote?> = _update

    @Volatile var data: SpeedData? = null
        private set

    /** "Får använda mobildata" i inställningarna (av som standard). */
    private val _mobileData = MutableStateFlow(false)
    val mobileData: StateFlow<Boolean> = _mobileData

    /** Senast kända storlek på datafilen (byte). */
    @Volatile var knownSize: Long = APPROX_SIZE
        private set

    /** Nyare data som hittats men inte laddats ner än. */
    @Volatile private var pending: Remote? = null

    /** Bara en nedladdning åt gången (appen, bevakningen och bakgrundsjobbet kan alla vilja ladda ner). */
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun dataFile(ctx: Context) = File(ctx.filesDir, "hastigheter.bin")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun localVersion(ctx: Context): String? = prefs(ctx).getString(KEY_VERSION, null)

    fun setMobileData(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_MOBILE, on).apply()
        _mobileData.value = on
        DataUpdates.schedule(ctx)
    }

    /** Går nätet via mobildata (eller annat som kostar per MB)? Utan nät: nej (då misslyckas nedladdningen ändå). */
    fun onMetered(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Får vi ladda ner nu utan att fråga? Wifi alltid, mobildata bara om det är tillåtet. */
    fun mayDownloadNow(ctx: Context) = _mobileData.value || !onMetered(ctx)

    /**
     * Kollar efter ny data och laddar ner den direkt om det går utan att fråga (wifi, eller
     * mobildata om det är tillåtet). Annars visas frågan i appen som förut.
     */
    suspend fun autoUpdate(ctx: Context): Boolean {
        _mobileData.value = prefs(ctx).getBoolean(KEY_MOBILE, false)
        checkForUpdate(ctx, auto = true) ?: return false
        if (pending != null && mayDownloadNow(ctx)) return download(ctx)
        _update.value = pending
        return false
    }

    /** Öppnar datan som redan finns i telefonen. */
    @Synchronized
    fun load(ctx: Context) {
        _mobileData.value = prefs(ctx).getBoolean(KEY_MOBILE, false)
        if (data != null) return
        val opened = SpeedData.open(dataFile(ctx))
        val v = localVersion(ctx)
        if (opened != null && v != null) {
            data = opened
            _state.value = State.Ready(v)
        } else {
            _state.value = State.Missing
        }
    }

    /** Ska vi kolla efter ny version? (vid start, och sedan högst en gång per dygn) */
    fun shouldCheck(ctx: Context, force: Boolean): Boolean =
        force || System.currentTimeMillis() - prefs(ctx).getLong(KEY_LAST_CHECK, 0) >= CHECK_INTERVAL_MS

    /**
     * Hämtar version.json. Sätter [update] om den är nyare än den lokala.
     * Utan nät händer ingenting.
     */
    suspend fun checkForUpdate(ctx: Context, auto: Boolean = false): Remote? = withContext(Dispatchers.IO) {
        val remote = try {
            fetchRemote()
        } catch (e: Exception) {
            return@withContext null
        }
        prefs(ctx).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        val local = localVersion(ctx)
        val newer = remote != null && (local == null || remote.version > local)
        if (remote != null) knownSize = remote.size
        pending = if (newer && data != null) remote else null
        if (!auto) _update.value = pending
        remote
    }

    fun dismissUpdate() { _update.value = null }

    private fun fetchRemote(): Remote? {
        val json = JSONObject(open(BASE + "version.json").use { it.readBytes().toString(Charsets.UTF_8) })
        if (json.optInt("format", 0) != SUPPORTED_FORMAT) return null
        return Remote(
            version = json.getString("version"),
            file = json.getString("fil"),
            size = json.getLong("storlek"),
            sha256 = json.getString("sha256"),
            unpacked = json.optLong("okomprimerad", 0),
        )
    }

    private fun open(url: String): java.io.InputStream {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "DuKorForFort-Android")
        if (c.responseCode !in 200..299) {
            c.disconnect()
            throw IllegalStateException("HTTP ${c.responseCode}")
        }
        return c.inputStream
    }

    /** Laddar ner och installerar senaste datan. Den gamla finns kvar tills den nya är klar. */
    suspend fun download(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        if (!busy.compareAndSet(false, true)) return@withContext false
        try { downloadNow(ctx) } finally { busy.set(false) }
    }

    private fun downloadNow(ctx: Context): Boolean {
        val hadData = data != null
        _state.value = State.Downloading(0f)
        _update.value = null
        val tmp = File(ctx.filesDir, "hastigheter.bin.tmp")
        try {
            val remote = fetchRemote() ?: throw IllegalStateException("Appen behöver uppdateras för att läsa den nya datan.")
            knownSize = remote.size
            _state.value = State.Downloading(0f, 0, remote.size)
            val need = (if (remote.unpacked > 0) remote.unpacked else remote.size * 2) + 20L * 1024 * 1024
            if (ctx.filesDir.usableSpace < need) {
                throw IllegalStateException("Inte tillräckligt med lagringsutrymme (behöver ${need / 1048576} MB).")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var read = 0L
            open(BASE + remote.file).use { raw ->
                val counting = object : java.io.FilterInputStream(raw) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val n = super.read(b, off, len)
                        if (n > 0) {
                            read += n
                            _state.value = State.Downloading((read.toFloat() / remote.size).coerceIn(0f, 1f), read, remote.size)
                        }
                        return n
                    }
                }
                GZIPInputStream(DigestInputStream(counting, digest), 64 * 1024).use { gz ->
                    tmp.outputStream().buffered(256 * 1024).use { out -> gz.copyTo(out, 256 * 1024) }
                }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (hex != remote.sha256) throw IllegalStateException("Filen blev skadad under nedladdningen. Försök igen.")
            if (SpeedData.open(tmp) == null) throw IllegalStateException("Filen gick inte att läsa.")

            synchronized(this@DataManager) {
                val target = dataFile(ctx)
                // Atomiskt byte: antingen ligger den nya filen på plats, eller så är den gamla orörd.
                // Den gamla datan används ända tills den nya är öppnad.
                try {
                    Files.move(tmp.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (e: Exception) {
                    throw IllegalStateException("Kunde inte spara filen.")
                }
                val opened = SpeedData.open(target)
                if (opened != null) {
                    prefs(ctx).edit().putString(KEY_VERSION, remote.version).apply()
                    pending = null
                    data = opened
                    _state.value = State.Ready(remote.version)
                } else {
                    data = null
                    _state.value = State.Missing
                }
            }
            return data != null
        } catch (e: Exception) {
            tmp.delete()
            if (hadData && data == null) load(ctx)
            _state.value = State.Failed(
                e.message ?: "Nedladdningen misslyckades. Kontrollera internetanslutningen.",
                hasData = data != null,
            )
            return false
        }
    }

    /** "12 345 / 51 200 kB" (total okänd: bara det som laddats ner). */
    fun kbText(read: Long, total: Long): String {
        val f = java.text.NumberFormat.getIntegerInstance(java.util.Locale("sv", "SE"))
        return if (total > 0) "${f.format(read / 1024)} / ${f.format(total / 1024)} kB" else "${f.format(read / 1024)} kB"
    }

    /** "20261120-1609" -> "20 nov 2026" */
    fun prettyVersion(v: String): String {
        if (v.length < 8) return v
        val months = listOf("jan", "feb", "mar", "apr", "maj", "jun", "jul", "aug", "sep", "okt", "nov", "dec")
        val m = v.substring(4, 6).toIntOrNull() ?: return v
        return "${v.substring(6, 8).trimStart('0')} ${months.getOrElse(m - 1) { "?" }} ${v.substring(0, 4)}"
    }
}
