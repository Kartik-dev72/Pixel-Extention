package com.theveloper.pixelplay.data.extension

import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.extension.api.ExtensionTrack

/**
 * Turns an [ExtensionTrack] into a host [Song] that the existing playback pipeline can queue.
 *
 * Every extension track gets the id `ext_<extensionId>::<trackId>`; playback resolves it through
 * [ExtensionRepository.resolveStreamUrl] in `PlaybackDispatchStateHolder`.
 */
object ExtensionSongMapper {

    private const val ID_PREFIX = "ext_"
    private const val SEPARATOR = "::"

    data class Ref(val extensionId: String, val trackId: String)

    fun toSong(extensionId: String, extensionName: String, track: ExtensionTrack): Song {
        val id = "$ID_PREFIX$extensionId$SEPARATOR${track.id}"
        val now = System.currentTimeMillis()
        return Song(
            id = id,
            title = track.title,
            artist = track.artist,
            artistId = -1L,
            artists = emptyList(),
            album = extensionName,
            albumId = -1L,
            albumArtist = null,
            path = "",
            contentUriString = "",
            albumArtUriString = track.thumbnailUrl,
            duration = track.durationMs ?: 0L,
            genre = null,
            lyrics = null,
            isFavorite = false,
            trackNumber = 0,
            year = 0,
            dateAdded = now,
            dateModified = now,
            mimeType = null,
            bitrate = null,
            sampleRate = null
        )
    }

    /** Non-null only for `ext_...` ids. */
    fun parse(songId: String): Ref? {
        if (!songId.startsWith(ID_PREFIX)) return null
        val body = songId.removePrefix(ID_PREFIX)
        val extensionId = body.substringBefore(SEPARATOR, missingDelimiterValue = "")
        val trackId = body.substringAfter(SEPARATOR, missingDelimiterValue = "")
        if (extensionId.isBlank() || trackId.isBlank()) return null
        return Ref(extensionId, trackId)
    }
}
