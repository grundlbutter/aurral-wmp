package org.aurral.android.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import kotlinx.coroutines.delay
import org.aurral.android.AurralApp
import org.aurral.android.api.Album
import org.aurral.android.api.Song
import org.aurral.android.data.TrackEntity
import org.aurral.android.sync.SyncWorker

private fun api() = AurralApp.instance.api() ?: throw java.io.IOException("Not signed in")

@Composable
private fun rememberSynced(): Map<String, TrackEntity> {
    val flow = remember { AurralApp.instance.db.library().tracks() }
    val tracks by flow.collectAsStateWithLifecycle(emptyList())
    return remember(tracks) { tracks.associateBy { it.id } }
}

@Composable
private fun rememberSelected(kind: String, id: String): Boolean {
    val flow = remember { AurralApp.instance.db.library().selections() }
    val selections by flow.collectAsStateWithLifecycle(emptyList())
    return selections.any { it.kind == kind && it.id == id }
}

@Composable
private fun <T> Loaded(load: Load<T>, retry: () -> Unit, content: @Composable (T) -> Unit) {
    when (load) {
        Load.Loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is Load.Failed -> Message(load.message, "Try again", retry)
        is Load.Done -> content(load.value)
    }
}

private fun cover(id: String?) = id?.let { AurralApp.instance.api()?.coverUrl(it) }

@Composable
fun ServerHome(nav: NavHostController) {
    var view by rememberSaveable { mutableStateOf("Albums") }
    var query by rememberSaveable { mutableStateOf("") }
    var debounced by remember { mutableStateOf(query) }
    androidx.compose.runtime.LaunchedEffect(query) {
        delay(350)
        debounced = query.trim()
    }

    Column(Modifier.fillMaxSize()) {
        GlassTopBar("Aurral", actions = {
            IconButton(onClick = { nav.navigate("settings") }) { Icon(Icons.Filled.Settings, "Server and account") }
        })
        OutlinedTextField(
            query,
            { query = it },
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Search your Library") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            singleLine = true,
        )
        if (debounced.isNotEmpty()) {
            SearchResults(nav, debounced)
            return@Column
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("Albums", "Artists", "Playlists").forEach { name ->
                FilterChip(selected = view == name, onClick = { view = name }, label = { Text(name) })
            }
            FilterChip(selected = false, onClick = { nav.navigate("server/favorites") }, label = { Text("Favorites") })
        }
        when (view) {
            "Artists" -> {
                val (load, retry) = rememberLoad("artists") { api().artists() }
                Loaded(load, retry) { artists ->
                    LazyColumn {
                        items(artists, key = { it.id }) { artist ->
                            ListRow(artist.name, "${artist.albumCount} albums", cover(artist.coverArt), onClick = {
                                nav.open("server/artist", artist.id)
                            })
                        }
                    }
                }
            }
            "Playlists" -> {
                val (load, retry) = rememberLoad("playlists") { api().playlists() }
                Loaded(load, retry) { playlists ->
                    LazyColumn {
                        items(playlists, key = { it.id }) { playlist ->
                            ListRow(playlist.name, "${playlist.songCount} tracks", cover(playlist.coverArt), onClick = {
                                nav.open("server/playlist", playlist.id)
                            })
                        }
                    }
                }
            }
            else -> AlbumPages(nav)
        }
    }
}

@Composable
private fun AlbumPages(nav: NavHostController) {
    var pages by rememberSaveable { mutableIntStateOf(1) }
    val (load, retry) = rememberLoad("albums", pages) { api().albums(0, pages * PAGE) }
    Loaded(load, retry) { albums ->
        LazyColumn {
            albumItems(nav, albums)
            if (albums.size == pages * PAGE) {
                item {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        OutlinedButton(onClick = { pages++ }) { Text("Show more") }
                    }
                }
            }
        }
    }
}

private const val PAGE = 100

private fun LazyListScope.albumItems(nav: NavHostController, albums: List<Album>) {
    items(albums, key = { "album:" + it.id }) { album ->
        ListRow(album.name, listOfNotNull(album.artist, album.year?.toString()).joinToString(" · "), cover(album.coverArt), onClick = {
            nav.open("server/album", album.id)
        })
    }
}

@Composable
private fun SearchResults(nav: NavHostController, query: String) {
    val synced = rememberSynced()
    val player = LocalPlayer.current
    val (load, retry) = rememberLoad(query) { api().search(query) }
    Loaded(load, retry) { result ->
        val api = AurralApp.instance.api()
        val songs = result.song.map { it.toUi(synced, api) }
        LazyColumn {
            if (result.artist.isEmpty() && result.album.isEmpty() && songs.isEmpty()) item { Message("Nothing found") }
            if (result.artist.isNotEmpty()) item { SectionLabel("Artists") }
            items(result.artist, key = { "artist:" + it.id }) { artist ->
                ListRow(artist.name, null, cover(artist.coverArt), onClick = { nav.open("server/artist", artist.id) })
            }
            if (result.album.isNotEmpty()) item { SectionLabel("Albums") }
            albumItems(nav, result.album)
            if (songs.isNotEmpty()) item { SectionLabel("Tracks") }
            itemsIndexed(songs, key = { _, song -> "song:" + song.id }) { index, song ->
                TrackRow(song, showArtwork = true) { player?.playTracks(songs, index) }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
fun ServerArtist(nav: NavHostController, id: String) {
    val (load, retry) = rememberLoad(id) { api().artist(id) }
    val selected = rememberSelected(SyncWorker.KIND_ARTIST, id)
    val name = (load as? Load.Done)?.value?.name.orEmpty()
    Column(Modifier.fillMaxSize()) {
        GlassTopBar(name, onBack = { nav.popBackStack() })
        Loaded(load, retry) { artist ->
            LazyColumn {
                item {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Cover(cover(artist.coverArt), 96.dp)
                        Column(Modifier.padding(start = 16.dp)) {
                            Text(artist.name, style = MaterialTheme.typography.titleLarge)
                            Text("${artist.album.size} albums", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            SyncChip(selected) { selectionChange(SyncWorker.KIND_ARTIST, id, artist.name, it) }
                        }
                    }
                }
                albumItems(nav, artist.album)
            }
        }
    }
}

@Composable
fun ServerAlbum(nav: NavHostController, id: String) {
    val (load, retry) = rememberLoad(id) { api().album(id) }
    val selected = rememberSelected(SyncWorker.KIND_ALBUM, id)
    val synced = rememberSynced()
    Column(Modifier.fillMaxSize()) {
        GlassTopBar((load as? Load.Done)?.value?.name.orEmpty(), onBack = { nav.popBackStack() })
        Loaded(load, retry) { album ->
            val tracks = album.song.toTracks(synced)
            TrackList(tracks, showArtwork = false) {
                DetailHeader(album.name, album.artist, cover(album.coverArt), tracks) {
                    SyncChip(selected) { selectionChange(SyncWorker.KIND_ALBUM, id, "${album.artist} - ${album.name}", it) }
                }
            }
        }
    }
}

@Composable
fun ServerPlaylist(nav: NavHostController, id: String) {
    val (load, retry) = rememberLoad(id) { api().playlist(id) }
    val selected = rememberSelected(SyncWorker.KIND_PLAYLIST, id)
    val synced = rememberSynced()
    Column(Modifier.fillMaxSize()) {
        GlassTopBar((load as? Load.Done)?.value?.name.orEmpty(), onBack = { nav.popBackStack() })
        Loaded(load, retry) { playlist ->
            val tracks = playlist.entry.toTracks(synced)
            TrackList(tracks, showArtwork = true) {
                DetailHeader(playlist.name, playlist.owner, cover(playlist.coverArt), tracks) {
                    SyncChip(selected) { selectionChange(SyncWorker.KIND_PLAYLIST, id, playlist.name, it) }
                }
            }
        }
    }
}

@Composable
fun ServerFavorites(nav: NavHostController) {
    val (load, retry) = rememberLoad("favorites") { api().starred() }
    val selected = rememberSelected(SyncWorker.KIND_FAVORITES, SyncWorker.KIND_FAVORITES)
    val synced = rememberSynced()
    Column(Modifier.fillMaxSize()) {
        GlassTopBar("Favorites", onBack = { nav.popBackStack() })
        Loaded(load, retry) { starred ->
            val tracks = starred.song.toTracks(synced)
            TrackList(tracks, showArtwork = true) {
                Column {
                    DetailHeader("Favorites", "Favorite tracks, plus your favorite albums when synced", null, tracks) {
                        SyncChip(selected) {
                            selectionChange(SyncWorker.KIND_FAVORITES, SyncWorker.KIND_FAVORITES, "Favorites", it)
                        }
                    }
                    if (starred.album.isNotEmpty()) {
                        SectionLabel("Favorite albums")
                        starred.album.forEach { album ->
                            ListRow(album.name, album.artist, cover(album.coverArt), onClick = { nav.open("server/album", album.id) })
                        }
                        SectionLabel("Favorite tracks")
                    }
                }
            }
        }
    }
}

private fun List<Song>.toTracks(synced: Map<String, TrackEntity>): List<TrackUi> {
    val api = AurralApp.instance.api()
    return map { it.toUi(synced, api) }
}
