package com.theveloper.pixelplay.data.network.youtube

import com.theveloper.pixelplay.data.model.Song
import org.schabi.newpipe.extractor.stream.StreamInfoItem

object YouTubeToSongMapper {

    fun mapToSong(
        videoItem: StreamInfoItem
    ): Song {

        val artist =
            videoItem.uploaderName
                ?: "Unknown Artist"

        val videoId =
            extractVideoIdFromUrl(
                videoItem.url
            ) ?: "unknown"

        val songId =
            "youtube_$videoId"

        val durationMs =
            videoItem.duration * 1000L

        val thumbnailUrl =
            videoItem.thumbnails
                .maxByOrNull { it.height }
                ?.url
                ?: ""

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
            discNumber = null,
            year = 0,
            dateAdded = System.currentTimeMillis(),
            dateModified = 0,
            mimeType = null,
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

    fun mapToSongs(
        videoItems: List<StreamInfoItem>
    ): List<Song> {
        return videoItems.mapNotNull { item ->
            runCatching {
                mapToSong(item)
            }.getOrNull()
        }
    }

    fun extractVideoId(
        songId: String
    ): String? {
        return if (
            songId.startsWith("youtube_")
        ) {
            songId.removePrefix(
                "youtube_"
            )
        } else {
            null
        }
    }

    fun isYouTubeSong(
        song: Song
    ): Boolean {
        return song.id.startsWith(
            "youtube_"
        )
    }

    private fun extractVideoIdFromUrl(
        url: String
    ): String? {

        return try {

            when {

                url.contains(
                    "youtube.com/watch?v="
                ) -> {
                    url.substringAfter(
                        "v="
                    ).substringBefore(
                        "&"
                    )
                }

                url.contains(
                    "youtu.be/"
                ) -> {
                    url.substringAfter(
                        "youtu.be/"
                    ).substringBefore(
                        "?"
                    )
                }

                url.startsWith(
                    "/watch?v="
                ) -> {
                    url.substringAfter(
                        "v="
                    ).substringBefore(
                        "&"
                    )
                }

                url.matches(
                    Regex(
                        "[a-zA-Z0-9_-]{11}"
                    )
                ) -> {
                    url
                }

                else -> {
                    null
                }
            }

        } catch (
            _: Exception
        ) {
            null
        }
    }
}
