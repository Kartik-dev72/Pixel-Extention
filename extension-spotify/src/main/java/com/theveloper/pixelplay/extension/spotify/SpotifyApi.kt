package com.theveloper.pixelplay.extension.spotify

import android.util.Base64
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

internal data class SpotifyTrack(
    val id: String,
    val title: String,
    val artists: List<String>,
    val durationMs: Long,
    val artworkUrl: String?
)

internal data class SpotifyAlbum(
    val id: String,
    val name: String,
    val artists: List<String>,
    val year: String?,
    val artworkUrl: String?
)

/**
 * Minimal Spotify Web API client (client-credentials auth, no user login).
 * Only uses endpoints that survived the February 2026 revision: /search (max 10 results),
 * /tracks/{id}, /albums/{id}. Recommendations, new-releases, top-tracks and related-artists are gone.
 */
internal class SpotifyApi(
    clientId: String,
    clientSecret: String,
    private val client: OkHttpClient
) {
    private val clientId = clientId.trim()
    private val clientSecret = clientSecret.trim()

    val isConfigured: Boolean get() = clientId.isNotEmpty() && clientSecret.isNotEmpty()
    fun usesCredentials(id: String, secret: String) = clientId == id.trim() && clientSecret == secret.trim()

    @Volatile private var token: String? = null
    @Volatile private var tokenExpiresAtMs = 0L

    /** [query] may use Spotify filters, e.g. `genre:"indie" year:2025-2026` or `artist:"Radiohead"`. */
    fun search(query: String, limit: Int = 10): List<SpotifyTrack> {
        val items = getJson(searchUrl(query, "track", limit)).optJSONObject("tracks")?.optJSONArray("items")
            ?: return emptyList()
        return (0 until items.length()).mapNotNull { parseTrack(items.optJSONObject(it)) }
    }

    fun searchAlbums(query: String, limit: Int = 10): List<SpotifyAlbum> {
        val items = getJson(searchUrl(query, "album", limit)).optJSONObject("albums")?.optJSONArray("items")
            ?: return emptyList()
        return (0 until items.length()).mapNotNull { parseAlbum(items.optJSONObject(it)) }
    }

    fun track(id: String): SpotifyTrack? = parseTrack(getJson(apiUrl("v1/tracks/$id")))

    /** One call gives the album plus its track list (an album's list is still called `tracks`). */
    fun album(id: String): Pair<SpotifyAlbum, List<SpotifyTrack>>? {
        val json = getJson(apiUrl("v1/albums/$id"))
        val album = parseAlbum(json) ?: return null
        val items = json.optJSONObject("tracks")?.optJSONArray("items")
        val tracks = (0 until (items?.length() ?: 0)).mapNotNull {
            parseTrack(items?.optJSONObject(it), fallbackArtwork = album.artworkUrl)
        }
        return album to tracks
    }

    private fun apiUrl(path: String): HttpUrl =
        HttpUrl.Builder().scheme("https").host("api.spotify.com").addPathSegments(path).build()

    private fun searchUrl(query: String, type: String, limit: Int): HttpUrl =
        HttpUrl.Builder().scheme("https").host("api.spotify.com").addPathSegments("v1/search")
            .addQueryParameter("q", query)
            .addQueryParameter("type", type)
            .addQueryParameter("limit", limit.coerceIn(1, 10).toString())
            .build()

    private fun images(o: JSONObject?): String? {
        val arr = o?.optJSONArray("images") ?: return null
        return if (arr.length() > 0) arr.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() } else null
    }

    private fun names(o: JSONObject): List<String> {
        val arr = o.optJSONArray("artists") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") }.filter { it.isNotBlank() }
    }

    private fun parseTrack(o: JSONObject?, fallbackArtwork: String? = null): SpotifyTrack? {
        if (o == null || o.isNull("id")) return null
        val id = o.optString("id", "")
        if (id.isBlank()) return null
        return SpotifyTrack(
            id = id,
            title = o.optString("name", "Unknown"),
            artists = names(o),
            durationMs = o.optLong("duration_ms", 0L),
            artworkUrl = images(o.optJSONObject("album")) ?: fallbackArtwork
        )
    }

    private fun parseAlbum(o: JSONObject?): SpotifyAlbum? {
        if (o == null || o.isNull("id")) return null
        val id = o.optString("id", "")
        if (id.isBlank()) return null
        return SpotifyAlbum(
            id = id,
            name = o.optString("name", "Unknown"),
            artists = names(o),
            year = o.optString("release_date", "").take(4).takeIf { it.length == 4 },
            artworkUrl = images(o)
        )
    }

    private fun getJson(url: HttpUrl, retryAuth: Boolean = true): JSONObject {
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer ${accessToken()}")
            .build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            when {
                resp.isSuccessful -> return JSONObject(text)
                resp.code == 401 && retryAuth -> {
                    token = null
                    return getJson(url, retryAuth = false)
                }
                resp.code == 429 -> throw IOException(
                    "Spotify rate limit hit; retry in ${resp.header("Retry-After") ?: "a few"} seconds"
                )
                resp.code == 403 -> throw IOException(
                    "Spotify refused the request (403). Development Mode apps are restricted; check your app in the Spotify dashboard"
                )
                else -> throw IOException("Spotify API error (HTTP ${resp.code})")
            }
        }
    }

    @Synchronized
    private fun accessToken(): String {
        val now = System.currentTimeMillis()
        token?.let { if (now < tokenExpiresAtMs - 30_000) return it }

        val basic = Base64.encodeToString("$clientId:$clientSecret".toByteArray(), Base64.NO_WRAP)
        val request = Request.Builder()
            .url("https://accounts.spotify.com/api/token")
            .header("Authorization", "Basic $basic")
            .post(FormBody.Builder().add("grant_type", "client_credentials").build())
            .build()

        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("Spotify auth failed (HTTP ${resp.code}); check your Client ID and Secret in the extension's settings")
            }
            val json = JSONObject(text)
            val newToken = json.getString("access_token")
            token = newToken
            tokenExpiresAtMs = now + json.optLong("expires_in", 3600L) * 1000L
            return newToken
        }
    }
}
