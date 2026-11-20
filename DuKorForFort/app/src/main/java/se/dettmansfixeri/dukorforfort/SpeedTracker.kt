package se.dettmansfixeri.dukorforfort

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import kotlin.math.roundToInt

data class UiState(
    val running: Boolean = false,
    val speedKmh: Int? = null,
    /** Gränsen som gäller för valt fordon just nu – det som visas på skylten. */
    val limitKmh: Int? = null,
    /** Skyltad gräns på vägen (utan fordon/tid), för förklaringen under skylten. */
    val signedKmh: Int? = null,
    /** Varför [limitKmh] skiljer sig från [signedKmh], t.ex. "släp" eller "tidsbegränsning". */
    val reason: String? = null,
    val limitStale: Boolean = false,
    val status: String = "Väntar på GPS…",
    val tooFast: Boolean = false,
)

data class Settings(
    val vehicle: Vehicle = Vehicle.PERSONBIL,
    val trailer: Boolean = false,
    /** Hur många km/h över gränsen man får köra innan appen varnar. */
    val margin: Int = 0,
)

/**
 * Själva bevakningen: GPS, uppslag av hastighetsgräns i den lokala datan och pip.
 * Ägs av [SpeedService] så att den fortsätter när en annan app är framme.
 */
object SpeedTracker {

    private const val PREFS = "installningar"
    private const val KEY_VEHICLE = "fordon"
    private const val KEY_TRAILER = "slap"
    private const val KEY_MARGIN = "marginal"
    const val MAX_MARGIN = 10

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var locationManager: LocationManager? = null
    private var tone: ToneGenerator? = null
    private val matcher = RoadMatcher()
    private var lastBeep = 0L
    private var lastFix: Location? = null
    private var updateJob: Job? = null
    private var appContext: Context? = null

    private val listener = LocationListener { onLocation(it) }

    fun loadSettings(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _settings.value = Settings(
            Vehicle.fromName(p.getString(KEY_VEHICLE, null)),
            p.getBoolean(KEY_TRAILER, false),
            p.getInt(KEY_MARGIN, 0).coerceIn(0, MAX_MARGIN),
        )
    }

    fun setSettings(ctx: Context, s: Settings) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_VEHICLE, s.vehicle.name).putBoolean(KEY_TRAILER, s.trailer)
            .putInt(KEY_MARGIN, s.margin).apply()
        _settings.value = s
        lastFix?.let { onLocation(it) } // räkna om direkt
    }

    @SuppressLint("MissingPermission")
    fun start(context: Context) {
        if (_state.value.running) return
        val ctx = context.applicationContext
        appContext = ctx
        loadSettings(ctx)
        DataManager.load(ctx)
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationManager = lm
        tone = try { ToneGenerator(AudioManager.STREAM_ALARM, 90) } catch (e: Exception) { null }
        matcher.reset()
        _state.value = UiState(running = true)
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())

        // Kolla efter ny hastighetsdata en gång per dygn medan appen är igång.
        updateJob = scope.launch {
            while (isActive) {
                delay(DataManager.CHECK_INTERVAL_MS)
                DataManager.checkForUpdate(ctx)
            }
        }
    }

    fun stop() {
        locationManager?.removeUpdates(listener)
        locationManager = null
        tone?.release()
        tone = null
        updateJob?.cancel()
        updateJob = null
        lastFix = null
        _state.value = UiState(running = false, status = "Stoppad")
    }

    private fun onLocation(loc: Location) {
        if (!_state.value.running) return
        lastFix = loc
        val speed = if (loc.hasSpeed()) (loc.speed * 3.6f).roundToInt() else null
        val data = DataManager.data

        if (data == null) {
            _state.update { it.copy(speedKmh = speed, limitKmh = null, signedKmh = null, reason = null,
                tooFast = false, status = "Hastighetsdata saknas") }
            return
        }

        val (e, n) = Sweref99.fromWgs84(loc.latitude, loc.longitude)
        // Kursen är bara pålitlig när man rör sig.
        val course = if (loc.hasBearing() && (speed ?: 0) >= 8) loc.bearing else null
        val accuracy = if (loc.hasAccuracy()) loc.accuracy else null
        val road = matcher.match(data, e, n, accuracy, course)

        if (road == null) {
            _state.update { it.copy(speedKmh = speed, limitKmh = null, signedKmh = null, reason = null,
                tooFast = false, status = "Ingen väg med hastighetsgräns i närheten") }
            return
        }

        val s = _settings.value
        var limit = road.kmh
        var reason: String? = null
        if (road.ruleId >= 0) {
            val now = LocalDateTime.now()
            val active = data.rules.getOrNull(road.ruleId).orEmpty().filter { it.applies(now, s.vehicle, s.trailer) }
            active.minByOrNull { it.kmh }?.let { limit = it.kmh; reason = "tids-/fordonsregel" }
        }
        s.vehicle.cap(road.kmh, s.trailer)?.let { cap ->
            if (cap < limit) {
                limit = cap
                reason = if (s.trailer && cap == Vehicle.TRAILER_MAX) "släp" else s.vehicle.label.lowercase()
            }
        }

        val tooFast = speed != null && speed > limit + s.margin
        _state.update {
            it.copy(speedKmh = speed, limitKmh = limit, signedKmh = road.kmh, reason = reason,
                limitStale = false, tooFast = tooFast,
                status = "Hastighetsdata: Trafikverket (ISA)")
        }
        if (tooFast) maybeBeep()
    }

    private fun maybeBeep() {
        val now = System.currentTimeMillis()
        if (now - lastBeep > 5000) {
            lastBeep = now
            tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 400)
        }
    }
}
