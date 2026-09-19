package com.theveloper.pixelplay.extension.youtube

import com.theveloper.pixelplay.extension.api.ExtensionHostContext
import com.theveloper.pixelplay.extension.api.ExtensionLyrics
import com.theveloper.pixelplay.extension.api.ExtensionSection
import com.theveloper.pixelplay.extension.api.ExtensionSetting
import com.theveloper.pixelplay.extension.api.MusicExtension
import com.theveloper.pixelplay.extension.api.MusicExtensionV2
import okhttp3.OkHttpClient
import java.util.concurrent.Executors
import kotlin.coroutines.suspendCoroutine

/**
 * Entry class for the YouTube extension. Everything MusicExtension does is delegated to the
 * unchanged [YouTubeMusicExtension]; this adds synced lyrics (LRCLIB) and a Discover feed.
 *
 * The feed is search-based, like the Spotify one: each comma-separated query in the
 * "home_queries" setting becomes one shelf, using the same YouTube Music song search as
 * [YouTubeMusicExtension.search]. That avoids depending on NewPipe kiosk APIs that vary by version.
 */
class YouTubeMusicExtensionV2 private constructor(private val base: YouTubeMusicExtension) :
    MusicExtension by base, MusicExtensionV2 {

    constructor() : this(YouTubeMusicExtension())

    override val settings: List<ExtensionSetting> = listOf(
        ExtensionSetting(
            key = KEY_HOME_QUERIES,
            label = "Discover shelves",
            hint = "Comma-separated searches, e.g. $DEFAULT_HOME_QUERIES"
        )
    )

    @Volatile private var host: ExtensionHostContext? = null

    // Overriding a member that `by base` would otherwise forward: we need the host context for
    // settings, and the base still has to see onCreate so NewPipe gets initialised.
    override fun onCreate(context: ExtensionHostContext) {
        host = context
        base.onCreate(context)
    }

    private val lrclib by lazy { LrcLib(OkHttpClient()) }

    private val io = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "youtube-extension-io").apply { isDaemon = true }
    }

    private suspend fun <T> onIo(block: () -> T): T = suspendCoroutine { cont ->
        io.execute { cont.resumeWith(runCatching(block)) }
    }

    override suspend fun getLyrics(
        trackId: String,
        title: String,
        artist: String,
        durationMs: Long?
    ): ExtensionLyrics? = onIo { lrclib.find(title, artist, durationMs) }

    override suspend fun getHome(): List<ExtensionSection> {
        val configured = host?.getPref(KEY_HOME_QUERIES)?.trim()?.takeIf { it.isNotEmpty() }
        val queries = (configured ?: DEFAULT_HOME_QUERIES)
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_SHELVES)

        val sections = mutableListOf<ExtensionSection>()
        var firstError: Throwable? = null

        for (query in queries) {
            runCatching { base.search(query, null) }
                .onFailure { firstError = firstError ?: it }
                .getOrNull()
                ?.tracks
                ?.take(MAX_TRACKS_PER_SHELF)
                ?.takeIf { it.isNotEmpty() }
                ?.let { tracks ->
                    sections += ExtensionSection(
                        title = query.replaceFirstChar { it.uppercase() },
                        tracks = tracks
                    )
                }
        }

        // If every shelf failed, surface the real reason so the tab shows an error instead of "empty".
        if (sections.isEmpty()) firstError?.let { throw it }
        return sections
    }

    private companion object {
        const val KEY_HOME_QUERIES = "home_queries"
        const val DEFAULT_HOME_QUERIES = "trending songs, new music releases, top hits"
        const val MAX_SHELVES = 6
        const val MAX_TRACKS_PER_SHELF = 12
    }
}
