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
     * Piped publishes a larger public instance list.
     *
     * Keep several instances here so one unavailable/overloaded instance
     * does not break YouTube playback completely.
     *
     * The official Kavin instance and the India instance are intentionally
     * near the front of the list.
     */
    private val apiInstances = listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.in.projectsegfau.lt",
        "https://pipedapi.syncpundit.io",
        "https://pipedapi.tokhmi.xyz",
        "https://pipedapi.moomoo.me",
        "https://pipedapi.rivo.lol",
        "https://pipedapi.leptons.xyz",
        "https://piped-api.lunar.icu",
        "https://pipedapi.adminforge.de"
    )

    suspend fun getStreamUrl(videoId: String): Result<String> =
        withContext(Dispatchers.IO) {

            val normalizedVideoId = normalizeVideoId(videoId)

            if (normalizedVideoId.isNullOrBlank()) {
                return@withContext Result.failure(
                    IllegalArgumentException("Invalid YouTube video ID: $videoId")
                )
            }

            var lastError: Exception? = null

            for ((index, apiBase) in apiInstances.withIndex()) {
                try {
                    Timber.d(
                        "Piped: Trying instance ${index + 1}/${apiInstances.size}: $apiBase"
                    )

                    val url = "$apiBase/streams/$normalizedVideoId"

                    val request = Request.Builder()
                        .url(url)
                        .get()
                        .header(
                            "User-Agent",
                            "PixelPlayer/1.0 (Android; Music Player)"
                        )
                        .header(
                            "Accept",
                            "application/json"
                        )
                        .build()

                    okHttpClient.newCall(request).execute().use { response ->

                        if (!response.isSuccessful) {
                            val error = IOException(
                                "Piped HTTP ${response.code} from $apiBase"
                            )

                            Timber.w(
                                "Piped: Instance failed: ${response.code} $apiBase"
                            )

                            lastError = error
                            return@use
                        }

                        val body = response.body?.string()

                        if (body.isNullOrBlank()) {
                            val error = IOException(
                                "Piped returned an empty response from $apiBase"
                            )

                            Timber.w("Piped: Empty response from $apiBase")

                            lastError = error
                            return@use
                        }

                        val streamResponse = try {
                            gson.fromJson(
                                body,
                                PipedStreamResponse::class.java
                            )
                        } catch (e: Exception) {
                            Timber.e(
                                e,
                                "Piped: Failed to parse response from $apiBase"
                            )

                            lastError = e
                            return@use
                        }

                        if (streamResponse == null) {
                            val error = IOException(
                                "Piped returned null stream response"
                            )

                            lastError = error
                            return@use
                        }

                        Timber.d(
                            "Piped: ${streamResponse.audioStreams.size} audio streams from $apiBase"
                        )

                        val validStreams = streamResponse.audioStreams
                            .filter { stream ->
                                !stream.videoOnly &&
                                    !stream.url.isNullOrBlank()
                            }

                        if (validStreams.isEmpty()) {
                            val error = IOException(
                                "Piped returned no valid audio streams from $apiBase"
                            )

                            Timber.w(
                                "Piped: No valid audio streams from $apiBase"
                            )

                            lastError = error
                            return@use
                        }

                        /*
                         * Prefer:
                         * 1. audio/mp4 when available
                         * 2. audio/webm
                         * 3. highest useful bitrate
                         *
                         * We do NOT use the old "50..300 kbps means full song"
                         * heuristic. A bitrate is not a reliable indicator of
                         * whether a YouTube stream is a preview.
                         */
                        val selectedStream = validStreams
                            .sortedWith(
                                compareByDescending<PipedAudioStream> { stream ->
                                    when {
                                        stream.mimeType
                                            ?.startsWith("audio/mp4") == true -> 3

                                        stream.mimeType
                                            ?.startsWith("audio/webm") == true -> 2

                                        else -> 1
                                    }
                                }.thenByDescending { stream ->
                                    stream.bitrate ?: 0
                                }
                            )
                            .firstOrNull()

                        val streamUrl = selectedStream?.url

                        if (!streamUrl.isNullOrBlank()) {
                            Timber.d(
                                "Piped: Successfully resolved stream " +
                                    "bitrate=${selectedStream.bitrate}, " +
                                    "format=${selectedStream.format}, " +
                                    "mimeType=${selectedStream.mimeType}, " +
                                    "instance=$apiBase"
                            )

                            return@withContext Result.success(streamUrl)
                        }

                        lastError = IOException(
                            "Piped stream had no usable URL"
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
                "Piped: All instances failed for videoId=$normalizedVideoId"
            )

            Result.failure(
                lastError ?: Exception(
                    "All Piped instances failed for $normalizedVideoId"
                )
            )
        }

    private fun normalizeVideoId(value: String): String? {
        val trimmed = value.trim()

        if (trimmed.isEmpty()) {
            return null
        }

        if (
            trimmed.matches(
                Regex("[A-Za-z0-9_-]{11}")
            )
        ) {
            return trimmed
        }

        return try {
            when {
                trimmed.contains("youtube.com/watch") -> {
                    Regex("[?&]v=([A-Za-z0-9_-]{11})")
                        .find(trimmed)
                        ?.groupValues
                        ?.getOrNull(1)
                }

                trimmed.contains("youtu.be/") -> {
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
