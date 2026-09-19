package com.theveloper.pixelplay.extension.api

/** One timed lyric line. An empty [text] marks an instrumental gap. */
data class ExtensionLyricLine(val timeMs: Long, val text: String)

data class ExtensionLyrics(
    /** Time-synced lines, sorted by [ExtensionLyricLine.timeMs]. Empty if only plain lyrics exist. */
    val lines: List<ExtensionLyricLine> = emptyList(),
    val plain: String? = null,
    val source: String? = null
) {
    val isSynced: Boolean get() = lines.isNotEmpty()
}

/** An album/playlist-like group. Expand it with MusicExtension.getCollectionTracks(id). */
data class ExtensionCollection(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val artworkUrl: String? = null
)

/** One shelf on an extension's discover/home screen. */
data class ExtensionSection(
    val title: String,
    val tracks: List<ExtensionTrack> = emptyList(),
    val collections: List<ExtensionCollection> = emptyList()
)

/** A text field the host renders on the extension's settings screen. */
data class ExtensionSetting(
    val key: String,
    val label: String,
    val hint: String? = null,
    val isSecret: Boolean = false
)

/**
 * Optional add-on to [MusicExtension]. An extension implements both interfaces to offer settings,
 * lyrics, discover shelves and album/playlist expansion. Extensions built before this existed simply
 * don't implement it, and the host treats them as "no such features".
 */
interface MusicExtensionV2 {
    /** Fields the host renders on the extension's settings screen; values are read back with getPref(key). */
    val settings: List<ExtensionSetting> get() = emptyList()

    suspend fun getLyrics(trackId: String, title: String, artist: String, durationMs: Long?): ExtensionLyrics? = null

    suspend fun getHome(): List<ExtensionSection> = emptyList()

    suspend fun getCollectionTracks(collectionId: String): List<ExtensionTrack> = emptyList()
}
