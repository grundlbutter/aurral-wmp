package org.aurral.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import coil.compose.AsyncImage

@Composable
fun NowPlayingScreen(onBack: () -> Unit) {
    val controller = LocalPlayer.current
    val ui = rememberPlayerUi(controller)
    var seeking by remember { mutableStateOf<Float?>(null) }

    Column(Modifier.fillMaxSize().background(Glass.page).navigationBarsPadding()) {
        GlassTopBar("Now Playing", onBack = onBack)
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Artwork(ui.artwork, Modifier.fillMaxWidth(0.85f))
            // The mirrored art under the cover, as in the desktop skin's Now Playing view.
            Box(
                Modifier
                    .fillMaxWidth(0.85f)
                    .height(56.dp)
                    .clip(RoundedCornerShape(0.dp)),
            ) {
                Artwork(
                    ui.artwork,
                    Modifier
                        .fillMaxWidth()
                        .wrapContentHeight(Alignment.Top, unbounded = true)
                        .graphicsLayer {
                            scaleY = -1f
                            alpha = 0.25f
                        },
                )
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Glass.Night))))
            }
            Text(ui.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(ui.artist, color = Glass.Muted, maxLines = 1)
        }
        Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            val duration = ui.durationMs.coerceAtLeast(1)
            Slider(
                value = seeking ?: (ui.positionMs.toFloat() / duration).coerceIn(0f, 1f),
                onValueChange = { seeking = it },
                onValueChangeFinished = {
                    seeking?.let { controller?.seekTo((it * duration).toLong()) }
                    seeking = null
                },
                enabled = ui.durationMs > 0,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(seeking?.let { (it * duration).toLong() } ?: ui.positionMs), style = MaterialTheme.typography.bodySmall, color = Glass.Muted)
                Text(formatTime(ui.durationMs), style = MaterialTheme.typography.bodySmall, color = Glass.Muted)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { controller?.shuffleModeEnabled = !ui.shuffle }) {
                    Icon(Icons.Filled.Shuffle, "Shuffle", tint = if (ui.shuffle) Glass.Aqua else Glass.Muted)
                }
                IconButton(onClick = { controller?.seekToPrevious() }) { Icon(Icons.Filled.SkipPrevious, "Previous", Modifier.size(32.dp)) }
                GlassOrb(72.dp, Modifier.clickable { if (ui.isPlaying) controller?.pause() else controller?.play() }) {
                    Icon(
                        if (ui.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        "Play or pause",
                        Modifier.size(40.dp),
                        tint = Color.White,
                    )
                }
                IconButton(onClick = { controller?.seekToNext() }) { Icon(Icons.Filled.SkipNext, "Next", Modifier.size(32.dp)) }
                IconButton(onClick = {
                    controller?.repeatMode = when (ui.repeat) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                }) {
                    Icon(
                        if (ui.repeat == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                        "Repeat",
                        tint = if (ui.repeat == Player.REPEAT_MODE_OFF) Glass.Muted else Glass.Aqua,
                    )
                }
            }
        }
    }
}

@Composable
private fun Artwork(model: Any?, modifier: Modifier) {
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(Glass.Panel)
            .border(1.dp, Glass.Line, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.MusicNote, null, Modifier.size(64.dp), tint = Glass.Muted)
        if (model != null) AsyncImage(model, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}
