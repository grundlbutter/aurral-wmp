package org.aurral.android.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.aurral.android.Account
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest

@Serializable
data class Song(
    val id: String,
    val title: String? = null,
    val album: String = "",
    val artist: String = "",
    val albumId: String? = null,
    val artistId: String? = null,
    val track: Int = 0,
    val discNumber: Int = 1,
    val coverArt: String? = null,
    val duration: Int = 0,
    val size: Long = 0,
    val suffix: String = "mp3",
    val year: Int? = null,
) {
    val name get() = title ?: "Unknown track"
}

@Serializable
data class Album(
    val id: String,
    val name: String = "",
    val artist: String = "",
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int = 0,
    val year: Int? = null,
    val song: List<Song> = emptyList(),
)

@Serializable
data class Artist(
    val id: String,
    val name: String = "",
    val coverArt: String? = null,
    val albumCount: Int = 0,
    val album: List<Album> = emptyList(),
)

@Serializable
data class Playlist(
    val id: String,
    val name: String = "",
    val owner: String? = null,
    val coverArt: String? = null,
    val songCount: Int = 0,
    val duration: Int = 0,
    val entry: List<Song> = emptyList(),
)

@Serializable
private data class ArtistIndex(val artist: List<Artist> = emptyList())

@Serializable
private data class ArtistIndexes(val index: List<ArtistIndex> = emptyList())

@Serializable
private data class PlaylistList(val playlist: List<Playlist> = emptyList())

@Serializable
private data class AlbumList(val album: List<Album> = emptyList())

@Serializable
data class Starred(
    val artist: List<Artist> = emptyList(),
    val album: List<Album> = emptyList(),
    val song: List<Song> = emptyList(),
)

@Serializable
data class SearchResult(
    val artist: List<Artist> = emptyList(),
    val album: List<Album> = emptyList(),
    val song: List<Song> = emptyList(),
)

/** [code] is set when Aurral answered with a Subsonic error, null for transport failures. */
class SubsonicException(message: String, val code: Int? = null) : IOException(message)

val json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
    explicitNulls = false
}

class SubsonicClient(private val account: Account, private val http: OkHttpClient) {
    private val base = account.serverUrl.trimEnd('/')

    fun url(method: String, vararg params: Pair<String, String>): HttpUrl =
        "$base/rest/$method".toHttpUrl().newBuilder().apply {
            if (account.username.isBlank()) {
                // Browser sign-in: OpenSubsonic API key authentication, no username or password.
                addQueryParameter("apiKey", account.apiKey)
            } else if (account.passwordAuth) {
                addQueryParameter("u", account.username)
                addQueryParameter("p", "enc:" + account.password.toByteArray().toHex())
            } else {
                addQueryParameter("u", account.username)
                addQueryParameter("t", md5(account.password + account.salt))
                addQueryParameter("s", account.salt)
            }
            addQueryParameter("v", "1.16.1")
            addQueryParameter("c", "Aurral Android")
            addQueryParameter("f", "json")
            params.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()

    private suspend fun call(method: String, vararg params: Pair<String, String>): JsonObject =
        withContext(Dispatchers.IO) {
            val request = request(url(method, *params))
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (body.trimStart().startsWith("<")) {
                    throw SubsonicException(
                        "Got a web page instead of Aurral. If a sign-in proxy sits in front of " +
                            "Aurral, let /rest/* through without its login.",
                    )
                }
                if (!response.isSuccessful) throw SubsonicException("Aurral answered HTTP ${response.code}")
                val root = runCatching {
                    json.parseToJsonElement(body).jsonObject["subsonic-response"]?.jsonObject
                }.getOrNull() ?: throw SubsonicException("That address doesn't look like an Aurral server")
                if (root["status"]?.jsonPrimitive?.contentOrNull != "ok") {
                    val error = root["error"]?.jsonObject
                    throw SubsonicException(
                        error?.get("message")?.jsonPrimitive?.contentOrNull ?: "Aurral refused the request",
                        error?.get("code")?.jsonPrimitive?.intOrNull ?: 0,
                    )
                }
                root
            }
        }

    private inline fun <reified T> JsonObject.field(key: String, empty: T): T =
        this[key]?.let { json.decodeFromJsonElement<T>(it) } ?: empty

    suspend fun ping() {
        call("ping")
    }

    suspend fun artists(): List<Artist> =
        call("getArtists").field("artists", ArtistIndexes()).index.flatMap { it.artist }

    suspend fun artist(id: String): Artist =
        json.decodeFromJsonElement(call("getArtist", "id" to id)["artist"] ?: throw notFound())

    suspend fun album(id: String): Album =
        json.decodeFromJsonElement(call("getAlbum", "id" to id)["album"] ?: throw notFound())

    suspend fun albums(offset: Int, size: Int): List<Album> =
        call(
            "getAlbumList2",
            "type" to "alphabeticalByName",
            "size" to size.toString(),
            "offset" to offset.toString(),
        ).field("albumList2", AlbumList()).album

    suspend fun playlists(): List<Playlist> = call("getPlaylists").field("playlists", PlaylistList()).playlist

    suspend fun playlist(id: String): Playlist =
        json.decodeFromJsonElement(call("getPlaylist", "id" to id)["playlist"] ?: throw notFound())

    suspend fun starred(): Starred = call("getStarred2").field("starred2", Starred())

    suspend fun search(query: String): SearchResult =
        call(
            "search3",
            "query" to query,
            "artistCount" to "20",
            "albumCount" to "40",
            "songCount" to "60",
        ).field("searchResult3", SearchResult())

    suspend fun scrobble(id: String, playedAt: Long) {
        call("scrobble", "id" to id, "time" to playedAt.toString(), "submission" to "true")
    }

    fun streamUrl(id: String) = url("stream", "id" to id).toString()

    fun coverUrl(id: String, size: Int = 300) = url("getCoverArt", "id" to id, "size" to size.toString()).toString()

    /** Writes the original file for [id] to [out]. */
    suspend fun download(id: String, out: OutputStream) = fetch(url("download", "id" to id), out)

    suspend fun downloadCover(id: String, target: File) {
        val partial = File(target.path + ".part")
        partial.outputStream().use { fetch(url("getCoverArt", "id" to id, "size" to "600"), it) }
        if (!partial.renameTo(target)) partial.delete()
    }

    private suspend fun fetch(url: HttpUrl, out: OutputStream) = withContext(Dispatchers.IO) {
        http.newCall(request(url)).execute().use { response ->
            val type = response.header("Content-Type").orEmpty()
            if (!response.isSuccessful) throw SubsonicException("Download failed: HTTP ${response.code}", response.code)
            if (type.startsWith("text/") || type.contains("json")) {
                throw SubsonicException("Aurral sent an error instead of the file", 0)
            }
            response.body!!.byteStream().use { it.copyTo(out, 64 * 1024) }
        }
    }

    private fun request(url: HttpUrl) = Request.Builder().url(url).apply {
        if (account.apiKey.isNotBlank()) header("X-Api-Key", account.apiKey)
    }.build()

    private fun notFound() = SubsonicException("Not found on Aurral", 70)
}

private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

fun md5(value: String): String = MessageDigest.getInstance("MD5").digest(value.toByteArray()).toHex()
