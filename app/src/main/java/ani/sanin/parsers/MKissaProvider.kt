package ani.sanin.parsers

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import ani.sanin.App
import ani.sanin.FileUrl
import org.jsoup.Jsoup
import ani.sanin.Mapper
import ani.sanin.okHttpClient
import ani.sanin.util.Logger
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URI
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

class MKissaProvider : NativeAnimeParser() {

    override val name = "Mkissa"
    override val saveName = "MKissa"
    override fun isDubAvailableSeparately(sourceLang: Int?): Boolean = true

    override val defaultBaseUrl = "https://mkissa.to"
    private val siteUrl: String get() = baseUrl
    private val apiUrl: String get() = "https://api.mkissa.net"
    private val playerDomain: String get() = "https://allanime.day"

    override val knownServers = listOf(
        "Default", "Ac", "Ak", "Kir", "Rab", "Luf-hls", "Luf-mp4",
        "Si-Hls", "S-mp4", "Ac-Hls", "Uv-mp4", "Pn-Hls", "Fm-Hls", "Vid-mp4", "Player",
    )

    override suspend fun search(query: String): List<ShowResponse> = withContext(Dispatchers.IO) {
        try {
            val body = postGraph(SEARCH_QUERY, buildJsonObject {
                put("search", buildJsonObject {
                    put("allowAdult", true)
                    put("allowUnknown", true)
                    put("query", query)
                })
                put("limit", PAGE_SIZE)
                put("page", 1)
                put("translationType", "sub")
                put("countryOrigin", "ALL")
            })
            val edges = (Mapper.json.parseToJsonElement(body) as? JsonObject)
                ?.get("data") as? JsonObject
                ?: return@withContext emptyList()
            val showEdges = ((edges.get("shows") as? JsonObject)
                ?.get("edges") as? JsonArray) ?: return@withContext emptyList()
            showEdges.mapNotNull { el ->
                val card = (el as? JsonObject) ?: return@mapNotNull null
                val id = card.primitive("_id") ?: return@mapNotNull null
                val title = card.primitive("name") ?: return@mapNotNull null
                if (title.isBlank()) return@mapNotNull null
                val others = buildList {
                    card.primitive("englishName")?.takeIf { it.isNotBlank() }?.let { add(it) }
                    card.primitive("nativeName")?.takeIf { it.isNotBlank() }?.let { add(it) }
                }
                ShowResponse(
                    name = title,
                    link = id,
                    coverUrl = thumbOf(card.primitive("thumbnail")),
                    otherNames = others,
                    extra = mutableMapOf("showId" to id),
                )
            }
        } catch (e: Exception) {
            Logger.log("MKissa search error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun loadEpisodes(
        animeLink: String,
        extra: Map<String, String>?,
        sAnime: SAnime
    ): List<Episode> = withContext(Dispatchers.IO) {
        try {
            val showId = extra?.get("showId") ?: animeLink
            val body = postGraph(EPISODES_QUERY, buildJsonObject {
                put("_id", showId)
            })
            val available = (((Mapper.json.parseToJsonElement(body) as? JsonObject)
                ?.get("data") as? JsonObject)
                ?.get("show") as? JsonObject)
                ?.get("availableEpisodesDetail") as? JsonObject
            val subList = available?.ofStrings("sub").orEmpty()
            val dubList = available?.ofStrings("dub").orEmpty()
            val useDub = selectDub && dubList.isNotEmpty()
            val episodes = if (useDub) dubList else subList
            val translationType = if (useDub) "dub" else "sub"
            Logger.log("MKissa: ${episodes.size} $translationType episode(s) for $showId")
            episodes
                .sortedBy { it.toFloatOrNull() ?: Float.MAX_VALUE }
                .map { ep ->
                    Episode(
                        number = ep,
                        link = ep,
                        title = "Episode $ep",
                        extra = mapOf(
                            "showId" to showId,
                            "translationType" to translationType,
                            "ep" to ep,
                        )
                    )
                }
        } catch (e: Exception) {
            Logger.log("MKissa loadEpisodes error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun loadVideoServers(
        episodeLink: String,
        extra: Map<String, String>?,
        sEpisode: SEpisode
    ): List<VideoServer> = withContext(Dispatchers.IO) {
        try {
            val showId = extra?.get("showId").orEmpty()
            if (showId.isBlank()) return@withContext emptyList()
            val translationType = extra?.get("translationType") ?: "sub"
            val episodeString = extra?.get("ep")?.takeIf { it.isNotBlank() } ?: episodeLink
            val sources = resolveSourceUrls(showId, translationType, episodeString)
            Logger.log(
                "MKissa: ${sources.size} source(s) for $showId ep $episodeString ($translationType)"
            )
            data class Entry(val source: KissaSource, val url: String)

            val duplicateNames = HashMap<String, Int>()
            sources
                .mapNotNull { source ->
                    val url = decryptSource(source.sourceUrl)
                    if (url.isBlank()) null else Entry(source, url)
                }
                .sortedByDescending { it.source.priority }
                .distinctBy { it.url }
                .mapNotNull { entry ->
                    when {
                        entry.url.startsWith("/apivtwo/") -> {
                            val label = hosterLabel(entry.source.sourceName.lowercase())
                                ?: entry.source.sourceName
                            emitServer(label, entry.url, "internal", duplicateNames)
                        }
                        entry.source.type == "player" -> {
                            emitServer("Player", entry.url, "player", duplicateNames)
                        }
                        else -> {
                            val kind = hosterKindOf(entry.url) ?: return@mapNotNull null
                            Logger.log(
                                "MKissa: exposing ${entry.source.sourceName} via $kind " +
                                    "(${entry.url.take(72)})"
                            )
                            emitServer(
                                entry.source.sourceName.ifBlank { kind },
                                entry.url,
                                kind,
                                duplicateNames
                            )
                        }
                    }
                }
        } catch (e: Exception) {
            Logger.log("MKissa loadVideoServers error: ${e.message}")
            emptyList()
        }
    }

    private fun emitServer(
        label: String,
        url: String,
        kind: String,
        duplicateNames: HashMap<String, Int>
    ): VideoServer? {
        if (label.isBlank()) return null
        val count = (duplicateNames[label] ?: 0) + 1
        duplicateNames[label] = count
        val display = if (count > 1) "$label ($count)" else label
        return VideoServer(
            display,
            url,
            mapOf(
                "mkissaKind" to kind,
                "referer" to "$playerDomain/",
                "origin" to playerDomain,
            )
        )
    }

    private fun hosterKindOf(url: String): String? = when {
        url.contains("ok.ru", true) || url.contains("okru", true) -> "okru"
        url.contains("mp4upload", true) -> "mp4upload"
        url.contains("bysekoze", true) || url.contains("filemoon", true) ||
            url.contains("fastmoon", true) || url.contains("moonplayer", true) -> "filemoon"
        url.contains("uns.bio", true) -> "uni"
        url.contains("streamlare", true) -> "streamlare"
        url.contains("dood", true) -> "dood"
        url.contains("wish", true) -> "streamwish"
        else -> null
    }

    override suspend fun getVideoExtractor(server: VideoServer): VideoExtractor {
        val kind = server.extraData?.get("mkissaKind")
        return when (kind) {
            "internal" -> MKissaInternalExtractor(server)
            "okru" -> MKissaOkruExtractor(server)
            "mp4upload" -> MKissaMp4UploadExtractor(server)
            "uni" -> MKissaUniExtractor(server)
            "filemoon" -> MKissaFilemoonExtractor(server)
            "streamwish", "dood", "streamlare" ->
                MKissaCsBridgeExtractor(server, kind)
            else -> super.getVideoExtractor(server)
        }
    }

    

    private fun postGraph(query: String, variables: JsonObject, retries: Int = 3): String {
        val body = buildJsonObject {
            put("query", query)
            put("variables", variables)
        }.toString().toRequestBody("application/json".toMediaType())
        var text = ""
        var attempt = 0
        while (attempt <= retries) {
            val request = Request.Builder().url("$apiUrl/api")
                .header("User-Agent", NativeAnimeParser.USER_AGENT)
                .header("Accept", "*/*")
                .header("Origin", GRAPHQL_ORIGIN)
                .header("Referer", "$GRAPHQL_ORIGIN/")
                .post(body)
                .build()
            text = okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("MKissa API HTTP ${resp.code}")
                resp.body?.string().orEmpty()
            }
            val waitSeconds = RATE_LIMIT_REGEX.find(text)?.groupValues?.get(1)?.toLongOrNull()
                ?: return text
            val wait = (waitSeconds + 1).coerceAtMost(10) * 1000
            Logger.log("MKissa: API throttled, waiting ${wait / 1000}s")
            try {
                Thread.sleep(wait)
            } catch (_: InterruptedException) {
            }
            attempt++
        }
        return text
    }

    private fun JsonObject.primitive(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.ofStrings(key: String): List<String> =
        ((this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull })
            ?: emptyList()

    

    private data class KissaSource(
        val sourceUrl: String,
        val type: String,
        val sourceName: String,
        val priority: Float,
    )

    private val sourceResolverLock = Mutex()

    private suspend fun resolveSourceUrls(
        showId: String,
        translationType: String,
        episodeString: String
    ): List<KissaSource> = withContext(Dispatchers.IO) {
        val result = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
            sourceResolverLock.withLock {
                resolveInWebView(showId, translationType, episodeString)
            }
        }
        if (result == null) {
            Logger.log("MKissa streams resolve timed out after ${RESOLVE_TIMEOUT_MS / 1000}s")
        }
        result ?: emptyList()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun resolveInWebView(
        showId: String,
        translationType: String,
        episodeString: String
    ): List<KissaSource> {
        val context = App.context ?: return emptyList()
        return suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            val finished = AtomicBoolean(false)
            val sawCaptcha = AtomicBoolean(false)
            val apiError = AtomicReference<String?>(null)
            var webView: WebView? = null

            fun buildError(): Exception {
                return if (sawCaptcha.get()) {
                    IOException(
                        "MKissa wants a captcha from this network. Open an episode in WebView, " +
                            "complete the check, then try again."
                    )
                } else {
                    IOException(apiError.get() ?: "stream request failed")
                }
            }

            fun finish(result: List<KissaSource>?, error: Exception?) {
                if (!finished.compareAndSet(false, true)) return
                handler.post { webView?.destroy() }
                if (error != null) {
                    Logger.log("MKissa stream resolve error: ${error.message}")
                    cont.resume(emptyList())
                } else {
                    cont.resume(result ?: emptyList())
                }
            }

            val token = UUID.randomUUID().toString()
            val path = "/anime/$showId/p-$episodeString-$translationType"
            val target = """{"showId":"$showId","episode":"$episodeString","path":"$path",""" +
                """"apiHost":"api.mkissa.net","token":"$token"}"""
            val bridge = MKissaBridge(token) { kind, value ->
                when (kind) {
                    "result" -> {
                        val parsed = runCatching {
                            val array = Mapper.json.parseToJsonElement(value) as? JsonArray
                            array?.mapNotNull { el ->
                                val obj = (el as? JsonObject) ?: return@mapNotNull null
                                val payload = obj.primitive("sourceUrl") ?: return@mapNotNull null
                                KissaSource(
                                    sourceUrl = payload,
                                    type = obj.primitive("type").orEmpty(),
                                    sourceName = obj.primitive("sourceName").orEmpty(),
                                    priority = (obj["priority"] as? JsonPrimitive)
                                        ?.doubleOrNull?.toFloat() ?: 0f,
                                )
                            } ?: emptyList()
                        }.getOrNull().orEmpty()
                        Logger.log("MKissa resolver returned ${parsed.size} source(s): " +
                            parsed.joinToString { "${it.sourceName}[${it.type}](${it.sourceUrl.take(24)})" })
                        finish(parsed, null)
                    }
                    "captcha" -> sawCaptcha.set(true)
                    "apiError" -> {
                        if (value.contains("NEED_CAPTCHA")) {
                            sawCaptcha.set(true)
                        } else if (apiError.getAndSet(value) == null) {
                            val grace = RETRY_AFTER_REGEX.find(value)
                                ?.groupValues?.get(1)?.toLongOrNull()
                                ?.let { (it + 3) * 1000L } ?: 5_000L
                            val reportError = {
                                if (apiError.get() != null) finish(null, buildError())
                            }
                            handler.postDelayed({ reportError() }, grace)
                        }
                    }
                }
            }
            val script = HOOK_SCRIPT.replace("__TARGET__", target)
            handler.post {
                if (finished.get()) return@post
                try {
                    webView = WebView(context.applicationContext).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.blockNetworkImage = true
                        settings.userAgentString = MK_UA
                        addJavascriptInterface(bridge, BRIDGE_NAME)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest
                            ): Boolean {
                                val host = runCatching { request.url.host }.getOrNull().orEmpty()
                                val siteHost = runCatching { URI(siteUrl).host }.getOrNull().orEmpty()
                                return request.isForMainFrame && host != siteHost
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                view.evaluateJavascript(script, null)
                            }
                        }
                        loadUrl("$siteUrl/")
                    }
                } catch (e: Exception) {
                    finish(null, IOException("MKissa could not start a WebView: ${e.message}", e))
                }
            }
            cont.invokeOnCancellation {
                finished.set(true)
                handler.post { webView?.destroy() }
            }
        }
    }

    private class MKissaBridge(
        private val token: String,
        private val onMessage: (kind: String, value: String) -> Unit
    ) {
        @JavascriptInterface
        fun post(token: String, kind: String, value: String) {
            if (token == this.token) onMessage(kind, value)
        }
    }

    

    private fun decryptSource(payload: String): String {
        if (payload.isBlank()) return ""
        val (hexPayload, keyType) = when {
            payload.startsWith("--") -> payload.substring(2) to 3
            payload.startsWith("#-") -> payload.substring(2) to 2
            payload.startsWith("##") -> payload.substring(2) to 1
            payload.startsWith("-#") -> payload.substring(2) to 4
            payload.startsWith("#") -> payload.substring(1) to 0
            else -> payload to -1
        }
        if (hexPayload.isBlank() || hexPayload.length % 2 != 0) return payload
        if (!hexPayload.matches(Regex("^[0-9a-fA-F]+$"))) return payload

        val size = hexPayload.length / 2
        val bytes = ByteArray(size)
        for (i in 0 until size) {
            val hi = Character.digit(hexPayload[i * 2], 16)
            val lo = Character.digit(hexPayload[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return payload
            bytes[i] = ((hi shl 4) or lo).toByte()
        }

        val decode = { mask: Int ->
            val chars = CharArray(size) { idx ->
                ((bytes[idx].toInt() and 0xFF) xor mask).toChar()
            }
            String(chars)
        }

        if (keyType < 0) {
            for (mask in XOR_MASKS) {
                val decoded = decode(mask)
                if (decoded.contains("/clock") || decoded.contains("http")) return decoded
            }
            return payload
        }
        val mask = XOR_MASKS.getOrNull(keyType) ?: return payload
        return decode(mask)
    }

    private fun hosterLabel(sourceName: String): String? =
        HOSTER_MATCHERS.filter { (_, pattern) -> pattern.containsMatchIn(sourceName) }
            .maxByOrNull { (name, _) -> name.length }?.first

    private fun thumbOf(url: String?): String {
        val raw = url?.takeIf { it.isNotBlank() } ?: return defaultImage
        if (raw.startsWith("http")) return raw
        return THUMBNAIL_PROXY_SUB + raw + THUMBNAIL_PARAMS
    }

    companion object {
        private const val PAGE_SIZE = 26
        private const val GRAPHQL_ORIGIN = "https://youtu-chan.com"
        private const val BRIDGE_NAME = "MKissaBridge"
        private const val RESOLVE_TIMEOUT_MS = 45_000L
        private const val MK_UA =
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val THUMBNAIL_PROXY_SUB = "https://wp.youtube-anime.com/aln.youtube-anime.com/"
        private const val THUMBNAIL_PARAMS = "?w=250"

        private val RATE_LIMIT_REGEX =
            Regex("""Too many requests, please try again in (\d+) seconds""")
        private val RETRY_AFTER_REGEX = Regex("""try again in (\d+) seconds""")
        private val HOSTER_NAMES = arrayOf(
            "Default", "Ac", "Ak", "Kir", "Rab", "Luf-hls", "Luf-mp4",
            "Si-Hls", "S-mp4", "Ac-Hls", "Uv-mp4", "Pn-Hls", "Fm-Hls", "Vid-mp4",
        )
        private val HOSTER_MATCHERS = HOSTER_NAMES.map {
            it.lowercase() to Regex("""\b${Regex.escape(it.lowercase())}\b""")
        }

        private val XOR_KEYS = arrayOf(
            "allanimenews",
            "1234567890123456789",
            "1234567890123456789012345",
            "s5feqxw21",
            "feqx1",
        )
        private val XOR_MASKS = XOR_KEYS.map { key ->
            key.fold(0) { mask, ch -> mask xor ch.code }
        }.toIntArray()

        private val SEARCH_QUERY = """
            query(
                %search: SearchInput
                %limit: Int
                %page: Int
                %translationType: VaildTranslationTypeEnumType
                %countryOrigin: VaildCountryOriginEnumType
            ) {
                shows(
                    search: %search
                    limit: %limit
                    page: %page
                    translationType: %translationType
                    countryOrigin: %countryOrigin
                ) {
                    pageInfo {
                        total
                    }
                    edges {
                        _id
                        name
                        thumbnail
                        englishName
                        nativeName
                        slugTime
                    }
                }
            }
        """.trimIndent().replace("%", "$")

        private val EPISODES_QUERY = """
            query (%_id: String!) {
                show(
                    _id: %_id
                ) {
                    _id
                    availableEpisodesDetail
                }
            }
        """.trimIndent().replace("%", "$")

        private val HOOK_SCRIPT = """
            (function () {
              if (window.__mkissaHooked || window._cf_chl_opt) return;
              window.__mkissaHooked = true;
              var bridge = window.MKissaBridge;
              var target = __TARGET__;
              var parse = JSON.parse, stringify = JSON.stringify, done = false;
              function post(kind, value) { bridge.post(target.token, kind, value); }
              function inspect(obj) {
                if (done || !obj || typeof obj !== 'object') return;
                var ep = obj.episode || (obj.data && obj.data.episode);
                if (ep && Array.isArray(ep.sourceUrls) && String(ep.episodeString) === target.episode &&
                    !(ep.show && ep.show._id && ep.show._id !== target.showId)) {
                  done = true;
                  post('result', stringify(ep.sourceUrls));
                  return;
                }
                (Array.isArray(obj.errors) ? obj.errors : []).forEach(function (e) {
                  if (e && e.message && e.path && e.path[0] === 'episode') post('apiError', String(e.message));
                });
              }
              function inspectText(text) {
                if (done || typeof text !== 'string' || !/sourceUrls|"errors"/.test(text)) return;
                try { inspect(parse(text)); } catch (e) {}
              }
              var subtle = window.crypto && window.crypto.subtle;
              if (subtle) {
                var decrypt = subtle.decrypt;
                subtle.decrypt = function () {
                  return decrypt.apply(subtle, arguments).then(function (buf) {
                    try { inspectText(new TextDecoder().decode(buf)); } catch (e) {}
                    return buf;
                  });
                };
              }
              var text = Response.prototype.text, json = Response.prototype.json;
              Response.prototype.text = function () {
                return text.call(this).then(function (t) { inspectText(t); return t; });
              };
              Response.prototype.json = function () {
                return json.call(this).then(function (v) { try { inspect(v); } catch (e) {} return v; });
              };
              var captcha = false;
              setInterval(function () {
                if (!captcha && document.querySelector('#captcha-root iframe, #captcha-root [id^="cf-chl-widget"]')) {
                  captcha = true;
                  post('captcha', '');
                }
              }, 500);
              if (location.pathname === target.path) return;
              var timer = setInterval(function () {
                var started = performance.getEntriesByType('resource').some(function (e) {
                  return e.name.indexOf(target.apiHost) >= 0;
                });
                if (!started) return;
                clearInterval(timer);
                var a = document.createElement('a');
                a.href = target.path;
                document.body.appendChild(a);
                a.click();
              }, 200);
            })();
        """.trimIndent()
    }
}

class MKissaInternalExtractor(override val server: VideoServer) : VideoExtractor() {

    override suspend fun extract(): VideoContainer = withContext(Dispatchers.IO) {
        try {
            val endpoint = "https://allanime.day"
            val raw = server.embed.url
            val jsonUrl = endpoint + raw.replace("/clock?", "/clock.json?")
            val body = mkGet(jsonUrl)
            val parsed = Mapper.json.parseToJsonElement(body) as? JsonObject
                ?: return@withContext VideoContainer(emptyList())
            val links = (parsed.get("links") as? JsonArray)
                ?: return@withContext VideoContainer(emptyList())

            val videos = mutableListOf<Video>()
            val subtitles = mutableListOf<Subtitle>()
            val hlsHeaders = mutableMapOf(
                "Accept" to "*/*",
                "Origin" to endpoint,
                "Referer" to "$endpoint/",
            )
            val dashHeaders = mapOf("Accept" to "*/*")
            val seenUrls = HashSet<String>()

            fun addVideo(video: Video) {
                if (video.file.url.isBlank() || !seenUrls.add(video.file.url)) return
                videos.add(video)
            }

            for (linkEl in links) {
                val link = (linkEl as? JsonObject) ?: continue
                val linkUrl = link.primitive("link") ?: continue
                val resolutionStr = link.primitive("resolutionStr").orEmpty()
                val quality = resolutionOf(resolutionStr)

                (link.get("subtitles") as? JsonArray)?.forEach { t ->
                    val sub = (t as? JsonObject) ?: return@forEach
                    val src = sub.primitive("src") ?: return@forEach
                    val lang = sub.primitive("lang") ?: "en"
                    val label = sub.primitive("label")
                    val name =
                        displayLanguage(lang) + (label?.let { " - $it" } ?: "")
                    if (subtitles.none { it.file.url == src }) {
                        subtitles.add(Subtitle(name, src, mkSubtitleType(src)))
                    }
                }

                when {
                    link.bool("mp4") == true -> addVideo(
                        Video(quality, VideoType.CONTAINER, FileUrl(linkUrl))
                    )

                    link.bool("hls") == true -> {
                        val master = mkGet(linkUrl, "$endpoint/")
                        addVideo(Video(null, VideoType.M3U8, FileUrl(linkUrl, hlsHeaders)))
                        hlsVariantVideos(master, linkUrl, hlsHeaders).forEach(::addVideo)
                    }

                    link.bool("crIframe") == true -> {
                        val streams = (((link.get("portData") as? JsonObject)
                            ?.get("streams")) as? JsonArray) ?: emptyList()
                        for (streamEl in streams) {
                            val stream = (streamEl as? JsonObject) ?: continue
                            val format = stream.primitive("format") ?: continue
                            val streamUrl = stream.primitive("url") ?: continue
                            val hardsub = stream.primitive("hardsub_lang")
                            when (format) {
                                "adaptive_dash" -> addVideo(
                                    Video(null, VideoType.DASH, FileUrl(streamUrl, dashHeaders))
                                )

                                "adaptive_hls" -> {
                                    val master = mkGet(streamUrl, "$endpoint/")
                                    addVideo(
                                        Video(
                                            null,
                                            VideoType.M3U8,
                                            FileUrl(streamUrl, hlsHeaders)
                                        )
                                    )
                                    hlsVariantVideos(master, streamUrl, hlsHeaders).forEach(::addVideo)
                                    Logger.log(
                                        "MKissaInternal: hls stream res=" +
                                            "${resolutionOf(streamUrl)} hardsub=$hardsub"
                                    )
                                }

                                else -> Logger.log("MKissaInternal: unknown format $format")
                            }
                        }
                    }

                    else -> Logger.log(
                        "MKissaInternal: no usable stream in $resolutionStr (${linkUrl.take(60)})"
                    )
                }
            }
            videos.sortByDescending { it.quality ?: 0 }
            VideoContainer(videos, subtitles, audioTracks = emptyList())
        } catch (e: Exception) {
            Logger.log("MKissaInternal extract error: ${e.message}")
            VideoContainer(emptyList())
        }
    }

    private fun hlsVariantVideos(
        master: String,
        masterUrl: String,
        headers: Map<String, String>
    ): List<Video> {
        val base = runCatching { URI(masterUrl) }.getOrNull() ?: return emptyList()
        return Regex(
            """#EXT-X-STREAM-INF:[^\r\n]*?RESOLUTION=(\d+)x(\d+)[^\r\n]*\r?\n([^\r\n]+)""",
            RegexOption.IGNORE_CASE
        ).findAll(master).mapNotNull { m ->
            val height = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            val child = m.groupValues[3].trim()
            val url = if (child.startsWith("http")) child else base.resolve(child).toString()
            Video(height, VideoType.M3U8, FileUrl(url, headers))
        }.distinctBy { it.file.url }.toList()
    }

    private fun resolutionOf(input: String): Int? =
        Regex("""\b(?:\d+x)?(\d{2,4})p?\b""", RegexOption.IGNORE_CASE)
            .find(input)?.groupValues?.get(1)?.toIntOrNull()
}

private fun JsonObject.primitive(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

private fun displayLanguage(code: String): String = runCatching {
    Locale(code).displayLanguage.ifBlank { code }
}.getOrDefault(code)

private fun mkGet(url: String, referer: String? = null): String {
    val request = Request.Builder().url(url)
        .header("User-Agent", NativeAnimeParser.USER_AGENT)
        .apply { referer?.let { header("Referer", it) } }
        .get().build()
    return okHttpClient.newCall(request).execute().use { resp ->
        val body = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw IOException("MKissa HTTP ${resp.code} for $url")
        body
    }
}

private fun mkSubtitleType(url: String): SubtitleType = when {
    url.contains(".ass", true) || url.contains(".ssa", true) -> SubtitleType.ASS
    url.contains(".srt", true) -> SubtitleType.SRT
    else -> SubtitleType.VTT
}


