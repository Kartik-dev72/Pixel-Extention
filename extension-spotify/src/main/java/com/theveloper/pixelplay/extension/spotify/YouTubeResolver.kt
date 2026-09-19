package com.theveloper.pixelplay.extension.spotify

import android.util.Log
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.util.Collections
import kotlin.math.abs

/**
 * Turns a Spotify track into a playable YouTube audio URL: search YouTube Music for
 * "title artist", score the candidates, take the best one, then pull its highest-bitrate audio stream.
 */
internal class YouTubeResolver(private val downloader: Downloader) {

    @Volatile private var initialized = false

    // Spotify track id -> matched YouTube watch URL (avoids re-searching on every play).
    private val matches: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 500
        }
    )

    @Synchronized
    private fun ensureInit() {
        if (!initialized) {
            NewPipe.init(downloader)
            initialized = true
        }
    }

    fun resolveAudioUrl(track: SpotifyTrack): String {
        ensureInit()
        val cached = matches[track.id]
        val watchUrl = cached ?: findBestMatch(track).also { matches[track.id] = it }
        return try {
            audioUrlFor(watchUrl)
        } catch (e: Exception) {
            if (cached != null) matches.remove(track.id) // stale match (video removed?); re-match next time
            throw e
        }
    }

    private fun findBestMatch(track: SpotifyTrack): String {
        val artist = track.artists.firstOrNull().orEmpty()
        val query = "${track.title} $artist".trim()

        var scored = search(query, "music_songs").map { it to score(it, track) }
        if ((scored.maxOfOrNull { it.second } ?: Double.NEGATIVE_INFINITY) < GOOD_ENOUGH) {
            scored = scored + search(query, "music_videos").map { it to score(it, track) }
        }
        val best = scored.maxByOrNull { it.second }
        if (best == null || best.second < MIN_SCORE) {
            throw IllegalStateException("No confident YouTube match for \"$artist - ${track.title}\"")
        }
        return best.first.url
    }

    private fun search(query: String, filter: String): List<StreamInfoItem> = try {
        val extractor = ServiceList.YouTube.getSearchExtractor(query, listOf(filter), "")
        extractor.fetchPage()
        extractor.initialPage.items
            .filterIsInstance<StreamInfoItem>()
            .filter { it.streamType == StreamType.VIDEO_STREAM || it.streamType == StreamType.AUDIO_STREAM }
    } catch (e: Exception) {
        Log.w(TAG, "YouTube search ($filter) failed for \"$query\"", e)
        emptyList()
    }

    private fun score(c: StreamInfoItem, t: SpotifyTrack): Double {
        var s = 0.0

        val wantSec = t.durationMs / 1000
        val gotSec = c.duration // seconds; <= 0 when unknown
        if (wantSec > 0 && gotSec > 0) {
            s += when (abs(gotSec - wantSec)) {
                in 0L..2L -> 4.0
                in 3L..5L -> 3.0
                in 6L..10L -> 1.5
                in 11L..20L -> 0.0
                else -> -3.0
            }
        }

        val rawTitle = (c.name ?: "").lowercase()
        val wantRaw = t.title.lowercase()
        val title = normalize(rawTitle)
        val wantTitle = normalize(wantRaw)
        s += if (wantTitle.isNotEmpty() && title.contains(wantTitle)) 3.0 else 3.0 * tokenOverlap(wantTitle, title)

        val uploader = normalize(c.uploaderName ?: "")
        if (t.artists.any { a -> normalize(a).let { it.isNotEmpty() && uploader.contains(it) } }) s += 2.0

        // Penalise variants the Spotify title didn't ask for.
        for (word in UNWANTED_VARIANTS) {
            if (hasWord(rawTitle, word) && !hasWord(wantRaw, word)) s -= 2.5
        }
        return s
    }

    private fun audioUrlFor(watchUrl: String): String {
        val extractor = ServiceList.YouTube.getStreamExtractor(watchUrl)
        extractor.fetchPage()
        return extractor.audioStreams
            .filter { !it.content.isNullOrBlank() }
            .maxByOrNull { it.averageBitrate }
            ?.content
            ?: throw IllegalStateException("No playable audio stream found for $watchUrl")
    }

    private fun normalize(s: String): String =
        s.replace(Regex("[\\(\\[].*?[\\)\\]]"), " ")      // drop "(feat. X)", "[Official Audio]"
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")     // keep letters/digits of any script
            .trim()

    private fun tokenOverlap(want: String, got: String): Double {
        val wantTokens = want.split(' ').filter { it.isNotEmpty() }
        if (wantTokens.isEmpty()) return 0.0
        val gotTokens = got.split(' ').toSet()
        return wantTokens.count { it in gotTokens }.toDouble() / wantTokens.size
    }

    private fun hasWord(text: String, word: String): Boolean =
        Regex("\\b${Regex.escape(word)}\\b").containsMatchIn(text)

    private companion object {
        const val TAG = "SpotifyExtension"
        const val GOOD_ENOUGH = 6.5
        const val MIN_SCORE = 2.0
        val UNWANTED_VARIANTS = listOf(
            "live", "cover", "karaoke", "remix", "instrumental", "reaction",
            "slowed", "sped up", "8d", "nightcore", "reverb"
        )
    }
}
