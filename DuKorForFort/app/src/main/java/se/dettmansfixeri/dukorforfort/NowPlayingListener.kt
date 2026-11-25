package se.dettmansfixeri.dukorforfort

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat

/**
 * Finns bara för att Android ska låta appen se vad som spelas (titel och artist).
 * Den läser inga aviseringar och skickar ingenting någonstans.
 */
class NowPlayingListener : NotificationListenerService()

object NowPlaying {
    /** Har man slagit på "Tillgång till aviseringar" för appen? */
    fun allowed(ctx: Context) = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    fun openSettings(ctx: Context) {
        ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** "Artist – Låt" för det som spelas just nu, annars null. */
    fun text(ctx: Context): String? {
        if (!allowed(ctx)) return null
        val msm = ctx.getSystemService(MediaSessionManager::class.java) ?: return null
        val sessions = try {
            msm.getActiveSessions(ComponentName(ctx, NowPlayingListener::class.java))
        } catch (e: SecurityException) { return null }
        val c = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: return null
        val m = c.metadata ?: return null
        val title = m.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
        val artist = (m.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))?.trim().orEmpty()
        return listOf(artist, title).filter { it.isNotEmpty() }.joinToString(" – ").ifEmpty { null }
    }
}
