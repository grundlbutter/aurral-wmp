package org.aurral.android.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A track whose file is on this phone. [uri] is its MediaStore content URI. */
@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val albumArtist: String,
    val album: String,
    val albumId: String?,
    val track: Int,
    val disc: Int,
    val duration: Int,
    val year: Int?,
    val coverArt: String?,
    val uri: String,
    val size: Long,
    val syncedAt: Long,
)

/** Something the user chose to keep on the phone. kind: favorites, playlist, album, artist, library. */
@Entity(tableName = "selections", primaryKeys = ["kind", "id"])
data class SelectionEntity(val kind: String, val id: String, val name: String)

@Entity(tableName = "playlists")
data class PlaylistEntity(@PrimaryKey val id: String, val name: String, val coverArt: String?)

@Entity(tableName = "playlist_tracks", primaryKeys = ["playlistId", "position"])
data class PlaylistTrackEntity(val playlistId: String, val position: Int, val trackId: String)

@Entity(tableName = "scrobbles")
data class ScrobbleEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val trackId: String,
    val playedAt: Long,
)

@Dao
interface LibraryDao {
    @Query("SELECT * FROM tracks ORDER BY albumArtist COLLATE NOCASE, album COLLATE NOCASE, disc, track")
    fun tracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks")
    suspend fun allTracks(): List<TrackEntity>

    @Upsert
    suspend fun upsertTrack(track: TrackEntity)

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun deleteTrack(id: String)

    @Query("SELECT * FROM selections ORDER BY kind, name COLLATE NOCASE")
    fun selections(): Flow<List<SelectionEntity>>

    @Query("SELECT * FROM selections")
    suspend fun allSelections(): List<SelectionEntity>

    @Upsert
    suspend fun upsertSelection(selection: SelectionEntity)

    @Query("DELETE FROM selections WHERE kind = :kind AND id = :id")
    suspend fun deleteSelection(kind: String, id: String)

    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE")
    fun playlists(): Flow<List<PlaylistEntity>>

    @Query(
        "SELECT t.* FROM playlist_tracks p JOIN tracks t ON t.id = p.trackId " +
            "WHERE p.playlistId = :id ORDER BY p.position",
    )
    fun playlistTracks(id: String): Flow<List<TrackEntity>>

    @Query("DELETE FROM playlists")
    suspend fun clearPlaylists()

    @Query("DELETE FROM playlist_tracks")
    suspend fun clearPlaylistTracks()

    @Insert
    suspend fun insertPlaylists(playlists: List<PlaylistEntity>)

    @Insert
    suspend fun insertPlaylistTracks(entries: List<PlaylistTrackEntity>)

    @Transaction
    suspend fun replacePlaylists(playlists: List<PlaylistEntity>, entries: List<PlaylistTrackEntity>) {
        clearPlaylistTracks()
        clearPlaylists()
        insertPlaylists(playlists)
        insertPlaylistTracks(entries)
    }

    @Insert
    suspend fun insertScrobble(scrobble: ScrobbleEntity)

    @Query("SELECT * FROM scrobbles ORDER BY playedAt LIMIT 200")
    suspend fun pendingScrobbles(): List<ScrobbleEntity>

    @Query("DELETE FROM scrobbles WHERE rowId = :rowId")
    suspend fun deleteScrobble(rowId: Long)
}

@Database(
    entities = [
        TrackEntity::class,
        SelectionEntity::class,
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
        ScrobbleEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AurralDb : RoomDatabase() {
    abstract fun library(): LibraryDao
}
