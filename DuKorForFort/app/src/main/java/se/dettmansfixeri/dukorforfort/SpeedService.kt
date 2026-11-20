package se.dettmansfixeri.dukorforfort

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
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
 * Räknas som "när appen används", så ingen bakgrundsbehörighet behövs.
 * Visar en notis med hastighet och gräns, och har en Stopp-knapp.
 */
class SpeedService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watchJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(SpeedTracker.state.value), type)
        } catch (e: Exception) {
            // T.ex. om platsbehörigheten saknas – då går det inte att köra.
            stopSelf()
            return START_NOT_STICKY
        }
        SpeedTracker.start(this)
        if (watchJob == null) {
            val nm = getSystemService(NotificationManager::class.java)
            watchJob = scope.launch {
                SpeedTracker.state
                    .map { Triple(it.speedKmh, it.limitKmh, it.tooFast) }
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
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Hastighetsbevakning", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Visas medan appen bevakar din hastighet"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun buildNotification(s: UiState): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, SpeedService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val speed = s.speedKmh?.let { "$it km/h" } ?: "– km/h"
        val limit = s.limitKmh?.let { "Gräns $it" } ?: "Gräns okänd"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (s.tooFast) "Du kör för fort, IDIOT!" else speed)
            .setContentText(if (s.tooFast) "$speed · $limit" else limit)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, "Stopp", stop)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "bevakning"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "se.dettmansfixeri.dukorforfort.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, SpeedService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, SpeedService::class.java).setAction(ACTION_STOP))
        }
    }
}
