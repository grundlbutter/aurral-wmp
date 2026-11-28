package org.aurral.android.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.launch
import org.aurral.android.AurralApp
import org.aurral.android.api.SubsonicClient
import org.aurral.android.api.SubsonicException
import org.aurral.android.data.ScrobbleEntity
import org.aurral.android.ui.MainActivity
import java.io.IOException

class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(ScrobbleListener(player))
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(object : MediaSession.Callback {
                // Controllers only pass requestMetadata across, so the playable URI travels there.
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
                    mediaItems.map { item ->
                        item.requestMetadata.mediaUri?.let { item.buildUpon().setUri(it).build() } ?: item
                    }.toMutableList(),
                )
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}

/** Counts a play once half the track (or four minutes) has been heard. */
private class ScrobbleListener(private val player: Player) : Player.Listener {
    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (oldPosition.mediaItemIndex != newPosition.mediaItemIndex ||
            reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
        ) {
            consider(oldPosition.mediaItem, oldPosition.positionMs)
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) consider(player.currentMediaItem, Long.MAX_VALUE)
    }

    private fun consider(item: MediaItem?, positionMs: Long) {
        val id = item?.mediaId?.takeIf { it.isNotEmpty() } ?: return
        val duration = item.mediaMetadata.durationMs ?: return
        if (duration <= 0 || positionMs >= minOf(duration / 2, 240_000)) AurralApp.instance.scrobbles.record(id)
    }
}

/** Plays are queued in the database and sent whenever Aurral can be reached. */
class Scrobbles(private val app: AurralApp) {
    private val dao get() = app.db.library()

    fun record(trackId: String) {
        app.scope.launch {
            dao.insertScrobble(ScrobbleEntity(trackId = trackId, playedAt = System.currentTimeMillis()))
            app.api()?.let { runCatching { flush(it) } }
        }
    }

    suspend fun flush(api: SubsonicClient) {
        for (scrobble in dao.pendingScrobbles()) {
            try {
                api.scrobble(scrobble.trackId, scrobble.playedAt)
            } catch (e: SubsonicException) {
                if (e.code == null) return
            } catch (e: IOException) {
                return
            }
            dao.deleteScrobble(scrobble.rowId)
        }
    }
}
