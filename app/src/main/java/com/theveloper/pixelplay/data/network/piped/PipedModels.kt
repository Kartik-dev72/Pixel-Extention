package com.theveloper.pixelplay.data.network.piped

import com.google.gson.annotations.SerializedName

data class PipedStreamResponse(
    @SerializedName("title")
    val title: String? = null,

    @SerializedName("thumbnailUrl")
    val thumbnailUrl: String? = null,

    @SerializedName("audioStreams")
    val audioStreams: List<PipedAudioStream> = emptyList(),

    @SerializedName("videoStreams")
    val videoStreams: List<PipedVideoStream> = emptyList()
)

data class PipedAudioStream(
    @SerializedName("url")
    val url: String? = null,

    @SerializedName("format")
    val format: String? = null,

    @SerializedName("quality")
    val quality: String? = null,

    @SerializedName("mimeType")
    val mimeType: String? = null,

    @SerializedName("codec")
    val codec: String? = null,

    @SerializedName("audioTrackId")
    val audioTrackId: String? = null,

    @SerializedName("audioTrackName")
    val audioTrackName: String? = null,

    @SerializedName("audioTrackType")
    val audioTrackType: String? = null,

    @SerializedName("audioTrackLocale")
    val audioTrackLocale: String? = null,

    @SerializedName("videoOnly")
    val videoOnly: Boolean = false,

    @SerializedName("itag")
    val itag: Int? = null,

    @SerializedName("bitrate")
    val bitrate: Int? = null,

    @SerializedName("contentLength")
    val contentLength: Long? = null,

    @SerializedName("initStart")
    val initStart: Int? = null,

    @SerializedName("initEnd")
    val initEnd: Int? = null,

    @SerializedName("indexStart")
    val indexStart: Int? = null,

    @SerializedName("indexEnd")
    val indexEnd: Int? = null
)

data class PipedVideoStream(
    @SerializedName("url")
    val url: String? = null,

    @SerializedName("format")
    val format: String? = null,

    @SerializedName("quality")
    val quality: String? = null,

    @SerializedName("mimeType")
    val mimeType: String? = null,

    @SerializedName("codec")
    val codec: String? = null,

    @SerializedName("videoOnly")
    val videoOnly: Boolean = false,

    @SerializedName("itag")
    val itag: Int? = null,

    @SerializedName("bitrate")
    val bitrate: Int? = null,

    @SerializedName("contentLength")
    val contentLength: Long? = null
)
