package com.theveloper.pixelplay.extension.spotify

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit

/**
 * NewPipe [Downloader] on top of OkHttp. If your extension-youtube module has its own
 * SimpleOkHttpDownloader, you can copy that file over this one (change the package line).
 * The same OkHttpClient is reused for Spotify API calls.
 */
internal class SimpleOkHttpDownloader : Downloader() {

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun execute(request: Request): Response {
        val method = request.httpMethod()
        val bytes = request.dataToSend()
        val body = when {
            bytes != null -> bytes.toRequestBody()
            method.equals("POST", true) || method.equals("PUT", true) || method.equals("PATCH", true) ->
                ByteArray(0).toRequestBody()
            else -> null
        }

        val builder = okhttp3.Request.Builder().method(method, body).url(request.url())
        request.headers().forEach { (name, values) -> values.forEach { builder.addHeader(name, it) } }

        client.newCall(builder.build()).execute().use { resp ->
            if (resp.code == 429) {
                throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            }
            return Response(
                resp.code,
                resp.message,
                resp.headers.toMultimap(),
                resp.body?.string(),
                resp.request.url.toString()
            )
        }
    }
}
