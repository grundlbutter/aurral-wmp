package org.aurral.android.ui

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.aurral.android.AurralApp
import org.aurral.android.SyncOptions
import org.aurral.android.sync.SyncScheduler
import org.aurral.android.sync.SyncWorker

private val kindLabels = mapOf(
    SyncWorker.KIND_FAVORITES to "Favorites",
    SyncWorker.KIND_PLAYLIST to "Playlist",
    SyncWorker.KIND_ALBUM to "Album",
    SyncWorker.KIND_ARTIST to "Artist",
)

@Composable
fun SyncScreen(nav: NavHostController) {
    val app = AurralApp.instance
    val context = LocalContext.current
    val dao = app.db.library()
    val selectionsFlow = remember { dao.selections() }
    val selections by selectionsFlow.collectAsStateWithLifecycle(emptyList())
    val tracksFlow = remember { dao.tracks() }
    val tracks by tracksFlow.collectAsStateWithLifecycle(emptyList())
    val options by app.syncOptions.state.collectAsStateWithLifecycle()
    val workFlow = remember { WorkManager.getInstance(context).getWorkInfosByTagFlow(SyncScheduler.TAG) }
    val work by workFlow.collectAsStateWithLifecycle(emptyList())
    val running = work.firstOrNull { it.state == WorkInfo.State.RUNNING }
    val waiting = running == null && work.any { it.state == WorkInfo.State.ENQUEUED && SyncScheduler.NOW in it.tags }
    val wholeLibrary = selections.any { it.kind == SyncWorker.KIND_LIBRARY }

    fun setOption(change: (SyncOptions) -> SyncOptions) {
        app.syncOptions.update(change)
        SyncScheduler.applySchedule(app)
    }

    Column(Modifier.fillMaxSize()) {
        GlassTopBar("Sync")
        LazyColumn(Modifier.weight(1f)) {
            item {
                Column(
                    Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Glass.Panel)
                        .padding(16.dp),
                ) {
                    when {
                        running != null -> {
                            val done = running.progress.getInt("done", 0)
                            val total = running.progress.getInt("total", 0)
                            Text(if (total > 0) "Downloading $done of $total" else "Checking what to sync")
                            Spacer(Modifier.height(8.dp))
                            if (total > 0) {
                                LinearProgressIndicator({ done.toFloat() / total }, Modifier.fillMaxWidth())
                            } else {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                            running.progress.getString("current")?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = Glass.Muted, maxLines = 1)
                            }
                        }
                        else -> {
                            Text(
                                if (options.lastSyncAt > 0) {
                                    "Last synced " + DateUtils.getRelativeTimeSpanString(options.lastSyncAt)
                                } else {
                                    "Not synced yet"
                                },
                            )
                            options.lastResult?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Glass.Muted) }
                            if (waiting && options.wifiOnly) {
                                Text("Waiting for Wi-Fi", style = MaterialTheme.typography.bodySmall, color = Glass.Sky)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { SyncScheduler.syncNow(app) }, enabled = running == null) {
                            Icon(Icons.Filled.Sync, null)
                            Text("Sync now")
                        }
                        Text(
                            "${tracks.size} tracks · ${Formatter.formatShortFileSize(context, tracks.sumOf { it.size })}",
                            Modifier.padding(start = 16.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = Glass.Muted,
                        )
                    }
                }
            }
            item { Heading("What to keep on this phone") }
            item {
                Toggle("My whole Library", "Everything in Aurral. Needs room on the phone.", wholeLibrary) {
                    selectionChange(SyncWorker.KIND_LIBRARY, SyncWorker.KIND_LIBRARY, "Whole Library", it)
                }
            }
            val picked = selections.filter { it.kind != SyncWorker.KIND_LIBRARY }
            if (picked.isEmpty()) {
                item {
                    Text(
                        "Nothing picked yet. Open an album, artist, playlist or Favorites in the Aurral tab and tap Sync.",
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = Glass.Muted,
                    )
                }
            }
            items(picked, key = { it.kind + ":" + it.id }) { selection ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(selection.name)
                        Text(kindLabels[selection.kind].orEmpty(), style = MaterialTheme.typography.bodySmall, color = Glass.Muted)
                    }
                    IconButton(onClick = { selectionChange(selection.kind, selection.id, selection.name, false) }) {
                        Icon(Icons.Filled.Close, "Stop syncing ${selection.name}")
                    }
                }
            }
            item { Heading("Options") }
            item {
                Toggle("Only on Wi-Fi", "Skip mobile data for downloads", options.wifiOnly) { on ->
                    setOption { it.copy(wifiOnly = on) }
                }
            }
            item {
                Toggle("Sync every day", "Picks up new Favorites, playlist changes and flows", options.daily) { on ->
                    setOption { it.copy(daily = on) }
                }
            }
            item {
                Toggle(
                    "Remove music I stop syncing",
                    "Deletes files this app downloaded once they're no longer picked",
                    options.removeUnselected,
                ) { on -> setOption { it.copy(removeUnselected = on) } }
            }
            item {
                Text(
                    "Files are saved in Music/Aurral, so other music apps can play them too.",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Glass.Muted,
                )
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun Toggle(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Glass.Muted)
        }
        Switch(checked, onChange)
    }
}
