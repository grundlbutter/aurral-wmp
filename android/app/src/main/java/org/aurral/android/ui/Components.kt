package org.aurral.android.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import org.aurral.android.AurralApp
import org.aurral.android.api.Song
import org.aurral.android.api.SubsonicClient
import org.aurral.android.data.TrackEntity
import org.aurral.android.sync.coverFile

/** One playable track, from the phone or from Aurral. */
data class TrackUi(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val number: Int,
    val durationSec: Int,
    val artwork: String?,
    val localUri: String?,
    val streamUrl: String?,
) {
    val playUri get() = localUri ?: streamUrl

    fun toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(playUri?.let(Uri::parse)).build())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(artwork?.let(Uri::parse))
                .setDurationMs(durationSec * 1000L)
                .build(),
        )
        .build()
}

fun localArtwork(coverArt: String?): String? {
    val app = AurralApp.instance
    val file = coverArt?.let { coverFile(app, it) } ?: return null
    return if (file.exists()) Uri.fromFile(file).toString() else null
}

fun TrackEntity.toUi() = TrackUi(id, title, artist, album, track, duration, localArtwork(coverArt), uri, null)

fun Song.toUi(synced: Map<String, TrackEntity>, api: SubsonicClient?): TrackUi {
    val local = synced[id]
    return TrackUi(
        id = id,
        title = name,
        artist = artist,
        album = album,
        number = track,
        durationSec = duration,
        artwork = local?.let { localArtwork(it.coverArt) } ?: coverArt?.let { api?.coverUrl(it, 600) },
        localUri = local?.uri,
        streamUrl = api?.streamUrl(id),
    )
}

val LocalPlayer = compositionLocalOf<MediaController?> { null }

fun MediaController.playTracks(tracks: List<TrackUi>, start: Int = 0, shuffle: Boolean = false) {
    val playable = tracks.filter { it.playUri != null }
    if (playable.isEmpty()) return
    val first = tracks.getOrNull(start)?.let { playable.indexOf(it) }?.coerceAtLeast(0) ?: 0
    shuffleModeEnabled = shuffle
    setMediaItems(playable.map { it.toMediaItem() }, if (shuffle) (playable.indices).random() else first, 0)
    prepare()
    play()
}

data class PlayerUi(
    val hasItem: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val artwork: Uri? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF,
)

@Composable
fun rememberPlayerUi(controller: MediaController?): PlayerUi {
    var revision by remember { mutableLongStateOf(0) }
    var position by remember { mutableLongStateOf(0) }
    DisposableEffect(controller) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                revision++
                position = player.currentPosition
            }
        }
        controller?.addListener(listener)
        onDispose { controller?.removeListener(listener) }
    }
    LaunchedEffect(controller, revision) {
        while (controller?.isPlaying == true) {
            position = controller?.currentPosition ?: 0
            delay(500)
        }
    }
    val player = controller ?: return PlayerUi()
    return remember(player, revision, position) { player.toUi(position) }
}

private fun Player.toUi(position: Long): PlayerUi {
    val metadata = mediaMetadata
    return PlayerUi(
        hasItem = currentMediaItem != null,
        title = metadata.title?.toString().orEmpty(),
        artist = metadata.artist?.toString().orEmpty(),
        artwork = metadata.artworkUri,
        isPlaying = isPlaying,
        positionMs = position,
        durationMs = duration.coerceAtLeast(0),
        shuffle = shuffleModeEnabled,
        repeat = repeatMode,
    )
}

@Composable
fun Cover(model: Any?, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(4.dp))
            .background(Glass.Panel),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.MusicNote, null, tint = Glass.Muted, modifier = Modifier.size(size / 2))
        if (model != null) {
            AsyncImage(model, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

@Composable
fun ListRow(title: String, subtitle: String?, artwork: Any?, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(artwork, 48.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing()
    }
}

@Composable
fun TrackRow(track: TrackUi, showArtwork: Boolean, onClick: () -> Unit) {
    val playable = track.playUri != null
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = playable, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showArtwork) {
            Cover(track.artwork, 40.dp)
            Spacer(Modifier.width(12.dp))
        } else {
            Text(
                if (track.number > 0) track.number.toString() else "",
                Modifier.width(28.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (playable) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                track.artist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (track.localUri != null && track.streamUrl != null) {
            Icon(Icons.Filled.CloudDone, "On this phone", tint = Glass.Aqua, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(formatTime(track.durationSec * 1000L), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Cover, title and the play/shuffle/sync buttons at the top of an album or playlist. */
@Composable
fun DetailHeader(
    title: String,
    subtitle: String?,
    artwork: Any?,
    tracks: List<TrackUi>,
    syncControl: (@Composable () -> Unit)? = null,
) {
    val player = LocalPlayer.current
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(artwork, 112.dp)
            Column(Modifier.padding(start = 16.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 3)
                if (!subtitle.isNullOrBlank()) Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "${tracks.size} tracks",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { player?.playTracks(tracks) }, enabled = player != null && tracks.isNotEmpty()) {
                Icon(Icons.Filled.PlayArrow, null)
                Text("Play")
            }
            OutlinedButton(onClick = { player?.playTracks(tracks, shuffle = true) }, enabled = player != null && tracks.isNotEmpty()) {
                Icon(Icons.Filled.Shuffle, null)
                Text("Shuffle")
            }
            syncControl?.invoke()
        }
    }
}

@Composable
fun SyncChip(selected: Boolean, onToggle: (Boolean) -> Unit) {
    FilterChip(
        selected = selected,
        onClick = { onToggle(!selected) },
        label = { Text(if (selected) "Synced" else "Sync") },
        leadingIcon = if (selected) {
            { Icon(Icons.Filled.CloudDone, null, Modifier.size(18.dp)) }
        } else {
            null
        },
    )
}

@Composable
fun MiniPlayer(onOpen: () -> Unit) {
    val controller = LocalPlayer.current
    val ui = rememberPlayerUi(controller)
    if (!ui.hasItem) return
    Column(Modifier.fillMaxWidth().background(Glass.bar).clickable(onClick = onOpen)) {
        if (ui.durationMs > 0) {
            Box(Modifier.fillMaxWidth().height(2.dp).background(Glass.Line)) {
                Box(
                    Modifier
                        .fillMaxWidth((ui.positionMs.toFloat() / ui.durationMs).coerceIn(0f, 1f))
                        .height(2.dp)
                        .background(Glass.Aqua),
                )
            }
        }
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(ui.artwork, 40.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(ui.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(ui.artist, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = Glass.Muted)
            }
            GlassOrb(40.dp, Modifier.clickable { if (ui.isPlaying) controller?.pause() else controller?.play() }) {
                Icon(if (ui.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play or pause", tint = Color.White)
            }
            IconButton(onClick = { controller?.seekToNext() }) {
                Icon(Icons.Filled.SkipNext, "Next")
            }
        }
    }
}

@Composable
fun Message(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onAction) { Text(action) }
        }
    }
}

/** Result of loading something from Aurral. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Done<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

@Composable
fun <T> rememberLoad(vararg keys: Any?, load: suspend () -> T): Pair<Load<T>, () -> Unit> {
    var attempt by remember { mutableLongStateOf(0) }
    var state by remember(*keys) { mutableStateOf<Load<T>>(Load.Loading) }
    LaunchedEffect(*keys, attempt) {
        state = Load.Loading
        state = try {
            Load.Done(load())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Failed(
                (e.message ?: "Can't reach Aurral") + "\n\nSynced music still plays from On this phone.",
            )
        }
    }
    return state to { attempt++ }
}

fun formatTime(ms: Long): String {
    if (ms <= 0) return ""
    val seconds = ms / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
