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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class UiState(
    val running: Boolean = false,
    val speedKmh: Int? = null,
    val limitKmh: Int? = null,
    val limitStale: Boolean = false,
    val status: String = "Väntar på GPS…",
    val tooFast: Boolean = false,
)

/**
 * Själva bevakningen: GPS, hastighetsgräns och pip.
 * Ägs av [SpeedService] så att den fortsätter när en annan app är framme.
 * Skärmen (MainActivity) läser bara [state].
 */
object SpeedTracker {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var locationManager: LocationManager? = null
    private var tone: ToneGenerator? = null

    private var lastFetchLocation: Location? = null
    private var lastFetchTime = 0L
    private var fetching = false
    private var lastBeep = 0L

    private val listener = LocationListener { onLocation(it) }

    @SuppressLint("MissingPermission")
    fun start(context: Context) {
        if (_state.value.running) return
        val lm = context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationManager = lm
        tone = try { ToneGenerator(AudioManager.STREAM_ALARM, 90) } catch (e: Exception) { null }
        _state.value = UiState(running = true)
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
    }

    fun stop() {
        locationManager?.removeUpdates(listener)
        locationManager = null
        tone?.release()
        tone = null
        lastFetchLocation = null
        lastFetchTime = 0L
        _state.value = UiState(running = false, status = "Stoppad")
    }

    private fun onLocation(loc: Location) {
        if (!_state.value.running) return
        val speed = if (loc.hasSpeed()) (loc.speed * 3.6f).roundToInt() else null
        _state.update { s ->
            s.copy(speedKmh = speed, tooFast = speed != null && s.limitKmh != null && speed > s.limitKmh)
        }
        maybeBeep()
        maybeFetchLimit(loc)
    }

    private fun maybeBeep() {
        if (!_state.value.tooFast) return
        val now = System.currentTimeMillis()
        if (now - lastBeep > 5000) {
            lastBeep = now
            tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 400)
        }
    }

    private fun maybeFetchLimit(loc: Location) {
        if (fetching) return
        val now = System.currentTimeMillis()
        val prev = lastFetchLocation
        val moved = prev == null || prev.distanceTo(loc) > 40f
        val old = now - lastFetchTime > 15_000
        if (!moved && !old) return

        fetching = true
        lastFetchLocation = loc
        lastFetchTime = now
        scope.launch {
            try {
                val limit = TrafikverketApi.fetchSpeedLimit(loc.latitude, loc.longitude)
                _state.update { s ->
                    if (!s.running) s
                    else if (limit == null) s.copy(
                        limitKmh = null, limitStale = false, tooFast = false,
                        status = "Ingen hastighetsgräns hittad här"
                    )
                    else s.copy(
                        limitKmh = limit.kmh, limitStale = false,
                        tooFast = s.speedKmh != null && s.speedKmh > limit.kmh,
                        status = "Hastighetsgräns från Trafikverket (NVDB)"
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    if (!it.running) it
                    else it.copy(limitStale = true, status = "Fel: ${e.message ?: e.javaClass.simpleName}")
                }
            } finally {
                fetching = false
            }
        }
    }
}
