package org.aurral.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import org.aurral.android.AurralApp
import org.aurral.android.data.TrackEntity

private val TrackEntity.albumKey get() = albumId ?: "$albumArtist\u0000$album"

private data class LocalAlbum(val key: String, val name: String, val artist: String, val year: Int?, val tracks: List<TrackEntity>)

private fun List<TrackEntity>.albums(): List<LocalAlbum> =
    groupBy { it.albumKey }.map { (key, tracks) ->
        val first = tracks.first()
        LocalAlbum(key, first.album, first.albumArtist, first.year, tracks.sortedWith(compareBy({ it.disc }, { it.track })))
    }.sortedBy { it.name.lowercase() }

@Composable
private fun rememberTracks(): List<TrackEntity> {
    val flow = remember { AurralApp.instance.db.library().tracks() }
    val tracks by flow.collectAsStateWithLifecycle(emptyList())
    return tracks
}

@Composable
fun PhoneHome(nav: NavHostController) {
    val tracks = rememberTracks()
    val playlistsFlow = remember { AurralApp.instance.db.library().playlists() }
    val playlists by playlistsFlow.collectAsStateWithLifecycle(emptyList())
    var view by rememberSaveable { mutableStateOf("Artists") }
    val player = LocalPlayer.current

    Column(Modifier.fillMaxSize()) {
        GlassTopBar("On this phone", actions = {
            IconButton(onClick = { player?.playTracks(tracks.map { it.toUi() }, shuffle = true) }, enabled = tracks.isNotEmpty()) {
                Icon(Icons.Filled.Shuffle, "Shuffle everything")
            }
            IconButton(onClick = { nav.navigate("settings") }) { Icon(Icons.Filled.Settings, "Server and account") }
        })
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Artists", "Albums", "Playlists").forEach { name ->
                FilterChip(selected = view == name, onClick = { view = name }, label = { Text(name) })
            }
        }
        if (tracks.isEmpty()) {
            Message("Nothing on this phone yet. Pick music in the Aurral tab and tap Sync.", "Browse Aurral") {
                nav.navigate("server")
            }
            return@Column
        }
        LazyColumn(Modifier.weight(1f)) {
            when (view) {
                "Artists" -> {
                    val artists = tracks.groupBy { it.albumArtist }.entries.sortedBy { it.key.lowercase() }
                    items(artists, key = { it.key }) { (artist, artistTracks) ->
                        val albumCount = artistTracks.distinctBy { it.albumKey }.size
                        ListRow(
                            artist,
                            "$albumCount albums · ${artistTracks.size} tracks",
                            localArtwork(artistTracks.first().coverArt),
                            onClick = { nav.open("phone/artist", artist) },
                        )
                    }
                }
                "Albums" -> items(tracks.albums(), key = { it.key }) { album ->
                    ListRow(album.name, album.artist, localArtwork(album.tracks.first().coverArt), onClick = {
                        nav.open("phone/album", album.key)
                    })
                }
                else -> {
                    if (playlists.isEmpty()) item { Message("Synced playlists and Favorites show up here.") }
                    items(playlists, key = { it.id }) { playlist ->
                        ListRow(playlist.name, null, localArtwork(playlist.coverArt), onClick = {
                            nav.open("phone/playlist", playlist.id)
                        })
                    }
                }
            }
        }
    }
}

@Composable
fun PhoneArtist(nav: NavHostController, artist: String) {
    val albums = rememberTracks().filter { it.albumArtist == artist }.albums()
    Column(Modifier.fillMaxSize()) {
        GlassTopBar(artist, onBack = { nav.popBackStack() })
        LazyColumn {
            item { DetailHeader(artist, null, localArtwork(albums.firstOrNull()?.tracks?.first()?.coverArt), albums.flatMap { a -> a.tracks.map { it.toUi() } }) }
            items(albums, key = { it.key }) { album ->
                ListRow(album.name, album.year?.toString(), localArtwork(album.tracks.first().coverArt), onClick = {
                    nav.open("phone/album", album.key)
                })
            }
        }
    }
}

@Composable
fun PhoneAlbum(nav: NavHostController, key: String) {
    val album = rememberTracks().albums().firstOrNull { it.key == key }
    Column(Modifier.fillMaxSize()) {
        GlassTopBar(album?.name.orEmpty(), onBack = { nav.popBackStack() })
        if (album == null) return@Column
        val tracks = album.tracks.map { it.toUi() }
        TrackList(tracks, showArtwork = false) {
            DetailHeader(album.name, album.artist, localArtwork(album.tracks.first().coverArt), tracks)
        }
    }
}

@Composable
fun PhonePlaylist(nav: NavHostController, id: String) {
    val dao = AurralApp.instance.db.library()
    val playlistsFlow = remember { dao.playlists() }
    val playlist = playlistsFlow.collectAsStateWithLifecycle(emptyList()).value.firstOrNull { it.id == id }
    val tracksFlow = remember(id) { dao.playlistTracks(id) }
    val tracks = tracksFlow.collectAsStateWithLifecycle(emptyList()).value.map { it.toUi() }
    Column(Modifier.fillMaxSize()) {
        GlassTopBar(playlist?.name.orEmpty(), onBack = { nav.popBackStack() })
        TrackList(tracks, showArtwork = true) {
            DetailHeader(playlist?.name.orEmpty(), null, localArtwork(playlist?.coverArt), tracks)
        }
    }
}

@Composable
fun TrackList(tracks: List<TrackUi>, showArtwork: Boolean, header: @Composable () -> Unit) {
    val player = LocalPlayer.current
    LazyColumn {
        item { header() }
        itemsIndexed(tracks, key = { index, track -> "$index:${track.id}" }) { index, track ->
            TrackRow(track, showArtwork) { player?.playTracks(tracks, index) }
        }
    }
}
