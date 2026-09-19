package com.theveloper.pixelplay.extension.youtube

import com.theveloper.pixelplay.extension.api.ExtensionCapability
import com.theveloper.pixelplay.extension.api.ExtensionHostContext
import com.theveloper.pixelplay.extension.api.ExtensionMetadata
import com.theveloper.pixelplay.extension.api.ExtensionPage
import com.theveloper.pixelplay.extension.api.ExtensionSearchResult
import com.theveloper.pixelplay.extension.api.ExtensionTrack
import com.theveloper.pixelplay.extension.api.MusicExtension
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import timber.log.Timber
import java.util.concurrent.TimeUnit
import org.schabi.newpipe.extractor.downloader.Request as ExtractorRequest
import org.schabi.newpipe.extractor.downloader.Response as ExtractorResponse

/**
 * Reference PixelPlay extension. This is deliberately the *simple* version of what
 * `data/network/youtube/YouTubeExtractorService.kt` does in the host app: NewPipe only, no
 * Piped/yt-dlp fallback chain. Extensions run in their own APK with their own dependency
 * footprint — keeping this one lean is the point of moving it out of the host in the first place.
 * If you want the fallback chain too, port `PipedStreamService`'s logic in as a second attempt
 * inside [resolveStreamUrl].
 */
class YouTubeMusicExtension : MusicExtension {

    override val metadata = ExtensionMetadata(
        id = "com.theveloper.pixelplay.extension.youtube",
        displayName = "YouTube",
        version = "1.0.0",
        author = "PixelPlay",
        capabilities = setOf(
            ExtensionCapability.SEARCH,
            ExtensionCapability.STREAM,
            ExtensionCapability.RELATED
        ),
        description = "Search and stream from YouTube via NewPipe — no API key or login required."
    )

    private var initialized = false

    override fun onCreate(context: ExtensionHostContext) {
        if (initialized) return
        try {
            NewPipe.init(SimpleOkHttpDownloader())
            initialized = true
        } catch (e: Exception) {
            Timber.e(e, "YouTubeMusicExtension: NewPipe.init failed")
        }
    }

    override suspend fun isReady(): Boolean = initialized

    override suspend fun search(query: String, page: ExtensionPage?): ExtensionSearchResult {
        val youtubeService = ServiceList.YouTube

        // Prefer YouTube Music's "songs" filter first, same as the host's own logic, falling
        // back to plain video search if that filter comes back empty.
        val musicFilters = listOf(
            YoutubeSearchQueryHandlerFactory.MUSIC_SONGS,
            YoutubeSearchQueryHandlerFactory.MUSIC_VIDEOS
        )

        val musicResults = musicFilters.firstNotNullOfOrNull { filter ->
            runCatching {
                val extractor = youtubeService.getSearchExtractor(query, listOf(filter), "")
                extractor.fetchPage()
                extractor.initialPage.items
                    .filterIsInstance<StreamInfoItem>()
                    .filter(::isPlayableStream)
            }.getOrNull()?.takeIf { it.isNotEmpty() }
        }

        val items = musicResults ?: run {
            val extractor = youtubeService.getSearchExtractor(query)
            extractor.fetchPage()
            extractor.initialPage.items
                .filterIsInstance<StreamInfoItem>()
                .filter(::isPlayableStream)
        }

        return ExtensionSearchResult(tracks = items.map { it.toExtensionTrack() })
    }

    override suspend fun resolveStreamUrl(trackId: String): String {
        val fullUrl = if (trackId.startsWith("http")) trackId else "https://www.youtube.com/watch?v=$trackId"

        val streamExtractor = ServiceList.YouTube.getStreamExtractor(fullUrl)
        streamExtractor.fetchPage()

        val bestAudio = streamExtractor.audioStreams
            .filter { !it.content.isNullOrEmpty() }
            .maxByOrNull { it.averageBitrate ?: 0 }

        return bestAudio?.content
            ?: throw IllegalStateException("No playable audio stream found for $trackId")
    }

    override suspend fun getRelated(trackId: String): List<ExtensionTrack> {
        val fullUrl = if (trackId.startsWith("http")) trackId else "https://www.youtube.com/watch?v=$trackId"
        val streamExtractor = ServiceList.YouTube.getStreamExtractor(fullUrl)
        streamExtractor.fetchPage()

        return streamExtractor.relatedItems?.items
            ?.filterIsInstance<StreamInfoItem>()
            ?.filter(::isPlayableStream)
            ?.map { it.toExtensionTrack() }
            ?: emptyList()
    }

    private fun isPlayableStream(item: StreamInfoItem): Boolean =
        item.streamType == StreamType.VIDEO_STREAM || item.streamType == StreamType.AUDIO_STREAM

    private fun StreamInfoItem.toExtensionTrack(): ExtensionTrack = ExtensionTrack(
        id = this.url.substringAfter("v=").substringBefore("&").ifBlank { this.url },
        title = this.name,
        artist = this.uploaderName ?: "Unknown",
        durationMs = if (this.duration >= 0) this.duration * 1000 else null,
        thumbnailUrl = this.thumbnails.maxByOrNull { it.width }?.url
    )
}

/**
 * NewPipe Downloader backed by OkHttp — a direct port of the host's own
 * `data/network/youtube/OkHttpDownloader.kt`, kept identical on purpose. NewPipe's own extractor
 * code injects whatever headers a given YouTube client fingerprint needs; the downloader's job is
 * just to send them faithfully and surface HTTP 429 as [ReCaptchaException] so NewPipe's own
 * retry/error handling upstream knows what happened, rather than treating it as a generic failure.
 */
private class SimpleOkHttpDownloader : Downloader() {
    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override fun execute(request: ExtractorRequest): ExtractorResponse {
        val dataToSend = request.dataToSend()
        val requestBody: RequestBody? = if (dataToSend != null) RequestBody.create(null, dataToSend) else null

        val requestBuilder = okhttp3.Request.Builder()
            .method(request.httpMethod(), requestBody)
            .url(request.url())

        request.headers().forEach { (key, values) ->
            values.forEach { value -> requestBuilder.addHeader(key, value) }
        }

        val response = client.newCall(requestBuilder.build()).execute()

        if (response.code == 429) {
            response.close()
            throw ReCaptchaException("reCaptcha Challenge requested", request.url())
        }

        val bodyString = response.body?.string()

        return ExtractorResponse(
            response.code,
            response.message,
            response.headers.toMultimap(),
            bodyString,
            response.request.url.toString()
        )
    }
}
