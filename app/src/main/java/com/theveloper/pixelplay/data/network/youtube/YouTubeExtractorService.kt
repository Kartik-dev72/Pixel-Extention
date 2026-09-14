package com.theveloper.pixelplay.data.network.youtube

import com.theveloper.pixelplay.data.network.piped.PipedStreamService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamExtractor
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YouTubeExtractorService @Inject constructor(
    private val pipedStreamService: PipedStreamService
) {

    private val youtubeService = ServiceList.YouTube

    private var lastReinitTime = 0L

    private val REINIT_INTERVAL_MS = 5 * 60 * 1000L

    private val ytDlpService = YtDlpService()

    /**
     * Re-initialize NewPipe periodically.
     */
    private suspend fun reinitializeNewPipeIfNeeded() =
        withContext(Dispatchers.IO) {

            val currentTime = System.currentTimeMillis()

            if (currentTime - lastReinitTime > REINIT_INTERVAL_MS) {
                try {
                    Timber.d(
                        "YouTubeExtractor: Re-initializing NewPipe..."
                    )

                    val downloader =
                        OkHttpDownloader.getInstance()

                    NewPipe.init(downloader)

                    lastReinitTime = currentTime

                    Timber.d(
                        "YouTubeExtractor: NewPipe re-initialized successfully"
                    )
                } catch (e: Exception) {
                    Timber.e(
                        e,
                        "YouTubeExtractor: Failed to re-initialize NewPipe"
                    )
                }
            }
        }

    /**
     * Search for songs on YouTube.
     */
    suspend fun searchSongs(
        query: String
    ): Result<List<StreamInfoItem>> =
        withContext(Dispatchers.IO) {

            try {
                val musicQuery =
                    YouTubeToSongMapper.musicSearchQuery(query)

                val items =
                    searchYouTubeMusic(musicQuery)
                        .ifEmpty {
                            Timber.d(
                                "YouTube Music returned no songs for query: $musicQuery, " +
                                    "falling back to YouTube"
                            )

                            searchYouTubeVideos(musicQuery)
                        }

                Timber.d(
                    "YouTubeExtractor: Found ${items.size} songs for query: $query"
                )

                Result.success(items)
            } catch (e: Exception) {
                Timber.e(
                    e,
                    "YouTubeExtractor: Error searching songs: $query"
                )

                Result.failure(e)
            }
        }

    private fun searchYouTubeMusic(
        query: String
    ): List<StreamInfoItem> {

        val filters = listOf(
            YoutubeSearchQueryHandlerFactory.MUSIC_SONGS,
            YoutubeSearchQueryHandlerFactory.MUSIC_VIDEOS
        )

        return filters.firstNotNullOfOrNull { filter ->

            runCatching {

                val searchExtractor =
                    youtubeService.getSearchExtractor(
                        query,
                        listOf(filter),
                        ""
                    )

                searchExtractor.fetchPage()

                searchExtractor.initialPage.items
                    .filterIsInstance<StreamInfoItem>()
                    .filter(::isPlayableStream)
                    .filter(
                        YouTubeToSongMapper::isLikelyMusicContent
                    )

            }.onFailure { error ->

                Timber.w(
                    error,
                    "YouTubeExtractor: YouTube Music search failed for filter: $filter"
                )

            }.getOrNull()
                ?.takeIf { it.isNotEmpty() }

        } ?: emptyList()
    }

    private fun searchYouTubeVideos(
        query: String
    ): List<StreamInfoItem> {

        val searchExtractor =
            youtubeService.getSearchExtractor(query)

        searchExtractor.fetchPage()

        return searchExtractor.initialPage.items
            .filterIsInstance<StreamInfoItem>()
            .filter(::isPlayableStream)
            .filter(
                YouTubeToSongMapper::isLikelyMusicContent
            )
    }

    private fun isPlayableStream(
        item: StreamInfoItem
    ): Boolean {

        return item.streamType == StreamType.VIDEO_STREAM ||
            item.streamType == StreamType.AUDIO_STREAM
    }

    /**
     * Get next search page.
     */
    suspend fun getNextPage(
        searchExtractor: SearchExtractor,
        page: Page
    ): Result<List<StreamInfoItem>> =
        withContext(Dispatchers.IO) {

            try {

                val nextPage =
                    searchExtractor.getPage(page)

                val items =
                    nextPage.items
                        .filterIsInstance<StreamInfoItem>()
                        .filter(::isPlayableStream)
                        .filter(
                            YouTubeToSongMapper::isLikelyMusicContent
                        )

                Result.success(items)

            } catch (e: Exception) {

                Timber.e(
                    e,
                    "YouTubeExtractor: Error fetching next page"
                )

                Result.failure(e)
            }
        }

    /**
     * Get stream information using NewPipe.
     *
     * Piped is intentionally NOT used here because callers such as
     * related-video handling need an actual NewPipe StreamExtractor.
     */
    suspend fun getStreamInfo(
        videoUrl: String
    ): Result<StreamExtractor> =
        withContext(Dispatchers.IO) {

            try {

                val fullUrl =
                    if (videoUrl.startsWith("http")) {
                        videoUrl
                    } else {
                        "https://www.youtube.com/watch?v=$videoUrl"
                    }

                Timber.d(
                    "YouTubeExtractor: Fetching stream info for: $fullUrl"
                )

                val streamExtractor =
                    youtubeService.getStreamExtractor(fullUrl)

                try {

                    streamExtractor.fetchPage()

                    Timber.d(
                        "YouTubeExtractor: Got stream info for: " +
                            "${streamExtractor.name}, " +
                            "Audio streams: ${streamExtractor.audioStreams.size}"
                    )

                    Result.success(streamExtractor)

                } catch (e: Exception) {

                    Timber.w(
                        e,
                        "YouTubeExtractor: Primary extraction failed"
                    )

                    try {

                        val altExtractor =
                            youtubeService.getStreamExtractor(fullUrl)

                        altExtractor.fetchPage()

                        val audioCount =
                            altExtractor.audioStreams.size

                        Timber.d(
                            "YouTubeExtractor: Alternative extraction found " +
                                "$audioCount audio streams"
                        )

                        if (audioCount > 0) {
                            Result.success(altExtractor)
                        } else {
                            throw Exception(
                                "No audio streams found with alternative method"
                            )
                        }

                    } catch (altException: Exception) {

                        Timber.e(
                            altException,
                            "YouTubeExtractor: Alternative extraction also failed"
                        )

                        throw e
                    }
                }

            } catch (e: Exception) {

                Timber.e(
                    e,
                    "YouTubeExtractor: Error getting stream info: $videoUrl"
                )

                Result.failure(e)
            }
        }

    /**
     * Resolve a playable direct audio URL.
     *
     * Order:
     *
     * 1. Piped
     * 2. NewPipe
     * 3. yt-dlp if actually installed
     *
     * Piped comes first because the current log shows NewPipe returning
     * zero audio streams.
     */
    suspend fun getStreamUrl(
        videoUrl: String
    ): Result<String> =
        withContext(Dispatchers.IO) {

            var lastError: Exception? = null

            /*
             * ============================================================
             * 1. PIPED
             * ============================================================
             */

            try {

                Timber.d(
                    "YouTubeExtractor: Trying Piped first for $videoUrl"
                )

                val pipedResult =
                    pipedStreamService.getStreamUrl(videoUrl)

                if (pipedResult.isSuccess) {

                    val streamUrl =
                        pipedResult.getOrThrow()

                    if (streamUrl.isNotBlank()) {

                        Timber.d(
                            "YouTubeExtractor: Piped successfully resolved stream: " +
                                "${streamUrl.take(120)}..."
                        )

                        return@withContext Result.success(
                            streamUrl
                        )
                    }
                }

                lastError =
                    pipedResult.exceptionOrNull()
                        as? Exception
                        ?: Exception(
                            "Piped failed to resolve stream"
                        )

                Timber.w(
                    lastError,
                    "YouTubeExtractor: Piped failed"
                )

            } catch (e: Exception) {

                lastError = e

                Timber.e(
                    e,
                    "YouTubeExtractor: Error during Piped fallback"
                )
            }

            /*
             * ============================================================
             * 2. NEWPIPE
             * ============================================================
             */

            val maxRetries = 3

            repeat(maxRetries) { attempt ->

                try {

                    Timber.d(
                        "YouTubeExtractor: Trying NewPipe " +
                            "(attempt ${attempt + 1}/$maxRetries)"
                    )

                    reinitializeNewPipeIfNeeded()

                    val streamInfoResult =
                        getStreamInfo(videoUrl)

                    if (streamInfoResult.isFailure) {

                        val error =
                            streamInfoResult.exceptionOrNull()
                                as? Exception
                                ?: Exception(
                                    "Failed to get stream info"
                                )

                        lastError = error

                        Timber.w(
                            error,
                            "YouTubeExtractor: NewPipe attempt " +
                                "${attempt + 1} failed"
                        )

                        if (attempt < maxRetries - 1) {
                            delay(
                                1000L * (attempt + 1)
                            )
                        }

                        return@repeat
                    }

                    val streamExtractor =
                        streamInfoResult.getOrThrow()

                    val audioStreams =
                        streamExtractor.audioStreams

                    Timber.d(
                        "YouTubeExtractor: NewPipe found " +
                            "${audioStreams.size} audio streams"
                    )

                    val validAudioStreams =
                        audioStreams.filter {
                            !it.content.isNullOrEmpty()
                        }

                    Timber.d(
                        "YouTubeExtractor: NewPipe has " +
                            "${validAudioStreams.size} valid audio streams"
                    )

                    validAudioStreams.forEachIndexed { index, stream ->

                        Timber.d(
                            "YouTubeExtractor: " +
                                "Stream $index - " +
                                "bitrate=${stream.averageBitrate}, " +
                                "format=${stream.format}, " +
                                "itag=${stream.itag}"
                        )
                    }

                    /*
                     * Do not use the old assumption that >300 kbps
                     * means a preview.
                     *
                     * Select the highest available valid audio stream.
                     */
                    val audioStream =
                        validAudioStreams.maxByOrNull {
                            it.averageBitrate ?: 0
                        }

                    if (
                        audioStream != null &&
                        !audioStream.content.isNullOrEmpty()
                    ) {

                        val streamUrl =
                            audioStream.content

                        Timber.d(
                            "YouTubeExtractor: NewPipe successfully resolved: " +
                                "${streamUrl.take(120)}..."
                        )

                        return@withContext Result.success(
                            streamUrl
                        )
                    }

                    lastError =
                        Exception(
                            "NewPipe returned zero valid audio streams"
                        )

                    Timber.w(
                        "YouTubeExtractor: NewPipe returned no valid audio streams"
                    )

                } catch (e: Exception) {

                    lastError = e

                    Timber.e(
                        e,
                        "YouTubeExtractor: NewPipe attempt " +
                            "${attempt + 1} failed"
                    )
                }

                if (attempt < maxRetries - 1) {
                    delay(
                        1000L * (attempt + 1)
                    )
                }
            }

            /*
             * ============================================================
             * 3. YT-DLP
             * ============================================================
             *
             * This remains only as a last-resort fallback.
             *
             * The current application does NOT bundle yt-dlp, which is
             * why your previous log showed:
             *
             * Cannot run program "yt-dlp"
             *
             * We therefore do not rely on this path for normal playback.
             */

            Timber.w(
                "YouTubeExtractor: Piped and NewPipe failed, " +
                    "checking yt-dlp as final fallback"
            )

            try {

                val ytDlpAvailable =
                    ytDlpService.isYtDlpAvailable()

                if (ytDlpAvailable) {

                    val ytDlpResult =
                        ytDlpService.getStreamUrl(videoUrl)

                    if (ytDlpResult.isSuccess) {

                        val streamUrl =
                            ytDlpResult.getOrThrow()

                        if (streamUrl.isNotBlank()) {

                            Timber.d(
                                "YouTubeExtractor: yt-dlp succeeded"
                            )

                            return@withContext Result.success(
                                streamUrl
                            )
                        }
                    }

                    Timber.w(
                        ytDlpResult.exceptionOrNull(),
                        "YouTubeExtractor: yt-dlp failed"
                    )

                } else {

                    Timber.w(
                        "YouTubeExtractor: yt-dlp is not installed; " +
                            "skipping final fallback"
                    )
                }

            } catch (e: Exception) {

                Timber.e(
                    e,
                    "YouTubeExtractor: Error during yt-dlp fallback"
                )
            }

            /*
             * ============================================================
             * EVERYTHING FAILED
             * ============================================================
             */

            val finalError =
                lastError
                    ?: Exception(
                        "All YouTube extraction methods failed"
                    )

            Timber.e(
                finalError,
                "YouTubeExtractor: All extraction methods failed for: $videoUrl"
            )

            Result.failure(finalError)
        }

    /**
     * Get related videos.
     */
    suspend fun getRelatedVideos(
        videoUrl: String
    ): Result<List<StreamInfoItem>> =
        withContext(Dispatchers.IO) {

            try {

                val streamInfoResult =
                    getStreamInfo(videoUrl)

                if (streamInfoResult.isFailure) {

                    return@withContext Result.failure(
                        streamInfoResult.exceptionOrNull()
                            ?: Exception(
                                "Failed to get stream info"
                            )
                    )
                }

                val streamExtractor =
                    streamInfoResult.getOrThrow()

                val relatedItems =
                    streamExtractor.relatedItems
                        ?.items
                        ?.filterIsInstance<StreamInfoItem>()
                        ?.filter(::isPlayableStream)
                        ?.filter(
                            YouTubeToSongMapper::isLikelyMusicContent
                        )
                        ?: emptyList()

                Timber.d(
                    "YouTubeExtractor: Found " +
                        "${relatedItems.size} related videos"
                )

                Result.success(relatedItems)

            } catch (e: Exception) {

                Timber.e(
                    e,
                    "YouTubeExtractor: Error getting related videos: $videoUrl"
                )

                Result.failure(e)
            }
        }
}
