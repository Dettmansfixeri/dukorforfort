package se.dettmansfixeri.dukorforfort

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Kollar vid varje start om det finns en nyare version av appen (app-version.json på webbsidan,
 * som GitHub-bygget lägger dit). Finns det kan man ladda ner APK:n och installera den direkt.
 */
object AppUpdater {

    private const val VERSION_URL = "https://dukorforfort.dettmansfixeri.se/app-version.json"
    /** Bara APK:er härifrån laddas ner, vad json-filen än säger. */
    private const val ALLOWED_PREFIX = "https://github.com/Dettmansfixeri/dukorforfort/releases/download/"

    /** [changes] = vad som är nytt, per version (nyast först), bara versioner nyare än den man har. */
    class Remote(val version: String, val url: String, val size: Long, val changes: List<Pair<String, List<String>>> = emptyList())

    /** Läser "changes" ur app-version.json: [{v: "1.1.35", c: ["…", "…"]}, …], bara versioner nyare än [local]. */
    fun parseChanges(json: JSONObject, local: String): List<Pair<String, List<String>>> {
        val arr = json.optJSONArray("changes") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val v = o.optString("v")
            val c = o.optJSONArray("c") ?: return@mapNotNull null
            val lines = (0 until c.length()).map { c.optString(it) }.filter { it.isNotBlank() }
            if (lines.isEmpty() || !isNewer(v, local)) null else v to lines
        }
    }

    sealed interface State {
        data object None : State
        data class Available(val remote: Remote) : State
        data class Downloading(val remote: Remote, val progress: Float) : State
        data class Ready(val remote: Remote, val file: File) : State
        data class Failed(val remote: Remote, val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.None)
    val state: StateFlow<State> = _state

    /** Är [remote] nyare än [local]? Lokala byggen ("1.1-lokal") frågar aldrig. */
    fun isNewer(remote: String, local: String): Boolean {
        if (local.contains("lokal")) return false
        fun parts(v: String): List<Int>? = v.split('.').map { it.toIntOrNull() ?: return null }
        val r = parts(remote) ?: return false
        val l = parts(local) ?: return false
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** Hämtar app-version.json. Utan nät händer ingenting. */
    suspend fun check() = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject(open(VERSION_URL).use { it.readBytes().toString(Charsets.UTF_8) })
            val remote = Remote(json.getString("version"), json.getString("url"), json.optLong("size", 0),
                parseChanges(json, BuildConfig.VERSION_NAME))
            if (!remote.url.startsWith(ALLOWED_PREFIX)) return@withContext
            if (isNewer(remote.version, BuildConfig.VERSION_NAME) && remote.version != dismissed &&
                _state.value is State.None) _state.value = State.Available(remote)
        } catch (e: Exception) {
            // ingen kontakt – försök igen nästa gång
        }
    }

    /** Version man tryckt "Senare" på – frågar inte igen om den förrän appen startas om. */
    private var dismissed: String? = null

    fun dismiss() {
        when (val s = _state.value) {
            is State.Available -> dismissed = s.remote.version
            is State.Failed -> dismissed = s.remote.version
            is State.Ready -> dismissed = s.remote.version
            else -> {}
        }
        _state.value = State.None
    }

    /** Laddar ner APK:n till appens cache. */
    suspend fun download(ctx: Context, remote: Remote) = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "uppdatering").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "DuKorForFort.apk")
        _state.value = State.Downloading(remote, 0f)
        try {
            var read = 0L
            open(remote.url).use { input ->
                file.outputStream().buffered(256 * 1024).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (remote.size > 0) _state.value = State.Downloading(remote, (read.toFloat() / remote.size).coerceIn(0f, 1f))
                    }
                }
            }
            if (remote.size > 0 && read != remote.size) throw IllegalStateException("Filen blev inte hel. Försök igen.")
            _state.value = State.Ready(remote, file)
        } catch (e: Exception) {
            file.delete()
            _state.value = State.Failed(remote, e.message ?: "Nedladdningen misslyckades. Kontrollera internetanslutningen.")
        }
    }

    /** Får appen installera appar? Annars måste man slå på det en gång i telefonens inställningar. */
    fun mayInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Öppnar Androids installation för den nedladdade filen. */
    fun install(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.filer", file)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun open(url: String): java.io.InputStream {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "DuKorForFort-Android")
        c.setRequestProperty("Cache-Control", "no-cache")
        if (c.responseCode !in 200..299) {
            c.disconnect()
            throw IllegalStateException("HTTP ${c.responseCode}")
        }
        return c.inputStream
    }
}
