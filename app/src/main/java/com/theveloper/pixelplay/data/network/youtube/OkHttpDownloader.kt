package com.theveloper.pixelplay.data.network.youtube

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as ExtractorRequest
import org.schabi.newpipe.extractor.downloader.Response as ExtractorResponse
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.util.concurrent.TimeUnit

class OkHttpDownloader private constructor(
    private val client: OkHttpClient
) : Downloader() {

    companion object {
        @Volatile
        private var instance: OkHttpDownloader? = null

        fun getInstance(
            builder: OkHttpClient.Builder = OkHttpClient.Builder()
        ): OkHttpDownloader {
            return instance ?: synchronized(this) {
                instance ?: OkHttpDownloader(
                    builder
                        .readTimeout(30, TimeUnit.SECONDS)
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .followRedirects(true)
                        .followSslRedirects(true)
                        .build()
                ).also { instance = it }
            }
        }
    }

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(
        request: ExtractorRequest
    ): ExtractorResponse {

        val dataToSend = request.dataToSend()

        val requestBody: RequestBody? =
            dataToSend?.let {
                RequestBody.create(null, it)
            }

        val requestBuilder = Request.Builder()
            .url(request.url())
            .method(
                request.httpMethod(),
                requestBody
            )

        request.headers().forEach { (key, values) ->
            values.forEach { value ->
                requestBuilder.addHeader(key, value)
            }
        }

        val response: Response =
            client
                .newCall(requestBuilder.build())
                .execute()

        if (response.code == 429) {
            response.close()
            throw ReCaptchaException(
                "reCaptcha Challenge requested",
                request.url()
            )
        }

        val responseBody: ResponseBody? = response.body
        val body = responseBody?.string()

        return ExtractorResponse(
            response.code,
            response.message,
            response.headers.toMultimap(),
            body,
            response.request.url.toString()
        )
    }
}
