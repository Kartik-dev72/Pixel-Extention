package com.theveloper.pixelplay.data.network.youtube

import com.theveloper.pixelplay.data.model.Song
import org.schabi.newpipe.extractor.stream.StreamInfoItem

object YouTubeToSongMapper {

    private val musicTitleSignals = listOf(
        "song",
        "official audio",
        "official video",
        "music video",
        "lyric",
        "lyrics",
        "audio",
        "remix",
        "cover",
        "acoustic",
        "live performance",
        "theme",
        "ost",
        "soundtrack",
        "jukebox",
        "album",
        "single"
    )

    private val musicUploaderSignals = listOf(
        "vevo",
        "records",
        "music",
        "t-series",
        "sony music",
        "zee music",
        "saregama",
        "tips",
        "label"
    )

    private val nonMusicSignals = listOf(
        "trailer",
        "teaser",
        "episode",
        "full movie",
        "movie scene",
        "scene",
        "review",
        "reaction",
        "interview",
        "news",
        "podcast",
        "vlog",
        "comedy",
        "stand up",
        "gaming",
        "gameplay",
        "tutorial",
        "explained",
        "shorts",
        "#shorts"
    )

    fun mapToSong(videoItem: StreamInfoItem): Song {
        val artist = videoItem.uploaderName ?: "Unknown Artist"

        val videoId =
            extractVideoIdFromUrl(videoItem.url) ?: "unknown"

        val songId = "youtube_$videoId"

        val durationMs = videoItem.duration * 1000L

        val thumbnailUrl = videoItem.thumbnails
            .maxByOrNull { it.height }
            ?.url ?: ""

        return Song(
            id = songId,
            title = videoItem.name,
            artist = artist,
            artistId = -1L,
            artists = emptyList(),
            album = "YouTube",
            albumId = -1L,
            albumArtist = null,
            path = "",
            contentUriString = "",
            albumArtUriString = thumbnailUrl,
            duration = durationMs,
            genre = null,
            lyrics = null,
            isFavorite = false,
            trackNumber = 0,
            year = 0,
            dateAdded = System.currentTimeMillis(),
            dateModified = System.currentTimeMillis(),
            mimeType = null,
            bitrate = null,
            sampleRate = null
        )
    }

    fun mapToSongs(
        videoItems: List<StreamInfoItem>
    ): List<Song> {
        return videoItems
            .filter(::isLikelyMusicContent)
            .mapNotNull { item ->
                try {
                    mapToSong(item)
                } catch (_: Exception) {
                    null
                }
            }
    }

    fun isLikelyMusicContent(
        videoItem: StreamInfoItem
    ): Boolean {
        val title = videoItem.name.lowercase()
        val uploader =
            (videoItem.uploaderName ?: "").lowercase()

        val durationSeconds = videoItem.duration

        if (
            videoItem.isShortFormContent ||
            durationSeconds in 1 until 45
        ) {
            return false
        }

        val hasMusicSignal =
            musicTitleSignals.any { it in title } ||
                musicUploaderSignals.any { it in uploader }

        val hasNonMusicSignal =
            nonMusicSignals.any { it in title }

        if (hasNonMusicSignal && !hasMusicSignal) {
            return false
        }

        if (
            durationSeconds > 12 * 60 &&
            !hasMusicSignal
        ) {
            return false
        }

        return hasMusicSignal ||
            looksLikeArtistTitle(title) ||
            durationSeconds in 45..(12 * 60)
    }

    fun musicSearchQuery(query: String): String {
        val normalizedQuery = query.trim()
        val lowerQuery = normalizedQuery.lowercase()

        return if (
            musicTitleSignals.any { it in lowerQuery }
        ) {
            normalizedQuery
        } else {
            "$normalizedQuery song"
        }
    }

    private fun looksLikeArtistTitle(
        title: String
    ): Boolean {
        val parts = title.split(" - ", limit = 2)

        return parts.size == 2 &&
            parts.all {
                it.trim().length >= 2
            }
    }

    fun extractVideoId(songId: String): String? {
        return if (songId.startsWith("youtube_")) {
            songId.removePrefix("youtube_")
        } else {
            null
        }
    }

    private fun extractVideoIdFromUrl(
        url: String
    ): String? {
        return try {
            when {
                url.contains("youtube.com/watch?v=") -> {
                    url.substringAfter("v=")
                        .substringBefore("&")
                }

                url.contains("youtu.be/") -> {
                    url.substringAfter("youtu.be/")
                        .substringBefore("?")
                }

                url.startsWith("/watch?v=") -> {
                    url.substringAfter("v=")
                        .substringBefore("&")
                }

                url.matches(
                    Regex("[a-zA-Z0-9_-]{11}")
                ) -> {
                    url
                }

                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun isYouTubeSong(song: Song): Boolean {
        return song.id.startsWith("youtube_")
    }
}
