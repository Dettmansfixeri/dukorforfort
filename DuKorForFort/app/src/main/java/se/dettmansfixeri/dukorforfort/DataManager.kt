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
    const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    class Remote(val version: String, val file: String, val size: Long, val sha256: String, val unpacked: Long)

    sealed interface State {
        data object Missing : State
        data class Ready(val version: String) : State
        data class Downloading(val progress: Float) : State
        data class Failed(val message: String, val hasData: Boolean) : State
    }

    private val _state = MutableStateFlow<State>(State.Missing)
    val state: StateFlow<State> = _state

    /** Satt när en nyare version finns på GitHub. */
    private val _update = MutableStateFlow<Remote?>(null)
    val update: StateFlow<Remote?> = _update

    @Volatile var data: SpeedData? = null
        private set

    private fun dataFile(ctx: Context) = File(ctx.filesDir, "hastigheter.bin")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun localVersion(ctx: Context): String? = prefs(ctx).getString(KEY_VERSION, null)

    /** Öppnar datan som redan finns i telefonen. */
    @Synchronized
    fun load(ctx: Context) {
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
    suspend fun checkForUpdate(ctx: Context): Remote? = withContext(Dispatchers.IO) {
        val remote = try {
            fetchRemote()
        } catch (e: Exception) {
            return@withContext null
        }
        prefs(ctx).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        val local = localVersion(ctx)
        val newer = remote != null && (local == null || remote.version > local)
        _update.value = if (newer && data != null) remote else null
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
        val hadData = data != null
        _state.value = State.Downloading(0f)
        _update.value = null
        val tmp = File(ctx.filesDir, "hastigheter.bin.tmp")
        try {
            val remote = fetchRemote() ?: throw IllegalStateException("Appen behöver uppdateras för att läsa den nya datan.")
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
                            _state.value = State.Downloading((read.toFloat() / remote.size).coerceIn(0f, 1f))
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
                    data = opened
                    _state.value = State.Ready(remote.version)
                } else {
                    data = null
                    _state.value = State.Missing
                }
            }
            data != null
        } catch (e: Exception) {
            tmp.delete()
            if (hadData && data == null) load(ctx)
            _state.value = State.Failed(
                e.message ?: "Nedladdningen misslyckades. Kontrollera internetanslutningen.",
                hasData = data != null,
            )
            false
        }
    }

    /** "20261120-1609" -> "20 nov 2026" */
    fun prettyVersion(v: String): String {
        if (v.length < 8) return v
        val months = listOf("jan", "feb", "mar", "apr", "maj", "jun", "jul", "aug", "sep", "okt", "nov", "dec")
        val m = v.substring(4, 6).toIntOrNull() ?: return v
        return "${v.substring(6, 8).trimStart('0')} ${months.getOrElse(m - 1) { "?" }} ${v.substring(0, 4)}"
    }
}
