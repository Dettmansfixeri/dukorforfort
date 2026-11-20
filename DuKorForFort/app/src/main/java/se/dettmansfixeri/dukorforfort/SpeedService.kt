package se.dettmansfixeri.dukorforfort

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Förgrundstjänst av typen "location" – samma sätt som navigeringsappar körs.
 * Visar en notis med skylten, hastighet och gräns. På Android 16 blir den en
 * live-uppdatering med ett chip i statusraden (t.ex. "72/70").
 */
class SpeedService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watchJob: Job? = null
    private var signCache: Pair<Int?, Bitmap>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { shutdown(); return START_NOT_STICKY }
            ACTION_NOT_DRIVING -> { AutoStart.pause(this); shutdown(); return START_NOT_STICKY }
        }
        createChannel()
        val auto = intent?.getBooleanExtra(EXTRA_AUTO, false) == true
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(SpeedTracker.state.value), type)
        } catch (e: Exception) {
            // T.ex. om platsbehörigheten saknas – då går det inte att köra.
            stopSelf()
            return START_NOT_STICKY
        }
        SpeedTracker.onStopRequest = { shutdown() }
        SpeedTracker.start(this, auto)
        if (watchJob == null) {
            val nm = getSystemService(NotificationManager::class.java)
            watchJob = scope.launch {
                SpeedTracker.state
                    .map { listOf(it.speedKmh, it.limitKmh, it.tooFast, it.waiting, it.auto, it.running) }
                    .distinctUntilChanged()
                    .collect {
                        if (SpeedTracker.state.value.running)
                            nm.notify(NOTIFICATION_ID, buildNotification(SpeedTracker.state.value))
                    }
            }
        }
        return START_NOT_STICKY
    }

    private fun shutdown() {
        SpeedTracker.onStopRequest = null
        SpeedTracker.stop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        if (SpeedTracker.state.value.running) SpeedTracker.stop()
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("bevakning") // gamla kanalen (låg prioritet)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Hastighetsbevakning", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Visas medan appen bevakar din hastighet"
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }
    }

    private fun sign(limit: Int?): Bitmap {
        signCache?.let { if (it.first == limit) return it.second }
        val px = (resources.displayMetrics.density * 64).toInt()
        return SignPainter.signBitmap(this, limit, px).also { signCache = limit to it }
    }

    private fun buildNotification(s: UiState): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        fun service(code: Int, action: String) = PendingIntent.getService(
            this, code, Intent(this, SpeedService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val speed = s.speedKmh?.let { "$it km/h" } ?: "– km/h"
        val limit = s.limitKmh?.let { "Gräns $it" } ?: "Gräns okänd"
        val title = when {
            s.tooFast -> "Du kör för fort, IDIOT!"
            s.waiting -> "Väntar på fart…"
            else -> speed
        }
        val text = when {
            s.tooFast -> "$speed · $limit"
            s.waiting -> "Bevakar när du kör över ${SpeedTracker.AUTO_ARM_KMH} km/h"
            else -> limit
        }
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(sign(s.limitKmh))
            .setContentTitle(title)
            .setContentText(text)
            .setColor(if (s.tooFast) 0xFFE53935.toInt() else 0xFFFFC800.toInt())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, "Stopp", service(1, ACTION_STOP))
        if (s.auto) b.addAction(0, "Jag kör inte", service(2, ACTION_NOT_DRIVING))
        val n = b.build()

        // Chippet i statusraden: "72/70", eller bara hastigheten om gränsen är okänd.
        val chip = when {
            s.speedKmh == null -> s.limitKmh?.let { "–/$it" } ?: "–"
            s.limitKmh == null -> "${s.speedKmh}"
            else -> "${s.speedKmh}/${s.limitKmh}"
        }
        return promote(n, chip)
    }

    /**
     * Android 16: ber om att notisen visas som live-uppdatering (högt upp + chip i statusraden).
     * Görs via reflektion så att appen kan byggas mot SDK 35.
     */
    private fun promote(n: Notification, chip: String): Notification {
        if (Build.VERSION.SDK_INT < 36) return n
        return try {
            val b = Notification.Builder.recoverBuilder(this, n)
            b.addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
            Notification.Builder::class.java.getMethod("setShortCriticalText", String::class.java).invoke(b, chip)
            b.build()
        } catch (e: Throwable) {
            n
        }
    }

    companion object {
        private const val CHANNEL_ID = "bevakning2"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "se.dettmansfixeri.dukorforfort.STOP"
        private const val ACTION_NOT_DRIVING = "se.dettmansfixeri.dukorforfort.NOT_DRIVING"
        private const val EXTRA_AUTO = "auto"
        private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

        fun start(context: Context, auto: Boolean = false) {
            ContextCompat.startForegroundService(
                context, Intent(context, SpeedService::class.java).putExtra(EXTRA_AUTO, auto)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, SpeedService::class.java).setAction(ACTION_STOP))
        }

        /** Om telefonen låter appen visa live-uppdateringar (Android 16). null = stöds inte. */
        fun canPromote(context: Context): Boolean? {
            if (Build.VERSION.SDK_INT < 36) return null
            return try {
                val nm = context.getSystemService(NotificationManager::class.java)
                NotificationManager::class.java.getMethod("canPostPromotedNotifications").invoke(nm) as Boolean
            } catch (e: Throwable) { null }
        }
    }
}
