package com.theveloper.pixelplay.extension.spotify

import com.theveloper.pixelplay.extension.api.ExtensionLyricLine
import com.theveloper.pixelplay.extension.api.ExtensionLyrics
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Synced-lyrics lookup against LRCLIB (https://lrclib.net). No API key. */
internal class LrcLib(private val client: OkHttpClient) {

    fun find(title: String, artist: String, durationMs: Long?): ExtensionLyrics? {
        val durSec = durationMs?.takeIf { it > 0 }?.div(1000)
        val primary = artist.split(',', '&', ';', '/').first().trim()
        val artists = listOf(artist.trim(), primary).filter { it.isNotEmpty() }.distinct()
        if (artists.isEmpty()) return null

        for (a in artists) {
            val cleaned = cleanTitle(title, a)
            get(cleaned, a, durSec)?.let { return it }
        }
        return search(cleanTitle(title, primary), primary, durSec)
    }

    /** LRCLIB matches duration within +-2 s when provided. Returns null on 404 / no lyrics. */
    private fun get(title: String, artist: String, durSec: Long?): ExtensionLyrics? {
        val url = HttpUrl.Builder().scheme("https").host("lrclib.net").addPathSegments("api/get")
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", artist)
            .apply { if (durSec != null) addQueryParameter("duration", durSec.toString()) }
            .build()
        val body = request(url) ?: return null
        return parseRecord(JSONObject(body))
    }

    private fun search(title: String, artist: String, durSec: Long?): ExtensionLyrics? {
        val url = HttpUrl.Builder().scheme("https").host("lrclib.net").addPathSegments("api/search")
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", artist)
            .build()
        val arr = JSONArray(request(url) ?: return null)
        val records = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .filter { !it.optBoolean("instrumental", false) }
            .filter { durSec == null || abs(it.optDouble("duration", -1.0) - durSec) <= 5.0 }
            .sortedBy { if (durSec == null) 0.0 else abs(it.optDouble("duration", 0.0) - durSec) }
        val pick = records.firstOrNull { str(it, "syncedLyrics") != null }
            ?: records.firstOrNull { str(it, "plainLyrics") != null }
        return pick?.let(::parseRecord)
    }

    private fun request(url: HttpUrl): String? {
        val req = Request.Builder().url(url)
            .header("User-Agent", "PixelPlayExtension/1.0 (https://github.com/Kartik-dev72/Pixel-Extention)")
            .build()
        client.newCall(req).execute().use { resp ->
            return if (resp.isSuccessful) resp.body?.string() else null
        }
    }

    private fun str(o: JSONObject, key: String): String? =
        if (o.isNull(key)) null else o.optString(key, "").takeIf { it.isNotBlank() }

    private fun parseRecord(o: JSONObject): ExtensionLyrics? {
        if (o.optBoolean("instrumental", false)) return null
        val lines = str(o, "syncedLyrics")?.let(::parseLrc).orEmpty()
        val plain = str(o, "plainLyrics")
        if (lines.isEmpty() && plain == null) return null
        return ExtensionLyrics(lines = lines, plain = plain, source = "LRCLIB")
    }

    private fun cleanTitle(title: String, artist: String): String {
        var t = title.replace(NOISE_BRACKETS, "").replace(NOISE_DASH_SUFFIX, "").trim()
        if (artist.isNotEmpty() && t.startsWith("$artist - ", ignoreCase = true)) {
            t = t.substring(artist.length + 3).trim()   // YouTube "Artist - Song" titles
        }
        return t.ifEmpty { title }
    }

    internal fun parseLrc(lrc: String): List<ExtensionLyricLine> {
        val out = ArrayList<ExtensionLyricLine>()
        for (raw in lrc.lineSequence()) {
            val stamps = TIMESTAMP.findAll(raw).toList()
            if (stamps.isEmpty()) continue
            val text = raw.substring(stamps.last().range.last + 1).replace(WORD_TAGS, "").trim()
            for (m in stamps) {
                val (mm, ss, frac) = m.destructured
                val ms = mm.toLong() * 60_000 + ss.toLong() * 1_000 +
                    (frac.padEnd(3, '0').take(3).toLongOrNull() ?: 0L)
                out += ExtensionLyricLine(ms, text)
            }
        }
        return out.sortedBy { it.timeMs }
    }

    private companion object {
        val TIMESTAMP = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
        val WORD_TAGS = Regex("""<\d+:\d+(?:[.:]\d+)?>""")
        val NOISE_BRACKETS = Regex(
            """\s*[(\[][^)\]]*(official|video|audio|lyric|visualizer|\bhd\b|4k|remaster|explicit)[^)\]]*[)\]]""",
            RegexOption.IGNORE_CASE
        )
        val NOISE_DASH_SUFFIX = Regex(
            """\s+-\s+(\d{4}\s+)?(remaster(ed)?|mono|stereo|single version|radio edit).*$""",
            RegexOption.IGNORE_CASE
        )
    }
}
