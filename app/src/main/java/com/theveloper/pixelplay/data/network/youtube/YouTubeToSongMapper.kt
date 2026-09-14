package com.theveloper.pixelplay.data.network.youtube

import com.theveloper.pixelplay.data.model.Song
import org.schabi.newpipe.extractor.stream.StreamInfoItem

object YouTubeToSongMapper {

    private const val YOUTUBE_PREFIX = "youtube_"

    /**
     * Converts a NewPipe YouTube search result into a PixelPlayer Song.
     *
     * Returns null when a valid YouTube video ID cannot be extracted.
     */
    fun mapToSong(
        item: StreamInfoItem
    ): Song? {
        val videoId = extractVideoIdFromUrl(item.url)
            ?: return null

        val thumbnailUrl = item.thumbnails
            .maxByOrNull { it.height }
            ?.url

        return Song(
            id = "$YOUTUBE_PREFIX$videoId",
            title = item.name,
            artist = item.uploaderName ?: "YouTube",
            artistId = -1L,
            artists = emptyList(),
            album = "YouTube",
            albumId = -1L,
            albumArtist = null,
            path = "",
            contentUriString = "youtube://$videoId",
            albumArtUriString = thumbnailUrl,
            duration = item.duration * 1000L,
            genre = null,
            lyrics = null,
            isFavorite = false,
            trackNumber = 0,
            discNumber = null,
            year = 0,
            dateAdded = System.currentTimeMillis(),
            dateModified = 0L,
            mimeType = "audio/*",
            bitrate = null,
            sampleRate = null,
            telegramFileId = null,
            telegramChatId = null,
            neteaseId = null,
            gdriveFileId = null,
            qqMusicMid = null,
            navidromeId = null,
            jellyfinId = null
        )
    }

    /**
     * Converts multiple NewPipe search results into Songs.
     * Invalid/non-YouTube results are discarded.
     */
    fun mapToSongs(
        items: List<StreamInfoItem>
    ): List<Song> {
        return items.mapNotNull(::mapToSong)
    }

    /**
     * Extracts the YouTube video ID from a PixelPlayer YouTube song ID.
     *
     * Example:
     * youtube_dQw4w9WgXcQ -> dQw4w9WgXcQ
     */
    fun extractVideoId(
        songId: String
    ): String? {
        return songId
            .takeIf { it.startsWith(YOUTUBE_PREFIX) }
            ?.removePrefix(YOUTUBE_PREFIX)
            ?.takeIf { isValidVideoId(it) }
    }

    /**
     * Returns true when the Song represents a YouTube track.
     */
    fun isYouTubeSong(
        song: Song
    ): Boolean {
        return song.id.startsWith(YOUTUBE_PREFIX) &&
            extractVideoId(song.id) != null
    }

    /**
     * Extracts an 11-character YouTube video ID from common YouTube URLs.
     *
     * Supported:
     * - https://www.youtube.com/watch?v=VIDEO_ID
     * - https://youtube.com/watch?v=VIDEO_ID
     * - https://music.youtube.com/watch?v=VIDEO_ID
     * - https://youtu.be/VIDEO_ID
     * - https://www.youtube.com/shorts/VIDEO_ID
     */
    fun extractVideoIdFromUrl(
        url: String
    ): String? {
        val trimmedUrl = url.trim()

        if (trimmedUrl.isEmpty()) {
            return null
        }

        return runCatching {
            val uri = android.net.Uri.parse(trimmedUrl)

            val host = uri.host?.lowercase() ?: return@runCatching null
            val path = uri.path.orEmpty()

            when {
                host == "youtu.be" -> {
                    path
                        .removePrefix("/")
                        .substringBefore("/")
                        .takeIf(::isValidVideoId)
                }

                host == "youtube.com" ||
                    host == "www.youtube.com" ||
                    host == "m.youtube.com" ||
                    host == "music.youtube.com" -> {

                    val queryVideoId = uri
                        .getQueryParameter("v")
                        ?.takeIf(::isValidVideoId)

                    queryVideoId
                        ?: extractVideoIdFromPath(path)
                }

                else -> null
            }
        }.getOrNull()
    }

    private fun extractVideoIdFromPath(
        path: String
    ): String? {
        val segments = path
            .split("/")
            .filter { it.isNotEmpty() }

        if (segments.size < 2) {
            return null
        }

        return when (segments[0].lowercase()) {
            "shorts",
            "embed",
            "live" -> segments[1].takeIf(::isValidVideoId)

            else -> null
        }
    }

    private fun isValidVideoId(
        videoId: String
    ): Boolean {
        return videoId.matches(
            Regex("[A-Za-z0-9_-]{11}")
        )
    }
}
