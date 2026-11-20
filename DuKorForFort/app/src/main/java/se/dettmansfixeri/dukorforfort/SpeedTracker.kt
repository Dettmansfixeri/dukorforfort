package se.dettmansfixeri.dukorforfort

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    /** Startad automatiskt och väntar på att farten ska gå över [SpeedTracker.AUTO_ARM_KMH]. */
    val waiting: Boolean = false,
    /** Startad automatiskt (då finns knappen "Jag kör inte"). */
    val auto: Boolean = false,
)

enum class AutoMode(val label: String) {
    OFF("Av"),
    DRIVING("När jag åker"),
    CAR("Bara i min bil (Bluetooth)");

    companion object {
        fun fromName(n: String?) = entries.firstOrNull { it.name == n } ?: OFF
    }
}

data class Settings(
    val vehicle: Vehicle = Vehicle.PERSONBIL,
    val trailer: Boolean = false,
    /** Hur många km/h över gränsen man får köra innan appen varnar. */
    val margin: Int = 0,
    val beep: Boolean = true,
    val vibrate: Boolean = true,
    val heckle: HeckleMode = HeckleMode.NICE,
    /** Talmotor (paketnamn) och röst. null = standardmotorn / bästa svenska rösten. */
    val voiceEngine: String? = null,
    val voiceName: String? = null,
    val auto: AutoMode = AutoMode.OFF,
    val carAddress: String? = null,
    val carName: String? = null,
)

/** Teknisk info för testsidan. */
data class DebugInfo(
    val accuracy: Float? = null,
    val satsUsed: Int = 0,
    val satsTotal: Int = 0,
    val lat: Double? = null,
    val lon: Double? = null,
    val fixTime: Long = 0,
    val road: String? = null,
)

/**
 * Själva bevakningen: GPS, uppslag av hastighetsgräns i den lokala datan, vibration och pip.
 * Ägs av [SpeedService] så att den fortsätter när en annan app är framme.
 */
object SpeedTracker {

    private const val PREFS = "installningar"
    const val MAX_MARGIN = 10
    const val AUTO_ARM_KMH = 20
    private const val MIN = 60_000L

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings

    private val _debug = MutableStateFlow(DebugInfo())
    val debug: StateFlow<DebugInfo> = _debug

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var locationManager: LocationManager? = null
    private var warner: Warner? = null
    var heckler: Heckler? = null
        private set
    private val matcher = RoadMatcher()
    private var lastFix: Location? = null
    private var updateJob: Job? = null
    private var watchdogJob: Job? = null
    private var simulating = false

    // Automatisk start/stopp
    private var autoStarted = false
    private var armed = true
    private var startedAt = 0L
    private var lastMovingAt = 0L
    private var exitAt: Long? = null

    /** Sätts av tjänsten – anropas när bevakningen ska stängas av automatiskt. */
    var onStopRequest: (() -> Unit)? = null

    // GPS-bortfall: kommer ingen position på GPS_TIMEOUT_MS tystas varningen tills en färsk kommer.
    private const val GPS_TIMEOUT_MS = 5_000L
    private var lastFixAt = 0L
    private var gpsLost = false
    private var gpsJob: Job? = null

    private fun fixIsFresh() = lastFix != null && SystemClock.elapsedRealtime() - lastFixAt <= GPS_TIMEOUT_MS

    /** Räknar om med senaste positionen, men bara om den är färsk. */
    private fun recompute() { if (fixIsFresh()) lastFix?.let { onLocation(it) } }

    private val listener = LocationListener {
        lastFixAt = SystemClock.elapsedRealtime()
        gpsLost = false
        onLocation(it)
    }

    private fun checkGps() {
        if (!_state.value.running || simulating || gpsLost) return
        val since = if (lastFix == null) null else SystemClock.elapsedRealtime() - lastFixAt
        if (since == null || since <= GPS_TIMEOUT_MS) return
        gpsLost = true
        _state.update { it.copy(speedKmh = null, limitStale = it.limitKmh != null, tooFast = false,
            status = if (!armed) waitingText() else "GPS-signal saknas – varningen är tyst") }
        warner?.update(0, _settings.value)
        heckler?.reset() // tystnar utan "tack"-repliken
    }
    private val gnss = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            _debug.update { it.copy(satsUsed = used, satsTotal = status.satelliteCount) }
        }
    }

    fun loadSettings(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _settings.value = Settings(
            vehicle = Vehicle.fromName(p.getString("fordon", null)),
            trailer = p.getBoolean("slap", false),
            margin = p.getInt("marginal", 0).coerceIn(0, MAX_MARGIN),
            beep = p.getBoolean("pip", true),
            vibrate = p.getBoolean("vibrera", true),
            heckle = HeckleMode.fromName(p.getString("forolampning", null)),
            voiceEngine = p.getString("rost_motor", null),
            voiceName = p.getString("rost", null),
            auto = AutoMode.fromName(p.getString("auto", null)),
            carAddress = p.getString("bil_adress", null),
            carName = p.getString("bil_namn", null),
        )
    }

    fun setSettings(ctx: Context, s: Settings) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("fordon", s.vehicle.name).putBoolean("slap", s.trailer)
            .putInt("marginal", s.margin).putBoolean("pip", s.beep).putBoolean("vibrera", s.vibrate).putString("forolampning", s.heckle.name)
            .putString("rost_motor", s.voiceEngine).putString("rost", s.voiceName)
            .putString("auto", s.auto.name).putString("bil_adress", s.carAddress).putString("bil_namn", s.carName)
            .apply()
        val old = _settings.value
        _settings.value = s
        // Ny talmotor kräver en ny röst-instans; ny röst räcker att byta.
        heckler?.let { h ->
            if (s.voiceEngine != h.engine) { h.release(); heckler = Heckler(appContext(ctx), s.voiceEngine, s.voiceName) }
            else if (s.voiceName != old.voiceName) h.setVoiceName(s.voiceName)
        }
        recompute() // räkna om direkt
    }

    private fun appContext(ctx: Context): Context = ctx.applicationContext

    private fun warner(ctx: Context): Warner {
        if (heckler == null) heckler = Heckler(ctx.applicationContext, _settings.value.voiceEngine, _settings.value.voiceName)
        return warner ?: Warner(ctx.applicationContext).also { warner = it }
    }

    /** Skapar rösten i förväg (testsidan). */
    fun prepareVoice(ctx: Context): Heckler {
        warner(ctx)
        return heckler!!
    }

    private fun releaseWarners() {
        warner?.release(); warner = null
        heckler?.release(); heckler = null
    }

    @SuppressLint("MissingPermission")
    fun start(context: Context, auto: Boolean = false) {
        if (_state.value.running) return
        val ctx = context.applicationContext
        loadSettings(ctx)
        DataManager.load(ctx)
        warner(ctx)
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationManager = lm
        matcher.reset()

        val now = System.currentTimeMillis()
        autoStarted = auto
        armed = !auto
        startedAt = now
        lastMovingAt = now
        exitAt = null
        _state.value = UiState(running = true, waiting = !armed, auto = auto,
            status = if (auto) waitingText() else "Väntar på GPS…")

        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
        try { lm.registerGnssStatusCallback(gnss, Handler(Looper.getMainLooper())) } catch (e: Exception) { }

        // Kolla efter ny hastighetsdata en gång per dygn medan appen är igång.
        updateJob = scope.launch {
            while (isActive) {
                delay(DataManager.CHECK_INTERVAL_MS)
                DataManager.checkForUpdate(ctx)
            }
        }
        lastFix = null
        gpsLost = false
        gpsJob = scope.launch {
            while (isActive) { delay(1_000); checkGps() }
        }
        // Stänger av en automatiskt startad bevakning när resan verkar slut.
        watchdogJob = scope.launch {
            while (isActive) {
                delay(15_000)
                if (autoStarted && shouldAutoStop(System.currentTimeMillis())) requestStop()
            }
        }
    }

    private fun waitingText() = "Startad automatiskt – bevakar när du kör över $AUTO_ARM_KMH km/h"

    private fun shouldAutoStop(now: Long): Boolean {
        if (!armed) return now - startedAt > 10 * MIN          // kom aldrig upp i fart
        exitAt?.let { if (now - it > 2 * MIN) return true }     // klev ur fordonet
        return now - lastMovingAt > 10 * MIN                    // stått still länge
    }

    private fun requestStop() = onStopRequest?.invoke() ?: stop()

    /** Rörelseavkänningen säger att man klivit ur fordonet. Stoppar om farten inte kommer tillbaka. */
    fun onVehicleExit() {
        if (_state.value.running && autoStarted) exitAt = System.currentTimeMillis()
    }

    /** Stoppar bevakningen om den startades automatiskt (t.ex. när bilens Bluetooth kopplas ifrån). */
    fun stopIfAuto() {
        if (_state.value.running && autoStarted) requestStop()
    }

    fun stop() {
        locationManager?.removeUpdates(listener)
        try { locationManager?.unregisterGnssStatusCallback(gnss) } catch (e: Exception) { }
        locationManager = null
        if (!simulating) releaseWarners()
        updateJob?.cancel(); updateJob = null
        watchdogJob?.cancel(); watchdogJob = null
        gpsJob?.cancel(); gpsJob = null
        lastFix = null
        autoStarted = false
        _state.value = UiState(running = false, status = "Stoppad")
    }

    private fun onLocation(loc: Location) {
        if (!_state.value.running) return
        lastFix = loc
        val speed = if (loc.hasSpeed()) (loc.speed * 3.6f).roundToInt() else null
        val now = System.currentTimeMillis()
        if ((speed ?: 0) >= 10) lastMovingAt = now
        if ((speed ?: 0) >= AUTO_ARM_KMH) { armed = true; exitAt = null }
        _debug.update {
            it.copy(accuracy = if (loc.hasAccuracy()) loc.accuracy else null,
                lat = loc.latitude, lon = loc.longitude, fixTime = now)
        }
        if (simulating) return

        val data = DataManager.data
        if (data == null) {
            setNoLimit(speed, "Hastighetsdata saknas")
            return
        }

        val (e, n) = Sweref99.fromWgs84(loc.latitude, loc.longitude)
        // Kursen är bara pålitlig när man rör sig.
        val course = if (loc.hasBearing() && (speed ?: 0) >= 8) loc.bearing else null
        val accuracy = if (loc.hasAccuracy()) loc.accuracy else null
        val road = matcher.match(data, e, n, accuracy, course)

        if (road == null) {
            _debug.update { it.copy(road = null) }
            setNoLimit(speed, "Ingen väg med hastighetsgräns i närheten")
            return
        }
        _debug.update {
            it.copy(road = "Sträcka #${road.id} · ${road.kmh} km/h · ${road.distance.roundToInt()} m bort" +
                    (if (road.ruleId >= 0) " · regel ${road.ruleId}" else "") +
                    when (road.direction) { 1 -> " · enkelriktad (med)"; 2 -> " · enkelriktad (mot)"; else -> "" })
        }

        val s = _settings.value
        val (limit, reason) = limitFor(road.kmh, s, data.rules.getOrNull(road.ruleId).orEmpty())
        val over = if (speed != null && armed) speed - (limit + s.margin) else 0
        _state.update {
            it.copy(speedKmh = speed, limitKmh = limit, signedKmh = road.kmh, reason = reason,
                limitStale = false, tooFast = over > 0, waiting = !armed,
                status = if (!armed) waitingText() else "Hastighetsdata: Trafikverket (ISA)")
        }
        warner?.update(over, s)
        heckler?.update(over > 0, speed, limit, s.heckle)
    }

    private fun setNoLimit(speed: Int?, status: String) {
        _state.update { it.copy(speedKmh = speed, limitKmh = null, signedKmh = null, reason = null,
            tooFast = false, waiting = !armed, status = if (!armed) waitingText() else status) }
        warner?.update(0, _settings.value)
        heckler?.update(false, null, null, _settings.value.heckle)
    }

    /** Gränsen som gäller för valt fordon: skyltat, tids-/fordonsregler och fordonets egen maxfart. */
    private fun limitFor(signed: Int, s: Settings, rules: List<Deviation>): Pair<Int, String?> {
        var limit = signed
        var reason: String? = null
        if (rules.isNotEmpty()) {
            val now = LocalDateTime.now()
            rules.filter { it.applies(now, s.vehicle, s.trailer) }.minByOrNull { it.kmh }
                ?.let { limit = it.kmh; reason = "tids-/fordonsregel" }
        }
        s.vehicle.cap(signed, s.trailer)?.let { cap ->
            if (cap < limit) {
                limit = cap
                reason = if (s.trailer && cap == Vehicle.TRAILER_MAX) "släp" else s.vehicle.label.lowercase()
            }
        }
        return limit to reason
    }

    // ---------- Testläge ----------

    /** Låtsas att man kör i [speed] km/h där det är skyltat [signed]. Vibrerar och piper som på riktigt. */
    fun simulate(ctx: Context, signed: Int, speed: Int) {
        simulating = true
        val s = _settings.value
        val (limit, reason) = limitFor(signed, s, emptyList())
        val over = speed - (limit + s.margin)
        _state.update {
            it.copy(speedKmh = speed, limitKmh = limit, signedKmh = signed, reason = reason,
                limitStale = false, tooFast = over > 0, waiting = false, status = "Testläge")
        }
        warner(ctx).update(over, s)
        heckler?.update(over > 0, speed, limit, s.heckle)
    }

    fun endSimulation() {
        if (!simulating) return
        simulating = false
        warner?.update(0, _settings.value)
        heckler?.reset()
        if (_state.value.running) {
            _state.update { it.copy(speedKmh = null, limitKmh = null, signedKmh = null, reason = null,
                tooFast = false, status = "Väntar på GPS…") }
            gpsLost = false
            recompute()
        } else {
            releaseWarners()
            _state.value = UiState()
        }
    }
}
