package se.dettmansfixeri.dukorforfort

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Startar bevakningen av sig själv:
 *  - "När jag åker": telefonens rörelseavkänning (Google Play-tjänster) säger till när man sätter sig i ett fordon.
 *  - "Bara i min bil": när telefonen kopplas till bilens Bluetooth.
 * Knappen "Jag kör inte" i notisen pausar automatiken tills man klivit ur / bilen kopplats ifrån.
 */
object AutoStart {

    const val ACTION_TRANSITION = "se.dettmansfixeri.dukorforfort.TRANSITION"
    private const val PREFS = "auto"
    private const val KEY_PAUSED = "pausad"
    private const val PAUSE_MAX_MS = 4 * 60 * 60_000L

    private val _activity = MutableStateFlow<String?>(null)
    /** Vad rörelseavkänningen senast sa (för testsidan). */
    val activity: StateFlow<String?> = _activity

    private val _car = MutableStateFlow<String?>(null)
    /** Namnet på bilen om den är ansluten just nu (för testsidan). */
    val carConnected: StateFlow<String?> = _car

    private val _registered = MutableStateFlow(false)
    val registered: StateFlow<Boolean> = _registered

    // ---------- Behörigheter ----------

    private fun granted(ctx: Context, p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun hasActivityPermission(ctx: Context) =
        Build.VERSION.SDK_INT < 29 || granted(ctx, Manifest.permission.ACTIVITY_RECOGNITION)

    fun hasBackgroundLocation(ctx: Context) =
        Build.VERSION.SDK_INT < 29 || granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    fun hasBluetoothPermission(ctx: Context) =
        Build.VERSION.SDK_INT < 31 || granted(ctx, Manifest.permission.BLUETOOTH_CONNECT)

    // ---------- Registrering ----------

    /** Slår på/av rörelseavkänningen efter vad som är valt. Anropas vid start, inställningsändring och omstart. */
    @SuppressLint("MissingPermission")
    fun apply(ctx: Context, s: Settings) {
        val client = ActivityRecognition.getClient(ctx)
        if (s.auto == AutoMode.DRIVING && hasActivityPermission(ctx)) {
            val types = listOf(
                DetectedActivity.IN_VEHICLE, DetectedActivity.STILL, DetectedActivity.WALKING,
                DetectedActivity.ON_BICYCLE, DetectedActivity.RUNNING,
            )
            val transitions = types.map {
                ActivityTransition.Builder().setActivityType(it)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build()
            } + ActivityTransition.Builder().setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT).build()
            client.requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pendingIntent(ctx))
                .addOnSuccessListener { _registered.value = true }
                .addOnFailureListener { _registered.value = false }
        } else {
            try { client.removeActivityTransitionUpdates(pendingIntent(ctx)) } catch (e: Exception) { }
            _registered.value = false
        }
    }

    private fun pendingIntent(ctx: Context): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, AutoStartReceiver::class.java).setAction(ACTION_TRANSITION), flags
        )
    }

    // ---------- Händelser ----------

    private fun settings(ctx: Context): Settings {
        SpeedTracker.loadSettings(ctx)
        return SpeedTracker.settings.value
    }

    /** Man har satt sig i ett fordon. [test] = knappen på testsidan, startar oavsett läge. */
    fun vehicleEnter(ctx: Context, test: Boolean = false) {
        _activity.value = "I fordon"
        if (!test && (settings(ctx).auto != AutoMode.DRIVING || isPaused(ctx))) return
        startService(ctx)
    }

    /** Man har klivit ur. På riktigt stoppas bevakningen om farten inte kommer tillbaka inom 2 min; i test direkt. */
    fun vehicleExit(ctx: Context, test: Boolean = false) {
        clearPause(ctx)
        if (test) { _activity.value = "Klev ur fordon"; SpeedTracker.stopIfAuto() } else SpeedTracker.onVehicleExit()
    }

    fun carConnected(ctx: Context, address: String?, name: String?) {
        val s = settings(ctx)
        if (address == null || address != s.carAddress) return
        _car.value = name ?: s.carName ?: address
        if (s.auto == AutoMode.CAR && !isPaused(ctx)) startService(ctx)
    }

    fun carDisconnected(ctx: Context, address: String?) {
        val s = settings(ctx)
        if (address == null || address != s.carAddress) return
        _car.value = null
        clearPause(ctx)
        SpeedTracker.stopIfAuto()
    }

    private fun startService(ctx: Context) {
        try { SpeedService.start(ctx, auto = true) } catch (e: Exception) {
            // Android tillät inte start i bakgrunden (t.ex. "Tillåt alltid" saknas).
        }
    }

    // ---------- "Jag kör inte" ----------

    fun pause(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_PAUSED, System.currentTimeMillis()).apply()

    private fun clearPause(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_PAUSED).apply()

    fun isPaused(ctx: Context): Boolean {
        val at = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_PAUSED, 0L)
        return at > 0 && System.currentTimeMillis() - at < PAUSE_MAX_MS
    }

    internal fun setActivity(type: Int, enter: Boolean) {
        val name = when (type) {
            DetectedActivity.IN_VEHICLE -> "I fordon"
            DetectedActivity.STILL -> "Står still"
            DetectedActivity.WALKING -> "Går"
            DetectedActivity.RUNNING -> "Springer"
            DetectedActivity.ON_BICYCLE -> "Cyklar"
            else -> "Okänt"
        }
        _activity.value = if (enter) name else "Klev ur fordon"
    }
}

class AutoStartReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                SpeedTracker.loadSettings(ctx)
                AutoStart.apply(ctx, SpeedTracker.settings.value)
            }
            BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                val address = device?.address
                val name = try { if (AutoStart.hasBluetoothPermission(ctx)) device?.name else null } catch (e: SecurityException) { null }
                if (intent.action == BluetoothDevice.ACTION_ACL_CONNECTED) AutoStart.carConnected(ctx, address, name)
                else AutoStart.carDisconnected(ctx, address)
            }
            AutoStart.ACTION_TRANSITION -> {
                if (!ActivityTransitionResult.hasResult(intent)) return
                val events = ActivityTransitionResult.extractResult(intent)?.transitionEvents ?: return
                for (ev in events) {
                    val enter = ev.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER
                    AutoStart.setActivity(ev.activityType, enter)
                    if (ev.activityType == DetectedActivity.IN_VEHICLE) {
                        if (enter) AutoStart.vehicleEnter(ctx) else AutoStart.vehicleExit(ctx)
                    }
                }
            }
        }
    }
}
