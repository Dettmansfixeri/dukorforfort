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
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

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
    /** Testfunktionen "Visa vad appen tror": t.ex. "Landar på 73" eller "50 om 230 m – släpp gasen". */
    val guess: String? = null,
    /** Förvarningen: den lägre gränsen som kommer (visas under skylten), annars null. */
    val aheadKmh: Int? = null,
    /** Tickandets takt (ms) om det tickar, null om rösten sa till i stället (då lyser skylten stilla). */
    val aheadBlinkMs: Int? = null,
    /** När tickandet började (SystemClock.elapsedRealtime), så att blinket går i takt. */
    val aheadSince: Long = 0L,
    /** Gränsen är påhittad i utvecklarläget (−10 / +10), inte vägens. */
    val manual: Boolean = false,
)

enum class SoundMode(val label: String) {
    BEEP("Pip – tätare och ljusare ju fortare"),
    TONE("Ton – jämn ton som stiger för varje km/h"),
    SILENT("Tyst – pausar musiken när du kör för fort (piper om ingen musik spelas)");

    companion object {
        fun fromName(n: String?) = entries.firstOrNull { it.name == n } ?: BEEP
    }
}

enum class BeepOutput(val label: String, val info: String) {
    NORMAL("Vanligt ljud", "Samma väg som rösten och musiken, följer mediavolymen"),
    ALARM("Larmljud", "Följer larmvolymen och hörs även när mediavolymen är nere");

    companion object {
        fun fromName(n: String?) = entries.firstOrNull { it.name == n }
    }
}

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
    val auto: AutoMode = AutoMode.OFF,
    val carAddress: String? = null,
    val carName: String? = null,
    val sound: SoundMode = SoundMode.BEEP,
    /** Varna redan när appen räknar ut att man är på väg över gränsen (man gasar på). */
    val gasWarn: Boolean = false,
    /** Huvudbrytaren för testfunktioner. Av = alla testfunktioner av (valen sparas ändå). */
    val tests: Boolean = false,
    val testAhead: Boolean = false,
    val testLearnBrake: Boolean = false,
    val testShowGuess: Boolean = false,
    /** Rund skylt ovanpå andra appar när gränsen sänks. */
    val overlay: Boolean = false,
    /** Pipets och röstens volym i procent av standard. Ändrar inte telefonens volym. */
    val beepVolume: Int = 100,
    val voiceVolume: Int = 100,
    /** Var pipet går ut: vanliga ljudet (som rösten) eller som larm. */
    val beepOutput: BeepOutput = BeepOutput.NORMAL,
) {
    val aheadOn get() = tests && testAhead
    val learnBrakeOn get() = aheadOn && testLearnBrake
    val showGuessOn get() = tests && testShowGuess
    val anyTestOn get() = aheadOn || showGuessOn
    /** Tyst läge: musiken pausas i stället för pip (piper bara om ingen musik spelas). Ingen röst. */
    val silentOn get() = beep && sound == SoundMode.SILENT
    /** Rösten, utom i tyst läge. */
    val heckleOn get() = if (silentOn) HeckleMode.OFF else heckle
}

/** Teknisk info för testsidan. */
data class DebugInfo(
    val accuracy: Float? = null,
    val satsUsed: Int = 0,
    val satsTotal: Int = 0,
    val lat: Double? = null,
    val lon: Double? = null,
    val fixTime: Long = 0,
    /** Färdriktning i grader (0 = norr), null om GPS:en inte vet. */
    val bearing: Float? = null,
    val road: String? = null,
)

/**
 * Själva bevakningen: GPS, uppslag av hastighetsgräns i den lokala datan, vibration och pip.
 * Ägs av [SpeedService] så att den fortsätter när en annan app är framme.
 */
object SpeedTracker {

    private const val PREFS = "installningar"
    const val MAX_MARGIN = 10
    /** Gångfart (gångfartsområde) står som 5 km/h i Trafikverkets data. */
    const val GANGFART = 5
    /** Gångfart räknas som ungefär 7 km/h. Där gäller ingen marginal – så låg fart, marginal blir farligt. */
    const val GANGFART_VARNA = 7

    /** Farten där varningen börjar: gränsen + marginalen, men i gångfartsområde alltid 7 km/h. */
    fun warnAt(limit: Int, margin: Int): Int = if (limit == GANGFART) GANGFART_VARNA else limit + margin
    /** Minusmarginal: varnar redan under gränsen (för att prova pip och vibration i lägre fart). */
    const val MIN_MARGIN = -10
    /** Volymreglagen, procent av standard. */
    val BEEP_VOLUME = 10..300
    val VOICE_VOLUME = 25..200
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
    // Förvarningen: sagd för den här lägre gränsen ([announcedKmh]) medan vi är på gränsen [announcedAt].
    private var announcedAt: Int? = null
    private var announcedKmh: Int? = null
    private var lastClickMs: Int? = null
    // Påhittad gräns i utvecklarläget, null = vägens riktiga gräns.
    private var manualKmh: Int? = null
    // Lägre gräns som förvarnats om, och om man passerat den utan att ha saktat ner (då larmar det direkt).
    private var warnedFor: Int? = null
    private var warnedAt: Int? = null
    private var late = false
    private var clickSince = 0L
        private set
    private val matcher = RoadMatcher()
    private val accel = SpeedTrend()
    private val brake = BrakeLearner()
    private var prefsCtx: Context? = null
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
    /** Så många sekunder framåt räknar vi positionen (GPS-fördröjning + tid till nästa fix). */
    private const val LOOKAHEAD_S = 1.0
    private var lastFixAt = 0L
    private var gpsLost = false
    private var gpsJob: Job? = null
    // GPS-beskeden (sägs alltid, även när rösten är av): "saknas" om ingen position kommit efter
    // GPS_MISSING_MS, "förlorad" när signalen varit borta GPS_LOST_SAY_MS, "återställd" när den kommer tillbaka.
    private const val GPS_MISSING_MS = 30_000L
    private const val GPS_LOST_SAY_MS = 10_000L
    private var gpsSaid: String? = null      // "saknas" eller "förlorad" har sagts (eller väntar på att sägas)
    private var gpsPending: String? = null   // besked som ska sägas så fort rösten är ledig

    private fun fixIsFresh() = lastFix != null && SystemClock.elapsedRealtime() - lastFixAt <= GPS_TIMEOUT_MS

    /** Räknar om med senaste positionen, men bara om den är färsk. */
    private fun recompute() { if (fixIsFresh()) lastFix?.let { onLocation(it) } }

    private val listener = LocationListener {
        lastFixAt = SystemClock.elapsedRealtime()
        gpsLost = false
        if (gpsSaid != null) {
            // Hann "förlorad" inte sägas behövs inget "återställd" heller.
            gpsPending = if (gpsPending == gpsSaid) null else ClipLines.GPS_BACK
            gpsSaid = null
        }
        onLocation(it)
    }

    private fun checkGps() {
        if (!_state.value.running || simulating) return
        gpsVoice()
        if (gpsLost) return
        val since = if (lastFix == null) null else SystemClock.elapsedRealtime() - lastFixAt
        if (since == null || since <= GPS_TIMEOUT_MS) return
        gpsLost = true
        _state.update { it.copy(speedKmh = null, limitStale = it.limitKmh != null, tooFast = false, aheadKmh = null,
            status = if (!armed) waitingText() else "GPS-signal saknas – varningen är tyst") }
        warner?.update(0, _settings.value)
        warner?.clicks(null)
        heckler?.reset(hush = false) // ingen "tack"-replik, men det som sägs nu pratas klart
    }

    /** Bestämmer och säger GPS-beskeden. Försöker igen varje sekund om rösten pratar just då. */
    private fun gpsVoice() {
        if (gpsSaid == null) {
            val now = SystemClock.elapsedRealtime()
            val say = when {
                lastFix == null && System.currentTimeMillis() - startedAt >= GPS_MISSING_MS -> ClipLines.GPS_MISSING
                lastFix != null && gpsLost && now - lastFixAt >= GPS_LOST_SAY_MS -> ClipLines.GPS_LOST
                else -> null
            }
            if (say != null) { gpsSaid = say; gpsPending = say }
        }
        val text = gpsPending ?: return
        if (prefsCtx?.let { prepareVoice(it).announce(text) } == true) gpsPending = null
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
        val vehicle = Vehicle.fromName(p.getString("fordon", null))
        _settings.value = Settings(
            vehicle = vehicle,
            trailer = p.getBoolean("slap", false),
            margin = p.getInt("marginal", 0).coerceIn(MIN_MARGIN, vehicle.maxMargin),
            beep = p.getBoolean("pip", true),
            vibrate = p.getBoolean("vibrera", true),
            heckle = HeckleMode.fromName(p.getString("forolampning", null)),
            auto = AutoMode.fromName(p.getString("auto", null)),
            carAddress = p.getString("bil_adress", null),
            carName = p.getString("bil_namn", null),
            sound = SoundMode.fromName(p.getString("ljud", null)),
            gasWarn = p.getBoolean("forvarna_gas", false),
            tests = p.getBoolean("test", false),
            testAhead = p.getBoolean("test_lagre_grans", false),
            testLearnBrake = p.getBoolean("test_inbromsning", false),
            testShowGuess = p.getBoolean("test_visa", false),
            overlay = p.getBoolean("overlay", false),
            beepVolume = p.getInt("pip_volym", 100).coerceIn(BEEP_VOLUME),
            voiceVolume = p.getInt("rost_volym", 100).coerceIn(VOICE_VOLUME),
            // Inte valt än: som förut, larm om rösten är av, annars vanligt ljud.
            beepOutput = BeepOutput.fromName(p.getString("pip_utgang", null))
                ?: if (HeckleMode.fromName(p.getString("forolampning", null)) == HeckleMode.OFF) BeepOutput.ALARM else BeepOutput.NORMAL,
        )
        brake.load(p)
    }

    fun setSettings(ctx: Context, new: Settings) {
        val s = new.copy(margin = new.margin.coerceAtMost(new.vehicle.maxMargin))
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("fordon", s.vehicle.name).putBoolean("slap", s.trailer)
            .putInt("marginal", s.margin).putBoolean("pip", s.beep).putBoolean("vibrera", s.vibrate).putString("forolampning", s.heckle.name)
            .putString("auto", s.auto.name).putString("bil_adress", s.carAddress).putString("bil_namn", s.carName)
            .putString("ljud", s.sound.name).putBoolean("forvarna_gas", s.gasWarn).putBoolean("test", s.tests)
            .putBoolean("test_lagre_grans", s.testAhead).putBoolean("test_inbromsning", s.testLearnBrake)
            .putBoolean("test_visa", s.testShowGuess).putBoolean("overlay", s.overlay)
            .putInt("pip_volym", s.beepVolume).putInt("rost_volym", s.voiceVolume).putString("pip_utgang", s.beepOutput.name)
            .apply()
        _settings.value = s
        warner?.refreshVolume()
        if (!s.tests) manualKmh = null   // påhittad gräns bara i utvecklarläget
        recompute() // räkna om direkt
    }

    private fun appContext(ctx: Context): Context = ctx.applicationContext

    private fun warner(ctx: Context): Warner {
        if (heckler == null) heckler = Heckler(ctx.applicationContext)
        if (VoicePack.state.value !is VoicePack.State.Ready) VoicePack.load(ctx.applicationContext)
        heckler?.onSpeaking = { warner?.duck(it) }
        return warner ?: Warner(ctx.applicationContext).also { warner = it }
    }

    /** Rösterna: läs in röstpaketet och hämta nytt om det finns. */
    fun syncVoices(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { VoicePack.sync(app) }
    }

    /**
     * Utvecklarläget: ändra gränsen med [delta] (−10 / +10). Första trycket utgår från gränsen som gäller nu
     * (50 om ingen finns). Alla gränser appen kan visa: 10–120.
     */
    fun stepManual(delta: Int) {
        val base = manualKmh ?: _state.value.limitKmh ?: 50
        setManual(((base + delta) / 10 * 10).coerceIn(10, 120))
    }

    /** Tillbaka till vägens riktiga gräns. */
    fun clearManual() = setManual(null)

    private fun setManual(kmh: Int?) {
        manualKmh = kmh
        if (fixIsFresh()) recompute()
        else _state.update { it.copy(limitKmh = kmh, signedKmh = kmh, limitStale = false, manual = kmh != null, reason = null) }
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
        prefsCtx = ctx
        loadSettings(ctx)
        DataManager.load(ctx)
        EventLog.start(ctx)
        accel.reset()
        brake.reset()
        late = false; warnedFor = null; warnedAt = null
        syncVoices(ctx)
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

        // Kolla efter ny hastighetsdata och ny appversion en gång per dygn medan appen är igång.
        updateJob = scope.launch {
            while (isActive) {
                delay(DataManager.CHECK_INTERVAL_MS)
                DataManager.autoUpdate(ctx)
                VoicePack.sync(ctx)
                AppUpdater.check()
            }
        }
        lastFix = null
        gpsLost = false
        gpsSaid = null; gpsPending = null
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
        manualKmh = null
        locationManager?.removeUpdates(listener)
        try { locationManager?.unregisterGnssStatusCallback(gnss) } catch (e: Exception) { }
        locationManager = null
        if (!simulating) releaseWarners()
        updateJob?.cancel(); updateJob = null
        watchdogJob?.cancel(); watchdogJob = null
        gpsJob?.cancel(); gpsJob = null
        lastFix = null
        gpsSaid = null; gpsPending = null
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
                lat = loc.latitude, lon = loc.longitude, fixTime = now,
                bearing = if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 2f) loc.bearing else null)
        }
        if (simulating) return

        val data = DataManager.data
        if (data == null) {
            setNoLimit(speed, "Hastighetsdata saknas")
            return
        }

        val (e0, n0) = Sweref99.fromWgs84(loc.latitude, loc.longitude)
        // Kursen är bara pålitlig när man rör sig.
        val course = if (loc.hasBearing() && (speed ?: 0) >= 8) loc.bearing else null
        // GPS-positionen är redan lite gammal när den kommer, och nästa kommer först om en sekund.
        // Räkna därför med att bilen hunnit en bit framåt, så att skylten byts när man passerar den.
        val ahead = if (course != null && (speed ?: 0) >= 20) loc.speed * LOOKAHEAD_S else 0.0
        val rad = Math.toRadians((course ?: 0f).toDouble())
        val e = e0 + sin(rad) * ahead
        val n = n0 + cos(rad) * ahead
        val accuracy = if (loc.hasAccuracy()) loc.accuracy else null
        val road = matcher.match(data, e, n, accuracy, course)

        // Påhittad gräns (utvecklarläget, knapparna −10 / +10): gäller i stället för vägens.
        val manual = manualKmh.takeIf { _settings.value.tests }
        if (road == null) {
            _debug.update { it.copy(road = null) }
            if (manual == null) {
                setNoLimit(speed, "Ingen väg med hastighetsgräns i närheten")
                return
            }
        } else _debug.update {
            it.copy(road = "Sträcka #${road.id} · ${road.kmh} km/h · ${road.distance.roundToInt()} m bort" +
                    (if (road.ruleId >= 0) " · regel ${road.ruleId}" else "") +
                    when (road.direction) { 1 -> " · enkelriktad (med)"; 2 -> " · enkelriktad (mot)"; else -> "" })
        }

        val s = _settings.value
        val (limit, reason) = if (manual != null || road == null) (manual ?: 0) to null
            else limitFor(road.kmh, s, data.rules.getOrNull(road.ruleId).orEmpty())
        val over = if (speed != null && armed) speed - warnAt(limit, s.margin) else 0

        // Gasar man på: räkna ut var farten landar och varna redan innan man är över.
        val t = loc.elapsedRealtimeNanos / 1e9
        if (loc.hasSpeed()) accel.add(t, loc.speed.toDouble())
        val landing = if (loc.hasSpeed()) accel.landingKmh(loc.speed.toDouble()) else null
        val landOver = if (landing != null && armed) landing - warnAt(limit, s.margin) else 0
        val early = s.gasWarn && landOver > over && landOver > 0
        val warnOver = if (early) landOver else over

        // Lär sig hur snabbt bilen tappar fart när man släpper gasen (testfunktion).
        if (s.learnBrakeOn && loc.hasSpeed() && brake.add(t, loc.speed.toDouble())) prefsCtx?.let { brake.save(it, PREFS) }

        // Förvarning om lägre gräns längre fram (testfunktion).
        var clickMs: Int? = null
        var aheadText: String? = null
        var aheadKmh: Int? = null
        // Passerat skylten man förvarnats om men fortfarande för fort: larma direkt tills man är nere.
        if (limit != warnedAt) {
            late = warnedFor != null && limit <= warnedFor!! && over > 0
            warnedFor = null; warnedAt = limit
        }
        if (over <= 0) late = false
        if (limit != announcedAt) { announcedAt = limit; announcedKmh = null }
        if (manual == null && road != null && s.aheadOn && armed && course != null && loc.hasSpeed() && (speed ?: 0) >= 30 && warnOver <= 0) {
            val a = Ahead.find(data, road, e0, n0, course.toDouble()) { limitFor(it.kmh, s, data.rules.getOrNull(it.ruleId).orEmpty()).first }
            if (a != null && a.kmh < limit) {
                val v = loc.speed.toDouble()
                val v2 = warnAt(a.kmh, s.margin) / 3.6
                if (v > v2 + 0.5) {
                    // Olika inbromsning beroende på farten: 50 → 30 tappar fortare än 110 → 90.
                    val fromKmh = v * 3.6
                    val toKmh = v2 * 3.6
                    val dec = if (s.learnBrakeOn) brake.decel(fromKmh, toKmh)
                    else BrakeLearner.DEFAULTS[BrakeLearner.band((fromKmh + toKmh) / 2)]
                    // Rösten säger till så tidigt att man hinner höra hela frasen och sedan släppa gasen.
                    val talk = if (s.silentOn) 0.0 else Ahead.SPEAK_S
                    val need = (v * v - v2 * v2) / (2 * dec) + v * (Ahead.REACTION_S + talk)
                    val left = a.distance - need
                    aheadText = "${a.kmh} om ${a.distance.roundToInt()} m – " +
                        (if (left <= 0) "släpp gasen nu" else "släpp om ${left.roundToInt()} m")
                    if (left <= 0) {
                        aheadKmh = a.kmh
                        warnedFor = a.kmh; warnedAt = limit
                        // Säg "Nu blir det 40 här framme" en gång per lägre gräns. Går det inte (tyst läge,
                        // klippet saknas eller rösten pratar redan) tickar det som förut.
                        val said = announcedKmh == a.kmh || (!s.silentOn && prefsCtx?.let {
                            prepareVoice(it).announce(ClipLines.aheadText(a.kmh))
                        } == true)
                        if (said) announcedKmh = a.kmh
                        else clickMs = if (a.distance < need * 0.6) 600 else 1200
                    }
                }
            }
        }

        val guess = if (!s.showGuessOn) null else listOfNotNull(
            landing?.takeIf { speed != null && it > speed + 1 }?.let { "Landar på $it km/h" },
            aheadText,
            if (s.learnBrakeOn) "Inbromsning m/s²: ${brake.summary()}" else null,
        ).joinToString("\n").ifEmpty { null }

        if (clickMs != lastClickMs) { lastClickMs = clickMs; clickSince = SystemClock.elapsedRealtime() }
        _state.update {
            it.copy(speedKmh = speed, limitKmh = limit, signedKmh = manual ?: road?.kmh, reason = reason, manual = manual != null,
                limitStale = false, tooFast = over > 0, waiting = !armed, guess = guess,
                aheadKmh = aheadKmh, aheadBlinkMs = clickMs, aheadSince = clickSince,
                status = if (!armed) waitingText() else "Hastighetsdata: Trafikverket (ISA)")
        }
        warner?.update(warnOver, s.copy(margin = warnAt(limit, s.margin) - limit), limit, early, late)
        warner?.clicks(clickMs)
        heckler?.epa = s.vehicle == Vehicle.A_TRAKTOR && (speed ?: 0) > Vehicle.EPA_MAX
        heckler?.update(over > 0, speed, limit, s.heckleOn)
    }

    private fun setNoLimit(speed: Int?, status: String) {
        _state.update { it.copy(speedKmh = speed, limitKmh = null, signedKmh = null, reason = null, aheadKmh = null,
            tooFast = false, waiting = !armed, guess = null, status = if (!armed) waitingText() else status) }
        warner?.update(0, _settings.value)
        warner?.clicks(null)
        heckler?.update(false, null, null, _settings.value.heckleOn)
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
        val over = speed - warnAt(limit, s.margin)
        _state.update {
            it.copy(speedKmh = speed, limitKmh = limit, signedKmh = signed, reason = reason,
                limitStale = false, tooFast = over > 0, waiting = false, status = "Testläge", aheadKmh = null)
        }
        warner(ctx).update(over, s.copy(margin = warnAt(limit, s.margin) - limit), limit)
        heckler?.epa = s.vehicle == Vehicle.A_TRAKTOR && (speed ?: 0) > Vehicle.EPA_MAX
        heckler?.update(over > 0, speed, limit, s.heckleOn)
    }

    fun endSimulation() {
        if (!simulating) return
        simulating = false
        warner?.update(0, _settings.value)
        warner?.clicks(null)
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
