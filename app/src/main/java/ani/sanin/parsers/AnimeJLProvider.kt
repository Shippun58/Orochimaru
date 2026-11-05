package ani.sanin.parsers

import ani.sanin.FileUrl
import ani.sanin.Mapper
import ani.sanin.okHttpClient
import ani.sanin.util.Logger
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Request
import java.io.IOException
import java.net.URI

import android.app.Activity
import android.os.Message
import android.util.Base64
import android.util.Log
import android.view.ViewGroup
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import ani.sanin.currContext
import ani.sanin.others.JsUnpacker
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.Collections
import java.util.LinkedHashSet
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.delay

class AnimeJLProvider : NativeAnimeParser() {

    override val name = "AnimeJL"
    override val saveName = "animejl"
    override val language = "Spanish"
    override val defaultBaseUrl = "https://www.anime-jl.net"
    override val knownServers = listOf("Voe", "Mp4Upload", "YourUpload", "StreamWish", "Uqload", "VidHide", "StreamTape", "Upns", "Universal")

    override suspend fun search(query: String): List<ShowResponse> {
        return withContext(Dispatchers.IO) {
            try {
                val json = get("$baseUrl/api/search?q=${encode(query)}", "$baseUrl/")
                val arr = Mapper.json.parseToJsonElement(json) as? JsonArray ?: return@withContext emptyList()
                arr.mapNotNull { el ->
                    val obj = el as? JsonObject ?: return@mapNotNull null
                    val id = (obj["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    val title = (obj["title"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    val slug = (obj["url"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    val cover = (obj["cover"] as? JsonPrimitive)?.contentOrNull ?: ""
                    val coverUrl = if (cover.isNotEmpty()) "$baseUrl/storage/$cover" else defaultImage
                    ShowResponse(
                        name = title,
                        link = "$id/$slug",
                        coverUrl = coverUrl,
                        extra = mutableMapOf("id" to id, "slug" to slug)
                    )
                }.sortedBy { dubPriority(it.name) }
            } catch (e: Exception) {
                ajlLog("AnimeJL search error: ${e.message}")
                emptyList()
            }
        }
    }

    private fun dubPriority(title: String): Int = when {
        title.contains("latino", ignoreCase = true) -> 0
        title.contains("castellano", ignoreCase = true) -> 1
        else -> 2
    }

    override suspend fun loadEpisodes(animeLink: String, extra: Map<String, String>?, sAnime: SAnime): List<Episode> {
        val id = extra?.get("id") ?: animeLink.substringBefore('/')
        val slug = extra?.get("slug") ?: animeLink.substringAfter('/')
        return withContext(Dispatchers.IO) {
            try {
                val html = get("$baseUrl/anime/$id/$slug", "$baseUrl/")
                val episodesPattern = Regex("var episodes = (\\[.*?]);", RegexOption.DOT_MATCHES_ALL)
                val episodesMatch = episodesPattern.find(html) ?: return@withContext emptyList()
                val episodesString = episodesMatch.groupValues[1]
                val episodePattern = Regex("\\[(\\d+),\"(.*?)\",\"(.*?)\",\"(.*?)\"]")
                episodePattern.findAll(episodesString).mapNotNull { match ->
                    val number = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                    val coverPath = match.groupValues[3]
                    val thumb = if (coverPath.isNotEmpty()) "$baseUrl/storage/$coverPath" else defaultImage
                    Episode(
                        number = number.toString(),
                        link = "$baseUrl/anime/$id/$slug/episodio-$number",
                        thumbnail = thumb,
                        extra = mutableMapOf("id" to id, "slug" to slug)
                    )
                }.sortedBy { it.number.toIntOrNull() ?: 0 }.toList()
            } catch (e: Exception) {
                ajlLog("AnimeJL loadEpisodes error: ${e.message}")
                emptyList()
            }
        }
    }

    override suspend fun loadVideoServers(episodeLink: String, extra: Map<String, String>?, sEpisode: SEpisode): List<VideoServer> {
        return withContext(Dispatchers.IO) {
            try {
                val html = get(episodeLink, "$baseUrl/")
                val videoPattern = Regex("""video\[\d+\] = '<iframe src="(.*?)"""")
                val seen = mutableSetOf<String>()
                videoPattern.findAll(html).mapNotNull { match ->
                    val embed = match.groupValues[1].trim()
                    if (!embed.startsWith("http")) return@mapNotNull null
                    val family = hostFamily(embed) ?: return@mapNotNull null
                    if (!seen.add(family)) return@mapNotNull null
                    VideoServer(
                        family,
                        embed,
                        mapOf("referer" to "$baseUrl/", "host" to family)
                    )
                }.toList()
            } catch (e: Exception) {
                ajlLog("AnimeJL loadVideoServers error: ${e.message}")
                emptyList()
            }
        }
    }

    override suspend fun getVideoExtractor(server: VideoServer): VideoExtractor {
        return when (server.extraData?.get("host")) {
            "Voe" -> AnimeJLVoeExtractor(server)
            "Mp4Upload" -> Mp4UploadExtractor(server)
            "StreamWish" -> AnimeJLStreamWishExtractor(server)
            "YourUpload" -> YourUploadExtractor(server)
            "StreamTape" -> AnimeJLStreamTapeExtractor(server)
            "Upns" -> AnimeJLUpnsExtractor(server)
            "Uqload" -> AnimeJLUqloadExtractor(server)
            "VidHide" -> AnimeJLVidHideExtractor(server)
            else -> UniversalEmbedExtractor(server)
        }
    }

    companion object {
        fun hostFamily(embed: String): String? {
            val host = runCatching { URI(embed).host }
                .getOrNull()?.lowercase()?.removePrefix("www.") ?: return null
            return when {
                host == "voe.sx" || host.endsWith(".voe.sx") -> "Voe"
                host == "mp4upload.com" || host.endsWith(".mp4upload.com") -> "Mp4Upload"
                host == "yourupload.com" || host.endsWith(".yourupload.com") -> "YourUpload"
                host.contains("streamwish") || host.contains("playerwish") ||
                    host.contains("wishonly") || host.contains("filemoon") -> "StreamWish"
                host.contains("uqload") -> "Uqload"
                host.contains("vidhide") || host.contains("streamhidevid") -> "VidHide"
                host.contains("streamtape") || host.contains("streamta.pe") -> "StreamTape"
                host.contains("upns.pro") || host.contains("4meplayer") ||
                    host.contains("p2pstream") -> "Upns"
                host == "ok.ru" || host.endsWith(".ok.ru") -> null
                else -> {

                    val labels = host.split(".")
                    val label = if (labels.size >= 2) labels[labels.size - 2] else labels[0]
                    label.replaceFirstChar { it.uppercase() }.takeIf { it.length >= 2 }
                }
            }
        }
    }
}

class YourUploadExtractor(override val server: VideoServer) : VideoExtractor() {
    override suspend fun extract(): VideoContainer = withContext(Dispatchers.IO) {
        try {
            val pageReferer = server.extraData?.get("referer") ?: "https://www.yourupload.com/"
            val page = ajlGet(server.embed.url, pageReferer)
            val doc = Jsoup.parse(page)
            val baseData = doc.select("script").firstOrNull { it.data().contains("jwplayerOptions") }?.data()
            val mp4 = baseData?.substringAfter("file: '")?.substringBefore("',")?.takeIf { it.startsWith("http") }
            if (mp4.isNullOrBlank()) {
                ajlLog("AnimeJL YourUpload: no mp4 found in ${server.embed.url}")
                VideoContainer(emptyList())
            } else {
                ajlLog("AnimeJL YourUpload: got ${mp4.take(120)}")
                VideoContainer(
                    listOf(Video(null, VideoType.CONTAINER, FileUrl(mp4, mapOf("Referer" to "https://www.yourupload.com/"))))
                )
            }
        } catch (e: Exception) {
            ajlLog("AnimeJL YourUpload extract error: ${e.message}")
            VideoContainer(emptyList())
        }
    }
}

class AnimeJLStreamTapeExtractor(override val server: VideoServer) : VideoExtractor() {
    override suspend fun extract(): VideoContainer = withContext(Dispatchers.IO) {
        try {
            val referer = server.extraData?.get("referer") ?: ajlOrigin(server.embed.url)
            val baseUrl = "https://streamtape.com/e/"
            val embed = server.embed.url
            val pageUrl = if (embed.startsWith(baseUrl)) embed else {
                val id = embed.split("/").getOrNull(4)
                    ?: return@withContext VideoContainer(emptyList()).also {
                        ajlLog("AnimeJL StreamTape: no id in ${server.embed.url}")
                    }
                baseUrl + id
            }
            val page = ajlGet(pageUrl, referer)
            val doc = Jsoup.parse(page)
            val targetLine = "document.getElementById('robotlink')"
            val script = doc.select("script").firstOrNull { it.data().contains(targetLine) }?.data()
                ?: return@withContext VideoContainer(emptyList()).also {
                    ajlLog("AnimeJL StreamTape: no robotlink script in ${server.embed.url}")
                }
            val part1 = script.substringAfter("$targetLine.innerHTML = '").substringBefore("'")
            val part2 = script.substringAfter("+ ('xcd").substringBefore("'")
            val videoUrl = "https:" + part1 + part2
            if (!videoUrl.startsWith("https:")) {
                return@withContext VideoContainer(emptyList()).also {
                    ajlLog("AnimeJL StreamTape: empty url from ${server.embed.url}")
                }
            }
            ajlLog("AnimeJL StreamTape: got ${videoUrl.take(120)}")
            VideoContainer(
                listOf(Video(null, VideoType.CONTAINER, FileUrl(videoUrl, mapOf("Referer" to referer))))
            )
        } catch (e: Exception) {
            ajlLog("AnimeJL StreamTape extract error: ${e.message}")
            VideoContainer(emptyList())
        }
    }
}

class AnimeJLUpnsExtractor(override val server: VideoServer) : VideoExtractor() {
    override suspend fun extract(): VideoContainer = withContext(Dispatchers.IO) {
        try {
            val embed = server.embed.url
            val id = embed.substringAfter('#', "").trim().ifEmpty {
                embed.substringAfterLast('/').trim()
            }
            if (id.isBlank()) {
                ajlLog("AnimeJL Upns: no id in $embed")
                return@withContext VideoContainer(emptyList())
            }

            val referer = ajlOrigin(embed)
            val host = runCatching { URI(embed).host }.getOrNull()?.removePrefix("www.") ?: ""
            if (host.isBlank()) {
                ajlLog("AnimeJL Upns: no host in $embed")
                return@withContext VideoContainer(emptyList())
            }
            val api = "https://$host/api/v1/video?id=$id&w=1920&h=1080&r=$host"
            val res = ajlGet(api, referer)
            val obj = ajlUpnsDecrypt(res)?.let {
                runCatching { Mapper.json.parseToJsonElement(it) as? JsonObject }.getOrNull()
            }
            val source = (obj?.get("source") as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: (obj?.get("cfNative") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            if (source.isNullOrBlank()) {
                ajlLog("AnimeJL Upns: no source in api response")
                return@withContext VideoContainer(emptyList())
            }
            ajlLog("AnimeJL Upns: got ${source.take(140)}")
            val hls = ajlResolveHls(source, mapOf("Referer" to referer, "Origin" to referer))
            if (hls.videos.isEmpty()) {
                ajlLog("AnimeJL Upns: no playable variants from $source")
                VideoContainer(emptyList())
            } else {
                VideoContainer(hls.videos, audioTracks = hls.audioTracks)
            }
        } catch (e: Exception) {
            ajlLog("AnimeJL Upns extract error: ${e.message}")
            VideoContainer(emptyList())
        }
    }
}

class UniversalEmbedExtractor(override val server: VideoServer) : VideoExtractor() {
    override suspend fun extract(): VideoContainer = withContext(Dispatchers.IO) {
        try {
            val referer = server.extraData?.get("referer")
            val embed = server.embed.url
            val page = ajlGet(embed, referer)
            val hlsUrls = ajlHlsUrls(page)
            val mp4Urls = Regex("""https?://[^"'<>\s]+?\.mp4[^"'<>\s]*""", RegexOption.IGNORE_CASE)
                .findAll(page).map { it.value }.distinct().toList()
            val dashUrls = Regex("""https?://[^"'<>\s]+?\.mpd[^"'<>\s]*""", RegexOption.IGNORE_CASE)
                .findAll(page).map { it.value }.distinct().toList()

            val embedOrigin = ajlOrigin(embed)
            val staticUrls = (hlsUrls + mp4Urls + dashUrls).distinct()
            val allUrls = if (staticUrls.isEmpty()) {
                emptyList()
            } else {
                staticUrls.filter { url -> ajlMediaOk(url, ajlMediaHeaders(url, embed, embedOrigin)) }
            }
            val finalUrls = if (allUrls.isEmpty()) {
                ajlLog("AnimeJL Universal: static empty, trying headless WebView for $embed")
                ajlWebViewCatch(embed, referer)
            } else {
                allUrls
            }
            if (finalUrls.isEmpty()) {
                ajlLog("AnimeJL Universal: no streams found in $embed")
                VideoContainer(emptyList())
            } else {
                ajlLog("AnimeJL Universal: ${finalUrls.size} streams found")
                VideoContainer(finalUrls.map { url ->
                    val format = when {
                        url.contains(".m3u8", ignoreCase = true) -> VideoType.M3U8
                        url.contains(".mpd", ignoreCase = true) -> VideoType.DASH
                        else -> VideoType.CONTAINER
                    }
                    val headers = if (format == VideoType.CONTAINER) {
                        mapOf("Referer" to embed)
                    } else {
                        mapOf("Referer" to embed, "Origin" to embedOrigin)
                    }
                    Video(null, format, FileUrl(url, headers))
                })
            }
        } catch (e: Exception) {
            ajlLog("AnimeJL Universal extract error: ${e.message}")
            VideoContainer(emptyList())
        }
    }
}

class AnimeJLVoeExtractor(override val server: VideoServer) : VideoExtractor() {
    override suspend fun extract(): VideoContainer = withContext(Dispatchers.IO) {
        try {
            val referer = server.extraData?.get("referer") ?: "https://www.anime-jl.net/"
            val embed = server.embed.url
            val voeClient = okHttpClient.newBuilder()
                .addInterceptor(DdosGuardInterceptor(okHttpClient))
                .build()
            val req = Request.Builder().url(embed)
                .header("User-Agent", NativeAnimeParser.USER_AGENT)
                .header("Referer", referer)
                .get().build()
            val body = voeClient.newCall(req).execute().use { it.body?.string().orEmpty() }
            if (body.isEmpty()) {
                ajlLog("AnimeJL Voe: empty response for $embed")
                return@withContext VideoContainer(emptyList())
            }
            val firstScript = Jsoup.parse(body).selectFirst("script")?.data()
            val redirect = firstScript?.let {
                Regex("""window\.location\.href\s*=\s*'([^']+)'""").find(it)?.groupValues?.get(1)
            }
            val page = if (redirect != null && redirect.startsWith("http")) {
                val r2 = Request.Builder().url(redirect)
                    .header("User-Agent", NativeAnimeParser.USER_AGENT)
                    .header("Referer", referer)
                    .get().build()
                voeClient.newCall(r2).execute().use { it.body?.string().orEmpty() }
            } else body
            val encoded = Jsoup.parse(page).selectFirst("script[type=application/json]")?.data()?.trim()
                ?.substringAfter("[\"")?.substringBeforeLast("\"]")
                ?: return@withContext VideoContainer(emptyList()).also {
                    ajlLog("AnimeJL Voe: no application/json script in $embed")
                }
            val json = decryptVoe(encoded) ?: return@withContext VideoContainer(emptyList()).also {
                ajlLog("AnimeJL Voe: decryption failed for $embed")
            }
            val obj = runCatching { Mapper.json.parseToJsonElement(json) as? JsonObject }.getOrNull()
                ?: return@withContext VideoContainer(emptyList()).also {
                    ajlLog("AnimeJL Voe: json parse failed for $embed")
                }
            val m3u8 = (obj["source"] as? JsonPrimitive)?.contentOrNull
            val mp4 = (obj["direct_access_url"] as? JsonPrimitive)?.contentOrNull
            val origin = ajlOrigin(m3u8 ?: mp4 ?: embed)
            val videos = mutableListOf<Video>()
            var audioTracks = emptyList<Track>()
            if (!m3u8.isNullOrBlank()) {
                val hls = ajlResolveHls(m3u8, mapOf("Referer" to origin))
                videos.addAll(hls.videos)
                audioTracks = hls.audioTracks
            }
            if (!mp4.isNullOrBlank()) videos.add(Video(null, VideoType.CONTAINER, FileUrl(mp4, mapOf("Referer" to origin))))
            if (videos.isEmpty()) {
                ajlLog("AnimeJL Voe: payload had no source/mp4 for $embed")
                return@withContext VideoContainer(emptyList())
            }
            ajlLog("AnimeJL Voe: resolved ${videos.size} video(s) for $embed")
            VideoContainer(videos, audioTracks = audioTracks)
        } catch (e: Exception) {
            ajlLog("AnimeJL Voe extract error: ${e.message}")
            VideoContainer(emptyList())
        }
    }

    private fun decryptVoe(p8: String): String? = runCatching {
        val v1 = rot13(p8)
        val v2 = v1.replace(VOE_PATTERN_REGEX, "_")
        val v3 = v2.replace("_", "")
        val v4 = String(Base64.decode(v3, Base64.DEFAULT), Charsets.ISO_8859_1)
        val v5 = charShift(v4, 3)
        val v6 = v5.reversed()
        String(Base64.decode(v6, Base64.DEFAULT), Charsets.ISO_8859_1)
    }.getOrNull()

    private fun rot13(input: String): String = input.map { c ->
        when {
            c in 'A'..'Z' -> ((c - 'A' + 13) % 26 + 'A'.code).toChar()
            c in 'a'..'z' -> ((c - 'a' + 13) % 26 + 'a'.code).toChar()
            else -> c
        }
    }.joinToString("")

    private fun charShift(input: String, shift: Int): String =
        input.map { (it.code - shift).toChar() }.joinToString("")

    companion object {
        private val VOE_PATTERN_REGEX = listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")
            .joinToString("|") { Regex.escape(it) }.toRegex()
    }
}

class AnimeJLUqloadExtractor(override val server: VideoServer) : VideoExtractor() {
    override suspend fun extract(): VideoContainer = withContext(Dispatchers.IO) {
        try {
            val referer = server.extraData?.get("referer") ?: "https://uqload.ws/"
            val page = ajlGet(server.embed.url, referer)
            val doc = Jsoup.parse(page)
            val script = pickScript(doc) { it.contains("sources:") } ?: return@withContext VideoContainer(emptyList()).also {
                ajlLog("AnimeJL Uqload: no sources script in ${server.embed.url}")
            }
            val videoUrl = script.substringAfter("file:\"").substringBefore("\"")
                .takeIf { it.startsWith("http") }
                ?: script.substringAfter("sources: [\"").substringBefore('"')
                    .takeIf { it.startsWith("http") }
                ?: return@withContext VideoContainer(emptyList()).also {
                    ajlLog("AnimeJL Uqload: no url in sources")
                }
            ajlLog("AnimeJL Uqload: ${videoUrl.take(120)}")
            val embedOrigin = ajlOrigin(server.embed.url)
            if (videoUrl.contains(".m3u8", ignoreCase = true)) {
                val hls = ajlResolveHls(videoUrl, mapOf("Referer" to embedOrigin, "Origin" to embedOrigin))
                if (hls.audioTracks.isNotEmpty()) {
                    ajlLog("AnimeJL Uqload: attached ${hls.audioTracks.size} audio track(s)")
                }
                VideoContainer(hls.videos, audioTracks = hls.audioTracks)
            } else {
                VideoContainer(listOf(Video(null, VideoType.CONTAINER, FileUrl(videoUrl, mapOf("Referer" to embedOrigin)))))
            }
        } catch (e: Exception) {
            ajlLog("AnimeJL Uqload extract error: ${e.message}")
            VideoContainer(emptyList())
        }
    }
}

class AnimeJLVidHideExtractor(override val server: VideoServer) : VideoExtractor() {
    override suspend fun extract(): VideoContainer = withContext(Dispatchers.IO) {
        try {
            val pageReferer = server.extraData?.get("referer") ?: ajlOrigin(server.embed.url)
            val page = ajlGet(server.embed.url, pageReferer)
            val doc = Jsoup.parse(page)
            val script = pickScript(doc) { it.contains("m3u8") } ?: return@withContext VideoContainer(emptyList()).also {
                ajlLog("AnimeJL VidHide: no m3u8 script in ${server.embed.url}")
            }
            val master = script.substringAfter("source").substringAfter("file:\"").substringBefore("\"")
                .takeIf { it.isNotBlank() && it.contains(".m3u8", ignoreCase = true) }
                ?: return@withContext VideoContainer(emptyList()).also { ajlLog("AnimeJL VidHide: no master") }
            ajlLog("AnimeJL VidHide: ${master.take(120)}")
            val embed = server.embed.url
            val hls = ajlResolveHls(master, mapOf("Referer" to embed, "Origin" to ajlOrigin(embed)))
            VideoContainer(hls.videos, audioTracks = hls.audioTracks)
        } catch (e: Exception) {
            ajlLog("AnimeJL VidHide extract error: ${e.message}")
            VideoContainer(emptyList())
        }
    }
}

class AnimeJLStreamWishExtractor(override val server: VideoServer) : VideoExtractor() {
    override suspend fun extract(): VideoContainer = withContext(Dispatchers.IO) {
        try {
            val pageReferer = server.extraData?.get("referer") ?: ajlOrigin(server.embed.url)
            val page = ajlGet(server.embed.url, pageReferer)
            val doc = Jsoup.parse(page)
            val script = pickScript(doc) { it.contains("m3u8") } ?: return@withContext VideoContainer(emptyList()).also {
                ajlLog("AnimeJL StreamWish: no m3u8 script in ${server.embed.url}")
            }
            val master = script.substringAfter("source").substringAfter("file:\"").substringBefore("\"")
                .takeIf { it.isNotBlank() && it.contains(".m3u8", ignoreCase = true) }
                ?: return@withContext VideoContainer(emptyList()).also { ajlLog("AnimeJL StreamWish: no master") }
            ajlLog("AnimeJL StreamWish: ${master.take(120)}")
            val embedOrigin = ajlOrigin(server.embed.url)
            val hls = ajlResolveHls(master, mapOf("Referer" to "$embedOrigin/", "Origin" to embedOrigin))
            VideoContainer(hls.videos, audioTracks = hls.audioTracks)
        } catch (e: Exception) {
            ajlLog("AnimeJL StreamWish extract error: ${e.message}")
            VideoContainer(emptyList())
        }
    }
}

private fun pickScript(doc: Document, marker: (String) -> Boolean): String? {
    for (raw in doc.select("script").map { it.data() }) {
        val unpacked = if (raw.contains("eval(function(p,a,c")) {
            JsUnpacker(raw).let { if (it.detect()) it.unpack() ?: raw else raw }
        } else raw
        if (marker(unpacked)) return unpacked
    }
    return null
}

private data class AjlHlsResult(val videos: List<Video>, val audioTracks: List<Track>)

private fun ajlResolveHls(masterUrl: String, headers: Map<String, String>): AjlHlsResult {
    return try {
        val body = ajlGet(masterUrl, headers["Referer"])
        val lines = body.lines()
        val base = URI(masterUrl)

        val audioTracks = mutableListOf<Track>()
        for (line in lines) {
            val attrLine = line.trim()
            if (!attrLine.startsWith("#EXT-X-MEDIA:", ignoreCase = true)) continue
            if (!attrLine.contains("TYPE=AUDIO", ignoreCase = true)) continue
            val name = attrLine.substringAfter("NAME=", "")
                .substringAfter('"', "").substringBefore('"', "")
                .trim().takeIf { it.isNotBlank() }
            val lang = name ?: attrLine.substringAfter("LANGUAGE=", "")
                .substringAfter('"', "").substringBefore('"', "")
                .trim().takeIf { it.isNotBlank() } ?: "und"
            val uri = attrLine.substringAfter("URI=", "")
                .substringAfter('"', "").substringBefore('"', "")
                .trim().takeIf { it.isNotBlank() } ?: continue
            val audioUrl = if (uri.startsWith("http")) uri else base.resolve(uri).toString()
            audioTracks.add(Track(url = audioUrl, lang = lang))
        }

        val videos = mutableListOf<Video>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true)) {
                val quality = Regex("RESOLUTION=\\d+[xX](\\d+)", RegexOption.IGNORE_CASE)
                    .find(line)?.groupValues?.get(1)?.toIntOrNull()
                var j = i + 1
                while (j < lines.size) {
                    val next = lines[j].trim()
                    if (next.isNotEmpty() && !next.startsWith("#")) {
                        val variant = if (next.startsWith("http")) next else base.resolve(next).toString()
                        videos.add(Video(quality, VideoType.M3U8, FileUrl(variant, headers)))
                        break
                    }
                    j++
                }
                i = j
            } else i++
        }
        if (videos.isEmpty()) {
            AjlHlsResult(listOf(Video(null, VideoType.M3U8, FileUrl(masterUrl, headers))), audioTracks)
        } else {
            AjlHlsResult(videos, audioTracks)
        }
    } catch (e: Exception) {
        AjlHlsResult(listOf(Video(null, VideoType.M3U8, FileUrl(masterUrl, headers))), emptyList())
    }
}

private fun ajlUpnsDecrypt(hex: String): String? = try {
    val bytes = hex.trim().chunked(2).mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
    val key = SecretKeySpec("kiemtienmua911ca".toByteArray(Charsets.UTF_8), "AES")
    val iv = IvParameterSpec("1234567890oiuytr".toByteArray(Charsets.UTF_8))
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.DECRYPT_MODE, key, iv)
    String(cipher.doFinal(bytes), Charsets.UTF_8)
} catch (e: Exception) {
    ajlLog("AnimeJL Upns decrypt error: ${e.message}")
    null
}

private const val AJL_WEBVIEW_TIMEOUT_MS = 10_000L
private val AJL_WEBVIEW_SKIP_HOSTS = listOf("mega.nz", "mega.co.nz", "d.tube")

private suspend fun ajlWebViewCatch(embedUrl: String, referer: String?): List<String> =
    withContext(Dispatchers.Main) {
        val activity = currContext() as? Activity ?: return@withContext emptyList()
        val host = runCatching { URI(embedUrl).host?.lowercase() }.getOrNull().orEmpty()
        if (AJL_WEBVIEW_SKIP_HOSTS.any { host == it || host.endsWith(".$it") }) {
            ajlLog("AnimeJL WebView: skipping JS capture for $host")
            return@withContext emptyList()
        }
        val candidates = Collections.synchronizedSet(LinkedHashSet<String>())
        val container = FrameLayout(activity)
        val webView = WebView(activity)
        try {
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.mediaPlaybackRequiresUserGesture = false
            webView.settings.javaScriptCanOpenWindowsAutomatically = false
            webView.webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?
                ): Boolean = false

                override fun onJsAlert(
                    view: WebView?, url: String?, message: String?, result: JsResult?
                ): Boolean {
                    result?.confirm()
                    return true
                }

                override fun onJsConfirm(
                    view: WebView?, url: String?, message: String?, result: JsResult?
                ): Boolean {
                    result?.confirm()
                    return true
                }

                override fun onJsPrompt(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    defaultValue: String?,
                    result: JsPromptResult?
                ): Boolean {
                    result?.confirm("")
                    return true
                }
            }
            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val url = request?.url?.toString().orEmpty()
                    if (isMediaUrl(url) && !isAdUrl(url) && candidates.add(url)) {
                        ajlLog("AnimeJL WebView: captured ${url.take(140)}")
                    }
                    return null
                }
            }
            container.addView(webView, FrameLayout.LayoutParams(1, 1))
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            content?.addView(container)
            webView.loadUrl(embedUrl)
            val deadline = System.currentTimeMillis() + AJL_WEBVIEW_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline && candidates.isEmpty()) {
                delay(250)
            }
        } catch (e: Exception) {
            ajlLog("AnimeJL WebView catch error: ${e.message}")
        } finally {
            runCatching { webView.stopLoading() }
            runCatching { (webView.parent as? ViewGroup)?.removeView(webView) }
            runCatching { (container.parent as? ViewGroup)?.removeView(container) }
            runCatching { webView.destroy() }
        }
        candidates.toList()
    }

private fun isMediaUrl(url: String): Boolean {
    if (url.isEmpty()) return false
    val lower = url.lowercase()
    if (lower.contains(".jpg") || lower.contains(".jpeg") || lower.contains(".png") ||
        lower.contains(".webp") || lower.contains(".svg") || lower.contains(".gif") ||
        lower.contains(".css") || lower.contains(".js") || lower.contains(".ico") ||
        lower.contains("favicon") || lower.endsWith("/")
    ) return false
    return lower.contains(".m3u8") || lower.contains(".mpd") ||
        lower.contains(".mp4") || lower.contains(".webm")
}

private fun isAdUrl(url: String): Boolean {
    val lower = url.lowercase()
    val adMarkers = listOf(
        "doubleclick", "googlesyndication", "googletagmanager", "googlead", "adservice",
        "amazon-adsystem", "outbrain", "taboola", "revcontent", "adform", "adsystem",
        "adsdk", "vast", "vpaid", "adserver", "/ad/", "/ads/", "adshorte", "exoclick",
        "/banner/", "adpush", "/ad?", "=ad", "ads?", "double-click"
    )
    return adMarkers.any { lower.contains(it) }
}

private fun ajlLog(message: String) {
    Log.d("AnimeJL", message)
    Logger.log(message)
}

private fun ajlGet(url: String, referer: String? = null): String {
    val request = Request.Builder().url(url)
        .header("User-Agent", NativeAnimeParser.USER_AGENT)
        .apply { referer?.let { header("Referer", it) } }
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .get().build()
    okHttpClient.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
        return body
    }
}

private fun ajlOrigin(url: String): String = runCatching {
    URI(url).let { "${it.scheme}://${it.authority}" }
}.getOrDefault(url.substringBefore('/', ""))

private fun ajlMediaHeaders(url: String, embed: String, embedOrigin: String): Map<String, String> =
    if (url.contains(".mp4", ignoreCase = true)) mapOf("Referer" to embed)
    else mapOf("Referer" to embed, "Origin" to embedOrigin)

private fun ajlMediaOk(url: String, headers: Map<String, String>): Boolean = try {
    val builder = Request.Builder().url(url)
        .header("User-Agent", NativeAnimeParser.USER_AGENT)
        .header("Accept", "*/*")
    headers.forEach { (k, v) -> builder.header(k, v) }
    if (url.contains(".mp4", ignoreCase = true)) builder.header("Range", "bytes=0-0")
    okHttpClient.newCall(builder.get().build()).execute().use { resp ->
        val statusOk = resp.isSuccessful
        val contentType = resp.header("Content-Type").orEmpty().lowercase()

        statusOk && !contentType.contains("text/html") && !contentType.contains("application/xhtml")
    }
} catch (e: Exception) {
    false
}

private fun ajlHlsUrls(html: String): List<String> = Regex(
    "(?:https?:)?(?:\\\\/|/)[^\"'\\s<>]+?\\.m3u8[^\"'\\s<>]*",
    RegexOption.IGNORE_CASE
).findAll(html)
    .map { it.value.replace("\\/", "/").replace("\\u003d", "=") }
    .map { if (it.startsWith("//")) "https:$it" else it }
    .distinct()
    .toList()
