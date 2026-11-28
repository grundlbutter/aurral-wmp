package org.aurral.android.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.aurral.android.AurralApp
import org.aurral.android.R
import org.aurral.android.api.Song
import org.aurral.android.api.SubsonicClient
import org.aurral.android.api.SubsonicException
import org.aurral.android.api.md5
import org.aurral.android.data.PlaylistEntity
import org.aurral.android.data.PlaylistTrackEntity
import org.aurral.android.data.SelectionEntity
import org.aurral.android.data.TrackEntity
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object SyncScheduler {
    const val TAG = "sync"
    const val NOW = "sync-now"
    private const val DAILY = "sync-daily"

    private fun constraints(context: Context) = Constraints.Builder()
        .setRequiredNetworkType(
            if ((context.applicationContext as AurralApp).syncOptions.state.value.wifiOnly) {
                NetworkType.UNMETERED
            } else {
                NetworkType.CONNECTED
            },
        )
        .setRequiresStorageNotLow(true)
        .build()

    fun syncNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints(context)).addTag(TAG).addTag(NOW).build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
    }

    fun applySchedule(context: Context) {
        val app = context.applicationContext as AurralApp
        val work = WorkManager.getInstance(context)
        if (app.syncOptions.state.value.daily && app.accounts.state.value != null) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.DAYS)
                .setConstraints(constraints(context))
                .addTag(TAG)
                .build()
            work.enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.UPDATE, request)
        } else {
            work.cancelUniqueWork(DAILY)
        }
    }
}

private data class Planned(val song: Song, val folderArtist: String)

private class Plan {
    val tracks = LinkedHashMap<String, Planned>()
    val playlists = mutableListOf<Pair<PlaylistEntity, List<String>>>()

    fun add(song: Song, folderArtist: String? = null) {
        if (song.id !in tracks) tracks[song.id] = Planned(song, folderArtist?.takeIf { it.isNotBlank() } ?: song.artist)
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val app = context.applicationContext as AurralApp
    private val dao = app.db.library()
    private val resolver = context.contentResolver

    override suspend fun doWork(): Result = lock.withLock {
        val api = app.api() ?: return Result.failure()
        runCatching { setForeground(foreground("Checking what to sync", 0, 0)) }
        try {
            app.scrobbles.flush(api)
            val summary = sync(api)
            app.syncOptions.update { it.copy(lastSyncAt = System.currentTimeMillis(), lastResult = summary) }
            Result.success()
        } catch (e: IOException) {
            app.syncOptions.update { it.copy(lastResult = "Sync stopped: ${e.message}") }
            if (runAttemptCount < 2 && e !is SubsonicException) Result.retry() else Result.failure()
        }
    }

    private suspend fun sync(api: SubsonicClient): String {
        val plan = plan(api, dao.allSelections())
        val existing = dao.allTracks().associateBy { it.id }
        val missing = plan.tracks.values.filter { planned ->
            val have = existing[planned.song.id]
            have == null || !exists(Uri.parse(have.uri))
        }

        var downloaded = 0
        var failed = 0
        missing.forEachIndexed { index, planned ->
            val label = "${planned.song.artist} - ${planned.song.name}"
            setProgress(workDataOf("done" to index, "total" to missing.size, "current" to label))
            runCatching { setForeground(foreground(label, index, missing.size)) }
            try {
                existing[planned.song.id]?.let { dao.deleteTrack(it.id) }
                dao.upsertTrack(save(api, planned))
                downloaded++
            } catch (e: SubsonicException) {
                failed++
            }
        }

        plan.tracks.values.mapNotNull { it.song.coverArt }.distinct().forEach { id ->
            val file = coverFile(applicationContext, id)
            if (!file.exists()) runCatching { api.downloadCover(id, file) }
        }

        var removed = 0
        if (app.syncOptions.state.value.removeUnselected) {
            existing.values.filter { it.id !in plan.tracks }.forEach { track ->
                runCatching { resolver.delete(Uri.parse(track.uri), null, null) }
                dao.deleteTrack(track.id)
                removed++
            }
        }

        val onPhone = dao.allTracks().mapTo(HashSet()) { it.id }
        val entries = plan.playlists.flatMap { (playlist, ids) ->
            ids.filter { it in onPhone }.mapIndexed { position, id -> PlaylistTrackEntity(playlist.id, position, id) }
        }
        dao.replacePlaylists(plan.playlists.map { it.first }, entries)

        return buildList {
            add("${plan.tracks.size} tracks selected")
            if (downloaded > 0) add("$downloaded downloaded")
            if (removed > 0) add("$removed removed")
            if (failed > 0) add("$failed couldn't be downloaded")
        }.joinToString(", ")
    }

    private suspend fun plan(api: SubsonicClient, selections: List<SelectionEntity>): Plan {
        val plan = Plan()
        suspend fun addAlbum(id: String) {
            val album = api.album(id)
            album.song.forEach { plan.add(it, album.artist) }
        }
        for (selection in selections) {
            when (selection.kind) {
                KIND_FAVORITES -> {
                    val starred = api.starred()
                    starred.song.forEach { plan.add(it) }
                    starred.album.forEach { addAlbum(it.id) }
                    plan.playlists += PlaylistEntity(KIND_FAVORITES, "Favorites", null) to starred.song.map { it.id }
                }
                KIND_PLAYLIST -> {
                    val playlist = try {
                        api.playlist(selection.id)
                    } catch (e: SubsonicException) {
                        if (e.code == 70) continue else throw e
                    }
                    playlist.entry.forEach { plan.add(it) }
                    plan.playlists += PlaylistEntity(playlist.id, playlist.name, playlist.coverArt) to
                        playlist.entry.map { it.id }
                }
                KIND_ALBUM -> try {
                    addAlbum(selection.id)
                } catch (e: SubsonicException) {
                    if (e.code != 70) throw e
                }
                KIND_ARTIST -> try {
                    api.artist(selection.id).album.forEach { addAlbum(it.id) }
                } catch (e: SubsonicException) {
                    if (e.code != 70) throw e
                }
                KIND_LIBRARY -> {
                    var offset = 0
                    while (true) {
                        val page = api.albums(offset, 500)
                        page.forEach { addAlbum(it.id) }
                        if (page.size < 500) break
                        offset += page.size
                    }
                }
            }
        }
        return plan
    }

    private suspend fun save(api: SubsonicClient, planned: Planned): TrackEntity {
        val song = planned.song
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName(song))
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType(song.suffix))
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_MUSIC}/Aurral/${safe(planned.folderArtist)}/${safe(song.album)}/",
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("Android refused to create the file")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("Couldn't open the file for writing")
            out.use { api.download(song.id, it) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return TrackEntity(
            id = song.id,
            title = song.name,
            artist = song.artist,
            albumArtist = planned.folderArtist,
            album = song.album,
            albumId = song.albumId,
            track = song.track,
            disc = song.discNumber,
            duration = song.duration,
            year = song.year,
            coverArt = song.coverArt,
            uri = uri.toString(),
            size = song.size,
            syncedAt = System.currentTimeMillis(),
        )
    }

    private fun exists(uri: Uri): Boolean = runCatching {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.count > 0 } ?: false
    }.getOrDefault(false)

    private fun foreground(text: String, done: Int, total: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("sync", "Music sync", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = NotificationCompat.Builder(applicationContext, "sync")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (total > 0) "Syncing music ($done of $total)" else "Syncing music")
            .setContentText(text)
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val KIND_FAVORITES = "favorites"
        const val KIND_PLAYLIST = "playlist"
        const val KIND_ALBUM = "album"
        const val KIND_ARTIST = "artist"
        const val KIND_LIBRARY = "library"
        private const val NOTIFICATION_ID = 41
        private val lock = Mutex()
    }
}

fun coverFile(context: Context, coverArt: String): File =
    File(context.filesDir, "covers").apply { mkdirs() }.resolve(md5(coverArt) + ".jpg")

private fun safe(name: String) =
    name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.').take(100).ifBlank { "Unknown" }

private fun fileName(song: Song): String {
    val number = when {
        song.track <= 0 -> ""
        song.discNumber > 1 -> "%d-%02d - ".format(song.discNumber, song.track)
        else -> "%02d - ".format(song.track)
    }
    return safe(number + song.name) + "." + song.suffix.lowercase()
}

private fun mimeType(suffix: String) =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(suffix.lowercase())?.takeIf { it.startsWith("audio/") }
        ?: when (suffix.lowercase()) {
            "flac" -> "audio/flac"
            "opus", "ogg" -> "audio/ogg"
            "m4a", "aac", "alac" -> "audio/mp4"
            else -> "audio/mpeg"
        }
