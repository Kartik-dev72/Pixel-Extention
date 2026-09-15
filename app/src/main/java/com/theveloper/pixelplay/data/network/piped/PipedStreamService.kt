package com.theveloper.pixelplay.data.network.piped

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PipedStreamService @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val gson: Gson
) {

    /*
     * Piped instances are extremely volatile.
     *
     * The first two are currently the most important:
     * - ducks.party
     * - private.coffee
     *
     * The remaining instances are fallback candidates from the
     * official Piped instance list.
     */
    private val apiInstances = listOf(
        "https://pipedapi.ducks.party",
        "https://api.piped.private.coffee",

        "https://pipedapi.tokhmi.xyz",
        "https://pipedapi.moomoo.me",
        "https://pipedapi.syncpundit.io",
        "https://api-piped.mha.fi",
        "https://piped-api.garudalinux.org",
        "https://pipedapi.rivo.lol",
        "https://pipedapi.leptons.xyz",
        "https://piped-api.lunar.icu",
        "https://pipedapi.adminforge.de",
        "https://pipedapi.qdi.fi",
        "https://pipedapi.owo.si",
        "https://pipedapi.12a.app",
        "https://api.piped.minionflo.net",
        "https://pipedapi.nezumi.party",
        "https://pipedapi.ngn.tf",
        "https://pipedapi.coldforge.xyz",
        "https://pipedapi.codespace.cz",
        "https://pipedapi.reallyaweso.me",
        "https://pipedapi.phoenixthrush.com"
    )

    suspend fun getStreamUrl(videoId: String): Result<String> =
        withContext(Dispatchers.IO) {

            val normalizedVideoId = normalizeVideoId(videoId)

            if (normalizedVideoId.isNullOrBlank()) {
                return@withContext Result.failure(
                    IllegalArgumentException(
                        "Invalid YouTube video ID: $videoId"
                    )
                )
            }

            var lastError: Exception? = null

            for ((index, apiBase) in apiInstances.withIndex()) {

                try {
                    Timber.d(
                        "Piped: Trying instance " +
                            "${index + 1}/${apiInstances.size}: $apiBase"
                    )

                    val requestUrl =
                        "$apiBase/streams/$normalizedVideoId"

                    val request = Request.Builder()
                        .url(requestUrl)
                        .get()
                        .header(
                            "User-Agent",
                            "PixelPlayer/1.0 (Android; Music Player)"
                        )
                        .header(
                            "Accept",
                            "application/json"
                        )
                        .header(
                            "Accept-Language",
                            "en-US,en;q=0.9"
                        )
                        .build()

                    okHttpClient.newCall(request).execute().use { response ->

                        if (!response.isSuccessful) {

                            val error = IOException(
                                "HTTP ${response.code} from $apiBase"
                            )

                            Timber.w(
                                "Piped: HTTP ${response.code} from $apiBase"
                            )

                            lastError = error

                            return@use
                        }

                        val body = response.body?.string()

                        if (body.isNullOrBlank()) {

                            val error = IOException(
                                "Empty response from $apiBase"
                            )

                            Timber.w(
                                "Piped: Empty response from $apiBase"
                            )

                            lastError = error

                            return@use
                        }

                        /*
                         * A valid Piped /streams response must be a JSON
                         * object beginning with '{'.
                         *
                         * Some failed instances currently return a JSON
                         * string containing an error instead. Trying to
                         * deserialize that directly produces:
                         *
                         * Expected BEGIN_OBJECT but was STRING
                         *
                         * which appeared in your log.
                         */
                        val trimmedBody = body.trimStart()

                        if (!trimmedBody.startsWith("{")) {

                            val preview =
                                trimmedBody
                                    .take(300)
                                    .replace("\n", " ")
                                    .replace("\r", " ")

                            val error = IOException(
                                "Piped returned non-object response " +
                                    "from $apiBase"
                            )

                            Timber.w(
                                "Piped: Invalid response from $apiBase: $preview"
                            )

                            lastError = error

                            return@use
                        }

                        val streamResponse =
                            try {
                                gson.fromJson(
                                    trimmedBody,
                                    PipedStreamResponse::class.java
                                )
                            } catch (e: Exception) {

                                Timber.e(
                                    e,
                                    "Piped: JSON parsing failed for $apiBase"
                                )

                                lastError = e

                                return@use
                            }

                        if (streamResponse == null) {

                            val error = IOException(
                                "Piped returned null stream response " +
                                    "from $apiBase"
                            )

                            lastError = error

                            return@use
                        }

                        Timber.d(
                            "Piped: Received response from $apiBase: " +
                                "audioStreams=" +
                                streamResponse.audioStreams.size +
                                ", videoStreams=" +
                                streamResponse.videoStreams.size
                        )

                        /*
                         * Only actual audio streams with usable URLs.
                         */
                        val validStreams =
                            streamResponse.audioStreams
                                .filter { stream ->
                                    !stream.videoOnly &&
                                        !stream.url.isNullOrBlank()
                                }

                        if (validStreams.isEmpty()) {

                            val error = IOException(
                                "Piped returned no usable audio streams " +
                                    "from $apiBase"
                            )

                            Timber.w(
                                "Piped: No usable audio streams from $apiBase"
                            )

                            lastError = error

                            return@use
                        }

                        /*
                         * Prefer AAC/MP4 because Android/Media3 generally
                         * handles it very reliably.
                         *
                         * Then WebM/Opus.
                         *
                         * Within the same format, prefer the highest
                         * bitrate.
                         */
                        val selectedStream =
                            validStreams
                                .sortedWith(
                                    compareByDescending<PipedAudioStream> {
                                        stream ->
                                        when {
                                            stream.mimeType
                                                ?.startsWith("audio/mp4") == true -> 3

                                            stream.mimeType
                                                ?.startsWith("audio/webm") == true -> 2

                                            stream.format
                                                ?.contains(
                                                    "m4a",
                                                    ignoreCase = true
                                                ) == true -> 3

                                            stream.format
                                                ?.contains(
                                                    "opus",
                                                    ignoreCase = true
                                                ) == true -> 2

                                            else -> 1
                                        }
                                    }.thenByDescending { stream ->
                                        stream.bitrate ?: 0
                                    }
                                )
                                .firstOrNull()

                        val streamUrl =
                            selectedStream?.url

                        if (!streamUrl.isNullOrBlank()) {

                            Timber.d(
                                "Piped: SUCCESS " +
                                    "instance=$apiBase, " +
                                    "bitrate=${selectedStream.bitrate}, " +
                                    "format=${selectedStream.format}, " +
                                    "mimeType=${selectedStream.mimeType}"
                            )

                            return@withContext Result.success(
                                streamUrl
                            )
                        }

                        val error = IOException(
                            "Selected Piped stream has no URL"
                        )

                        lastError = error

                        Timber.w(
                            "Piped: Selected stream had no URL " +
                                "from $apiBase"
                        )
                    }

                } catch (e: Exception) {

                    lastError = e

                    Timber.w(
                        e,
                        "Piped: Instance failed: $apiBase"
                    )
                }
            }

            Timber.e(
                lastError,
                "Piped: ALL instances failed for " +
                    "videoId=$normalizedVideoId"
            )

            Result.failure(
                lastError
                    ?: IOException(
                        "All Piped instances failed for " +
                            normalizedVideoId
                    )
            )
        }

    private fun normalizeVideoId(
        value: String
    ): String? {

        val trimmed =
            value.trim()

        if (trimmed.isEmpty()) {
            return null
        }

        /*
         * Already a plain YouTube video ID.
         */
        if (
            trimmed.matches(
                Regex("[A-Za-z0-9_-]{11}")
            )
        ) {
            return trimmed
        }

        return try {

            when {

                trimmed.contains(
                    "youtube.com/watch"
                ) -> {

                    Regex(
                        "[?&]v=([A-Za-z0-9_-]{11})"
                    )
                        .find(trimmed)
                        ?.groupValues
                        ?.getOrNull(1)
                }

                trimmed.contains(
                    "youtu.be/"
                ) -> {

                    trimmed
                        .substringAfter("youtu.be/")
                        .substringBefore("?")
                        .substringBefore("&")
                        .takeIf {
                            it.matches(
                                Regex("[A-Za-z0-9_-]{11}")
                            )
                        }
                }

                else -> null
            }

        } catch (_: Exception) {
            null
        }
    }
}
