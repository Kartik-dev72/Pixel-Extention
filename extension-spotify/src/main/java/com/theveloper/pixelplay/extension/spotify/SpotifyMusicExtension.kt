package com.theveloper.pixelplay.extension.spotify

import com.theveloper.pixelplay.extension.api.ExtensionCapability
import com.theveloper.pixelplay.extension.api.ExtensionCollection
import com.theveloper.pixelplay.extension.api.ExtensionHostContext
import com.theveloper.pixelplay.extension.api.ExtensionLyrics
import com.theveloper.pixelplay.extension.api.ExtensionMetadata
import com.theveloper.pixelplay.extension.api.ExtensionPage
import com.theveloper.pixelplay.extension.api.ExtensionSearchResult
import com.theveloper.pixelplay.extension.api.ExtensionSection
import com.theveloper.pixelplay.extension.api.ExtensionSetting
import com.theveloper.pixelplay.extension.api.ExtensionTrack
import com.theveloper.pixelplay.extension.api.MusicExtension
import com.theveloper.pixelplay.extension.api.MusicExtensionV2
import java.util.Calendar
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.coroutines.suspendCoroutine

/**
 * Spotify catalog search + discovery, with playback matched to YouTube and synced lyrics from LRCLIB.
 *
 * Spotify's audio is DRM-protected and its API gives new apps no previews, so Spotify supplies only
 * metadata. Track ids handed to the host are plain Spotify track ids; collection ids are Spotify album ids.
 * Credentials come from the extension's settings screen (host prefs), not from the build.
 */
class SpotifyMusicExtension : MusicExtension, MusicExtensionV2 {

    // Positional: id, name, version, author, capabilities, description. `id` must match the manifest.
    override val metadata: ExtensionMetadata = ExtensionMetadata(
        "com.theveloper.pixelplay.extension.spotify",
        "Spotify",
        "1.1.0",
        "Community",
        setOf(ExtensionCapability.SEARCH, ExtensionCapability.STREAM, ExtensionCapability.RELATED),
        "Search and discover on Spotify. Audio is matched and streamed from YouTube; lyrics from LRCLIB."
    )

    override val settings: List<ExtensionSetting> = listOf(
        // Every parameter is passed explicitly: Kotlin's synthetic default-args constructors get
        // stripped by R8 in the host app, which makes this class fail to load.
        ExtensionSetting("client_id", "Spotify Client ID", "From developer.spotify.com/dashboard", false),
        ExtensionSetting("client_secret", "Spotify Client Secret", null, true),
        ExtensionSetting("genres", "Discover genres", "Comma-separated, e.g. $DEFAULT_GENRES", false)
    )

    @Volatile private var host: ExtensionHostContext? = null

    private val downloader = SimpleOkHttpDownloader()
    private val resolver = YouTubeResolver(downloader)
    private val lrclib = LrcLib(downloader.client)

    @Volatile private var apiRef: SpotifyApi? = null

    private val seen: MutableMap<String, SpotifyTrack> = Collections.synchronizedMap(
        object : LinkedHashMap<String, SpotifyTrack>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SpotifyTrack>?) = size > 500
        }
    )

    // Blocking work runs on our own threads so a caller on the main thread can't hit NetworkOnMainThread.
    // Only kotlin-stdlib coroutine primitives, so no dependency on the host's kotlinx.
    private val io = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "spotify-extension-io").apply { isDaemon = true }
    }

    private suspend fun <T> onIo(block: () -> T): T = suspendCoroutine { cont ->
        io.execute { cont.resumeWith(runCatching(block)) }
    }

    override fun onCreate(context: ExtensionHostContext) {
        host = context
    }

    private suspend fun pref(key: String): String? = host?.getPref(key)?.trim()?.takeIf { it.isNotEmpty() }

    /** Rebuilt automatically when the user changes credentials in settings. */
    private suspend fun api(): SpotifyApi {
        val id = pref("client_id").orEmpty()
        val secret = pref("client_secret").orEmpty()
        synchronized(this) {
            apiRef?.takeIf { it.usesCredentials(id, secret) }?.let { return it }
            return SpotifyApi(id, secret, downloader.client).also { apiRef = it }
        }
    }

    private suspend fun configuredApi(): SpotifyApi = api().also {
        check(it.isConfigured) { "Add your Spotify Client ID and Secret in this extension's settings" }
    }

    override suspend fun isReady(): Boolean = api().isConfigured

    // ---- search / playback ---------------------------------------------------------------

    override suspend fun search(query: String, page: ExtensionPage?): ExtensionSearchResult {
        val api = configuredApi()
        val tracks = onIo { api.search(query) }
        tracks.forEach { seen[it.id] = it }
        // Next-page parameter left at its default: Spotify search is capped at 10 per request.
        return ExtensionSearchResult(tracks.map { it.toExtensionTrack() }, null)
    }

    override suspend fun resolveStreamUrl(trackId: String): String {
        val api = configuredApi()
        return onIo {
            val track = seen[trackId]
                ?: api.track(trackId)?.also { seen[it.id] = it }
                ?: throw IllegalStateException("Spotify track $trackId not found")
            resolver.resolveAudioUrl(track)
        }
    }

    /** "More from this artist": Spotify's related/recommendation endpoints are gone, so search by artist. */
    override suspend fun getRelated(trackId: String): List<ExtensionTrack> {
        val api = configuredApi()
        return onIo {
            val track = seen[trackId] ?: api.track(trackId)?.also { seen[it.id] = it } ?: return@onIo emptyList()
            val artist = track.artists.firstOrNull() ?: return@onIo emptyList()
            api.search("artist:\"$artist\"")
                .filter { it.id != trackId }
                .onEach { seen[it.id] = it }
                .map { it.toExtensionTrack() }
        }
    }

    // ---- lyrics ---------------------------------------------------------------------------

    override suspend fun getLyrics(
        trackId: String,
        title: String,
        artist: String,
        durationMs: Long?
    ): ExtensionLyrics? = onIo { lrclib.find(title, artist, durationMs) }

    // ---- discovery + collections -----------------------------------------------------------

    /**
     * Search-based shelves (the browse/new-releases/featured endpoints no longer exist):
     * "New releases" via `tag:new`, then one shelf per genre from settings for the last two years.
     */
    override suspend fun getHome(): List<ExtensionSection> {
        val api = configuredApi()
        val genres = (pref("genres") ?: DEFAULT_GENRES)
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(6)
        val year = Calendar.getInstance().get(Calendar.YEAR)

        return onIo {
            val sections = mutableListOf<ExtensionSection>()
            var firstError: Throwable? = null

            runCatching { api.searchAlbums("tag:new") }
                .onFailure { firstError = firstError ?: it }
                .getOrNull()?.takeIf { it.isNotEmpty() }?.let { albums ->
                    sections += ExtensionSection("New releases", emptyList(), albums.map { it.toCollection() })
                }

            for (genre in genres) {
                runCatching { api.search("genre:\"$genre\" year:${year - 1}-$year") }
                    .onFailure { firstError = firstError ?: it }
                    .getOrNull()?.takeIf { it.isNotEmpty() }?.let { tracks ->
                        tracks.forEach { seen[it.id] = it }
                        sections += ExtensionSection(
                            genre.replaceFirstChar { it.uppercase() },
                            tracks.map { it.toExtensionTrack() },
                            emptyList()
                        )
                    }
            }
            if (sections.isEmpty()) firstError?.let { throw it }
            sections
        }
    }

    /** Expands an album id (from [getHome]) into its tracks, in album order. Feeds "Play album" / "Add to queue". */
    override suspend fun getCollectionTracks(collectionId: String): List<ExtensionTrack> {
        val api = configuredApi()
        return onIo {
            val (_, tracks) = api.album(collectionId) ?: return@onIo emptyList()
            tracks.onEach { seen[it.id] = it }.map { it.toExtensionTrack() }
        }
    }

    // ---- mapping ------------------------------------------------------------------------------

    private fun SpotifyTrack.toExtensionTrack() = ExtensionTrack(
        id,
        title,
        artists.joinToString(", ").ifBlank { "Unknown" },
        durationMs.takeIf { it > 0 },
        artworkUrl.orEmpty(),
        true
    )

    private fun SpotifyAlbum.toCollection() = ExtensionCollection(
        id = id,
        title = name,
        subtitle = listOfNotNull(artists.firstOrNull(), year).joinToString(" \u00B7 ").ifBlank { null },
        artworkUrl = artworkUrl
    )

    private companion object {
        const val DEFAULT_GENRES = "pop, hip-hop, rock, electronic, indie"
    }
}
