package ani.sanin.parsers

import android.util.Log
import ani.sanin.FileUrl
import ani.sanin.Mapper
import ani.sanin.okHttpClient
import ani.sanin.util.Logger
import eu.kanade.tachiyomi.animesource.model.ChapterType
import eu.kanade.tachiyomi.animesource.model.TimeStamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.net.URI

class NativeVideoExtractor(override val server: VideoServer) : VideoExtractor() {

    override suspend fun extract(): VideoContainer {
        val url = server.embed.url
        var headers = server.embed.headers
        val extraData = server.extraData
        if (extraData != null && headers.isEmpty()) {
            val ref = extraData["referer"]
            if (!ref.isNullOrBlank()) {
                val built = mutableMapOf("Referer" to ref)
                extraData["origin"]?.takeIf { it.isNotBlank() }?.let { built["Origin"] = it }
                headers = built
            }
        }

        val format = when {
            url.contains(".m3u8", ignoreCase = true) -> VideoType.M3U8
            url.contains(".mpd", ignoreCase = true) -> VideoType.DASH
            else -> VideoType.CONTAINER
        }

        val videos = mutableListOf(
            Video(
                quality = null,
                format = format,
                file = FileUrl(url, headers),
                size = null
            )
        )

        if (url.contains(".m3u8", ignoreCase = true) && !isLocalProxyUrl(url)) {
            val parsed = parseHlsMaster(url, headers)
            videos.addAll(parsed)
        } else if (url.contains(".mpd", ignoreCase = true)) {

        } else {

        }

        val subtitles = parseSubtitles(server.extraData?.get("subtitles"))
        val timestamps = parseTimestamps(server.extraData)

        return VideoContainer(videos, subtitles, timestamps = timestamps)
    }

    private fun parseTimestamps(extraData: Map<String, String>?): List<TimeStamp> {
        val result = mutableListOf<TimeStamp>()
        extraData?.get("intro")?.let { json ->
            parseTimestamp(json, "intro", ChapterType.Opening)?.let { result.add(it) }
        }
        extraData?.get("outro")?.let { json ->
            parseTimestamp(json, "outro", ChapterType.Ending)?.let { result.add(it) }
        }
        return result
    }

    private fun parseTimestamp(jsonStr: String, name: String, type: ChapterType): TimeStamp? {
        return try {
            val obj = Mapper.json.parseToJsonElement(jsonStr) as? JsonObject ?: return null
            val start = (obj["start"] as? JsonPrimitive)
                ?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
                ?: (obj["startTime"] as? JsonPrimitive)
                    ?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() } ?: return null
            val end = (obj["end"] as? JsonPrimitive)
                ?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
                ?: (obj["endTime"] as? JsonPrimitive)
                    ?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() } ?: return null
            TimeStamp(start = start, end = end, name = name, type = type)
        } catch (_: Exception) {
            null
        }
    }

    private fun isLocalProxyUrl(url: String): Boolean =
        runCatching {
            val host = java.net.URI(url).host
            host == "127.0.0.1" || host == "localhost"
        }.getOrDefault(false)

    private suspend fun parseHlsMaster(masterUrl: String, headers: Map<String, String>): List<Video> {
        return withContext(Dispatchers.IO) {
            try {
                val masterHost = runCatching { URI(masterUrl).host }.getOrNull()
                    ?: return@withContext emptyList()
                val baseHeaders = ensureUserAgent(headers)
                val body = fetchHls(masterUrl, baseHeaders)
                if (body.isBlank()) return@withContext emptyList()

                val baseUri = URI(masterUrl)
                val lines = body.lines()

                val parsedVariants = mutableListOf<Triple<Int?, Long?, String>>()
                var i = 0
                while (i < lines.size) {
                    val line = lines[i].trim()
                    if (line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true)) {
                        val quality = Regex("RESOLUTION=\\d+x(\\d+)", RegexOption.IGNORE_CASE)
                            .find(line)?.groupValues?.get(1)?.toIntOrNull()
                        val bw = Regex("BANDWIDTH=(\\d+)", RegexOption.IGNORE_CASE)
                            .find(line)?.groupValues?.get(1)?.toLongOrNull()
                        var j = i + 1
                        while (j < lines.size) {
                            val next = lines[j].trim()
                            if (next.isNotEmpty() && !next.startsWith("#")) {
                                val variantUrl = if (next.startsWith("http")) next
                                    else baseUri.resolve(next).toString()
                                parsedVariants.add(Triple(quality, bw, variantUrl))
                                break
                            }
                            j++
                        }
                        i = j
                    } else {
                        i++
                    }
                }

                val hasAudioGroup = lines.any {
                    it.trim().startsWith("#EXT-X-MEDIA:", ignoreCase = true) &&
                        it.contains("TYPE=AUDIO", ignoreCase = true)
                }
                if (hasAudioGroup) {
                    parsedVariants.map { it.third }.firstOrNull()?.let { variantUrl ->
                        runCatching { warmGet(variantUrl, baseHeaders) }
                        segmentAndKeyUrls(variantUrl, baseHeaders).forEach { url ->
                            runCatching { warmGet(url, baseHeaders) }
                        }
                    }
                    return@withContext emptyList()
                }

                val warmUrls = mutableListOf<String>()
                parsedVariants.map { it.third }.take(2).forEach { variantUrl ->
                    warmUrls.add(variantUrl)
                    warmUrls.addAll(segmentAndKeyUrls(variantUrl, baseHeaders))
                }
                warmUrls.distinct().forEach { url ->
                    runCatching { warmGet(url, baseHeaders) }
                }

                val videos = mutableListOf(
                    Video(
                        quality = null,
                        format = VideoType.M3U8,
                        file = FileUrl(masterUrl, baseHeaders),
                        size = null
                    )
                )
                parsedVariants.forEach { (quality, bw, variantUrl) ->
                    videos.add(
                        Video(
                            quality = quality,
                            format = VideoType.M3U8,
                            file = FileUrl(variantUrl, baseHeaders),
                            size = if (bw != null) bw.toDouble() else null
                        )
                    )
                }

                videos.sortedByDescending { it.quality ?: 0 }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    private fun buildRequest(url: String, headers: Map<String, String>): Request =
        Request.Builder().url(url)
            .header("User-Agent", headers["User-Agent"] ?: NativeAnimeParser.USER_AGENT)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .get()
            .build()

    private fun ensureUserAgent(headers: Map<String, String>): Map<String, String> {
        if (!headers["User-Agent"].isNullOrBlank()) return headers
        val out = headers.toMutableMap()
        out["User-Agent"] = NativeAnimeParser.USER_AGENT
        return out
    }

    private suspend fun fetchHls(url: String, headers: Map<String, String>): String {
        val (code, body) = okHttpClient.newCall(buildRequest(url, headers)).execute().use { resp ->
            resp.code to resp.body?.string().orEmpty()
        }
        if (code in 200..299 && body.startsWith("#EXT")) return body

        val cookies = cookieHeader(url)
        if (cookies.isBlank()) {
            Logger.log(Log.WARN, "HLS master $code for $url body=${body.take(300)}")
            return ""
        }
        val retryHeaders = headers.toMutableMap().apply { put("Cookie", cookies) }
        val retry = okHttpClient.newCall(buildRequest(url, retryHeaders)).execute().use { resp ->
            resp.code to resp.body?.string().orEmpty()
        }
        if (retry.first in 200..299 && retry.second.startsWith("#EXT")) {
            Logger.log(Log.WARN, "HLS master $code -> ${retry.first} after Cloudflare retry")
            return retry.second
        }
        Logger.log(Log.WARN, "HLS master ${retry.first} for $url body=${retry.second.take(300)}")
        return ""
    }

    private fun cookieHeader(url: String): String = runCatching {
        okHttpClient.cookieJar.loadForRequest(url.toHttpUrl())
            .joinToString("; ") { "${it.name}=${it.value}" }
    }.getOrDefault("")

    private fun segmentAndKeyUrls(variantUrl: String, headers: Map<String, String>): List<String> {
        return runCatching {
            val body = okHttpClient.newCall(buildRequest(variantUrl, headers)).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string().orEmpty() else ""
            }
            if (!body.startsWith("#EXT")) return@runCatching emptyList()
            val base = URI(variantUrl)
            val urls = mutableListOf<String>()
            var firstSegmentAdded = false
            for (line in body.lines()) {
                val t = line.trim()
                if (!firstSegmentAdded && t.isNotEmpty() && !t.startsWith("#")) {
                    urls.add(if (t.startsWith("http")) t else base.resolve(t).toString())
                    firstSegmentAdded = true
                }
                if (t.startsWith("#EXT-X-KEY:", ignoreCase = true)) {
                    Regex("""URI="([^"]+)"""").find(t)?.groupValues?.get(1)?.let { key ->
                        urls.add(if (key.startsWith("http")) key else base.resolve(key).toString())
                    }
                }
            }
            urls
        }.getOrDefault(emptyList())
    }

    private fun warmGet(url: String, headers: Map<String, String>) {
        okHttpClient.newCall(buildRequest(url, headers)).execute().use { }
    }

    private fun parseSubtitles(jsonStr: String?): List<Subtitle> {
        if (jsonStr.isNullOrBlank()) return emptyList()
        return try {
            val array = Mapper.json.parseToJsonElement(jsonStr) as? JsonArray ?: return emptyList()
            array.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val subUrl = (obj["url"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val lang = (obj["language"] as? JsonPrimitive)?.contentOrNull ?: "Unknown"
                val typeStr = (obj["type"] as? JsonPrimitive)?.contentOrNull
                val type = when (typeStr?.lowercase()) {
                    "ass", "ssa" -> SubtitleType.ASS
                    "srt" -> SubtitleType.SRT
                    else -> SubtitleType.VTT
                }
                Subtitle(language = lang, file = FileUrl(subUrl), type = type)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
